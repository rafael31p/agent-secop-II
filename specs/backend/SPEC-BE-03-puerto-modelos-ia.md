# SPEC-BE-03 · Puerto de modelos de lenguaje y patrones de proveedor

| | |
|---|---|
| **Estado** | Propuesta |
| **Prioridad** | 🔴 Alta |
| **Cierra** | BE-C2, BE-A6, BE-M13, BE-A12 |
| **Depende de** | SPEC-BE-01 · Complementa SPEC-BE-02 |
| **Esfuerzo** | 4–6 jornadas |

---

## 1. Problema

La abstracción `ProveedorIA` es la mejor decisión de diseño del proyecto —está bien
justificada y ha demostrado su valor: DeepSeek entró casi gratis—. Lo que falla es la clase
base sobre la que se apoya.

### 1.1 `ProveedorLangChain4j` hace cinco cosas

333 líneas que mezclan: caché de modelos, política de reintentos, imposición del esquema
JSON, análisis sintáctico de la respuesta y traducción de errores. Cambiar cualquiera de
las cinco obliga a tocar la clase de la que heredan los cinco proveedores.

### 1.2 Caché sin cota con clave del cliente (BE-C2)

`ia/ProveedorLangChain4j.java:47-49`:

```java
private final Map<String, ChatModel> modelosCacheados = new ConcurrentHashMap<>();
private final Map<String, StreamingChatModel> modelosFlujoCacheados = new ConcurrentHashMap<>();
```

La clave viene de `peticion.modelo()`, que es un `String` libre del cuerpo de la petición
sin validación alguna. Cada valor nuevo crea un cliente HTTP con su pool y sus hilos, que
nunca se cierra ni se expulsa. Denegación de servicio de coste cero, y fuga de recursos
incluso sin malicia: el selector del frontend permite escribir el identificador a mano.

### 1.3 Clasificación de errores por subcadenas (BE-A6)

`:264-304` decide la política de reintentos buscando `"429"`, `"404"`, `"timeout"` dentro de
un texto que concatena hasta cuatro causas anidadas. Frágil ante versiones del SDK,
vulnerable a falsos positivos desde el propio texto del pliego, cerrada a la extensión (un
proveedor nuevo amplía una cadena de `if` compartida), y —lo más grave— es de lo que depende
`esReintentable`. La política más importante del sistema descansa sobre coincidencia de
texto.

`SPEC-BE-02` necesita `retryOn`/`abortOn` **tipados**. Sin esta spec, aquella no se puede
implementar.

### 1.4 Duplicación en los proveedores (BE-M13) y configuración cerrada (BE-A12)

Diez métodos `construirModelo`/`construirModeloFlujo` casi idénticos. Y
`config/ConfiguracionIA` declara los cinco proveedores nominalmente, de modo que añadir uno
sexto obliga a tocar cinco archivos —contradiciendo la promesa de `RegistroProveedores` de
que basta con crear una clase—.

---

## 2. Decisión

Cuatro cambios, cada uno con su patrón:

1. **Descomponer la clase base** en colaboradores con una responsabilidad cada uno:
   fábrica de modelos, traductor de errores, analizador de respuesta. La clase base queda
   como **Template Method** que fija el flujo invariante.
2. **Caché acotada con cierre al expulsar** (Caffeine) y **lista de patrones válidos** para
   el identificador de modelo.
3. **Jerarquía de excepciones tipadas** derivada del código de estado HTTP, con la
   coincidencia de texto degradada a último recurso.
4. **Configuración por mapa** (`Map<String, ProviderSettings>`), abierta a la extensión.

---

## 3. Diseño

### 3.1 Colaboradores

```
LanguageModelPort                        ← application/port/out
   └── LangChain4jLanguageModel          Template Method: valida → resuelve → invoca → parsea
         ├── ChatModelFactory            Abstract Factory + caché acotada
         │     └── ProviderModelBuilder  Strategy, una por proveedor
         ├── ProviderErrorTranslator     Strategy, traduce a excepciones tipadas
         ├── StructuredResponseReader    recorte de Markdown + deserialización
         └── JsonSchemaHardener          (el actual EsquemasJson, sin cambios de lógica)
```

`LangChain4jLanguageModel` pasa de 333 líneas a unas 80, y cada colaborador se prueba solo.

### 3.2 Fábrica con caché acotada (BE-C2)

