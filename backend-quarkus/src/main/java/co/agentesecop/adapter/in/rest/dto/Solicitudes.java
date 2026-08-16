package co.agentesecop.adapter.in.rest.dto;

import co.agentesecop.domain.shared.Listas;
import co.agentesecop.dominio.Analisis.RequisitoTecnico;
import co.agentesecop.dominio.Secop;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Cuerpos de petición de la API.
 *
 * <p>Viven en el adaptador de entrada y no en el dominio: una solicitud HTTP no es un
 * concepto de contratación pública, es la forma que toma una petición en este protocolo
 * concreto. Ahí van también sus anotaciones de validación y de documentación, que son
 * detalles del contrato.
 *
 * <p>Todas las solicitudes que invocan al modelo llevan {@code proveedor} y
 * {@code modelo} opcionales: si vienen nulos se usa la configuración por defecto del
 * servidor. Eso permite que el usuario elija en tiempo de ejecución sin obligarlo.
 *
 * <h2>Por qué hay cotas máximas en casi todo</h2>
 *
 * <p>Bean Validation se ejecuta <em>antes</em> de entrar al recurso. Un pliego de 900.000
 * caracteres se rechaza aquí con 422, sin construir el prompt, sin serializar el contexto
 * y sin llamar a ningún proveedor. Sin estas cotas, el único freno estaba dentro del
 * proveedor y llegaba tarde: la petición se aceptaba, se enviaba, se facturaba y fallaba
 * al otro lado.
 *
 * <p>El límite de 400.000 caracteres no es arbitrario ni conservador por gusto: por encima
 * de ahí el material no cabe en la ventana de contexto útil de los modelos disponibles.
 * Rechazarlo diciendo qué hacer es más honesto que enviarlo y cobrar el fallo.
 */
public final class Solicitudes {

    private Solicitudes() {}

    /** Tope de texto largo (pliego, propuesta). Ver la nota de la clase. */
    public static final int MAXIMO_TEXTO_LARGO = 400_000;

    /**
     * Nombres de proveedor admitidos por forma, no por lista.
     *
     * <p>Validar aquí cierra la puerta en la frontera: sin esto, cualquier cadena llega
     * hasta la fábrica de modelos y se usa como clave de una caché en memoria.
     */
    public static final String PATRON_PROVEEDOR = "[a-z]{2,20}";

    /**
     * Identificadores de modelo: letras, dígitos, punto, guion, guion bajo y dos puntos.
     * Cubre {@code gemini-3.6-flash}, {@code gpt-4.1-mini}, {@code claude-sonnet-4-6} y
     * {@code llama3.1:8b} sin admitir rutas ni separadores raros.
     */
    public static final String PATRON_MODELO = "[a-zA-Z0-9._:\\-]{1,80}";

