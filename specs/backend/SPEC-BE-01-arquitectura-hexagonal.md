# SPEC-BE-01 · Arquitectura hexagonal y regla de dependencias

| | |
|---|---|
| **Estado** | Propuesta |
| **Prioridad** | 🔴 Alta |
| **Cierra** | BE-A9, BE-A10, BE-M14, BE-M19, BE-B22 |
| **Depende de** | Fase 0 del plan (git + CI + ArchUnit congelado) |
| **Esfuerzo** | 8–12 jornadas |
| **Módulo** | `backend-quarkus/` |

---

## 1. Problema

El proyecto tiene una separación en paquetes por *tipo técnico* (`api`, `dominio`, `ia`,
`secop`, `servicio`) que aparenta arquitectura por capas pero no impone ninguna dirección de
dependencia. Cuatro consecuencias concretas, todas verificables:

**El dominio importa frameworks.** Los cuatro archivos de `dominio/` importan
`com.fasterxml.jackson.annotation.*` y `org.eclipse.microprofile.openapi.annotations.media.Schema`.
El núcleo de negocio no compila sin las bibliotecas de serialización y documentación HTTP.

**El dominio aloja conceptos de adaptadores.** `dominio/Secop.java` declara
`ProveedorDisponible` —descriptor de un proveedor de modelos de lenguaje— y `EstadoSalud`
—informe de operación—. Ninguno de los dos pertenece al dominio de contratación pública, y
uno de ellos hace que el adaptador de IA (`ia/RegistroProveedores`) dependa del dominio para
describirse a sí mismo: la dependencia va exactamente al revés de lo que debería.

**No hay puertos.** `api/ProcesosResource` inyecta `SecopCliente`, la clase concreta. No
existe ninguna interfaz que represente «catálogo de procesos de contratación» como
capacidad requerida por la aplicación. Solo `ProveedorIA` cumple ese papel, y bien: es la
prueba de que el equipo sabe hacerlo y de que la decisión de no hacerlo en el resto fue
implícita, no razonada.

**La aplicación construye presentación.** `servicio/AgenteSecop` dedica la mayor parte de
sus 261 líneas a ensamblar cadenas Markdown para el modelo, y depende de `ObjectMapper` para
serializar contexto. El formato del prompt es un detalle del adaptador de salida.

El comentario de cabecera de `dominio/Analisis.java` defiende explícitamente el
acoplamiento:

> Estos tipos cumplen doble función […]: son el contrato HTTP que documenta OpenAPI *y* el
> esquema JSON que se le impone al modelo de lenguaje. Una sola definición, imposible que se
> desincronicen.

Fue una buena decisión mientras las dos representaciones fueron idénticas. Ya no lo son: la
clase `ia/EsquemasJson` existe precisamente porque el esquema que necesita el modelo (todos
los campos obligatorios) difiere del contrato HTTP (campos opcionales). El acoplamiento ya
se rompió; lo que falta es reconocerlo en la estructura.

---

## 2. Decisión

Adoptar **puertos y adaptadores (hexagonal)** con tres anillos y una regla de dependencia
única, verificada automáticamente:

> Las dependencias apuntan hacia adentro. `domain` no conoce a nadie. `application` conoce a
> `domain`. `adapter` conoce a `application` y a `domain`. Nada conoce a `adapter`.

Se descarta la alternativa de arquitectura por capas tradicional (`controller`/`service`/
`repository`) porque no resuelve el problema principal: seguiría permitiendo que el servicio
dependa de la implementación concreta del cliente HTTP. Y se descarta partir en módulos
Maven separados por ahora: la regla se puede imponer con ArchUnit dentro de un solo módulo,
y multiplicar los `pom.xml` en esta fase añade fricción sin añadir garantía.

---

## 3. Diseño

### 3.1 Estructura objetivo

Nombres en inglés porque `SPEC-BE-07` lo establece como destino; la migración de nombres es
la fase 6, pero la estructura nueva ya nace correcta para no renombrar dos veces.

