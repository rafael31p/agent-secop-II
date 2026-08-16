# SPEC-BE-07 · Migración del código a inglés

| | |
|---|---|
| **Estado** | Propuesta |
| **Prioridad** | 🟡 Baja |
| **Cierra** | BE-B20 |
| **Depende de** | SPEC-BE-01 (estructura estable), SPEC-DOC-01 (glosario) |
| **Esfuerzo** | 3–5 jornadas |

---

## 1. Problema

El código está en español con inglés incrustado, no por descuido puntual sino de forma
sistemática. Muestra real:

```java
public String motivoNoDisponible()          // español
private final String datasetProcesos;       // mezcla
public record Detalle(String detail) {}     // los dos, en la misma línea
Multi<String> flujo(PeticionIA peticion);   // español para un concepto (stream) en inglés
config.maxTokens()  config.timeoutSegundos()  config.esperaBaseMillis()
p.scoreTi()  senalesTi  urlBase  baseUrl  apiKey  nombreModelo
```

`MensajeChat.rol` es un campo en español cuyos valores válidos son `"user"` y `"assistant"`.
`EstadoCumplimiento.CUMPLE_PARCIAL` es una constante en español que serializa
`cumple_parcial`. `ProveedorOpenAI` declara `urlBase()` y lo pasa a `baseUrl(...)`.

El coste no es estético: **obliga a decidir el idioma en cada nombre nuevo**, y las
decisiones sueltas son inconsistentes entre sí. Es fricción de baja intensidad y alta
frecuencia.

### 1.1 El término sobrecargado

El hallazgo que aparece al hacer el glosario: **«proveedor» significa tres cosas distintas**
en este código.

| Uso | Significado | Destino |
|---|---|---|
| `ProveedorIA`, `RegistroProveedores`, `ProveedorGemini` | proveedor de modelos de lenguaje | `AiProvider`, `ProviderRegistry` |
| `perfilProveedor`, `contextoProveedor` | el **oferente**, quien presenta la propuesta | `bidderProfile`, `bidderContext` |
| `ProveedorEspacio`, `ProveedorIA` (React) | el patrón *Provider* de React | `WorkspaceProvider`, `AiSelectionProvider` |

Que `perfilProveedor` y `ProveedorIA` compartan raíz es una ambigüedad real que ya confunde
al leer `AgenteSecop.priorizarProcesos`, donde conviven `solicitud.perfilProveedor()` y
`proveedor(solicitud.proveedor())`. Desambiguarlo es la mejora concreta de esta spec; el
idioma es el vehículo.

---

## 2. Decisión

**Dos decisiones separadas, con radios de impacto muy distintos.**

### Decisión 1 — identificadores internos → inglés

Paquetes, clases, métodos, campos, variables, constantes y nombres de prueba. Cambio interno
sin efecto observable desde fuera del proceso.

### Decisión 2 — contrato HTTP → **se mantiene en español**

Rutas (`/api/procesos/buscar`), campos JSON (`textoPliego`, `criticidad`), códigos de
enumeración (`obligatorio`, `cumple_parcial`) y claves de configuración
(`agente.ia.…`) **no cambian**.

Razones, en orden de peso:

1. **El contrato es el que menos gana y el que más rompe.** Cambiarlo obliga a tocar el
   frontend entero, invalida cualquier integración y no aporta ninguna ventaja de
   mantenimiento: los campos JSON no se autocompletan ni se refactorizan.
2. **Los códigos de enumeración son vocabulario del dominio, no del código.**
   `cumple_parcial`, `riesgo_de_rechazo`, `subsanable` son términos de contratación pública
   colombiana. Traducirlos a `partially_compliant` los aleja del usuario que los va a leer.
3. **El dominio es colombiano y el usuario es hispanohablante.** Un contrato en español para
   un dominio en español es coherente, no deuda.
4. Ya existe una capa de traducción: los DTO de `SPEC-BE-01` §3.3. El coste de mantener las
   dos convenciones es un mapeador que hay que escribir de todos modos.

Esto se registra como **ADR-0009**, porque es exactamente el tipo de decisión que alguien
querrá revisar dentro de un año.

Los mensajes de error visibles al usuario **siguen en español**. Los comentarios y el Javadoc
también: los escribe y los lee un equipo hispanohablante, y su valor está en el contenido.

---

## 3. Diseño

### 3.1 Convenciones

