# Plan de mejoramiento

Ordena los 47 hallazgos del [diagnóstico](00-DIAGNOSTICO.md) en fases ejecutables. Cada fase
deja el sistema en un estado íntegro y verificable: **en ningún momento hay una rama larga
con el sistema roto**.

---

## Criterio de ordenación

No se ordena por severidad. Se ordena por **dependencia y por riesgo de rehacer trabajo**:

1. Primero lo que permite trabajar con red de seguridad (versionado, CI, reglas de
   arquitectura automatizadas). Sin esto, cada refactorización posterior es una apuesta.
2. Después lo que **detiene un daño en curso** y es barato (seguridad y fuga de datos).
3. Después la reorganización estructural, que es la más invasiva y la que hace más costoso
   cualquier trabajo hecho antes sobre la estructura vieja.
4. La resiliencia va **después** de la estructura, no antes: los decoradores de reintento y
   cortacircuitos se aplican sobre los puertos, y los puertos no existen todavía. Aplicarlos
   ahora significaría hacerlo dos veces.
5. El renombrado a inglés va al final, en un cambio mecánico y aislado, para que no
   contamine ningún diff de comportamiento.

> **La regla que evita el desastre:** ningún cambio de estructura y ningún renombrado se
> mezcla en el mismo commit con un cambio de comportamiento. Un diff que mueve archivos y
> además arregla un bug es un diff que nadie puede revisar.

---

## Fase 0 · Red de seguridad

**Objetivo:** poder equivocarse sin perder trabajo. **Duración:** 1–2 jornadas.
**Bloquea:** todo lo demás.

| # | Acción | Cierra |
|---|---|---|
| 0.1 | `git init`, primer commit con el estado actual verde. Verificar que `.env` no entra. | TR-A2 |
| 0.2 | Etiquetar `v0.2.0-legacy` y eliminar `backend/` y `frontend/` del tronco. | TR-B6 |
| 0.3 | CI: `mvnw verify` + `tsc --noEmit` + `vitest run` en cada push. | TR-A3 |
| 0.4 | ESLint + Prettier en el frontend, en modo aviso al principio. | FE-M15 |
| 0.5 | ArchUnit en el backend con las reglas de `SPEC-BE-01`, **en verde desde el primer día** con las excepciones actuales declaradas explícitamente y fechadas. | prepara BE-A9 |

La sutileza de 0.5 importa: se introduce la regla con la lista de infracciones conocidas
congelada. A partir de ahí, la deuda no puede crecer aunque todavía no se haya pagado. Cada
paso de la fase 2 borra una línea de esa lista.

**Criterio de salida:** un push que rompa cualquier suite falla en CI, y nadie puede añadir
una violación nueva de la regla de dependencias.

---

## Fase 1 · Contención del daño

**Objetivo:** cerrar lo que hoy cuesta dinero, filtra datos o impide usar la aplicación.
**Duración:** 3–5 jornadas. **Depende de:** fase 0.

Se hace antes de la reestructuración porque son cambios pequeños, localizados y de alto
valor, que no se van a rehacer.

| # | Acción | Cierra | Spec |
|---|---|---|---|
| 1.1 | Eliminar la rama por defecto de `traducir` que devuelve el mensaje crudo del proveedor. Registrar el detalle con un identificador; devolver un mensaje genérico + el identificador. | BE-A5 | BE-06 |
| 1.2 | Cotas de entrada por caso de uso con `@Size` en los records de solicitud. Rechazo con 422 antes de construir nada. | BE-M16 | BE-06 |
| 1.3 | Acotar la caché de modelos (tamaño máximo + cierre al expulsar) y validar el identificador de modelo contra un patrón. | BE-C2 | BE-03 |
| 1.4 | `@Blocking` en `ChatResource.chat`, o desplazar la suscripción al pool de trabajo. | BE-C1 | BE-02 |
| 1.5 | CORS por perfil, con origen desde variable de entorno y fallo al arrancar en `%prod` si falta. | BE-M17 | BE-06 |
| 1.6 | Autenticación por clave de API en los endpoints que invocan al modelo, más límite de tasa por clave. | BE-C4 | BE-06 |
| 1.7 | `error.tsx` por segmento y `global-error.tsx` con recuperación del espacio de trabajo. | FE-C1 | FE-02 |
| 1.8 | Timeout y cancelación en todas las llamadas del cliente, con botón «Cancelar» en las operaciones largas. | FE-C2 | FE-02 |
| 1.9 | Foco visible (`:focus-visible`) en todos los controles interactivos. | FE-A5 | FE-03 |
| 1.10 | `aria-live` en el hilo de chat y `aria-busy` en las operaciones largas. | FE-A6 | FE-03 |