```
co.agentesecop
│
├── domain/                          ← sin ninguna dependencia externa
│   ├── model/
│   │   ├── procurement/             ProcurementProcess, ProcessFilter, DateRange,
│   │   │                            AmountRange, TechRelevance
│   │   ├── tender/                  TenderAnalysis, TechnicalRequirement, DetectedRisk,
│   │   │                            Criticality, RiskLevel, RiskKind
│   │   └── proposal/                Proposal, ProposalSection, ComplianceReport,
│   │                                ComplianceItem, ComplianceStatus, Verdict
│   ├── service/                     TechRelevanceScorer  (heurística pura)
│   ├── shared/                      CodedEnum, Lists, DomainException
│   └── package-info.java            @ArchitectureLayer(DOMAIN)
│
├── application/
│   ├── port/in/                     un caso de uso = una interfaz
│   │     SearchProcessesUseCase, GetProcessUseCase, RankProcessesUseCase,
│   │     AnalyzeTenderUseCase, GenerateProposalUseCase, ValidateProposalUseCase,
│   │     AskAgentUseCase, ExtractDocumentTextUseCase
│   ├── port/out/
│   │     LanguageModelPort, LanguageModelCatalogPort, ProcurementCatalogPort,
│   │     DocumentTextExtractorPort, PromptRendererPort
│   └── service/                     una implementación por caso de uso
│
├── adapter/
│   ├── in/rest/
│   │   ├── dto/                     records del contrato HTTP, con @Schema y Jackson
│   │   ├── mapper/                  DTO ↔ dominio
│   │   ├── ProcessResource, TenderResource, ProposalResource, ChatResource,
│   │   │ CatalogResource
│   │   └── error/                   ExceptionMappers + ProblemDetail
│   ├── out/llm/                     ver SPEC-BE-03
│   ├── out/procurement/             ver SPEC-BE-04
│   └── out/document/                PdfTextExtractor, DocxTextExtractor,
│                                    PlainTextExtractor, CompositeTextExtractor
│
└── config/                          AiProperties, ProcurementProperties, BuildInfo
```

### 3.2 Patrones aplicados y dónde

| Patrón | Dónde | Qué problema resuelve |
|---|---|---|
| **Ports & Adapters** | Global | La regla de dependencias (BE-A9) |
| **Command / Use Case por clase** | `application/service` | El servicio Dios (BE-A10) |
| **Strategy** | Extractores de documento, proveedores de modelo | Un formato/proveedor nuevo no toca a los demás |
| **Chain of Responsibility** | `CompositeTextExtractor` | Selección de extractor sin cadena de `if` en el servicio |
| **Adapter / Mapper** | `adapter/in/rest/mapper` | Desacopla el contrato HTTP del modelo |
| **Value Object** | `AmountRange`, `DateRange`, `TechRelevance` | Reglas de validación junto al dato, no dispersas |
| **Sealed result** | `CatalogResult` (BE-04) | Elimina `null` como centinela |
| **Template Method** | `AbstractLanguageModelAdapter` (BE-03) | Fija el flujo invariante, varían los ganchos |
| **Decorator** | Resiliencia y métricas sobre los puertos (BE-02) | Añade comportamiento transversal sin tocar implementaciones |

Ninguno se aplica por catálogo: cada uno cierra un hallazgo concreto. Donde el código actual
ya usa el patrón correcto —`ProveedorIA` como Strategy, `RegistroProveedores` como
Registry— se conserva y solo se recoloca.

### 3.3 El dominio, sin frameworks

Antes (`dominio/Analisis.java`):

```java
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Requisito técnico atómico y verificable extraído del pliego.")
public record RequisitoTecnico(
        @Schema(description = "Identificador corto, ej. RT-01", examples = "RT-01")
        String id, …) {}
```

Después — dos tipos con responsabilidades distintas:

```java
// domain/model/tender/TechnicalRequirement.java  — cero imports de terceros
public record TechnicalRequirement(
        RequirementId id,
        RequirementCategory category,
        String statement,
        Criticality criticality,
        String expectedEvidence,
        String relatedRegulation,   // nullable: no toda exigencia cita norma
        String tenderQuote) {

    public TechnicalRequirement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(statement, "statement");
        criticality = criticality == null ? Criticality.INFORMATIVE : criticality;
    }

    /** Regla de negocio: solo lo obligatorio puede causar rechazo. */
    public boolean canCauseRejection() {
        return criticality == Criticality.MANDATORY;
    }
}
```

```java
// adapter/in/rest/dto/TechnicalRequirementDto.java  — aquí sí van las anotaciones
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Requisito técnico atómico y verificable extraído del pliego.")
public record TechnicalRequirementDto(
        @Schema(examples = "RT-01") String id,
        String categoria,
        String requisito,
        String criticidad,
        String evidenciaEsperada,
        String normaRelacionada,
        String citaPliego) {}
```

El coste es real y hay que decirlo: aparecen tipos duplicados y mapeadores. La contrapartida
es que el contrato HTTP puede evolucionar sin tocar el negocio —y en este proyecto tiene que
poder hacerlo, porque el contrato está en español y el código va a inglés (`SPEC-BE-07`)—.
Los mapeadores se escriben a mano; MapStruct no compensa para siete tipos.

**Sobre el esquema del modelo:** el esquema que se impone al modelo de lenguaje se deriva de
un tercer tipo, propiedad del adaptador `out/llm` (`AnalysisResponsePayload`), no del
dominio ni del DTO HTTP. Es exactamente la separación que `EsquemasJson` está pidiendo hoy a
gritos.

### 3.4 Enumeraciones: una sola vez el molde

`domain/shared/CodedEnum.java`:

```java
/** Enumeración con un código estable que viaja por el cable. */
public interface CodedEnum {
    String code();

    static <E extends Enum<E> & CodedEnum> E parse(Class<E> type, String raw, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (E value : type.getEnumConstants()) {
            if (value.code().equals(normalized) || value.name().equalsIgnoreCase(normalized)) {
                return value;
            }
        }
        return fallback;
    }
}
```

```java
public enum Criticality implements CodedEnum {
    MANDATORY("obligatorio"),
    SCORED("ponderable"),
    DESIRABLE("deseable"),
    INFORMATIVE("informativo");

    private final String code;
    Criticality(String code) { this.code = code; }
    @Override public String code() { return code; }
}
```

La serialización se resuelve **una vez** en el adaptador, con un módulo de Jackson que
reconoce `CodedEnum`, en lugar de `@JsonValue`/`@JsonCreator` repetidos en cinco sitios:

```java
// adapter/in/rest/CodedEnumModule.java
public final class CodedEnumModule extends SimpleModule { … }
```

De 167 líneas con cinco copias del mismo molde se pasa a unas 60, y añadir una enumeración
nueva cuesta cinco líneas en vez de treinta. **Los códigos por cable no cambian** —siguen
siendo `obligatorio`, `cumple_parcial`…—: es refactorización pura.

La tolerancia actual (valor desconocido → valor por defecto en vez de excepción) se conserva
tal cual y por el mismo motivo: la fuente es un modelo de lenguaje y fallar duro ante una
variante léxica tiraría un análisis entero.

### 3.5 Casos de uso: uno por clase

`AgenteSecop` se parte. Cada servicio recibe solo los puertos que usa —lo que hace evidente
en la firma qué depende de qué—:

```java
@ApplicationScoped
public class AnalyzeTenderService implements AnalyzeTenderUseCase {

    private final LanguageModelPort models;
    private final PromptRendererPort prompts;

    @Inject
    public AnalyzeTenderService(LanguageModelPort models, PromptRendererPort prompts) {
        this.models = models;
        this.prompts = prompts;
    }

    @Override
    public TenderAnalysis analyze(AnalyzeTenderCommand command) {
        Prompt prompt = prompts.render(PromptTemplate.TENDER_ANALYSIS, command);
        return models.complete(prompt, command.modelSelection(), TenderAnalysis.class);
    }
}
```

