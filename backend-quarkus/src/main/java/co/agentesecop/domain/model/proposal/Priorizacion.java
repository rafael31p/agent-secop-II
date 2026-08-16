package co.agentesecop.domain.model.proposal;

import co.agentesecop.domain.shared.Listas;
import java.util.List;

/** Lista de procesos ordenada por encaje, con el porqué en una frase. */
public record Priorizacion(List<ProcesoPriorizado> priorizados, String resumen) {

    public Priorizacion {
        priorizados = Listas.copiaOVacia(priorizados);
    }
}
