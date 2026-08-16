# Diagnóstico técnico y de experiencia de usuario

**Fecha:** 15 de agosto de 2026
**Alcance:** `backend-quarkus/` y `frontend-next/`. Se excluyen `backend/` y `frontend/`.
**Método:** lectura completa del código fuente de ambos módulos (≈3 000 líneas Java de
producción, ≈2 900 líneas TS/TSX), ejecución de las suites de pruebas y del verificador de
tipos, e inspección de la configuración de compilación y despliegue.

---

## Resumen ejecutivo

El sistema está **bien construido para el camino feliz y mal preparado para todo lo
demás**. Las 152 pruebas pasan, los tipos compilan y el recorrido funcional completo está
verificado contra APIs reales. Nada de lo que sigue es un error de programación evidente:
son decisiones de diseño que funcionan con un usuario y un pliego, y que fallan de forma
cara o silenciosa con diez usuarios, un proveedor caído o un documento hostil.

Hay tres cosas que un arquitecto debe mirar antes que ninguna otra:

1. **El servicio es un proxy abierto a modelos de lenguaje de pago, sin autenticación,
   sin cuotas y sin límites de tamaño por caso de uso.** Cualquiera que alcance el puerto
   8000 puede gastar el presupuesto de tokens del propietario. (`BE-C4`)
2. **La resiliencia está escrita a mano y bloquea hilos.** Un proveedor lento retiene un
   hilo de trabajo hasta ~15 minutos entre timeout y reintentos; con la concurrencia por
   defecto, doscientas peticiones así dejan el servicio entero —incluido el endpoint de
   salud— sin responder. No hay cortacircuitos, así que un proveedor caído sigue cobrando
   el precio completo de los reintentos en cada petición. (`BE-C3`)
3. **La arquitectura hexagonal no existe todavía.** El paquete `dominio` importa Jackson y
   MicroProfile OpenAPI, contiene DTO de adaptadores (`ProveedorDisponible`, `EstadoSalud`)
   y el servicio de aplicación construye cadenas Markdown para el modelo. No hay ni un solo
   puerto declarado hacia SECOP: el recurso REST inyecta la clase concreta. (`BE-A9`)

En experiencia de usuario, el hallazgo más caro es que **los resultados de operaciones que
cuestan dinero se pierden al navegar**: la búsqueda en SECOP y el resultado de la
validación viven en `useState` local, así que volver atrás los borra y obliga a repetir la
llamada. El más grave en accesibilidad es que **ningún botón ni enlace tiene indicador de
foco visible**, lo que hace la aplicación inoperable con teclado.

### Distribución de hallazgos

| Severidad | Backend | Frontend | Transversal | Total |
|---|---|---|---|---|
| 🔴 Crítica | 4 | 2 | 1 | **7** |
| 🟠 Alta | 8 | 8 | 2 | **18** |
| 🟡 Media | 7 | 5 | 3 | **15** |
| ⚪ Baja | 3 | 3 | 1 | **7** |
| **Total** | **22** | **18** | **7** | **47** |

**Criterio de severidad.** Crítica: pérdida de servicio, de dinero o de datos, o barrera de
acceso total. Alta: comportamiento incorrecto observable o coste de mantenimiento que ya
está frenando el cambio. Media: deuda que encarecerá el siguiente incremento. Baja:
consistencia y pulido.

---

## 1. Backend — Quarkus

### 🔴 BE-C1 · El streaming SSE se ejecuta en el hilo de E/S y lo bloquea

**Evidencia:** `api/ChatResource.java` declara `public Multi<OutboundSseEvent> chat(...)`.
Quarkus REST considera no bloqueante todo método que devuelve `Multi` o `Uni`, y lo despacha
en el *event loop*. Dentro, `AgenteSecop.chat` llama a `ProveedorIA.flujo`, que en
`ia/ProveedorLangChain4j.java:177-181` ejecuta
`modelosFlujoCacheados.computeIfAbsent(nombreModelo, this::construirModeloFlujo)`.

Construir un `StreamingChatModel` levanta un cliente HTTP: resolución DNS, negociación TLS,
creación del pool de conexiones. Todo eso es bloqueante y ocurre sobre el hilo de E/S.

**Impacto:** el *event loop* de Vert.x atiende a *todos* los clientes conectados a ese hilo.
Bloquearlo durante una negociación TLS lenta congela peticiones de otros usuarios que no
tienen nada que ver con el chat. Quarkus emite `BlockedThreadChecker` a partir de 2 s, pero
no aborta: degrada en silencio.

**Principio:** resiliencia; contrato implícito del modelo de concurrencia reactivo.

---

### 🔴 BE-C2 · Caché de modelos sin cota, con clave controlada por el cliente

**Evidencia:** `ia/ProveedorLangChain4j.java:47-49` declara dos `ConcurrentHashMap` sin
límite. La clave es `resolverModelo(peticion.modelo())` (`:67-69`), y `peticion.modelo()`
llega directamente del cuerpo de la petición: `SolicitudChat.modelo` es un `String` libre,
sin `@Pattern` ni lista blanca (`dominio/Solicitudes.java`). La única normalización es
`trim()`.

**Impacto:** cada valor distinto de `modelo` crea una entrada nueva y, con ella, un cliente
HTTP con su propio pool de conexiones y sus hilos. No hay expulsión, no hay cierre, no hay
tope. Un bucle de peticiones con nombres de modelo aleatorios agota memoria y descriptores
de fichero sin necesidad de credenciales válidas —el fallo del proveedor ocurre *después* de
crear y cachear el modelo—. Es un vector de denegación de servicio de coste cero para el
atacante.

Aun sin malicia, el problema existe: el selector del frontend permite escribir el
identificador de modelo a mano (`componentes/SelectorIA.tsx`, campo `personalizado`), y cada
error de tecleo deja un cliente huérfano vivo para siempre.

**Principio:** resiliencia; validación de entrada; gestión de recursos.

---

### 🔴 BE-C3 · Reintentos artesanales y bloqueantes, sin cortacircuitos ni mamparos

**Evidencia:** `ia/ProveedorLangChain4j.java:226-256`.

```java
long espera = (long) (config.esperaBaseMillis() * Math.pow(2, intento - 1)
        + Math.random() * 1000);
Thread.sleep(espera);
```