Doce líneas frente a los actuales bloques de `StringBuilder`. La diferencia no es estética:
el caso de uso vuelve a decir *qué hace el negocio*, y el *cómo se le habla al modelo* se
prueba por separado.

`ValidateProposalService` mantiene la composición actual —extraer requisitos si no vienen—
pero lo hace **invocando el caso de uso de análisis por su interfaz**, no llamando a un
método de la misma clase. Eso permite que `SPEC-BE-02` le ponga un presupuesto de tiempo
global y que la composición sea visible en el diagrama de secuencia.

### 3.6 Prompts como plantillas, no como código

Hoy los prompts viven en constantes de `servicio/Prompts.java` y se ensamblan con
`StringBuilder`, incluido este mecanismo (`AgenteSecop.java:218-224`):

```java
String sistema = Prompts.SISTEMA_BASE.replace("""
        ## Formato de salida
        Responde ÚNICAMENTE con un objeto JSON…
        """, "") + "\n\n" + Prompts.INSTRUCCION_CHAT;
```

Eliminar un bloque del prompt con un `replace` de texto literal de tres líneas se rompe con
cualquier cambio de espaciado —incluido el de un formateador automático— y **falla en
silencio**: el prompt queda mal compuesto, el chat empieza a responder JSON y nada avisa.

Objetivo: plantillas Qute en `src/main/resources/prompts/`, una por tarea, con los fragmentos
compartidos incluidos explícitamente.

```
prompts/
├── _role.txt                 rol y marco normativo (compartido)
├── _json-output.txt          instrucción de formato JSON (se incluye o no se incluye)
├── tender-analysis.txt
├── proposal-generation.txt
├── proposal-validation.txt
├── process-ranking.txt
└── chat.txt                  incluye _role.txt y NO incluye _json-output.txt
```

La composición pasa a ser declarativa y verificable: una prueba comprueba que
`chat.txt` no contiene la instrucción de JSON, en vez de confiar en que un `replace`
coincida.

### 3.7 La regla, automatizada

`src/test/java/co/agentesecop/architecture/DependencyRuleTest.java`:

```java
@AnalyzeClasses(packages = "co.agentesecop",
                importOptions = ImportOption.DoNotIncludeTests.class)
class DependencyRuleTest {

    @ArchTest
    static final ArchRule domainIsPure = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..adapter..", "..application..", "..config..",
                    "com.fasterxml..", "jakarta..", "io.quarkus..",
                    "org.eclipse.microprofile..", "dev.langchain4j..");

    @ArchTest
    static final ArchRule applicationIgnoresAdapters = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..");

    @ArchTest
    static final ArchRule restLayerUsesPortsOnly = noClasses()
            .that().resideInAPackage("..adapter.in.rest..")
            .should().dependOnClassesThat().resideInAPackage("..application.service..");

    @ArchTest
    static final ArchRule hexagonalLayers = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy("..domain..")
            .layer("Application").definedBy("..application..")
            .layer("Adapters").definedBy("..adapter..")
            .whereLayer("Adapters").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapters");
}
```

`restLayerUsesPortsOnly` es la regla que más se olvida y la que más valor tiene: obliga a
que los recursos REST dependan de `port/in`, nunca de la implementación —el defecto exacto
de `ProcesosResource` hoy con `SecopCliente`—.

**Introducción sin bloquear:** en la fase 0 se añade con
`.because(…)` y una lista congelada de infracciones conocidas mediante
`FreezingArchRule.freeze(rule)`. La deuda existente queda registrada y **no puede crecer**;
cada paso de la fase 2 descongela una entrada.

### 3.8 Versión única

`api/SaludResource.VERSION = "0.2.0"`, `pom.xml` (`0.1.0`) y
`quarkus.smallrye-openapi.info-version` son tres verdades distintas hoy. Sustituir por
filtrado de recursos en tiempo de compilación:

```properties
quarkus.application.version=${project.version}
quarkus.smallrye-openapi.info-version=${project.version}
```

```java
@ConfigProperty(name = "quarkus.application.version") String version;
```

---

## 4. Plan de migración

