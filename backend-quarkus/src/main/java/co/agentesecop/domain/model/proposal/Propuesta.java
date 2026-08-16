package co.agentesecop.domain.model.proposal;

import co.agentesecop.domain.shared.Listas;
import java.util.List;

/** Borrador de propuesta técnica alineado a un pliego. */
public record Propuesta(
        String titulo,
        String resumenEjecutivo,
        List<SeccionPropuesta> secciones,
        List<String> supuestos,
        List<String> vaciosDeInformacion,
        String markdown) {

    public Propuesta {
        secciones = Listas.copiaOVacia(secciones);
        supuestos = Listas.copiaOVacia(supuestos);
        vaciosDeInformacion = Listas.copiaOVacia(vaciosDeInformacion);
    }

    /**
     * Una propuesta con vacíos declarados no está lista para radicar.
     *
     * <p>El agente marca como vacío lo que el pliego exige y el perfil del oferente no
     * acredita, en lugar de inventarlo. Que existan es señal de honestidad, no de fallo;
     * que se ignoren, sí.
     */
    public boolean requiereCompletarse() {
        return !vaciosDeInformacion.isEmpty();
    }
}
