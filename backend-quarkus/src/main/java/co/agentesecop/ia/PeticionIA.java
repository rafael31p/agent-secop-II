package co.agentesecop.ia;

import java.util.List;

/**
 * Petición al modelo, independiente del proveedor.
 *
 * @param sistema instrucción de sistema (rol y tarea)
 * @param turnos conversación; para las tareas de un solo paso es un único turno de usuario
 * @param modelo identificador del modelo, o nulo para el predeterminado del proveedor
 */
public record PeticionIA(String sistema, List<Turno> turnos, String modelo) {

    public PeticionIA {
        turnos = turnos == null ? List.of() : List.copyOf(turnos);
    }

    /** Un turno de la conversación. */
    public record Turno(Rol rol, String contenido) {}

    public enum Rol {
        USUARIO,
        ASISTENTE
    }

    /** Petición de un solo turno, que es el caso de análisis, propuesta y validación. */
    public static PeticionIA unica(String sistema, String contenido, String modelo) {
        return new PeticionIA(sistema, List.of(new Turno(Rol.USUARIO, contenido)), modelo);
    }

    /** Longitud total del contenido enviado, para controlar el tamaño del contexto. */
    public int caracteres() {
        int total = sistema == null ? 0 : sistema.length();
        for (Turno turno : turnos) {
            total += turno.contenido() == null ? 0 : turno.contenido().length();
        }
        return total;
    }
}