Cada paso deja las 88 pruebas en verde y se puede entregar por separado.

| Paso | Contenido | Riesgo |
|---|---|---|
| 1 | Crear `domain/`, mover las enumeraciones tras `CodedEnum` + `CodedEnumModule`. Contrato por cable idéntico, verificado con las pruebas de recursos existentes. | Bajo |
| 2 | Duplicar los records de `dominio/` en `adapter/in/rest/dto/` con las anotaciones; crear los mapeadores; dejar los recursos usando DTO. | Medio |
| 3 | Retirar las anotaciones de framework de los tipos de dominio. Descongelar `domainIsPure`. | Bajo |
| 4 | Mover `ProveedorDisponible` y `EstadoSalud` fuera de `dominio` a `adapter/in/rest/dto`. | Bajo |
| 5 | Declarar `port/out/*` y hacer que los adaptadores actuales los implementen. `ProveedorIA` → `LanguageModelPort` (renombrado y recolocado, misma lógica). | Medio |
| 6 | Partir `AgenteSecop` en cinco servicios; `port/in`; recursos contra `port/in`. Descongelar `restLayerUsesPortsOnly`. | **Alto** |
| 7 | Prompts a Qute; prueba de composición de `chat.txt`. | Medio |
| 8 | Versión única. | Bajo |
| 9 | Descongelar el resto de reglas; la lista debe quedar vacía. | — |

El paso 6 es el único con riesgo alto y conviene hacerlo en una sola sesión, sin mezclar con
nada más.

---

## 5. Criterios de aceptación

1. `ArchUnit` pasa **sin ninguna regla congelada**.
2. `domain/` no contiene ningún `import` de `com.fasterxml`, `jakarta`, `io.quarkus`,
   `org.eclipse.microprofile` ni `dev.langchain4j`.
3. Existe una prueba unitaria de al menos una regla de negocio del dominio que se ejecuta
   **sin arrancar Quarkus** (JUnit puro, sin `@QuarkusTest`) y en menos de 100 ms.
4. Ninguna clase de `adapter/in/rest` importa de `application.service`.
5. `dominio/Enumeraciones.java` desaparece; los códigos por cable son byte a byte los
   mismos, comprobado por las pruebas de recursos existentes sin modificar sus aserciones.
6. Ninguna clase de `application/service` supera las 80 líneas.
7. `GET /api/salud` devuelve la versión del `pom.xml`; no queda ninguna versión literal en
   el código.
8. Existe una prueba que verifica que la plantilla de chat no contiene la instrucción de
   salida JSON.
9. Las 88 pruebas existentes pasan **sin modificar sus aserciones sobre el cuerpo HTTP**.
   Cualquier cambio en una aserción de contrato es una regresión, no una adaptación.

---

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| Se rompe el contrato HTTP en el paso 2 sin que nadie lo note. | Las pruebas de recursos (`ProcesosResourceTest`, `SaludResourceTest`) son el contrato. Su código de aserción no se puede tocar durante esta spec. |
| Duplicación dominio/DTO percibida como burocracia y abandonada a medias. | Peor que ambos extremos es quedarse en medio. Si se decide no separar, hay que dejarlo escrito en un ADR y retirar `domainIsPure` de ArchUnit, no dejarla congelada indefinidamente. |
| El paso 6 genera un diff enorme y no revisable. | Un commit por caso de uso extraído, no uno por los cinco. |
| Se pierden los comentarios que explican el porqué. | Los comentarios de decisión (los de `SecopCliente` sobre alias de Socrata, los de `EsquemasJson`, los de `HeuristicaTI` sobre límites de palabra) **se migran literalmente**. Son el activo mejor conservado del repositorio. |

---

## 7. Fuera de alcance

- Partir en módulos Maven separados. Se puede hacer después; ArchUnit ya da la garantía.
- Cambiar el contrato HTTP (`SPEC-BE-07`).
- Añadir persistencia. No hay caso de uso que la requiera.
- Reescribir los prompts. Se mueven de sitio y se recomponen de forma segura; su contenido
  no se toca en esta spec.
