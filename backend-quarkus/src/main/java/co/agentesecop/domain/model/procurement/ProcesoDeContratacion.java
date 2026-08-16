package co.agentesecop.domain.model.procurement;

import co.agentesecop.domain.shared.Listas;
import java.util.List;

/**
 * Proceso de contratación publicado en SECOP II.
 *
 * <p>Dos campos merecen una advertencia, porque se han malinterpretado antes:
 *
 * <ul>
 *   <li>{@code fechaUltimaPublicacion} <strong>no</strong> es la fecha de cierre de
 *       recepción de ofertas. El conjunto de datos abierto no expone esa fecha; hay que
 *       consultarla en el enlace del proceso.
 *   <li>{@code scoreTi} es una heurística local por palabras clave, no una clasificación
 *       del modelo. Sirve para ordenar y descartar ruido sin gastar tokens.
 * </ul>
 */
public record ProcesoDeContratacion(
        String id,
        String numeroProceso,
        String entidad,
        String nitEntidad,
        String departamento,
        String ciudad,
        String objeto,
        String modalidad,
        String estado,
        String tipoContrato,
        String ordenEntidad,
        String adjudicado,
        Double valor,
        String fechaPublicacion,
        String fechaUltimaPublicacion,
        String url,
        String codigoUnspsc,
        String duracion,
        Integer scoreTi,
        List<String> senalesTi) {

    public ProcesoDeContratacion {
        senalesTi = Listas.copiaOVacia(senalesTi);
    }
}
