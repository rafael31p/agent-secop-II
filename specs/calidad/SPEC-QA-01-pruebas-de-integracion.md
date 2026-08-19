# SPEC-QA-01 · Pruebas de integración: backend y frontend

| | |
|---|---|
| **Estado** | Propuesta |
| **Prioridad** | 🟠 Media |
| **Cierra** | BE-B22, TR-M7; da red de seguridad a SPEC-BE-02, BE-08 y SEC-01 |
| **Depende de** | Fase 3 (resiliencia declarada) y fase 7 (superficie estable) |
| **Esfuerzo** | 5–7 jornadas |
| **Línea base** | 164 pruebas backend, 64 frontend, 18 de agosto de 2026 |

---

## 1. Problema

Hay 228 pruebas y ninguna prueba de integración en el sentido estricto: **ninguna verifica
que dos piezas se comporten bien juntas cuando la de abajo falla**.

Lo que hay hoy, clasificado con honestidad:

| Tipo | Cuántas | Qué cubren |
|---|---|---|
| Unitarias puras | mayoría | Recorte de JSON, construcción de cláusulas, endurecimiento del esquema, formato, heurística |
| De arquitectura | 5 | Regla de dependencias con ArchUnit |
| De recurso (`@QuarkusTest`) | algunas | Códigos de estado y forma del cuerpo, camino feliz |
| De componente (Testing Library) | 64 | Vistas contra `fetch` parcheado |

**Lo que no está cubierto, y es justo lo caro:**

- Que un 429 se reintente y un 401 **no**, contando llamadas al proveedor.
- Que el cortacircuitos abra, deje de llamar y se recupere.
- Que el mamparo rechace la petición 13 con 429 y `Retry-After`, no con 503 culpando al
  proveedor.
- Que el presupuesto de tiempo del caso de uso acote **dos** llamadas encadenadas.
- Que el protocolo SSE emita `delta`/`error`/`fin` en orden y se reensamble bien.
- Que cancelar el flujo aborte la llamada saliente.
- Que un endpoint sin clave devuelva 401 **sin realizar ninguna llamada saliente**.
- Que un DOCX bomba no tumbe el proceso.
- Que la degradación de SECOP no sustituya la consulta por otra distinta.

Las cuatro primeras son las políticas más importantes del sistema y hoy sólo están
declaradas en anotaciones. Una anotación mal escrita —o sobrescrita por una propiedad con la
unidad equivocada, que ya pasó una vez y quedó documentado en `application.properties`—
pasa desapercibida.

Y el defecto `SEC-1` es la ilustración perfecta del hueco: la prueba de la cláusula de
importe existe, usa 1 000 000 y 5 000 000, y lleva verde desde el principio sobre un
comportamiento roto para cualquier valor ≥ 10 000 000.

---

## 2. Decisión

1. **La frontera es HTTP, no la clase.** Una prueba de integración entra por el recurso REST
   y sale por el doble de la dependencia externa. Lo de en medio no se simula.
2. **Las dependencias externas se sustituyen por dobles fieles, nunca por mocks de método.**
   WireMock ya está en el proyecto como biblioteca en la misma JVM, decisión tomada porque
   **no hay Docker en este entorno** y Testcontainers no es opción. Se mantiene.
3. **Ninguna prueba llama a un proveedor real.** Ni al de modelos —cuesta dinero y depende de
   cuota— ni a datos.gov.co. `verificar_en_vivo.py` conserva ese papel, manual y previo a
   publicar.
4. **Una prueba por política declarada.** Si algo se declara con una anotación, hay una
   prueba que lo demuestra contando invocaciones.
5. **El frontend prueba contra el puerto, no contra `fetch`.**

---

## 3. Diseño — backend

### 3.1 Organización

