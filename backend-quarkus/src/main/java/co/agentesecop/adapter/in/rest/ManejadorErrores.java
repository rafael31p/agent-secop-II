package co.agentesecop.adapter.in.rest;

import co.agentesecop.ia.ErroresIA;
import co.agentesecop.adapter.out.document.ExtractorDocumentos.DocumentoNoSoportado;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.stream.Collectors;
import org.eclipse.microprofile.faulttolerance.exceptions.FaultToleranceException;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Traduce las excepciones del dominio a respuestas HTTP, en un solo sitio.
 *
 * <p>Sin esto, cualquier excepción sale como «500 Internal Server Error» y el mensaje
 * explicativo (modelo inexistente, cuota agotada, filtro de contenido) nunca llega al
 * usuario. Es exactamente el defecto que tuvo la versión Python hasta que se centralizó.
 *
 * <h2>Qué sale y qué no</h2>
 *
 * <p>Al cliente solo va el mensaje que <em>escribió este código</em>. El detalle técnico
 * —la excepción del proveedor, sus causas, la URL de la petición— va al registro y solo
 * al registro, junto a un identificador que también viaja en la respuesta. Quien reporta
 * un fallo cita ese identificador y el operador encuentra la traza completa.
 *
 * <p>El motivo no es pudor: la API de Google AI Studio lleva la clave en la cadena de
 * consulta (<code>?key=AIza…</code>). Basta con que una versión de la biblioteca cliente
 * incluya la URL en el texto de una excepción para publicar la credencial del operador en
 * el navegador de cualquier usuario.
 *
 * <p>El cuerpo conserva la clave {@code detail}, que el frontend ya lee, y <em>añade</em>
 * {@code correlationId}. Es aditivo: ningún cliente existente se entera.
 */
@Provider
public class ManejadorErrores {

    private static final Logger LOG = Logger.getLogger(ManejadorErrores.class);

    /** Coincide con el retardo del cortacircuitos: antes de eso no hay nada que ganar. */
    private static final String SEGUNDOS_PARA_REINTENTAR = "30";

    /** Cuerpo de error uniforme. */
    public record Detalle(String detail, String correlationId) {

        /**
         * Construye el cuerpo con el identificador de la petición en curso.
         *
         * <p>Existe para que ninguna respuesta de error salga sin él: un recurso que
         * construye su propio 404 no debería tener que acordarse de pedirlo.
         */
        public static Detalle de(String detail) {
            return new Detalle(detail, FiltroCorrelacion.actual());
        }
    }

    @ServerExceptionMapper
    public Response errorDelAgente(ErroresIA.ErrorAgente error) {
        String identificador = FiltroCorrelacion.actual();
        // El mensaje de las excepciones tipadas lo redacta este código y es seguro por
        // construcción; la causa es la que puede traer texto de fuera, y solo se registra.
        // Sin repetir el identificador en el texto: el formato del registro ya lo
        // inserta desde el contexto (`%X{correlationId}`), y ponerlo también aquí lo
        // pintaba dos veces en cada línea.
        LOG.errorf(error, "%s (HTTP %d)",
                error.getClass().getSimpleName(), error.estadoHttp());
        return Response.status(error.estadoHttp())
                .entity(new Detalle(error.getMessage(), identificador))
                .build();
    }

    @ServerExceptionMapper
    public Response documentoNoSoportado(DocumentoNoSoportado error) {
        String identificador = FiltroCorrelacion.actual();
        LOG.warnf("Documento no soportado: %s", error.getMessage());
        return Response.status(415)
                .entity(new Detalle(error.getMessage(), identificador))
                .build();
    }

    /** Errores de Bean Validation: se listan campo por campo, como hacía Pydantic. */
    @ServerExceptionMapper
    public Response validacion(ConstraintViolationException error) {
        String detalle = error.getConstraintViolations().stream()
                .map(v -> {
                    String ruta = String.valueOf(v.getPropertyPath());
                    // La ruta llega como "metodo.parametro.campo"; el campo es lo útil.
                    int ultimoPunto = ruta.lastIndexOf('.');
                    String campo = ultimoPunto >= 0 ? ruta.substring(ultimoPunto + 1) : ruta;
                    return campo + ": " + v.getMessage();
                })
                .collect(Collectors.joining(" · "));
        // Estos mensajes son los de las anotaciones del contrato: los escribimos nosotros
        // y describen lo que el cliente envió mal. Se devuelven tal cual, a propósito.
        return Response.status(422)
                .entity(new Detalle(detalle, FiltroCorrelacion.actual()))
                .build();
    }

    /**
     * Traduce los fallos de las políticas de resiliencia.
     *
     * <p>Sin este mapeo, un mamparo lleno o un cortacircuitos abierto salen como «500
     * Internal Server Error»: exactamente el defecto que este manejador existe para
     * evitar, reaparecido por la puerta de atrás al añadir Fault Tolerance. Los tres son
     * situaciones de saturación temporal, y por eso llevan {@code Retry-After}: le dicen
     * al cliente que vuelva, no que se rinda.
     */
    @ServerExceptionMapper
    public Response saturacion(FaultToleranceException error) {
        String identificador = FiltroCorrelacion.actual();
        LOG.warnf(error, "Política de resiliencia activada: %s",
                error.getClass().getSimpleName());
        return Response.status(503)
                .header("Retry-After", SEGUNDOS_PARA_REINTENTAR)
                .entity(new Detalle(
                        "El servicio está saturado o el proveedor no responde. Prueba con "
                                + "otro proveedor desde el selector, o reintenta en un minuto.",
                        identificador))
                .build();
    }
}
