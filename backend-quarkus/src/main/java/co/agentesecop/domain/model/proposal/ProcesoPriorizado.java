package co.agentesecop.domain.model.proposal;

import co.agentesecop.domain.shared.Listas;
import java.util.List;

/** Proceso de contratación con su puntaje de encaje, según el modelo. */
public record ProcesoPriorizado(
        String id,
        String objeto,
        String entidad,
        Double valor,
        int puntaje,
        String categoriaTi,
        String justificacion,
        String encajeProveedor,
        List<String> banderas) {

    public ProcesoPriorizado {
        banderas = Listas.copiaOVacia(banderas);
    }
}
