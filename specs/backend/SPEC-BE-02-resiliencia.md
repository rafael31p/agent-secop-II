# SPEC-BE-02 · Resiliencia: timeouts, reintentos, cortacircuitos y mamparos

| | |
|---|---|
| **Estado** | Implementada (fase 3) |
| **Prioridad** | 🔴 Alta |
| **Cierra** | BE-C1, BE-C3, BE-A11, BE-B22 (parcial) |
| **Depende de** | SPEC-BE-01 (los decoradores se aplican sobre puertos) |
| **Esfuerzo** | 5–8 jornadas |
| **Módulo** | `backend-quarkus/` |

---

## 1. Problema

El sistema depende de dos servicios externos que fallan de forma rutinaria —proveedores de
modelos de lenguaje en plan gratuito y la API abierta de datos.gov.co— y su estrategia ante
el fallo está escrita a mano en un solo sitio, bloquea hilos y no aprende.

### 1.1 El coste real de un hilo

`ia/ProveedorLangChain4j.java:226-256`, con la configuración por defecto
(`timeout-segundos=300`, `intentos-maximos=3`, `espera-base-millis=2000`):

```java
long espera = (long) (config.esperaBaseMillis() * Math.pow(2, intento - 1)
        + Math.random() * 1000);
Thread.sleep(espera);
```

| Concepto | Peor caso |
|---|---|
| Intento 1 | 300 s |
| Retroceso 1 | 2–3 s (`Thread.sleep`) |
| Intento 2 | 300 s |
| Retroceso 2 | 4–5 s (`Thread.sleep`) |
| Intento 3 | 300 s |
| **Total** | **≈ 15 min con un hilo de trabajo retenido** |

Y en `validarPropuesta` sin requisitos estructurados
(`servicio/AgenteSecop.java:128-141`), son **dos** invocaciones secuenciales: hasta media
hora de petición HTTP viva.

El pool de trabajo por defecto de Quarkus son 200 hilos. 200 análisis simultáneos lo agotan
y a partir de ese punto **nada** responde, incluida `/api/salud`, que comparte el mismo pool
y es justo lo que un orquestador consultaría para decidir si reiniciar.

### 1.2 Lo que falta

- **Cortacircuitos.** Con Gemini caído, la petición 1 000 paga los mismos tres intentos que
  la primera. El sistema no aprende.
- **Mamparo.** Buscar en SECOP (rápido, gratis) y analizar un pliego (lento, caro) comparten
  pool. El segundo ahoga al primero.
- **Presupuesto de tiempo por petición.** El timeout es por llamada al proveedor, no por
  caso de uso.
- **Bloqueo del event loop.** `ChatResource.chat` devuelve `Multi`, así que Quarkus lo
  ejecuta en el hilo de E/S; dentro, la construcción del modelo abre un cliente HTTP —DNS,
  TLS, pool— de forma bloqueante (`BE-C1`).
- **Pruebas.** Ninguna verifica que un 429 se reintente ni que un 401 no lo haga.

### 1.3 Y todo ello reimplementa el estándar

MicroProfile Fault Tolerance está disponible en la plataforma Quarkus que el proyecto ya
importa. `conReintentos` es unas 30 líneas de código propio que hace peor —bloqueando,
sin métricas, sin cortacircuitos— lo que una anotación hace mejor. Es el caso de libro de
violación de KISS: lo artesanal funciona, y aun así es la opción equivocada.

---

## 2. Decisión

Tres decisiones, en este orden de importancia:

1. **La política de resiliencia se declara, no se programa.** MicroProfile Fault Tolerance
   (`quarkus-smallrye-fault-tolerance`) con todos los parámetros externalizados a
   configuración.
2. **La resiliencia es un decorador sobre el puerto, no una responsabilidad de la
   implementación.** Un proveedor de modelos nuevo no vuelve a escribir política de
   reintentos; la hereda por composición.
