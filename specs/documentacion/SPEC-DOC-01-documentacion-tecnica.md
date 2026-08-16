# SPEC-DOC-01 · Documentación técnica, ADR y contrato OpenAPI como fuente única

| | |
|---|---|
| **Estado** | Propuesta |
| **Prioridad** | 🟠 Media |
| **Cierra** | TR-M5, TR-B6, FE-B18, BE-B20 (glosario) |
| **Depende de** | SPEC-BE-01 (la documentación describe la estructura nueva) |
| **Esfuerzo** | 4–6 jornadas |

---

## 1. Problema

El repositorio tiene dos documentos buenos y ninguno de los que hacen falta.

`README.md` explica **qué hay** y cómo arrancarlo. `ESTADO.md` explica **dónde quedó cada
cosa**. Ambos están bien escritos y son honestos —`ESTADO.md` incluso corrige un error de
una versión anterior de sí mismo—. Lo que falta es el **porqué**, y falta en tres formas
distintas:

### 1.1 Las decisiones fuertes viven en comentarios de código

El proyecto ha tomado al menos cinco decisiones de arquitectura no obvias, cada una con
alternativas descartadas por razones concretas:

| Decisión | Dónde está documentada hoy |
|---|---|
| Interfaz propia `ProveedorIA` en vez de la extensión LangChain4j de Quarkus | Comentario en `pom.xml` + Javadoc de `ProveedorIA` + `ESTADO.md` |
| Selección de proveedor y modelo **por petición** | Javadoc de `Solicitudes` + `ESTADO.md` |
| DeepSeek heredando de la implementación de OpenAI | Javadoc de `ProveedorDeepSeek` |
| Frontend llamando al backend directo, sin proxy de Next | Comentario en `next.config.ts` + `ESTADO.md` |
| WireMock como biblioteca en vez de Testcontainers | Comentario en `pom.xml` |

Los comentarios son buenos, pero un comentario documenta una línea de código, no una
decisión. Cuando `SPEC-BE-03` proponga que DeepSeek deje de heredar de OpenAI, ¿dónde queda
constancia de que se evaluó y por qué se cambió de opinión? Hoy: en ninguna parte. El
comentario simplemente desaparece con el `git rm`.

Peor: `ESTADO.md` es un documento de estado. Se **sobrescribe** en cada sesión. La sección
«Decisiones tomadas (para no repetir la discusión)» tiene exactamente el propósito de un
ADR y exactamente la vida útil equivocada.

### 1.2 El contrato de la API está definido dos veces (FE-B18)

`frontend-next/lib/tipos.ts` son 203 líneas escritas a mano que replican los records de
`backend-quarkus/src/main/java/co/agentesecop/dominio/`. El backend ya publica OpenAPI en
`/q/openapi` y Swagger UI en `/docs`, pero nadie consume ese contrato: se copia a mano.

Esto no es hipotético. `ESTADO.md` documenta el fallo que ya produjo:

> Los dos backends no exponen el mismo contrato, al contrario de lo que decía este
> documento: el de Python serializa en `snake_case` y el de Quarkus en `camelCase`. Por eso
> el frontend viejo solo funciona contra el backend viejo. Se descubrió al empezar la
> migración, comparando `frontend/src/types.ts` con los records de `dominio/`.

Un contrato duplicado a mano se desincroniza; se descubrió comparando dos archivos a ojo. El
mecanismo que lo evita —generar los tipos— ya está disponible y sin usar.

### 1.3 No hay nada que explique cómo operar ni cómo contribuir

Ni runbook (qué hacer cuando el análisis falla, cómo rotar una clave, qué significa
«degradado» en `/api/salud`), ni guía de contribución, ni criterio de «terminado», ni
glosario. Este último es un prerrequisito duro de `SPEC-BE-07`: no se puede renombrar
`RespuestaValidacion` a inglés sin acordar antes si `pliego` es *tender document*,
*specifications* o *bidding documents* —y en contratación pública colombiana la traducción
importa, porque *pliego de condiciones* es un término legal con contenido preciso—.

---

## 2. Decisión

Cinco entregables, cada uno con un mecanismo que impide que envejezca:

1. **ADR** en formato MADR, en `docs/adr/`, numerados e inmutables.
2. **OpenAPI como fuente única del contrato**, con los tipos del frontend generados.
3. **README por módulo**, con el README raíz reducido a orientación.
4. **Runbook de operación** y **guía de contribución**.
5. **Glosario bilingüe** dominio ES ↔ código EN.

