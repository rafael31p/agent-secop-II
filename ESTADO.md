# Estado del proyecto — 18 de agosto de 2026

Punto de retomada para la próxima sesión.

## Dónde quedó cada cosa

| Componente | Ruta | Estado |
|---|---|---|
| Backend Java (Quarkus) | `backend-quarkus/` | **Terminado y verificado**: 213/213 pruebas, verificación en vivo y cadena completa coherente. |
| Frontend Next.js | `frontend-next/` | **Terminado y verificado**: 75/75 pruebas y recorrido completo en el navegador. |
| Backend Python (FastAPI) | `backend/` | Versión anterior. Funciona, ya es redundante. |
| Frontend React (Vite) | `frontend/` | Versión anterior. **No sirve contra el backend Quarkus** (ver abajo). |

**Los dos backends no exponen el mismo contrato**, al contrario de lo que decía este
documento: el de Python serializa en `snake_case` y el de Quarkus en `camelCase`. Por eso
el frontend viejo solo funciona contra el backend viejo. Se descubrió al empezar la
migración, comparando `frontend/src/types.ts` con los records de `dominio/`.

Ambos backends usan el puerto 8000: solo uno a la vez.

## Fases 0, 1, 2 y 3 del plan de mejora: ejecutadas

El repositorio ya es git, con CI y flujo GitFlow, y las cuatro primeras fases del
[plan](specs/01-PLAN-DE-MEJORA.md) están hechas. Publicado en GitHub bajo GPL-3.0.

**Fase 0 · Red de seguridad**

| | Qué quedó |
|---|---|
| 0.1 | Primer commit con las suites en verde; verificado que los `.env` no entran |
| 0.2 | `backend/` y `frontend/` fuera del tronco, recuperables en `v0.2.0-legacy` |
| 0.3 | CI con `mvnw verify`, tipos, lint, formato, pruebas, compilación y gitleaks |
| 0.4 | ESLint y Prettier, **en modo aviso**; 8 avisos contados, ninguno silenciado |
| 0.5 | ArchUnit con las infracciones congeladas: la deuda no puede crecer |

**Fase 1 · Contención del daño** — los cuatro hallazgos críticos, cerrados:

| | Qué quedó |
|---|---|
| 1.1 | Ningún texto de un sistema externo llega al navegador; identificador de error en su lugar |
| 1.2 | Cotas de entrada por caso de uso, rechazadas con 422 antes de construir nada |
| 1.3 | Identificador de modelo validado y caché de modelos acotada |
| 1.4 | `@Blocking` en el chat: ya no se bloquea el bucle de eventos |
| 1.5 | CORS por perfil, con fallo al arrancar en producción si falta |
| 1.6 | Clave de API y límite de consumo por clave y hora |
| 1.7 | `error.tsx` y `global-error.tsx`, con rescate del espacio de trabajo |
| 1.8 | Timeout y cancelación en todas las llamadas, con botón «Cancelar» |
| 1.9 | Foco visible (`:focus-visible`) y salto al contenido |
| 1.10 | `aria-live` en el chat y `role="status"` en las operaciones largas |

Estado de las suites al cerrar esa fase: **140/140 backend · 74/74 frontend**.

Verificado en el navegador tras los cambios: búsqueda, cancelación de una
priorización sin dejar error, chat en streaming completo, foco visible con
teclado y atributos ARIA presentes en el DOM.

## Fase 2 · Arquitectura hexagonal: cerrada

Los nueve pasos de SPEC-BE-01 §4, hechos. **La deuda congelada de ArchUnit pasó de
482 a cero y el almacén se borró**: las reglas ya no admiten ni una infracción, así
que una violación nueva rompe la compilación en el acto.

    domain/       modelo y reglas de negocio, sin una sola dependencia externa
    application/  port/in (casos de uso), port/out (puertos), service (5 servicios)
    adapter/      in/rest (HTTP, DTO, mapeadores), out/llm, out/document

Criterios de aceptación de la spec, todos cumplidos: dominio sin imports de
framework; prueba de reglas de negocio en JUnit puro en 92 ms; `adapter.in.rest`
sin depender de `application.service`; `Enumeraciones.java` desaparecido con los
códigos por cable idénticos; ningún servicio por encima de 64 líneas; la versión
sale del `pom.xml`; y las pruebas existentes pasan sin tocar sus aserciones.

