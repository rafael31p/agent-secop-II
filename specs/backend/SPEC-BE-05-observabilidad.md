# SPEC-BE-05 · Salud, métricas, trazas y correlación

| | |
|---|---|
| **Estado** | Implementada en parte (fase 3) |
| **Prioridad** | 🟠 Media |
| **Cierra** | BE-M15, BE-M18 |
| **Depende de** | SPEC-BE-01 · Habilita el calibrado de SPEC-BE-02 |
| **Esfuerzo** | 3–4 jornadas |

---

## 1. Problema

### 1.1 El endpoint de salud no comprueba nada

`api/SaludResource.salud()` construye su respuesta a partir de `registro.hayAlgunoConfigurado()`
y de propiedades de configuración. Devuelve `"ok"` con Gemini caído, con la clave revocada y
con datos.gov.co inalcanzable. **Informa de qué hay configurado, no de si funciona.**

Como sonda para un orquestador es peor que no tener ninguna: dice que todo está bien
mientras el servicio no puede hacer nada útil. Tampoco distingue vivacidad (¿hay que
reiniciar?) de disponibilidad (¿le mando tráfico?), que son decisiones opuestas.

### 1.2 No hay ninguna métrica

`pom.xml` no incluye Micrometer ni OpenTelemetry. Preguntas que hoy no se pueden responder:

- ¿Cuánto tarda un análisis, por proveedor y por modelo?
- ¿Qué porcentaje de llamadas falla, y por qué motivo?
- ¿Cuántos reintentos se están gastando? ¿Está el cortacircuitos abierto?
- ¿Cuántos tokens se han consumido este mes? ¿Cuánto cuesta un análisis?
- ¿Se está saturando el mamparo?

Sin la última, los valores de `SPEC-BE-02` son adivinanzas. **La observabilidad no es
opcional para esa spec: es su instrumento de calibrado.**

### 1.3 Un error reportado no se puede localizar

No hay identificador de correlación. Un usuario dice «me falló el análisis a las 3» y no hay
forma de encontrar esa petición en el registro, que además es texto plano sin estructura.

### 1.4 El aislamiento de las pruebas depende de recordar una anotación

`PerfilSinCredenciales` se aplica clase a clase con `@TestProfile`. Una clase nueva que se
olvide usará la clave real de `backend-quarkus/.env` y hará llamadas facturables —o pasará
en una máquina y fallará en otra—.

---

## 2. Decisión

1. **Salud estándar** con `quarkus-smallrye-health`: `/q/health/live` y `/q/health/ready`,
   con sondas que **comprueban de verdad**. `/api/salud` se conserva por compatibilidad con
   el pie del frontend, pero deja de ser el mecanismo de operación.
2. **Micrometer con registro Prometheus**, con etiquetas por proveedor, modelo y caso de
   uso, y un contador de tokens.
3. **Identificador de correlación** propagado del cliente al registro y devuelto en los
   errores, con registro en JSON.
4. **OpenTelemetry desactivado por defecto**, listo para activarse por configuración.
5. **Aislamiento de pruebas por defecto**, con fallo explícito si alguna clave resuelve a un
   valor no vacío.

---

## 3. Diseño

### 3.1 Sondas que comprueban

```java
@Readiness
@ApplicationScoped
public class LanguageModelReadinessCheck implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        var configured = catalog.configuredProviders();
        var builder = HealthCheckResponse.named("language-models")
                .withData("configured", String.join(",", configured))
                .withData("default", properties.defaultProvider());

        // No se llama al proveedor: eso costaría dinero en cada sondeo. Se usa el estado
        // del cortacircuitos, que ya sabe si el proveedor está respondiendo.
        boolean anyClosed = configured.stream().anyMatch(circuitBreakers::isClosed);
        return builder.status(!configured.isEmpty() && anyClosed).build();
    }
}
```

Ese detalle es el que hace la sonda útil sin hacerla cara: **el cortacircuitos de
`SPEC-BE-02` ya es un sensor de salud del proveedor**. Consultarlo es gratis y refleja el
estado real. Las dos specs se refuerzan.

| Sonda | Tipo | Qué decide |
|---|---|---|
| `application-live` | Liveness | Si el proceso responde. Reiniciar. |
| `language-models` | Readiness | Si hay al menos un proveedor con circuito cerrado. |
| `procurement-source` | Readiness | Última llamada a Socrata, con caché de 30 s. |

Un matiz de producto: si **ningún** proveedor está disponible pero SECOP sí, el servicio
sigue siendo parcialmente útil —se puede buscar, no analizar—. La disponibilidad se declara
degradada, no caída, y la interfaz ya sabe representarlo (`hayProveedorConfigurado` en
`lib/ia.tsx` muestra el aviso correspondiente).

### 3.2 Métricas

