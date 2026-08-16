package co.agentesecop.domain.model.proposal;

import co.agentesecop.domain.shared.Listas;
import java.util.List;

/** Resultado de validar una propuesta contra los requisitos de un pliego. */
public record InformeDeCumplimiento(
        int puntajeCumplimiento,
        Veredicto veredicto,
        String resumen,
        List<ItemCumplimiento> matriz,
        List<String> causalesDeRechazo,
        List<String> mejorasPrioritarias) {

    public InformeDeCumplimiento {
        matriz = Listas.copiaOVacia(matriz);
        causalesDeRechazo = Listas.copiaOVacia(causalesDeRechazo);
        mejorasPrioritarias = Listas.copiaOVacia(mejorasPrioritarias);
    }

    /** Los ítems que por sí solos dejarían la oferta fuera. */
    public List<ItemCumplimiento> incumplimientosGraves() {
        return matriz.stream().filter(ItemCumplimiento::esCausalDeRechazo).toList();
    }
}