```java
@ApplicationScoped
public class ChatModelFactory {

    private static final Pattern VALID_MODEL_ID = Pattern.compile("[a-zA-Z0-9._:\\-]{1,80}");

    private final Cache<ModelKey, ChatModel> models;

    ChatModelFactory(AiProperties properties, Instance<ProviderModelBuilder> builders) {
        this.models = Caffeine.newBuilder()
                .maximumSize(properties.modelCacheSize())          // por defecto 32
                .expireAfterAccess(Duration.ofHours(2))
                .removalListener((ModelKey key, ChatModel model, RemovalCause cause) ->
                        closeQuietly(model))                       // libera el cliente HTTP
                .build();
    }

    public ChatModel resolve(ProviderId provider, String requestedModel) {
        String modelId = normalize(provider, requestedModel);
        return models.get(new ModelKey(provider, modelId), this::build);
    }

    /**
     * El identificador de modelo llega del cuerpo de la petición. Sin este filtro, cada
     * cadena distinta creaba una entrada de caché y un cliente HTTP que nunca se cerraba:
     * un bucle de peticiones con nombres aleatorios agotaba memoria y descriptores.
     */
    private String normalize(ProviderId provider, String requested) {
        if (requested == null || requested.isBlank()) {
            return properties.of(provider).defaultModel();
        }
        String trimmed = requested.trim();
        if (!VALID_MODEL_ID.matcher(trimmed).matches()) {
            throw new UnknownModelException(
                    "El identificador de modelo '%s' no es válido. Consulta GET /api/proveedores."
                            .formatted(abbreviate(trimmed, 40)));
        }
        return trimmed;
    }
}
```

**Por qué patrón y no lista cerrada.** La documentación actual promete explícitamente que
«se acepta cualquier identificador válido del proveedor», y es una promesa útil: los modelos
nuevos salen antes de que nadie actualice una lista. Un patrón sintáctico conserva la
promesa y elimina el vector: con 32 entradas máximas y expulsión con cierre, el peor caso
está acotado sea cual sea la entrada.

`ModelKey` incluye el proveedor, corrigiendo de paso un defecto latente del diseño actual:
hoy la caché es por instancia de proveedor, lo que funciona por accidente porque cada
proveedor es un `@Singleton` distinto —pero `ProveedorDeepSeek extends ProveedorOpenAI`
hereda el mapa como campo propio, no compartido. Con la clave explícita deja de depender de
ese detalle.

### 3.3 Constructores de modelo: una función, no diez métodos (BE-M13)

```java
public record ModelParameters(
        String modelId, String apiKey, String baseUrl,
        double temperature, int maxTokens, Duration timeout, boolean logRequests) {}

public interface ProviderModelBuilder {
    ProviderId providerId();
    ChatModel buildSync(ModelParameters parameters);
    StreamingChatModel buildStreaming(ModelParameters parameters);
}
```

```java
@ApplicationScoped
public class GeminiModelBuilder implements ProviderModelBuilder {

    @Override public ProviderId providerId() { return ProviderId.GEMINI; }

    @Override
    public ChatModel buildSync(ModelParameters p) {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(p.apiKey()).modelName(p.modelId())
                .temperature(p.temperature()).maxOutputTokens(p.maxTokens())
                .timeout(p.timeout()).logRequestsAndResponses(p.logRequests())
                .build();
    }

    @Override
    public StreamingChatModel buildStreaming(ModelParameters p) { … }
}
```

Los parámetros comunes se arman **una vez** en la fábrica; el constructor solo mapea al SDK
concreto. La duplicación que queda es irreducible: los SDK tienen nombres distintos
(`maxOutputTokens` vs. `maxTokens`, `logRequestsAndResponses` vs. `logRequests`) y esa
diferencia es precisamente lo que el adaptador existe para absorber.

**DeepSeek deja de heredar de OpenAI.** Comparte el `ProviderModelBuilder` de OpenAI por
composición —mismo SDK, distinta URL base y clave— sin acoplar las dos clases. El ahorro que
celebra el comentario actual se conserva; la fragilidad de la herencia, no.

### 3.4 Errores tipados (BE-A6)

```java
// domain/... no: estas excepciones son del adaptador, no del dominio.
// adapter/out/llm/error/
public sealed class ProviderException extends RuntimeException
        permits TransientProviderException, InvalidCredentialsException,
                UnknownModelException, ContentBlockedException,
                UnusableResponseException, ContextTooLargeException { … }

/** Fallo recuperable: cuota, saturación, red. Es lo único que @Retry reintenta. */
public final class TransientProviderException extends ProviderException { … }
```

```java
@ApplicationScoped
public class ProviderErrorTranslator {

    public ProviderException translate(Throwable error, ProviderId provider, String modelId) {
        // 1. Preferente: el código de estado tipado que ya trae el SDK.
        Integer status = httpStatusOf(error);
        if (status != null) {
            return switch (status) {
                case 401, 403 -> new InvalidCredentialsException(provider);
                case 404      -> new UnknownModelException(provider, modelId);
                case 413      -> new ContextTooLargeException(provider);
                case 429      -> new TransientProviderException(provider, status);
                case 500, 502, 503, 504 -> new TransientProviderException(provider, status);
                default -> new ProviderCallFailedException(provider, status);
            };
        }
        // 2. Último recurso: heurística de texto, solo cuando no hay estado.
        //    Se conserva porque algunos SDK envuelven errores de red sin código, pero ya
        //    no es el camino principal ni decide la política de reintentos por sí sola.
        return byMessageHeuristics(error, provider, modelId);
    }
}
```

