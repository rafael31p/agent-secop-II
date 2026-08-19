package co.agentesecop.ia;

import co.agentesecop.config.ConfiguracionIA;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.List;

/**
 * Ollama: modelos locales, sin costo ni cuota.
 *
 * <p>No lleva clave, así que «configurado» significa que el operador lo habilitó
 * explícitamente. Sin ese interruptor aparecería siempre como disponible aunque no
 * hubiera ningún servidor levantado.
 *
 * <p>Advertencia práctica: la calidad en análisis normativo es notablemente menor que la
 * de los modelos alojados. Sirve para desarrollo y pruebas, no para decidir sobre una
 * oferta real.
 */
@Singleton
public class ProveedorOllama extends ProveedorLangChain4j {

    @Inject
    public ProveedorOllama(
            ConfiguracionIA config, ObjectMapper jackson, CierreDiferido cierreDiferido) {
        super(config, jackson, cierreDiferido);
    }

    @Override
    public String nombre() {
        return "ollama";
    }

    @Override
    public String etiqueta() {
        return "Ollama (local)";
    }

    @Override
    public boolean configurado() {
        return config.ollama().habilitado();
    }

    @Override
    public String motivoNoDisponible() {
        return configurado()
                ? null
                : "Ollama está deshabilitado. Levanta el servidor y activa "
                        + "AGENTE_IA_OLLAMA_HABILITADO=true.";
    }

    @Override
    public String modeloPorDefecto() {
        return config.ollama().modeloPorDefecto();
    }

    @Override
    public List<String> modelosSugeridos() {
        return List.of("llama3.1", "llama3.2", "qwen2.5", "mistral", "gemma2");
    }

    @Override
    protected ChatModel construirModelo(String nombreModelo) {
        return OllamaChatModel.builder()
                // Sin reintento propio del cliente, a propósito: la política la declara
                // `adapter.out.llm.PoliticaDeResiliencia` y tenerla también aquí las
                // MULTIPLICA en vez de sumarlas. Se descubrió contando peticiones contra
                // un proveedor falso: dos intentos declarados daban seis llamadas, porque
                // LangChain4j reintenta tres veces por su cuenta. Es la misma trampa que
                // avisaba SPEC-BE-02 para la transición, escondida en la biblioteca.
                .maxRetries(0)
                .baseUrl(config.ollama().baseUrl())
                .modelName(nombreModelo)
                .temperature(config.temperatura())
                .timeout(Duration.ofSeconds(config.timeoutSegundos()))
                .logRequests(config.registrarPeticiones())
                .build();
    }

    @Override
    protected StreamingChatModel construirModeloFlujo(String nombreModelo) {
        return OllamaStreamingChatModel.builder()
                .baseUrl(config.ollama().baseUrl())
                .modelName(nombreModelo)
                .temperature(config.temperatura())
                .timeout(Duration.ofSeconds(config.timeoutSegundos()))
                .logRequests(config.registrarPeticiones())
                .build();
    }
}
