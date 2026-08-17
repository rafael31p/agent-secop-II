# SPEC-BE-08 · Concurrencia: hilos, contención y ciclo de vida de recursos compartidos

| | |
|---|---|
| **Estado** | Implementada en parte (ver §8) |
| **Prioridad** | 🔴 Alta |
| **Cierra** | BE-K1 … BE-K10 (hallazgos nuevos, ver §1) |
| **Depende de** | SPEC-BE-02 (implementada), SPEC-BE-03, SPEC-BE-05 |
| **Esfuerzo** | 4–6 jornadas |
| **Módulo** | `backend-quarkus/` |
| **Línea base** | 164/164 pruebas en verde, 17 de agosto de 2026 |

---

## 0. Qué se validó y qué está bien

Antes del diagnóstico, lo que **no** hay que tocar. La fase 3 dejó el modelo de
concurrencia en una posición mucho mejor que la del diagnóstico inicial:

| Aspecto | Estado |
|---|---|
| Reintentos artesanales con `Thread.sleep` | ✅ Eliminados. No queda ninguno en `src/main` |
| Política declarativa | ✅ `@Timeout` `@Retry` `@CircuitBreaker` `@Bulkhead` `@Fallback`, externalizada a propiedades |
| Aislamiento entre rutas | ✅ Mamparos **separados**: 12 (modelo) y 30 (SECOP). Un incidente de Gemini no deja sin búsqueda |
| Bloqueo del bucle de eventos | ✅ `@Blocking` en `ChatResource`, con el porqué documentado en el Javadoc |
| Caché de modelos | ✅ Acotada (LRU 16) y con cierre al expulsar, frente al `ConcurrentHashMap` sin cota de antes |
| Presupuesto por caso de uso | ✅ 150 s / 180 s / 240 s / 120 s, por encima del timeout por intento |
| Auto-invocación de interceptores | ✅ Bien entendida: `PoliticaDeResiliencia` y `ConsultaResiliente` son beans aparte, y el Javadoc explica por qué |
| Estado compartido del limitador | ✅ `ConcurrentHashMap` + `AtomicInteger`, no `synchronized` a mano |

Ese Javadoc de `PoliticaDeResiliencia` —el que explica que los interceptores no actúan
sobre llamadas internas y que Fault Tolerance no empareja repliegues con firmas genéricas—
documenta dos trampas que solo se descubren fallando. Es material que no hay que perder.

**Lo que sigue son los huecos que quedan.** Todos comparten una raíz: la política de
resiliencia se declaró correctamente sobre las llamadas *estructuradas*, y los tres
territorios que no pasan por ahí —la construcción de clientes, el streaming y la extracción
de documentos— quedaron sin gobierno.

---

## 1. Problemas

### 🔴 BE-K1 · La caché de modelos serializa a todos los hilos del proveedor

`ia/ProveedorLangChain4j.java:69-81` y `:158`, `:246`.

```java
private static <V> Map<String, V> cacheAcotada() {
    return Collections.synchronizedMap(new LinkedHashMap<String, V>(16, 0.75f, true) { … });
}
…
modelosCacheados.computeIfAbsent(nombreModelo, this::construirModelo);
```

`Collections.synchronizedMap` envuelve **todos** los métodos en `synchronized (mutex)`, y la
implementación por defecto de `computeIfAbsent` ejecuta la función de mapeo **dentro** de ese
bloque. La función de mapeo es `construirModelo`, que abre un cliente HTTP: resolución DNS,
negociación TLS, creación del pool de conexiones.

Consecuencias, en orden de gravedad:

1. **Mientras se construye un modelo, ningún otro hilo puede leer la caché de ese
   proveedor** — ni siquiera para un modelo ya cacheado. Con el mamparo en 12, hasta once
   hilos esperando en un monitor por un trabajo que no les incumbe.
2. `removeEldestEntry` invoca `cerrarSiProcede`, que hace `close()` sobre el cliente
   expulsado. **También bajo el mismo monitor.** Cerrar un cliente HTTP puede drenar
   conexiones y bloquear.
3. El primer arranque en frío es el peor caso: nadie tiene nada cacheado y todos los hilos
   convergen en el mismo lock.

Es un punto de serialización global por instancia de proveedor, justo en el camino caliente.

### 🔴 BE-K2 · La expulsión cierra clientes que otros hilos están usando

Mismo bloque. `removeEldestEntry` cierra el modelo expulsado, pero **la referencia que ya
entregó a otros hilos sigue viva**. Una llamada estructurada puede durar hasta 120 s; en ese
tiempo, otras peticiones a modelos distintos pueden empujar la entrada fuera de la caché de
16 y cerrar el cliente por debajo de una llamada en curso.