3. **Ninguna espera bloquea un hilo de plataforma.** Los reintentos usan el planificador de
   Fault Tolerance; los hilos virtuales de Java 25 (`@RunOnVirtualThread`) cubren el resto.

Se descarta Resilience4j directamente: MicroProfile ya está en la plataforma, se integra con
Micrometer sin trabajo adicional y con la configuración de Quarkus. Añadir otra biblioteca
sería duplicar el mecanismo.

---

## 3. Diseño

### 3.1 Los decoradores

El puerto queda limpio y las políticas se apilan por composición. Cada decorador tiene una
sola responsabilidad y se puede probar aislado —lo contrario de la clase base actual, que
mezcla caché, reintentos, esquema y traducción de errores—.

```
LanguageModelPort  (application/port/out)
        ▲
        │ implementa
ResilientLanguageModel        @Retry @Timeout @CircuitBreaker @Bulkhead @Fallback
        │ delega en
MeteredLanguageModel          métricas por proveedor y modelo (SPEC-BE-05)
        │ delega en
LangChain4jLanguageModel      la llamada real (SPEC-BE-03)
```

```java
@ApplicationScoped
@Resilient                                   // calificador CDI
public class ResilientLanguageModel implements LanguageModelPort {

    private final LanguageModelPort delegate;

    @Override
    @Timeout(value = 120, unit = ChronoUnit.SECONDS)
    @Retry(maxRetries = 2,
           delay = 2, delayUnit = ChronoUnit.SECONDS,
           jitter = 1, jitterDelayUnit = ChronoUnit.SECONDS,
           retryOn = TransientProviderException.class,
           abortOn = { InvalidCredentialsException.class,
                       UnknownModelException.class,
                       ContentBlockedException.class })
    @CircuitBreaker(requestVolumeThreshold = 8,
                    failureRatio = 0.5,
                    delay = 30, delayUnit = ChronoUnit.SECONDS,
                    successThreshold = 2,
                    failOn = TransientProviderException.class)
    @Bulkhead(value = 12, waitingTaskQueue = 24)
    @Fallback(fallbackMethod = "unavailable")
    public <T> T complete(Prompt prompt, ModelSelection selection, Class<T> type) {
        return delegate.complete(prompt, selection, type);
    }

    private <T> T unavailable(Prompt prompt, ModelSelection selection, Class<T> type) {
        throw new ProviderUnavailableException(
                "El proveedor %s no está respondiendo. Prueba con otro proveedor desde el "
                        .formatted(selection.providerName())
                        + "selector, o reintenta en unos minutos.");
    }
}
```

Dos detalles que importan:

- **`retryOn`/`abortOn` son tipos, no cadenas.** Esto exige que `SPEC-BE-03` sustituya la
  clasificación por subcadenas (`BE-A6`) por una jerarquía de excepciones tipadas. Las dos
  specs se apoyan mutuamente: la resiliencia declarativa es lo que hace *obligatoria* la
  tipificación de errores, que por sí sola parecería un lujo.
- **El mensaje del `@Fallback` es accionable.** El sistema tiene cinco proveedores; cuando
  uno cae, decirle al usuario que puede cambiar de proveedor convierte una caída total en
  una degradación.

### 3.2 Parámetros: por qué estos valores

| Parámetro | Valor | Razón |
|---|---|---|
| `@Timeout` | 120 s | Los 300 s actuales están calibrados para el peor caso de un pliego enorme. 120 s es tolerable para un usuario que espera mirando una pantalla; por encima de eso, el patrón correcto es un trabajo asíncrono, no una espera más larga. |
| `maxRetries` | 2 (3 intentos) | Igual que hoy. Con cortacircuitos ya no hace falta más. |
| `delay` + `jitter` | 2 s ± 1 s | Equivalente al retroceso actual, pero sin bloquear hilo. |
| `requestVolumeThreshold` | 8 | Suficiente para no abrir por dos fallos aislados en un servicio de bajo tráfico. |
| `failureRatio` | 0,5 | La mitad de la ventana fallando es un proveedor caído, no mala suerte. |
| `delay` del circuito | 30 s | Las cuotas de los planes gratuitos se reponen por minuto. |
| `Bulkhead` | 12 concurrentes + 24 en cola | El mamparo es lo que impide que la IA ahogue a la búsqueda. El valor se calibra con las métricas de `SPEC-BE-05`; se empieza conservador. |

