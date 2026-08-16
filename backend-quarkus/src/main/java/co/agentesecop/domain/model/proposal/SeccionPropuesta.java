package co.agentesecop.domain.model.proposal;

import co.agentesecop.domain.shared.Listas;
import java.util.List;

/** Sección de la propuesta, con los requisitos que cubre. */
public record SeccionPropuesta(
        String titulo,
        String contenido,
        List<String> requisitosCubiertos) {

    public SeccionPropuesta {
        requisitosCubiertos = Listas.copiaOVacia(requisitosCubiertos);
    }
}
