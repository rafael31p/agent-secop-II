package co.agentesecop.adapter.in.salud;

import co.agentesecop.adapter.out.llm.PoliticaDeResiliencia;
import co.agentesecop.ia.RegistroProveedores;
import io.smallrye.faulttolerance.api.CircuitBreakerMaintenance;
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Disponibilidad de los modelos: ¿tiene sentido mandarle tráfico de análisis?
 *
 * <h2>El endpoint anterior decía «ok» con la clave revocada</h2>
 *
 * <p>{@code /api/salud} construye su respuesta a partir de qué hay configurado, no de si
 * funciona: devolvía «ok» con el proveedor caído y la clave muerta. Como sonda para un
 * orquestador eso es peor que no tener ninguna. Se conserva por compatibilidad con el pie
 * del frontend, pero la operación se decide aquí.
 *
 * <h2>Por qué el cortacircuitos es el sensor</h2>
 *
 * <p>Llamar al proveedor en cada sondeo costaría dinero cada pocos segundos, para saber
 * algo que el cortacircuitos ya sabe: lleva la cuenta de cómo han ido las llamadas reales.
 * Consultarlo es gratis y refleja el estado que están viendo los usuarios. Es el punto
 * donde la resiliencia y la observabilidad se pagan mutuamente el trabajo.
 *
 * <h2>Una limitación que conviene declarar</h2>
 *
 * <p>El cortacircuitos es uno solo, el del puerto, no uno por proveedor: MicroProfile los
 * asocia a un método, no a un parámetro de la llamada. Así que esta sonda informa del
 * estado agregado. Distinguir por proveedor exigiría construir los cortacircuitos a mano
 * en vez de declararlos, y esa es justo la complejidad que se quiso evitar.
 */
@Readiness
@ApplicationScoped
public class SondaDeModelos implements HealthCheck {

    private final RegistroProveedores registro;
    private final CircuitBreakerMaintenance circuitos;

    @Inject
    public SondaDeModelos(RegistroProveedores registro, CircuitBreakerMaintenance circuitos) {
        this.registro = registro;
        this.circuitos = circuitos;
    }

    @Override
    public HealthCheckResponse call() {
        CircuitBreakerState estado = circuitos.currentState(PoliticaDeResiliencia.CIRCUITO);
        boolean hayProveedor = registro.hayAlgunoConfigurado();

        return HealthCheckResponse.named("modelos-de-lenguaje")
                .withData("configurados", String.join(",", registro.nombresConfigurados()))
                .withData("por-defecto", registro.nombrePorDefecto())
                .withData("cortacircuitos", estado.name().toLowerCase())
                // Semiabierto cuenta como disponible: es el estado en que el circuito está
                // dejando pasar llamadas de prueba, y retirar el tráfico entonces impide
                // precisamente la recuperación que se está intentando medir.
                .status(hayProveedor && estado != CircuitBreakerState.OPEN)
                .build();
    }
}
