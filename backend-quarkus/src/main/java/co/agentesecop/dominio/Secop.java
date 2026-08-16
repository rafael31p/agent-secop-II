package co.agentesecop.dominio;

import co.agentesecop.domain.shared.Listas;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Modelos de los procesos de contratación publicados en SECOP II. */
public final class Secop {

    private Secop() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProcesoResumen(
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
            List<String> senalesTi) {

        public ProcesoResumen {
            senalesTi = Listas.copiaOVacia(senalesTi);
        }
    }

    public record RespuestaProcesos(
            int total,
            List<ProcesoResumen> procesos,
            String dataset,
            @Schema(description = "Avisos sobre la consulta: filtros ignorados, "
                    + "degradaciones o errores de la fuente.")
            List<String> advertencias) {

        public RespuestaProcesos {
            procesos = Listas.copiaOVacia(procesos);
            advertencias = Listas.copiaOVacia(advertencias);
        }
    }

    public record RespuestaDocumento(
            String nombreArchivo,
            String tipo,
            int caracteres,
            Integer paginas,
            String texto,
            boolean truncado) {}
}
