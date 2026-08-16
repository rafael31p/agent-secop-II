package co.agentesecop.adapter.in.rest.dto;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Contrato HTTP de la generación y la validación de propuestas. Ver {@link AnalisisDto}. */
public final class PropuestasDto {

    private PropuestasDto() {}

    public record SeccionPropuestaDto(
            String titulo,
            String contenido,
            @Schema(description = "IDs de requisito que esta sección cubre.")
            List<String> requisitosCubiertos) {}

    public record RespuestaPropuesta(
            String titulo,
            String resumenEjecutivo,
            List<SeccionPropuestaDto> secciones,
            @Schema(description = "Lo que se asumió por ausencia de información en el pliego.")
            List<String> supuestos,
            @Schema(description = "Exigencias del pliego que el perfil del oferente no "
                    + "acredita. El agente las declara en lugar de inventarlas.")
            List<String> vaciosDeInformacion,
            @Schema(description = "Propuesta completa en Markdown, lista para exportar.")
            String markdown) {}

    public record ItemCumplimientoDto(
            String requisitoId,
            String requisito,
            String criticidad,
            @Schema(description = "cumple, cumple_parcial, no_cumple o no_evaluable.")
            String estado,
            @Schema(description = "Cita corta de la propuesta que lo sustenta.")
            String evidenciaEnPropuesta,
            String brecha,
            String accionCorrectiva) {}

    public record RespuestaValidacion(
            @Schema(description = "0-100, ponderando obligatorios por encima de deseables.")
            int puntajeCumplimiento,
            @Schema(description = "apta, apta_con_ajustes, riesgo_de_rechazo o no_apta.")
            String veredicto,
            String resumen,
            List<ItemCumplimientoDto> matriz,
            @Schema(description = "Requisitos obligatorios incumplidos, con la nota de si "
                    + "serían subsanables bajo el régimen colombiano.")
            List<String> causalesDeRechazo,
            List<String> mejorasPrioritarias) {}

    public record ProcesoPriorizadoDto(
            String id,
            String objeto,
            String entidad,
            Double valor,
            int puntaje,
            @Schema(description = "Desarrollo de software, Infraestructura y nube, "
                    + "Ciberseguridad, Datos y analítica, Licenciamiento, Soporte y mesa "
                    + "de ayuda, Consultoría TI, Conectividad, Equipos y hardware, "
                    + "Transformación digital, No es TI")
            String categoriaTi,
            String justificacion,
            String encajeProveedor,
            List<String> banderas) {}

    public record RespuestaRelevancia(
            List<ProcesoPriorizadoDto> priorizados, String resumen) {}
}