Con la configuración por defecto (`application.properties`): `timeout-segundos=300`,
`intentos-maximos=3`, `espera-base-millis=2000`.

**Impacto acumulado por petición en el peor caso:** 3 × 300 s de espera del proveedor + 2 s
+ 4 s de retroceso ≈ **15 minutos con un hilo de trabajo retenido**, de los cuales 6
segundos son `Thread.sleep` puro. El pool de trabajo por defecto de Quarkus son 200 hilos;
200 análisis concurrentes lo agotan y a partir de ahí *nada* responde, ni siquiera
`/api/salud`, que comparte el mismo pool.

Faltan además tres mecanismos:

- **Sin cortacircuitos.** Cuando Gemini devuelve 503 de forma sostenida, cada petición
  nueva vuelve a pagar los tres intentos completos. El sistema no aprende que el proveedor
  está caído.
- **Sin mamparos.** La ruta de búsqueda en SECOP (rápida, barata) y la ruta de IA (lenta,
  cara) comparten el mismo pool. La segunda ahoga a la primera.
- **Sin límite global de tiempo por caso de uso.** El timeout es por llamada al proveedor,
  no por petición HTTP.

Y todo ello reimplementa a mano lo que MicroProfile Fault Tolerance —ya disponible en la
plataforma Quarkus que el proyecto importa— resuelve con anotaciones declarativas,
observables y probadas.

**Principio:** KISS (mecanismo artesanal en vez del estándar), resiliencia, SRP (la clase
base del proveedor gestiona política de reintentos).

---

### 🔴 BE-C4 · Servicio sin autenticación que gasta dinero de terceros

**Evidencia:** no hay ninguna extensión de seguridad en `pom.xml`, ninguna anotación
`@Authenticated`/`@RolesAllowed` en `api/`, y ningún filtro de límite de tasa. Los
endpoints `/api/analisis/requisitos`, `/api/propuestas/generar`, `/api/propuestas/validar`,
`/api/procesos/relevancia-ti` y `/api/chat` invocan a un proveedor de pago con la clave del
operador. `application.properties` fija `quarkus.http.port=8000` y CORS a `localhost`, pero
CORS **no** protege: es una política del navegador, irrelevante para `curl`.

**Impacto:** cualquiera con acceso de red al puerto puede consumir el presupuesto de tokens
del propietario, sin límite y sin traza de quién lo hizo. Con `max-tokens=32000` y un límite
de contexto de 800 000 caracteres por petición, el coste unitario de un abuso es alto.

**Principio:** seguridad; el hallazgo se agrava por `BE-M16` (sin cotas de entrada) y
`BE-M15` (sin métricas para siquiera detectarlo).

---

### 🟠 BE-A5 · Fuga del detalle interno del proveedor al cuerpo de la respuesta HTTP

**Evidencia:** `ia/ProveedorLangChain4j.java:302-303`, rama por defecto de `traducir`:

```java
return new ErroresIA.FalloDelProveedor(
        "Error de %s: %s".formatted(etiqueta(), recortar(mensaje)), 502, error);
```

`mensaje` es el resultado de `mensajeCompleto(error)` (`:307-319`), que concatena el mensaje
de la excepción y hasta cuatro causas anidadas. `recortar` deja pasar 300 caracteres. Ese
texto llega íntegro al cliente a través de `api/ManejadorErrores.errorDelAgente`, que lo
serializa en `{"detail": …}`, y de ahí a la interfaz.

**Impacto:** los mensajes de error de los SDK suelen incluir la URL de la petición. En la
API de Google AI Studio —el proveedor por defecto— **la clave viaja en la cadena de
consulta** (`?key=AIza…`). Basta con que una versión de LangChain4j incluya la URL en el
mensaje para publicar la credencial del operador en el navegador de cualquier usuario. Aun
sin ese caso extremo, se exponen nombres de host internos, versiones de biblioteca y
estructura de la petición.

Es especialmente irónico porque la clase existe justamente para *no* filtrar errores crudos:
lo hace bien en las cinco ramas clasificadas y falla en la rama por defecto, que es la que
más probablemente contendrá algo inesperado.

**Principio:** seguridad; fail-safe defaults.

---

### 🟠 BE-A6 · Clasificación de errores por coincidencia de subcadenas

**Evidencia:** `ia/ProveedorLangChain4j.java:264-304`. La política de reintentos depende de
esta clasificación (`esReintentable`, `:258-261`, solo reintenta si el estado traducido es
429 o 502).

```java
if (contiene(minusculas, "not found", "404", "no longer available", "does not exist")) { … }
```

**Impacto:** tres problemas encadenados.

1. **Falsos positivos.** El mensaje concatena hasta cuatro causas; el texto del pliego puede
   aparecer en un error de validación del proveedor. Un anexo técnico de TI que mencione
   «error 404» o «rate limit» reclasifica el fallo y cambia el comportamiento de reintento.
2. **Fragilidad ante versiones.** Cualquier cambio de redacción en el SDK rompe la
   clasificación en silencio: no hay fallo, solo peor comportamiento.
3. **Cero cobertura del efecto.** `ProveedorLangChain4jTest` prueba la *clasificación*
   (`clasificaErrores`, `mensajeDeModeloInexistente`…) pero **ninguna prueba verifica que un
   429 realmente se reintente, que un 401 no se reintente, ni cuántas veces**. La política
   más importante del sistema no tiene red de seguridad.

La información correcta está disponible y tipada: LangChain4j expone el código de estado en
sus excepciones HTTP. La cadena debería ser el último recurso, no el primero.

**Principio:** OCP (añadir un proveedor obliga a ampliar una cadena de `if` compartida),
fiabilidad, testabilidad.

---

### 🟠 BE-A7 · Construcción de SoQL por concatenación de cadenas

**Evidencia:** `secop/SecopCliente.java:177-252`.

```java
private static String likeCrudo(String campo, String valor) {
    return "upper(%s) like upper('%%%s%%')".formatted(campo, escapar(valor));
}
static String escapar(String valor) {
    return valor == null ? "" : valor.replace("'", "''");
}
```

Y para los rangos numéricos (`:202-207`):

```java
clausulas.add("precio_base >= " + f.valorMin());
```

**Impacto:**

