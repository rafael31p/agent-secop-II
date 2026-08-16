package co.agentesecop.domain.model.tender;

import co.agentesecop.domain.shared.Listas;
import java.util.List;

/**
 * Resultado de analizar un pliego: qué exige, qué arriesga y qué conviene hacer.
 *
 * <p>Antes se llamaba {@code RespuestaAnalisis} y vivía en {@code dominio}. El nombre
 * anterior describía su papel en el protocolo —ser la respuesta de un endpoint— y no lo
 * que es; ese papel lo cumple ahora {@code AnalisisDto}.
 */
public record AnalisisDePliego(
        String resumenEjecutivo,
        String objetoNormalizado,
        List<RequisitoTecnico> requisitos,
        List<RiesgoDetectado> riesgos,
        List<String> criteriosEvaluacion,
        List<String> documentosHabilitantes,
        List<String> preguntasALaEntidad,
        List<String> alertasNormativas,
        String recomendacion) {

    /** Normaliza las listas nulas para que nadie aguas abajo reciba {@code null}. */
    public AnalisisDePliego {
        requisitos = Listas.copiaOVacia(requisitos);
        riesgos = Listas.copiaOVacia(riesgos);
        criteriosEvaluacion = Listas.copiaOVacia(criteriosEvaluacion);
        documentosHabilitantes = Listas.copiaOVacia(documentosHabilitantes);
        preguntasALaEntidad = Listas.copiaOVacia(preguntasALaEntidad);
        alertasNormativas = Listas.copiaOVacia(alertasNormativas);
    }

    /** Los requisitos cuyo incumplimiento deja la oferta fuera. */
    public List<RequisitoTecnico> obligatorios() {
        return requisitos.stream().filter(RequisitoTecnico::puedeCausarRechazo).toList();
    }
}