El cambio de fondo: **el estado HTTP manda y el texto es el respaldo**, exactamente al revés
que hoy. La heurística de texto se conserva porque cubre casos reales (errores de red sin
código), pero deja de ser la fuente de verdad, y las pruebas existentes de
`ProveedorLangChain4jTest` siguen siendo válidas para esa rama.

Un traductor por proveedor es posible (`Instance<ProviderErrorTranslator>` con calificador)
si algún SDK resulta especialmente peculiar; se empieza con uno solo, porque hoy no hay
evidencia de que haga falta más.

### 3.5 Configuración abierta (BE-A12)

```java
@ConfigMapping(prefix = "agente.ia")
public interface AiProperties {

    @WithDefault("gemini") String defaultProvider();
    @WithDefault("32")     int    modelCacheSize();

    Shared shared();

    /** Clave = identificador del proveedor. Añadir uno es configuración, no código. */
    Map<String, ProviderSettings> providers();

    interface Shared {
        @WithDefault("32000") int maxTokens();
        @WithDefault("0.2")   double temperature();
        @WithDefault("120")   int timeoutSeconds();
        @WithDefault("false") boolean logRequests();
    }

    interface ProviderSettings {
        Optional<String> apiKey();
        String defaultModel();
        Optional<String> baseUrl();
        @WithDefault("true")  boolean enabled();
        @WithDefault("false") boolean requiresApiKey();   // Ollama: false
        List<String> suggestedModels();
    }
}
```

```properties
agente.ia.providers.gemini.default-model=gemini-3.6-flash
agente.ia.providers.gemini.requires-api-key=true
agente.ia.providers.gemini.suggested-models=gemini-3.6-flash,gemini-3.5-flash,gemini-flash-latest

agente.ia.providers.deepseek.base-url=https://api.deepseek.com/v1
agente.ia.providers.deepseek.default-model=deepseek-chat
```

Con esto, `configurado()` y `motivoNoDisponible()` —repetidos cinco veces— pasan a una sola
implementación en la clase base, calculada desde `requiresApiKey` y `apiKey`. Los mensajes
de ayuda («Obtén una clave gratuita en…») se mueven a la configuración, que es donde
pertenece un texto que cambia cuando cambia una URL de terceros.

Añadir un proveedor sexto queda en: una clase `ProviderModelBuilder` + un bloque de
propiedades. Dos sitios, que es la promesa original.

### 3.6 El catálogo deja de vivir en el dominio

`RegistroProveedores.catalogo()` devuelve hoy `dominio.Secop.ProveedorDisponible`. Se parte:

- `LanguageModelCatalogPort` (`application/port/out`) devuelve
  `List<LanguageModelDescriptor>`, tipo propio de la aplicación.
- `adapter/in/rest/dto/ProviderDto` traduce al contrato HTTP, que **no cambia**: el frontend
  sigue recibiendo exactamente los mismos campos.

---

## 4. Plan de migración

| Paso | Contenido | Riesgo |
|---|---|---|
| 1 | Jerarquía de excepciones + `ProviderErrorTranslator` con estado HTTP primero. Las pruebas de clasificación existentes se conservan para la rama heurística. | Medio |
| 2 | `ChatModelFactory` con Caffeine y validación del identificador. Cierra `BE-C2`. | Bajo |
| 3 | `ProviderModelBuilder` por proveedor; vaciar los `construirModelo` de las subclases. | Medio |
| 4 | DeepSeek deja de heredar de OpenAI. | Bajo |
| 5 | Configuración por mapa; migrar `application.properties` y `.env.example`. | Medio |
| 6 | Extraer `StructuredResponseReader`; `LangChain4jLanguageModel` como Template Method. | Bajo |
| 7 | `LanguageModelCatalogPort` + DTO. | Bajo |

El paso 5 es el único que puede romper despliegues: los nombres de las variables de entorno
cambian (`AGENTE_IA_GEMINI_API_KEY` → `AGENTE_IA_PROVIDERS_GEMINI_API_KEY`). Se documenta en
el `CHANGELOG` y se soporta el nombre antiguo un ciclo con
`@WithName`/`@ConfigMapping` de compatibilidad.

---

## 5. Criterios de aceptación

1. Mil peticiones con identificadores de modelo aleatorios dejan la caché en su tamaño
   máximo configurado y no incrementan el número de clientes HTTP vivos. Verificado con una
   prueba que cuenta las entradas y las expulsiones.
2. Un identificador de modelo que no cumple el patrón devuelve 400 **sin construir ningún
   modelo ni llamar al proveedor**.
