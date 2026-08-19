# SPEC-QA-02 · Pruebas de carga y rendimiento: backend y frontend

| | |
|---|---|
| **Estado** | Propuesta |
| **Prioridad** | 🟠 Media |
| **Cierra** | Calibra SPEC-BE-02 §3.2, SPEC-BE-08 §3.3/§3.7 y los SLO de SPEC-NT-03 §3.4 |
| **Depende de** | SPEC-QA-01 (los dobles), SPEC-BE-05 (las métricas), fase 7 |
| **Esfuerzo** | 4–6 jornadas |

---

## 1. Problema

Cada número de resiliencia del sistema se eligió con criterio y **ninguno se ha medido**:

| Valor | De dónde salió |
|---|---|
| `@Bulkhead(12)` en el modelo | «Conservador a propósito: se sube con las métricas en la mano» |
| `@Bulkhead(30)` en SECOP | Proporción respecto al anterior |
| `@Bulkhead(8)` en streaming | «Ocho conversaciones simultáneas es mucho para el uso previsto» |
| `@Bulkhead(4)` en extracción | «El recurso escaso aquí es CPU» |
| `max-threads=120` | Suma de mamparos más margen |
| Límites de tasa (20/hora, …) | «Un analista revisa unos pocos pliegos al día» |

Los propios comentarios del código lo dicen: **están esperando datos que nadie ha producido**.

Y hay preguntas que sólo la carga responde, todas con consecuencia directa:

- ¿Cuánta memoria consume de verdad una extracción de 25 MB? El mamparo de 4 se eligió por
  intuición sobre CPU, sin medir memoria.
- Con el mamparo lleno, ¿el rechazo llega rápido o el cliente se queda esperando?
- ¿Cuántos flujos SSE simultáneos aguanta el proceso antes de que la latencia del resto se
  degrade?
- ¿El presupuesto de 120 hilos es holgado o justo?
- ¿La caché de modelos, con el lock global de `BE-K1` todavía pendiente, es un cuello de
  botella medible?

Esa última es la más interesante: `SPEC-BE-08` afirma que `computeIfAbsent` sobre
`synchronizedMap` serializa a todos los hilos del proveedor. Es un razonamiento correcto y
**una prueba de carga lo convierte en un número**, que es lo que justifica —o descarta— el
paso 7 de aquella spec, que es el de mayor riesgo.

---

## 2. Decisión

### 2.1 La regla que gobierna todo lo demás

> **Nunca se carga contra un proveedor real de modelos.**

Tres razones, y las tres son suficientes por separado:

1. **Cuesta dinero.** Una prueba de 500 análisis con Gemini son cientos de miles de tokens.
2. **La cuota lo impide.** El plan gratuito devuelve 429 a los pocos segundos, así que la
   prueba mediría el límite del proveedor, no el nuestro.
3. **Mediría lo que no se controla.** La latencia de un modelo la fija el proveedor. Lo que
   se quiere medir es **nuestro** comportamiento: mamparos, colas, hilos, memoria, cómo se
   rechaza cuando hay que rechazar.

El doble del proveedor responde con una latencia **fijada y realista** —tomada de las métricas
`llm_peticion_seconds` de producción cuando existan, y de la verificación en vivo mientras
tanto—. Eso hace la prueba determinista y repetible, que es lo que una prueba de carga
necesita para servir de línea base.

Lo mismo con datos.gov.co: cargar contra una API pública gratuita desde un CI es abusar de un
tercero.

### 2.2 Herramientas

| Módulo | Herramienta | Por qué |
|---|---|---|
| Backend | **k6** | Binario único, sin Docker —restricción documentada del entorno—, guiones en JavaScript, umbrales como criterio de fallo, salida a Prometheus |
| SSE | **k6 con `xk6-sse`**, o cliente propio con `http.request` en modo flujo | k6 no habla SSE de serie y el chat es la mitad del riesgo |
| Frontend | **Lighthouse CI** + presupuestos | Lo que limita al usuario aquí es carga y render, no concurrencia |
| Perfilado | JFR (`-XX:StartFlightRecording`) | Ya viene con la JVM; es lo que responde «¿dónde se fue la memoria?» |

Se descarta Gatling: excelente, pero el DSL en Scala/Java añade una toolchain para un
beneficio que k6 ya da. Y JMeter, por lo mismo más el peso de la interfaz.

---

## 3. Diseño — backend

### 3.1 Estructura

