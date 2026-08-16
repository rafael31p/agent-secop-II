package co.agentesecop.config;

import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Map;

/**
 * Autenticación por clave de API y límites de consumo.
 *
 * <p>No es OIDC a propósito: no hay usuarios ni organización todavía, y montar un
 * proveedor de identidad para una herramienta de equipo es desproporcionado. La clave es
 * reversible y se sustituye por OIDC cuando exista la necesidad —el día que haga falta
 * saber <em>quién</em> analizó un pliego y no solo con qué clave—.
 *
 * <p>{@code @StaticInitSafe} no es decorativo: quien consume este mapeo es
 * {@code FiltroClaveApi}, un {@code @Provider} de JAX-RS que se instancia durante el
 * arranque estático. Sin la anotación, el mapeo se registra solo para la fase de
 * ejecución y la aplicación no levanta, con un {@code SRCFG00027: Could not find a
 * mapping} que no dice nada de la causa. El síntoma es desconcertante: dejan de arrancar
 * <em>todas</em> las pruebas de Quarkus, incluidas las que no tocan seguridad.
 */
@StaticInitSafe
@ConfigMapping(prefix = "agente.seguridad")
public interface ConfiguracionSeguridad {

    /**
     * Claves admitidas, como {@code nombre -> sha256:<hash hexadecimal>}.
     *
     * <p>Se almacenan hashes y no claves: quien lea la configuración del servidor no
     * obtiene con qué llamar. Para generar una:
     *
     * <pre>
     *   openssl rand -hex 32 | tee /dev/stderr | tr -d '
' | sha256sum
     * </pre>
     */
    Map<String, String> apiKeys();

    @WithDefault("true")
    boolean autenticacionRequerida();

    Limites limites();

    /**
     * Peticiones por clave y por hora.
     *
     * <p>Los valores son un punto de partida deliberadamente holgado, pensados para el
     * uso real de un analista —unos pocos pliegos al día—, no para acotar al milímetro.
     * Se ajustan cuando haya métricas.
     */
    interface Limites {

        @WithDefault("20")
        int analisis();

        @WithDefault("20")
        int propuesta();

        /** Menor que los demás porque puede costar dos llamadas al modelo. */
        @WithDefault("15")
        int validacion();

        @WithDefault("40")
        int relevancia();

        @WithDefault("100")
        int chat();

        /** Gratis para nosotros; el límite existe para no maltratar a Socrata. */
        @WithDefault("300")
        int busqueda();

        @WithDefault("60")
        int documento();
    }
}
