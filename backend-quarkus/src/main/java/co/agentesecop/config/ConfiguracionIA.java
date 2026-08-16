package co.agentesecop.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/**
 * Configuración de los proveedores de IA.
 *
 * <p>Cada proveedor tiene su propia clave y modelo por defecto. Un proveedor sin clave
 * queda simplemente «no configurado»: la aplicación arranca igual y lo reporta en
 * {@code /api/proveedores}, en vez de fallar al inicio.
 */
@ConfigMapping(prefix = "agente.ia")
public interface ConfiguracionIA {

    /** Proveedor usado cuando la petición no especifica uno. */
    @WithDefault("gemini")
    String proveedorPorDefecto();

    /** Tope de tokens de salida. En modelos con razonamiento, lo incluye. */
    @WithDefault("32000")
    int maxTokens();

    /** Baja a propósito: el trabajo es extracción y verificación, no redacción libre. */
    @WithDefault("0.2")
    double temperatura();

    /** Segundos de espera antes de abortar una llamada al modelo. */
    @WithDefault("300")
    int timeoutSegundos();

    /** Intentos totales ante fallos transitorios (429, 5xx) del proveedor. */
    @WithDefault("3")
    int intentosMaximos();

    /** Espera base del retroceso exponencial entre reintentos, en milisegundos. */
    @WithDefault("2000")
    long esperaBaseMillis();

    /** Registra las peticiones al proveedor. Útil en desarrollo, ruidoso en producción. */
    @WithDefault("false")
    boolean registrarPeticiones();

    Gemini gemini();

    OpenAI openai();

    Anthropic anthropic();

    DeepSeek deepseek();

    Ollama ollama();

    interface Gemini {
        Optional<String> apiKey();

        @WithDefault("gemini-3.6-flash")
        String modeloPorDefecto();
    }

    interface OpenAI {
        Optional<String> apiKey();

        @WithDefault("gpt-4.1-mini")
        String modeloPorDefecto();

        Optional<String> baseUrl();
    }

    interface Anthropic {
        Optional<String> apiKey();

        @WithDefault("claude-sonnet-4-6")
        String modeloPorDefecto();
    }

    /** API compatible con la de OpenAI; solo cambia la URL base. */
    interface DeepSeek {
        Optional<String> apiKey();

        @WithDefault("deepseek-chat")
        String modeloPorDefecto();

        @WithDefault("https://api.deepseek.com/v1")
        String baseUrl();
    }

    /** Local: no necesita clave, pero sí que el servidor esté levantado. */
    interface Ollama {
        @WithDefault("http://localhost:11434")
        String baseUrl();

        @WithDefault("llama3.1")
        String modeloPorDefecto();

        /** Se marca como configurado solo si se habilita explícitamente. */
        @WithDefault("false")
        boolean habilitado();
    }
}
