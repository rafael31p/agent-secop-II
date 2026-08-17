package co.agentesecop.pruebas;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Blinda el perfil de pruebas para que no pueda usar credenciales reales.
 *
 * <h2>Por qué no basta con {@code %test} en {@code application.properties}</h2>
 *
 * <p>Porque pierde. Quarkus lee el archivo {@code .env} del proyecto con prioridad 295 y
 * {@code application.properties} del classpath con 250: en la máquina de quien tiene
 * claves configuradas, las líneas {@code %test.agente.ia.*.api-key=} no anulaban nada. El
 * resultado era el peor posible, porque no fallaba: las pruebas hacían llamadas reales y
 * facturables, pasaban en local y fallaban en integración continua —o al revés—.
 *
 * <p>Esta fuente vive solo en el classpath de pruebas y declara prioridad 300, por encima
 * del {@code .env}. Los {@code @TestProfile} siguen ganando a esto, que es lo que se
 * quiere: una prueba que necesita simular una clave puede pedirla explícitamente.
 *
 * <p>Se registra por {@code META-INF/services}, así que no hay que acordarse de nada: el
 * aislamiento deja de depender de que quien escriba la siguiente clase de prueba recuerde
 * anotar un perfil.
 */
public class AislamientoDePruebas implements ConfigSource {

    /** Por encima del .env (295) y por debajo de los perfiles de prueba. */
    private static final int PRIORIDAD = 300;

    /** Las claves que jamás deben resolver a un valor real durante las pruebas. */
    public static final Set<String> CLAVES_DE_PROVEEDOR = Set.of(
            "agente.ia.gemini.api-key",
            "agente.ia.openai.api-key",
            "agente.ia.anthropic.api-key",
            "agente.ia.deepseek.api-key");

    private final Map<String, String> valores = new HashMap<>();

    public AislamientoDePruebas() {
        CLAVES_DE_PROVEEDOR.forEach(clave -> valores.put(clave, ""));
        valores.put("agente.ia.ollama.habilitado", "false");
        // El token de datos.gov.co no cuesta dinero, pero una prueba que lo use está
        // llamando a la red de verdad sin quererlo.
        valores.put("agente.secop.app-token", "");
    }

    @Override
    public Map<String, String> getProperties() {
        return Map.copyOf(valores);
    }

    @Override
    public Set<String> getPropertyNames() {
        return valores.keySet();
    }

    @Override
    public String getValue(String nombre) {
        return valores.get(nombre);
    }

    @Override
    public String getName() {
        return "aislamiento-de-pruebas";
    }

    @Override
    public int getOrdinal() {
        return PRIORIDAD;
    }
}
