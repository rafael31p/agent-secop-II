package co.agentesecop.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pruebas de la lógica común de los proveedores: recorte de JSON y traducción de errores.
 *
 * <p>La traducción importa mucho: un error mal clasificado hace que se reintente algo que
 * nunca va a funcionar (clave inválida) o que se dé por perdido algo transitorio (503).
 */
class ProveedorLangChain4jTest {

    // ------------------------------------------------------ recorte de JSON

    @Test
    @DisplayName("Deja intacto un JSON limpio")
    void jsonLimpio() {
        String json = "{\"a\":1}";

        assertEquals(json, ProveedorLangChain4j.recortarACuerpoJson(json));
    }

    @Test
    @DisplayName("Quita el bloque de código Markdown que algunos modelos añaden")
    void quitaBloqueMarkdown() {
        String conCerca = """
                ```json
                {"resumen": "hola"}
                ```""";

        assertEquals("{\"resumen\": \"hola\"}",
                ProveedorLangChain4j.recortarACuerpoJson(conCerca));
    }

    @Test
    @DisplayName("Descarta el texto explicativo alrededor del objeto")
    void descartaTextoAlrededor() {
        String conCharla = "Claro, aquí tienes el análisis:\n{\"a\": 1}\nEspero que sirva.";

        assertEquals("{\"a\": 1}", ProveedorLangChain4j.recortarACuerpoJson(conCharla));
    }

    @Test
    @DisplayName("Conserva los objetos anidados al recortar")
    void conservaAnidados() {
        String anidado = "{\"a\":{\"b\":{\"c\":1}}}";

        assertEquals(anidado, ProveedorLangChain4j.recortarACuerpoJson(anidado));
    }

    // --------------------------------------------------- traducción de errores

    /** Doble mínimo: solo necesita heredar la traducción, no hablar con ningún servicio. */
    private static final class ProveedorDePrueba extends ProveedorLangChain4j {
        ProveedorDePrueba() {
            super(null, null);
        }

        @Override
        public String nombre() {
            return "prueba";
        }

        @Override
        public String etiqueta() {
            return "Proveedor de prueba";
        }

        @Override
        public boolean configurado() {
            return true;
        }

        @Override
        public java.util.List<String> modelosSugeridos() {
            return java.util.List.of("modelo-x");
        }

        @Override
        public String modeloPorDefecto() {
            return "modelo-x";
        }

        @Override
        protected dev.langchain4j.model.chat.ChatModel construirModelo(String m) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected dev.langchain4j.model.chat.StreamingChatModel construirModeloFlujo(String m) {
            throw new UnsupportedOperationException();
        }
    }

    private final ProveedorDePrueba proveedor = new ProveedorDePrueba();

    @ParameterizedTest(name = "{0} -> HTTP {1}")
    @CsvSource({
        "'Invalid API key provided',                401",
        "'401 Unauthorized',                        401",
        "'You exceeded your current quota',         429",
        "'429 rate limit exceeded',                 429",
        "'RESOURCE_EXHAUSTED',                      429",
        "'404 NOT_FOUND model does not exist',      404",
        "'This model is no longer available',       404",
        "'503 UNAVAILABLE high demand',             502",
        "'500 internal error',                      502",
        "'Read timed out',                          502"
    })
    @DisplayName("Clasifica los errores del proveedor por su código correcto")
    void clasificaErrores(String mensaje, int esperado) {
        var traducido = proveedor.traducir(new RuntimeException(mensaje), "modelo-x");

        assertEquals(esperado, traducido.estadoHttp(),
                "Mal clasificado '%s': %s".formatted(mensaje, traducido.getMessage()));
    }

    @Test
    @DisplayName("Los filtros de contenido dan 422, no un fallo de proveedor")
    void filtroDeContenido() {
        var traducido = proveedor.traducir(
                new RuntimeException("Response blocked by safety filter"), "modelo-x");

        assertEquals(422, traducido.estadoHttp());
        assertInstanceOf(ErroresIA.RespuestaInutilizable.class, traducido);
    }

    @Test
    @DisplayName("Busca el detalle útil en la causa anidada")
    void buscaEnLaCausa() {
        var anidado = new RuntimeException("Request failed",
                new IllegalStateException("wrapper",
                        new RuntimeException("429 quota exceeded")));

        assertEquals(429, proveedor.traducir(anidado, "modelo-x").estadoHttp());
    }

    @Test
    @DisplayName("Un error ya traducido no se vuelve a envolver")
    void noReenvuelve() {
        var original = new ErroresIA.ProveedorNoConfigurado("falta la clave");

        assertEquals(original, proveedor.traducir(original, "modelo-x"));
    }

    @Test
    @DisplayName("El mensaje de modelo inexistente nombra el modelo y dónde consultarlos")
    void mensajeDeModeloInexistente() {
        var traducido = proveedor.traducir(
                new RuntimeException("404 not found"), "modelo-fantasma");

        assertTrue(traducido.getMessage().contains("modelo-fantasma"));
        assertTrue(traducido.getMessage().contains("/api/proveedores"),
                "Debe decir dónde ver los disponibles: " + traducido.getMessage());
    }

    @Test
    @DisplayName("Un error desconocido cae en 502, no en 500 opaco")
    void errorDesconocido() {
        var traducido = proveedor.traducir(new RuntimeException("algo raro pasó"), "modelo-x");

        assertEquals(502, traducido.estadoHttp());
        assertTrue(traducido.getMessage().contains("algo raro pasó"));
    }
}