El fallo resultante —conexión cerrada a mitad de respuesta— se traduciría como
`FalloTransitorio`, se reintentaría, y el reintento reconstruiría el modelo. Es decir: **el
sistema se recupera y nadie se entera de que hay una carrera**, salvo por latencia y coste
duplicados que las métricas atribuirán al proveedor.

`accessOrder = true` agrava el análisis: cada lectura reordena, así que qué entrada es la
víctima depende del entrelazado de los hilos y no es reproducible.

Hoy es difícil de alcanzar —haría falta usar más de 16 modelos distintos a la vez— pero el
identificador de modelo lo elige el cliente y el selector del frontend permite escribirlo a
mano. No es un escenario hipotético, es un escenario poco frecuente.

### 🔴 BE-K3 · El timeout del cliente HTTP (300 s) sobrevive al de Fault Tolerance (120 s)

`application.properties:107` mantiene `agente.ia.timeout-segundos=300`, que es lo que se le
pasa a `.timeout(Duration.ofSeconds(...))` de cada constructor de LangChain4j. La política
declara `@Timeout(120_000 ms)`.

`@Timeout` sobre un método **síncrono** funciona con un vigilante que interrumpe el hilo. Una
lectura de socket bloqueante no responde a `Thread.interrupt()` en la mayoría de los clientes
HTTP. Así que a los 120 s el llamante recibe `TimeoutException` y el hilo sigue dentro de la
llamada hasta que el cliente se rinda a los 300 s.

A partir de ahí hay dos posibilidades, y **ambas son malas**:

- Si el permiso del mamparo se libera con la excepción, la concurrencia real supera los 12
  declarados: se acumulan hilos zombis fuera de todo control.
- Si el permiso se libera cuando la invocación interna termina de verdad, el mamparo se llena
  de llamadas fantasma durante tres minutos y los usuarios reciben «no está respondiendo o
  está saturado» **con el proveedor perfectamente sano**.

Cuál de las dos ocurre depende de detalles de SmallRye que conviene medir, no suponer (§3.1
propone la prueba). Lo que no depende de eso es la causa: **el timeout de la capa de abajo
nunca debe ser mayor que el de la capa de arriba.** El comentario de `application.properties`
explica muy bien por qué el timeout bajó de 300 s a 120 s; lo que quedó pendiente es bajar
también el del cliente que hay debajo.

### 🔴 BE-K4 · El chat no tiene ningún límite de concurrencia

Tres capas y ninguna lo acota:

| Capa | Protección |
|---|---|
| `ChatResource` | `@Blocking`, nada más |
| `ServicioDeChat` | **sin `@Timeout`** — el único caso de uso sin presupuesto |
| `ModeloDeLenguajeResiliente.flujo` | delega sin política, documentado como decisión consciente |

El Javadoc argumenta: «Lo que protege al chat es el mamparo que acota las llamadas
estructuradas —las caras— y el límite de peticiones por clave.»

**La primera mitad no se sostiene.** El `@Bulkhead(12)` está sobre
`PoliticaDeResiliencia.estructurado`; los flujos no pasan por ese método y no consumen sus
permisos. No hay ningún mamparo que cuente conversaciones simultáneas.

**La segunda es un límite de caudal, no de concurrencia.** `agente.seguridad.limites.chat=100`
son cien peticiones por clave y hora; nada impide que las cien se abran a la vez.

La parte del razonamiento que **sí** es correcta y hay que conservar: no se reintenta ni se
aplica cortacircuitos a una respuesta que ya empezó a emitirse. Lo que falta no es `@Retry`,
es una cota de flujos concurrentes.

### 🟠 BE-K5 · Emisor SSE sin contrapresión ni cancelación

`ia/ProveedorLangChain4j.java:250`:

```java
return Multi.createFrom().emitter(emisor -> modelo.chat(solicitud, new StreamingChatResponseHandler() { … }));
```

Sin `BackPressureStrategy`, el emisor usa `BUFFER` **sin cota**. Un cliente lento —o una
pestaña en segundo plano— acumula fragmentos en memoria sin techo, y el modelo emite tan
rápido como el proveedor entregue.

Sin `onCancellation`, cerrar la pestaña **no cancela la llamada al proveedor**: el handler
sigue recibiendo fragmentos, el emisor los descarta y los tokens se siguen facturando por una
respuesta que nadie va a leer.

Estaba especificado en `SPEC-BE-02` §3.4 y quedó sin implementar. Es la única parte de
aquella spec que no llegó al código.

### 🟠 BE-K6 · El identificador de correlación no cruza los límites de hilo

`FiltroCorrelacion` hace `MDC.put` en el filtro de petición y `MDC.remove` en el de
respuesta, y `application.properties:211` incluye `[%X{correlationId}]` en el formato. Pero
**el `pom.xml` no incluye `quarkus-smallrye-context-propagation`**, y el MDC de jboss-logging
es un `ThreadLocal`.