```
src/test/java/co/agentesecop/
├── unidad/                      lo que ya hay: puro, sin contenedor, milisegundos
├── arquitectura/                ArchUnit
└── integracion/
    ├── dobles/
    │   ├── ProveedorFalso.java          WireMock con guiones por escenario
    │   ├── SecopFalso.java              el ServidorSecopFalso actual, ampliado
    │   └── PerfilDeIntegracion.java     apunta los clientes a los dobles
    ├── contrato/                ContratoAnalisisIT, ContratoProcesosIT, …
    ├── resiliencia/             ReintentosIT, CortacircuitosIT, MamparoIT, PresupuestoIT
    ├── seguridad/               AutenticacionIT, LimiteDeTasaIT, DocumentoHostilIT, FugaDeDatosIT
    └── flujo/                   ChatSseIT, DegradacionSecopIT, ValidacionEncadenadaIT
```

Sufijo `IT` para que Failsafe las separe de Surefire: `mvnw test` sigue siendo rápido y
`mvnw verify` corre todo. Hoy `skipITs=true` en el `pom.xml`; pasa a `false` y las
integración corren en CI.

### 3.2 El doble del proveedor, con guiones

La pieza central. WireMock con escenarios, para que la respuesta dependa de cuántas veces se
ha llamado:

```java
public final class ProveedorFalso {

    /** Falla las primeras `veces` llamadas con `estado`, luego responde bien. */
    public static void falloTransitorio(int veces, int estado, String respuestaFinal) {
        for (int i = 0; i < veces; i++) {
            stubFor(post(urlPathMatching("/.*"))
                    .inScenario("transitorio").whenScenarioStateIs(estadoNumero(i))
                    .willReturn(aResponse().withStatus(estado))
                    .willSetStateTo(estadoNumero(i + 1)));
        }
        stubFor(post(urlPathMatching("/.*"))
                .inScenario("transitorio").whenScenarioStateIs(estadoNumero(veces))
                .willReturn(okJson(respuestaFinal)));
    }

    /** Nunca responde. Para timeouts y para el presupuesto del caso de uso. */
    public static void nuncaResponde() {
        stubFor(post(urlPathMatching("/.*"))
                .willReturn(aResponse().withFixedDelay((int) Duration.ofMinutes(10).toMillis())));
    }

    /** Responde algo que no cumple el esquema. */
    public static void respuestaInutilizable() { … }

    /** Cuántas veces se llamó de verdad. Es la aserción que importa. */
    public static int llamadas() {
        return findAll(postRequestedFor(urlPathMatching("/.*"))).size();
    }
}
```

`llamadas()` es el corazón de la suite de resiliencia. Verificar que el resultado es correcto
no dice nada sobre la política; **verificar cuántas veces se llamó al proveedor, sí**.

### 3.3 Resiliencia: una prueba por política

```java
@QuarkusTest
@TestProfile(PerfilDeIntegracion.class)
class ReintentosIT {

    @Test
    void un429SeReintentaYAcabaRespondiendo() {
        ProveedorFalso.falloTransitorio(1, 429, ANALISIS_VALIDO);

        given().header("X-Api-Key", CLAVE).body(solicitudDeAnalisis())
                .post("/api/analisis/requisitos")
                .then().statusCode(200).body("requisitos", not(empty()));

        assertThat(ProveedorFalso.llamadas())
                .as("un fallo transitorio debe consumir exactamente un reintento")
                .isEqualTo(2);
    }

    @Test
    void un401NoSeReintentaNiUnaVez() {
        ProveedorFalso.siempre(401);

        given().header("X-Api-Key", CLAVE).body(solicitudDeAnalisis())
                .post("/api/analisis/requisitos")
                .then().statusCode(401);

        assertThat(ProveedorFalso.llamadas())
                .as("una credencial inválida no mejora reintentando: falla rápido")
                .isEqualTo(1);
    }

    @Test
    void seAgotanLosIntentosYElUsuarioRecibeUnMensajeAccionable() {
        ProveedorFalso.siempre(503);

        given().header("X-Api-Key", CLAVE).body(solicitudDeAnalisis())
                .post("/api/analisis/requisitos")
                .then().statusCode(502)
                .body("detail", containsString("Reintenta"))
                .body("correlationId", not(emptyString()));

        assertThat(ProveedorFalso.llamadas()).isEqualTo(3);
    }
}
```

