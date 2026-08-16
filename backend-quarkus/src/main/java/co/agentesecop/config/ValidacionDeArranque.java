package co.agentesecop.config;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Comprobaciones que deben hacer fallar el arranque en producción.
 *
 * <h2>Por qué existe esta clase y no basta con la configuración</h2>
 *
 * <p>La intuición razonable es que dejar {@code %prod.quarkus.http.cors.origins=${AGENTE_CORS_ORIGINS}}
 * baste: sin la variable, la expresión no resuelve y el arranque falla. Se comprobó
 * ejecutando el jar empaquetado con el perfil {@code prod} y sin la variable, y
 * <strong>arranca sin protestar</strong>: la propiedad es opcional, así que una expresión
 * sin valor se resuelve como ausente en lugar de romper.
 *
 * <p>El resultado sería un servicio en producción con CORS habilitado y ningún origen
 * autorizado —o peor, la ilusión de que está configurado—. De ahí esta comprobación
 * explícita.
 *
 * <p>Se usa {@link LaunchMode#NORMAL} y no el nombre del perfil porque describe justo lo
 * que interesa: la aplicación arrancó como servicio, no en desarrollo ni en pruebas.
 */
@ApplicationScoped
public class ValidacionDeArranque {

    private static final Logger LOG = Logger.getLogger(ValidacionDeArranque.class);

    @ConfigProperty(name = "quarkus.http.cors.origins")
    Optional<List<String>> origenesCors;

    private final ConfiguracionSeguridad seguridad;

    ValidacionDeArranque(ConfiguracionSeguridad seguridad) {
        this.seguridad = seguridad;
    }

    void alArrancar(@Observes StartupEvent evento) {
        if (LaunchMode.current() != LaunchMode.NORMAL) {
            return;
        }
        exigirOrigenesCors();
        exigirClavesDeApi();
    }

    private void exigirClavesDeApi() {
        if (!seguridad.autenticacionRequerida()) {
            throw new IllegalStateException(
                    "La autenticación está desactivada en producción. Eso deja los "
                            + "endpoints que llaman al modelo abiertos a cualquiera con "
                            + "ruta de red, gastando el presupuesto de tokens del "
                            + "operador. Quita agente.seguridad.autenticacion-requerida "
                            + "o ponla en true.");
        }
        if (seguridad.apiKeys().isEmpty()) {
            throw new IllegalStateException("""
                    No hay ninguna clave de API configurada.

                    Sin claves, la autenticación rechazaría todas las peticiones y el \
                    servicio no serviría para nada. Configura al menos una:

                        AGENTE_SEGURIDAD_API_KEYS_EQUIPO=sha256:<hash de la clave>

                    El hash se genera con:

                        openssl rand -hex 32 | tee /dev/stderr | tr -d '\\n' | sha256sum""");
        }
        LOG.infof("Autenticación por clave activa para %d cliente(s): %s",
                seguridad.apiKeys().size(),
                String.join(", ", seguridad.apiKeys().keySet()));
    }

    private void exigirOrigenesCors() {
        boolean vacio = origenesCors.isEmpty()
                || origenesCors.get().stream().allMatch(String::isBlank);
        if (vacio) {
            throw new IllegalStateException("""
                    Falta AGENTE_CORS_ORIGINS.

                    En producción hay que declarar qué orígenes pueden llamar a este \
                    servicio, por ejemplo:

                        AGENTE_CORS_ORIGINS=https://tu-frontend.example.com

                    Se prefiere no arrancar a arrancar sin saberlo. Ojo: CORS lo aplica el \
                    navegador y no es un control de acceso; quien protege los endpoints \
                    que gastan dinero es la clave de API.""");
        }
        LOG.infof("CORS autorizado para: %s", String.join(", ", origenesCors.get()));
    }
}