`@PreMatching` con un filtro no bloqueante corre en el bucle de eventos; el método del recurso
lleva `@Blocking` y corre en un hilo de trabajo. Tres consecuencias:

1. **Las líneas de registro del servicio y de los adaptadores salen con `[]`**, porque el
   hilo de trabajo nunca heredó el valor. Justo las líneas que interesan cuando algo falla.
2. **El hilo del bucle de eventos se queda con el valor puesto.** El `MDC.remove` se ejecuta
   en el hilo de la respuesta, que es otro. La siguiente petición que atienda ese hilo del
   bucle antes de pasar por el filtro se registra con el identificador de otra petición —
   exactamente la contaminación que el comentario de `:64-66` dice estar evitando.
3. **En SSE es peor.** El filtro de respuesta corre al *abrir* el flujo, no al cerrarlo, así
   que todo el streaming ocurre sin identificador.

El mecanismo está bien pensado y bien documentado; lo que falta es la propagación que lo hace
funcionar cuando hay más de un hilo.

### 🟠 BE-K7 · El mamparo rechaza sin cola y con un mensaje engañoso

`@Bulkhead(12)` sin `@Asynchronous`. En MicroProfile, `waitingTaskQueue` **solo aplica a
métodos asíncronos**: en el camino síncrono no hay cola, la petición 13 recibe
`BulkheadException` de inmediato.

El `@Fallback` la convierte en: «*{proveedor}* no está respondiendo o está saturado. Prueba
con otro proveedor desde el selector.»

Con doce análisis en vuelo —perfectamente posible con cuatro usuarios— el decimotercero recibe
un mensaje que **culpa al proveedor de una decisión nuestra**, y le sugiere cambiar a otro
proveedor que tiene exactamente el mismo mamparo, porque el cortacircuitos es uno solo para
todos. La sugerencia no puede funcionar.

Falta además `Retry-After`: el cliente no sabe cuándo reintentar. Y `SPEC-FE-03` §3.9 ya tiene
el tratamiento para 429 con `Retry-After`; aquí no hay nada que presentar.

### 🟠 BE-K8 · La extracción de documentos no tiene mamparo

`AnalisisResource.cargarDocumento` lee el archivo entero a `byte[]` (hasta 25 MB) y llama a
`ExtractorDocumentos`, que recorre el PDF **página a página** con PDFBox o carga el DOCX
completo con POI. Es trabajo intensivo en CPU y memoria, sin ninguna cota de concurrencia:
ni mamparo, ni timeout, ni límite distinto del de tasa (60 por clave y hora).

Diez cargas simultáneas de 25 MB son 250 MB de arreglos de bytes vivos más las estructuras de
PDFBox, en un pool de 200 hilos que nada impide que se llenen de extracciones. Es la ruta que
más memoria consume por petición y la única sin gobierno.

### 🟡 BE-K9 · Carrera en el contador del limitador de tasa

`LimitadorDeTasa.consumir:42-47`:

```java
Contador contador = contadores.compute(clave, (k, actual) ->
        actual == null || actual.expirado(ahora) ? new Contador(ahora.plus(VENTANA)) : actual);

int usadas = contador.usos.incrementAndGet();
```

El incremento ocurre **fuera** del `compute`. Entre que `compute` devuelve la referencia y se
ejecuta `incrementAndGet`, otro hilo puede expirar la ventana y sustituir la entrada: el
incremento cae en un contador huérfano y se pierde.

Baja severidad —solo se manifiesta en el cambio de ventana y el propio Javadoc ya acepta la
imprecisión de la ventana fija— pero es una carrera real y se corrige sin coste.

### 🟡 BE-K10 · `limpiarVencidos()` no lo llama nadie

Método público sin ningún invocador en todo el repositorio. El Javadoc declara la intención
—«Sin esto, un cliente puntual queda para siempre»— y esa intención no se cumple.

El crecimiento está acotado por (claves × operaciones), así que no es una fuga real; es código
muerto que promete algo que no ocurre. O se programa, o se borra.

### ⚪ BE-K11 · El presupuesto de hilos no está declarado

`quarkus.thread-pool.max-threads` no aparece en `application.properties`; se usa el valor por
defecto (200). Con mamparos de 12 y 30, el consumo previsto es muy inferior — pero `BE-K3`,
`BE-K4` y `BE-K8` son precisamente las tres rutas que pueden desbordarlo sin que nada lo
declare.

Fijarlo explícitamente convierte un supuesto en un contrato, y hace que el sistema falle de
forma predecible en vez de degradarse.

---

## 2. Decisión

Cinco principios, en orden de importancia:

1. **Ningún recurso compartido se construye con un lock global tomado.**
2. **Un recurso compartido no se cierra mientras alguien puede estar usándolo.** El ciclo de
   vida se gobierna por referencias, no por posición en una caché.
3. **Toda ruta que consume un hilo tiene una cota declarada.** Estructurada, streaming y
   extracción: tres rutas, tres mamparos, ninguna sin gobierno.
4. **Los timeouts son monótonos hacia adentro.** Cliente HTTP ≤ intento ≤ caso de uso ≤
   servidor. Una capa interna nunca espera más que la que la envuelve.
5. **El contexto de diagnóstico sobrevive al cambio de hilo**, o no sirve.

---

## 3. Diseño

### 3.1 Caché de modelos sin contención (BE-K1, BE-K2)

Dos cambios, uno por problema.

**Contra la serialización:** `ConcurrentHashMap` con la construcción fuera del lock del mapa,
usando `computeIfAbsent` sobre un contenedor perezoso. El truco estándar es un `FutureTask`
por clave: solo un hilo construye, los demás esperan **ese** modelo concreto, y el resto de la
caché queda libre.

```java
private final ConcurrentMap<String, CompletableFuture<ChatModel>> modelos =
        new ConcurrentHashMap<>();

private ChatModel modeloPara(String nombre) {
    // computeIfAbsent sobre ConcurrentHashMap bloquea solo el segmento de esa clave, y la
    // función de mapeo es barata: crear un futuro vacío. La construcción del cliente —DNS,
    // TLS, pool— ocurre después, ya fuera del mapa.
    CompletableFuture<ChatModel> futuro = modelos.computeIfAbsent(
            nombre, clave -> new CompletableFuture<>());

    if (!futuro.isDone() && futuro.getNumberOfDependents() == 0) { … }
    // Variante explícita y sin trucos, preferible por legibilidad:
    return futuro.completeAsync(() -> construirModelo(nombre), constructor).join();
}
```

En la práctica conviene no escribirlo a mano. **Caffeine** ya resuelve exactamente esto —carga
por clave sin bloquear el mapa, tamaño máximo, expulsión por acceso y `removalListener`— y
`SPEC-BE-03` §3.2 ya lo proponía. Esta spec adelanta esa decisión:

```java
private final Cache<String, ChatModel> modelos = Caffeine.newBuilder()
        .maximumSize(MAXIMO_MODELOS_CACHEADOS)
        .expireAfterAccess(Duration.ofHours(2))
        .evictionListener((String nombre, ChatModel modelo, RemovalCause causa) ->
                cierreDiferido.programar(modelo))
        .build();
```

**Contra el cierre prematuro:** la expulsión **no cierra de inmediato**. Cierra tras un
periodo de gracia mayor que el peor caso de una llamada en vuelo:

```java
/**
 * El modelo expulsado puede estar en mitad de una llamada de hasta 120 s: otro hilo obtuvo
 * la referencia antes de la expulsión y el cliente HTTP sigue vivo por debajo. Cerrarlo en
 * ese momento aborta esa llamada, que se traduce como fallo transitorio, se reintenta y
 * duplica el coste — sin que nada delate la carrera. El periodo de gracia es el timeout por
 * intento más un margen.
 */
private static final Duration GRACIA_DE_CIERRE = Duration.ofMinutes(3);
```

Alternativa considerada y descartada: contar referencias con `AtomicInteger` y cerrar al
llegar a cero. Es exacto y es bastante más código; el periodo de gracia acota el problema con
una fracción del riesgo. Si algún día la memoria de los clientes importa, se reconsidera.

### 3.2 Timeouts monótonos (BE-K3)

```properties
# El timeout del cliente HTTP debe ser MENOR que el @Timeout de la política. Si es mayor,
# Fault Tolerance devuelve TimeoutException a los 120 s mientras el hilo sigue atrapado en
# una lectura de socket que no responde a interrupt() — y el mamparo queda gobernando una
# concurrencia que ya no refleja la real.
agente.ia.timeout-segundos=100
```

La cadena completa, verificada de dentro hacia fuera:

| Capa | Valor | Regla |
|---|---|---|
| Cliente HTTP LangChain4j | 100 s | < intento |
| `@Timeout` por intento | 120 s | < caso de uso |
| `@Timeout` de caso de uso | 120–240 s | < servidor |
| `quarkus.http.read-timeout` | 300 s | el techo |

Y una prueba que la convierte en invariante en vez de en convención:

```java
@Test
void losTimeoutsSonMonotonosHaciaAdentro() {
    assertThat(config.timeoutSegundos() * 1000L)
            .as("El timeout del cliente HTTP debe ser menor que el de la política por "
                    + "intento; si no, quedan hilos atrapados fuera del control del mamparo")
            .isLessThan(timeoutDeLaPolitica());
}
```