```java
@ApplicationScoped
public class MeteredLanguageModel implements LanguageModelPort {   // Decorator

    @Override
    public <T> T complete(Prompt prompt, ModelSelection selection, Class<T> type) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            T result = delegate.complete(prompt, selection, type);
            registry.counter("llm.tokens",
                    "provider", selection.providerName(),
                    "model", selection.modelId(),
                    "direction", "input").increment(prompt.estimatedTokens());
            return result;
        } catch (ProviderException e) {
            outcome = e.getClass().getSimpleName();
            throw e;
        } finally {
            sample.stop(registry.timer("llm.request",
                    "provider", selection.providerName(),
                    "model", selection.modelId(),
                    "use_case", prompt.useCase().name(),
                    "outcome", outcome));
        }
    }
}
```

| Métrica | Tipo | Etiquetas | Para qué |
|---|---|---|---|
| `llm.request` | Timer | provider, model, use_case, outcome | Latencia y tasa de error |
| `llm.tokens` | Counter | provider, model, direction | Coste |
| `llm.retries` | Counter | provider, reason | Calibrar `@Retry` |
| `llm.circuit.state` | Gauge | provider | ¿Está abierto? |
| `llm.bulkhead.queue` | Gauge | — | Calibrar `@Bulkhead` |
| `llm.model.cache` | Gauge | hits, misses, evictions | Verificar `BE-C2` |
| `procurement.request` | Timer | outcome, degraded | Frecuencia de `Unavailable` |
| `document.extraction` | Timer | type, outcome | Rendimiento de PDF/DOCX |

SmallRye Fault Tolerance publica automáticamente las tres primeras si Micrometer está
presente: no hay que instrumentarlas a mano.

**El contador de tokens es el que financia el resto del trabajo.** Es la única forma de
responder «cuánto nos cuesta esto», que es la pregunta que decide si el proyecto sigue. Se
empieza con una estimación por caracteres —aproximada pero suficiente para tendencias— y se
sustituye por el conteo real cuando el proveedor lo devuelva en la respuesta.

### 3.3 Correlación y registro estructurado

```java
@Provider
@PreMatching
public class CorrelationIdFilter implements ContainerRequestFilter, ContainerResponseFilter {

    public static final String HEADER = "X-Correlation-Id";

    @Override
    public void filter(ContainerRequestContext ctx) {
        String id = Optional.ofNullable(ctx.getHeaderString(HEADER))
                .filter(v -> v.length() <= 64 && v.matches("[A-Za-z0-9\\-]+"))
                .orElseGet(() -> UUID.randomUUID().toString());
        MDC.put("correlationId", id);
        ctx.setProperty(HEADER, id);
    }
}
```

La validación del encabezado entrante importa: aceptar un valor arbitrario del cliente e
insertarlo en el registro es inyección de registros.

El identificador se devuelve en **todas** las respuestas y se incluye en el cuerpo de error:

```json
{
  "detail": "El proveedor Google Gemini no está respondiendo. Prueba con otro proveedor.",
  "correlationId": "6c9f1e2a-7b40-4a1c-9f2e-2b8a1d3c4e5f"
}
```

Esto es lo que convierte `SPEC-BE-06` §fuga de errores en algo utilizable: se puede devolver
un mensaje genérico *porque* el identificador permite encontrar el detalle en el registro.
El campo `detail` se mantiene para no romper el frontend, que ya lo lee.

```properties
%prod.quarkus.log.console.json=true
%prod.quarkus.log.console.json.additional-field."service".value=agente-secop
```

### 3.4 Trazas, listas pero apagadas

```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-opentelemetry</artifactId>
</dependency>
```

```properties
quarkus.otel.enabled=false
%prod.quarkus.otel.enabled=${OTEL_ENABLED:false}
quarkus.otel.exporter.otlp.endpoint=${OTEL_ENDPOINT:http://localhost:4317}
```

Sin colector, activarlo ahora sería complejidad sin beneficio. Con la dependencia presente y
la configuración lista, encenderlo el día que haga falta es una variable de entorno.
**Requisito:** no se propaga el contenido del prompt ni de la respuesta a los atributos del
tramo. Sería enviar pliegos y propuestas a un sistema más (`SPEC-NT-02`).

### 3.5 Aislamiento de pruebas por defecto (BE-M18)

El perfil `test` se blinda:

```properties
%test.quarkus.config.locations=              # ignora .env
%test.agente.ia.providers.gemini.api-key=
%test.agente.ia.providers.openai.api-key=
%test.agente.ia.providers.anthropic.api-key=
%test.agente.ia.providers.deepseek.api-key=
%test.agente.ia.providers.ollama.enabled=false
```

Y una prueba que hace de guardia, para que el aislamiento deje de depender de la memoria de
quien escribe la siguiente clase:

```java
@QuarkusTest
class TestIsolationGuardTest {

    @Test
    void ningunaClaveRealEnElPerfilDePruebas() {
        for (var settings : properties.providers().values()) {
            assertThat(settings.apiKey().orElse(""))
                    .as("Una clave real en el perfil de pruebas haría llamadas facturables "
                            + "y volvería las pruebas dependientes de la máquina")
                    .isEmpty();
        }
    }
}
```

