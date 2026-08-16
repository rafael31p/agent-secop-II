# Estado del proyecto — 16 de agosto de 2026

Punto de retomada para la próxima sesión.

## Dónde quedó cada cosa

| Componente | Ruta | Estado |
|---|---|---|
| Backend Java (Quarkus) | `backend-quarkus/` | **Terminado y verificado**: 88/88 pruebas y verificación en vivo completa. |
| Frontend Next.js | `frontend-next/` | **Terminado y verificado**: 64/64 pruebas y recorrido completo en el navegador. |
| Backend Python (FastAPI) | `backend/` | Versión anterior. Funciona, ya es redundante. |
| Frontend React (Vite) | `frontend/` | Versión anterior. **No sirve contra el backend Quarkus** (ver abajo). |

**Los dos backends no exponen el mismo contrato**, al contrario de lo que decía este
documento: el de Python serializa en `snake_case` y el de Quarkus en `camelCase`. Por eso
el frontend viejo solo funciona contra el backend viejo. Se descubrió al empezar la
migración, comparando `frontend/src/types.ts` con los records de `dominio/`.

Ambos backends usan el puerto 8000: solo uno a la vez.

## Fases 0 y 1 del plan de mejora: ejecutadas

El repositorio ya es git, con CI, y las dos fases de mayor valor del
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

Estado de las suites: **119/119 backend · 74/74 frontend**.

Verificado en el navegador tras los cambios: búsqueda, cancelación de una
priorización sin dejar error, chat en streaming completo, foco visible con
teclado y atributos ARIA presentes en el DOM.

## Fase 2 · Arquitectura hexagonal: en curso

De los nueve pasos de SPEC-BE-01 §4, hechos **1, 4 y 8**, y el 3 a medias.

| Paso | Estado | Qué quedó |
|---|---|---|
| 1 · Enumeraciones tras `CodedEnum` | ✅ | Cinco copias del mismo molde → una. `CodedEnumModule` en el adaptador. |
| 2 · DTO de respuesta + mapeadores | ⬜ | Es lo que falta para vaciar `dominio/`. |
| 3 · Quitar frameworks del dominio | 🟡 | Las solicitudes ya salieron (417 → 166 infracciones). Faltan las respuestas. |
| 4 · `EstadoSalud` y `ProveedorDisponible` fuera | ✅ | No eran dominio: uno describe la instalación, otro un proveedor de modelos. |
| 5 · Puertos de salida | ⬜ | Bloqueado: ver abajo. |
| 6 · Partir `AgenteSecop` | ⬜ | El paso de riesgo alto. |
| 7 · Prompts a Qute | ⬜ | |
| 8 · Versión única | ✅ | Sale del `pom.xml`; había tres números y ya divergían. |
| 9 · Almacén de ArchUnit vacío | ⬜ | Va por 244, desde 482. |

**Deuda registrada: 482 → 244.** El desglose actual es 166 de
`dominio → frameworks` (las anotaciones de los records de respuesta) y 78 de
`servicio → adaptadores`.

### Lo que falta es un solo bloque, no cuatro pasos sueltos

Conviene saberlo antes de empezar: los pasos 2, 5 y 6 están atados entre sí y
hacerlos a medias deja el sistema peor que ahora.

El nudo es que los mismos records sirven de contrato HTTP, de modelo y de esquema
para el modelo de lenguaje. Un puerto de salida para el catálogo de SECOP tendría
que recibir `FiltroProcesos`, que es un DTO de entrada HTTP, así que declararlo
hoy cambiaría una infracción por otra. El puerto necesita un tipo de comando
propio, y ese tipo aparece al partir `AgenteSecop` —el paso 6, el de riesgo
alto—. La spec ya lo dice: ese paso conviene hacerlo en una sesión dedicada y sin
mezclarlo con nada más.

### Un hallazgo que cambia el orden de la fase 6

**Las constantes de las enumeraciones no se pueden renombrar a inglés todavía**,
aunque SPEC-BE-01 §3.1 lo proponga. LangChain4j deriva el esquema JSON del
**nombre de la constante**, no del código de serialización, y los prompts le
piden al modelo esos mismos códigos en español. Renombrar dejaría el esquema
diciendo una cosa y el prompt otra, y con esquema estricto gana el esquema: el
prompt pasaría a mentirle al modelo sin que nada fallara de forma visible.

El renombrado es seguro cuando el adaptador de modelos tenga su tipo de carga
útil propio, como la propia spec prevé en §3.3. Mientras tanto,
`EsquemaDelModeloTest` fija los valores y comprueba que el prompt los menciona.

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

1. **Terminar la fase 2**, empezando por el bloque atado 2 + 5 + 6 descrito arriba.
2. **Fase 3 · Resiliencia y observabilidad** (5-8 jornadas). Va después de la 2 a
   propósito: los decoradores se aplican sobre puertos que aún no existen.
3. **Fase 4 · Frontend** (6-10 jornadas). Incluye `useAsyncAction`, que absorbe la
   cancelación que la fase 1 dejó a medio camino, y persistir búsqueda y validación.
4. **Fase 5 · Documentación y diagramas**, y **Fase 6 · Migración a inglés**.

Y un asunto que el plan marca como **bloqueante y no técnico**: `SPEC-NT-02` (qué datos
salen hacia terceros, con qué base y con qué aviso). Hasta que esté decidido y comunicado,
la herramienta no debería usarse con un pliego real de un cliente real.

## Deuda registrada, para que no se olvide

- **ArchUnit congela 244 infracciones** (166 de `dominio → frameworks`, 78 de
  `servicio → adaptadores`). Están en `src/test/resources/archunit_store` y la fase 2 las
  va borrando; empezó en 482. Las 78 no son deuda nueva: estaban camufladas porque los DTO
  de solicitud vivían en el paquete `dominio`, y salieron a la luz al moverlos a su sitio.
- **El dominio nuevo (`domain/`) se vigila sin congelar.** Nace limpio y nada puede
  entrar ahí arrastrando un framework.
- **ESLint deja 8 avisos**, siete del mismo patrón: restaurar estado en un efecto. Es
  correcto para algo que no existe durante el render en servidor, y aun así encadena
  renders; lo paga el reducer de los puntos 4.2 y 4.3.
- **El límite de tasa es por instancia**, porque su estado vive en memoria. Con un solo
  proceso es exacto; escalar horizontalmente exigiría un almacén compartido.
