package co.agentesecop.domain.shared;

import java.util.Locale;

/**
 * Enumeración con un código estable que viaja por el cable.
 *
 * <p>El código es parte del contrato HTTP y no cambia aunque la constante se renombre: el
 * frontend y el backend Python original hablan de {@code "obligatorio"} y
 * {@code "cumple_parcial"}, no de nombres de constante.
 *
 * <p>Sustituye a cinco copias del mismo molde —campo, accesor, {@code @JsonValue},
 * {@code @JsonCreator} y una búsqueda tolerante— repartidas por las cinco enumeraciones.
 * La serialización se resuelve una sola vez, en el adaptador
 * ({@code adapter.in.rest.CodedEnumModule}), y añadir una enumeración cuesta ahora unas
 * cinco líneas en lugar de treinta.
 *
 * <p>Esta interfaz vive en el dominio y no importa nada de terceros: es una regla del
 * negocio —«estos valores tienen un código público»—, no un detalle de serialización.
 */
public interface CodedEnum {

    /** Código que viaja por el cable. En minúsculas y estable. */
    String code();

    /**
     * Busca por código, tolerando variantes, y cae al valor por defecto si no reconoce.
     *
     * <p>La tolerancia es deliberada y se conserva del código anterior: la fuente de estos
     * valores es un modelo de lenguaje, y fallar duro ante una variante léxica tiraría un
     * análisis entero que por lo demás es correcto. También se acepta el nombre de la
     * constante, porque el esquema JSON que se le impone al modelo se deriva de él.
     */
    static <E extends Enum<E> & CodedEnum> E parse(Class<E> type, String raw, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (E value : type.getEnumConstants()) {
            if (value.code().equals(normalized) || value.name().equalsIgnoreCase(normalized)) {
                return value;
            }
        }
        return fallback;
    }
}