Nótese que 1.9 son unas quince líneas de CSS y convierte una aplicación inoperable con
teclado en una operable. Es la mejor relación valor/esfuerzo de todo el plan.

**Criterio de salida:** un tercero sin credenciales no puede gastar tokens; ningún mensaje
de error del proveedor llega al navegador; la aplicación se puede recorrer entera con
teclado y con lector de pantalla.

---

## Fase 2 · Arquitectura hexagonal

**Objetivo:** la regla de dependencias se cumple y está automatizada.
**Duración:** 8–12 jornadas. **Depende de:** fase 0.

Es la fase más invasiva. Se ejecuta en pasos pequeños, cada uno con las 152 pruebas en
verde. El orden minimiza los conflictos: primero se extrae el dominio puro (nadie depende de
que se mueva), luego se declaran los puertos, luego se mueven los adaptadores.

| # | Acción | Cierra | Spec |
|---|---|---|---|
| 2.1 | Extraer `domain/` sin anotaciones de framework. Los DTO de la API se separan del modelo, con mapeadores explícitos. | BE-A9 | BE-01 |
| 2.2 | Unificar las cinco enumeraciones tras `CodedEnum` + resolución genérica. | BE-M14 | BE-01 |
| 2.3 | Declarar los puertos de entrada (un caso de uso, una interfaz) y de salida (`LanguageModelPort`, `ProcurementCatalogPort`, `DocumentTextExtractorPort`). | BE-A9 | BE-01 |
| 2.4 | Partir `AgenteSecop` en cinco servicios de aplicación. | BE-A10 | BE-01 |
| 2.5 | Sacar la construcción de prompts a plantillas Qute versionadas. Elimina el `replace` frágil. | BE-A10 | BE-01 |
| 2.6 | Adaptador de catálogo: `SoqlQueryBuilder` tipado, mapeador de filas separado, `TechRelevanceScorer` al dominio, resultado sellado en vez de `null`. | BE-A7, BE-A8 | BE-04 |
| 2.7 | Adaptador de modelos: fábrica parametrizada, traducción de errores por tipo, configuración por mapa. | BE-A6, BE-M13, BE-A12 | BE-03 |
| 2.8 | Versión única desde el `pom.xml`. | BE-M19 | BE-01 |
| 2.9 | Borrar las excepciones de ArchUnit una a una hasta dejar la lista vacía. | BE-A9 | BE-01 |

**Criterio de salida:** `domain/` compila sin Jackson, sin OpenAPI y sin LangChain4j en el
classpath de compilación; ArchUnit sin excepciones; las 88 pruebas en verde sin cambios de
comportamiento observables desde HTTP.

---

## Fase 3 · Resiliencia y observabilidad

**Objetivo:** el sistema se comporta de forma predecible cuando sus dependencias fallan, y
se puede demostrar. **Duración:** 5–8 jornadas. **Depende de:** fase 2.

Va después porque los mecanismos se aplican como decoradores sobre los puertos.

| # | Acción | Cierra | Spec |
|---|---|---|---|
| 3.1 | Sustituir `conReintentos` por MicroProfile Fault Tolerance: `@Retry`, `@Timeout`, `@CircuitBreaker`, `@Bulkhead`, `@Fallback`. | BE-C3 | BE-02 |
| 3.2 | Decoradores reutilizables sobre `LanguageModelPort`: resiliencia, métricas, registro. | BE-C3 | BE-02, BE-03 |
| 3.3 | Presupuesto de tiempo por caso de uso, y `validarPropuesta` sin llamadas anidadas sin límite. | BE-A11 | BE-02 |
| 3.4 | `quarkus-smallrye-health` con sondas reales de vivacidad y disponibilidad. | BE-M15 | BE-05 |
| 3.5 | Micrometer: latencia, tasa de error y estado del cortacircuitos por proveedor; contador de tokens. | BE-M15 | BE-05 |
| 3.6 | Identificador de correlación propagado del cliente al registro y devuelto en los errores. | BE-M15 | BE-05 |
| 3.7 | Pruebas de fallo con WireMock: 429 se reintenta, 401 no, el cortacircuitos abre y se recupera. | BE-B22 | BE-02 |
| 3.8 | Perfil de pruebas con aislamiento por defecto y fallo si alguna clave resuelve no vacía. | BE-M18 | BE-05 |