**Todos externalizados.** MicroProfile permite sobrescribir cualquier parámetro por
configuración, lo que hace que ajustarlos en producción no requiera desplegar:

```properties
co.agentesecop.adapter.out.llm.ResilientLanguageModel/complete/Retry/maxRetries=2
co.agentesecop.adapter.out.llm.ResilientLanguageModel/complete/CircuitBreaker/delay=30000
co.agentesecop.adapter.out.llm.ResilientLanguageModel/complete/Bulkhead/value=12
```

### 3.3 Presupuesto de tiempo por caso de uso (BE-A11)

El timeout por llamada no acota la petición HTTP cuando hay dos llamadas encadenadas. Se
añade un presupuesto en la capa de aplicación:

```java
@ApplicationScoped
public class ValidateProposalService implements ValidateProposalUseCase {

    @Override
    @Timeout(value = 240, unit = ChronoUnit.SECONDS)   // techo del caso de uso completo
    public ComplianceReport validate(ValidateProposalCommand command) {
        List<TechnicalRequirement> requirements = command.requirements();
        if (requirements.isEmpty()) {
            requirements = analyzeTender.analyze(command.toAnalysisCommand()).requirements();
        }
        return models.complete(prompts.render(PROPOSAL_VALIDATION, command, requirements),
                               command.modelSelection(), ComplianceReport.class);
    }
}
```

240 s de techo con 120 s por llamada deja margen para las dos invocaciones y garantiza que
**ninguna petición HTTP puede vivir media hora**.

Complemento a nivel de servidor, que es la última línea de defensa:

```properties
quarkus.http.limits.max-body-size=30M
quarkus.http.read-timeout=300s
quarkus.http.idle-timeout=310s
```

### 3.4 El event loop, liberado (BE-C1)

Dos cambios en `ChatResource`:

```java
@POST
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.SERVER_SENT_EVENTS)
@RunOnVirtualThread                     // ← saca el trabajo bloqueante del event loop
public Multi<OutboundSseEvent> chat(@Valid ChatRequest request, @Context Sse sse) { … }
```

Y en el adaptador, el emisor deja de heredar el hilo del llamante y declara su estrategia
de contrapresión:

```java
return Multi.createFrom()
        .emitter(emitter -> model.chat(request, handler(emitter)),
                 BackPressureStrategy.BUFFER)
        .emitOn(Infrastructure.getDefaultWorkerPool())
        .onCancellation().invoke(() -> {
            // Si el navegador cierra la pestaña, hay que cortar la llamada al
            // proveedor. Sin esto se sigue pagando por tokens que nadie leerá.
            LOG.debugf("Flujo cancelado por el cliente; se aborta la llamada al modelo");
            handler.cancel();
        });
```

El `onCancellation` cierra un agujero que hoy existe y no está en el diagnóstico como
hallazgo separado porque es consecuencia directa de `BE-C1`: **cerrar la pestaña no detiene
la llamada al modelo**, así que el usuario se va y los tokens se siguen facturando.

### 3.5 Resiliencia hacia SECOP

El adaptador de catálogo lleva su propia política, distinta y más agresiva —es una fuente
gratuita, rápida y de solo lectura—:

```java
@Retry(maxRetries = 2, delay = 500, delayUnit = ChronoUnit.MILLIS,
       retryOn = ProcurementSourceUnavailableException.class)
@Timeout(value = 20, unit = ChronoUnit.SECONDS)
@CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.6,
                delay = 15, delayUnit = ChronoUnit.SECONDS)
@Bulkhead(30)
public CatalogResult search(ProcessFilter filter) { … }
```