Pendiente de la fase, menor: los prompts siguen siendo constantes Java agrupadas
tras un puerto, no plantillas Qute (punto 2.5). El defecto que ese punto quería
cerrar —la composición frágil— ya está resuelto por otra vía.

### Cuatro hallazgos de la fase que conviene no olvidar

**El esquema del modelo sale de los nombres de constante y de campo.** LangChain4j
lo deriva de ahí, no de los códigos de serialización, y los prompts le piden al
modelo esos mismos nombres en español. Por eso las enumeraciones y los campos del
dominio **no se pueden renombrar a inglés** —lo que la fase 6 quiere hacer— hasta
que el adaptador de salida tenga su propio tipo de carga útil (SPEC-BE-01 §3.3).
`EsquemaDelModeloTest` lo vigila.

**El chat le estaba pidiendo JSON al modelo.** Se componía quitando un bloque del
prompt base con un `replace` contra una copia del literal, y la copia había dejado
de coincidir. Componer quitando es frágil por construcción y falla en silencio;
ahora se compone añadiendo.

**Un módulo de Jackson declarado como bean CDI no se instala.** Se descubre y se
puede inyectar, pero no llega al `ObjectMapper`. Hay que usar
`ObjectMapperCustomizer`. Las 106 pruebas de entonces pasaban igual porque ninguna
afirmaba el formato por cable de una enumeración.

**`out/` en un `.gitignore` casa a cualquier profundidad.** Dejó seis archivos de
`application/port/out` y `adapter/out` fuera del repositorio sin ninguna señal
local: compilaban y las pruebas pasaban. Solo lo detectó CI, compilando desde un
clon limpio.

## Fase 3 · Resiliencia y observabilidad: cerrada

Los ocho puntos, hechos. Lo que cambia de fondo es **dónde vive la política**: ya no es un
bucle escrito a mano dentro del proveedor, sino un decorador declarado sobre el puerto y
configurable sin recompilar.

    ModeloDeLenguaje                   ← lo que inyectan los casos de uso
      ModeloDeLenguajeResiliente         devuelve el tipo pedido
        PoliticaDeResiliencia            @Retry @Timeout @CircuitBreaker @Bulkhead @Fallback
          ModeloDeLenguajeMedido         latencia, resultado y consumo estimado
            ModeloDeLenguajeLangChain4j  la llamada real

| | Qué quedó |
|---|---|
| 3.1 | `conReintentos` borrado; MicroProfile Fault Tolerance en su lugar. **Ningún `Thread.sleep` en `src/main/java`** |
| 3.2 | Decoradores sobre el puerto: resiliencia y métricas se heredan por composición |
| 3.3 | Presupuesto por caso de uso (validación 240 s, análisis 150 s, propuesta 180 s, priorización 120 s) |
| 3.4 | `/q/health/live` y `/q/health/ready` con sondas que comprueban, no que recitan |
| 3.5 | Micrometer en `/q/metrics`: latencia por caso de uso, consumo estimado, y gratis las de Fault Tolerance |
| 3.6 | `X-Correlation-Id` validado, devuelto en toda respuesta, en el cuerpo del error y en cada línea de registro |
| 3.7 | 24 pruebas nuevas con WireMock que **cuentan peticiones al proveedor** |
| 3.8 | Aislamiento de pruebas por defecto, con guardián que falla si alguna clave resuelve no vacía |

Estado de las suites: **213/213 backend · 75/75 frontend**.

### Cinco hallazgos de la fase que conviene no olvidar

**LangChain4j reintenta por su cuenta.** Con `conReintentos` ya borrado y dos intentos
declarados, el proveedor falso recibió **seis** peticiones: la política se multiplicaba por
tres. Los cuatro proveedores llevan ahora `.maxRetries(0)`. Solo se detectó porque las
pruebas cuentan peticiones; un `assertThrows` habría pasado en verde.

