# Agente SECOP II — Análisis de Contratación Pública TI

[![CI](https://github.com/rafael31p/agent-secop-II/actions/workflows/ci.yml/badge.svg)](https://github.com/rafael31p/agent-secop-II/actions/workflows/ci.yml)
[![Licencia: GPL v3](https://img.shields.io/badge/licencia-GPLv3-blue.svg)](LICENSE)

Aplicación full-stack con un agente de IA experto en **contratación pública colombiana**,
especializado en:

1. **Explorar oportunidades** en SECOP II (API abierta de datos.gov.co / Socrata).
2. **Analizar pliegos y requisitos técnicos** de tecnología (TI).
3. **Generar propuestas técnicas** alineadas al pliego y a la normativa colombiana.
4. **Validar propuestas** contra los requisitos, detectando brechas y riesgos de rechazo.

El proveedor de IA y el modelo **se eligen en tiempo de ejecución**, por petición: Gemini,
OpenAI, Anthropic, DeepSeek u Ollama local.

> ⚠️ Esta herramienta es de **apoyo analítico**. No sustituye asesoría jurídica ni las
> decisiones de la entidad contratante. Toda conclusión debe verificarse contra los
> documentos oficiales publicados en SECOP II.

---

## Arquitectura

```
agent-secop-II/
├── backend-quarkus/    Java 25 + Quarkus + LangChain4j
│   └── src/main/java/co/agentesecop/
│       ├── api/            Recursos REST y traducción de errores
│       ├── dominio/        Records: contrato HTTP y esquema para el modelo
│       ├── ia/             ProveedorIA + las cinco implementaciones
│       ├── secop/          Cliente Socrata y heurística TI
│       └── servicio/       Agente y extracción de documentos
├── frontend-next/      Next.js 16 + React 19 + TypeScript
│   ├── app/                Rutas (App Router) y layout
│   ├── componentes/        Vistas y presentación
│   ├── lib/                Cliente HTTP, tipos y contextos
│   └── pruebas/            Vitest + Testing Library
└── specs/              Diagnóstico y plan de mejora
```

`specs/` contiene un diagnóstico de 47 hallazgos y un plan de mejora en siete fases. Las
fases 0 y 1 —red de seguridad y contención del daño— ya están ejecutadas; el estado
detallado está en [`ESTADO.md`](ESTADO.md).

El proyecto nació como FastAPI + React/Vite y se migró a esta pila. Esas versiones
anteriores se retiraron del tronco pero siguen recuperables:

```bash
git checkout v0.2.0-legacy -- backend/ frontend/
```

Ojo si las consultas: el backend Python serializa en `snake_case` y el de Quarkus en
`camelCase`, así que cada frontend solo funciona contra su backend.

### Flujo funcional

```
SECOP II (Socrata)  ──▶  /api/procesos/buscar        ──▶  Listado de oportunidades
        │
        ▼
Pliego / requisitos  ──▶  /api/analisis/requisitos    ──▶  Requisitos TI estructurados
                                                            (obligatorios, ponderables,
                                                             riesgos, preguntas al ente)
        │
        ▼
                     ──▶  /api/propuestas/generar     ──▶  Propuesta técnica (borrador)
        │
        ▼
                     ──▶  /api/propuestas/validar     ──▶  Matriz de cumplimiento + score
```

---

## Requisitos

- JDK 21+ (probado en GraalVM JDK 25)
- Node.js 20+ (probado en 24)
- Al menos una clave de proveedor de IA. La de
  [Google AI Studio](https://aistudio.google.com/apikey) es gratuita.

## Puesta en marcha

### Backend

```bash
cd backend-quarkus
cp .env.example .env      # y edita AGENTE_IA_GEMINI_API_KEY
./mvnw quarkus:dev        # puerto 8000
```

Docs interactivas: http://localhost:8000/docs

### Frontend

```bash
cd frontend-next
npm install
cp .env.local.example .env.local    # si el backend no está en localhost:8000
                                    # o si exige clave de API
npm run dev
```

App: http://localhost:3000

El frontend llama al backend **directamente**, no por un proxy: un intermediario puede
almacenar en búfer la respuesta del chat y romper el streaming. En desarrollo el backend
ya autoriza por CORS el puerto 3000; en producción hay que declararlo en
`AGENTE_CORS_ORIGINS`.

---

## Elección de proveedor y modelo

`GET /api/proveedores` devuelve el catálogo con el estado de cada uno, y la cabecera de la
aplicación lo usa para armar el selector. Cualquier petición que invoque al modelo acepta
`proveedor` y `modelo` opcionales; si van nulos manda la configuración del servidor.

| Proveedor | Variable de entorno | Modelo por defecto |
|---|---|---|
| Google Gemini | `AGENTE_IA_GEMINI_API_KEY` | `gemini-3.6-flash` |
| OpenAI | `AGENTE_IA_OPENAI_API_KEY` | `gpt-4.1-mini` |
| Anthropic | `AGENTE_IA_ANTHROPIC_API_KEY` | `claude-sonnet-4-6` |
| DeepSeek | `AGENTE_IA_DEEPSEEK_API_KEY` | `deepseek-chat` |
| Ollama (local) | `AGENTE_IA_OLLAMA_HABILITADO=true` | `llama3.1` |

DeepSeek hereda de la implementación de OpenAI: su API es compatible y solo cambia la URL
base. La lista de modelos del selector es de **sugerencias**, no cerrada; se puede escribir
cualquier identificador válido del proveedor.

**Aparecer en el catálogo no significa poder usarlo.** Dos trampas verificadas contra la
API real de Gemini:

- Los modelos retirados **siguen apareciendo** en el listado pero devuelven `404`
  («no longer available to new users»).
- Los modelos `pro` devuelven `429` en el plan gratuito. Solo los `flash` están
  disponibles sin plan de pago.

Los filtros de seguridad se fijan en `BLOCK_ONLY_HIGH` porque los pliegos de
ciberseguridad, control de acceso o infraestructura penitenciaria disparan falsos
positivos con el umbral por defecto.

---

## Endpoints

| Método | Ruta | Descripción |
|---|---|---|
| `GET`  | `/api/salud` | Estado del servicio y de la configuración |
| `GET`  | `/api/proveedores` | Catálogo de proveedores de IA y su disponibilidad |
| `POST` | `/api/procesos/buscar` | Busca procesos en SECOP II con filtros |
| `GET`  | `/api/procesos/{id}` | Detalle de un proceso |
| `POST` | `/api/procesos/relevancia-ti` | Clasifica y prioriza procesos por relevancia TI |
| `POST` | `/api/analisis/requisitos` | Extrae y estructura requisitos técnicos del pliego |
| `POST` | `/api/analisis/documento` | Sube PDF/DOCX/TXT y extrae su texto |
| `POST` | `/api/propuestas/generar` | Genera propuesta técnica |
| `POST` | `/api/propuestas/validar` | Valida propuesta vs. requisitos |
| `POST` | `/api/chat` | Chat con el agente experto (streaming SSE) |

### Operación

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/q/health/live` | Vivacidad: ¿hay que reiniciar el proceso? |
| `GET` | `/q/health/ready` | Disponibilidad: ¿tiene sentido mandarle tráfico? |
| `GET` | `/q/metrics` | Métricas en formato Prometheus |

`/api/salud` informa de qué hay **configurado**; estas informan de si **funciona**. La
diferencia no es sutil: el primero devuelve «ok» con la clave del proveedor revocada.

La sonda de disponibilidad usa el estado del cortacircuitos como sensor —consultarlo es
gratis y refleja las llamadas reales, mientras que sondear al proveedor costaría dinero en
cada latido— y separa los modelos de la fuente de datos, porque con los modelos caídos aún
se puede buscar procesos.

> **Al desplegar:** `/q/metrics` revela qué modelos se usan, cuánto falla cada proveedor y
> cuánto se consume. Restrínjalo por red o por proxy inverso; no se publica junto a la API.

Toda respuesta lleva `X-Correlation-Id`, y todo cuerpo de error lo repite en
`correlationId`. Si el cliente envía el suyo se conserva, siempre que tenga forma
admisible: aceptar un valor arbitrario y escribirlo en el registro es inyección de
registros.

### Autenticación y límites

Los endpoints que llaman al modelo exigen la cabecera `X-Api-Key`. `/api/salud` y
`/api/proveedores` quedan abiertos: el frontend los consulta antes de tener contexto y no
gastan nada.

```properties
# Se guardan hashes, no claves: quien lea esta configuración no obtiene con qué llamar.
agente.seguridad.api-keys.equipo=sha256:<hash>
```

```bash
# Generar una clave y su hash
openssl rand -hex 32 | tee /dev/stderr | tr -d '\n' | sha256sum
```

En `quarkus:dev` la autenticación está desactivada para no estorbar. En producción es
obligatoria y **el arranque falla** si no hay ninguna clave configurada o si falta
`AGENTE_CORS_ORIGINS`; se prefiere no arrancar a arrancar abierto sin saberlo.

Cada clave tiene un límite por hora y operación (20 análisis, 15 validaciones, 100
mensajes de chat…), configurable en `agente.seguridad.limites.*`. Al superarlo se
devuelve `429` con `Retry-After`.

Conviene tener presente qué **no** es esto: CORS lo aplica el navegador y `curl` lo
ignora, así que quien protege el presupuesto de tokens es la clave, no el CORS. Y la
clave identifica a un cliente, no a una persona: sirve para controlar el gasto, no para
auditar quién analizó qué. El día que haga falta lo segundo, el destino es OIDC.

### Manejo de errores del proveedor

Los planes gratuitos devuelven `503` y `429` con frecuencia. La política de resiliencia
—reintentos, timeout, cortacircuitos y mamparo— se **declara** con MicroProfile Fault
Tolerance y todos sus parámetros son configuración: ver la sección de resiliencia de
`application.properties`. Cuando el fallo persiste, el error llega al cliente **con su
explicación**, no como un `500` genérico. Cada excepción tiene su propio
`ExceptionMapper` en `adapter/in/rest/error/`, sobre una clase base que define el cuerpo y
el registro una sola vez:

| Situación | HTTP | Qué ve el usuario |
|---|---|---|
| Falta la clave del proveedor | `503` | Cuál falta y dónde obtenerla |
| Proveedor desconocido | `400` | Los proveedores disponibles |
| Cuota agotada | `429` | Que espere o revise su plan |
| Servicio caído / modelo inexistente | `404`/`502` | Qué modelo falló y cómo listarlos |
| Filtro de contenido o respuesta truncada | `422` | Por qué se detuvo la generación |
| Pliego demasiado grande | `422` | El tamaño admitido y qué hacer |
| Circuito abierto o tiempo agotado | `503` | Que pruebe otro proveedor del selector |
| Máximo de trabajos simultáneos | `429` + `Retry-After` | Que reintente en unos segundos, **sin culpar al proveedor** |
| Falta un dato para poder trabajar | `422` | Qué falta enviar |

**Ningún texto originado en un sistema externo llega al navegador.** El detalle del
proveedor va al registro junto a un identificador que sí viaja en la respuesta
(`correlationId`), y el usuario lo cita al reportar el fallo. No es pudor: la API de
Google AI lleva la clave en la cadena de consulta, así que un error que incluya la URL
incluye la credencial.

---

## Verificación

```bash
cd backend-quarkus
./mvnw test                    # 202 pruebas, sin red ni credenciales
python verificar_en_vivo.py    # extremo a extremo contra el servidor levantado
```

```bash
cd frontend-next
npm test                       # 74 pruebas (Vitest + Testing Library)
npm run typecheck
npm run lint
npm run build
```

Todo eso corre en CI en cada empuje, más `gitleaks` sobre el historial completo.

La regla de dependencias de la arquitectura se verifica con ArchUnit y **sin ninguna
excepción**: las 482 infracciones que había congeladas se pagaron en la fase 2 y el
almacén desapareció, así que una violación nueva rompe la compilación en el acto.

Las políticas de resiliencia tienen pruebas que **cuentan peticiones al proveedor**, no
solo el resultado. La diferencia importa: una prueba que se limita a comprobar que un
`401` acaba en error pasa igual si se reintentó tres veces —tres llamadas facturables por
una clave que nunca va a funcionar— que si se abandonó a la primera. Fue lo que reveló
que LangChain4j reintentaba por su cuenta y multiplicaba la política declarada.

Las pruebas del backend usan **WireMock como librería en la misma JVM** en lugar de
Testcontainers, porque el entorno de desarrollo no tiene Docker.

Las pruebas corren sin claves **por defecto**: `pruebas.AislamientoDePruebas` es una
fuente de configuración de prioridad superior al `.env` del desarrollador, y
`AislamientoDePruebasTest` vigila que ninguna clave de proveedor resuelva a un valor no
vacío. Sin eso, las pruebas hacen llamadas facturables y se comportan distinto en cada
máquina.

---

## Notas sobre los datos de SECOP II

Los datos provienen de la API abierta de **datos.gov.co** (Socrata / SoQL). Cosas que
conviene saber antes de confiar en un resultado:

- **El conjunto de datos no incluye los documentos del proceso.** Trae el objeto
  contractual, la entidad, el valor y el enlace, pero no el pliego ni los anexos.
  Para analizar requisitos hay que descargar el PDF desde el enlace de SECOP y subirlo
  en la vista «Analizar».
- **No hay fecha de cierre de recepción de ofertas** en el dataset. Se expone
  `fechaUltimaPublicacion`, que es otra cosa; la fecha de cierre debe consultarse en
  el proceso publicado.
- **Los nulos ordenan primero en `DESC`.** Por eso el cliente añade siempre
  `fecha_de_publicacion_del IS NOT NULL`; sin esa cláusula el listado se llena de
  procesos antiguos sin fecha en vez de los recientes.
- **El puntaje `TI` de cada resultado es una heurística local por palabras clave**, no
  una clasificación del modelo. Sirve para ordenar y descartar ruido sin gastar tokens.
  La clasificación fina la hace el botón «Priorizar con IA».
- **El esquema del dataset ha cambiado entre versiones.** El cliente prueba varios
  alias por campo. Si la API responde `no-such-column`, revisa el mapa `ALIAS` en
  `SecopCliente`. Si un dataset responde 404, consulta el catálogo en
  https://www.datos.gov.co y actualiza `agente.secop.dataset-procesos`.

## Limitaciones conocidas

- Los PDF escaneados sin capa de texto no se pueden leer (no hay OCR).
- El análisis se hace en una sola llamada: pliegos de más de ~400 000 caracteres deben
  dividirse por capítulos.
- Los archivos `.doc` antiguos deben convertirse a `.docx` o PDF.
- El espacio de trabajo del frontend vive en el navegador (`sessionStorage`): sobrevive a
  recargar la página, no a cerrar la pestaña. No hay persistencia en servidor ni cuentas.
- Las salidas del agente son insumo de análisis y deben contrastarse con los documentos
  oficiales del proceso.

---

## Licencia

Copyright © 2026 rafael31p

Este programa es software libre: puedes redistribuirlo y/o modificarlo bajo los términos
de la **Licencia Pública General de GNU, versión 3**, publicada por la Free Software
Foundation. El texto completo está en [`LICENSE`](LICENSE).

Se distribuye con la esperanza de que sea útil, pero **SIN NINGUNA GARANTÍA**, ni siquiera
la garantía implícita de comerciabilidad o idoneidad para un propósito particular.

En términos prácticos: puedes usarlo, estudiarlo, modificarlo y redistribuirlo. Si
distribuyes una versión modificada, o un servicio construido sobre este código que
entregues a terceros, debes publicar tu código también bajo GPL-3.0.

Los datos de SECOP II consultados por esta herramienta son información pública del Estado
colombiano (Ley 1712 de 2014) y no están cubiertos por esta licencia.
