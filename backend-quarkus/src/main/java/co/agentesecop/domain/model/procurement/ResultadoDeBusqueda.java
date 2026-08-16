package co.agentesecop.domain.model.procurement;

import co.agentesecop.domain.shared.Listas;
import java.util.List;

/**
 * Procesos encontrados, con los avisos de lo que salió a medias.
 *
 * <p>Las advertencias no son decoración: la fuente degrada en silencio —un filtro que se
 * ignora, una consulta que falla y se reintenta sin filtros— y sin decirlo el usuario
 * creería estar viendo lo que pidió.
 */
public record ResultadoDeBusqueda(
        int total,
        List<ProcesoDeContratacion> procesos,
        String dataset,
        List<String> advertencias) {

    public ResultadoDeBusqueda {
        procesos = Listas.copiaOVacia(procesos);
        advertencias = Listas.copiaOVacia(advertencias);
    }
}