**Sobrescribir por configuración cambia el número, no la unidad.** Con
`delayUnit = SECONDS`, un `Retry/delay=2000` pide dos mil segundos. El arranque falló y por
eso se vio; el mismo error en `Timeout/value` habría arrancado tan campante con un timeout
de treinta y tres horas. Todo declara ahora `MILLIS`.

**`@Fallback` no se empareja con una firma genérica**, y los interceptores de CDI no actúan
sobre llamadas internas. De ahí que la política viva en un bean aparte con la firma borrada
(`PoliticaDeResiliencia`) y el decorador haga el `cast`.

**Las métricas también reciben entrada del usuario.** `/q/metrics` mostraba
`proveedor="inventado"`: bastaba un bucle pidiendo proveedores al azar para llenar el
almacén de series inútiles. El riesgo estaba anotado para el identificador de modelo y no
para el campo de al lado.

**La prueba que vigila las credenciales las imprimía.** `assertEquals("", clave)` publicaba
la clave entera del `.env` en su mensaje de fallo, es decir, en el registro de CI, y
justamente el día en que hubiera una clave real que filtrar. Ahora afirma sobre la
longitud.

### Verificado contra una caída real de Gemini

Durante la verificación en vivo el proveedor devolvió `503 UNAVAILABLE — high demand` de
verdad, y la política entera se ejercitó sin montar nada: tres reintentos gastados, cuatro
fallos contados, circuito abierto, `/q/health/ready` en `DOWN` con `cortacircuitos: open`,
respuestas inmediatas en vez de tres intentos por petición, y `POST /api/procesos/buscar`
respondiendo con normalidad durante todo el episodio —que es el objetivo central de la
fase—. El circuito se cerró solo al recuperarse el proveedor.

Además, `verificar_en_vivo.py` pasa sus 8 secciones y `verificar_flujo_completo.py`
confirma que la cadena buscar → priorizar → analizar → proponer → validar sigue siendo
coherente de extremo a extremo.

## Hotfix 0.2.1 · Importes y entrada hostil

Cuatro puntos de la fase 7 adelantados, porque uno de ellos no era endurecimiento sino una
**corrección de algo que fallaba todos los días**. Detalle en
[SPEC-SEC-01](specs/seguridad/SPEC-SEC-01-auditoria-de-seguridad.md) §8.

| | Qué quedó |
|---|---|
| 7.1 | Importes a `BigDecimal` con `toPlainString` en SoQL (SEC-1) |
| 7.2 | Cotas de descompresión de POI al arranque, en todos los perfiles (SEC-3) |
| 7.3 | `registrar-peticiones=true` impide arrancar en producción (SEC-8) |
| 7.4 | Regex de marcas de página acotada a `[ 	]`, con prueba de tiempo (SEC-6) |

### El defecto que fallaba todos los días

`valorMin` era un `Double` y se concatenaba en la consulta. **Java pasa a notación
científica a partir de diez millones**, así que la cláusula salía `precio_base >= 1.0E7` y
Socrata la rechazaba. Entonces `consultar` capturaba la excepción, devolvía `null` y
`buscar` reintentaba **sin cláusula `WHERE`**: quien pedía «procesos entre 100 y 500
millones» recibía los últimos procesos de cualquier tipo del país, con HTTP 200.

En contratación pública colombiana diez millones es un contrato pequeño y el frontend avanza
de millón en millón, así que no era un caso extremo: era el caso normal.

### Tres pruebas llevaban el defecto dentro

Y es lo que más conviene recordar de esta entrega:

1. **`rangoDeValor`** usaba uno y cinco millones, las dos por debajo del umbral. Verde desde
   el principio sobre un defecto activo.
2. **`mapeaFilaReal`** afirmaba `assertEquals(1_500_000_000.0, proceso.valor())`, es decir,
   daba por bueno el `Double` `1.5E9` como valor esperado.
3. **`ProcesosResourceTest`** afirmaba `.body("procesos[0].valor", is(1.5E9f))`: la respuesta
   HTTP **también** llevaba notación científica. Es JSON válido y el navegador lo interpreta
   igual, así que no rompía nada, pero era la misma raíz asomando por el otro extremo.

Ninguna detectaba el problema; las tres lo documentaban como correcto.