**Y una medición previa** que resuelve la ambigüedad de §1 BE-K3: un WireMock que nunca
responde, doce llamadas concurrentes, y comprobar si la decimotercera pasa a los 120 s o a los
300 s. Sea cual sea el resultado, el arreglo es el mismo; el dato sirve para saber cuánto se
ganó.

### 3.3 El chat, gobernado (BE-K4)

Un mamparo propio, en su propio bean para que el interceptor actúe:

```java
@ApplicationScoped
public class PoliticaDeStreaming {

    /**
     * Los flujos no consumen permisos del mamparo de las llamadas estructuradas: son métodos
     * distintos y los interceptores cuentan por método. Sin este mamparo, las conversaciones
     * simultáneas no tienen más techo que el límite por clave y hora, que es caudal y no
     * concurrencia: nada impide abrir las cien a la vez.
     *
     * No lleva @Retry ni @CircuitBreaker a propósito: no se reintenta una respuesta que ya
     * empezó a emitirse al navegador.
     */
    @Bulkhead(8)
    @Fallback(fallbackMethod = "demasiadasConversaciones")
    public Multi<String> flujo(String sistema, List<Turno> turnos, SeleccionDeModelo seleccion) {
        return delegado.flujo(sistema, turnos, seleccion);
    }
}
```

El mamparo cuenta **aperturas**, no duración: `@Bulkhead` sobre un método que devuelve `Multi`
libera el permiso al retornar, no al completarse el flujo. Para acotar flujos *vivos* hace
falta un semáforo explícito atado al ciclo de vida del `Multi`:

```java
return Multi.createFrom().deferred(() -> {
            if (!permisos.tryAcquire()) {
                return Multi.createFrom().failure(new ErroresIA.ProveedorNoDisponible(
                        "Hay demasiadas conversaciones abiertas. Reintenta en un momento."));
            }
            return delegado.flujo(sistema, turnos, seleccion);
        })
        .onTermination().invoke(permisos::release);   // completado, error o cancelación
```

`onTermination` cubre los tres finales —incluida la cancelación—, que es lo que impide que un
permiso se quede colgado cuando el usuario cierra la pestaña.

Más el presupuesto que falta en el caso de uso, medido como **inactividad** y no como total:
una respuesta larga es legítima, sesenta segundos sin un solo fragmento no lo son.

```java
// ServicioDeChat
return modelo.flujo(...)
        .ifNoItem().after(Duration.ofSeconds(60)).failWith(
                () -> new ErroresIA.FalloTransitorio(
                        "El modelo dejó de responder. Reintenta la consulta.", 504));
```

### 3.4 Contrapresión y cancelación del SSE (BE-K5)

```java
return Multi.createFrom().emitter(
        emisor -> {
            var control = modelo.chat(solicitud, manejador(emisor, nombreModelo));
            // Cerrar la pestaña debe cortar la llamada. Sin esto se siguen facturando
            // tokens de una respuesta que ya nadie va a leer.
            emisor.onTermination(() -> cancelarSiProcede(control));
        },
        BackPressureStrategy.BUFFER)   // explícito, no por omisión
        .capDemandsTo(TOPE_DE_FRAGMENTOS);
```

Con el tope declarado, un cliente lento produce un fallo acotado en vez de crecimiento de
memoria sin techo. `BUFFER` sigue siendo la estrategia correcta —descartar fragmentos
mutilaría la respuesta— pero deja de ser ilimitada y deja de estar implícita.

La cancelación depende de que el modelo de streaming de LangChain4j exponga un asidero. Si
para algún proveedor no lo hace, se documenta esa limitación por proveedor en vez de fingir
que está cubierta.

### 3.5 Propagación del contexto (BE-K6)

```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-smallrye-context-propagation</artifactId>
</dependency>
```

Y tres correcciones en `FiltroCorrelacion`:

1. **Marcar el filtro como bloqueante** para que corra en el mismo hilo de trabajo que el
   recurso, de modo que `put` y `remove` ocurran en el mismo hilo.
2. **Limpiar también en el camino de fallo**, con un `try/finally` en un filtro de
   terminación, no solo en el de respuesta.
3. **Para SSE, no limpiar al abrir el flujo.** El identificador se captura por valor y se
   vuelve a instalar en los puntos de registro del streaming, en lugar de confiar en un
   `ThreadLocal` que ya no está.

El punto 3 es el que reconoce el límite honesto del `ThreadLocal`: los callbacks de
LangChain4j corren en hilos de su propio cliente HTTP, fuera del alcance de cualquier
propagación de Quarkus. Ahí el identificador se pasa **como parámetro**, no por contexto.

Y una prueba que lo fija:

```java
@Test
void elIdentificadorDeCorrelacionLlegaAlRegistroDelServicio() { … }

@Test
void unHiloReutilizadoNoArrastaElIdentificadorDeLaPeticionAnterior() { … }
```

La segunda es la que detecta la contaminación cruzada, que es el defecto con peores
consecuencias: un registro que atribuye un error a la petición equivocada es peor que un
registro sin identificador.

### 3.6 Rechazo honesto del mamparo (BE-K7)

`BulkheadException` deja de compartir repliegue con el cortacircuitos:

```java
@Fallback(fallbackMethod = "noDisponible",
        applyOn = { CircuitBreakerOpenException.class, TimeoutException.class })
```

y `BulkheadException` se traduce en el manejador de errores a un **429 con `Retry-After`**:

> «El servicio está atendiendo el máximo de análisis simultáneos. Reintenta en unos segundos
> — no es un problema del proveedor.»

La distinción importa: culpar al proveedor de un límite propio manda al usuario a cambiar de
proveedor, que no puede ayudarle porque el mamparo es el mismo. Y un 429 con `Retry-After` ya
tiene tratamiento en el cliente (`SPEC-FE-03` §3.9), así que el arreglo del backend llega a la
interfaz sin trabajo adicional.

Se añade además la métrica que hoy falta para calibrar el valor:

```java
registry.gauge("llm.mamparo.disponibles", permisosLibres);
```

Subir de 12 se hace con ese número delante, no por intuición — que es lo que el propio
comentario de `application.properties` pide.

### 3.7 Mamparo para la extracción (BE-K8)

```java
@ApplicationScoped
public class ExtraccionResiliente {

    /**
     * Cuatro extracciones simultáneas. No es un límite de red sino de CPU y memoria: un PDF
     * de 25 MB recorrido página a página con PDFBox es la petición que más recursos consume
     * de todo el servicio, y era la única sin cota.
     */
    @Bulkhead(4)
    @Timeout(value = 60_000, unit = ChronoUnit.MILLIS)
    public TextoDeDocumento extraer(String nombre, byte[] contenido) { … }
}
```

Cuatro y no doce porque el recurso escaso aquí es CPU, no conexiones salientes. El valor se
ajusta con `document.extraction` de `SPEC-BE-05`.

### 3.8 Limitador de tasa (BE-K9, BE-K10)

El incremento entra dentro del `compute`, que es atómico por clave en `ConcurrentHashMap`:

```java
public Veredicto consumir(String cliente, String operacion, int limite) {
    if (limite <= 0) {
        return new Veredicto(true, 0);
    }
    Instant ahora = Instant.now();
    // El incremento va DENTRO del compute: hacerlo fuera permite que otra hebra expire y
    // sustituya la ventana entremedias, y el incremento caiga en un contador huérfano.
    var resultado = contadores.compute(cliente + ' ' + operacion, (clave, actual) -> {
        Contador vigente = (actual == null || actual.expirado(ahora))
                ? new Contador(ahora.plus(VENTANA))
                : actual;
        vigente.usos++;                    // ya no hace falta AtomicInteger
        return vigente;
    });
    …
}
```

Con el incremento dentro del bloque atómico, `AtomicInteger` sobra: la exclusión la da el
propio `compute`. Menos piezas y más correcto.

Y la limpieza se programa, que es lo que el Javadoc ya prometía:

```java
@Scheduled(every = "30m", concurrentExecution = SKIP)
void purgarVentanasVencidas() {
    limitador.limpiarVencidos();
}
```

`concurrentExecution = SKIP` evita solapes si una purga se alarga. Requiere
`quarkus-scheduler`. Si se prefiere no añadir la extensión, la alternativa honesta es
**borrar el método** y documentar que el crecimiento está acotado.

### 3.9 Presupuesto de hilos declarado (BE-K11)

```properties
# Presupuesto explícito. Con los mamparos actuales —12 modelo + 8 chat + 30 SECOP + 4
# extracción = 54 en el peor caso— sobra de largo, y ese margen es deliberado: deja sitio a
# las peticiones baratas (salud, proveedores, búsqueda cacheada) cuando las caras están al
# tope. Fijarlo hace que el sistema falle de forma predecible en lugar de degradarse.
quarkus.thread-pool.max-threads=120
quarkus.thread-pool.queue-size=200
```

Bajar de 200 a 120 no reduce la capacidad útil —los mamparos ya la acotan en 54— y hace que un
desbordamiento se manifieste antes y más claro.

---

## 4. Plan de ejecución