| Elemento | Convención |
|---|---|
| Paquetes | inglés, minúsculas: `domain.model.tender` |
| Clases | `PascalCase` inglés: `AnalyzeTenderService` |
| Interfaces de puerto | sin prefijo `I`; sufijo `Port` solo en `port/out` |
| Métodos | `camelCase` inglés, verbo primero: `analyze`, `findById` |
| Constantes | `UPPER_SNAKE` inglés: `MAX_DOCUMENT_CHARACTERS` |
| Pruebas | inglés en el nombre de clase, **español en el nombre del método** |
| Comentarios y Javadoc | español |
| Mensajes al usuario | español |
| Campos JSON y rutas | español (decisión 2) |

Los nombres de método de prueba se quedan en español a propósito: `noReintentaAnte401()` se
lee como una frase y es la documentación ejecutable del comportamiento. Traducirlo a
`doesNotRetryOn401` no gana nada para un equipo hispanohablante.

### 3.2 Mapa de renombrado

**Paquetes** (ya cubierto por `SPEC-BE-01`):

| Actual | Destino |
|---|---|
| `co.agentesecop.api` | `co.agentesecop.adapter.in.rest` |
| `co.agentesecop.dominio` | `co.agentesecop.domain.model` |
| `co.agentesecop.ia` | `co.agentesecop.adapter.out.llm` |
| `co.agentesecop.secop` | `co.agentesecop.adapter.out.procurement` |
| `co.agentesecop.servicio` | `co.agentesecop.application.service` |

**Clases:**

| Actual | Destino | Nota |
|---|---|---|
| `AgenteSecop` | 5 servicios de caso de uso | `SPEC-BE-01` |
| `ProveedorIA` | `LanguageModelPort` | «modelo de lenguaje», no «IA» |
| `RegistroProveedores` | `AiProviderRegistry` | |
| `ProveedorLangChain4j` | `LangChain4jLanguageModel` | |
| `PeticionIA` | `Prompt` | |
| `ErroresIA` | `ProviderException` (sellada) | `SPEC-BE-03` |
| `EsquemasJson` | `JsonSchemaHardener` | «endurecer» es lo que hace |
| `SecopCliente` | `SocrataProcurementCatalog` + colaboradores | `SPEC-BE-04` |
| `SecopApi` | `SocrataApiClient` | |
| `HeuristicaTI` | `TechRelevanceScorer` | |
| `ExtractorDocumentos` | `CompositeTextExtractor` | |
| `ManejadorErrores` | `ExceptionMappers` | |
| `ConfiguracionIA` | `AiProperties` | |
| `Prompts` | plantillas Qute | `SPEC-BE-01` §3.6 |

**Miembros con trampa:**

| Actual | Destino | Por qué no es mecánico |
|---|---|---|
| `flujo(...)` | `stream(...)` | |
| `estructurado(...)` | `complete(..., Class<T>)` | |
| `motivoNoDisponible()` | `unavailabilityReason()` | |
| `perfilProveedor` | `bidderProfile` | **No** `providerProfile`: es el oferente |
| `contextoProveedor` | `bidderContext` | Igual |
| `scoreTi` / `senalesTi` | `techScore` / `techSignals` | |
| `timeoutSegundos`, `esperaBaseMillis` | `timeout`, `baseDelay` como `Duration` | Se corrige el tipo, no solo el nombre |
| `urlBase()` vs `baseUrl(...)` | `baseUrl` | Se elimina la duplicidad |
| `Detalle(String detail)` | `ProblemDetail(String detail, String correlationId)` | `SPEC-BE-06` |
| `recortarACuerpoJson` | `trimToJsonBody` | |
| `noVacio` | `hasText` | |

Las filas de `perfilProveedor` y `timeoutSegundos` son las que justifican hacer esto a mano y
no con un `sed`: la primera cambia de concepto, la segunda cambia de tipo.

### 3.3 Enumeraciones: constantes en inglés, códigos intactos

```java
public enum Criticality implements CodedEnum {
    MANDATORY("obligatorio"),
    SCORED("ponderable"),
    DESIRABLE("deseable"),
    INFORMATIVE("informativo");
}
```

La constante Java cambia; **la cadena por cable no**. Las pruebas de recurso existentes, que
afirman sobre `"obligatorio"`, siguen pasando sin tocar una aserción. Esa es la prueba de que
el contrato se respeta.

### 3.4 Cómo se ejecuta