```
pruebas-de-carga/
├── README.md                    cómo ejecutar, cómo leer los resultados
├── entorno/
│   ├── proveedor-falso.json     guiones de WireMock con latencia realista
│   ├── secop-falso.json
│   └── arrancar.ps1 / .sh       levanta dobles + backend con perfil `carga`
├── escenarios/
│   ├── busqueda.js              barato y frecuente
│   ├── analisis.js              caro y lento
│   ├── chat-sse.js              flujos concurrentes
│   ├── documentos.js            memoria y CPU
│   ├── mixto.js                 la mezcla realista
│   └── saturacion.js            hasta que algo cede
├── umbrales/
│   └── slo.json                 los objetivos de SPEC-NT-03 §3.4
└── resultados/                  líneas base fechadas, versionadas
```

### 3.2 Latencias del doble, y de dónde salen

```json
{
  "analisis":    { "p50": 8000,  "p95": 25000, "p99": 45000 },
  "propuesta":   { "p50": 15000, "p95": 40000, "p99": 70000 },
  "validacion":  { "p50": 10000, "p95": 30000, "p99": 55000 },
  "chat_ttfb":   { "p50": 900,   "p95": 2500 },
  "chat_fragmento": { "p50": 40, "p95": 120 },
  "secop":       { "p50": 350,   "p95": 1200, "p99": 3000 }
}
```

Se documenta el origen de cada número y su fecha. **Un doble con latencia inventada produce
una prueba de carga que mide una fantasía**; si aún no hay datos de producción, se toman de
`verificar_en_vivo.py` con una nota de que son provisionales.

### 3.3 Escenarios

**Búsqueda — la carga cotidiana.**

```js
export const options = {
  scenarios: {
    busqueda: { executor: "ramping-vus",
      stages: [ { duration: "1m", target: 10 }, { duration: "3m", target: 30 },
                { duration: "1m", target: 0 } ] },
  },
  thresholds: {
    "http_req_duration{escenario:busqueda}": ["p(95)<3000"],   // SLO de NT-03
    "http_req_failed": ["rate<0.01"],
  },
};
```

**Análisis — la carga cara. Aquí se calibra el mamparo.**

```js
// El mamparo son 12. Se sube a 20 usuarios a propósito, para observar QUÉ pasa al
// rechazar, que es tan importante como cuántos entran: el rechazo debe ser rápido,
// llevar Retry-After y no culpar al proveedor.
export const options = {
  scenarios: { analisis: { executor: "ramping-vus",
      stages: [ { duration: "2m", target: 8 }, { duration: "3m", target: 20 },
                { duration: "2m", target: 0 } ] } },
  thresholds: {
    "http_req_duration{estado:200}": ["p(95)<90000"],
    "rechazos_sin_retry_after": ["count==0"],
    "rechazos_que_culpan_al_proveedor": ["count==0"],   // cierra BE-K7
    "http_req_duration{estado:429}": ["p(99)<200"],     // rechazar debe ser barato
  },
};
```

El umbral `http_req_duration{estado:429} < 200 ms` es el que más dice: **si rechazar es
lento, el mamparo no protege**, sólo traslada la cola.

**Chat — flujos simultáneos.**

```js
// Mide lo que ninguna otra prueba mide: cuántos flujos vivos aguanta el proceso y si
// el semáforo de PoliticaDeStreaming libera el permiso al cancelar.
thresholds: {
  "sse_ttfb": ["p(95)<3000"],
  "sse_intervalo_entre_fragmentos": ["p(95)<500"],
  "flujos_colgados_tras_cancelar": ["count==0"],
  "busqueda_durante_chat_p95": ["p(95)<3000"],    // el aislamiento sigue en pie
}
```

**Documentos — el escenario de memoria.**

```js
// Cuatro concurrentes es el mamparo. Se prueban 4, 8 y 16 para ver cómo degrada, con
// PDF de 1, 10 y 25 MB. Se mide RSS del proceso, no sólo latencia: es la ruta que más
// memoria consume por petición y el mamparo se eligió pensando en CPU.
```

Con JFR activo y una aserción sobre memoria:

```js
thresholds: {
  "memoria_heap_maxima_mb": ["value<1024"],
  "gc_pausa_p99_ms": ["p(99)<500"],
}
```

**Mixto — la mezcla realista.** 70 % búsqueda, 15 % análisis, 10 % chat, 5 % documentos.
Es el escenario cuyo resultado se convierte en la línea base.

**Saturación — hasta que algo cede.** Rampa hasta que se incumpla un umbral. No para
publicar el número, sino para **saber cómo muere**: ¿rechaza limpio con 429, o se degrada en
silencio y empieza a dar timeouts? La respuesta cambia lo que hay que arreglar.

### 3.4 Lo que se mide además de la latencia

La instrumentación de `SPEC-BE-05` ya publica esto; la prueba de carga sólo lo recoge y lo
afirma:

| Métrica | Qué decide |
|---|---|
| `llm_mamparo_disponibles` (mínimo) | Si 12 sobra o falta |
| `llm_modelo_cache` (aciertos/fallos) | Si el lock de `BE-K1` es un cuello real |
| `procurement_request` p95 | Si el mamparo de 30 es sensato |
| Hilos vivos (máximo) | Si 120 es holgado o justo |
| RSS y pausas de GC | Si la extracción cabe |
| `llm_circuito_estado` | Que la carga por sí sola **no** abre el circuito |

La última es una comprobación fácil de olvidar: si una prueba de carga abre el
cortacircuitos, el sistema está confundiendo saturación propia con caída del proveedor, y eso
es un defecto.

### 3.5 La medición que decide una spec

```js
// escenarios/cache-de-modelos.js
//
// SPEC-BE-08 §BE-K1 afirma que computeIfAbsent sobre synchronizedMap serializa a todos los
// hilos del proveedor mientras se construye un cliente. Esta prueba lo convierte en número:
// se compara p95 de un arranque en frío con N modelos distintos contra el mismo escenario
// con un solo modelo ya cacheado. La diferencia ES el coste de la contención.
//
// El resultado decide si el paso 7 de aquella spec —el de mayor riesgo— compensa.
```

Es el mejor ejemplo de para qué sirve esta spec: **convertir un argumento de diseño en un
dato antes de gastar jornadas en refactorizarlo.**

### 3.6 Perfil `carga` del backend

```properties
%carga.quarkus.rest-client.secop.url=http://localhost:8089
%carga.agente.ia.providers.gemini.base-url=http://localhost:8090
%carga.agente.seguridad.api-keys.carga=sha256:<hash fijo de prueba>
# Los límites de tasa se desactivan: se está midiendo el mamparo, no el limitador.
%carga.agente.seguridad.limites.analisis=0
%carga.quarkus.log.level=WARN
```

Desactivar el límite de tasa es deliberado y hay que decirlo: con 20/hora, un escenario de
20 usuarios chocaría contra el limitador en el primer minuto y nunca llegaría al mamparo.
**Se mide una barrera cada vez**; el limitador tiene su propio escenario.

---

## 4. Diseño — frontend

### 4.1 Aquí «carga» significa otra cosa

Un frontend de Next.js servido estáticamente no tiene concurrencia que saturar: el cuello es
el backend, y eso ya lo cubre §3. Lo que sí se degrada, y hoy nadie mide, es **el navegador
del usuario con datos reales**:

| Riesgo | Origen |
|---|---|
| 500 procesos renderizados sin virtualización | `limite` llega hasta 500 (`FE-M13`) |
| Un pliego de 800 000 caracteres en un `<textarea>` | Cada pulsación redibuja |
| Serializar el espacio completo a `sessionStorage` en cada cambio | `FE-M11`, con `JSON.stringify` de todo |
| Un chat largo sin virtualizar | Cada fragmento vuelve a renderizar el hilo |
| Una matriz de cumplimiento de 200 filas | Se ordena en cada render |

### 4.2 Presupuestos de rendimiento

```json
{
  "budgets": [{
    "resourceSizes": [
      { "resourceType": "script", "budget": 350 },
      { "resourceType": "total",  "budget": 700 }
    ],
    "timings": [
      { "metric": "largest-contentful-paint", "budget": 2500 },
      { "metric": "interaction-to-next-paint", "budget": 200 },
      { "metric": "cumulative-layout-shift",  "budget": 0.1 }
    ]
  }]
}
```

En CI con Lighthouse CI sobre las cinco rutas. El presupuesto de script importa porque
`SPEC-FE-03` §3.6 añade `react-markdown` + `rehype-sanitize` (~40 kB comprimidos): el
presupuesto es lo que impide que esa adición pase a ser un goteo.

### 4.3 Pruebas de volumen, con Playwright

Lo que Lighthouse no ve, porque necesita datos realistas:

```ts
test("500 resultados no bloquean la interacción", async ({ page }) => {
  await interceptarBusqueda(page, generarProcesos(500));
  await page.goto("/?limite=500");
  await page.getByRole("button", { name: "Buscar" }).click();

  const inp = await medirINP(page, () =>
      page.getByRole("button", { name: /analizar este proceso/i }).first().click());
  expect(inp).toBeLessThan(200);
});

test("un pliego de 800k caracteres no congela el área de texto", async ({ page }) => {
  await page.goto("/analizar");
  await page.getByLabel(/texto del pliego/i).fill(textoDe(800_000));
  const t = await medirLatenciaDeTecla(page);
  expect(t).toBeLessThan(100);           // detecta el redibujado por pulsación
});

test("guardar el espacio con un pliego grande no bloquea el hilo principal", async ({ page }) => {
  // FE-M11: JSON.stringify de 800k caracteres en cada cambio, sin retardo.
  const bloqueo = await medirTareasLargas(page, () => escribirYAnalizar(page));
  expect(bloqueo).toBeLessThan(200);
});

test("un chat de 100 mensajes sigue respondiendo al escribir", async ({ page }) => { … });
```