Con esto, `PerfilSinCredenciales` deja de ser necesario clase a clase. Se conserva solo
donde se quiera probar el comportamiento *con* una clave simulada.

---

## 4. Plan de migración

| Paso | Contenido |
|---|---|
| 1 | `quarkus-smallrye-health` + tres sondas. `/api/salud` se conserva sin cambios. |
| 2 | `quarkus-micrometer-registry-prometheus`; `/q/metrics` restringido a red interna. |
| 3 | `MeteredLanguageModel` como decorador. |
| 4 | `CorrelationIdFilter` + `correlationId` en los errores + registro JSON en `%prod`. |
| 5 | OpenTelemetry como dependencia, apagado. |
| 6 | Blindaje del perfil de pruebas + prueba guardián. |

Se puede hacer en cualquier momento después de `SPEC-BE-01`, y conviene hacerlo **antes** de
calibrar los valores de `SPEC-BE-02`.

---

## 5. Criterios de aceptación

1. `/q/health/ready` devuelve `DOWN` cuando ningún proveedor tiene el circuito cerrado, y
   `UP` cuando al menos uno lo tiene. Verificado con prueba.
2. Ninguna sonda de salud realiza una llamada facturable a un proveedor.
3. `/q/metrics` expone `llm_request_seconds` con las cuatro etiquetas y
   `llm_tokens_total` por proveedor.
4. Toda respuesta HTTP incluye `X-Correlation-Id`; todo cuerpo de error incluye
   `correlationId`; el mismo valor aparece en la línea de registro correspondiente.
5. Un `X-Correlation-Id` entrante malicioso (con salto de línea o de 10 000 caracteres) se
   descarta y se genera uno nuevo.
6. En `%prod` el registro es JSON de una línea por evento.
7. `TestIsolationGuardTest` falla si alguien añade una clave real al perfil de pruebas.
8. Ninguna métrica ni traza contiene texto del pliego, de la propuesta ni del prompt.

---

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| Explosión de cardinalidad por etiquetar con el identificador de modelo. | Los modelos son un conjunto pequeño y acotado por el patrón de `SPEC-BE-03`. Aun así, se limita a los sugeridos + `other`. |
| `/q/metrics` expuesto públicamente filtra información operativa. | Se restringe por red o por rol; nunca se publica junto a la API. |
| El registro JSON dificulta la lectura en desarrollo. | Solo en `%prod`. En desarrollo se mantiene el formato legible actual. |
| El conteo de tokens estimado da cifras engañosas. | Se etiqueta explícitamente como `estimated` hasta que se lea el uso real de la respuesta del proveedor. |

---

## 7. Fuera de alcance

- Desplegar Prometheus y Grafana; solo se expone el endpoint.
- Alertas y sus umbrales (`SPEC-NT-03`).
- Registro de auditoría de quién analizó qué (`SPEC-NT-02` lo requiere y merece su propia
  decisión, porque implica almacenar contenido de pliegos).

---

## 8. Qué se implementó en la fase 3 y qué no

**Hecho:** las tres sondas (`/q/health/live`, `/q/health/ready`), Micrometer con registro
Prometheus en `/q/metrics`, el decorador de métricas sobre el puerto, el identificador de
correlación y el blindaje del perfil de pruebas con su guardián.

**Pendiente, con motivo:**

| Punto | Estado |
|---|---|
| §3.4 OpenTelemetry como dependencia apagada | No se añade. Sin colector no aporta nada y engorda el arranque; encenderlo el día que haya uno es una dependencia y dos propiedades. |
| §3.3 registro JSON en `%prod` | No se activa. El identificador ya viaja en cada línea vía MDC, que era lo que faltaba; el formato JSON se decide cuando haya un destino que lo consuma. |
| §3.1 sonda por proveedor | No es posible sin renunciar a la resiliencia declarativa. Ver `SPEC-BE-02` §8.4. |

### 8.1 Dos correcciones sobre lo que decía esta spec

**El aislamiento del perfil de pruebas no se consigue con `%test.…=`** (§3.5). Quarkus lee
`.env` con prioridad 295 y `application.properties` del classpath con 250: las líneas
`%test.agente.ia.*.api-key=` **perdían**. Es decir, el blindaje propuesto no blindaba nada
en la máquina de quien tiene claves. Lo resuelve `pruebas.AislamientoDePruebas`, una
`ConfigSource` de prioridad 300 que solo existe en el classpath de pruebas.

**Las métricas de Fault Tolerance no aparecen hasta que hay tráfico.** Micrometer registra
los medidores la primera vez que se invoca el método guardado, así que comprobarlas antes
del primer análisis siempre da rojo. La verificación en vivo las mira al final, no junto a
las sondas.

### 8.2 Un detalle de despliegue que no se puede olvidar

`/q/metrics` dice qué modelos se usan, cuánto falla cada proveedor y cuánto se consume. No
debe publicarse junto a la API: se restringe por red o por proxy inverso. Está anotado
también en `application.properties`, donde lo verá quien despliegue.