- `escapar` solo duplica comillas simples. **`%` y `_` no se escapan**, así que un usuario
  que escriba `100%` en el campo de texto obtiene semántica de comodín en vez de búsqueda
  literal: resultados incorrectos sin ningún aviso.
- `valorMin`/`valorMax` son `Double` y se interpolan con la representación de Java. Un valor
  grande produce `1.0E30`, que SoQL rechaza; el fallo se degrada silenciosamente por la vía
  de `BE-A8`.
- El escapado es artesanal y no hay ninguna prueba que intente romperlo. `SecopClienteFiltroTest`
  verifica que las cláusulas se construyan como se espera, no que no se puedan evadir.

No es inyección SQL clásica —Socrata es de solo lectura y el conjunto de datos es público—
pero sí es construcción insegura de consultas: mismo error de diseño, menor radio de daño
*hoy*. El día que se añada un segundo conjunto de datos o un endpoint autenticado, el radio
crece.

**Principio:** seguridad; separación entre datos y código; SRP.

---

### 🟠 BE-A8 · Degradación silenciosa: una búsqueda filtrada se convierte en una sin filtrar

**Evidencia:** `secop/SecopCliente.java:162-173` usa `null` como valor centinela de fallo:

```java
} catch (RuntimeException e) {
    LOG.warnf("Error consultando SECOP: %s", e.getMessage());
    advertencias.add("SECOP no respondió correctamente: " + recortar(e.getMessage()));
    return null;
}
```

Y `:118-124` reacciona repitiendo la consulta **sin cláusula `WHERE`**:

```java
if (filas == null) {
    advertencias.add("La consulta con filtros falló; se devuelven resultados sin filtrar…");
    filas = Optional.ofNullable(consultar(filtro.limite(), filtro.offset(), null, null, …))
            .orElse(List.of());
}
```

**Impacto:** el usuario pide «procesos de ciberseguridad en Antioquia entre 100 y 500
millones» y recibe **los últimos N procesos de cualquier tipo del país**, con estado HTTP
200 y una advertencia en una lista que la interfaz muestra como un aviso amarillo entre
otros. Es la peor forma de degradación posible: devolver datos plausibles que responden a
una pregunta distinta de la que se hizo. Un usuario apurado los tomará por buenos.

Además, el registro de advertencias filtra el mensaje crudo de la excepción de SECOP al
cliente (`recortar(e.getMessage())`), variante menor de `BE-A5`.

**Correcto sería:** 502 explícito cuando la fuente falla, o una degradación que el usuario
tenga que aceptar activamente, nunca una sustitución tácita de la consulta.

**Principio:** principio de mínima sorpresa; Clean Code (sin valores centinela); honestidad
en la degradación.

---

### 🟠 BE-A9 · La arquitectura hexagonal no existe: el dominio depende de la infraestructura

**Evidencia:** cuatro violaciones concretas de la regla de dependencias.

1. **El dominio importa frameworks.** `dominio/Analisis.java`, `dominio/Propuestas.java`,
   `dominio/Secop.java` y `dominio/Solicitudes.java` importan
   `com.fasterxml.jackson.annotation.*` y
   `org.eclipse.microprofile.openapi.annotations.media.Schema`. El núcleo del negocio no
   compila sin Jackson ni sin OpenAPI.
2. **El dominio contiene DTO de adaptadores.** `dominio/Secop.java` declara
   `ProveedorDisponible` (descriptor de un proveedor de IA, concepto del adaptador de
   salida) y `EstadoSalud` (concepto de operación). Ninguno pertenece al dominio de
   contratación pública.
3. **Inversión de dependencias al revés.** `ia/RegistroProveedores.catalogo()` devuelve
   `dominio.Secop.ProveedorDisponible`: el adaptador de IA depende de un tipo del dominio
   que existe únicamente para servirle a él.
4. **No hay puerto hacia SECOP.** `api/ProcesosResource` inyecta la clase concreta
   `SecopCliente`, no una interfaz. Cambiar de Socrata a otra fuente obliga a tocar el
   recurso REST.

A esto se suma que `servicio/AgenteSecop` —la capa de aplicación— construye cadenas
Markdown con `StringBuilder` y depende de `ObjectMapper`: el formato del prompt es un
detalle del adaptador del modelo, no una regla de negocio.

**Impacto:** hoy es un coste de mantenibilidad; mañana es un bloqueo. No se puede probar el
dominio sin el contenedor, no se puede cambiar el formato de serialización sin tocar el
negocio, y el propio comentario de `dominio/Analisis.java` («son el contrato HTTP *y* el
esquema JSON que se le impone al modelo») documenta el acoplamiento como si fuera una
virtud. Lo fue mientras el sistema era pequeño; deja de serlo en cuanto el contrato HTTP y
el esquema del modelo necesiten divergir —y ya divergen: `EsquemasJson` existe precisamente
para endurecer el esquema del modelo sin tocar el contrato HTTP—.

**Principio:** Clean Architecture (regla de dependencias), DIP, SRP.

---

### 🟠 BE-A10 · `AgenteSecop` es un servicio Dios con lógica de presentación

**Evidencia:** `servicio/AgenteSecop.java`, 261 líneas, cinco casos de uso
(`analizarRequisitos`, `generarPropuesta`, `validarPropuesta`, `priorizarProcesos`, `chat`),
cada uno con su bloque de construcción de texto Markdown. Ejemplo, `:46-61`: doce líneas de
`StringBuilder` antes de la única llamada que importa.

Y `:218-224`, para el chat:

```java
String sistema = Prompts.SISTEMA_BASE
        .replace("""
                ## Formato de salida
                Responde ÚNICAMENTE con un objeto JSON…
                """, "")
        + "\n\n" + Prompts.INSTRUCCION_CHAT;
```

Se elimina un bloque del prompt base **haciendo un `replace` de texto literal de tres
líneas**. Cualquier cambio de espaciado en `Prompts.SISTEMA_BASE` —incluido el que haría un
formateador automático— deja el `replace` sin efecto y el chat empieza a responder JSON en
vez de conversación, sin que falle nada.

**Impacto:** cada caso de uso nuevo engorda la clase; los prompts, que son el activo
funcional más valioso del sistema, no son versionables ni probables por separado; y hay un
acoplamiento frágil e invisible entre dos constantes de texto.

**Principio:** SRP, OCP, KISS.

