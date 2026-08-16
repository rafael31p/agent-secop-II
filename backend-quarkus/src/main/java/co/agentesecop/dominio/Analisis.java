package co.agentesecop.dominio;

import co.agentesecop.dominio.Enumeraciones.Criticidad;
import co.agentesecop.dominio.Enumeraciones.NivelRiesgo;
import co.agentesecop.dominio.Enumeraciones.TipoRiesgo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Modelos de salida del análisis de un pliego.
 *
 * <p>Estos tipos cumplen doble función, igual que en la versión Python: son el contrato
 * HTTP que documenta OpenAPI <em>y</em> el esquema JSON que se le impone al modelo de
 * lenguaje. Una sola definición, imposible que se desincronicen.
 *
 * <p>Los records llevan {@code @JsonIgnoreProperties(ignoreUnknown = true)} porque un
 * modelo puede añadir campos no solicitados; descartarlos es preferible a fallar.
 */
public final class Analisis {

    private Analisis() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Requisito técnico atómico y verificable extraído del pliego.")
    public record RequisitoTecnico(
            @Schema(description = "Identificador corto, ej. RT-01", examples = "RT-01")
            String id,
            @Schema(description = "Arquitectura, Seguridad, Datos, Integraciones, "
                    + "Infraestructura, Soporte, Metodología, Accesibilidad, "
                    + "Interoperabilidad, Personal, Otros")
            String categoria,
            String requisito,
            Criticidad criticidad,
            @Schema(description = "Documento o certificado con el que se acredita.")
            String evidenciaEsperada,
            @Schema(description = "Norma o marco aplicable. Nulo si no aplica.")
            String normaRelacionada,
            @Schema(description = "Fragmento textual del pliego que lo sustenta. "
                    + "Nulo si el requisito es inferido y no textual.")
            String citaPliego) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RiesgoDetectado(
            String descripcion,
            NivelRiesgo nivel,
            String impacto,
            String mitigacion,
            TipoRiesgo tipo) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RespuestaAnalisis(
            String resumenEjecutivo,
            String objetoNormalizado,
            List<RequisitoTecnico> requisitos,
            List<RiesgoDetectado> riesgos,
            List<String> criteriosEvaluacion,
            List<String> documentosHabilitantes,
            @Schema(description = "Preguntas para radicar como observaciones al "
                    + "proyecto de pliego.")
            List<String> preguntasALaEntidad,
            List<String> alertasNormativas,
            String recomendacion) {

        /** Normaliza las listas nulas para que el frontend nunca reciba {@code null}. */
        public RespuestaAnalisis {
            requisitos = requisitos == null ? List.of() : List.copyOf(requisitos);
            riesgos = riesgos == null ? List.of() : List.copyOf(riesgos);
            criteriosEvaluacion = vacioSiNulo(criteriosEvaluacion);
            documentosHabilitantes = vacioSiNulo(documentosHabilitantes);
            preguntasALaEntidad = vacioSiNulo(preguntasALaEntidad);
            alertasNormativas = vacioSiNulo(alertasNormativas);
        }
    }

    static List<String> vacioSiNulo(List<String> lista) {
        return lista == null ? List.of() : List.copyOf(lista);
    }
}
