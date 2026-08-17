package co.agentesecop.adapter.out.llm;

import co.agentesecop.adapter.out.llm.error.ProveedorNoDisponible;
import co.agentesecop.application.port.out.ModeloDeLenguaje;
import co.agentesecop.application.port.out.ModeloDeLenguaje.Turno;
import co.agentesecop.application.port.out.SeleccionDeModelo;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Cuántas conversaciones pueden estar abiertas a la vez.
 *
 * <h2>El hueco que cierra</h2>
 *
 * <p>El chat no tenía <b>ninguna</b> cota de concurrencia, y el razonamiento que decía que
 * sí la tenía era incorrecto en sus dos mitades:
 *
 * <ul>
 *   <li>«Lo protege el mamparo de las llamadas estructuradas». No: los interceptores
 *       cuentan permisos <b>por método</b>, y el flujo no pasa por
 *       {@code PoliticaDeResiliencia.estructurado}. No consumía ni uno.
 *   <li>«Lo protege el límite por clave». Ese es un límite de <b>caudal</b>, no de
 *       concurrencia: cien mensajes por hora no impiden abrir las cien a la vez.
 * </ul>
 *
 * <p>Lo que sí era correcto de aquel razonamiento, y se conserva: no se reintenta ni se
 * aplica cortacircuitos a una respuesta que ya empezó a emitirse al navegador. Lo que
 * faltaba no era {@code @Retry}, era una cota.
 *
 * <h2>Por qué un semáforo y no {@code @Bulkhead}</h2>
 *
 * <p>Porque {@code @Bulkhead} sobre un método que devuelve {@code Multi} libera el permiso
 * cuando el método <b>retorna</b>, y un método que construye un flujo retorna de inmediato.
 * Contaría aperturas por segundo, no conversaciones vivas, que es justo lo contrario de lo
 * que hace falta. El permiso tiene que atarse al ciclo de vida del flujo.
 *
 * <p>{@code onTermination} cubre los tres finales —completado, error y <b>cancelación</b>—,
 * y el tercero es el que importa: sin él, cerrar la pestaña dejaría el permiso colgado para
 * siempre y el servicio se quedaría sin conversaciones disponibles al cabo de unas cuantas.
 */
@ApplicationScoped
public class PoliticaDeStreaming {

    private final ModeloDeLenguaje delegado;
    private final MeterRegistry metricas;
    private final int maximoConversaciones;

    /** Cuánto puede callar el modelo antes de darlo por perdido. */
    private static final Duration SILENCIO_MAXIMO = Duration.ofSeconds(60);

    private Semaphore permisos;

    @Inject
    public PoliticaDeStreaming(
            @Medido ModeloDeLenguaje delegado,
            MeterRegistry metricas,
            @ConfigProperty(name = "agente.ia.maximo-conversaciones-simultaneas",
                    defaultValue = "8") int maximoConversaciones) {
        this.delegado = delegado;
        this.metricas = metricas;
        this.maximoConversaciones = maximoConversaciones;
    }

    @PostConstruct
    void prepararPermisos() {
        this.permisos = new Semaphore(maximoConversaciones);
        // Sin esta métrica, subir el valor sería adivinar. Es la misma razón por la que el
        // mamparo de las llamadas estructuradas se declaró conservador.
        metricas.gauge("llm.conversaciones.disponibles", permisos, Semaphore::availablePermits);
    }

    public Multi<String> flujo(String sistema, List<Turno> turnos, SeleccionDeModelo seleccion) {
        return Multi.createFrom().deferred(() -> {
            if (!permisos.tryAcquire()) {
                // El mensaje no culpa al proveedor: el límite es nuestro y es temporal.
                return Multi.createFrom().failure(new ProveedorNoDisponible(
                        "Hay demasiadas conversaciones abiertas en este momento. "
                                + "Reintenta en unos segundos."));
            }
            // Un solo release por flujo, aunque `onTermination` pudiera invocarse más de
            // una vez: devolver de más agrandaría el semáforo en silencio.
            AtomicBoolean devuelto = new AtomicBoolean();
            return delegado.flujo(sistema, turnos, seleccion)
                    // El presupuesto del chat se mide como INACTIVIDAD y no como total: en
                    // una conversación, una respuesta larga es legítima y un minuto sin un
                    // solo fragmento no lo es. Sin esto, quien espera se queda mirando un
                    // cursor que ya no va a moverse, y el permiso sigue ocupado.
                    .ifNoItem().after(SILENCIO_MAXIMO).failWith(() -> new ProveedorNoDisponible(
                            "El modelo dejó de responder a mitad de la conversación. "
                                    + "Reintenta la consulta."))
                    .onTermination().invoke(() -> {
                        if (devuelto.compareAndSet(false, true)) {
                            permisos.release();
                        }
                    });
        });
    }

    /** Para las pruebas: cuántas conversaciones quedan libres. */
    public int disponibles() {
        return permisos.availablePermits();
    }
}
