# SPEC-FE-05 · Migración a inglés y contrato tipado generado

| | |
|---|---|
| **Estado** | Propuesta |
| **Prioridad** | 🟡 Baja |
| **Cierra** | FE-B17, FE-B18 |
| **Depende de** | SPEC-DOC-01 (glosario y OpenAPI), SPEC-FE-01 (estructura estable) |
| **Esfuerzo** | 2–4 jornadas |

---

## 1. Problema

### 1.1 Mezcla de idiomas (FE-B17)

El código del cliente mezcla igual que el del backend, pero con un agravante: la API de React
y del navegador es en inglés y no se puede cambiar, así que la mezcla ocurre **dentro de la
misma expresión**.

```ts
const lector = respuesta.body.getReader();
const decodificador = new TextDecoder();
let bufer = "";
const bloques = bufer.split("\n\n");
useEffect(() => () => abortar.current?.abort(), []);
```

Los tipos son en español (`RespuestaAnalisis`, `ItemCumplimiento`), con campos en español y
valores en inglés: `MensajeChat.rol: "user" | "assistant"`.

Y se repite la sobrecarga de «proveedor» del backend, aquí con una tercera acepción: el
patrón *Provider* de React.

| Uso | Significado |
|---|---|
| `ProveedorDisponible`, `useIA().proveedor` | proveedor de modelos de lenguaje |
| `perfilProveedor`, `contextoProveedor` | el oferente |
| `ProveedorEspacio`, `ProveedorIA` (componentes) | el patrón Provider de React |

`ProveedorIA` es lo peor de los tres mundos: es un componente Provider de React **cuyo
nombre completo** significa además «proveedor de IA». Al leer `<ProveedorIA>` en
`app/proveedores.tsx` no se sabe si envuelve un contexto o si representa un proveedor.

### 1.2 El contrato se mantiene a mano (FE-B18)

`lib/tipos.ts` son 203 líneas escritas a mano que replican los records de
`co.agentesecop.dominio`. Es duplicación de conocimiento **entre módulos**, y es el mecanismo
que ya produjo el incidente documentado en `ESTADO.md`: el backend Python serializaba en
`snake_case`, el de Quarkus en `camelCase`, y se descubrió comparando dos archivos a ojo al
empezar la migración.

El backend publica OpenAPI en `/q/openapi` y nadie lo consume.

---

## 2. Decisión

Las mismas dos decisiones que en `SPEC-BE-07`, con una tercera propia del cliente:

1. **Identificadores internos → inglés.** Componentes, hooks, funciones, variables, tipos.
2. **El contrato HTTP se mantiene en español.** No cambia nada por el cable.
3. **Los tipos del contrato se generan, no se escriben.** Con una capa fina de alias en
   inglés encima.

La tercera decisión es la que hace las otras dos baratas: los nombres del esquema generado
son los del cable (español) y los alias son los del código (inglés). La traducción vive en un
archivo de diez líneas en vez de en 203 escritas a mano.

Los textos de la interfaz **siguen en español**: el usuario es hispanohablante y el dominio
es colombiano.

---

## 3. Diseño

### 3.1 Tipos generados

```json
{
  "scripts": {
    "api:tipos": "openapi-typescript ../docs/api/openapi.yaml -o dominio/esquema.ts",
    "api:verificar": "npm run api:tipos && git diff --exit-code dominio/esquema.ts"
  }
}
```

```ts
// dominio/tipos.ts — lo único escrito a mano
import type { components } from "./esquema";

type E = components["schemas"];

export type TenderAnalysis      = E["RespuestaAnalisis"];
export type TechnicalRequirement = E["RequisitoTecnico"];
export type DetectedRisk        = E["RiesgoDetectado"];
export type Proposal            = E["RespuestaPropuesta"];
export type ComplianceReport    = E["RespuestaValidacion"];
export type ComplianceItem      = E["ItemCumplimiento"];
export type ProcurementProcess  = E["ProcesoResumen"];
export type ProcessFilter       = E["FiltroProcesos"];
export type AiProvider          = E["ProveedorDisponible"];
export type HealthStatus        = E["EstadoSalud"];

// Los códigos por cable no cambian: son vocabulario del dominio colombiano.
export type Criticality       = TechnicalRequirement["criticidad"];   // "obligatorio" | …
export type ComplianceStatus  = ComplianceItem["estado"];             // "cumple_parcial" | …
```

