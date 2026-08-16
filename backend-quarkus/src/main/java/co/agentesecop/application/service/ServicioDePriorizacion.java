package co.agentesecop.application.service;

import co.agentesecop.application.port.in.PriorizarProcesos;
import co.agentesecop.application.port.out.ModeloDeLenguaje;
import co.agentesecop.application.port.out.RedactorDePrompts;
import co.agentesecop.application.port.out.RedactorDePrompts.Tarea;
import co.agentesecop.domain.model.proposal.Priorizacion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Clasifica y ordena procesos por su encaje con el oferente. */
@ApplicationScoped
public class ServicioDePriorizacion implements PriorizarProcesos {

    private final ModeloDeLenguaje modelo;
    private final RedactorDePrompts prompts;

    @Inject
    public ServicioDePriorizacion(ModeloDeLenguaje modelo, RedactorDePrompts prompts) {
        this.modelo = modelo;
        this.prompts = prompts;
    }

    @Override
    public Priorizacion priorizar(ComandoDePriorizacion comando) {
        return modelo.estructurado(
                prompts.sistema(Tarea.PRIORIZACION),
                prompts.procesosAClasificar(comando),
                comando.seleccion(),
                Priorizacion.class);
    }
}