---

### 🟠 BE-A11 · Llamada al modelo anidada dentro de otra, sin presupuesto de tiempo

**Evidencia:** `servicio/AgenteSecop.java:128-141`. Si `validarPropuesta` no recibe
requisitos estructurados, llama primero a `analizarRequisitos` y luego a la validación: dos
invocaciones secuenciales al modelo dentro de una sola petición HTTP.

**Impacto:** en el peor caso son 2 × 15 minutos (`BE-C3`) = **hasta media hora de petición
HTTP viva**, con un hilo de trabajo retenido todo ese tiempo, sin resultado parcial, sin
progreso y sin posibilidad de cancelar. El frontend, que no pone timeout (`FE-C2`), se queda
girando indefinidamente. Y si la segunda llamada falla, el trabajo —y el coste— de la
primera se tiran.

La interfaz avisa («tarda un poco más», `componentes/vistas/Validar.tsx`), lo cual es
honesto pero insuficiente para media hora.

**Principio:** resiliencia; diseño de operaciones de larga duración.

---

### 🟠 BE-A12 · La configuración de proveedores está cerrada a la extensión

**Evidencia:** `config/ConfiguracionIA.java` declara cinco métodos nominales —`gemini()`,
`openai()`, `anthropic()`, `deepseek()`, `ollama()`— cada uno con su propia interfaz anidada.

**Impacto:** `RegistroProveedores` promete extensibilidad («añadir un proveedor nuevo es
crear una clase, sin tocar este registro»), y la configuración la desmiente: añadir un
proveedor obliga a editar la interfaz de configuración, `application.properties`,
`.env.example`, el README y la propia clase del proveedor. Cinco archivos para lo que el
diseño anunciaba como uno.

SmallRye Config soporta mapas (`Map<String, ProviderSettings>`), que resolverían esto sin
perder tipado ni valores por defecto.

**Principio:** OCP; coherencia entre lo que el diseño promete y lo que cumple.

---

### 🟡 BE-M13 · Duplicación en las cinco implementaciones de proveedor

**Evidencia:** `ia/ProveedorGemini`, `ProveedorOpenAI`, `ProveedorAnthropic`, `ProveedorOllama`
implementan cada uno `construirModelo` y `construirModeloFlujo` con la misma secuencia
—`apiKey`, `modelName`, `temperature`, `maxTokens`, `timeout`, `logRequests`— sobre
constructores distintos. Son diez métodos casi idénticos. `configurado()` y
`motivoNoDisponible()` repiten la misma forma cinco veces.

`ProveedorDeepSeek` sí evita la duplicación heredando de `ProveedorOpenAI`, y el comentario
lo celebra con razón. Pero es herencia por conveniencia de implementación: DeepSeek *no es*
un OpenAI. El día que DeepSeek necesite un comportamiento propio en `estructurado`, la
jerarquía estorba. Composición con una fábrica parametrizada sería igual de compacta y no
ataría los dos proveedores.

**Principio:** DRY; «composición sobre herencia».

---

### 🟡 BE-M14 · Enumeraciones: cinco veces el mismo molde

**Evidencia:** `dominio/Enumeraciones.java`, 167 líneas. Cinco enumeraciones
(`Criticidad`, `EstadoCumplimiento`, `NivelRiesgo`, `TipoRiesgo`, `Veredicto`) que repiten
literalmente el mismo bloque: campo `valor`, constructor, `@JsonValue`, `@JsonCreator` y una
llamada a `Utilidades.buscar`. Unas 130 de las 167 líneas son copia.

El diseño ya tiene la abstracción a medio hacer —`Utilidades.buscar` es genérica— y le falta
el último paso: una interfaz `CodedEnum` y un módulo de Jackson que la aplique.

**Principio:** DRY.

---

### 🟡 BE-M15 · Sin salud estándar, métricas, trazas ni identificador de correlación

**Evidencia:** `pom.xml` no incluye `quarkus-smallrye-health`, `quarkus-micrometer` ni
`quarkus-opentelemetry`. `api/SaludResource` es un endpoint propio en `/api/salud`.

**Impacto:**

- `/api/salud` **no comprueba nada**: informa de qué hay configurado, no de si funciona.
  Devuelve `"ok"` con un proveedor caído y con SECOP inalcanzable. Como sonda de
  disponibilidad para un orquestador es engañosa, y no hay distinción entre *liveness* y
  *readiness*.
- No hay forma de saber cuántas peticiones fallan, cuánto tarda cada proveedor, cuántos
  reintentos se están gastando ni cuántos tokens se consumen. Diagnosticar «el análisis va
  lento» es imposible sin adivinar.
- Sin identificador de correlación, un error que el usuario reporta no se puede localizar
  en el registro.

**Principio:** operabilidad; sin observabilidad no hay resiliencia verificable, solo
resiliencia declarada.

---

### 🟡 BE-M16 · Sin cotas de entrada por caso de uso

**Evidencia:**

- `dominio/Solicitudes.SolicitudRelevancia.procesos`: `@NotEmpty`, sin `@Size(max=…)`.
- `dominio/Solicitudes.SolicitudChat.mensajes`: `@NotEmpty`, sin cota, y `contexto` sin cota.
- `dominio/Solicitudes.SolicitudAnalisis.textoPliego`: `@Size(min = 40)`, **sin máximo**.

El único freno es `exigirTamanoRazonable` en `ia/ProveedorLangChain4j.java:77-86`, con
`LIMITE_CARACTERES = 800_000`.

**Impacto:** ese límite llega tarde y es demasiado alto. Tarde, porque se comprueba después
de deserializar el cuerpo, construir el prompt y serializar el JSON de contexto —todo el
trabajo caro ya está hecho—. Alto, porque 800 000 caracteres son del orden de 200 000 tokens,
por encima de la ventana de la mayoría de modelos: la petición se acepta, se envía, se
factura y falla en el proveedor. El límite debería ser por caso de uso, expresado en el
contrato (`@Size`) y rechazado con 422 antes de tocar nada.

**Principio:** validación en la frontera; economía.

---

### 🟡 BE-M17 · CORS de desarrollo fijado en la configuración base

**Evidencia:** `application.properties`:

```properties
quarkus.http.cors.origins=http://localhost:5173,http://127.0.0.1:5173,http://localhost:3000,http://127.0.0.1:3000
```

