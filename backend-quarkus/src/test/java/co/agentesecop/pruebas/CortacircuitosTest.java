package co.agentesecop.pruebas;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.agentesecop.adapter.out.llm.PoliticaDeResiliencia;
import co.agentesecop.application.port.out.ModeloDeLenguaje;
import co.agentesecop.application.port.out.SeleccionDeModelo;
import co.agentesecop.domain.model.tender.AnalisisDePliego;
import co.agentesecop.adapter.out.llm.error.ProveedorNoDisponible;
import co.agentesecop.adapter.out.llm.error.ServicioDelProveedorCaido;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.faulttolerance.api.CircuitBreakerMaintenance;
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El sistema aprende: deja de llamar a un proveedor que no responde, y vuelve cuando sí.
 *
 * <p>Es la pieza que faltaba por completo. Sin cortacircuitos, con el proveedor caído la
 * petición número mil pagaba exactamente los mismos tres intentos que la primera: cada
 * usuario esperaba el ciclo entero para recibir el mismo error, y el proveedor recibía una
 * avalancha justo cuando peor estaba.
 *
 * <p>El umbral, la proporción y el reposo se bajan aquí a valores de prueba. Que se pueda
 * es la demostración del criterio de salida de SPEC-BE-02: son configuración, no código.
 */
@QuarkusTest
@TestProfile(CortacircuitosTest.PerfilDeCircuitoSensible.class)
// Restringido a esta clase: sin esto, la clave falsa y la URL del
// proveedor simulado se aplican a TODA la suite —es el comportamiento por
// defecto de @QuarkusTestResource— y contaminan pruebas que no lo piden.
@QuarkusTestResource(value = ServidorModeloFalso.class, restrictToAnnotatedClass = true)
class CortacircuitosTest {

    /** Reposo del circuito en esta prueba, en milisegundos. */
    private static final long REPOSO = 2_000;

    public static class PerfilDeCircuitoSensible implements QuarkusTestProfile {
        private static final String POLITICA =
                "co.agentesecop.adapter.out.llm.PoliticaDeResiliencia/estructurado/";

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    // Sin reintentos, para que cada llamada cuente una vez y el conteo sea
                    // legible: aquí se mide el circuito, no el reintento.
                    POLITICA + "Retry/maxRetries", "0",
                    POLITICA + "CircuitBreaker/requestVolumeThreshold", "4",
                    POLITICA + "CircuitBreaker/failureRatio", "0.5",
                    POLITICA + "CircuitBreaker/delay", String.valueOf(REPOSO),
                    POLITICA + "CircuitBreaker/successThreshold", "1");
        }
    }

    private static final int VENTANA = 4;

    @InyectarModeloFalso
    WireMockServer proveedor;

    @Inject
    ModeloDeLenguaje modelo;

    @Inject
    CircuitBreakerMaintenance circuitos;

    /**
     * El circuito es estado de la aplicación y sobrevive entre pruebas. Sin este reinicio,
     * la segunda prueba heredaría el circuito abierto por la primera y pasaría —o
     * fallaría— por motivos que no tienen nada que ver con lo que afirma.
     */
    @BeforeEach
    void reiniciar() {
        proveedor.resetAll();
        circuitos.reset(PoliticaDeResiliencia.CIRCUITO);
    }

    private AnalisisDePliego analizar() {
        return modelo.estructurado(
                "Eres un analista de pliegos.",
                "Analiza este anexo técnico.",
                SeleccionDeModelo.porDefecto(),
                AnalisisDePliego.class);
    }

    private void proveedorCaido() {
        proveedor.stubFor(post(urlPathEqualTo(ServidorModeloFalso.RUTA))
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":\"unavailable\"}")));
    }

    private void proveedorSano() {
        proveedor.stubFor(post(urlPathEqualTo(ServidorModeloFalso.RUTA))
                .willReturn(okJson(ServidorModeloFalso.respuestaDeChat(
                        ServidorModeloFalso.ANALISIS_VALIDO))));
    }

    /** Llena la ventana del circuito con fallos. */
    private void agotarLaPaciencia() {
        for (int i = 0; i < VENTANA; i++) {
            assertThrows(ServicioDelProveedorCaido.class, this::analizar);
        }
    }

    @Test
    @DisplayName("Superado el umbral, el circuito abre y deja de llamar al proveedor")
    void abreYDejaDeLlamar() {
        proveedorCaido();
        agotarLaPaciencia();

        // La llamada siguiente ya no debería salir a la red.
        assertThrows(ProveedorNoDisponible.class, this::analizar);

        proveedor.verify(VENTANA, postRequestedFor(anyUrl()));
        assertTrue(circuitos.currentState(PoliticaDeResiliencia.CIRCUITO)
                        == CircuitBreakerState.OPEN,
                "El circuito debería estar abierto tras llenar la ventana de fallos");
    }

    @Test
    @DisplayName("El mensaje del repliegue le dice al usuario que puede cambiar de proveedor")
    void elRepliegueEsAccionable() {
        proveedorCaido();
        agotarLaPaciencia();

        var error = assertThrows(ProveedorNoDisponible.class, this::analizar);

        // Hay cinco proveedores configurables: esta frase es lo que convierte una caída
        // total en una degradación. Un «error interno» dejaría al usuario sin salida.
        assertTrue(error.getMessage().contains("otro proveedor"),
                "El repliegue debe sugerir la salida que existe: " + error.getMessage());
        assertTrue(error.getMessage().contains("openai"),
                "Y nombrar al proveedor que falló: " + error.getMessage());
    }

    @Test
    @DisplayName("Pasado el reposo, el circuito se recupera solo")
    void seRecuperaSolo() throws InterruptedException {
        proveedorCaido();
        agotarLaPaciencia();
        assertThrows(ProveedorNoDisponible.class, this::analizar);

        proveedorSano();
        // Abrirse no basta: un circuito que no vuelve a cerrarse deja el servicio caído
        // para siempre después de un incidente de un minuto.
        Thread.sleep(REPOSO + 500);

        assertNotNull(analizar().resumenEjecutivo());
        assertTrue(circuitos.currentState(PoliticaDeResiliencia.CIRCUITO)
                        == CircuitBreakerState.CLOSED,
                "Tras una llamada correcta el circuito debería haberse cerrado");
    }
}
