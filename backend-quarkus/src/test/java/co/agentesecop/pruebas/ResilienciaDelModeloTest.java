package co.agentesecop.pruebas;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.agentesecop.application.port.out.ModeloDeLenguaje;
import co.agentesecop.application.port.out.SeleccionDeModelo;
import co.agentesecop.domain.model.tender.AnalisisDePliego;
import co.agentesecop.adapter.out.llm.error.CredencialInvalida;
import co.agentesecop.adapter.out.llm.error.CuotaAgotada;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Qué se reintenta y qué no, contado en llamadas al proveedor.
 *
 * <h2>Por qué se cuentan las peticiones y no basta con el resultado</h2>
 *
 * <p>Porque el resultado no distingue las dos cosas que importan. Una prueba que solo
 * comprueba que un 401 acaba en error pasa igual si se reintentó tres veces —tres llamadas
 * facturables por una clave que nunca va a funcionar— que si se abandonó a la primera.
 * {@code verify(1, …)} y {@code verify(2, …)} son lo que convierte «creemos que no
 * reintenta credenciales inválidas» en un hecho comprobado.
 *
 * <p>Antes de esta clase, ninguna prueba verificaba que un 429 se reintentara ni que un
 * 401 no lo hiciera: la política existía solo en la cabeza de quien escribió el bucle.
 */
@QuarkusTest
@TestProfile(ResilienciaDelModeloTest.PerfilConReintentos.class)
// Restringido a esta clase: sin esto, la clave falsa y la URL del
// proveedor simulado se aplican a TODA la suite —es el comportamiento por
// defecto de @QuarkusTestResource— y contaminan pruebas que no lo piden.
@QuarkusTestResource(value = ServidorModeloFalso.class, restrictToAnnotatedClass = true)
class ResilienciaDelModeloTest {