Sin perfil `%prod`. Además incluye todavía el puerto 5173 del frontend Vite, que ya no se
usa.

**Impacto:** desplegar tal cual deja el frontend sin funcionar, y la salida habitual bajo
presión es poner `*`. Mejor decidirlo ahora, con un perfil `%prod` que lea el origen de una
variable de entorno y falle al arrancar si no está.

**Principio:** configuración por entorno; seguridad por defecto.

---

### 🟡 BE-M18 · El aislamiento de las pruebas depende de recordar una anotación

**Evidencia:** `PerfilSinCredenciales` debe aplicarse manualmente con `@TestProfile` a cada
clase de prueba que lo necesite (hoy: `RegistroProveedoresTest`, `SaludResourceTest`,
`ProcesosResourceTest`). Simultáneamente, `backend-quarkus/.env` contiene una clave real de
Gemini.

**Impacto:** una clase de prueba nueva que se olvide la anotación usará la clave real del
desarrollador y hará llamadas facturables —o peor, pasará en su máquina y fallará en otra—.
El aislamiento debería ser el valor por defecto del perfil `test`, con un fallo explícito si
alguna clave resuelve a un valor no vacío, no una decisión por clase.

**Principio:** *pit of success*; las pruebas deben ser deterministas por construcción.

---

### 🟡 BE-M19 · La versión está declarada en cuatro sitios, y ya no coinciden

**Evidencia:** `pom.xml` → `0.1.0`; `api/SaludResource.VERSION` → `"0.2.0"`;
`application.properties` → `quarkus.smallrye-openapi.info-version=0.2.0`;
`frontend-next/package.json` → `"0.2.0"`.

**Impacto:** el endpoint de salud y OpenAPI mienten sobre qué artefacto está desplegado, que
es justo la pregunta para la que existen. La versión debe salir del `pom.xml` en tiempo de
compilación.

**Principio:** DRY (una única fuente de verdad).

---

### ⚪ BE-B20 · Mezcla de idiomas en el código

**Evidencia:** paquetes, clases y métodos en español; identificadores mezclados:
`scoreTi`, `senalesTi`, `apiKey`, `urlBase`, `baseUrl`, `maxTokens`, `timeoutSegundos`,
`esperaBaseMillis`, `datasetProcesos`, `nombreModelo`, `Multi<String> flujo`,
`ManejadorErrores.Detalle(String detail)`, `MensajeChat.rol` con valores `"user"` y
`"assistant"`, `EstadoCumplimiento.CUMPLE_PARCIAL` serializado como `cumple_parcial`.

**Impacto:** es el hallazgo de menor riesgo y mayor fricción diaria. Obliga a decidir el
idioma en cada nombre nuevo, y las decisiones inconsistentes se acumulan. Nota importante:
el **contrato HTTP** también está en español (`/api/procesos/buscar`, `textoPliego`), y
cambiarlo tiene un radio de impacto completamente distinto al de renombrar identificadores
internos. Se tratan como dos decisiones separadas en `SPEC-BE-07`.

**Principio:** consistencia; Clean Code (un vocabulario, no dos).

---

### ⚪ BE-B21 · Credencial real en el árbol de trabajo

**Evidencia:** `backend-quarkus/.env` contiene una clave de Google AI (`AIza…`) válida.
Está cubierta por `backend-quarkus/.gitignore`, y el repositorio todavía no es un
repositorio git, así que **no hay exposición en historial**. Pero `ESTADO.md` deja
pendiente el `git init`.

**Impacto:** riesgo latente, no realizado. Antes de versionar o compartir el repositorio:
verificar que `.env` está ignorado desde el primer commit, y rotar la clave si el
directorio se ha compartido de cualquier forma.

**Principio:** gestión de secretos.

---

### ⚪ BE-B22 · Las pruebas cubren análisis sintáctico, no comportamiento bajo fallo

**Evidencia:** 88 pruebas. `ProveedorLangChain4jTest` cubre el recorte de JSON y la
clasificación de errores; `SecopClienteFiltroTest` la construcción de cláusulas;
`EsquemaJsonTest` el endurecimiento del esquema; `ExtractorDocumentosTest` la extracción.

**Lo que no está cubierto:** que un 429 se reintente y un 401 no; el número de reintentos;
el comportamiento del cortacircuitos (no existe); la caché de modelos; el protocolo SSE de
extremo a extremo; y la regla de dependencias entre paquetes. El sistema está probado en lo
que es fácil de probar, no en lo que es peligroso.

**Principio:** las pruebas deben cubrir el riesgo, no la superficie.

---

## 2. Frontend — Next.js

### 🔴 FE-C1 · Sin fronteras de error: una excepción de render tira la aplicación entera

**Evidencia:** no existe ningún `error.tsx`, `global-error.tsx`, `loading.tsx` ni
`not-found.tsx` en `app/`. Tampoco hay `<ErrorBoundary>` en el árbol de componentes.

**Impacto:** el App Router de Next ofrece fronteras de error por segmento precisamente para
esto. Sin ellas, cualquier excepción durante el render —un campo inesperado en la respuesta
del modelo, un `undefined` donde se esperaba una lista— sustituye toda la interfaz por la
pantalla de error genérica de Next y **el espacio de trabajo se pierde**: el pliego cargado,
el análisis pagado y la propuesta generada. El coste de un fallo trivial de render es una
sesión de trabajo entera.

Agrava el riesgo que buena parte de los datos renderizados provienen de un modelo de
lenguaje, es decir, de una fuente que por definición no cumple contratos de forma
determinista.

**Principio:** resiliencia; contención del fallo.

---

### 🔴 FE-C2 · `fetch` sin timeout, sin reintento y sin cancelación

**Evidencia:** `lib/api.ts`, función `pedir`: construye `fetch(...)` con las opciones
recibidas y **ninguna de las siete operaciones del objeto `api` pasa un `AbortSignal`**.
Solo `chatStream` acepta `senal`, y solo `Consultar.tsx` la usa.

**Impacto:** el backend puede tardar legítimamente cinco minutos por llamada y hasta media
hora en `validarPropuesta` (`BE-A11`). Durante todo ese tiempo el usuario ve un botón
girando, sin cancelar, sin estimación y sin saber si sigue vivo. La salida natural es
recargar la página —que en `Analizar` y `Validar` es exactamente lo que no debe hacer,
porque el texto que escribió en el área de texto no está en el espacio persistido hasta que
pulsa el botón—.