**Con la refactorización del IDE, no con búsqueda y reemplazo.** «Rename Symbol» de IntelliJ
entiende el ámbito: renombra usos, no coincidencias de texto, y no toca cadenas literales.
Un `sed` sobre `proveedor` destrozaría los tres significados a la vez, los mensajes de error
y los campos JSON.

**Un commit por grupo coherente, sin mezclar nada más:**

```
refactor(nombres): paquete secop → adapter.out.procurement
refactor(nombres): ProveedorIA → LanguageModelPort
refactor(nombres): perfilProveedor → bidderProfile (desambigua oferente/proveedor IA)
```

**La regla que hace esto seguro:** ningún commit de renombrado puede cambiar comportamiento.
Si al renombrar aparece un defecto, se anota y se arregla en un commit aparte. Un diff que
renombra 200 símbolos *y* corrige un `if` es un diff que nadie puede revisar.

### 3.5 Verificación

Además de las 88 pruebas, un guardián para que la mezcla no vuelva:

```java
@ArchTest
static final ArchRule sinIdentificadoresEnEspanol = classes()
        .that().resideOutsideOfPackage("..adapter.in.rest.dto..")
        .should(new ArchCondition<>("no usar términos de dominio en español") {
            // Lista cerrada del glosario: proveedor, pliego, requisito, propuesta,
            // solicitud, respuesta, busqueda, validacion, criticidad…
        });
```

Se excluye `adapter.in.rest.dto`, donde los campos en español son el contrato y deben estarlo.

La lista sale del glosario de `SPEC-DOC-01`, que por eso es prerrequisito duro: sin acuerdo
previo sobre las traducciones, el renombrado produce tres nombres distintos para el mismo
concepto y la migración empeora la consistencia en vez de mejorarla.

---

## 4. Plan de ejecución

| Paso | Contenido |
|---|---|
| 1 | Verificar que el glosario está completo y aprobado (ADR-0009). |
| 2 | Paquetes (llega hecho de `SPEC-BE-01`). |
| 3 | Clases, por paquete, un commit cada uno. |
| 4 | Métodos y campos públicos. |
| 5 | Campos privados y variables locales. |
| 6 | Constantes de enumeración, con las pruebas de contrato como red. |
| 7 | Nombres de clase de prueba (métodos en español, se conservan). |
| 8 | Regla de ArchUnit. |
| 9 | Actualizar los diagramas de `SPEC-DOC-02` con los nombres nuevos. |

Va al final del plan general porque es el cambio con más líneas tocadas y menos riesgo
semántico. Hacerlo antes obligaría a renombrar dos veces todo lo que las fases 2 y 3 mueven.

---

## 5. Criterios de aceptación

1. Ningún identificador fuera de `adapter.in.rest.dto` contiene un término de dominio en
   español, verificado por ArchUnit.
2. Las 88 pruebas pasan **sin modificar ninguna aserción sobre cuerpos HTTP**.
3. `GET /api/proveedores`, `POST /api/procesos/buscar` y el resto devuelven respuestas
   byte a byte idénticas para la misma entrada.
4. Ningún commit de esta spec mezcla renombrado con cambio de comportamiento.
5. Los comentarios de decisión se conservan en español y no se han perdido.
6. `perfilProveedor` y `ProveedorIA` han dejado de compartir raíz: el oferente es `bidder` y
   el proveedor de modelos es `aiProvider`.
7. Existe ADR-0009 justificando por qué el contrato HTTP se queda en español.
8. Los diagramas usan los nombres nuevos.

---

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| Un renombrado toca sin querer un literal del contrato. | Refactorización del IDE, nunca `sed`. Las pruebas de recurso son el contrato y sus aserciones no se pueden tocar. |
| El diff gigante oculta un cambio real. | Un commit por grupo; ninguno mezcla comportamiento. Revisable con `--word-diff`. |
| El equipo discute traducciones durante la migración. | Se cierra antes en el glosario y el ADR. Durante la ejecución no se renegocia. |
| Se pierden comentarios al mover código. | Criterio de aceptación 5, verificado en la revisión. |
| Alguien decide «ya que estamos» y traduce también el JSON. | La decisión 2 es explícita y está en un ADR. |

---

## 7. Fuera de alcance

- El contrato HTTP (decisión 2, explícita).
- Los mensajes al usuario, que siguen en español.
- Comentarios y Javadoc.
- Los nombres de método de prueba.
- El frontend (`SPEC-FE-05`).
