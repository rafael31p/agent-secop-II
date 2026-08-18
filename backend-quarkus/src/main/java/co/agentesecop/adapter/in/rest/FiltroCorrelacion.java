package co.agentesecop.adapter.in.rest;

import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jboss.logging.MDC;

/**
 * Da a cada petición un identificador y lo pone donde se pueda cruzar después.
 *
 * <h2>El problema que cierra</h2>
 *
 * <p>Un usuario decía «me falló el análisis a las 3» y no había forma de encontrar esa
 * petición en el registro. El identificador viaja ahora por tres sitios a la vez: la
 * cabecera de respuesta, el cuerpo del error y el contexto de registro, de modo que quien
 * reporta un fallo trae consigo la llave para encontrarlo.
 *
 * <p>Es también lo que hace <em>viable</em> no contarle al usuario qué pasó exactamente.
 * Se puede devolver un mensaje genérico —y hay que hacerlo: los errores de proveedor
 * pueden arrastrar la clave de API en la URL— precisamente porque el identificador permite
 * recuperar el detalle completo en el registro.
 *
 * <h2>Por qué se valida el valor entrante</h2>
 *
 * <p>Se acepta el identificador del cliente para poder cruzar el rastro de extremo a
 * extremo, pero aceptarlo tal cual y escribirlo en el registro es inyección de registros:
 * bastaría un salto de línea para fabricar una entrada falsa, o un valor de diez mil
 * caracteres para inundarlo. Lo que no encaje en la forma admitida se descarta en silencio
 * y se genera uno nuevo.
 */
@Provider
@PreMatching
@Priority(1)
public class FiltroCorrelacion implements ContainerRequestFilter, ContainerResponseFilter {

    public static final String CABECERA = "X-Correlation-Id";

    /** Clave en el contexto de registro; aparece en cada línea si el formato la incluye. */
    public static final String CLAVE_MDC = "correlationId";

    /** Ni saltos de línea, ni espacios, ni longitudes absurdas. */
    private static final Pattern FORMA_ADMITIDA = Pattern.compile("[A-Za-z0-9\\-]{1,64}");

    @Override
    public void filter(ContainerRequestContext peticion) {
        String recibido = peticion.getHeaderString(CABECERA);
        String identificador = admisible(recibido) ? recibido.trim() : nuevo();
        MDC.put(CLAVE_MDC, identificador);
        peticion.setProperty(CLAVE_MDC, identificador);
    }

    @Override
    public void filter(ContainerRequestContext peticion, ContainerResponseContext respuesta) {
        Object identificador = peticion.getProperty(CLAVE_MDC);
        if (identificador != null) {
            respuesta.getHeaders().putSingle(CABECERA, identificador);
        }
        // El hilo vuelve al pool: dejar la clave puesta haría que la siguiente petición
        // que no pase por aquí se registre con el identificador de esta.
        MDC.remove(CLAVE_MDC);
    }

    /** El identificador de la petición en curso, o uno nuevo fuera de contexto HTTP. */
    public static String actual() {
        Object enContexto = MDC.get(CLAVE_MDC);
        return enContexto == null ? nuevo() : String.valueOf(enContexto);
    }

    private static boolean admisible(String valor) {
        return valor != null && FORMA_ADMITIDA.matcher(valor.trim()).matches();
    }

    /**
     * Ocho caracteres: bastan para localizarlo en el registro de este servicio y son pocos
     * para dictarlos por teléfono o pegarlos en un reporte.
     */
    private static String nuevo() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
