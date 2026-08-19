package co.agentesecop.domain.shared;

/**
 * Operaciones de texto que hacen seguro escribir en el registro algo que vino de fuera.
 *
 * <h2>Por qué existe</h2>
 *
 * <p>Un registro es un archivo de texto plano donde cada línea es un evento, y esa es toda
 * su estructura. Quien consigue meter un salto de línea en un valor que se registra puede
 * **fabricar eventos**: basta con enviar un nombre de proveedor como
 *
 * <pre>gemini\n2026-01-01 12:00:00 INFO [Seguridad] Sesión iniciada por administrador</pre>
 *
 * <p>para que el registro contenga una línea de auditoría que nunca ocurrió. No hace falta
 * malicia sofisticada: el valor llega en el cuerpo de una petición HTTP corriente.
 *
 * <p>El agente registra tres clases de texto ajeno —lo que envía el usuario, lo que
 * responde el proveedor de modelos y lo que responde datos.gov.co— y ninguna de las tres
 * es de fiar. La validación del identificador de correlación cubría este riesgo solo para
 * una cabecera; el resto de los puntos quedaban abiertos.
 *
 * <p>Vive en el dominio porque es una función pura sobre cadenas, sin ninguna dependencia,
 * y porque la necesitan tanto los adaptadores de entrada como los de salida.
 */
public final class Texto {

    /** Lo que se corta por defecto. Un registro no es el sitio para un pliego entero. */
    public static final int LARGO_MAXIMO_EN_REGISTRO = 200;

    private Texto() {}

    /** Deja un valor ajeno en condiciones de escribirse en una línea de registro. */
    public static String paraRegistro(String valor) {
        return paraRegistro(valor, LARGO_MAXIMO_EN_REGISTRO);
    }

    /**
     * Igual, con un tope propio para los casos en que el detalle importa —la respuesta que
     * no cumplió el esquema, por ejemplo— y compensa registrar más.
     */
    public static String paraRegistro(String valor, int largoMaximo) {
        if (valor == null) {
            return "(nulo)";
        }
        StringBuilder limpio = new StringBuilder(Math.min(valor.length(), largoMaximo));
        for (int i = 0; i < valor.length() && limpio.length() < largoMaximo; i++) {
            char c = valor.charAt(i);
            // Se sustituyen en vez de borrarse: dejar constancia de que había un carácter
            // de control es parte del valor de la entrada del registro. Un salto de línea
            // que desaparece sin rastro esconde justo el intento que interesa ver.
            limpio.append(esImprimible(c) ? c : '␣');
        }
        if (valor.length() > largoMaximo) {
            limpio.append("…(+").append(valor.length() - largoMaximo).append(')');
        }
        return limpio.toString();
    }

    /**
     * Todo lo que no sea un carácter de control. Se admite cualquier letra o símbolo
     * imprimible, incluidos los acentos y la eñe: el problema no es el alfabeto, es la
     * estructura del registro.
     */
    private static boolean esImprimible(char c) {
        return !Character.isISOControl(c);
    }
}