### Y una prueba mía que tampoco probaba nada

La primera versión de la prueba de SEC-6 usaba 800 000 espacios **sin saltos de línea**, y el
patrón defectuoso la pasaba en 8 ms. El coste cuadrático necesita muchas **líneas**: como
`\s` incluye el salto, cada inicio de línea puede consumir hasta el final del texto. Con
50 000 líneas de espacios —200 KB— el patrón anterior **seguía corriendo tras ocho segundos**
y el acotado tarda 23 ms. La prueba usa ahora esa entrada.

### Verificado en el jar

SEC-8 se comprobó donde se comprueban las otras validaciones de arranque, no con una prueba:
con `registrar-peticiones=true` el arranque en producción falla con el motivo escrito, y con
`false` arranca en dos segundos.

## Revisión del PR #2: lo que salió de ahí

Tres comentarios en la solicitud de cambios, y una spec nueva (`SPEC-BE-08`) que llegó a la
vez. De todo ello salieron cuatro defectos reales que ninguna prueba tenía.

### 1. Las excepciones, una clase por error

`ErroresIA` era una clase con nueve excepciones anidadas y el código HTTP dentro de la base.
Ahora son `adapter/out/llm/error/` —jerarquía sellada, una clase por error, sin códigos
HTTP— y `adapter/in/rest/error/` —un `ExceptionMapper` por excepción sobre una base que
define el proceso de respuesta una sola vez—.

**El defecto que destapó:** con un solo manejador para toda la jerarquía, lo que no heredara
de ella caía en el 500 sin que nadie se enterara. Pasaba: `domain.shared.PeticionInvalida`
se movió al dominio en la fase 2 y se quedó sin traducir, así que
`POST /api/propuestas/generar` sin requisitos ni pliego devolvía **500 Internal Server
Error** sin `detail` ni identificador. Comprobado contra el servicio real antes de
arreglarlo.

También desapareció una confusión latente: había **dos** clases `PeticionInvalida` a un
import de distancia, con códigos distintos. La del adaptador se llama ahora
`IdentificadorDeModeloInvalido`, que es lo que era.

### 2. Inyección en registros

`domain.shared.Texto.paraRegistro` sanea todo lo que viene de fuera antes de registrarlo:
el nombre de proveedor de la petición, la respuesta del modelo, el mensaje de PDFBox al
leer un archivo subido. Un salto de línea en cualquiera de ellos permitía **fabricar una
línea de auditoría** en un archivo de texto plano.

`InyeccionEnRegistrosTest` no comprueba el saneador —eso verificaría el saneador, no que se
use— sino **el registro real**, capturándolo con un enganche al `Logger` raíz.

### 3. TOON en el material de entrada

Las tres listas que se le mandan al modelo iban como JSON con sangrado, repitiendo los
nombres de campo en cada elemento. Ahora van en TOON tabular: la cabecera declara los campos
y el número de filas una sola vez. **35 % menos de caracteres, medido** (`SPEC-BE-09`).

### 4. Concurrencia (`SPEC-BE-08`)

Ocho de los once hallazgos, aplicados: timeouts monótonos hacia adentro con prueba de
invariante, cota de conversaciones simultáneas, contrapresión y fin del SSE, mamparo de
extracción, carrera del limitador, purga programada y presupuesto de hilos declarado.

**Dos resultados que conviene recordar:**

- **BE-K6 no reproduce.** La spec suponía que el identificador de correlación no cruzaba de
  hilo. Sí cruza: Quarkus lo propaga por el contexto duplicado. Medido —filtro en
  `vert.x-eventloop-thread-2`, mapeador en `executor-thread-1`, mismo valor— y fijado con
  `CorrelacionEntreHilosTest`. Se estuvo a un paso de añadir una dependencia para arreglar
  algo que funcionaba: el contexto **no** se puede leer del `LogRecord`, hay que leerlo al
  escribir la línea.
- **Un byte nulo crudo en `LimitadorDeTasa.java`**, commiteado desde el principio. Compilaba
  y como separador de clave era buena idea; el problema es que git clasificaba el archivo
  como binario y `git diff` no mostraba nada. Sustituido por el escape textual.

