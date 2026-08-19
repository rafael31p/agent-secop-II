package co.agentesecop.adapter.in.rest.error;

import co.agentesecop.adapter.in.rest.FiltroCorrelacion;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import org.jboss.logging.Logger;

/**
 * El proceso de respuesta a un error, definido una sola vez.
 *
 * <p>Cada mapeador concreto aporta dos datos —el estado HTTP y, si hace falta, cabeceras—
 * y hereda todo lo demás: el cuerpo uniforme, el identificador de correlación y la regla
 * de qué se registra frente a qué se devuelve.
 *
 * <p>Esa regla es la razón de que la clase exista y no se repita en cada mapeador. Al
 * cliente va <b>solo</b> el mensaje que escribió nuestro código; el detalle técnico —la
 * excepción del proveedor, sus causas, la URL de la petición— va al registro y solo al
 * registro. La API de Google AI lleva la clave en la cadena de consulta: basta con que una
 * versión de la biblioteca cliente incluya la URL en el texto de una excepción para
 * publicar la credencial del operador en el navegador de cualquier usuario.
 *
 * <p>El identificador ya no se pinta en el texto del mensaje: el formato del registro lo
 * inserta desde el contexto ({@code %X{correlationId}}), de modo que aparece también en
 * las líneas que escribe cualquier otra clase durante la misma petición.
 *
 * @param <E> el tipo concreto que este mapeador atiende
 */
public abstract class MapeadorDeError<E extends Throwable> implements ExceptionMapper<E> {

    private static final Logger LOG = Logger.getLogger(MapeadorDeError.class);

    /** Qué código HTTP corresponde. Es lo único que distingue a la mayoría. */
    protected abstract int estado();

    /**
     * Con qué severidad se registra. Un 4xx es normalmente culpa de la petición y no
     * merece un ERROR que despierte a nadie; un fallo del proveedor sí.
     */
    protected Logger.Level severidad() {
        return estado() >= 500 ? Logger.Level.ERROR : Logger.Level.WARN;
    }

    /** Cabeceras extra. Por defecto ninguna; la saturación añade {@code Retry-After}. */
    protected void decorar(Response.ResponseBuilder respuesta) {
        // Sin decoración por defecto.
    }

    /**
     * Lo que ve el usuario. Por defecto, el mensaje de la excepción, que en esta jerarquía
     * lo redacta nuestro código y es seguro por construcción. Se sobrescribe cuando el
     * mensaje original no se puede publicar.
     */
    protected String detalle(E error) {
        return error.getMessage();
    }

    @Override
    public final Response toResponse(E error) {
        String identificador = FiltroCorrelacion.actual();
        LOG.logf(severidad(), error, "%s (HTTP %d)",
                error.getClass().getSimpleName(), estado());

        Response.ResponseBuilder respuesta = Response.status(estado())
                .entity(new CuerpoDeError(detalle(error), identificador));
        decorar(respuesta);
        return respuesta.build();
    }
}
