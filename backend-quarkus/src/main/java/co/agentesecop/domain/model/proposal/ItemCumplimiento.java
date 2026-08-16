package co.agentesecop.domain.model.proposal;

import co.agentesecop.domain.model.tender.Criticidad;

/** Contraste de un requisito del pliego contra lo que dice la propuesta. */
public record ItemCumplimiento(
        String requisitoId,
        String requisito,
        Criticidad criticidad,
        EstadoCumplimiento estado,
        String evidenciaEnPropuesta,
        String brecha,
        String accionCorrectiva) {

    /**
     * Regla de negocio: un obligatorio incumplido es lo que deja fuera una oferta.
     *
     * <p>Un ponderable incumplido resta puntaje; un obligatorio en {@code no_cumple} es
     * causal de rechazo. La distinción es la que separa «perder puntos» de «quedar fuera».
     */
    public boolean esCausalDeRechazo() {
        return criticidad != null
                && criticidad.puedeCausarRechazo()
                && estado == EstadoCumplimiento.NO_CUMPLE;
    }
}
