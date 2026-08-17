package co.agentesecop.pruebas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import co.agentesecop.config.ConfiguracionIA;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Los timeouts van monótonos hacia adentro, y eso es un invariante, no una convención.
 *
 * <h2>Qué pasa si se rompe</h2>
 *
 * <p>El cliente HTTP de LangChain4j esperaba 300 s mientras la política declaraba 120 s.
 * {@code @Timeout} sobre un método síncrono trabaja con un vigilante que <b>interrumpe</b>
 * el hilo, y una lectura de socket bloqueante no responde a {@code interrupt()} en la
 * mayoría de los clientes HTTP. Así que a los 120 s el llamante recibía
 * {@code TimeoutException} y el hilo seguía atrapado hasta que el cliente se rindiera tres
 * minutos después.
 *
 * <p>Las dos salidas posibles son malas: o el permiso del mamparo se libera con la
 * excepción —y entonces la concurrencia real supera los doce declarados, con hilos zombis
 * fuera de todo control— o se libera cuando la invocación termina de verdad, y el mamparo
 * pasa tres minutos lleno de llamadas fantasma rechazando usuarios con el proveedor
 * perfectamente sano.
 *
 * <p>De ahí el invariante: <b>una capa interna nunca espera más que la que la envuelve</b>.
 * Es fácil de romper sin darse cuenta, porque los dos valores viven en sitios distintos y
 * en unidades distintas; por eso se comprueba en cada compilación en vez de confiarlo a un
 * comentario.
 */
@QuarkusTest
class TimeoutsMonotonosTest {

    private static final String POLITICA =
            "co.agentesecop.adapter.out.llm.PoliticaDeResiliencia/estructurado/Timeout/value";

    @Inject
    ConfiguracionIA config;

    @ConfigProperty(name = POLITICA)
    long timeoutPorIntentoMillis;

    @ConfigProperty(name = "co.agentesecop.application.service."
            + "ServicioDeAnalisis/analizar/Timeout/value")
    long presupuestoDelAnalisisMillis;

    @Test
    @DisplayName("El cliente HTTP se rinde antes que la política por intento")
    void elClienteAntesQueLaPolitica() {
        long clienteMillis = config.timeoutSegundos() * 1000L;

        assertTrue(clienteMillis < timeoutPorIntentoMillis,
                ("El cliente HTTP espera %d ms y la política corta a los %d ms. Al revés, "
                        + "quedan hilos atrapados en una lectura de socket fuera del "
                        + "control del mamparo.")
                        .formatted(clienteMillis, timeoutPorIntentoMillis));
    }

    @Test
    @DisplayName("La política por intento corta antes que el presupuesto del caso de uso")
    void laPoliticaAntesQueElCasoDeUso() {
        assertTrue(timeoutPorIntentoMillis < presupuestoDelAnalisisMillis,
                ("El intento dura %d ms y el caso de uso corta a los %d ms: el presupuesto "
                        + "no llegaría a acotar ni un solo intento completo.")
                        .formatted(timeoutPorIntentoMillis, presupuestoDelAnalisisMillis));
    }
}
