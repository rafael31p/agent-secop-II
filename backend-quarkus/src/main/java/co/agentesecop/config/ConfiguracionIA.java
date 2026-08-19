package co.agentesecop.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
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

    /**
     * Segundos de espera del cliente HTTP antes de abortar una llamada al modelo.
     *
     * <p>Es la última red, no el presupuesto: quien acota de verdad la llamada es el
     * {@code @Timeout} de {@code ModeloDeLenguajeResiliente}, y quien acota la petición
     * completa es el del caso de uso. Este valor se deja por encima de ambos para que el
     * que corte sea siempre el de Fault Tolerance, que sabe reintentar y contar métricas.
     *
     * <p>Aquí vivían también {@code intentosMaximos} y {@code esperaBaseMillis}. Ya no:
     * la política de reintentos es configuración de MicroProfile Fault Tolerance
     * ({@code …/Retry/maxRetries}) y tenerla además aquí habría dejado dos fuentes de
     * verdad para el mismo número.
     */
    @WithDefault("300")
    int timeoutSegundos();

    /**
     * Cuánto se espera antes de cerrar un cliente expulsado de la caché de modelos.
     *
     * <p>Por encima del {@code @Timeout} por intento, con margen: expulsar de una caché no
     * es lo mismo que dejar de usar, y la referencia que la caché ya entregó puede estar en
     * mitad de una llamada. Ver {@code ia.CierreDiferido}.
     */
    @WithDefault("PT3M")
    Duration graciaDeCierre();

    /**
     * Cuántas conversaciones de chat pueden estar abiertas a la vez.
     *
     * <p>Ocho es mucho para el uso previsto. Se calibra con la métrica
     * {@code llm.conversaciones.disponibles}, no por intuición.
     */
    @WithDefault("8")
    int maximoConversacionesSimultaneas();

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
