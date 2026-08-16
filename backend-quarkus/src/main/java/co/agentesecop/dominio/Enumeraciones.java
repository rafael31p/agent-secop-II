package co.agentesecop.dominio;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Enumeraciones del dominio de contratación pública.
 *
 * <p>Se agrupan en un contenedor porque son valores pequeños y estrechamente
 * relacionados; separarlos en archivos sueltos solo añadiría ruido.
 *
 * <p>Todas serializan por su valor en minúsculas (no por el nombre de la constante),
 * para que el JSON de la API coincida con el del backend original en Python y el
 * frontend no tenga que cambiar.
 */
public final class Enumeraciones {

    private Enumeraciones() {}

    /** Qué tan exigible es un requisito del pliego. */
    public enum Criticidad {
        /** Su incumplimiento es causal de rechazo o de no habilitación. */
        OBLIGATORIO("obligatorio"),
        /** Otorga puntaje en la evaluación. */
        PONDERABLE("ponderable"),
        /** Suma sin ser exigido. */
        DESEABLE("deseable"),
        /** Contexto, no es exigencia. */
        INFORMATIVO("informativo");

        private final String valor;

        Criticidad(String valor) {
            this.valor = valor;
        }

        @JsonValue
        public String valor() {
            return valor;
        }

        @JsonCreator
        public static Criticidad desde(String texto) {
            return Utilidades.buscar(values(), Criticidad::valor, texto, INFORMATIVO);
        }
    }

    /** Resultado de contrastar un requisito contra la propuesta. */
    public enum EstadoCumplimiento {
        CUMPLE("cumple"),
        CUMPLE_PARCIAL("cumple_parcial"),
        NO_CUMPLE("no_cumple"),
        NO_EVALUABLE("no_evaluable");

        private final String valor;

        EstadoCumplimiento(String valor) {
            this.valor = valor;
        }

        @JsonValue
        public String valor() {
            return valor;
        }

        @JsonCreator
        public static EstadoCumplimiento desde(String texto) {
            return Utilidades.buscar(values(), EstadoCumplimiento::valor, texto, NO_EVALUABLE);
        }
    }

    public enum NivelRiesgo {
        ALTO("alto"),
        MEDIO("medio"),
        BAJO("bajo");

        private final String valor;

        NivelRiesgo(String valor) {
            this.valor = valor;
        }

        @JsonValue
        public String valor() {
            return valor;
        }

        @JsonCreator
        public static NivelRiesgo desde(String texto) {
            return Utilidades.buscar(values(), NivelRiesgo::valor, texto, MEDIO);
        }
    }

    /** Naturaleza del riesgo detectado en el proceso. */
    public enum TipoRiesgo {
        TECNICO("tecnico"),
        JURIDICO("juridico"),
        FINANCIERO("financiero"),
        OPERATIVO("operativo"),
        CRONOGRAMA("cronograma"),
        /** Requisitos que restringen la pluralidad de oferentes. */
        COMPETENCIA("competencia");

        private final String valor;

        TipoRiesgo(String valor) {
            this.valor = valor;
        }

        @JsonValue
        public String valor() {
            return valor;
        }

        @JsonCreator
        public static TipoRiesgo desde(String texto) {
            return Utilidades.buscar(values(), TipoRiesgo::valor, texto, TECNICO);
        }
    }

    /** Conclusión global de la validación de una propuesta. */
    public enum Veredicto {
        APTA("apta"),
        APTA_CON_AJUSTES("apta_con_ajustes"),
        RIESGO_DE_RECHAZO("riesgo_de_rechazo"),
        NO_APTA("no_apta");

        private final String valor;

        Veredicto(String valor) {
            this.valor = valor;
        }

        @JsonValue
        public String valor() {
            return valor;
        }

        @JsonCreator
        public static Veredicto desde(String texto) {
            return Utilidades.buscar(values(), Veredicto::valor, texto, RIESGO_DE_RECHAZO);
        }
    }

    /** Búsqueda tolerante para no romper si el modelo devuelve una variante. */
    private static final class Utilidades {
        private Utilidades() {}

        static <E extends Enum<E>> E buscar(
                E[] valores,
                java.util.function.Function<E, String> extractor,
                String texto,
                E porDefecto) {
            if (texto == null || texto.isBlank()) {
                return porDefecto;
            }
            var normalizado = texto.trim().toLowerCase();
            for (E valor : valores) {
                if (extractor.apply(valor).equals(normalizado)
                        || valor.name().equalsIgnoreCase(normalizado)) {
                    return valor;
                }
            }
            return porDefecto;
        }
    }
}
