package co.agentesecop.pruebas;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Captura lo que la aplicación escribe en el registro, para poder afirmar sobre ello.
 *
 * <p>Existe porque la única forma honesta de comprobar que no se puede inyectar una línea
 * de registro es <b>mirar el registro</b>. Una prueba que llame al saneador y compruebe su
 * salida verifica el saneador, no que se esté usando en los sitios que importan; el
 * defecto que se quiere evitar es precisamente olvidarse de llamarlo.
 *
 * <p>Se engancha al {@code Logger} raíz de {@code java.util.logging}, que bajo Quarkus es
 * el de JBoss Log Manager: todo lo que escriba cualquier clase pasa por aquí.
 *
 * <p>La lista es de copia al escribir porque las peticiones se atienden en hilos de
 * trabajo y la prueba lee desde el suyo.
 */
public final class ColectorDeRegistros implements AutoCloseable {

    /** El mensaje y el identificador de correlación que había cuando se escribió. */
    public record Linea(String mensaje, String correlacion, String hilo) {}

    private final List<Linea> capturados = new CopyOnWriteArrayList<>();
    /** La clave que se vigila. Se fija aquí para poder leerla dentro del enganche. */
    private static final String CLAVE_CORRELACION = "correlationId";

    private final Logger raiz = Logger.getLogger("");
    private final Handler enganche;

    public ColectorDeRegistros() {
        this.enganche = new Handler() {
            @Override
            public void publish(LogRecord registro) {
                // El contexto se lee AQUÍ, en el hilo que escribe la línea y desde la
                // misma fachada que usa la aplicación. Leerlo del LogRecord no sirve: su
                // copia del contexto la rellena la cadena de manejadores más tarde, así
                // que sale vacía y haría creer que la propagación no funciona.
                capturados.add(new Linea(
                        formatear(registro),
                        org.jboss.logging.MDC.get(CLAVE_CORRELACION) == null
                                ? null
                                : String.valueOf(org.jboss.logging.MDC.get(CLAVE_CORRELACION)),
                        Thread.currentThread().getName()));
            }

            @Override
            public void flush() {
                // Nada que vaciar: se guarda en memoria.
            }

            @Override
            public void close() {
                // Nada que cerrar.
            }
        };
        enganche.setLevel(Level.ALL);
        raiz.addHandler(enganche);
    }

    /** Lo registrado hasta ahora, ya formateado como lo vería quien lea el archivo. */
    public List<String> mensajes() {
        return capturados.stream().map(Linea::mensaje).toList();
    }

    /** Las líneas completas, con el contexto que llevaban. */
    public List<Linea> lineas() {
        return List.copyOf(capturados);
    }

    public void limpiar() {
        capturados.clear();
    }

    /** Los identificadores de correlación vistos, uno por línea que llevara alguno. */
    public List<String> correlacionesVistas() {
        return capturados.stream()
                .map(Linea::correlacion)
                .filter(valor -> valor != null && !valor.isBlank())
                .toList();
    }

    @Override
    public void close() {
        raiz.removeHandler(enganche);
    }

    /**
     * El mensaje con sus parámetros ya sustituidos.
     *
     * <p>Sustituirlos importa: el peligro no está en la plantilla, que es una constante del
     * código, sino en lo que se interpola en ella. Comprobar {@code getMessage()} a secas
     * daría verde siempre.
     */
    private static String formatear(LogRecord registro) {
        String plantilla = String.valueOf(registro.getMessage());
        Object[] parametros = registro.getParameters();
        if (parametros == null || parametros.length == 0) {
            return plantilla;
        }
        StringBuilder completo = new StringBuilder(plantilla);
        // No se intenta reproducir el formateo exacto de JBoss —hay dos sintaxis en juego,
        // %s y {0}—; basta con que el valor interpolado quede dentro del texto examinado.
        for (Object parametro : parametros) {
            completo.append(' ').append(parametro);
        }
        return completo.toString();
    }
}
