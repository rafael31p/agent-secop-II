package co.agentesecop.application.port.out;

import io.smallrye.mutiny.Multi;
import java.util.List;

/**
 * Lo que la aplicación necesita de un modelo de lenguaje, y nada más.
 *
 * <p>Es la vista <em>desde dentro</em>: dos operaciones, una que devuelve un objeto
 * tipado y otra que devuelve texto según se produce. Qué proveedores existen, cómo se
 * construyen sus clientes, cómo se reintenta un 429 o cómo se traduce un error suyo son
 * asuntos del adaptador, y por eso no aparecen aquí.
 *
 * <p>El adaptador que lo implementa es {@code adapter.out.llm.ModeloDeLenguajeLangChain4j},
 * que delega en el registro de proveedores existente.
 */
public interface ModeloDeLenguaje {

    /**
     * Pide una respuesta que cumpla la forma de {@code tipo}.
     *
     * @param sistema instrucciones de rol y tarea
     * @param usuario el material a analizar
     */
    <T> T estructurado(String sistema, String usuario, SeleccionDeModelo seleccion, Class<T> tipo);

    /** Conversación con respuesta progresiva. */
    Multi<String> flujo(String sistema, List<Turno> turnos, SeleccionDeModelo seleccion);

    /** Un turno de conversación. */
    record Turno(Rol rol, String contenido) {}

    enum Rol {
        USUARIO,
        ASISTENTE
    }
}