**Criterio de salida:** existe una prueba que demuestra cada política de resiliencia. El
número de reintentos, el umbral del cortacircuitos y el presupuesto de tiempo son
configuración, no código.

**Ejecutada.** Los ocho puntos, hechos. 24 pruebas nuevas (164 en total), ningún
`Thread.sleep` en `src/main/java`, y la política entera verificada contra una caída real
del proveedor. Cinco hallazgos de la ejecución están en `SPEC-BE-02` §8; el que más
importa es que **LangChain4j reintentaba por su cuenta** y los reintentos se multiplicaban
por tres, cosa que solo se vio porque las pruebas cuentan peticiones al proveedor en vez
de comprobar el resultado.

---

## Fase 4 · Frontend: arquitectura, reutilización y experiencia

**Objetivo:** el cliente deja de perder trabajo del usuario y deja de repetirse.
**Duración:** 6–10 jornadas. **Depende de:** fase 1.

| # | Acción | Cierra | Spec |
|---|---|---|---|
| 4.1 | Separar `dominio/`, `aplicacion/`, `infraestructura/`. Puerto `AgentGateway` inyectado por contexto. | FE-A10 | FE-01 |
| 4.2 | `useAsyncAction`: estado, error, timeout, cancelación y `aria-busy` en un solo sitio. | FE-A9, FE-C2 | FE-01, FE-02 |
| 4.3 | Espacio de trabajo con reducer, persistencia recortada y aviso visible si la cuota falla. | FE-M11 | FE-02 |
| 4.4 | Persistir el resultado de búsqueda y de validación. Nada que cueste dinero se pierde al navegar. | FE-A3 | FE-03 |
| 4.5 | Filtros de búsqueda en la URL; ruta `/procesos/[id]`. | FE-A4, FE-B16 | FE-03 |
| 4.6 | Acción visible de «Borrar espacio de trabajo», con confirmación. | FE-M12 | FE-03 |
| 4.7 | Render de Markdown saneado en secciones y documento. | FE-A8 | FE-03 |
| 4.8 | Paginación de resultados. | FE-M13 | FE-03 |
| 4.9 | Corregir `descargar()`. | FE-M14 | FE-02 |
| 4.10 | Puntos de ruptura adaptables, o declaración explícita de «solo escritorio» en la documentación. | FE-A7 | FE-03 |
| 4.11 | Extraer el sistema de componentes: `AsyncBoundary`, `DocumentUploader`, `DataTable`, `StatusBadge`. | FE-A9 | FE-04 |
| 4.12 | Cabeceras de seguridad en `next.config.ts`. | FE-M15 | FE-01 |

**Criterio de salida:** navegar entre pasos y volver no pierde ningún resultado; auditoría
de accesibilidad (axe) sin infracciones de nivel A ni AA en las cinco rutas.

---

## Fase 5 · Documentación y diagramas

**Objetivo:** que la arquitectura se pueda explicar sin leer el código.
**Duración:** 4–6 jornadas. **Depende de:** fase 2 (los diagramas describen la estructura
nueva, no la vieja).

| # | Acción | Cierra | Spec |
|---|---|---|---|
| 5.1 | ADR retroactivos de las cinco decisiones fuertes ya tomadas. | TR-M5 | DOC-01 |
| 5.2 | C4 niveles 1–3 en Mermaid, versionados junto al código. | TR-M5 | DOC-02 |
| 5.3 | Diagramas de secuencia de los cinco flujos, incluidos los de fallo. | TR-M5 | DOC-02 |
| 5.4 | Diagrama de clases del hexágono y de dependencias entre paquetes. | TR-M5 | DOC-02 |
| 5.5 | OpenAPI como fuente única y generación de los tipos del frontend. | FE-B18 | DOC-01, FE-05 |
| 5.6 | Runbook de operación y guía de contribución. | TR-M5 | DOC-01 |
| 5.7 | Glosario bilingüe dominio ES ↔ código EN. | BE-B20 | DOC-01 |