La segunda y la tercera miden defectos ya identificados. Convertirlos en un umbral es lo que
impide que reaparezcan.

### 4.4 Datos de prueba

Un generador de procesos, requisitos y matrices realistas —longitudes, acentos, objetos
contractuales de 2 000 caracteres como los de SECOP— en `pruebas/generadores/`. Compartido
entre las pruebas de integración y las de carga.

**Nunca datos reales de un proceso ni de un oferente** en el repositorio (`SPEC-NT-02`).

---

## 5. Cómo se usa esto

### 5.1 Cadencia

| Cuándo | Qué |
|---|---|
| Cada PR | Lighthouse CI + presupuestos; pruebas de volumen del frontend |
| Antes de publicar | Escenario mixto, comparado contra la línea base |
| Al tocar resiliencia o concurrencia | El escenario afectado, obligatorio |
| Trimestral | Saturación, para revisar los mamparos |

Los escenarios completos de backend **no** van en cada PR: tardan minutos y su valor está en
la comparación, no en la frecuencia.

### 5.2 Las líneas base se versionan

```
resultados/2026-08-25-mixto.json     ← línea base inicial
resultados/2026-09-10-mixto.json     ← tras la fase 7
```

Con un resumen en `README.md`: qué cambió, contra qué se compara y qué se decidió. Un
resultado de carga sin el anterior al lado es un número sin significado.

### 5.3 De los números a los SLO

`SPEC-NT-03` §3.4 declara SLO y advierte que se fijan **tras 30 días de métricas**. La carga
no los sustituye —el tráfico real es otra cosa— pero da el punto de partida y, sobre todo,
dice **si el objetivo es alcanzable** antes de comprometerse con él. Comprometer un p95 de 90 s
para el análisis sin haber medido nunca es prometer a ciegas.

---

## 6. Criterios de aceptación

1. Ningún escenario llama a un proveedor de modelos real ni a datos.gov.co; verificado
   porque el perfil `carga` apunta a `localhost` y no hay credenciales configuradas.
2. Existe una línea base fechada del escenario mixto, versionada.
3. El escenario de análisis demuestra que superar el mamparo produce **429 con
   `Retry-After`** en menos de 200 ms al p99, y que ningún mensaje de rechazo nombra al
   proveedor.
4. Con el mamparo del modelo lleno, la búsqueda mantiene su p95 por debajo de 3 s.
5. Ningún escenario de carga abre el cortacircuitos: saturación propia y caída del proveedor
   no se confunden.
6. El escenario de documentos mide RSS máximo y pausas de GC, y no supera el presupuesto.
7. La medición de la caché de modelos existe y su resultado está anotado en `SPEC-BE-08` §8.2
   como dato, no como suposición.
8. Lighthouse CI corre en las cinco rutas de cada PR con presupuestos que hacen fallar.
9. Existe una prueba de volumen por cada riesgo de §4.1.
10. Ningún dato real de un proceso o de un oferente está versionado.
11. `pruebas-de-carga/README.md` explica cómo ejecutar cada escenario y cómo leerlo.

---

## 7. Riesgos

| Riesgo | Mitigación |
|---|---|
| Latencias del doble inventadas → prueba que mide una fantasía. | Cada número lleva origen y fecha. Mientras no haya producción, se toman de `verificar_en_vivo.py` y se marcan provisionales. |
| Resultados no comparables entre máquinas. | Se compara siempre contra la línea base **del mismo entorno**. Un número absoluto de un portátil no significa nada; la variación sí. |
| Las pruebas de carga se ejecutan una vez y se abandonan. | Cadencia de §5.1 en la definición de terminado. Si al cabo de dos trimestres nadie las corrió, se borran: mantener algo que nadie usa cuesta más que no tenerlo. |
| Desactivar el limitador para medir el mamparo enmascara un problema real. | Es deliberado y está documentado: una barrera cada vez. El limitador tiene escenario propio. |
| k6 sin SSE nativo complica el escenario de chat. | `xk6-sse` o cliente propio con lectura de flujo. Si resulta frágil, el chat se mide con Playwright, que sí habla SSE de forma natural. |

---

## 8. Fuera de alcance

- Pruebas de resistencia de días (*soak*): tienen sentido cuando haya un despliegue
  permanente que observar.
- Pruebas de escalado horizontal: hasta que `SPEC-NT-03` §3.6 no fije un destino de
  despliegue, sería medir una arquitectura hipotética.
- Comparativa de rendimiento entre proveedores de modelos: es una decisión de producto y de
  coste, no una prueba de carga del sistema.
- Compilación nativa y su rendimiento. `SPEC-NT-03` ya decide empezar con imagen JVM.