Los mamparos separados —12 para IA, 30 para catálogo— son lo que impide que un incidente de
Gemini deje sin búsqueda a quien solo quería listar procesos. Ese aislamiento es el objetivo
central de esta spec.

### 3.6 Pruebas de la política (BE-B22)

La resiliencia no declarada en una prueba no existe. WireMock ya está en el proyecto como
biblioteca en la misma JVM (no hay Docker), así que el coste de añadirlas es bajo:

```java
@QuarkusTest
@TestProfile(NoCredentialsProfile.class)
class LanguageModelResilienceTest {

    @Test
    void reintentaAnte429YAcabaRespondiendo() {
        stubFor(post(anyUrl())
                .inScenario("cuota")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("recuperado"));
        stubFor(post(anyUrl())
                .inScenario("cuota")
                .whenScenarioStateIs("recuperado")
                .willReturn(okJson(ANALISIS_VALIDO)));

        var resultado = useCase.analyze(comandoValido());

        assertThat(resultado.requirements()).isNotEmpty();
        verify(2, postRequestedFor(anyUrl()));      // exactamente un reintento
    }

    @Test
    void noReintentaAnte401() {
        stubFor(post(anyUrl()).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> useCase.analyze(comandoValido()))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(1, postRequestedFor(anyUrl()));      // ni un intento de más
    }

    @Test
    void elCortacircuitosAbreYDejaDeLlamarAlProveedor() { … }

    @Test
    void elTimeoutDelCasoDeUsoAcotaDosLlamadasEncadenadas() { … }
}
```

`verify(1, …)` y `verify(2, …)` son la clave: no basta con comprobar que el resultado es el
esperado, hay que comprobar **cuántas veces se llamó al proveedor**. Es lo que convierte
«creemos que no reintenta credenciales inválidas» en un hecho.

---

## 4. Plan de migración

| Paso | Contenido | Riesgo |
|---|---|---|
| 1 | Añadir `quarkus-smallrye-fault-tolerance`. Sin usarlo todavía. | Nulo |
| 2 | `@RunOnVirtualThread` en `ChatResource` + `emitOn` + `onCancellation`. Cierra `BE-C1`. | Bajo |
| 3 | Jerarquía de excepciones tipadas (coordinado con `SPEC-BE-03`). | Medio |
| 4 | `ResilientLanguageModel` como decorador, delegando en la implementación actual **con `conReintentos` todavía dentro**. Verificar que el número total de intentos no se multiplica. | Medio |
| 5 | Borrar `conReintentos` de `ProveedorLangChain4j`. | Bajo |
| 6 | `@Timeout` de caso de uso en los cinco servicios de aplicación. | Bajo |
| 7 | Resiliencia del adaptador de catálogo. | Bajo |
| 8 | Suite de pruebas de fallo con WireMock. | Bajo |
| 9 | Externalizar todos los parámetros a `application.properties`. | Nulo |

El paso 4 tiene una trampa clásica que conviene nombrar: si se añade `@Retry` sin quitar
`conReintentos`, los reintentos se **multiplican** (3 × 3 = 9 llamadas) en vez de sumarse.
Por eso el paso 4 incluye la verificación explícita y el paso 5 va inmediatamente después.

---

## 5. Criterios de aceptación

1. No queda ningún `Thread.sleep` en `src/main/java`.
2. Existe una prueba que demuestra cada una de estas cuatro afirmaciones, contando llamadas
   al proveedor:
   - un 429 se reintenta y el número total de llamadas es exactamente el configurado;
   - un 401 no se reintenta nunca;
   - el cortacircuitos abre tras superar el umbral y deja de llamar al proveedor;
   - un caso de uso con dos llamadas encadenadas respeta el presupuesto global.
