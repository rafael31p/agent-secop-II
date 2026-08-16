# Estado del proyecto — 15 de agosto de 2026

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

1. **Decidir cuándo borrar `backend/` y `frontend/`** (las versiones Python y Vite). Las dos
   están cubiertas por las nuevas y verificadas; conservarlas solo tiene sentido mientras
   sirvan de referencia. Es una decisión del usuario, no se ha tocado nada.
2. El repositorio **no es un repositorio git**. Si se va a versionar, `git init` antes de
   seguir: el `.gitignore` ya está preparado (cubre `.env`, `target/`, `.next/`,
   `node_modules/`).
3. Opcional: el frontend no tiene ESLint configurado. `next lint` desapareció en Next 16,
   así que habría que instalar `eslint` y `eslint-config-next` a mano.
