package co.agentesecop.secop;

import io.smallrye.faulttolerance.api.CircuitBreakerName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * La llamada a datos.gov.co, con su propia política de resiliencia.
 *
 * <h2>Por qué es una clase aparte</h2>
 *
 * <p>Dos motivos, y ninguno es estético. El primero es de CDI: los interceptores solo
 * actúan sobre invocaciones que pasan por el proxy del bean, así que anotar un método
 * privado de {@code SecopCliente} no habría hecho absolutamente nada. El segundo es que
 * {@code SecopCliente} captura los fallos a propósito para degradar con advertencias en
 * vez de romper la búsqueda; con la política dentro, no habría excepción que reintentar.
 * Poniéndola debajo, cada uno hace lo suyo: aquí se reintenta, allí se degrada.
 *
 * <h2>Por qué es más agresiva que la del modelo</h2>
 *
 * <p>Esta fuente es gratuita, rápida y de solo lectura: reintentar cuesta medio segundo y
 * no cuesta dinero. La política del modelo es conservadora porque cada intento se factura.
 *
 * <p>Y sobre todo, los mamparos son <b>separados</b> —30 aquí, 12 en el modelo—. Eso es lo
 * que impide que un incidente del proveedor de IA deje sin búsqueda a quien solo quería
 * listar procesos. Compartir pool era el defecto: el caso lento ahogaba al rápido.
 */
@ApplicationScoped
public class ConsultaResiliente {

    /** Nombre del cortacircuitos, para que la sonda de salud consulte su estado. */
    public static final String CIRCUITO = "fuente-secop";

    private final SecopApi api;

    @Inject
    public ConsultaResiliente(@RestClient SecopApi api) {
        this.api = api;
    }

    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    @Retry(maxRetries = 2,
            delay = 500, delayUnit = ChronoUnit.MILLIS,
            jitter = 200, jitterDelayUnit = ChronoUnit.MILLIS)
    @CircuitBreaker(requestVolumeThreshold = 10,
            failureRatio = 0.6,
            delay = 15_000, delayUnit = ChronoUnit.MILLIS,
            successThreshold = 2)
    @CircuitBreakerName(CIRCUITO)
    @Bulkhead(30)
    public List<Map<String, Object>> consultar(
            String dataset, int limite, int offset, String orden, String where, String token) {
        return api.consultar(dataset, limite, offset, orden, where, token);
    }
}
