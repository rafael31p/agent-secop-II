package co.agentesecop.ia;

import co.agentesecop.config.ConfiguracionIA;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.List;

/** Google Gemini, vía la API de Google AI Studio. */
@Singleton
public class ProveedorGemini extends ProveedorLangChain4j {

    @Inject
    public ProveedorGemini(ConfiguracionIA config, ObjectMapper jackson) {
        super(config, jackson);
    }

    @Override
    public String nombre() {
        return "gemini";
    }

    @Override
    public String etiqueta() {
        return "Google Gemini";
    }

    @Override
    public boolean configurado() {
        return config.gemini().apiKey().filter(k -> !k.isBlank()).isPresent();
    }

    @Override
    public String motivoNoDisponible() {
        return configurado()
                ? null
                : "Falta AGENTE_IA_GEMINI_API_KEY. Obtén una clave gratuita en "
                        + "https://aistudio.google.com/apikey";
    }

    @Override
    public String modeloPorDefecto() {
        return config.gemini().modeloPorDefecto();
    }

    /**
     * Verificados contra la API en agosto de 2026. Los modelos {@code pro} exigen plan
     * de pago y los {@code 2.5} ya no se sirven a claves nuevas.
     */
    @Override
    public List<String> modelosSugeridos() {
        return List.of(
                "gemini-3.6-flash",
                "gemini-3.5-flash",
                "gemini-3.5-flash-lite",
                "gemini-3.1-flash-lite",
                "gemini-flash-latest",
                "gemini-3.1-pro-preview");
    }

    @Override
    protected ChatModel construirModelo(String nombreModelo) {
        return GoogleAiGeminiChatModel.builder()
                // Sin reintento propio del cliente, a propósito: la política la declara
                // `adapter.out.llm.PoliticaDeResiliencia` y tenerla también aquí las
                // MULTIPLICA en vez de sumarlas. Se descubrió contando peticiones contra
                // un proveedor falso: dos intentos declarados daban seis llamadas, porque
                // LangChain4j reintenta tres veces por su cuenta. Es la misma trampa que
                // avisaba SPEC-BE-02 para la transición, escondida en la biblioteca.
                .maxRetries(0)
                .apiKey(config.gemini().apiKey().orElseThrow())
                .modelName(nombreModelo)
                .temperature(config.temperatura())
                .maxOutputTokens(config.maxTokens())
                .timeout(Duration.ofSeconds(config.timeoutSegundos()))
                .logRequestsAndResponses(config.registrarPeticiones())
                .build();
    }

    @Override
    protected StreamingChatModel construirModeloFlujo(String nombreModelo) {
        return GoogleAiGeminiStreamingChatModel.builder()
                .apiKey(config.gemini().apiKey().orElseThrow())
                .modelName(nombreModelo)
                .temperature(config.temperatura())
                .maxOutputTokens(config.maxTokens())
                .timeout(Duration.ofSeconds(config.timeoutSegundos()))
                .logRequestsAndResponses(config.registrarPeticiones())
                .build();
    }
}