Tampoco hay reintento para las lecturas idempotentes: si `/api/proveedores` falla al
arrancar por una condición de carrera con el backend, el selector queda en estado de error
hasta que el usuario pulse «Reintentar» manualmente.

**Principio:** resiliencia; toda operación de red necesita un límite de tiempo.

---

### 🟠 FE-A3 · Los resultados que cuestan dinero se pierden al navegar

**Evidencia:** `componentes/vistas/Buscar.tsx:40-46` guarda `resultado` y `priorizacion` en
`useState` local. `componentes/vistas/Validar.tsx` hace lo mismo con `resultado`. El espacio
de trabajo compartido (`lib/estado.tsx`, `Instantanea`) persiste el proceso seleccionado, el
pliego, el análisis y la propuesta —pero no la búsqueda ni la validación—.

**Impacto:** el modelo mental que el rediseño a rutas promete es «cada paso es una página a
la que puedo volver». No se cumple:

- Buscar → pulsar «Analizar este proceso» → volver atrás = **formulario vacío**. Hay que
  repetir la búsqueda contra SECOP.
- Priorizar con IA (llamada facturada) → navegar → volver = **priorización perdida**.
- Validar (la llamada más cara del sistema, hasta dos invocaciones al modelo) → navegar →
  volver = **veredicto y matriz de cumplimiento perdidos**.

Es una inconsistencia en el propio criterio de persistencia: se conservan las cosas baratas
de recalcular y se pierden las caras.

**Principio:** coherencia del modelo mental; respeto por el trabajo del usuario.

---

### 🟠 FE-A4 · «Compartir una vista por URL» no funciona

**Evidencia:** ninguna ruta lee ni escribe parámetros de consulta. `app/analizar/page.tsx`
renderiza `<Analizar />` sin leer nada de la URL; el estado llega del contexto en memoria.

**Impacto:** `ESTADO.md` presenta como logro de la migración que «se puede compartir una
vista por URL». Enviar `/analizar` a un compañero abre una página vacía. Enviar `/` abre el
formulario de búsqueda sin los filtros. Ni siquiera el propio usuario recupera su búsqueda
al usar el historial del navegador, porque los filtros tampoco están en la URL.

Falta además `/procesos/[id]` para enlazar un proceso concreto, pese a que el backend ya
expone `GET /api/procesos/{idProceso}` y el cliente ya tiene `api.obtenerProceso` —que
**no se usa en ninguna parte del frontend**: es código muerto—.

**Principio:** la URL como estado; coherencia entre lo documentado y lo real.

---

### 🟠 FE-A5 · Ningún botón ni enlace tiene indicador de foco visible

**Evidencia:** `app/globals.css:244-248` define el contorno de foco **solo** para
`input`, `select` y `textarea`:

```css
input:focus, select:focus, textarea:focus {
  outline: 2px solid var(--acento);
  outline-offset: -1px;
}
```

No hay ninguna regla `:focus-visible` en las 638 líneas del archivo. Los estilos de
`button.principal`, `button.secundario`, `a.boton`, `button.enlace`, los enlaces de
navegación y `details > summary` definen `:hover` pero no `:focus`. Y como definen `outline`
implícitamente a través de sus propios estilos de borde, el contorno por defecto del
navegador queda visualmente perdido sobre los fondos de color.

**Impacto:** un usuario que navegue con teclado no puede saber dónde está. La aplicación
tiene 4 pasos de flujo, cada uno con varios botones de acción; sin foco visible es
inoperable. Incumple **WCAG 2.2, criterio 2.4.7 (Focus Visible, nivel AA)** y, por el
grosor mínimo del indicador, también el 2.4.11.

Para una herramienta pensada para contratación pública —donde la accesibilidad de los
proveedores de software es un requisito que esta misma aplicación ayuda a *auditar*, vía la
Resolución 1519 de 2020 que aparece en sus propias sugerencias de chat— es una contradicción
incómoda.

**Principio:** accesibilidad WCAG 2.2 AA.

---

### 🟠 FE-A6 · El chat en streaming no se anuncia a lectores de pantalla

**Evidencia:** `componentes/vistas/Consultar.tsx`, el contenedor `.chat-hilo` y el bloque
`{parcial && <div className="chat-mensaje assistant">{parcial}</div>}` no tienen
`aria-live`, `role="log"` ni `aria-busy`.

**Impacto:** la respuesta del agente aparece progresivamente y un lector de pantalla no
anuncia nada. El usuario ciego pulsa «Enviar» y no recibe ninguna indicación de que algo
está ocurriendo, ni de que ha terminado. Es el caso de uso donde `aria-live` es
imprescindible, y es justo donde falta.

Relacionado: los botones de operación larga (`Analizar requisitos`, `Generar propuesta`,
`Validar cumplimiento`) cambian su etiqueta a «Analizando pliego…» pero no marcan
`aria-busy`, y el cambio de etiqueta de un elemento que no tiene el foco no se anuncia.

**Principio:** accesibilidad WCAG 2.2 AA (4.1.3 Status Messages).

---

### 🟠 FE-A7 · Sin diseño adaptable

**Evidencia:** `app/globals.css`, 638 líneas, contiene exactamente dos consultas de medios:
`@media (prefers-color-scheme: dark)` (`:21`) y `@media (prefers-reduced-motion: reduce)`
(`:614`). **Cero puntos de ruptura por anchura.**

Las rejillas (`repeat(auto-fit, minmax(220px, 1fr))`) se adaptan solas, pero no lo hacen: la
cabecera con cinco enlaces de navegación en una fila, las filas de acciones `.fila`, y las
tablas con `min-width: 700px` (`:448`) dentro de un contenedor con desplazamiento.

**Impacto:** hay que tomar una decisión explícita, no dejarla implícita. Un analista que
revisa oportunidades de contratación en el móvil es un caso de uso perfectamente razonable
para la vista de búsqueda, aunque no lo sea para redactar una propuesta. Hoy la aplicación
no lo soporta ni lo declara.

**Principio:** diseño adaptable; decisiones explícitas.

---

