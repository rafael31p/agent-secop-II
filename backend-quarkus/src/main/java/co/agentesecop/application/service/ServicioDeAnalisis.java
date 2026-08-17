package co.agentesecop.application.service;

import co.agentesecop.application.port.in.AnalizarPliego;
import co.agentesecop.application.port.out.ModeloDeLenguaje;
import co.agentesecop.application.port.out.RedactorDePrompts;
import co.agentesecop.application.port.out.RedactorDePrompts.Tarea;
import co.agentesecop.domain.model.tender.AnalisisDePliego;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.faulttolerance.Timeout;

/**
 * Analiza un pliego.
 *
 * <p>Doce líneas donde antes había un bloque de {@code StringBuilder} de cuarenta. La
 * diferencia no es estética: el caso de uso vuelve a decir qué hace el negocio, y el cómo
 * se le habla al modelo queda detrás de {@link RedactorDePrompts}, donde se puede probar
 * por separado.
 */
@ApplicationScoped
public class ServicioDeAnalisis implements AnalizarPliego {

    private final ModeloDeLenguaje modelo;
    private final RedactorDePrompts prompts;

    @Inject
    public ServicioDeAnalisis(ModeloDeLenguaje modelo, RedactorDePrompts prompts) {
        this.modelo = modelo;
        this.prompts = prompts;
    }

    /** Presupuesto de la petición completa. Ver {@code ServicioDeValidacion}. */
    @Override
    @Timeout(value = 150_000, unit = ChronoUnit.MILLIS)
    public AnalisisDePliego analizar(ComandoDeAnalisis comando) {
        return modelo.estructurado(
                prompts.sistema(Tarea.ANALISIS),
                prompts.materialDelAnalisis(comando),
                comando.seleccion(),
                AnalisisDePliego.class);
    }
}