| Paso | Contenido | Riesgo |
|---|---|---|
| 1 | Medir el comportamiento real de `@Timeout` + mamparo con un WireMock que no responde (§3.2). | Nulo |
| 2 | Timeouts monótonos + prueba de invariante. Cierra `BE-K3`. | Bajo |
| 3 | Contrapresión explícita y `onTermination` en el emisor SSE. Cierra `BE-K5`. | Bajo |
| 4 | Mamparo de streaming + semáforo por flujo vivo + timeout de inactividad. Cierra `BE-K4`. | Medio |
| 5 | `BulkheadException` → 429 con `Retry-After`. Cierra `BE-K7`. | Bajo |
| 6 | Mamparo de extracción. Cierra `BE-K8`. | Bajo |
| 7 | Caché con Caffeine + cierre diferido. Cierra `BE-K1` y `BE-K2`. | **Medio-alto** |
| 8 | Propagación de contexto + correcciones del filtro. Cierra `BE-K6`. | Medio |
| 9 | Limitador: incremento atómico + purga programada. Cierra `BE-K9` y `BE-K10`. | Bajo |
| 10 | Presupuesto de hilos declarado. Cierra `BE-K11`. | Bajo |

El paso 1 va primero porque es medición y no cambia nada: su resultado dice cuánto valen los
pasos 2 y 7. El paso 7 es el de mayor riesgo y conviene aislarlo en su propia entrega.

Los pasos 2, 3 y 5 juntos son menos de una jornada y cierran los dos hallazgos con
consecuencia económica directa —hilos atrapados y tokens facturados a nadie—.

---

## 5. Criterios de aceptación

1. Con doce análisis en vuelo sobre un proveedor que no responde, el hilo que atiende
   `/q/health/ready` responde en menos de 100 ms.
2. `agente.ia.timeout-segundos` es menor que el `@Timeout` por intento, verificado por una
   prueba que falla si alguien lo sube.
3. Una prueba con veinte peticiones concurrentes a **modelos distintos** demuestra que
   ninguna llamada en vuelo se aborta por expulsión de la caché.
4. Una prueba con cincuenta peticiones concurrentes al **mismo modelo** demuestra que el
   modelo se construye **una sola vez** y que ningún hilo espera por la construcción de otro
   modelo distinto.
5. Existe un límite de conversaciones simultáneas, y superarlo produce un mensaje que no
   culpa al proveedor.
6. Cancelar un flujo SSE desde el cliente libera el permiso y cancela la llamada al proveedor,
   verificado contando peticiones en WireMock.
7. Un cliente que no consume el flujo no hace crecer la memoria sin límite: el buffer tiene
   tope y superarlo produce un error acotado.
8. `BulkheadException` produce 429 con `Retry-After`, no 503 con el nombre del proveedor.
9. La extracción de documentos tiene mamparo y timeout; diez cargas simultáneas de 25 MB no
   agotan el pool de trabajo.
10. El identificador de correlación aparece en las líneas de registro del servicio y del
    adaptador, y existe una prueba de que un hilo reutilizado no arrastra el de la petición
    anterior.
11. `limpiarVencidos()` se ejecuta periódicamente o no existe.
12. Las 164 pruebas siguen en verde, y ninguna aserción de contrato se ha relajado.

---

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| Caffeine es una dependencia nueva. | Ya está en el árbol transitivo de Quarkus (`quarkus-cache` la usa) y `SPEC-BE-03` ya la proponía. La alternativa artesanal con `CompletableFuture` es correcta pero tiene más superficie de error. |
| El periodo de gracia retiene clientes tres minutos más. | Con 16 entradas y clientes ligeros, el coste de memoria es despreciable frente a abortar una llamada en curso. |
| Bajar el timeout del cliente a 100 s corta análisis legítimos largos. | El `@Timeout` de 120 s ya los cortaba: lo único que cambia es **dónde** se corta y que el hilo se libera de verdad. Se mide antes con `llm.peticion`. |
| El mamparo de streaming en 8 rechaza conversaciones legítimas. | Ocho conversaciones simultáneas es mucho para el uso previsto. Se calibra con la métrica nueva y se sube sin desplegar. |
| Las pruebas de concurrencia son intermitentes. | Se construyen con `CountDownLatch` y esperas con condición, nunca con `sleep`. Una prueba intermitente se arregla o se borra: no se tolera. |
| El paso 7 introduce un defecto sutil en el camino caliente. | Entrega aislada, con los criterios 3 y 4 como red, y las 164 pruebas existentes en verde. |

---

## 7. Fuera de alcance

- **Hilos virtuales (`@RunOnVirtualThread`).** Java 25 los tiene y son tentadores para las
  rutas bloqueantes, pero cambian el modelo de fijación de hilos bajo `synchronized` y bajo
  los clientes HTTP de LangChain4j. Merecen su propia evaluación con medición, no un cambio
  de anotación. Esta spec deja el camino despejado al eliminar los locks globales (`BE-K1`),
  que es precisamente lo que hoy haría a los hilos virtuales contraproducentes.
