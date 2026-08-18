package co.agentesecop.pruebas;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/**
 * Un proveedor de modelos falso, en la misma JVM, que se puede hacer fallar a voluntad.
 *
 * <h2>Por qué se simula OpenAI y no Gemini</h2>
 *
 * <p>Porque su cliente admite una URL base configurable y el de Google AI no. La API de
 * OpenAI es además la que comparte DeepSeek, así que el mismo servidor sirve para los dos
 * proveedores. Lo que se está probando no es OpenAI: es la política de resiliencia, que es
 * la misma para todos porque vive en un decorador sobre el puerto.
 *
 * <p>WireMock como librería y no Testcontainers porque este entorno no tiene Docker.
 */
public class ServidorModeloFalso implements QuarkusTestResourceLifecycleManager {

    public static final int PUERTO = 8090;

    /** Ruta de la API de completado de chat de OpenAI. */
    public static final String RUTA = "/v1/chat/completions";

    /** Un análisis válido y mínimo, para cuando lo que se prueba es el camino feliz. */
    public static final String ANALISIS_VALIDO = """
            {"resumenEjecutivo":"Proceso de portal ciudadano.",
             "objetoNormalizado":"Portal ciudadano",
             "requisitos":[],"riesgos":[],"criteriosEvaluacion":[],
             "documentosHabilitantes":[],"preguntasALaEntidad":[],
             "alertasNormativas":[],
             "recomendacion":"Conviene presentarse."}""";

    /** Envuelve un contenido en la forma de respuesta que espera el cliente de OpenAI. */
    public static String respuestaDeChat(String contenido) {
        return """
                {"id":"chatcmpl-prueba","object":"chat.completion","created":1,
                 "model":"gpt-4.1-mini",
                 "choices":[{"index":0,"role":"assistant",
                             "message":{"role":"assistant","content":%s},
                             "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
                .formatted(comoCadenaJson(contenido));
    }

    private static String comoCadenaJson(String texto) {
        return '"' + texto.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ") + '"';
    }

    private WireMockServer servidor;

    @Override
    public Map<String, String> start() {
        servidor = new WireMockServer(WireMockConfiguration.options().port(PUERTO));
        servidor.start();
        // La clave es falsa a propósito y la URL apunta aquí: si algún día una de las dos
        // cosas dejara de aplicarse, la prueba llamaría a OpenAI de verdad y lo pagaría
        // alguien. Es el mismo riesgo que vigila AislamientoDePruebasTest.
        return Map.of(
                "agente.ia.openai.api-key", "clave-de-prueba",
                "agente.ia.openai.base-url", "http://localhost:" + PUERTO + "/v1",
                "agente.ia.proveedor-por-defecto", "openai");
    }

    @Override
    public void stop() {
        if (servidor != null) {
            servidor.stop();
        }
    }

    @Override
    public void inject(TestInjector inyector) {
        inyector.injectIntoFields(
                servidor,
                new TestInjector.AnnotatedAndMatchesType(
                        InyectarModeloFalso.class, WireMockServer.class));
    }
}
