package co.agentesecop.adapter.in.rest.mapper;

import co.agentesecop.adapter.in.rest.dto.AnalisisDto.RequisitoTecnicoDto;
import co.agentesecop.adapter.in.rest.dto.AnalisisDto.RespuestaAnalisis;
import co.agentesecop.adapter.in.rest.dto.AnalisisDto.RiesgoDetectadoDto;
import co.agentesecop.domain.model.tender.AnalisisDePliego;
import co.agentesecop.domain.model.tender.RequisitoTecnico;
import co.agentesecop.domain.model.tender.RiesgoDetectado;
import co.agentesecop.domain.shared.CodedEnum;
import java.util.List;

/**
 * Traduce el análisis del dominio al contrato HTTP.
 *
 * <p>Se escribe a mano. MapStruct no compensa para siete tipos, y un mapeador generado no
 * dejaría ver lo único que aquí importa de verdad: que las enumeraciones salen por su
 * <em>código</em> —{@code "obligatorio"}, no {@code "OBLIGATORIO"}—, que es lo que lee el
 * frontend.
 */
public final class AnalisisMapper {

    private AnalisisMapper() {}

    public static RespuestaAnalisis aDto(AnalisisDePliego analisis) {
        return new RespuestaAnalisis(
                analisis.resumenEjecutivo(),
                analisis.objetoNormalizado(),
                analisis.requisitos().stream().map(AnalisisMapper::aDto).toList(),
                analisis.riesgos().stream().map(AnalisisMapper::aDto).toList(),
                analisis.criteriosEvaluacion(),
                analisis.documentosHabilitantes(),
                analisis.preguntasALaEntidad(),
                analisis.alertasNormativas(),
                analisis.recomendacion());
    }

    public static RequisitoTecnicoDto aDto(RequisitoTecnico requisito) {
        return new RequisitoTecnicoDto(
                requisito.id(),
                requisito.categoria(),
                requisito.requisito(),
                codigo(requisito.criticidad()),
                requisito.evidenciaEsperada(),
                requisito.normaRelacionada(),
                requisito.citaPliego());
    }

    public static List<RequisitoTecnicoDto> aDtoRequisitos(List<RequisitoTecnico> requisitos) {
        return requisitos.stream().map(AnalisisMapper::aDto).toList();
    }

    private static RiesgoDetectadoDto aDto(RiesgoDetectado riesgo) {
        return new RiesgoDetectadoDto(
                riesgo.descripcion(),
                codigo(riesgo.nivel()),
                riesgo.impacto(),
                riesgo.mitigacion(),
                codigo(riesgo.tipo()));
    }

    /** Un valor ausente sigue saliendo como {@code null}, no como cadena vacía. */
    static String codigo(CodedEnum valor) {
        return valor == null ? null : valor.code();
    }
}
