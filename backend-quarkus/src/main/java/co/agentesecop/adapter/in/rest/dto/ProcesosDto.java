package co.agentesecop.adapter.in.rest.dto;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Contrato HTTP de los procesos de SECOP II. Ver {@link AnalisisDto}. */
public final class ProcesosDto {

    private ProcesosDto() {}

    public record ProcesoResumenDto(
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
            @Schema(description = "Nacional o Territorial.") String ordenEntidad,
            String adjudicado,
            @Schema(description = "Precio base del proceso, en pesos colombianos.")
            Double valor,
            String fechaPublicacion,
            @Schema(description = "Fecha de última publicación. El conjunto de datos "
                    + "abierto no expone la fecha de cierre de recepción de ofertas; "
                    + "consúltala en el enlace del proceso.")
            String fechaUltimaPublicacion,
            String url,
            String codigoUnspsc,
            String duracion,
            @Schema(description = "0-100. Heurística local por palabras clave, no una "
                    + "clasificación del modelo.")
            Integer scoreTi,
            List<String> senalesTi) {}

    public record RespuestaProcesos(
            int total,
            List<ProcesoResumenDto> procesos,
            String dataset,
            @Schema(description = "Avisos sobre la consulta: filtros ignorados, "
                    + "degradaciones o errores de la fuente.")
            List<String> advertencias) {}

    public record RespuestaDocumento(
            String nombreArchivo,
            String tipo,
            int caracteres,
            Integer paginas,
            String texto,
            boolean truncado) {}
}
