# Especificaciones de mejora — Agente SECOP II

Conjunto de especificaciones para llevar `backend-quarkus/` y `frontend-next/` a una
arquitectura hexagonal, resiliente, reutilizable y documentada.

**Alcance:** únicamente `backend-quarkus/` (Java 25 + Quarkus 3.38) y `frontend-next/`
(Next.js 16 + React 19). Las carpetas `backend/` (FastAPI) y `frontend/` (Vite) quedan
explícitamente fuera: son versiones anteriores congeladas.

**Punto de partida verificado el 15 de agosto de 2026:**

| Comprobación | Resultado |
|---|---|
| `./mvnw test` | 88/88 ✅ |
| `npx vitest run` | 64/64 ✅ |
| `npx tsc --noEmit` | sin errores ✅ |

No hay fallos de compilación ni de pruebas. **Todo lo que estas especificaciones corrigen
es de diseño**: acoplamiento, resiliencia, seguridad, accesibilidad y mantenibilidad. Es
importante entender esa distinción antes de leer el diagnóstico: el sistema funciona en el
camino feliz y falla mal fuera de él.

---

## Cómo leer esto

Empieza por los dos documentos transversales y baja al detalle solo de lo que vayas a
implementar.

| Documento | Qué contiene |
|---|---|
| [`00-DIAGNOSTICO.md`](00-DIAGNOSTICO.md) | Los 47 hallazgos, con evidencia en `archivo:línea`, severidad y principio violado. |
| [`01-PLAN-DE-MEJORA.md`](01-PLAN-DE-MEJORA.md) | Plan por fases: qué se hace, en qué orden, con qué criterio de corte. Incluye el plano no técnico. |

### Especificaciones técnicas — backend

| Spec | Título | Prioridad |
|---|---|---|
| [SPEC-BE-01](backend/SPEC-BE-01-arquitectura-hexagonal.md) | Arquitectura hexagonal y regla de dependencias | 🔴 Alta |
| [SPEC-BE-02](backend/SPEC-BE-02-resiliencia.md) | Resiliencia: timeouts, reintentos, cortacircuitos, mamparos | 🔴 Alta |
| [SPEC-BE-03](backend/SPEC-BE-03-puerto-modelos-ia.md) | Puerto de modelos de lenguaje y patrones de proveedor | 🔴 Alta |
| [SPEC-BE-04](backend/SPEC-BE-04-puerto-secop.md) | Puerto de catálogo SECOP y consultas SoQL seguras | 🟠 Media |
| [SPEC-BE-05](backend/SPEC-BE-05-observabilidad.md) | Salud, métricas, trazas y correlación | 🟠 Media |
| [SPEC-BE-06](backend/SPEC-BE-06-seguridad-y-limites.md) | Autenticación, límites de consumo y fuga de datos | 🔴 Alta |
| [SPEC-BE-07](backend/SPEC-BE-07-idioma-y-nomenclatura.md) | Migración del código a inglés | 🟡 Baja |

### Especificaciones técnicas — frontend

| Spec | Título | Prioridad |
|---|---|---|
| [SPEC-FE-01](frontend/SPEC-FE-01-arquitectura.md) | Arquitectura hexagonal en el cliente | 🟠 Media |
| [SPEC-FE-02](frontend/SPEC-FE-02-resiliencia-cliente.md) | Timeouts, cancelación, fronteras de error y persistencia | 🔴 Alta |
| [SPEC-FE-03](frontend/SPEC-FE-03-ux-y-accesibilidad.md) | Experiencia de usuario y accesibilidad WCAG 2.2 AA | 🔴 Alta |
| [SPEC-FE-04](frontend/SPEC-FE-04-sistema-de-componentes.md) | Sistema de componentes reutilizables | 🟠 Media |
| [SPEC-FE-05](frontend/SPEC-FE-05-idioma-y-nomenclatura.md) | Migración del código a inglés y contrato tipado generado | 🟡 Baja |