`lib/tipos.ts` desaparece. Los campos siguen accediéndose en español
(`analisis.resumenEjecutivo`) porque son los del JSON; los **tipos** están en inglés, que es
lo que aparece en firmas y en errores del compilador.

Esa asimetría es deliberada y conviene entenderla: un campo en español no cuesta nada —se
escribe una vez y el editor lo autocompleta—, mientras que un tipo en español aparece en cada
firma, cada genérico y cada mensaje de error.

**CI:** `npm run api:verificar` falla si el esquema publicado y los tipos versionados
divergen. Es lo que hace imposible repetir el incidente de `snake_case`.

### 3.2 Mapa de renombrado

**Archivos y carpetas** (mayormente cubierto por `SPEC-FE-01`):

| Actual | Destino |
|---|---|
| `componentes/` | `components/` |
| `componentes/vistas/` | `components/views/` |
| `componentes/comunes.tsx` | `components/ui/*` |
| `lib/` | `domain/`, `application/`, `infrastructure/` |
| `pruebas/` | `tests/` |

**Componentes y contextos — donde está el valor real:**

| Actual | Destino | Por qué |
|---|---|---|
| `ProveedorEspacio` | `WorkspaceProvider` | Provider de React, no un proveedor |
| `ProveedorIA` (componente) | `AiSelectionProvider` | Ídem, y desambigua |
| `Proveedores` (en `app/`) | `AppProviders` | |
| `useEspacio` | `useWorkspace` | |
| `useIA` | `useAiSelection` | |
| `Cabecera` / `Pie` | `Header` / `Footer` | |
| `SelectorIA` / `ResumenIA` | `AiSelector` / `AiSummary` | |
| `Buscar`/`Analizar`/`Proponer`/`Validar`/`Consultar` | `SearchView`/`AnalyzeView`/`ProposeView`/`ValidateView`/`AskView` | El sufijo evita chocar con los verbos de los hooks |
| `Tarjeta`/`Aviso`/`Etiqueta`/`Medidor`/`Vacio`/`Cargando` | `Card`/`Alert`/`Badge`/`Gauge`/`Empty`/`Spinner` | |
| `perfilProveedor` | `bidderProfile` | **No** `providerProfile`: es el oferente |
| `chatStream` / `alFragmento` / `senal` | `openChatStream` / `onChunk` / `signal` | |
| `bufer` / `lector` / `decodificador` | `buffer` / `reader` / `decoder` | Coherencia con la API del navegador |
| `interpretarBloque` | `parseSseBlock` | |
| `limpiarFiltro` | `stripEmptyFilters` | Dice lo que hace, no solo «limpiar» |
| `ordenarPorSeveridad` | `sortBySeverity` | |
| `formatearCOP` / `formatearFecha` | `formatCop` / `formatDate` | |
| `construirContexto` | `buildAgentContext` | |
| `ErrorApi` | `ApiError` | |

**Rutas del App Router: `/analizar`, `/proponer`, `/validar`, `/consultar` NO cambian.** Son
URL visibles para el usuario, en español, coherentes con la interfaz. Cambiarlas rompería
marcadores por cero beneficio — el mismo razonamiento que mantiene el contrato HTTP en
español.

### 3.3 Claves de almacenamiento

```ts
export const STORAGE_KEYS = {
  bidderProfile: "agente-secop:perfil-proveedor",   // se conserva el valor
  workspace: "agente-secop:espacio",
  tenderDocument: "agente-secop:pliego",            // nueva (SPEC-FE-02 §3.5)
  aiSelection: "agente-secop:ia",
} as const;
```

**Las cadenas no cambian**, solo los nombres de las constantes. Cambiar la clave descartaría
el espacio de trabajo guardado de cualquier usuario con una pestaña abierta — una pérdida de
datos gratuita por un cambio cosmético.

### 3.4 Nombres de prueba

Igual que en el backend: **clase en inglés, caso en español.**

```ts
describe("AnalyzeView", () => {
  it("deshabilita el botón cuando el pliego tiene menos de 40 caracteres", …);
  it("transfiere los requisitos extraídos al espacio de trabajo", …);
});
```

El nombre del caso es la especificación ejecutable y se lee mejor en el idioma del equipo.