La regla que los mantiene vivos: **lo que se puede verificar en CI, se verifica en CI**. Un
diagrama desactualizado o unos tipos regenerados con diferencias rompen la compilación. Lo
que no se puede verificar (el porqué de un ADR) se hace inmutable, que es la otra forma de
que no mienta.

---

## 3. Diseño

### 3.1 Estructura

```
docs/
├── README.md                    índice de la documentación
├── adr/
│   ├── 0000-plantilla.md
│   ├── 0001-abstraccion-propia-sobre-langchain4j.md
│   ├── 0002-seleccion-de-proveedor-por-peticion.md
│   ├── 0003-sin-proxy-de-next-para-preservar-el-streaming.md
│   ├── 0004-wiremock-en-la-misma-jvm-sin-testcontainers.md
│   ├── 0005-esquema-json-endurecido-para-salidas-estructuradas.md
│   ├── 0006-arquitectura-hexagonal.md
│   ├── 0007-resiliencia-declarativa-con-microprofile.md
│   ├── 0008-openapi-como-fuente-unica-del-contrato.md
│   └── 0009-idioma-del-codigo-y-del-contrato-http.md
├── arquitectura/                ver SPEC-DOC-02 (diagramas)
├── glosario.md
├── runbook.md
└── contribuir.md

backend-quarkus/README.md        cómo trabajar en el backend
frontend-next/README.md          cómo trabajar en el frontend
README.md                        qué es esto y a dónde ir
```

### 3.2 ADR: formato y regla de inmutabilidad

Formato MADR reducido. Plantilla:

```markdown
# ADR-000N · <título en presente, decisión no problema>

- **Estado:** propuesta | aceptada | sustituida por [ADR-000M](000M-….md)
- **Fecha:** AAAA-MM-DD
- **Decide:** <quién>

## Contexto
Qué situación obliga a decidir. Hechos, no opiniones.

## Opciones consideradas
1. …
2. …

## Decisión
Qué se elige y **por qué esa y no las otras**.

## Consecuencias
Lo bueno y lo malo. Lo malo es obligatorio: un ADR sin costes no se ha pensado.
```

**Un ADR nunca se edita.** Si la decisión cambia, se escribe uno nuevo y el viejo pasa a
`sustituida por`. Es lo que distingue un ADR de una página de wiki, y es exactamente la
propiedad que a `ESTADO.md` le falta.

Los ADR 0001 a 0005 son **retroactivos**: documentan decisiones ya tomadas, y su contenido
ya existe disperso en comentarios y en `ESTADO.md`. Escribirlos es sobre todo un ejercicio
de recolección. Ejemplo del 0001, con el material que ya hay:

```markdown
# ADR-0001 · Abstracción propia sobre LangChain4j, no la extensión de Quarkus

- **Estado:** aceptada
- **Fecha:** 2026-08-06

## Contexto
El agente debe poder usar cinco proveedores de modelos (Gemini, OpenAI, Anthropic, DeepSeek,
Ollama) y el usuario elige proveedor y modelo **en cada petición** desde la interfaz.

## Opciones consideradas
1. `quarkus-langchain4j-*` como extensión de Quarkus.
2. LangChain4j como biblioteca simple, detrás de una interfaz propia (`ProveedorIA`).
3. Llamar a las APIs REST de cada proveedor directamente.

## Decisión
Opción 2. Las extensiones de Quarkus cablean proveedor y modelo **por configuración**, en
tiempo de arranque; el requisito es elegirlos por petición, así que la extensión trabaja en
contra. La opción 3 significaría mantener cinco clientes HTTP y cinco formatos de salida
estructurada.

## Consecuencias
**A favor:** DeepSeek costó ~60 líneas al ser compatible con la API de OpenAI. LangChain4j es
sustituible sin que el resto de la aplicación se entere.
**En contra:** se pierde la integración automática de Quarkus (métricas, salud, configuración
por extensión) y hay que construirla a mano — lo que efectivamente ocurrió, y es la deuda que
SPEC-BE-02 y SPEC-BE-05 pagan.
```

Ese último párrafo es el que justifica todo el ejercicio: conecta una decisión de agosto con
el trabajo de hoy, y deja constancia de que la deuda fue una consecuencia aceptada, no un
descuido.

### 3.3 OpenAPI como fuente única (FE-B18)

**Backend — el contrato se publica como artefacto versionado:**

```properties
quarkus.smallrye-openapi.store-schema-directory=target/openapi
quarkus.smallrye-openapi.info-version=${project.version}
```