3. Ninguna petición HTTP puede exceder el presupuesto de su caso de uso, demostrado con una
   prueba de un proveedor que nunca responde.
4. Con el proveedor de IA totalmente caído, `POST /api/procesos/buscar` sigue respondiendo
   con normalidad. Es la prueba de que el mamparo funciona.
5. `ChatResource` no bloquea el event loop: no aparece ningún aviso de
   `BlockedThreadChecker` en una sesión de chat completa.
6. Cancelar el flujo desde el cliente aborta la llamada al proveedor, verificado por el
   número de peticiones que registra WireMock.
7. Todos los parámetros de resiliencia son configurables sin recompilar.
8. `/api/salud` responde en menos de 100 ms con el pool de IA saturado.

---

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| Los reintentos se multiplican durante la transición (3 × 3 = 9). | El paso 4 incluye la verificación explícita del conteo y el paso 5 va inmediatamente después, en la misma entrega. |
| Un mamparo de 12 rechaza peticiones legítimas en un pico. | `waitingTaskQueue = 24` absorbe el pico. El rechazo devuelve 503 con `Retry-After`, no un error opaco. Se calibra con las métricas de `SPEC-BE-05` antes de bajar el valor. |
| Bajar el timeout de 300 s a 120 s rompe análisis de pliegos muy grandes. | Se mide antes. El límite de 800 000 caracteres (`BE-M16`) baja en la misma entrega, y un pliego que no cabe en 120 s tampoco cabe en la ventana de contexto del modelo: el fallo correcto es 413, no una espera larga. |
| El cortacircuitos abre por un fallo de una sola clave mal configurada. | `abortOn` incluye `InvalidCredentialsException`, que no cuenta como fallo del circuito. Una clave mala falla rápido y no envenena al proveedor para todos. |

---

## 7. Fuera de alcance

- Procesamiento asíncrono con cola y sondeo de estado. Es la respuesta correcta si los
  análisis superan sistemáticamente los 120 s, pero es un cambio de producto que debe
  decidirse con datos de `SPEC-BE-05`, no por anticipado.
- Caché de respuestas del modelo. Se evalúa cuando haya métricas de repetición.
- Degradación entre proveedores (si Gemini cae, probar OpenAI automáticamente). Tentador y
  peligroso: cambia el coste y la calidad sin que el usuario lo pida. El `@Fallback` actual
  se lo **sugiere** al usuario y le deja la decisión.

---

## 8. Lo que se aprendió al implementarla

Cinco cosas que la spec no anticipaba. Se dejan escritas porque cuatro de ellas son
trampas que cualquiera volvería a pisar, y la quinta cambia el diseño propuesto.

### 8.1 Sobrescribir por configuración cambia el número, no la unidad

El diseño proponía `@Retry(delay = 2, delayUnit = SECONDS)` y, en paralelo,
`…/Retry/delay=2000` en las propiedades. Son incompatibles: la propiedad sustituye el
valor y **conserva la unidad de la anotación**, así que eso pedía dos mil segundos de
espera. El arranque falló con «maxDuration should be greater than delay», que fue una
suerte: el mismo error en `Timeout/value` habría arrancado sin protestar con un timeout
de treinta y tres horas.

Todas las anotaciones declaran ahora `ChronoUnit.MILLIS` y todas las propiedades van en
milisegundos. La unidad del código y la de la configuración coinciden por construcción.

### 8.2 El repliegue no se puede emparejar con una firma genérica

`@Fallback(fallbackMethod = …)` sobre `<T> T complete(…, Class<T>)` hace fallar el
despliegue: «can't find fallback method with matching parameter types and return type».
No es cuestión del modificador de acceso ni del orden de los parámetros.

