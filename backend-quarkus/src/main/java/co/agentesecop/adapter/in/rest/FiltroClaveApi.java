package co.agentesecop.adapter.in.rest;

import co.agentesecop.adapter.in.rest.error.CuerpoDeError;
import co.agentesecop.config.ConfiguracionSeguridad;
import co.agentesecop.domain.shared.Texto;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Exige una clave de API en los endpoints que gastan dinero, y limita su consumo.
 *
 * <h2>El problema que cierra</h2>
 *
 * <p>Cinco endpoints llamaban al modelo con la clave del operador y sin ninguna
 * comprobación de identidad. CORS estaba configurado, pero CORS no es un control de
 * acceso: es una política que aplica el navegador y que {@code curl} ignora por completo.
 * Cualquiera con ruta de red al puerto podía consumir el presupuesto de tokens, sin
 * límite y sin dejar constancia de quién fue.
 *
 * <h2>Decisiones que parecen detalles y no lo son</h2>
 *
 * <ul>
 *   <li><b>Se comparan hashes, no claves.</b> La configuración del servidor lleva
 *       {@code sha256:…}; quien la lea no obtiene con qué llamar.
 *   <li><b>La comparación es de tiempo constante</b> ({@link MessageDigest#isEqual}).
 *       Comparar secretos con igualdad de cadenas filtra información por el tiempo que
 *       tarda en encontrar la primera diferencia.
 *   <li><b>{@code /api/salud} y {@code /api/proveedores} quedan abiertos.</b> El frontend
 *       los consulta antes de tener contexto de usuario y no gastan dinero. Es una
 *       decisión consciente, no un olvido.
 * </ul>
 *
 * <p>Limitación conocida del esquema: una clave en una cabecera puede acabar registrada
 * por un proxy intermedio. Es el motivo por el que OIDC es el destino, no este filtro.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class FiltroClaveApi implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(FiltroClaveApi.class);
    private static final String CABECERA = "X-Api-Key";
    private static final String PREFIJO_HASH = "sha256:";

    /** Nombre del cliente autenticado, para que los recursos puedan consultarlo. */
    public static final String CLIENTE = "clienteApi";

    private final ConfiguracionSeguridad config;
    private final LimitadorDeTasa limitador;

    @Inject
    public FiltroClaveApi(ConfiguracionSeguridad config, LimitadorDeTasa limitador) {
        this.config = config;
        this.limitador = limitador;
    }

    @Override
    public void filter(ContainerRequestContext contexto) {
        String ruta = contexto.getUriInfo().getPath();
        Operacion operacion = Operacion.de(ruta);
        if (operacion == null || !config.autenticacionRequerida()) {
            return;
        }

        String cliente = identificar(contexto.getHeaderString(CABECERA));
        if (cliente == null) {
            // La ruta la normaliza el contenedor, pero registrarla sin sanear deja el
            // único punto por el que un valor de la petición entra al registro sin filtro.
            LOG.warnf("Petición sin clave válida a %s", Texto.paraRegistro(ruta, 120));
            contexto.abortWith(Response.status(401)
                    .entity(CuerpoDeError.de(
                            "Falta la cabecera X-Api-Key o su valor no es válido."))
                    .build());
            return;
        }

        int limite = operacion.limite(config.limites());
        var veredicto = limitador.consumir(cliente, operacion.name(), limite);
        if (!veredicto.permitido()) {
            LOG.infof("Límite alcanzado por '%s' en %s (%d/hora)",
                    cliente, operacion.name(), limite);
            contexto.abortWith(Response.status(429)
                    .header("Retry-After", veredicto.segundosParaReintentar())
                    .entity(CuerpoDeError.de(
                            ("Alcanzaste el límite de %d peticiones por hora para esta "
                                    + "operación. Vuelve a intentarlo en %d minutos.")
                                    .formatted(limite,
                                            Math.max(1, veredicto.segundosParaReintentar() / 60))))
                    .build());
            return;
        }

        contexto.setProperty(CLIENTE, cliente);
    }

    /** Devuelve el nombre del cliente cuya clave coincide, o {@code null}. */
    private String identificar(String presentada) {
        if (presentada == null || presentada.isBlank()) {
            return null;
        }
        byte[] hashPresentado = sha256(presentada.trim());
        String encontrado = null;
        for (Map.Entry<String, String> admitida : config.apiKeys().entrySet()) {
            byte[] esperado = decodificar(admitida.getValue());
            // Se recorren todas las claves aunque ya haya coincidencia: salir antes
            // haría que el tiempo de respuesta revelara la posición de la clave.
            if (esperado != null && MessageDigest.isEqual(hashPresentado, esperado)) {
                encontrado = admitida.getKey();
            }
        }
        return encontrado;
    }

    private static byte[] decodificar(String configurada) {
        String valor = configurada == null ? "" : configurada.trim();
        if (!valor.startsWith(PREFIJO_HASH)) {
            LOG.warnf("Clave de API mal configurada: debe empezar por '%s'.", PREFIJO_HASH);
            return null;
        }
        try {
            return HexFormat.of().parseHex(valor.substring(PREFIJO_HASH.length()));
        } catch (IllegalArgumentException e) {
            LOG.warn("Clave de API mal configurada: el hash no es hexadecimal válido.");
            return null;
        }
    }

    static byte[] sha256(String texto) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(texto.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 debería existir en toda JVM", e);
        }
    }

    /**
     * Operaciones sujetas a clave, con su límite. Lo que no aparece aquí queda abierto:
     * la salud, el catálogo de proveedores y la documentación.
     */
    private enum Operacion {
        ANALISIS("/analisis/requisitos"),
        DOCUMENTO("/analisis/documento"),
        PROPUESTA("/propuestas/generar"),
        VALIDACION("/propuestas/validar"),
        RELEVANCIA("/procesos/relevancia-ti"),
        BUSQUEDA("/procesos/buscar"),
        CHAT("/chat");

        private final String sufijoRuta;

        Operacion(String sufijoRuta) {
            this.sufijoRuta = sufijoRuta;
        }

        static Operacion de(String ruta) {
            String normalizada = ruta.startsWith("/") ? ruta : "/" + ruta;
            for (Operacion operacion : values()) {
                if (normalizada.endsWith(operacion.sufijoRuta)) {
                    return operacion;
                }
            }
            return null;
        }

        int limite(ConfiguracionSeguridad.Limites limites) {
            return switch (this) {
                case ANALISIS -> limites.analisis();
                case DOCUMENTO -> limites.documento();
                case PROPUESTA -> limites.propuesta();
                case VALIDACION -> limites.validacion();
                case RELEVANCIA -> limites.relevancia();
                case BUSQUEDA -> limites.busqueda();
                case CHAT -> limites.chat();
            };
        }
    }
}
