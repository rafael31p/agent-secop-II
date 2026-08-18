package co.agentesecop;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Garantiza que la prueba corra sin credenciales de ningún proveedor.
 *
 * <p>Hace falta un perfil explícito y no basta con {@code %test} en
 * {@code application.properties}: Quarkus lee el archivo {@code .env} del proyecto en
 * todos los perfiles y con mayor prioridad, así que en la máquina de quien sí tiene
 * claves configuradas las pruebas se comportaban distinto que en integración continua.
 * Los valores de un perfil de prueba se aplican como propiedades de sistema, que sí
 * ganan al {@code .env}.
 *
 * <p><b>Ya no es obligatorio recordarlo.</b> {@code pruebas.AislamientoDePruebas} hace lo
 * mismo por defecto para todas las clases, y {@code AislamientoDePruebasTest} vigila que
 * siga siendo cierto. Este perfil se conserva porque además fija el proveedor por defecto
 * y porque declarar la intención en la clase sigue siendo más legible que deducirla.
 */
public class PerfilSinCredenciales implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "agente.ia.gemini.api-key", "",
                "agente.ia.openai.api-key", "",
                "agente.ia.anthropic.api-key", "",
                "agente.ia.deepseek.api-key", "",
                "agente.ia.ollama.habilitado", "false",
                "agente.ia.proveedor-por-defecto", "gemini");
    }
}