### 5. La caché de modelos (BE-K1 y BE-K2)

Entregada aparte, como pedía la spec por ser el paso de mayor riesgo.
`Collections.synchronizedMap` ejecutaba la construcción del cliente —DNS, TLS, pool— dentro
del monitor, así que **construir un modelo detenía a todos los hilos de ese proveedor**,
incluso a los que pedían uno ya cacheado. Y la expulsión cerraba clientes que otros hilos
podían estar usando en mitad de una llamada de dos minutos.

Ahora es Caffeine —bloqueo por clave— más `CierreDiferido`, que espera tres minutos antes
de cerrar lo expulsado. Se descartó contar referencias: es exacto y es mucho más código
para acotar lo mismo.

Dos cosas que cambian con Caffeine y conviene no olvidar: **no es LRU estricto** (usa
W-TinyLFU y puede rechazar la entrada nueva), y **la expulsión es amortizada**. La primera
versión de la prueba pasaba por el motivo equivocado justamente por lo primero.

### 6. Y una calibración que solo salió midiendo

Con todo lo demás arreglado, la verificación en vivo dejó el cortacircuitos **abierto
mientras todas las llamadas funcionaban**: `maxRetriesReached` en cero —ninguna petición se
rindió— y aun así 6 fallos contados de 13. La causa es el orden que fija MicroProfile:
`Retry` envuelve a `CircuitBreaker`, así que **el circuito cuenta intentos, no llamadas**, y
el plan gratuito de Gemini falla el 40 % de los intentos en horas punta.

Abrir el circuito ahí es peor que no tenerlo: retira treinta segundos un servicio que los
reintentos estaban rescatando. Recalibrado a 20 de ventana y 0,8 de proporción, con el
número delante. Es para lo que servían las métricas.

**Pendiente:** con anotaciones no hay forma de que el circuito cuente llamadas en vez de
intentos. Si algún día importa, la salida es la API programática de SmallRye.

## Frontend Next.js: cerrado

Migrado de React/Vite a Next.js 16 con App Router. Lo que cambió de fondo, más allá del
framework:

- **Cada paso del flujo es una ruta** (`/`, `/analizar`, `/proponer`, `/validar`,
  `/consultar`) en lugar de un estado de `App.tsx`. Funciona el historial del navegador y
  se puede compartir una vista por URL.
- **Selector de proveedor y modelo** en la cabecera, alimentado por `GET /api/proveedores`.
  Era lo que faltaba para que la función que se construyó en el backend fuera usable. Los
  proveedores sin credenciales se listan deshabilitados y con el motivo.
- **El espacio de trabajo se respalda en `sessionStorage`.** Con rutas propias recargar deja
  de ser improbable, y perder el análisis en un F5 significa gastar otra llamada al modelo.
- **Tipos en `camelCase`**, espejo de los records del backend Quarkus.
- **64 pruebas** con Vitest + Testing Library, sobre el reensamblado de SSE, la traducción
  de errores, la limpieza de filtros vacíos, el orden de la matriz y la persistencia.

Verificado en el navegador de extremo a extremo contra el backend real: búsqueda en SECOP,
análisis de un pliego con Gemini, persistencia tras recargar, chat en streaming y las cinco
rutas. Sin errores ni avisos de hidratación en consola.

Una prueba encontró un defecto real: la vista de chat descartaba el texto que devuelve
`chatStream` y dependía solo del callback de fragmentos. Corregido en `Consultar.tsx`.

## Backend Quarkus: cerrado

Las dos incógnitas que quedaron abiertas el 6 de agosto están resueltas.

**Pruebas unitarias — 88/88 pasan.** El arreglo del `.env` filtrándose al perfil de
pruebas (`src/test/java/co/agentesecop/PerfilSinCredenciales.java`, aplicado con
`@TestProfile` a `RegistroProveedoresTest`, `SaludResourceTest` y `ProcesosResourceTest`)
quedó confirmado.

