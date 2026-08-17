package co.agentesecop.ia;

import co.agentesecop.config.ConfiguracionIA;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.List;

/** Anthropic Claude. */
@Singleton
public class ProveedorAnthropic extends ProveedorLangChain4j {

    @Inject
    public ProveedorAnthropic(ConfiguracionIA config, ObjectMapper jackson) {
        super(config, jackson);
    }

    @Override
    public String nombre() {
        return "anthropic";
    }

    @Override
    public String etiqueta() {
        return "Anthropic Claude";
    }

    @Override
    public boolean configurado() {
        return config.anthropic().apiKey().filter(k -> !k.isBlank()).isPresent();
    }

    @Override
    public String motivoNoDisponible() {
        return configurado()
                ? null
                : "Falta AGENTE_IA_ANTHROPIC_API_KEY. Obtén una clave en "
                        + "https://console.anthropic.com";
    }

    @Override
    public String modeloPorDefecto() {
        return config.anthropic().modeloPorDefecto();
    }

    @Override
    public List<String> modelosSugeridos() {
        return List.of(
                "claude-sonnet-4-6",
                "claude-opus-4-6",
                "claude-haiku-4-5",
                "claude-sonnet-4-5");
    }

    @Override
    protected ChatModel construirModelo(String nombreModelo) {
        return AnthropicChatModel.builder()
                // Sin reintento propio del cliente, a propósito: la política la declara
                // `adapter.out.llm.PoliticaDeResiliencia` y tenerla también aquí las
                // MULTIPLICA en vez de sumarlas. Se descubrió contando peticiones contra
                // un proveedor falso: dos intentos declarados daban seis llamadas, porque
                // LangChain4j reintenta tres veces por su cuenta. Es la misma trampa que
                // avisaba SPEC-BE-02 para la transición, escondida en la biblioteca.
                .maxRetries(0)
                .apiKey(config.anthropic().apiKey().orElseThrow())
                .modelName(nombreModelo)
                .temperature(config.temperatura())
                .maxTokens(config.maxTokens())
                .timeout(Duration.ofSeconds(config.timeoutSegundos()))
                .logRequests(config.registrarPeticiones())
                .build();
    }

    @Override
    protected StreamingChatModel construirModeloFlujo(String nombreModelo) {
        return AnthropicStreamingChatModel.builder()
                .apiKey(config.anthropic().apiKey().orElseThrow())
                .modelName(nombreModelo)
                .temperature(config.temperatura())
                .maxTokens(config.maxTokens())
                .timeout(Duration.ofSeconds(config.timeoutSegundos()))
                .logRequests(config.registrarPeticiones())
                .build();
    }
}