    /**
     * Reintentos activos y sin espera apreciable, y un cortacircuitos que no se meta en
     * medio: aquí se mide el reintento, no la apertura del circuito. Que todo esto se
     * pueda ajustar desde una prueba es, por sí solo, la demostración de que los
     * parámetros son configuración y no código.
     */
    public static class PerfilConReintentos implements QuarkusTestProfile {
        private static final String POLITICA =
                "co.agentesecop.adapter.out.llm.PoliticaDeResiliencia/estructurado/";

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    POLITICA + "Retry/maxRetries", "1",
                    POLITICA + "Retry/delay", "10",
                    POLITICA + "Retry/jitter", "5",
                    POLITICA + "CircuitBreaker/requestVolumeThreshold", "1000");
        }
    }

    @InyectarModeloFalso
    WireMockServer proveedor;

    @Inject
    ModeloDeLenguaje modelo;

    @BeforeEach
    void limpiar() {
        proveedor.resetAll();
    }

    private AnalisisDePliego analizar() {
        return modelo.estructurado(
                "Eres un analista de pliegos.",
                "Analiza este anexo técnico.",
                SeleccionDeModelo.porDefecto(),
                AnalisisDePliego.class);
    }

    @Test
    @DisplayName("Un 429 se reintenta y la petición acaba respondiendo")
    void reintentaAnte429() {
        proveedor.stubFor(post(urlPathEqualTo(ServidorModeloFalso.RUTA))
                .inScenario("cuota")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withBody("{\"error\":\"rate limit\"}"))
                .willSetStateTo("recuperado"));
        proveedor.stubFor(post(urlPathEqualTo(ServidorModeloFalso.RUTA))
                .inScenario("cuota")
                .whenScenarioStateIs("recuperado")
                .willReturn(okJson(ServidorModeloFalso.respuestaDeChat(
                        ServidorModeloFalso.ANALISIS_VALIDO))));

        AnalisisDePliego analisis = analizar();

        assertNotNull(analisis.resumenEjecutivo());
        // Exactamente dos: el fallo y el reintento. Ni uno más, que sería pagar de sobra.
        proveedor.verify(2, postRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("Un 401 no se reintenta nunca: la clave no mejora esperando")
    void noReintentaAnte401() {
        proveedor.stubFor(post(urlPathEqualTo(ServidorModeloFalso.RUTA))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"invalid api key\"}")));

        assertThrows(CredencialInvalida.class, this::analizar);

        proveedor.verify(1, postRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("Cada llamada deja métrica de latencia y de consumo estimado")
    void dejaMetricas() {
        proveedor.stubFor(post(urlPathEqualTo(ServidorModeloFalso.RUTA))
                .willReturn(okJson(ServidorModeloFalso.respuestaDeChat(
                        ServidorModeloFalso.ANALISIS_VALIDO))));
        analizar();

        String metricas = given().when().get("/q/metrics").then().statusCode(200)
                .extract().asString();

        assertTrue(metricas.contains("llm_peticion_seconds_count"),
                "Falta la métrica de latencia por llamada");
        assertTrue(metricas.contains("caso_de_uso=\"AnalisisDePliego\""),
                "La latencia debe poder desglosarse por caso de uso");
        // El contador de consumo es el que responde «cuánto nos cuesta esto», que es la
        // pregunta que decide si el proyecto sigue.
        assertTrue(metricas.contains("llm_tokens_total"),
                "Falta el contador de consumo estimado");
    }

    @Test
    @DisplayName("Un proveedor o un modelo inventados no crean series temporales nuevas")
    void laCardinalidadEstaAcotada() {
        proveedor.stubFor(post(urlPathEqualTo(ServidorModeloFalso.RUTA))
                .willReturn(okJson(ServidorModeloFalso.respuestaDeChat(
                        ServidorModeloFalso.ANALISIS_VALIDO))));

        // Los dos campos vienen de la petición del usuario y ninguno está acotado por
        // naturaleza. Etiquetar con ellos tal cual convierte una métrica en tantas series
        // como quiera fabricar quien llame: un bucle basta para llenar el almacén.
        for (String inventado : new String[] {"proveedor-fantasma", "otro-mas"}) {
            try {
                modelo.estructurado("sistema", "usuario",
                        new SeleccionDeModelo(inventado, "modelo-que-nadie-ofrece"),
                        AnalisisDePliego.class);
            } catch (RuntimeException esperado) {
                // El registro rechaza el proveedor desconocido; la métrica ya se etiquetó.
            }
        }

        String metricas = given().when().get("/q/metrics").then().statusCode(200)
                .extract().asString();

        assertFalse(metricas.contains("proveedor=\"proveedor-fantasma\""),
                "El nombre de proveedor de la petición llegó a la etiqueta sin filtrar");
        assertFalse(metricas.contains("modelo=\"modelo-que-nadie-ofrece\""),
                "El identificador de modelo llegó a la etiqueta sin filtrar");
        assertTrue(metricas.contains("proveedor=\"otro\""),
                "Lo desconocido debe agruparse bajo una etiqueta única, no desaparecer");
    }

    @Test
    @DisplayName("Un fallo transitorio agotado conserva su mensaje, no se vuelve genérico")
    void elMensajeSobreviveAlReintento() {
        proveedor.stubFor(post(urlPathEqualTo(ServidorModeloFalso.RUTA))
                .willReturn(aResponse().withStatus(429).withBody("{\"error\":\"quota\"}")));

        var error = assertThrows(CuotaAgotada.class, this::analizar);

        // Que el usuario sepa que fue la cuota, y no un «no disponible» opaco, es la
        // diferencia entre esperar cinco minutos y escribir un reporte de fallo. El tipo
        // es lo que lo garantiza: su mapeador es el único que devuelve 429.
        assertTrue(error.getMessage().contains("cuota"), error.getMessage());
        proveedor.verify(2, postRequestedFor(anyUrl()));
    }
}
