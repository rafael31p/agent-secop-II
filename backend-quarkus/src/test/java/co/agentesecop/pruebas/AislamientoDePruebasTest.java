package co.agentesecop.pruebas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import co.agentesecop.config.ConfiguracionIA;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * El guardián del aislamiento: ninguna clave real durante las pruebas.
 *
 * <h2>Por qué hace falta una prueba y no basta con la configuración</h2>
 *
 * <p>Porque el aislamiento dependía de que quien escribiera la siguiente clase se acordara
 * de anotar {@code @TestProfile(PerfilSinCredenciales.class)}. Una clase que se olvidara
 * usaría la clave real de {@code backend-quarkus/.env} y haría llamadas facturables sin que
 * nadie se enterase; peor aún, pasaría en la máquina de quien tiene claves y fallaría en
 * integración continua, o al revés, que es la clase de fallo que cuesta días entender.
 *
 * <p>Esta clase no lleva perfil a propósito: comprueba el estado <em>por defecto</em>. Si
 * alguien retira {@code AislamientoDePruebas} o baja su prioridad por debajo del
 * {@code .env}, esto se pone rojo en la máquina de quien tiene claves configuradas —que es
 * exactamente donde importa que se ponga rojo—.
 */
@QuarkusTest
class AislamientoDePruebasTest {

    @Inject
    ConfiguracionIA config;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"gemini", "openai", "anthropic", "deepseek"})
    @DisplayName("Ninguna clave de proveedor resuelve a un valor real")
    void sinClavesReales(String proveedor) {
        String clave = switch (proveedor) {
            case "gemini" -> config.gemini().apiKey().orElse("");
            case "openai" -> config.openai().apiKey().orElse("");
            case "anthropic" -> config.anthropic().apiKey().orElse("");
            default -> config.deepseek().apiKey().orElse("");
        };

        // Se afirma sobre la LONGITUD y nunca sobre el valor. Al comprobar esta prueba
        // bajando la prioridad de la fuente de aislamiento, el mensaje de fallo de
        // `assertEquals("", clave)` imprimió la clave real del .env entera. Es decir: la
        // prueba que existe para que no se filtren credenciales las habría publicado en
        // el registro de integración continua el día que fallara, que es justo el día en
        // que hay una clave real que filtrar.
        assertEquals(0, clave.length(),
                "Alguna clave de %s resuelve a un valor no vacío (%d caracteres). Una clave "
                        .formatted(proveedor, clave.length())
                        + "real en el perfil de pruebas haría llamadas facturables y "
                        + "volvería el resultado de la suite dependiente de la máquina. "
                        + "Revisa co.agentesecop.pruebas.AislamientoDePruebas.");
    }

    @Test
    @DisplayName("Ollama queda deshabilitado: hablar con un servidor local también es salir")
    void ollamaDeshabilitado() {
        assertFalse(config.ollama().habilitado(),
                "Con Ollama habilitado, la suite depende de que haya —o no haya— un "
                        + "servidor levantado en la máquina de quien la ejecuta.");
    }
}
