package co.agentesecop.dominio;

import co.agentesecop.dominio.Analisis.RequisitoTecnico;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Cuerpos de petición de la API.
 *
 * <p>Todas las solicitudes que invocan al modelo llevan {@code proveedor} y
 * {@code modelo} opcionales: si vienen nulos se usa la configuración por defecto del
 * servidor. Eso permite que el usuario elija en tiempo de ejecución sin obligarlo.
 */
public final class Solicitudes {

    private Solicitudes() {}

    /** Campos de selección de proveedor y modelo, compartidos por varias solicitudes. */
    public interface ConSeleccionIA {
        @Schema(description = "gemini, openai, anthropic, deepseek u ollama. "
                + "Nulo = el proveedor por defecto del servidor.")
        String proveedor();

        @Schema(description = "Identificador del modelo. Nulo = el modelo por defecto "
                + "del proveedor elegido.")
        String modelo();
    }

    public record FiltroProcesos(
            @Schema(description = "Búsqueda de texto libre sobre el objeto del proceso.",
                    examples = "desarrollo de software")
            String texto,
            String entidad,
            @Schema(examples = "Distrito Capital de Bogotá")
            String departamento,
            String modalidad,
            String estado,
            @PositiveOrZero Double valorMin,
            @PositiveOrZero Double valorMax,
            @Schema(description = "Fecha ISO, ej. 2026-01-01") String fechaDesde,
            @Schema(description = "Fecha ISO, ej. 2026-12-31") String fechaHasta,
            @Schema(description = "Aplica el filtro heurístico por palabras clave de TI.")
            Boolean soloTi,
            @Min(1) @Max(500) Integer limite,
            @Min(0) Integer offset) {

        /** Valores por defecto cuando el cliente omite campos. */
        public FiltroProcesos {
            soloTi = soloTi != null && soloTi;
            limite = limite == null ? 50 : limite;
            offset = offset == null ? 0 : offset;
        }

        public boolean rangoDeValorInvertido() {
            return valorMin != null && valorMax != null && valorMin > valorMax;
        }
    }

    public record SolicitudAnalisis(
            @NotBlank @Size(min = 40, message = "El pliego debe tener al menos 40 caracteres.")
            String textoPliego,
            String objetoContractual,
            String entidad,
            String modalidad,
            Double valorEstimado,
            @Schema(description = "Capacidades y limitaciones del oferente, para "
                    + "contextualizar los riesgos.")
            String contextoProveedor,
            String proveedor,
            String modelo) implements ConSeleccionIA {}

    public record SolicitudPropuesta(
            @NotBlank String objetoContractual,
            List<RequisitoTecnico> requisitos,
            String textoPliego,
            @NotBlank
            @Size(min = 10, message = "Describe el perfil del oferente con más detalle.")
            String perfilProveedor,
            String entidad,
            Double valorEstimado,
            @Min(1) @Max(120) Integer plazoMeses,
            @Schema(description = "Aspectos a destacar, ej. 'seguridad', 'accesibilidad'.")
            List<String> enfasis,
            String proveedor,
            String modelo) implements ConSeleccionIA {

        public SolicitudPropuesta {
            requisitos = Propuestas.sinNulos(requisitos);
            enfasis = Analisis.vacioSiNulo(enfasis);
        }

        /** Sin requisitos ni pliego no hay nada contra qué alinear la propuesta. */
        public boolean sinReferencia() {
            return requisitos.isEmpty() && (textoPliego == null || textoPliego.isBlank());
        }
    }

    public record SolicitudValidacion(
            @NotBlank
            @Size(min = 40, message = "La propuesta debe tener al menos 40 caracteres.")
            String textoPropuesta,
            List<RequisitoTecnico> requisitos,
            @Schema(description = "Si no se envían requisitos estructurados, se extraen "
                    + "de aquí antes de validar.")
            String textoPliego,
            String objetoContractual,
            String proveedor,
            String modelo) implements ConSeleccionIA {

        public SolicitudValidacion {
            requisitos = Propuestas.sinNulos(requisitos);
        }
    }

    public record SolicitudRelevancia(
            @NotEmpty(message = "Envía al menos un proceso para priorizar.")
            List<Secop.ProcesoResumen> procesos,
            @Schema(description = "Capacidades del proveedor, para priorizar por encaje.")
            String perfilProveedor,
            @Min(1) @Max(50) Integer maximo,
            String proveedor,
            String modelo) implements ConSeleccionIA {

        public SolicitudRelevancia {
            maximo = maximo == null ? 15 : maximo;
        }
    }

    public record MensajeChat(
            @Schema(description = "user o assistant") String rol,
            @NotBlank String contenido) {}

    public record SolicitudChat(
            @NotEmpty List<MensajeChat> mensajes,
            @Schema(description = "Contexto adicional: pliego, proceso o propuesta en curso.")
            String contexto,
            String proveedor,
            String modelo) implements ConSeleccionIA {}
}