### 🟠 FE-A8 · El Markdown se muestra como texto plano

**Evidencia:** `componentes/vistas/Proponer.tsx`:

```tsx
<Tarjeta titulo="Documento completo (Markdown)">
  <div className="markdown">{propuesta.markdown}</div>
</Tarjeta>
```

React escapa el contenido, así que el usuario ve literalmente `## Alcance técnico` y
`**obligatorio**`. Lo mismo ocurre con `seccion.contenido` en el acordeón de secciones, que
sí está pensado para leerse.

**Impacto:** el resultado principal del caso de uso «generar propuesta» se presenta sin
formato. El botón de copiar y el de descargar sí tienen sentido con Markdown crudo; la
lectura, no.

**Advertencia de seguridad para quien lo arregle:** este contenido lo genera un modelo de
lenguaje a partir de un PDF subido por el usuario, es decir, es una fuente susceptible de
inyección de prompt. La solución **no** puede ser `dangerouslySetInnerHTML`. Debe pasar por
un renderizador con saneamiento (`react-markdown` + `rehype-sanitize`), con enlaces
forzados a `rel="noopener noreferrer"` y sin permitir HTML embebido.

**Principio:** utilidad del resultado; seguridad en el render de contenido no confiable.

---

### 🟠 FE-A9 · Duplicación del mismo patrón asíncrono en las cuatro vistas

**Evidencia:** `Buscar`, `Analizar`, `Proponer` y `Validar` repiten la misma estructura:

```tsx
const [cargando, setCargando] = useState(false);
const [error, setError] = useState<string | null>(null);
try { … } catch (excepcion) {
  setError(excepcion instanceof ErrorApi ? excepcion.message : String(excepcion));
} finally { setCargando(false); }
```

La línea del `catch` aparece **siete veces idéntica** en el frontend. El bloque de subida de
archivo (input oculto + ref + botón + limpieza del `value`) está duplicado literalmente entre
`Analizar.tsx` y `Validar.tsx`.

**Impacto:** más allá de las líneas repetidas, esto es duplicación de *decisión*: el timeout
de `FE-C2`, la cancelación, el reintento y el `aria-busy` de `FE-A6` habría que añadirlos en
siete sitios. Un único `useAsyncAction` los añade en uno.

**Principio:** DRY.

---

### 🟠 FE-A10 · `lib/` mezcla capas y obliga a probar contra `fetch`

**Evidencia:** `lib/api.ts` reúne el transporte HTTP, el catálogo de endpoints y el análisis
del protocolo SSE. Los componentes importan `api` directamente
(`import { api, ErrorApi } from "@/lib/api"` en las cuatro vistas y en `Pie.tsx`).

**Impacto:** no hay puerto, así que probar una vista exige simular `fetch` global —que es lo
que hace `pruebas/ayudas.tsx`—. Las pruebas quedan atadas a detalles de transporte
(cabeceras, forma del cuerpo) en vez de al comportamiento. Cambiar de `fetch` a otro
mecanismo obligaría a reescribir las pruebas de componentes, que no deberían enterarse.

Nótese lo que **sí** está bien: `lib/formato.ts`, `lib/filtros.ts` y `lib/matriz.ts` son
funciones puras sin React, probadas por separado. Ese es exactamente el modelo a extender al
resto.

**Principio:** DIP; hexagonal en el cliente.

---

### 🟡 FE-M11 · La persistencia del espacio de trabajo puede romperse en silencio

**Evidencia:** `lib/estado.tsx` serializa la instantánea completa en cada cambio:

```tsx
useEffect(() => {
  if (!restaurado.current) return;
  try { sessionStorage.setItem(CLAVE_ESPACIO, JSON.stringify(datos)); }
  catch { /* Cuota llena … */ }
}, [datos]);
```

**Impacto:** la instantánea incluye `textoPliego` (hasta 800 000 caracteres), el análisis
completo y la propuesta completa. Con un pliego grande se supera la cuota de ~5 MB de
`sessionStorage`; el `catch` lo absorbe y **la persistencia deja de funcionar sin que nadie
se entere**. El usuario recarga y pierde todo, que es justo lo que el mecanismo existía para
evitar. El comentario reconoce la posibilidad; la interfaz no la comunica.

Falta también recorte: el pliego se reserializa entero en cada cambio del análisis o de la
propuesta.

**Principio:** los fallos silenciosos de un mecanismo de seguridad son peores que no
tenerlo.

---

### 🟡 FE-M12 · No hay forma de borrar el espacio de trabajo

**Evidencia:** `lib/estado.tsx` expone `limpiar: () => setDatos(VACIO)` en el contexto.
`grep` sobre `componentes/` y `app/` no encuentra **ninguna** invocación: es código muerto.

**Impacto:** doble. Como código, es una función pública sin uso (YAGNI). Como producto, es
un vacío real: el usuario carga un pliego confidencial y una propuesta comercial en el
navegador y **no tiene ningún botón para borrarlos**. En un equipo compartido, el siguiente
usuario de la pestaña ve el trabajo del anterior. Se relaciona directamente con `NT-2`.

**Principio:** YAGNI; privacidad por diseño.

---

### 🟡 FE-M13 · Sin paginación pese a que el backend la soporta

**Evidencia:** `FiltroProcesos` incluye `offset` (backend y `lib/tipos.ts`), pero
`Buscar.tsx` nunca lo fija ni ofrece navegación de páginas. El usuario solo puede subir
«Máximo de resultados» hasta 500.

**Impacto:** o se piden pocos resultados y se pierden oportunidades, o se piden 500 y la
página se vuelve una lista inmanejable —sin ordenación, sin filtrado en cliente y sin
virtualización—.

**Principio:** aprovechar la capacidad que ya existe.

---

### 🟡 FE-M14 · `descargar()` tiene dos defectos conocidos de compatibilidad

**Evidencia:** `componentes/vistas/Proponer.tsx`:

```tsx
const enlace = document.createElement("a");
enlace.href = url;
enlace.download = `propuesta-…md`;
enlace.click();
URL.revokeObjectURL(url);
```

**Impacto:** el ancla nunca se añade al documento (Firefox históricamente ignora el `click()`
sobre un elemento desconectado) y la URL del blob se revoca de forma síncrona
inmediatamente después del `click()`, lo que puede cancelar la descarga antes de que
empiece. Es un fallo intermitente y dependiente del navegador: el peor tipo de error para
diagnosticar a partir del reporte de un usuario.

