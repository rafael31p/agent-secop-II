package co.agentesecop.ia;

import co.agentesecop.config.ConfiguracionIA;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.List;

/**
 * OpenAI.
 *
 * <p>Es también la base de {@link ProveedorDeepSeek}: DeepSeek expone una API compatible
 * con la de OpenAI, así que solo cambian la clave, la URL base y los modelos.
 */
@Singleton
public class ProveedorOpenAI extends ProveedorLangChain4j {

    @Inject
    public ProveedorOpenAI(ConfiguracionIA config, ObjectMapper jackson) {
        super(config, jackson);
    }

    @Override
    public String nombre() {
        return "openai";
    }

    @Override
    public String etiqueta() {
        return "OpenAI";
    }

    @Override
    public boolean configurado() {
        return claveApi().filter(k -> !k.isBlank()).isPresent();
    }

    @Override
    public String motivoNoDisponible() {
        return configurado()
                ? null
                : "Falta AGENTE_IA_OPENAI_API_KEY. Obtén una clave en "
                        + "https://platform.openai.com/api-keys";
    }

    @Override
    public String modeloPorDefecto() {
        return config.openai().modeloPorDefecto();
    }

    @Override
    public List<String> modelosSugeridos() {
        return List.of("gpt-4.1-mini", "gpt-4.1", "gpt-4o-mini", "gpt-4o", "o4-mini");
    }

    protected java.util.Optional<String> claveApi() {
        return config.openai().apiKey();
    }

    protected String urlBase() {
        return config.openai().baseUrl().orElse(null);
    }

    /**
     * {@code strictJsonSchema} activa la modalidad estricta de OpenAI, que garantiza que
     * la salida cumpla el esquema en vez de solo aproximarse a él.
     */
    @Override
    protected ChatModel construirModelo(String nombreModelo) {
        var constructor = OpenAiChatModel.builder()
                .apiKey(claveApi().orElseThrow())
                .modelName(nombreModelo)
                .temperature(config.temperatura())
                .maxTokens(config.maxTokens())
                .strictJsonSchema(true)
                .timeout(Duration.ofSeconds(config.timeoutSegundos()))
                .logRequests(config.registrarPeticiones());
        if (urlBase() != null) {
            constructor.baseUrl(urlBase());
        }
        return constructor.build();
    }

    @Override
    protected StreamingChatModel construirModeloFlujo(String nombreModelo) {
        var constructor = OpenAiStreamingChatModel.builder()
                .apiKey(claveApi().orElseThrow())
                .modelName(nombreModelo)
                .temperature(config.temperatura())
                .maxTokens(config.maxTokens())
                .timeout(Duration.ofSeconds(config.timeoutSegundos()))
                .logRequests(config.registrarPeticiones());
        if (urlBase() != null) {
            constructor.baseUrl(urlBase());
        }
        return constructor.build();
    }
}