```java
class CortacircuitosIT {

    @BeforeEach
    void circuitoLimpio() {
        circuitos.reset(PoliticaDeResiliencia.CIRCUITO);   // CircuitBreakerMaintenance
    }

    @Test
    void trasElUmbralDejaDeLlamarAlProveedor() {
        ProveedorFalso.siempre(503);

        for (int i = 0; i < 8; i++) { analizar(); }
        int llamadasHastaAbrir = ProveedorFalso.llamadas();

        analizar();   // ya con el circuito abierto

        assertThat(ProveedorFalso.llamadas())
                .as("con el circuito abierto no se llama: ese es todo el punto")
                .isEqualTo(llamadasHastaAbrir);
    }

    @Test
    void elCircuitoAbiertoSeReflejaEnLaSondaDeDisponibilidad() {
        ProveedorFalso.siempre(503);
        repetir(8, this::analizar);

        given().get("/q/health/ready").then()
                .statusCode(503)
                .body("checks.find { it.name == 'modelos-de-lenguaje' }.status", is("DOWN"));
    }
}
```

La segunda prueba une resiliencia y observabilidad: comprueba que el cortacircuitos —el
sensor— llega efectivamente a la sonda que un orquestador consulta. Es la afirmación central
de `SPEC-BE-05` §3.1 y hoy nada la verifica.

```java
class MamparoIT {

    @Test
    void laPeticionQueExcedeElMamparoRecibe429YNoCulpaAlProveedor() throws Exception {
        ProveedorFalso.tarda(Duration.ofSeconds(5));
        var barrera = new CountDownLatch(1);
        lanzarConcurrentes(12, () -> { barrera.await(); analizar(); });
        esperarAQueHaya(12, "llamadas en vuelo");

        var respuesta = given().header("X-Api-Key", CLAVE).body(solicitudDeAnalisis())
                .post("/api/analisis/requisitos");

        assertThat(respuesta.statusCode()).isEqualTo(429);
        assertThat(respuesta.header("Retry-After")).isNotNull();
        assertThat(respuesta.jsonPath().getString("detail"))
                .as("el mamparo es una decisión nuestra: culpar al proveedor manda al "
                        + "usuario a cambiar a otro que tiene el mismo límite")
                .doesNotContain("Gemini");
        barrera.countDown();
    }

    @Test
    void conElModeloSaturadoLaBusquedaSigueFuncionando() throws Exception {
        ProveedorFalso.tarda(Duration.ofSeconds(30));
        lanzarConcurrentes(12, this::analizar);
        esperarAQueHaya(12, "llamadas en vuelo");

        given().header("X-Api-Key", CLAVE).body(filtroSimple())
                .post("/api/procesos/buscar")
                .then().statusCode(200);

        given().get("/q/health/ready").then().time(lessThan(200L));
    }
}
```

La segunda es **la prueba más valiosa de toda la suite**: es la única que demuestra que los
mamparos separados hacen lo que prometen. Si algún día alguien unifica los pools, esta prueba
es lo que lo detecta.

Nada de `Thread.sleep` para sincronizar: `CountDownLatch` y espera por condición. Una prueba
de concurrencia con `sleep` es una prueba intermitente, y una prueba intermitente acaba
desactivada.

### 3.4 Seguridad

```java
class AutenticacionIT {

    @ParameterizedTest
    @MethodSource("rutasProtegidas")
    void sinClaveNoSeLlamaAlExterior(String metodo, String ruta, String cuerpo) {
        given().body(cuerpo).request(metodo, ruta).then().statusCode(401);

        assertThat(ProveedorFalso.llamadas())
                .as("un 401 debe cortar antes de gastar dinero")
                .isZero();
        assertThat(SecopFalso.llamadas()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/analisis/requisitos/", "/api/analisis/requisitos;x=1",
        "//api/analisis/requisitos", "/api/./analisis/requisitos"})
    void ningunaFormaAlternativaDeLaRutaEludeElFiltro(String ruta) {
        given().body(SOLICITUD).post(ruta).then().statusCode(anyOf(is(401), is(404), is(405)));
    }

    @Test
    void todaRutaDeclaradaEstaProtegidaOEsAbiertaAProposito() {
        // Recorre las rutas que publica OpenAPI y exige que cada una exija clave o figure
        // en la lista de abiertas. Un endpoint nuevo rompe esta prueba: es el objetivo.
    }
}
```

