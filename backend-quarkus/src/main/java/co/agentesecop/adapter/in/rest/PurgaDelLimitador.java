package co.agentesecop.adapter.in.rest;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Cumple lo que el Javadoc de {@code LimitadorDeTasa.limpiarVencidos()} ya prometía.
 *
 * <p>El método era público y no lo llamaba nadie en todo el repositorio. Su documentación
 * decía «sin esto, un cliente puntual queda para siempre», y esa intención no se cumplía.
 * No era una fuga —el crecimiento está acotado por (claves × operaciones)— pero sí código
 * muerto que prometía algo que no ocurría, que es peor que no prometerlo: quien lee el
 * Javadoc deja de buscar el problema.
 *
 * <p>{@code SKIP} evita que dos purgas se solapen si una se alarga. Media hora es holgado
 * para una ventana de una hora: lo que se limpia ya no cuenta para nada.
 */
@ApplicationScoped
public class PurgaDelLimitador {

    private final LimitadorDeTasa limitador;

    @Inject
    public PurgaDelLimitador(LimitadorDeTasa limitador) {
        this.limitador = limitador;
    }

    @Scheduled(every = "30m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void purgarVentanasVencidas() {
        limitador.limpiarVencidos();
    }
}