La política vive por eso en `PoliticaDeResiliencia`, un bean con la firma borrada
(`Object`, `Class<?>`), y `ModeloDeLenguajeResiliente` hace el `cast`. La separación tiene
además una segunda razón que la spec tampoco menciona y que es más importante: **los
interceptores de CDI no actúan sobre llamadas internas**, así que las anotaciones tenían
que estar en un bean distinto del que las invoca, no en un método privado del mismo.

### 8.3 LangChain4j ya reintentaba, y los reintentos se multiplicaban

La spec avisaba de esta trampa para la transición —«3 × 3 = 9 llamadas»— pero la situaba
en `conReintentos`. Estaba también, y sobre todo, **dentro de la biblioteca**: los modelos
de LangChain4j reintentan tres veces por su cuenta. Con `conReintentos` borrado y dos
intentos declarados, el proveedor falso recibió **seis** peticiones.

Solo se detectó porque la prueba cuenta peticiones en vez de comprobar el resultado; con
un `assertThrows` habría pasado en verde. Los cuatro proveedores llevan ahora
`.maxRetries(0)`: la única política de reintentos es la declarada.

### 8.4 Un cortacircuitos por método, no por proveedor

MicroProfile asocia el cortacircuitos a un método, no a un argumento de la llamada. Con
cinco proveedores detrás del mismo puerto, el circuito es **uno y agregado**: si Gemini
cae y abre el circuito, una petición dirigida explícitamente a OpenAI también se replegará
durante el reposo.

Es una degradación aceptable —el mensaje del repliegue invita a reintentar— pero rebaja lo
que puede afirmar la sonda de `SPEC-BE-05` §3.1: informa del estado agregado, no de «hay
al menos un proveedor con circuito cerrado». Tener uno por proveedor exigiría construirlos
a mano con la API programática, que es justo la complejidad que la decisión 1 evitaba.

### 8.5 La observabilidad también recibe entrada del usuario

`/q/metrics` contra el servicio real mostró `proveedor="inventado"`: el nombre de
proveedor de la petición llegaba a la etiqueta sin filtrar, y basta un bucle pidiendo
proveedores al azar para llenar el almacén de métricas de series inútiles. El riesgo
estaba anotado en `SPEC-BE-05` §6 para el identificador de modelo —que sí se había
acotado— y no para el campo de al lado. Ambos se resuelven contra el catálogo y lo
desconocido cae en `otro`.

En la misma línea: la prueba que vigila que no haya claves reales en el perfil de pruebas
**imprimía la clave entera** en su mensaje de fallo. Es decir, la prueba que existe para
que no se filtren credenciales las habría publicado en el registro de integración continua
justo el día que hubiera una que filtrar. Ahora afirma sobre la longitud y nunca sobre el
valor.

### 8.6 Verificado contra una caída real

Durante la verificación en vivo, Gemini devolvió `503 UNAVAILABLE — high demand` de
verdad. La política entera se ejercitó sin montar nada: tres reintentos gastados
(`ft_retry_retries_total`), cuatro fallos contados, circuito abierto, `/q/health/ready` en
`DOWN` con `cortacircuitos: open`, respuestas inmediatas en vez de tres intentos por
petición —y `POST /api/procesos/buscar` respondiendo con normalidad durante todo el
episodio, que es el objetivo central de esta spec—. El circuito se cerró solo al
recuperarse el proveedor.

---

## 9. Qué queda fuera de esta entrega

- **`@RunOnVirtualThread` en el chat.** El `@Blocking` de la fase 1 ya sacó el trabajo
  del bucle de eventos, que era el defecto (`BE-C1`). Cambiar a hilos virtuales es una
  mejora de densidad, no una corrección.
- **Cancelar la llamada al proveedor cuando el cliente cierra la pestaña** (§3.4). Sigue
  abierto: LangChain4j no expone un asa para abortar una respuesta en curso, así que
  reaccionar a la cancelación deja de emitir pero no deja de facturar.
- **Política declarada sobre `flujo`.** Fault Tolerance no envuelve `Multi`, y reintentar
  una respuesta ya empezada no tendría sentido.