```java
class FugaDeDatosIT {

    @Test
    void elMensajeDelProveedorNoLlegaAlNavegador() {
        ProveedorFalso.fallaConMensaje(
                "Request failed: https://generativelanguage.googleapis.com/v1/models"
                        + "?key=AIzaSyCLAVE_DE_PRUEBA_NO_REAL");

        String cuerpo = analizar().asString();

        assertThat(cuerpo).doesNotContain("AIzaSy");
        assertThat(cuerpo).doesNotContain("googleapis.com");
        assertThat(cuerpo).contains("correlationId");
    }
}
```

```java
class DocumentoHostilIT {

    @Test void unDocxBombaSeRechazaSinTumbarElProceso() { … }
    @Test void unDocxConEntidadExternaNoAbreConexionesSalientes() {
        // El servidor que la entidad apuntaría es un WireMock: si recibe una petición,
        // la protección contra XXE no está activa. Cuenta 0.
    }
    @Test void unZipRenombradoAPdfSeRechazaPorContenido() { … }
    @Test void elNombreDevueltoNoContieneSeparadoresNiControles() { … }
}
```

Los artefactos hostiles **se generan en la prueba**, nunca se versionan: un repositorio con
una bomba zip dentro es un problema para el análisis antivirus de quien lo clone y para
cualquier escáner de CI.

### 3.5 El protocolo SSE

```java
class ChatSseIT {

    @Test
    void elFlujoEmiteDeltaYCierraConFin() {
        ProveedorFalso.emiteFragmentos("Según", " la ", "Ley 80");

        List<Suceso> sucesos = consumirSse("/api/chat", solicitudDeChat());

        assertThat(sucesos).extracting(Suceso::nombre)
                .containsExactly("delta", "delta", "delta", "fin");
        assertThat(textoDe(sucesos)).isEqualTo("Según la Ley 80");
    }

    @Test
    void unFalloAMitadDeFlujoSeReportaComoEventoYNoRompeLaConexion() {
        ProveedorFalso.emiteYFalla("Según", " la ");

        List<Suceso> sucesos = consumirSse("/api/chat", solicitudDeChat());

        assertThat(sucesos).extracting(Suceso::nombre)
                .containsExactly("delta", "delta", "error", "fin");
    }

    @Test
    void cancelarElFlujoAbortaLaLlamadaSaliente() {
        ProveedorFalso.emiteLentamente(100, Duration.ofMillis(50));

        var conexion = abrirSse("/api/chat", solicitudDeChat());
        esperarAQueLleguen(3, conexion);
        conexion.close();

        esperarA(() -> ProveedorFalso.conexionesAbiertas() == 0,
                "cerrar la pestaña debe cortar la llamada: si no, se siguen facturando "
                        + "tokens de una respuesta que nadie va a leer");
    }
}
```

El cliente SSE de prueba es el mismo problema que resuelve `chatStream` en el frontend
—reensamblar por línea en blanco—, así que la prueba **valida el protocolo desde el otro
lado** y detecta cualquier divergencia entre lo que emite el servidor y lo que espera el
cliente.

### 3.6 Degradación de SECOP

