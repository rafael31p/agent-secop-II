package co.agentesecop.pruebas;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.agentesecop.application.port.in.AnalizarPliego;
import co.agentesecop.application.port.out.ModeloDeLenguaje;
import co.agentesecop.application.port.out.SeleccionDeModelo;
import co.agentesecop.domain.model.tender.AnalisisDePliego;
import co.agentesecop.ia.ErroresIA;
import co.agentesecop.secop.InyectarSecopFalso;
import co.agentesecop.secop.ServidorSecopFalso;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ninguna petición vive para siempre, y la IA no puede ahogar a la búsqueda.
 *
 * <h2>Lo que se está evitando</h2>
 *
 * <p>Con la configuración anterior —300 s de timeout por llamada, tres intentos— un
 * análisis podía retener un hilo de trabajo quince minutos, y una validación sin
 * requisitos estructurados, al encadenar dos llamadas, hasta media hora. El pool de
 * Quarkus son 200 hilos: agotarlo dejaba sin respuesta a <em>todo</em> el servicio,
 * incluida la búsqueda de procesos, que ni siquiera usa el modelo.
 *
 * <p>Las dos afirmaciones que se comprueban aquí son las que cierran ese agujero: existe
 * un techo por caso de uso, y las llamadas al modelo tienen su propio cupo separado del de
 * la fuente de datos.
 */
@QuarkusTest
@TestProfile(PresupuestoYMamparoTest.PerfilEstrecho.class)
// Restringido a esta clase: sin esto, la clave falsa y la URL del
// proveedor simulado se aplican a TODA la suite —es el comportamiento por
// defecto de @QuarkusTestResource— y contaminan pruebas que no lo piden.
@QuarkusTestResource(value = ServidorModeloFalso.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(ServidorSecopFalso.class)
class PresupuestoYMamparoTest {

    /** Presupuesto del caso de uso en esta prueba. Real: 150 s. */
    private static final long PRESUPUESTO_MS = 1_500;

    /** Lo que tarda el proveedor falso: siempre más que el presupuesto. */
    private static final int RETARDO_MS = 20_000;

    public static class PerfilEstrecho implements QuarkusTestProfile {
        private static final String POLITICA =
                "co.agentesecop.adapter.out.llm.PoliticaDeResiliencia/estructurado/";

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    // El presupuesto del caso de uso, muy por debajo del timeout por
                    // llamada: se comprueba que corta el de arriba, que es el que acota la
                    // petición HTTP completa.
                    "co.agentesecop.application.service.ServicioDeAnalisis/analizar/Timeout/value",
                    String.valueOf(PRESUPUESTO_MS),
                    POLITICA + "Timeout/value", "60000",
                    POLITICA + "Retry/maxRetries", "0",
                    // Mamparo de uno: basta una llamada en vuelo para llenarlo, y así la
                    // saturación se puede provocar sin montar una prueba de carga.
                    POLITICA + "Bulkhead/value", "1",
                    POLITICA + "CircuitBreaker/requestVolumeThreshold", "1000");
        }
    }

    private static final String RUTA_SECOP = "/resource/p6dx-8zbt.json";

    private static final String UN_PROCESO = """
            [{"id_del_proceso": "CO1.REQ.999",
              "entidad": "MINISTERIO DE TECNOLOGIAS",
              "descripci_n_del_procedimiento":
                  "Desarrollo de software para el portal ciudadano en la nube",
              "precio_base": "1000000000",
              "fecha_de_publicacion_del": "2026-08-05T00:00:00.000"}]
            """;

    @InyectarModeloFalso
    WireMockServer proveedor;

    @InyectarSecopFalso
    WireMockServer secop;

    @Inject
    AnalizarPliego analizarPliego;

    @Inject
    ModeloDeLenguaje modelo;

    @BeforeEach
    void reiniciar() {
        proveedor.resetAll();
        secop.resetAll();
        secop.stubFor(get(urlPathEqualTo(RUTA_SECOP))
                .willReturn(okJson(UN_PROCESO)));
    }

    /** Un proveedor que acepta la conexión y no contesta: el peor caso, no el fallo limpio. */
    private void proveedorQueNoResponde() {
        proveedor.stubFor(post(urlPathEqualTo(ServidorModeloFalso.RUTA))
                .willReturn(aResponse()
                        .withFixedDelay(RETARDO_MS)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ServidorModeloFalso.respuestaDeChat(
                                ServidorModeloFalso.ANALISIS_VALIDO))));
    }

    private AnalisisDePliego analizarDirecto() {
        return modelo.estructurado(
                "Eres un analista de pliegos.",
                "Analiza este anexo técnico.",
                SeleccionDeModelo.porDefecto(),
                AnalisisDePliego.class);
    }

    @Test
    @DisplayName("El presupuesto del caso de uso corta aunque el proveedor no conteste nunca")
    void elPresupuestoCorta() {
        proveedorQueNoResponde();

        long inicio = System.nanoTime();
        assertThrows(TimeoutException.class, () -> analizarPliego.analizar(
                new AnalizarPliego.ComandoDeAnalisis(
                        "Anexo técnico con requisitos de accesibilidad. ".repeat(3),
                        "Portal ciudadano", null, null, null, null,
                        SeleccionDeModelo.porDefecto())));
        long transcurrido = (System.nanoTime() - inicio) / 1_000_000;

        // Lo que importa no es que falle, sino que falle *pronto*: sin presupuesto, esta
        // llamada habría retenido el hilo los veinte segundos del proveedor.
        assertTrue(transcurrido < RETARDO_MS / 2,
                "Debería haber cortado en el presupuesto (%d ms) y tardó %d ms"
                        .formatted(PRESUPUESTO_MS, transcurrido));
    }

    @Test
    @DisplayName("Con el mamparo de IA lleno, buscar procesos sigue respondiendo")
    void elMamparoAislaLaBusqueda() throws InterruptedException {
        proveedorQueNoResponde();

        // Ocupa el único hueco del mamparo y lo deja ocupado mientras dura la prueba.
        CountDownLatch enVuelo = new CountDownLatch(1);
        Thread ocupante = new Thread(() -> {
            enVuelo.countDown();
            try {
                analizarDirecto();
            } catch (RuntimeException esperado) {
                // Acabará en tiempo agotado; da igual, su papel es estorbar.
            }
        }, "ocupante-del-mamparo");
        ocupante.setDaemon(true);
        ocupante.start();
        assertTrue(enVuelo.await(5, TimeUnit.SECONDS), "El ocupante no arrancó");
        // Margen para que la llamada llegue a entrar en el mamparo, no solo a lanzarse.
        Thread.sleep(500);

        // 1) El modelo rechaza rápido en vez de encolar indefinidamente...
        long inicio = System.nanoTime();
        assertThrows(ErroresIA.ProveedorNoDisponible.class, this::analizarDirecto);
        long rechazo = (System.nanoTime() - inicio) / 1_000_000;
        assertTrue(rechazo < 2_000,
                "El rechazo del mamparo debe ser inmediato y tardó %d ms".formatted(rechazo));

        // 2) ...y la búsqueda, que no usa el modelo, responde con normalidad. Es el punto
        // entero de tener mamparos separados: un incidente del proveedor de IA no puede
        // dejar sin catálogo a quien solo quería listar procesos.
        given().contentType(ContentType.JSON)
                .body("{\"texto\": \"software\", \"soloTi\": false, \"limite\": 5}")
                .when().post("/api/procesos/buscar")
                .then().statusCode(200)
                .body("procesos", hasSize(1));

        ocupante.interrupt();
    }
}