**Campos vacíos en el análisis — arreglado y verificado contra la API real.** Gemini
devolvía el resumen y los riesgos pero dejaba `requisitos` vacío y `recomendacion` nulo,
porque LangChain4j deriva el esquema del record dejando `required: []` y el modelo puede
omitir cualquier campo sin que nada falle. El arreglo es `co.agentesecop.ia.EsquemasJson`,
que recorre el esquema y marca todo como obligatorio. Ahora `verificar_en_vivo.py` pasa
sus 7 secciones, incluida «extrae requisitos» (5 requisitos, antes 0).

Para repetir la verificación:

```bash
cd backend-quarkus
./mvnw test
./mvnw package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar   # puerto 8000
python verificar_en_vivo.py                    # en otra terminal
```

## Decisiones tomadas (para no repetir la discusión)

- **Abstracción de IA**: interfaz propia `ProveedorIA` + LangChain4j como librería, no como
  extensión de Quarkus. El motivo es que el proveedor y el modelo se eligen **por
  petición**, y las extensiones los cablean por configuración.
- **Cinco proveedores**: Gemini, OpenAI, Anthropic, DeepSeek y Ollama. DeepSeek hereda de
  la implementación de OpenAI porque su API es compatible; solo cambia la URL base.
- **Selección en tiempo de ejecución**: cualquier solicitud acepta `proveedor` y `modelo`
  opcionales. `GET /api/proveedores` devuelve el catálogo para armar el selector.
- **El frontend llama al backend directo, sin proxy de Next.** Un proxy intermedio puede
  almacenar en búfer la respuesta del chat y romper el streaming. El CORS del backend ya
  autoriza el puerto 3000.

## Restricciones del entorno

- **No hay Docker.** Por eso las pruebas usan WireMock como librería en la misma JVM y no
  Testcontainers. La búsqueda de documentación de Quarkus vía MCP tampoco funciona.
- **Plan gratuito de Gemini**: los modelos `pro` devuelven 429 y `gemini-2.5-flash` está
  retirado para claves nuevas. Solo funcionan los `flash` de la serie 3.x.
- Java 25 (GraalVM), Maven 3.9.15, Node 24.

## Pendiente

Lo que sigue del plan, en orden:

1. **Fase 4 · Frontend** (6-10 jornadas). Incluye `useAsyncAction`, que absorbe la
   cancelación que la fase 1 dejó a medio camino, y persistir búsqueda y validación.
   Conviene añadirle leer y mostrar el `correlationId` del error, que el backend ya
   devuelve y el frontend todavía ignora.
2. **Fase 5 · Documentación y diagramas**, y **Fase 6 · Migración a inglés**.

Y un asunto que el plan marca como **bloqueante y no técnico**: `SPEC-NT-02` (qué datos
salen hacia terceros, con qué base y con qué aviso). Hasta que esté decidido y comunicado,
la herramienta no debería usarse con un pliego real de un cliente real.

## Deuda registrada, para que no se olvide

- **ArchUnit ya no congela nada.** El almacén desapareció al pagar las 482
  infracciones. Cinco reglas activas verifican la dirección de las dependencias en
  cada compilación.
- **ESLint deja 8 avisos** en el frontend, siete del mismo patrón: restaurar estado
  en un efecto. Lo paga el reducer de los puntos 4.2 y 4.3.
- **El límite de tasa es por instancia**, porque su estado vive en memoria. Con un
  solo proceso es exacto; escalar horizontalmente exigiría un almacén compartido.
- **`ia/`, `secop/` y `config/` siguen fuera del árbol `adapter/`.** Son
  adaptadores de hecho y las reglas los tratan como tales; falta recolocarlos.
- **El cortacircuitos del modelo es uno y agregado, no uno por proveedor.**
  MicroProfile lo asocia a un método, no a un argumento: si Gemini abre el circuito,
  una petición dirigida a OpenAI también se repliega durante el reposo. Tenerlos
  separados exigiría construirlos con la API programática y renunciar a declararlos.
- **Cerrar la pestaña no detiene la llamada al modelo.** LangChain4j no expone un asa
  para abortar una respuesta en curso, así que se siguen facturando tokens que nadie
  leerá.
- **Sin OpenTelemetry ni registro JSON.** Ambos estaban en SPEC-BE-05 y se dejan para
  cuando exista un colector que los consuma; encenderlos hoy sería complejidad sin
  destinatario.
