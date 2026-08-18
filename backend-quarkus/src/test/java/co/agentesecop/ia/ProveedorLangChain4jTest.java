package co.agentesecop.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.agentesecop.adapter.out.llm.error.CuotaAgotada;
import co.agentesecop.adapter.out.llm.error.FalloTransitorio;
import co.agentesecop.adapter.out.llm.error.ProveedorNoConfigurado;
import co.agentesecop.adapter.out.llm.error.RespuestaInutilizable;
import co.agentesecop.adapter.out.llm.error.ServicioDelProveedorCaido;
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
            super(null, null, new CierreDiferido(java.time.Duration.ofMinutes(3)));
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

    /**
     * Se afirma sobre el <b>tipo</b> y no sobre un código HTTP, y el cambio no es
     * cosmético: el tipo es lo que decide la política. {@code @Retry(retryOn = ...)} y
     * {@code @CircuitBreaker(failOn = ...)} razonan sobre clases, así que clasificar mal
     * aquí significa reintentar una clave inválida —tres llamadas facturables que nunca
     * van a funcionar— o rendirse ante un 503 que se habría resuelto solo. El código HTTP
     * es ahora consecuencia, y lo elige el mapeador de la capa REST.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "'Invalid API key provided',                CredencialInvalida",
        "'401 Unauthorized',                        CredencialInvalida",
        "'You exceeded your current quota',         CuotaAgotada",
        "'429 rate limit exceeded',                 CuotaAgotada",
        "'RESOURCE_EXHAUSTED',                      CuotaAgotada",
        "'404 NOT_FOUND model does not exist',      ModeloDesconocido",
        "'This model is no longer available',       ModeloDesconocido",
        "'503 UNAVAILABLE high demand',             ServicioDelProveedorCaido",
        "'500 internal error',                      ServicioDelProveedorCaido",
        "'Read timed out',                          ServicioDelProveedorCaido"
    })
    @DisplayName("Clasifica los errores del proveedor por su tipo, que es lo que decide")
    void clasificaErrores(String mensaje, String tipoEsperado) {
        var traducido = proveedor.traducir(new RuntimeException(mensaje), "modelo-x");

        assertEquals(tipoEsperado, traducido.getClass().getSimpleName(),
                "Mal clasificado '%s': %s".formatted(mensaje, traducido.getMessage()));
    }

    /**
     * Y la consecuencia que de verdad importa, dicha aparte: qué se reintenta.
     *
     * <p>Es la afirmación que la versión anterior no podía hacer. Con la clasificación
     * expresada como código HTTP, saber si algo se reintentaba exigía leer un {@code if}
     * en otra clase; ahora es una pregunta sobre el tipo, y la responde el compilador.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "'429 rate limit exceeded',            true",
        "'503 UNAVAILABLE',                    true",
        "'algo raro pasó',                     true",
        "'401 Unauthorized',                   false",
        "'404 NOT_FOUND',                      false",
        "'Response blocked by safety filter',  false"
    })
    @DisplayName("Solo lo transitorio se reintenta; lo demás falla a la primera")
    void loQueSeReintenta(String mensaje, boolean reintentable) {
        var traducido = proveedor.traducir(new RuntimeException(mensaje), "modelo-x");

        assertEquals(reintentable, traducido instanceof FalloTransitorio,
                "'%s' quedó como %s".formatted(mensaje, traducido.getClass().getSimpleName()));
    }

    @Test
    @DisplayName("Los filtros de contenido no son un fallo del proveedor")
    void filtroDeContenido() {
        var traducido = proveedor.traducir(
                new RuntimeException("Response blocked by safety filter"), "modelo-x");

        // Responder algo inservible no es lo mismo que no responder: reintentar un filtro
        // de contenido con el mismo material da exactamente el mismo resultado.
        assertInstanceOf(RespuestaInutilizable.class, traducido);
    }

    @Test
    @DisplayName("Busca el detalle útil en la causa anidada")
    void buscaEnLaCausa() {
        var anidado = new RuntimeException("Request failed",
                new IllegalStateException("wrapper",
                        new RuntimeException("429 quota exceeded")));

        assertInstanceOf(CuotaAgotada.class, proveedor.traducir(anidado, "modelo-x"));
    }

    @Test
    @DisplayName("Un error ya traducido no se vuelve a envolver")
    void noReenvuelve() {
        var original = new ProveedorNoConfigurado("falta la clave");

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
    @DisplayName("Un error desconocido se trata como caída temporal, no como 500 opaco")
    void errorDesconocido() {
        var traducido = proveedor.traducir(new RuntimeException("algo raro pasó"), "modelo-x");

        assertInstanceOf(ServicioDelProveedorCaido.class, traducido);
        assertTrue(traducido.getMessage().contains("Proveedor de prueba"),
                "El mensaje debe decir qué proveedor falló: " + traducido.getMessage());
    }

    // ------------------------------------------------- no filtrar texto ajeno

    @Test
    @DisplayName("El mensaje del proveedor no viaja en el error que ve el usuario")
    void noFiltraElTextoDelProveedor() {
        var traducido = proveedor.traducir(new RuntimeException("algo raro pasó"), "modelo-x");

        assertFalse(traducido.getMessage().contains("algo raro pasó"),
                "La rama por defecto recibe lo inesperado; devolverlo tal cual publica en "
                        + "el navegador lo que sea que haya escrito el proveedor: "
                        + traducido.getMessage());
    }

    @Test
    @DisplayName("Una clave de API en el error del proveedor no llega al usuario")
    void noFiltraLaCredencial() {
        // No es un caso rebuscado: la API de Google AI Studio lleva la clave en la cadena
        // de consulta, así que cualquier error que incluya la URL la incluye a ella.
        var conCredencial = new RuntimeException(
                "Request failed: GET https://generativelanguage.googleapis.com"
                        + "/v1/models/gemini-3.6-flash:generateContent?key=AIzaSyTEST123");

        var traducido = proveedor.traducir(conCredencial, "gemini-3.6-flash");

        assertFalse(traducido.getMessage().contains("AIzaSyTEST123"),
                "La credencial llegaría al navegador: " + traducido.getMessage());
        assertFalse(traducido.getMessage().contains("googleapis.com"),
                "La URL de la petición no aporta nada al usuario y arrastra la clave: "
                        + traducido.getMessage());

        // Y sin embargo no se pierde: viaja como causa, que es lo que se registra.
        assertNotNull(traducido.getCause());
        assertTrue(traducido.getCause().getMessage().contains("AIzaSyTEST123"),
                "El detalle debe seguir disponible para el registro y el diagnóstico.");
    }

    @Test
    @DisplayName("Las ramas clasificadas tampoco arrastran el texto original")
    void lasRamasClasificadasNoFiltran() {
        var conCredencial = new RuntimeException(
                "429 RESOURCE_EXHAUSTED for key=AIzaSyTEST123");

        var traducido = proveedor.traducir(conCredencial, "modelo-x");

        assertInstanceOf(CuotaAgotada.class, traducido);
        assertFalse(traducido.getMessage().contains("AIzaSyTEST123"),
                "Clasificar bien no basta si el mensaje se construye con el texto ajeno: "
                        + traducido.getMessage());
    }
}