### 3.5 Ejecución

Renombrado del editor (`F2` en VS Code / TypeScript Language Server), nunca búsqueda y
reemplazo: `proveedor` aparece con tres significados distintos y también dentro de textos de
interfaz en español que **no** deben cambiar.

Un commit por grupo, ninguno con cambio de comportamiento. Con `git mv` para preservar el
historial de los archivos que se mueven.

Guardián para que no vuelva:

```js
// eslint.config.mjs
"no-restricted-syntax": ["error", {
  selector: "Identifier[name=/^(proveedor|pliego|requisito|propuesta|solicitud|respuesta|criticidad|espacio|cargando)/i]",
  message: "Identificadores en inglés (ver docs/glosario.md). Los textos de interfaz y los campos del contrato siguen en español.",
}],
```

Con exclusión para `dominio/esquema.ts` (generado) y para los archivos de textos de interfaz.

---

## 4. Plan de ejecución

| Paso | Contenido |
|---|---|
| 1 | Glosario aprobado (`SPEC-DOC-01`) y OpenAPI versionado. |
| 2 | Generar `esquema.ts`; crear `dominio/tipos.ts`; **verificar que `tsc` no reporta diferencias** contra el `lib/tipos.ts` actual. |
| 3 | Borrar `lib/tipos.ts`; `api:verificar` en CI. |
| 4 | Renombrar contextos y hooks (`WorkspaceProvider`, `useWorkspace`, `AiSelectionProvider`, `useAiSelection`). |
| 5 | Renombrar componentes de `ui/`. |
| 6 | Renombrar vistas y sus archivos. |
| 7 | Renombrar funciones de dominio e infraestructura. |
| 8 | Renombrar archivos de prueba; los casos siguen en español. |
| 9 | Regla de ESLint. |

El paso 2 es el más valioso del conjunto y se puede hacer solo: comparar el tipo generado
contra el escrito a mano **es una auditoría del contrato**. Si aparece alguna diferencia, es
un defecto real que hoy nadie ve — exactamente la clase de defecto que causó el incidente de
`snake_case`.

---

## 5. Criterios de aceptación

1. `lib/tipos.ts` no existe; ningún tipo de respuesta de la API se escribe a mano.
2. `npm run api:verificar` falla si el esquema del backend cambia sin regenerar los tipos, y
   está en CI.
3. El paso 2 se ejecutó y su resultado está documentado: o no había diferencias, o las que
   había se corrigieron como defectos.
4. Ningún identificador de `components/`, `application/`, `domain/` o `infrastructure/` usa
   un término de dominio en español, verificado por ESLint.
5. Los textos de la interfaz siguen íntegramente en español.
6. Las rutas del App Router no han cambiado.
7. Las claves de `sessionStorage` y `localStorage` no han cambiado de valor.
8. `perfilProveedor` es ahora `bidderProfile` y ningún componente Provider de React se llama
   «Proveedor».
9. Las 64 pruebas pasan; ningún commit mezcla renombrado con comportamiento.
10. `npx tsc --noEmit` sin errores en cada paso.

---

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| Los tipos generados difieren de los escritos a mano y hay que arreglar código. | Es el objetivo, no el riesgo: cualquier diferencia es un defecto latente. El paso 2 lo aísla antes de renombrar nada. |
| Un renombrado toca un texto de interfaz en español. | Renombrado del editor, que no toca literales. Las pruebas afirman sobre textos visibles y lo detectarían. |
| Cambiar claves de almacenamiento descarta el trabajo de los usuarios. | Explícitamente prohibido; criterio de aceptación 7. |
| `openapi-typescript` genera tipos incómodos para uniones y nulos. | La capa de alias los normaliza. Si algún tipo generado es inutilizable, se envuelve ahí, nunca se reescribe a mano. |
| Renombrar carpetas rompe los alias `@/…` de `tsconfig`. | El alias apunta a la raíz; los imports se actualizan con el renombrado del editor. `tsc --noEmit` en cada paso. |

---

## 7. Fuera de alcance

- El contrato HTTP y los códigos de enumeración (decisión conjunta con `SPEC-BE-07`).
- Las rutas visibles del App Router.
- Los textos de la interfaz.
- Internacionalización.
- Los nombres de los casos de prueba.
