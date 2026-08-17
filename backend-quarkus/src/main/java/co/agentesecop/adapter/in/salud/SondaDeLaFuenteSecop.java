package co.agentesecop.adapter.in.salud;

import co.agentesecop.secop.EstadoDeLaFuente;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Disponibilidad de la fuente de datos de SECOP II, según cómo le fue a la última consulta.
 *
 * <p>Un matiz de producto que la sonda no debe borrar: si los modelos están caídos pero la
 * fuente responde, el servicio sigue siendo parcialmente útil —se puede buscar, no
 * analizar—, y al revés. Por eso son dos sondas separadas y no una sola: quien lea
 * {@code /q/health/ready} necesita saber cuál de las dos mitades falta.
 */
@Readiness
@ApplicationScoped
public class SondaDeLaFuenteSecop implements HealthCheck {

    private final EstadoDeLaFuente estado;

    @Inject
    public SondaDeLaFuenteSecop(EstadoDeLaFuente estado) {
        this.estado = estado;
    }

    @Override
    public HealthCheckResponse call() {
        var constructor = HealthCheckResponse.named("fuente-secop");
        estado.ultima().ifPresentOrElse(
                ultima -> constructor
                        .withData("ultima-consulta", ultima.momento().toString())
                        .withData("resultado", ultima.exito() ? "exito" : ultima.motivo()),
                () -> constructor.withData("resultado", "sin consultas todavía"));
        return constructor.status(estado.disponible()).build();
    }
}
