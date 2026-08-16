package co.agentesecop.application.service;

import co.agentesecop.application.port.in.GenerarPropuesta;
import co.agentesecop.application.port.out.ModeloDeLenguaje;
import co.agentesecop.application.port.out.RedactorDePrompts;
import co.agentesecop.application.port.out.RedactorDePrompts.Tarea;
import co.agentesecop.domain.model.proposal.Propuesta;
import co.agentesecop.domain.shared.PeticionInvalida;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Redacta el borrador de propuesta técnica. */
@ApplicationScoped
public class ServicioDePropuestas implements GenerarPropuesta {

    private final ModeloDeLenguaje modelo;
    private final RedactorDePrompts prompts;

    @Inject
    public ServicioDePropuestas(ModeloDeLenguaje modelo, RedactorDePrompts prompts) {
        this.modelo = modelo;
        this.prompts = prompts;
    }

    @Override
    public Propuesta generar(ComandoDePropuesta comando) {
        if (comando.sinReferencia()) {
            throw new PeticionInvalida(
                    "Envía requisitos estructurados o el texto del pliego; sin al menos uno "
                            + "de los dos la propuesta no puede alinearse al proceso.");
        }
        return modelo.estructurado(
                prompts.sistema(Tarea.PROPUESTA),
                prompts.insumosDeLaPropuesta(comando),
                comando.seleccion(),
                Propuesta.class);
    }
}
