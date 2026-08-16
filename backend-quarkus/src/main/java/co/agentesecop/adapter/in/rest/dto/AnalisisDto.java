package co.agentesecop.adapter.in.rest.dto;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Contrato HTTP del análisis de un pliego.
 *
 * <p>Duplica la forma de {@code domain.model.tender.AnalisisDePliego}, y esa duplicación
 * es deliberada: aquí viven las anotaciones de documentación, y el contrato puede
 * evolucionar sin tocar el negocio. Hace falta porque el contrato está en español y el
 * código va camino del inglés (SPEC-BE-07); sin esta separación, renombrar un campo del
 * dominio rompería el frontend.
 *
 * <p>Los nombres de campo de este lado <strong>no se tocan nunca</strong> sin cambiar
 * también `frontend-next/lib/tipos.ts`. `ContratoDeRespuestaTest` los fija.
 */
public final class AnalisisDto {

    private AnalisisDto() {}

    @Schema(description = "Requisito técnico atómico y verificable extraído del pliego.")
    public record RequisitoTecnicoDto(
            @Schema(description = "Identificador corto, ej. RT-01", examples = "RT-01")
            String id,
            @Schema(description = "Arquitectura, Seguridad, Datos, Integraciones, "
                    + "Infraestructura, Soporte, Metodología, Accesibilidad, "
                    + "Interoperabilidad, Personal, Otros")
            String categoria,
            String requisito,
            @Schema(description = "obligatorio, ponderable, deseable o informativo.")
            String criticidad,
            @Schema(description = "Documento o certificado con el que se acredita.")
            String evidenciaEsperada,
            @Schema(description = "Norma o marco aplicable. Nulo si no aplica.")
            String normaRelacionada,
            @Schema(description = "Fragmento textual del pliego que lo sustenta. "
                    + "Nulo si el requisito es inferido y no textual.")
            String citaPliego) {}

    public record RiesgoDetectadoDto(
            String descripcion,
            @Schema(description = "alto, medio o bajo.") String nivel,
            String impacto,
            String mitigacion,
            @Schema(description = "tecnico, juridico, financiero, operativo, cronograma "
                    + "o competencia.")
            String tipo) {}

    public record RespuestaAnalisis(
            String resumenEjecutivo,
            String objetoNormalizado,
            List<RequisitoTecnicoDto> requisitos,
            List<RiesgoDetectadoDto> riesgos,
            List<String> criteriosEvaluacion,
            List<String> documentosHabilitantes,
            @Schema(description = "Preguntas para radicar como observaciones al "
                    + "proyecto de pliego.")
            List<String> preguntasALaEntidad,
            List<String> alertasNormativas,
            String recomendacion) {}
}
