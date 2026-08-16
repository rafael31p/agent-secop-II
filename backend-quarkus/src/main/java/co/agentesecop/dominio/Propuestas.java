package co.agentesecop.dominio;

import static co.agentesecop.dominio.Analisis.vacioSiNulo;

import co.agentesecop.dominio.Analisis.RequisitoTecnico;
import co.agentesecop.domain.model.proposal.EstadoCumplimiento;
import co.agentesecop.domain.model.proposal.Veredicto;
import co.agentesecop.domain.model.tender.Criticidad;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Modelos de salida de la generación y la validación de propuestas técnicas. */
public final class Propuestas {

    private Propuestas() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeccionPropuesta(
            String titulo,
            String contenido,
            @Schema(description = "IDs de requisito que esta sección cubre.")
            List<String> requisitosCubiertos) {

        public SeccionPropuesta {
            requisitosCubiertos = vacioSiNulo(requisitosCubiertos);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RespuestaPropuesta(
            String titulo,
            String resumenEjecutivo,
            List<SeccionPropuesta> secciones,
            @Schema(description = "Lo que se asumió por ausencia de información en el pliego.")
            List<String> supuestos,
            @Schema(description = "Exigencias del pliego que el perfil del oferente no "
                    + "acredita. El agente las declara en lugar de inventarlas.")
            List<String> vaciosDeInformacion,
            @Schema(description = "Propuesta completa en Markdown, lista para exportar.")
            String markdown) {

        public RespuestaPropuesta {
            secciones = secciones == null ? List.of() : List.copyOf(secciones);
            supuestos = vacioSiNulo(supuestos);
            vaciosDeInformacion = vacioSiNulo(vaciosDeInformacion);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ItemCumplimiento(
            String requisitoId,
            String requisito,
            Criticidad criticidad,
            EstadoCumplimiento estado,
            @Schema(description = "Cita corta de la propuesta que lo sustenta.")
            String evidenciaEnPropuesta,
            String brecha,
            String accionCorrectiva) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RespuestaValidacion(
            @Schema(description = "0-100, ponderando obligatorios por encima de deseables.")
            int puntajeCumplimiento,
            Veredicto veredicto,
            String resumen,
            List<ItemCumplimiento> matriz,
            @Schema(description = "Requisitos obligatorios incumplidos, con la nota de si "
                    + "serían subsanables bajo el régimen colombiano.")
            List<String> causalesDeRechazo,
            List<String> mejorasPrioritarias) {

        public RespuestaValidacion {
            matriz = matriz == null ? List.of() : List.copyOf(matriz);
            causalesDeRechazo = vacioSiNulo(causalesDeRechazo);
            mejorasPrioritarias = vacioSiNulo(mejorasPrioritarias);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProcesoPriorizado(
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
            List<String> banderas) {

        public ProcesoPriorizado {
            banderas = vacioSiNulo(banderas);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RespuestaRelevancia(List<ProcesoPriorizado> priorizados, String resumen) {

        public RespuestaRelevancia {
            priorizados = priorizados == null ? List.of() : List.copyOf(priorizados);
        }
    }

    /** Requisitos usados como referencia al validar; útil para las pruebas. */
    public static List<RequisitoTecnico> sinNulos(List<RequisitoTecnico> requisitos) {
        return requisitos == null ? List.of() : List.copyOf(requisitos);
    }
}