`mvnw package` deja `target/openapi/openapi.yaml`. Se copia a `docs/api/openapi.yaml` y **se
versiona en git**: así el cambio de contrato es visible en el diff de la revisión, que es
donde tiene que verse.

**Frontend — los tipos se generan, no se escriben:**

```json
{
  "scripts": {
    "api:tipos": "openapi-typescript ../docs/api/openapi.yaml -o lib/api/esquema.ts",
    "api:verificar": "npm run api:tipos && git diff --exit-code lib/api/esquema.ts"
  },
  "devDependencies": { "openapi-typescript": "^7" }
}
```

`lib/tipos.ts` pasa de 203 líneas escritas a mano a un archivo generado más una capa fina de
alias legibles:

```ts
// lib/api/tipos.ts — la única parte escrita a mano
import type { components } from "./esquema";

export type TenderAnalysis = components["schemas"]["RespuestaAnalisis"];
export type ProcurementProcess = components["schemas"]["ProcesoResumen"];
export type ComplianceReport = components["schemas"]["RespuestaValidacion"];
```

Esa capa de alias no es ceremonia: es lo que permite que `SPEC-FE-05` renombre a inglés en
el frontend sin tocar el contrato HTTP, que sigue en español. Los nombres del esquema
generado son los del cable; los alias son los del código.

**CI:** `npm run api:verificar` falla si el esquema publicado y los tipos generados
divergen. Es el mecanismo que hace imposible repetir el incidente de `snake_case`.

### 3.4 README por módulo

El `README.md` raíz actual mezcla presentación, árbol de directorios, instrucciones de
arranque de cuatro módulos y notas de migración. Se reduce a:

- Qué es el Agente SECOP II y para quién.
- El descargo (herramienta de apoyo analítico, no asesoría jurídica) — **se conserva
  literal**, es lo más importante del documento.
- Arranque rápido: dos comandos.
- Tabla de a dónde ir: `docs/arquitectura` para entender, `docs/adr` para el porqué,
  `docs/runbook.md` para operar, `docs/contribuir.md` para cambiar.

El árbol de directorios **se elimina**: es exactamente lo que un `ls` reconstruye y lo
primero que envejece —de hecho ya envejeció: describe `backend/` y `frontend/` como
«se conserva de referencia» cuando el plan es archivarlos (TR-B6)—.

`ESTADO.md` se retira. Su contenido se reparte: las decisiones a ADR, las restricciones del
entorno al README del backend, los pendientes a issues. Un documento de estado es lo que
sustituye al historial de git cuando no hay historial de git; con el `git init` de la fase 0
deja de tener razón de ser.

### 3.5 Runbook

`docs/runbook.md`, escrito para quien recibe una alerta a las tres de la mañana y no escribió
el código. Un procedimiento por síntoma, no por componente:

| Síntoma | Diagnóstico | Acción |
|---|---|---|
| `/q/health/ready` en `DOWN` | ¿Qué sonda? `language-models` vs `procurement-source` | Si es la primera: revisar cortacircuitos por proveedor en `/q/metrics` |
| Todos los análisis devuelven 503 | Ninguna clave configurada, o todos los circuitos abiertos | Verificar `GET /api/proveedores`; el `motivo` de cada uno lo dice |
| Análisis lentos | `llm_request_seconds` por proveedor y modelo | Cuota agotada del plan gratuito: cambiar de modelo o de proveedor |
| Búsquedas devuelven 502 | Socrata caído o esquema cambiado | Ver `procurement_request_total{outcome}`; los alias de columna son lo primero que rompe |
| Gasto inesperado de tokens | `llm_tokens_total` por proveedor | Revisar claves de API activas; ver rotación abajo |
| Un usuario reporta un error | Pedirle el `correlationId` del mensaje | `grep` del identificador en el registro JSON |

Más los procedimientos de rotación de claves, de cambio de proveedor por defecto y de
despliegue con reversión.

### 3.6 Glosario bilingüe

Prerrequisito de `SPEC-BE-07`. Tres columnas, y la tercera es la que evita discusiones:

| Español (dominio) | Inglés (código) | Nota |
|---|---|---|
| pliego de condiciones | `tender document` | **No** *specifications*: es un documento con contenido legal definido, no una especificación técnica |
| proceso de contratación | `procurement process` | La unidad que publica SECOP II |
| entidad (contratante) | `contracting authority` | Terminología de contratación pública, no *company* |
| oferente / proveedor | `bidder` | **Cuidado:** en este código «proveedor» significa además *AI provider*. La ambigüedad del español es una de las razones para migrar |
| modalidad de selección | `selection procedure` | Licitación pública, concurso de méritos, etc. |
| requisito habilitante | `eligibility requirement` | Distinto de *scored*: habilita, no puntúa |
| criticidad: obligatorio / ponderable / deseable | `mandatory` / `scored` / `desirable` | Los códigos por cable **no cambian** |
| subsanable | `curable` | Concepto del régimen colombiano sin equivalente directo; se conserva la nota |
| causal de rechazo | `rejection ground` | |
| veredicto | `verdict` | |
| matriz de cumplimiento | `compliance matrix` | |
| relevancia TI | `tech relevance` | La heurística local, no la clasificación del modelo |

La fila de «proveedor» es el hallazgo del propio ejercicio: el término está sobrecargado en
el código actual (`ProveedorIA`, `perfilProveedor`, `contextoProveedor`, `ProveedorEspacio`
en React) con tres significados distintos. Separarlo en `AiProvider`, `bidder` y `Provider`
(el patrón de React) es una mejora de claridad, no solo de idioma.

---

## 4. Plan de ejecución

| Paso | Contenido | Depende de |
|---|---|---|
| 1 | Glosario. Es lo primero: bloquea `SPEC-BE-07`. | — |
| 2 | ADR 0001–0005 retroactivos, recolectando comentarios y `ESTADO.md`. | — |
| 3 | ADR 0006–0009 de las decisiones que toman estas specs. | Fases 2–3 |
| 4 | OpenAPI versionado + generación de tipos + verificación en CI. | SPEC-BE-01 |
| 5 | README por módulo; reducir el raíz; retirar `ESTADO.md`. | Fase 0 |
| 6 | Runbook. | SPEC-BE-05 |
| 7 | Guía de contribución y definición de terminado. | Fase 0 |

Los pasos 1 y 2 se pueden hacer hoy, sin esperar a ninguna refactorización, y el 2 es más
urgente de lo que parece: cada refactorización de las fases 2 y 3 **borra comentarios que
son la única constancia de una decisión**. Si los ADR retroactivos no se escriben antes, esa
información se pierde en el diff.

---

## 5. Criterios de aceptación

1. Existe un ADR por cada una de las cinco decisiones fuertes ya tomadas, y su sección de
   consecuencias incluye los costes reales, no solo los beneficios.
2. Ningún ADR aceptado se ha editado después de su fecha; los cambios de rumbo son ADR
   nuevos con `sustituida por`.
3. `docs/api/openapi.yaml` está versionado y CI falla si el backend publica un contrato
   distinto.
4. `npm run api:verificar` falla si los tipos generados difieren de los versionados.
5. `frontend-next/lib/tipos.ts` desaparece; ningún tipo de respuesta de la API se escribe a
   mano.
6. El README raíz no contiene el árbol de directorios ni instrucciones de los módulos
   retirados, y conserva el descargo literal.
7. El glosario cubre todo término de dominio que aparezca en un identificador renombrado por
   `SPEC-BE-07` o `SPEC-FE-05`.
8. El runbook tiene un procedimiento por cada alerta definida en `SPEC-NT-03`.
9. `ESTADO.md` ya no existe y ninguna información suya se ha perdido: cada punto tiene
   destino documentado.

---

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| Los ADR se escriben una vez y nadie los vuelve a tocar. | Es el comportamiento correcto: son inmutables. El riesgo real es que no se escriba el siguiente; la guía de contribución lo incluye en la definición de terminado para cambios de arquitectura. |
| Los tipos generados son feos y el equipo vuelve a escribirlos a mano. | La capa de alias de §3.3 existe justamente para eso: el código de la aplicación nunca ve un tipo generado directamente. |
| Retirar `ESTADO.md` pierde el contexto de retomada entre sesiones. | Su función la cubren el historial de git y los issues, que es donde debe estar. El paso 5 exige el reparto punto por punto antes de borrar. |
| El glosario se convierte en discusión terminológica interminable. | Lo decide una persona y se registra como ADR-0009. Un glosario imperfecto y acordado vale más que uno perfecto y pendiente. |

---

## 7. Fuera de alcance

- Documentación de usuario final. `SPEC-NT-01` cubre lo que la interfaz debe comunicar.
- Publicación de la documentación en un sitio (MkDocs, Docusaurus). Markdown en el
  repositorio se lee bien en cualquier visor y no añade un despliegue que mantener.
- Javadoc publicado. Los comentarios de decisión que ya existen en el código valen más que
  un Javadoc generado, y se conservan.