**Principio:** corrección; usar la API como está especificada.

---

### 🟡 FE-M15 · Sin ESLint, sin formateador y sin cabeceras de seguridad

**Evidencia:** no hay configuración de ESLint (`ESTADO.md` ya lo anota: `next lint`
desapareció en Next 16). No hay Prettier. `next.config.ts` no define `headers()`, ni
`poweredByHeader: false`, ni política de seguridad de contenido.

**Impacto:** sin analizador estático, defectos como las dependencias incompletas de un
`useEffect` o un `await` olvidado pasan a revisión humana. Sin CSP, la aplicación —que
renderiza contenido generado por un modelo— no tiene ninguna red de contención si algún día
se introduce un render de HTML.

**Principio:** calidad automatizada; defensa en profundidad.

---

### ⚪ FE-B16 · `api.obtenerProceso` no se usa

Código muerto en `lib/api.ts`. Se resuelve al implementar `/procesos/[id]` (`FE-A4`) o se
elimina.

---

### ⚪ FE-B17 · Mezcla de idiomas

`fijarTextoPliego`, `alFragmento`, `senal`, `bufer`, `lector`, `decodificador`,
`interpretarBloque` junto a `useState`, `useEffect`, `AbortController`. Tipos en español
(`RespuestaAnalisis`) con campos en español y valores en inglés
(`MensajeChat.rol: "user" | "assistant"`).

---

### ⚪ FE-B18 · `lib/tipos.ts` es un espejo manual del backend

203 líneas escritas a mano que replican los records de `dominio/`. Es duplicación de
conocimiento **entre módulos**, y es exactamente el mecanismo que produjo la incompatibilidad
`snake_case`/`camelCase` documentada en `ESTADO.md`. El backend ya publica OpenAPI: los tipos
deberían generarse. Se trata en `SPEC-FE-05`.

---

## 3. Transversal

### 🔴 TR-C1 · Se envían pliegos y propuestas comerciales a terceros sin aviso explícito

**Evidencia:** los casos de uso de análisis, propuesta, validación y chat envían el texto
íntegro del pliego y de la propuesta a Google, OpenAI, Anthropic o DeepSeek, según el
selector. La interfaz no lo dice en ninguna parte. El pie muestra un descargo jurídico
(«no sustituye asesoría jurídica») pero **nada sobre a dónde viajan los datos**.

**Impacto:** la propuesta técnica de un oferente es información comercial sensible antes de
la adjudicación. El usuario tiene derecho a saber que se transmite a un tercero en otra
jurisdicción, y la organización necesita esa decisión documentada. Se desarrolla en
`SPEC-NT-02`.

---

### 🟠 TR-A2 · El repositorio no está versionado

`ESTADO.md` lo deja pendiente. Sin git no hay historial, no hay revisión, no hay CI y no hay
forma de revertir ninguna de las refactorizaciones que estas especificaciones proponen.
**Es el prerrequisito de todo lo demás.**

---

### 🟠 TR-A3 · Sin integración continua

No hay `.github/workflows/` ni equivalente. Las 152 pruebas solo se ejecutan si alguien se
acuerda. Las reglas de arquitectura que introduce `SPEC-BE-01` no valen nada sin un
verificador automático que las imponga.

---

### 🟡 TR-M4 · Sin contenedores ni definición de despliegue

Hay `Dockerfile`s generados por Quarkus, pero no hay imagen del frontend, ni `compose`, ni
manifiestos. La restricción documentada («no hay Docker en este entorno») explica el
presente, no exime de definir el objetivo.

### 🟡 TR-M5 · Documentación de arquitectura inexistente

`README.md` y `ESTADO.md` son buenos, pero describen *qué hay* y *dónde quedó*, no *por qué*.
No hay ADR, no hay diagramas, no hay runbook. Las decisiones fuertes del proyecto —abstracción
propia sobre LangChain4j, selección de proveedor por petición, llamada directa sin proxy—
viven en comentarios de código y en un documento de estado que se sobrescribe.
Se resuelve en `SPEC-DOC-01` y `SPEC-DOC-02`.

### ⚪ TR-B6 · Dos módulos obsoletos en el árbol

`backend/` y `frontend/` siguen presentes y cubiertos por las versiones nuevas. Mantenerlos
confunde a cualquiera que llegue nuevo. Decisión del propietario (`ESTADO.md` pendiente #1);
la recomendación es archivarlos en una etiqueta de git tras el `git init` y borrarlos del
tronco.

### 🟡 TR-M7 · Sin estrategia de pruebas de extremo a extremo

No hay Playwright ni equivalente. `verificar_en_vivo.py` es un script de verificación manual
contra APIs reales, valioso pero no automatizable en CI (consume cuota y depende de la red).

---

## Tabla de cobertura

Cada hallazgo debe estar cerrado por al menos una especificación.

| Hallazgo | Cerrado por |
|---|---|
| BE-C1, BE-C3, BE-A11 | SPEC-BE-02 |
| BE-C2, BE-A6, BE-M13, BE-A12 | SPEC-BE-03 |
| BE-C4, BE-A5, BE-M16, BE-M17, BE-B21 | SPEC-BE-06 |
| BE-A7, BE-A8 | SPEC-BE-04 |
| BE-A9, BE-A10, BE-M14, BE-M19, BE-B22 | SPEC-BE-01 |
| BE-M15, BE-M18 | SPEC-BE-05 |
| BE-B20 | SPEC-BE-07 |
| FE-C1, FE-C2, FE-M11, FE-M14 | SPEC-FE-02 |
| FE-A3, FE-A4, FE-A5, FE-A6, FE-A7, FE-A8, FE-M12, FE-M13 | SPEC-FE-03 |
| FE-A9, FE-A10, FE-B16 | SPEC-FE-01 |
| FE-M15 | SPEC-FE-01, SPEC-FE-04 |
| FE-B17, FE-B18 | SPEC-FE-05 |
| TR-C1 | SPEC-NT-02 |
| TR-A2, TR-A3, TR-M4, TR-M7 | SPEC-NT-03 |
| TR-M5, TR-B6 | SPEC-DOC-01, SPEC-DOC-02 |
