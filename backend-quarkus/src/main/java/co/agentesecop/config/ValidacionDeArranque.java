package co.agentesecop.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import co.agentesecop.ia.ProveedorLangChain4j;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.util.IOUtils;
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
 * <h2>Por qué mira el perfil y no el modo de arranque</h2>
 *
 * <p>La primera versión usaba {@code LaunchMode.NORMAL}, que parece más honesto: describe
 * que la aplicación arrancó como servicio. Pero las excepciones que estas comprobaciones
 * vigilan están escritas como {@code %dev.} y {@code %test.} en la configuración, y esas
 * <em>sí</em> miran el perfil. Al ejecutar el jar empaquetado con {@code -Dquarkus.profile=dev}
 * los dos criterios se contradecían: la configuración desactivaba la autenticación y el
 * guardián se negaba a arrancar acusando a producción de algo que nadie había pedido.
 *
 * <p>Ahora las dos cosas miran lo mismo. Si un día se despliega con el perfil {@code dev},
 * lo que hay que arreglar es el despliegue.
 */
@ApplicationScoped
public class ValidacionDeArranque {

    private static final Logger LOG = Logger.getLogger(ValidacionDeArranque.class);

    /** Por debajo de esto, lo que entra parece una bomba y no un documento. */
    private static final double RATIO_MINIMO_DE_COMPRESION = 0.02;

    /** Tope por entrada descomprimida. Un pliego con imagenes cabe de sobra. */
    private static final long TAMANO_MAXIMO_DESCOMPRIMIDO = 80L * 1024 * 1024;

    @ConfigProperty(name = "quarkus.http.cors.origins")
    Optional<List<String>> origenesCors;

    /** Perfil activo. Quarkus lo publica bajo este nombre. */
    @ConfigProperty(name = "quarkus.profile", defaultValue = "prod")
    String perfil;

    private final ConfiguracionSeguridad seguridad;
    private final ConfiguracionIA ia;

    ValidacionDeArranque(ConfiguracionSeguridad seguridad, ConfiguracionIA ia) {
        this.seguridad = seguridad;
        this.ia = ia;
    }

    void alArrancar(@Observes StartupEvent evento) {
        // Las cotas de los analizadores se aplican en TODOS los perfiles: un archivo
        // hostil no es menos hostil en desarrollo, y una prueba que no las tenga puestas
        // no estaria probando lo que se despliega.
        acotarFormatosComprimidos();
        if ("dev".equals(perfil) || "test".equals(perfil)) {
            return;
        }
        exigirOrigenesCors();
        exigirClavesDeApi();
        prohibirVolcadoDePliegos();
    }

    /**
     * Cotas para lo que llega comprimido (SEC-3).
     *
     * <p>Un DOCX es un ZIP, y con los valores por defecto de POI 25 MB comprimidos
     * pueden expandirse al orden de gigabytes. El mamparo de la extraccion acota
     * <em>cuantas</em> extracciones corren a la vez, no <em>cuanta</em> memoria consume
     * cada una: son dos cotas distintas y hacian falta las dos.
     *
     * <p>Ratio 0,02 y entrada maxima de 80 MB: holgado para un pliego real con imagenes
     * escaneadas, cerrado para una bomba de descompresion. Superarlo produce un error de
     * POI que el extractor traduce a 415 con mensaje util, no un fallo de memoria que se
     * lleva por delante al resto de peticiones del proceso.
     */
    private void acotarFormatosComprimidos() {
        ZipSecureFile.setMinInflateRatio(RATIO_MINIMO_DE_COMPRESION);
        ZipSecureFile.setMaxEntrySize(TAMANO_MAXIMO_DESCOMPRIMIDO);
        ZipSecureFile.setMaxTextSize(ProveedorLangChain4j.LIMITE_CARACTERES);
        IOUtils.setByteArrayMaxOverride((int) TAMANO_MAXIMO_DESCOMPRIMIDO);
        LOG.debugf("Cotas de descompresion: ratio %s, entrada %d MB",
                RATIO_MINIMO_DE_COMPRESION, TAMANO_MAXIMO_DESCOMPRIMIDO / (1024 * 1024));
    }

    /**
     * El registro no puede tragarse un pliego (SEC-8).
     *
     * <p>{@code registrar-peticiones} vuelca al registro el texto integro del pliego y de
     * la propuesta. En desarrollo es util; en produccion convierte el archivo de registro
     * en un deposito de material confidencial de un cliente, con otra politica de acceso,
     * otra retencion y probablemente otro proveedor. Nada lo impedia.
     */
    private void prohibirVolcadoDePliegos() {
        if (!ia.registrarPeticiones()) {
            return;
        }
        throw new IllegalStateException("""
                agente.ia.registrar-peticiones=true vuelca el texto integro del pliego y \n                de la propuesta al registro. En produccion eso convierte el registro en un \n                archivo de material confidencial, con otra politica de acceso y otra \n                retencion que las previstas (ver SPEC-NT-02). Desactivalo, o usa el perfil \n                de desarrollo si lo que quieres es depurar.""");
    }

    private void exigirClavesDeApi() {
        if (!seguridad.autenticacionRequerida()) {
            throw new IllegalStateException(
                    "La autenticación está desactivada con el perfil '" + perfil
                            + "'. Eso deja los "
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