### Especificaciones de documentación

| Spec | Título | Prioridad |
|---|---|---|
| [SPEC-DOC-01](documentacion/SPEC-DOC-01-documentacion-tecnica.md) | Documentación técnica, ADR y contrato OpenAPI como fuente única | 🟠 Media |
| [SPEC-DOC-02](documentacion/SPEC-DOC-02-diagramas.md) | Diagramas: C4, componentes, secuencia, clases, estados y despliegue | 🟠 Media |

### Especificaciones no técnicas

| Spec | Título | Prioridad |
|---|---|---|
| [SPEC-NT-01](no-tecnico/SPEC-NT-01-producto-y-confianza.md) | Producto, expectativas y confianza en un sistema con IA | 🔴 Alta |
| [SPEC-NT-02](no-tecnico/SPEC-NT-02-datos-y-cumplimiento.md) | Tratamiento de datos, terceros y cumplimiento normativo colombiano | 🔴 Alta |
| [SPEC-NT-03](no-tecnico/SPEC-NT-03-operacion-y-costos.md) | Operación, costos, SLO y proceso de equipo | 🟠 Media |

---

## Convenciones de estas especificaciones

Cada spec tiene la misma estructura:

1. **Ficha** — identificador, estado, prioridad, hallazgos que cierra, esfuerzo estimado.
2. **Problema** — qué está mal hoy, con evidencia citable (`archivo:línea`).
3. **Decisión** — qué se va a hacer, y por qué esa opción y no otra.
4. **Diseño** — estructura objetivo, patrones aplicados, código de referencia.
5. **Plan de migración** — pasos ordenados, cada uno dejando el sistema verde.
6. **Criterios de aceptación** — verificables, no opinables.
7. **Riesgos y fuera de alcance** — lo que puede salir mal y lo que no se toca.

**Estados:** `Propuesta` → `Aceptada` → `Implementada` → `Sustituida`. Todas nacen como
`Propuesta`; nadie las ha aprobado todavía.

**Trazabilidad:** cada hallazgo del diagnóstico tiene un identificador (`BE-C1`, `FE-A5`,
`NT-2`…) que aparece en la spec que lo cierra. Ningún hallazgo debería quedar huérfano; el
diagnóstico lleva la tabla de cobertura al final.

**Estimaciones:** en jornadas de una persona con contexto del repositorio. Son órdenes de
magnitud para priorizar, no compromisos.

---

## Principios que se usan como criterio

Estas especificaciones no aplican principios como adorno; cada hallazgo dice cuál se
incumple y por qué eso cuesta dinero o tiempo.

- **Clean Architecture / Hexagonal** — la regla de dependencias apunta hacia adentro. El
  dominio no sabe que existen HTTP, Jackson, LangChain4j ni Socrata.
- **SOLID** — con énfasis en SRP (clases que hacen cinco cosas), OCP (añadir un proveedor
  obliga a tocar cinco archivos) y DIP (los recursos REST inyectan clases concretas).
- **DRY** — con el matiz importante: se elimina la duplicación *de conocimiento*, no la
  coincidencia textual. Cinco enumeraciones con el mismo boilerplate son duplicación de
  conocimiento; dos validaciones parecidas sobre reglas distintas, no.
- **KISS** — se prefiere el mecanismo estándar (MicroProfile Fault Tolerance) al artesanal
  (`Thread.sleep` en un bucle), aunque el artesanal ya funcione.
- **Clean Code** — nombres que dicen la verdad, funciones cortas, sin valores centinela,
  sin comentarios que expliquen lo que el código debería decir solo.

Una advertencia deliberada: **el código actual está bien comentado y sus comentarios
explican decisiones reales, no obviedades.** Eso es un activo poco común y las
especificaciones que siguen exigen conservarlo al refactorizar. Renombrar a inglés y
reorganizar en paquetes no puede convertirse en una excusa para perder el porqué.