```java
class DegradacionSecopIT {

    @Test
    void conLaFuenteCaidaNoSeSustituyeLaConsultaPorOtraDistinta() {
        SecopFalso.siempre(500);

        var respuesta = buscar(filtroCon("ciberseguridad", "Antioquia"));

        // Antes devolvía 200 con los últimos N procesos de cualquier tipo del país.
        assertThat(respuesta.statusCode()).isEqualTo(502);
        assertThat(respuesta.jsonPath().getList("procesos")).isNullOrEmpty();
    }

    @Test
    void unaFechaInvalidaIgnoraSoloEseFiltroYAvisa() {
        var respuesta = buscar(filtroConFecha("ayer"));

        assertThat(respuesta.statusCode()).isEqualTo(200);
        assertThat(respuesta.jsonPath().getList("advertencias"))
                .anySatisfy(a -> assertThat(a.toString()).contains("fechaDesde"));
    }

    @Test
    void unImporteGrandeProduceUnaConsultaFiltradaDeVerdad() {
        buscar(filtroConValorMin(new BigDecimal("500000000")));

        // Cierra SEC-1 desde la frontera: si vuelve la notación científica, SoQL falla y
        // la consulta degrada — esta prueba lo detecta aunque la unitaria no lo hiciera.
        SecopFalso.verificarQueLaUltimaConsultaContiene("precio_base >= 500000000");
    }
}
```

---

## 4. Diseño — frontend

### 4.1 Tres niveles, no uno

| Nivel | Herramienta | Frontera | Cuándo |
|---|---|---|---|
| Componente | Vitest + Testing Library | Un componente, con propiedades | Cada push |
| Integración | Vitest + **pasarela falsa inyectada** | Una vista completa con su hook y su espacio de trabajo | Cada push |
| Extremo a extremo | Playwright | Navegador real contra backend real con dobles | Cada PR |

Hoy sólo existe el primero, y con el defecto de que parchea `fetch`.

### 4.2 Integración con pasarela falsa

Depende de `SPEC-FE-01` §3.3: la inyección por contexto es lo que hace esto posible.

```tsx
function GatewayFalso(respuestas: Partial<AgentGateway>): AgentGateway { … }

it("transfiere los requisitos del análisis a la vista de propuesta", async () => {
  render(
    <ProveedorDependencias gateway={GatewayFalso({ analizarPliego: async () => ANALISIS })}>
      <App ruta="/analizar" />
    </ProveedorDependencias>,
  );

  await usuario.type(screen.getByLabelText(/texto del pliego/i), PLIEGO);
  await usuario.click(screen.getByRole("button", { name: /analizar requisitos/i }));

  expect(await screen.findByText(/RT-01/)).toBeVisible();

  await usuario.click(screen.getByRole("button", { name: /generar propuesta/i }));
  expect(screen.getByText(/se usarán los 5 requisitos/i)).toBeVisible();
});
```

La prueba deja de saber que existe HTTP, y el encadenado entre pasos —que es la promesa
central del producto— pasa a estar cubierto.

Casos que hoy no se prueban y deberían:

```tsx
it("muestra el aviso accionable cuando el backend responde 503 sin proveedores", …);
it("muestra la hora de restablecimiento cuando el backend responde 429", …);
it("conserva la búsqueda al navegar a analizar y volver", …);          // FE-A3
it("permite cancelar un análisis en curso sin dejar estado a medias", …); // FE-C2
it("avisa de forma visible cuando sessionStorage supera la cuota", …);  // FE-M11
it("renderiza inerte un markdown hostil devuelto por el modelo", …);    // SEC-7
```

### 4.3 Extremo a extremo con Playwright

**Con el backend real y sus dependencias externas dobladas.** No con el backend simulado: lo
que se quiere probar aquí es precisamente la integración de los dos módulos, incluido CORS,
el SSE real y las cabeceras.

```ts
test("recorrido completo: buscar, analizar, proponer, validar", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("Texto en el objeto").fill("ciberseguridad");
  await page.getByRole("button", { name: "Buscar" }).click();
  await expect(page.getByRole("article")).toHaveCount(3);
  …
});

test("el chat escribe progresivamente y se puede detener", async ({ page }) => {
  await page.goto("/consultar");
  await page.getByLabel("Consulta").fill("¿Qué modalidad aplica?");
  await page.keyboard.press("Enter");
  await expect(page.getByText(/Según/)).toBeVisible();      // llegó el primer fragmento
  await page.getByRole("button", { name: "Detener" }).click();
  await expect(page.getByRole("button", { name: "Enviar" })).toBeEnabled();
});

test("no hay infracciones de accesibilidad en las cinco rutas", async ({ page }) => {
  for (const ruta of ["/", "/analizar", "/proponer", "/validar", "/consultar"]) {
    await page.goto(ruta);
    const r = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag22aa"]).analyze();
    expect(r.violations).toEqual([]);
  }
});

test("todo el flujo se recorre con teclado y el foco siempre se ve", async ({ page }) => { … });
```