- **Trabajos asíncronos con sondeo de estado.** Es la respuesta correcta si los análisis
  superan sistemáticamente los dos minutos, y es un cambio de producto.
- **Límite de tasa distribuido.** El actual es por instancia y está documentado; solo importa
  al escalar horizontalmente.
- **Caché de respuestas del modelo.**

---

## 8. Estado de ejecución

Aplicado en la misma entrega que la fase 3, a raíz de la revisión del PR #2. Se ordenó por
lo que corregía código recién introducido y por lo que era barato e inequívoco; lo de mayor
riesgo se deja aislado, como pedía el §4 de esta misma spec.

| Hallazgo | Estado |
|---|---|
| BE-K3 · Timeouts monótonos | ✅ Cliente a 100 s, más `TimeoutsMonotonosTest` como invariante |
| BE-K4 · Chat sin cota | ✅ `PoliticaDeStreaming`: semáforo por flujo vivo + timeout de inactividad |
| BE-K5 · SSE sin contrapresión ni fin | ✅ Buffer acotado y `onTermination` que deja de emitir |
| BE-K7 · Mamparo engañoso | ✅ `BulkheadException` → 429 con `Retry-After`, sin culpar al proveedor |
| BE-K8 · Extracción sin mamparo | ✅ `ExtraccionResiliente`: 4 concurrentes, 60 s |
| BE-K9 · Carrera del limitador | ✅ Incremento dentro del `compute`; el `AtomicInteger` sobraba |
| BE-K10 · `limpiarVencidos()` huérfano | ✅ `PurgaDelLimitador`, cada 30 min con `SKIP` |
| BE-K11 · Presupuesto de hilos | ✅ 120 hilos, 200 en cola, declarado con su cuenta |
| BE-K6 · Correlación entre hilos | ⚠️ **No reproduce.** Ver §8.1 |
| BE-K1, BE-K2 · Caché de modelos | ⏳ Pendiente. Ver §8.2 |

### 8.1 BE-K6 no reproduce: el contexto sí cruza

La premisa era que el MDC es un `ThreadLocal` y que el filtro y el recurso corren en hilos
distintos, de modo que las líneas del servicio saldrían sin identificador. La primera mitad
es cierta y la conclusión no: **Quarkus propaga el contexto de diagnóstico por el contexto
duplicado de la petición**, sin necesidad de `quarkus-smallrye-context-propagation`.

Medido, no razonado:

```
filtro    hilo=vert.x-eventloop-thread-2  mdc=peticion-alfa
mapeador  hilo=executor-thread-1          mdc=peticion-alfa
```

`CorrelacionEntreHilosTest` lo deja fijado en las dos direcciones: que el identificador
llega a las líneas del adaptador, y que cinco peticiones seguidas sobre hilos reutilizados
no arrastran el identificador de la anterior —la contaminación cruzada, que es el defecto
con peores consecuencias—.

**El desvío que sí costó tiempo, y que conviene no repetir:** el contexto **no** se puede
leer del `LogRecord`. Su copia la rellena la cadena de manejadores más tarde, así que
`ExtLogRecord.getMdc()` devuelve nulo y hace creer que la propagación está rota cuando no lo
está. Hay que leerlo en el momento de escribir la línea, desde la misma fachada que usa la
aplicación. Se estuvo a un paso de añadir una dependencia para arreglar algo que funcionaba.

Lo que **sí** queda abierto de este hallazgo es el punto 3: en SSE, los callbacks de
LangChain4j corren en hilos de su propio cliente HTTP, fuera del alcance de cualquier
propagación. Ahí el identificador tendría que pasarse como parámetro.

### 8.2 BE-K1 y BE-K2 quedan para su propia entrega

Por la razón que da el §4 de esta spec: es el paso de mayor riesgo, toca el camino caliente
y merece aislarse. Añadir Caffeine y el cierre diferido en la misma entrega que otros ocho
cambios habría hecho imposible atribuir una regresión.

Sigue siendo la deuda más seria de las que quedan: `Collections.synchronizedMap` ejecuta la
función de `computeIfAbsent` dentro del monitor, así que construir un modelo —DNS, TLS,
pool— serializa a todos los hilos de ese proveedor.

### 8.3 Un hallazgo que esta spec no tenía

`LimitadorDeTasa.java` contenía un **byte nulo crudo** como separador de la clave
(`cliente + '\0' + operacion`, escrito con el carácter y no con el escape). Compilaba y
funcionaba, y como separador es incluso una buena elección. El problema es otro: un NUL
dentro de un archivo hace que git lo clasifique como **binario**, así que `git diff` no
muestra nada y el archivo no se puede revisar en una solicitud de cambios. Estaba
commiteado desde el principio. Sustituido por el escape `'\0'`, misma semántica y archivo
legible.
