package co.agentesecop.ia;

import co.agentesecop.config.ConfiguracionIA;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Optional;

/**
 * DeepSeek.
 *
 * <p>Su API es compatible con la de OpenAI, así que hereda toda la implementación y solo
 * cambia clave, URL base y modelos. Este ahorro es exactamente el beneficio de haber
 * definido {@link ProveedorIA} como interfaz propia en lugar de atarse a un SDK.
 */
@Singleton
public class ProveedorDeepSeek extends ProveedorOpenAI {

    @Inject
    public ProveedorDeepSeek(
            ConfiguracionIA config, ObjectMapper jackson, CierreDiferido cierreDiferido) {
        super(config, jackson, cierreDiferido);
    }

    @Override
    public String nombre() {
        return "deepseek";
    }

    @Override
    public String etiqueta() {
        return "DeepSeek";
    }

    @Override
    public String motivoNoDisponible() {
        return configurado()
                ? null
                : "Falta AGENTE_IA_DEEPSEEK_API_KEY. Obtén una clave en "
                        + "https://platform.deepseek.com";
    }

    @Override
    public String modeloPorDefecto() {
        return config.deepseek().modeloPorDefecto();
    }

    @Override
    public List<String> modelosSugeridos() {
        return List.of("deepseek-chat", "deepseek-reasoner");
    }

    @Override
    protected Optional<String> claveApi() {
        return config.deepseek().apiKey();
    }

    @Override
    protected String urlBase() {
        return config.deepseek().baseUrl();
    }
}