3. `ProviderErrorTranslator` deriva la clasificación del código de estado cuando existe;
   hay una prueba por cada estado mapeado.
4. `@Retry(retryOn = TransientProviderException.class)` de `SPEC-BE-02` funciona sin
   inspeccionar ningún mensaje.
5. Añadir un proveedor ficticio en una prueba requiere exactamente una clase y un bloque de
   propiedades; ninguna clase existente se modifica.
6. `LangChain4jLanguageModel` no supera las 100 líneas.
7. `GET /api/proveedores` devuelve un cuerpo **idéntico** al actual, byte a byte, para la
   misma configuración.
8. La lógica de `EsquemasJson` se conserva sin cambios: sus 97 líneas de prueba siguen
   pasando sin tocar una aserción.

---

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| Cambiar los nombres de configuración rompe entornos existentes. | Soporte del nombre antiguo durante un ciclo + aviso en el arranque + `CHANGELOG`. |
| La expulsión de la caché cierra un modelo en uso. | Caffeine expulsa por acceso, no por uso; el cierre se hace en el `removalListener`, que se ejecuta después de que la referencia deje de entregarse. Aun así, el cierre va envuelto en `closeQuietly`. |
| El patrón del identificador rechaza un modelo legítimo futuro. | El patrón es deliberadamente amplio (alfanuméricos, punto, guion, dos puntos, guion bajo, 80 caracteres). Cubre los identificadores de los cinco proveedores actuales y los previsibles. |
| Perder la tolerancia actual ante respuestas del modelo mal formadas. | `StructuredResponseReader` conserva `recortarACuerpoJson` tal cual, con sus cuatro pruebas. |

---

## 7. Fuera de alcance

- Cambiar de LangChain4j. La abstracción existe precisamente para poder hacerlo sin que el
  resto se entere, y hoy no hay motivo.
- Conteo de tokens y estimación de coste (`SPEC-BE-05`).
- Caché de respuestas del modelo.
- Selección automática de proveedor por coste o calidad.

---

## 8. Adelantado en la fase 3: la jerarquía de excepciones (§3.4)

El §3.4 de esta spec —excepciones tipadas en un paquete propio— se implementó antes de
tiempo, porque `SPEC-BE-02` no podía existir sin él: `@Retry(retryOn = …)` razona sobre
clases, no sobre códigos HTTP.

Lo que quedó, en `adapter/out/llm/error/`, una clase por excepción y jerarquía sellada:

```
ErrorDelAgente
├── PeticionDeModelo…      IdentificadorDeModeloInvalido, ContextoDemasiadoGrande
├── RespuestaInutilizable
├── ProveedorDesconocido, ProveedorNoConfigurado, ProveedorNoDisponible
└── FalloDelProveedor
    ├── CredencialInvalida          no se reintenta
    ├── ModeloDesconocido           no se reintenta
    └── FalloTransitorio            SÍ se reintenta, y solo esto abre el circuito
        ├── CuotaAgotada            429
        └── ServicioDelProveedorCaido   502
```

Dos desviaciones respecto a lo que decía esta spec, ambas a mejor:

**El código HTTP salió de la excepción.** La spec y `SPEC-BE-06` §3.3 preveían un
`httpStatus()` en la clase base. No lo lleva: el estado lo pone un `ExceptionMapper` por
excepción, en `adapter/in/rest/error/`, con una clase base que define el proceso de
respuesta una sola vez. Una decisión de transporte no tiene por qué viajar dentro de una
excepción del núcleo —la misma excepción es un 429 por HTTP y no es nada por una cola—.

**Y eso destapó un defecto en producción.** Con un único manejador que capturaba la
jerarquía entera, cualquier excepción que no heredara de ella caía en el comportamiento por
defecto sin que nadie se enterara. Pasó: `domain.shared.PeticionInvalida` se movió al
dominio en la fase 2 y se quedó sin traducir, de modo que
`POST /api/propuestas/generar` sin requisitos ni pliego devolvía **500 Internal Server
Error** sin `detail` ni identificador de correlación. Comprobado contra el servicio real
antes de arreglarlo. Con un mapeador por excepción, un tipo sin mapear se ve al leer la
lista.

**`FalloTransitorio` se partió en dos** (`CuotaAgotada`, `ServicioDelProveedorCaido`)
justamente porque, sin el código dentro, hacía falta un tipo por respuesta distinta. El
resultado es mejor: «se agotó tu cuota, espera unos minutos» y «el servicio falló» son
mensajes distintos y ahora son tipos distintos.

Lo que sigue pendiente de esta spec: la descomposición de la clase base (§3.1), la caché
con Caffeine (§3.2, ahora también en `SPEC-BE-08` §3.1), los constructores por proveedor
(§3.3) y la configuración por mapa (§3.5).
