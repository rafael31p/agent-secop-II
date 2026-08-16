package co.agentesecop.secop;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/**
 * Simula la API de datos.gov.co en la misma JVM.
 *
 * <p>Se usa WireMock como librería y no Testcontainers porque el entorno de desarrollo no
 * tiene Docker. El puerto coincide con el configurado en el perfil {@code %test}.
 */
public class ServidorSecopFalso implements QuarkusTestResourceLifecycleManager {

    public static final int PUERTO = 8089;

    private WireMockServer servidor;

    @Override
    public Map<String, String> start() {
        servidor = new WireMockServer(WireMockConfiguration.options().port(PUERTO));
        servidor.start();
        return Map.of("quarkus.rest-client.secop.url", "http://localhost:" + PUERTO);
    }

    @Override
    public void stop() {
        if (servidor != null) {
            servidor.stop();
        }
    }

    /** Expone el servidor a la prueba para que defina sus propios escenarios. */
    @Override
    public void inject(TestInjector inyector) {
        inyector.injectIntoFields(
                servidor,
                new TestInjector.AnnotatedAndMatchesType(InyectarSecopFalso.class,
                        WireMockServer.class));
    }
}