    private static final String MENSAJE_PROVEEDOR =
            "El nombre del proveedor solo admite letras minúsculas.";
    private static final String MENSAJE_MODELO =
            "El identificador del modelo tiene caracteres no admitidos.";
    private static final String MENSAJE_PLIEGO =
            "El pliego debe tener entre 40 y 400.000 caracteres. Si es más largo, "
                    + "analiza el anexo técnico por separado.";

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
            @Size(max = 500) String texto,
            @Size(max = 300) String entidad,
            @Schema(examples = "Distrito Capital de Bogotá")
            @Size(max = 200) String departamento,
            @Size(max = 200) String modalidad,
            @Size(max = 200) String estado,
            @PositiveOrZero @DecimalMax("1e15") Double valorMin,
            @PositiveOrZero @DecimalMax("1e15") Double valorMax,
            @Schema(description = "Fecha ISO, ej. 2026-01-01")
            @Size(max = 30) String fechaDesde,
            @Schema(description = "Fecha ISO, ej. 2026-12-31")
            @Size(max = 30) String fechaHasta,
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
            @NotBlank
            @Size(min = 40, max = MAXIMO_TEXTO_LARGO, message = MENSAJE_PLIEGO)
            String textoPliego,
            @Size(max = 500) String objetoContractual,
            @Size(max = 300) String entidad,
            @Size(max = 200) String modalidad,
            @PositiveOrZero @DecimalMax("1e15") Double valorEstimado,
            @Schema(description = "Capacidades y limitaciones del oferente, para "
                    + "contextualizar los riesgos.")
            @Size(max = 5_000) String contextoProveedor,
            @Pattern(regexp = PATRON_PROVEEDOR, message = MENSAJE_PROVEEDOR) String proveedor,
            @Pattern(regexp = PATRON_MODELO, message = MENSAJE_MODELO) String modelo)
            implements ConSeleccionIA {}

    public record SolicitudPropuesta(
            @NotBlank @Size(max = 500) String objetoContractual,
            @Size(max = 200) List<RequisitoTecnico> requisitos,
            @Size(max = MAXIMO_TEXTO_LARGO, message = MENSAJE_PLIEGO) String textoPliego,
            @NotBlank
            @Size(min = 10, max = 20_000,
                  message = "Describe el perfil del oferente con más detalle.")
            String perfilProveedor,
            @Size(max = 300) String entidad,
            @PositiveOrZero @DecimalMax("1e15") Double valorEstimado,
            @Min(1) @Max(120) Integer plazoMeses,
            @Schema(description = "Aspectos a destacar, ej. 'seguridad', 'accesibilidad'.")
            @Size(max = 20) List<@Size(max = 100) String> enfasis,
            @Pattern(regexp = PATRON_PROVEEDOR, message = MENSAJE_PROVEEDOR) String proveedor,
            @Pattern(regexp = PATRON_MODELO, message = MENSAJE_MODELO) String modelo)
            implements ConSeleccionIA {

        public SolicitudPropuesta {
            requisitos = Listas.copiaOVacia(requisitos);
            enfasis = Listas.copiaOVacia(enfasis);
        }

        /** Sin requisitos ni pliego no hay nada contra qué alinear la propuesta. */
        public boolean sinReferencia() {
            return requisitos.isEmpty() && (textoPliego == null || textoPliego.isBlank());
        }
    }

    public record SolicitudValidacion(
            @NotBlank
            @Size(min = 40, max = MAXIMO_TEXTO_LARGO,
                  message = "La propuesta debe tener entre 40 y 400.000 caracteres.")
            String textoPropuesta,
            @Size(max = 200) List<RequisitoTecnico> requisitos,
            @Schema(description = "Si no se envían requisitos estructurados, se extraen "
                    + "de aquí antes de validar.")
            @Size(max = MAXIMO_TEXTO_LARGO, message = MENSAJE_PLIEGO) String textoPliego,
            @Size(max = 500) String objetoContractual,
            @Pattern(regexp = PATRON_PROVEEDOR, message = MENSAJE_PROVEEDOR) String proveedor,
            @Pattern(regexp = PATRON_MODELO, message = MENSAJE_MODELO) String modelo)
            implements ConSeleccionIA {

        public SolicitudValidacion {
            requisitos = Listas.copiaOVacia(requisitos);
        }
    }

    public record SolicitudRelevancia(
            @NotEmpty(message = "Envía al menos un proceso para priorizar.")
            @Size(max = 100, message = "Como máximo 100 procesos por petición.")
            List<Secop.ProcesoResumen> procesos,
            @Schema(description = "Capacidades del proveedor, para priorizar por encaje.")
            @Size(max = 20_000) String perfilProveedor,
            @Min(1) @Max(50) Integer maximo,
            @Pattern(regexp = PATRON_PROVEEDOR, message = MENSAJE_PROVEEDOR) String proveedor,
            @Pattern(regexp = PATRON_MODELO, message = MENSAJE_MODELO) String modelo)
            implements ConSeleccionIA {

        public SolicitudRelevancia {
            maximo = maximo == null ? 15 : maximo;
        }
    }

    public record MensajeChat(
            @Schema(description = "user o assistant")
            @Size(max = 20) String rol,
            @NotBlank @Size(max = 50_000) String contenido) {}

    public record SolicitudChat(
            @NotEmpty
            @Size(max = 50, message = "La conversación no puede pasar de 50 turnos. "
                    + "Empieza una nueva para seguir.")
            List<MensajeChat> mensajes,
            @Schema(description = "Contexto adicional: pliego, proceso o propuesta en curso.")
            @Size(max = 100_000) String contexto,
            @Pattern(regexp = PATRON_PROVEEDOR, message = MENSAJE_PROVEEDOR) String proveedor,
            @Pattern(regexp = PATRON_MODELO, message = MENSAJE_MODELO) String modelo)
            implements ConSeleccionIA {}
}
