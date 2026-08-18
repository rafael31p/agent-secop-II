package co.agentesecop.adapter.out.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Codifica listas uniformes en TOON tabular, para gastar menos tokens en el prompt.
 *
 * <h2>Qué problema resuelve</h2>
 *
 * <p>Los tres sitios donde se le manda una lista al modelo —los requisitos de la
 * propuesta, los requisitos de la validación y los procesos a priorizar— la enviaban como
 * JSON con sangrado. Ahí cada objeto repite los nombres de todos sus campos, y el sangrado
 * añade espacios que también se tokenizan. Con siete requisitos, los nombres de campo se
 * escriben siete veces para decir lo mismo.
 *
 * <p>TOON declara los campos una sola vez, en una cabecera, y escribe los datos como filas:
 *
 * <pre>
 * requisitos[2]{id,requisito,criticidad}:
 *   REQ-01,"Portal bajo arquitectura de microservicios",obligatorio
 *   REQ-02,"Entrega del código fuente",obligatorio
 * </pre>
 *
 * <p>frente a lo que había:
 *
 * <pre>
 * [ {
 *   "id" : "REQ-01",
 *   "requisito" : "Portal bajo arquitectura de microservicios",
 *   "criticidad" : "obligatorio"
 * }, {
 *   "id" : "REQ-02", …
 * </pre>
 *
 * <p>La longitud del array va en la cabecera a propósito: le dice al modelo cuántas filas
 * debe haber, que es información que el JSON no da hasta terminar de leerlo.
 *
 * <h2>Qué cubre y qué no</h2>
 *
 * <p>Solo la <b>forma tabular</b> (§9.3 del spec), que es la única que aparece aquí: listas
 * de objetos con los mismos campos y valores primitivos. Cualquier otra cosa —listas
 * mixtas, campos anidados, listas vacías de forma desconocida— cae de vuelta a JSON, que
 * es correcto aunque gaste más. Implementar el formato entero sería escribir un
 * serializador de propósito general para un caso de uso que no lo necesita.
 *
 * <p>Se implementa aquí en vez de traer una biblioteca porque la parte que se usa son unas
 * pocas reglas y no hay implementación oficial para Java: el SDK de referencia es
 * TypeScript. Las reglas de entrecomillado y escape siguen el spec 4.1.
 *
 * <h2>Por qué pasa por Jackson</h2>
 *
 * <p>Porque la serialización de los tipos del dominio ya está decidida en otro sitio: las
 * enumeraciones salen con su código por cable ({@code obligatorio}, no
 * {@code OBLIGATORIO}) gracias a {@code CodedEnumCustomizer}, y los prompts le piden al
 * modelo exactamente esos códigos. Codificar los objetos por reflexión propia rompería esa
 * correspondencia en silencio.
 */
public final class Toon {

    /** Dos espacios por nivel, el valor por defecto del spec. */
    private static final String SANGRIA = "  ";

    /** El delimitador por defecto; con él, la cabecera no lleva símbolo. */
    private static final char DELIMITADOR = ',';

    /** Cadenas que parecen un número y por tanto deben ir entre comillas. */
    private static final Pattern PARECE_NUMERO =
            Pattern.compile("[+-]?[0-9]+(?:\\.[0-9]+)?(?:e[+-]?[0-9]+)?", Pattern.CASE_INSENSITIVE);

    private Toon() {}

    /** El resultado, o la razón por la que no se pudo codificar. */
    public record Resultado(String texto, boolean tabular) {}

    /**
     * Codifica una lista como tabla TOON.
     *
     * @param nombre la clave que encabeza la tabla, p. ej. {@code requisitos}
     * @return la tabla, o {@code tabular = false} si la lista no admite forma tabular
     */
    public static Resultado tabla(ObjectMapper jackson, String nombre, Object lista) {
        JsonNode raiz = jackson.valueToTree(lista);
        if (!raiz.isArray()) {
            return new Resultado(null, false);
        }
        ArrayNode elementos = (ArrayNode) raiz;
        if (elementos.isEmpty()) {
            // Una lista vacía sigue siendo información: decir que hay cero es distinto de
            // no decir nada, y le ahorra al modelo suponer que se nos olvidó.
            return new Resultado(nombre + "[0]:", true);
        }

        List<String> campos = camposUniformes(elementos);
        if (campos == null) {
            return new Resultado(null, false);
        }

        StringBuilder salida = new StringBuilder();
        salida.append(nombre).append('[').append(elementos.size()).append("]{")
                .append(String.join(String.valueOf(DELIMITADOR), campos.stream()
                        .map(Toon::comoCelda).toList()))
                .append("}:");
        for (JsonNode elemento : elementos) {
            salida.append('\n').append(SANGRIA);
            for (int i = 0; i < campos.size(); i++) {
                if (i > 0) {
                    salida.append(DELIMITADOR);
                }
                salida.append(celda(elemento.get(campos.get(i))));
            }
        }
        return new Resultado(salida.toString(), true);
    }

    /**
     * Los campos comunes, o {@code null} si la lista no es tabulable.
     *
     * <p>Se exige que todos los objetos tengan las mismas claves <b>en el mismo orden</b> y
     * que ningún valor sea a su vez objeto o lista. Es más estricto de lo que permite el
     * spec —que admite grupos anidados— y a propósito: la alternativa es JSON, que
     * funciona, así que no vale la pena arriesgar una codificación dudosa por unos tokens.
     */
    private static List<String> camposUniformes(ArrayNode elementos) {
        List<String> referencia = null;
        for (JsonNode elemento : elementos) {
            if (!(elemento instanceof ObjectNode objeto)) {
                return null;
            }
            List<String> campos = new ArrayList<>();
            for (Iterator<String> it = objeto.fieldNames(); it.hasNext(); ) {
                String campo = it.next();
                JsonNode valor = objeto.get(campo);
                if (valor.isObject() || valor.isArray()) {
                    return null;
                }
                campos.add(campo);
            }
            if (referencia == null) {
                referencia = campos;
            } else if (!referencia.equals(campos)) {
                return null;
            }
        }
        return referencia;
    }

    private static String celda(JsonNode valor) {
        if (valor == null || valor.isNull()) {
            return "null";
        }
        if (valor.isBoolean()) {
            return valor.booleanValue() ? "true" : "false";
        }
        if (valor.isNumber()) {
            // El texto original de Jackson ya viene en forma canónica.
            return valor.asText();
        }
        return comoCelda(valor.asText());
    }

    /**
     * Entrecomilla si el spec lo exige, y solo entonces.
     *
     * <p>La lista de condiciones no es decorativa: sin entrecomillar, un valor que contenga
     * la coma parte la fila y desplaza todas las columnas siguientes. Los requisitos de un
     * pliego llevan comas y dos puntos casi siempre.
     */
    static String comoCelda(String valor) {
        if (necesitaComillas(valor)) {
            return '"' + escapar(valor) + '"';
        }
        return valor;
    }

    private static boolean necesitaComillas(String valor) {
        if (valor.isEmpty()) {
            return true;
        }
        if (valor.equals("true") || valor.equals("false") || valor.equals("null")) {
            return true;
        }
        if (PARECE_NUMERO.matcher(valor).matches()) {
            return true;
        }
        char primero = valor.charAt(0);
        char ultimo = valor.charAt(valor.length() - 1);
        if (esBlanco(primero) || esBlanco(ultimo) || primero == '-' || primero == '#') {
            return true;
        }
        for (int i = 0; i < valor.length(); i++) {
            char c = valor.charAt(i);
            if (c == DELIMITADOR || c == ':' || c == '"' || c == '\\'
                    || c == '[' || c == ']' || c == '{' || c == '}'
                    || c < 0x20) {
                return true;
            }
        }
        return false;
    }

    private static boolean esBlanco(char c) {
        return c == ' ' || c == '\t';
    }

    private static String escapar(String valor) {
        StringBuilder salida = new StringBuilder(valor.length() + 8);
        for (int i = 0; i < valor.length(); i++) {
            char c = valor.charAt(i);
            switch (c) {
                case '\\' -> salida.append("\\\\");
                case '"' -> salida.append("\\\"");
                case '\n' -> salida.append("\\n");
                case '\r' -> salida.append("\\r");
                case '\t' -> salida.append("\\t");
                default -> {
                    if (c < 0x20) {
                        salida.append("\\u%04x".formatted((int) c));
                    } else {
                        salida.append(c);
                    }
                }
            }
        }
        return salida.toString();
    }
}