Las dos últimas cierran los criterios de aceptación de `SPEC-FE-03` §5.1 y §5.2, que hoy no
tienen forma de verificarse.

Deliberadamente **corta**: seis u ocho recorridos del camino principal. Una suite de extremo a
extremo amplia es lenta e intermitente, y una suite intermitente acaba desactivada — momento
en el que deja de valer cero y pasa a valer menos que cero, porque da confianza falsa.

### 4.4 Arranque del entorno

Sin Docker, así que se orquesta con scripts:

```json
{
  "scripts": {
    "e2e": "start-server-and-test e2e:backend http://localhost:8000/q/health/ready e2e:front",
    "e2e:backend": "java -jar ../backend-quarkus/target/quarkus-app/quarkus-run.jar -Dquarkus.profile=e2e",
    "e2e:front": "start-server-and-test dev http://localhost:3000 'playwright test'"
  }
}
```

El perfil `e2e` del backend apunta los clientes a los dobles y usa una clave de API fija de
prueba. **Nunca credenciales reales**: `TestIsolationGuardTest` (`SPEC-BE-05` §3.5) ya lo
verifica para `test` y se extiende a `e2e`.

---

## 5. Criterios de aceptación

1. Existe una prueba de integración por cada política declarada con anotación: reintentos,
   cortacircuitos, mamparo, presupuesto de caso de uso, límite de tasa.
2. Cada una de esas pruebas afirma sobre el **número de llamadas salientes**, no sólo sobre
   el resultado.
3. Con el modelo saturado, la búsqueda responde 200 y `/q/health/ready` en menos de 200 ms.
4. Ninguna prueba de la suite realiza una llamada de red fuera de `localhost`, verificado por
   un `SecurityManager` o por la ausencia de configuración de credenciales.
5. El protocolo SSE está cubierto de extremo a extremo, incluida la cancelación.
6. Ninguna prueba de componente del frontend parchea `fetch`.
7. Playwright cubre el recorrido de los cuatro pasos, el chat con cancelación, la
   accesibilidad de las cinco rutas y el recorrido con teclado.
8. `mvnw verify` ejecuta las pruebas de integración (`skipITs=false`) y CI las corre.
9. La suite completa de CI termina en menos de diez minutos.
10. Ninguna prueba usa `Thread.sleep` ni `waitForTimeout` para sincronizar.
11. Ningún artefacto hostil está versionado: todos se generan en la prueba.

---

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| Las pruebas de concurrencia resultan intermitentes. | `CountDownLatch` y espera por condición, nunca `sleep`. Criterio 10. Una intermitente se arregla o se borra en la misma semana. |
| El cortacircuitos filtra estado entre pruebas. | `CircuitBreakerMaintenance.reset` en `@BeforeEach`; sin eso, el orden de ejecución cambia el resultado. |
| Playwright alarga demasiado el CI. | Sólo en PR, seis a ocho recorridos, un solo navegador. Si pasa de tres minutos, se recorta. |
| Levantar el backend para E2E es frágil sin Docker. | `start-server-and-test` espera a `/q/health/ready`, que es una sonda real y no un puerto abierto. |
| Se duplica cobertura entre niveles. | La unitaria prueba reglas; la de integración, colaboración bajo fallo; la E2E, que los dos módulos hablan. Si una prueba de integración sólo verifica una regla, va al nivel de abajo. |

---

## 7. Fuera de alcance

- Pruebas de carga: [SPEC-QA-02](SPEC-QA-02-pruebas-de-carga.md).
- Pruebas contra proveedores reales: `verificar_en_vivo.py` conserva ese papel, manual.
- Pruebas de mutación.
- Pruebas de regresión visual.
- Contract testing con Pact: con un solo consumidor y un solo productor, el OpenAPI
  versionado de `SPEC-DOC-01` §3.3 ya da la garantía.
