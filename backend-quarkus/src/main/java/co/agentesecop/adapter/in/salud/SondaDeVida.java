package co.agentesecop.adapter.in.salud;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Vivacidad: ¿hay que reiniciar el proceso?
 *
 * <p>No comprueba nada externo, y eso es deliberado. Vivacidad y disponibilidad responden
 * preguntas opuestas: si esta sonda mirase al proveedor de modelos, una caída de Gemini
 * provocaría que el orquestador reiniciara un proceso perfectamente sano, una y otra vez,
 * sin arreglar nada. Lo que dice es que el servidor HTTP acepta peticiones y las atiende;
 * cualquier otra cosa es materia de {@link SondaDeModelos} o {@link SondaDeLaFuenteSecop}.
 */
@Liveness
@ApplicationScoped
public class SondaDeVida implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.up("agente-vivo");
    }
}