**Criterio de salida:** los diagramas se renderizan en CI y un diagrama desactualizado
respecto a la estructura de paquetes rompe la compilación de la documentación.

---

## Fase 6 · Migración a inglés

**Objetivo:** un solo idioma en el código. **Duración:** 3–5 jornadas.
**Depende de:** fases 2 y 5 (el glosario debe existir antes de renombrar).

Última a propósito: es el cambio con más líneas tocadas y menos riesgo semántico. Hacerlo
antes obligaría a renombrar dos veces todo lo que la fase 2 mueve.

| # | Acción | Cierra | Spec |
|---|---|---|---|
| 6.1 | Renombrado de identificadores internos del backend, guiado por el glosario, con refactorización asistida por el IDE, en un commit sin cambios de comportamiento. | BE-B20 | BE-07 |
| 6.2 | Decisión explícita sobre el contrato HTTP: mantenerlo en español o versionar `/api/v2` en inglés. Se recomienda **mantenerlo**; ver la spec. | BE-B20 | BE-07 |
| 6.3 | Renombrado del frontend + tipos generados desde OpenAPI. | FE-B17, FE-B18 | FE-05 |

**Criterio de salida:** ningún identificador nuevo en español en `src/`; el glosario cubre
todo término de dominio que no tenga traducción evidente.

---

## Plano no técnico

Corre en paralelo. No depende de las fases técnicas y no debería esperarlas.

| Spec | Contenido | Cuándo |
|---|---|---|
| [SPEC-NT-01](no-tecnico/SPEC-NT-01-producto-y-confianza.md) | Qué promete la herramienta, cómo comunica su incertidumbre, qué hace el usuario cuando el modelo se equivoca. | Con la fase 1 |
| [SPEC-NT-02](no-tecnico/SPEC-NT-02-datos-y-cumplimiento.md) | Qué datos salen hacia terceros, con qué base, con qué aviso y con qué retención. Ley 1581 de 2012 y Ley 1712 de 2014. | **Antes** de cualquier uso real |
| [SPEC-NT-03](no-tecnico/SPEC-NT-03-operacion-y-costos.md) | SLO, presupuesto de tokens, quién opera, definición de terminado. | Con la fase 3 |

`SPEC-NT-02` es lo único del plano no técnico con carácter bloqueante: hasta que no esté
decidido y comunicado qué se envía a terceros, la herramienta no debería usarse con un
pliego real de un cliente real.

---

## Resumen de esfuerzo

| Fase | Jornadas | Riesgo | Valor inmediato |
|---|---|---|---|
| 0 · Red de seguridad | 1–2 | Bajo | Habilitante |
| 1 · Contención | 3–5 | Bajo | **Muy alto** |
| 2 · Hexagonal | 8–12 | **Alto** | Diferido |
| 3 · Resiliencia | 5–8 | Medio | Alto |
| 4 · Frontend | 6–10 | Medio | **Muy alto** |
| 5 · Documentación | 4–6 | Bajo | Medio |
| 6 · Inglés | 3–5 | Bajo | Bajo |
| **Total** | **30–48** | | |

### Si solo hay presupuesto para una semana

Fase 0 completa + puntos 1.1, 1.3, 1.6, 1.7, 1.8, 1.9 y 1.10. Son unas seis jornadas y
cierran los cuatro hallazgos críticos que tienen consecuencia inmediata: gasto no
autorizado, fuga de credenciales, pérdida del trabajo del usuario e inaccesibilidad con
teclado. La deuda arquitectónica sigue ahí, pero deja de crecer gracias a la regla de
ArchUnit congelada en 0.5.

### Lo que este plan deliberadamente no hace

- **No reescribe.** Todo son refactorizaciones sobre el código existente. El sistema
  funciona y su comportamiento verificado es un activo; tirarlo sería empezar de cero con
  otro conjunto de defectos.
- **No introduce base de datos.** No hay ningún caso de uso que hoy la necesite. El
  espacio de trabajo en el navegador es una decisión coherente; lo que falta es
  documentarla y darle un botón de borrado.
- **No cambia de framework** en ninguno de los dos módulos.
- **No añade microservicios.** El monolito modular con puertos y adaptadores es la forma
  correcta para este tamaño; el hexágono se puede partir después si alguna vez hace falta,
  y precisamente por eso se hace primero.
