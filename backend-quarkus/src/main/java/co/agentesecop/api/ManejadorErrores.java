package co.agentesecop.api;

import co.agentesecop.ia.ErroresIA;
import co.agentesecop.servicio.ExtractorDocumentos.DocumentoNoSoportado;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.stream.Collectors;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Traduce las excepciones del dominio a respuestas HTTP, en un solo sitio.
 *
 * <p>Sin esto, cualquier excepción sale como «500 Internal Server Error» y el mensaje
 * explicativo (modelo inexistente, cuota agotada, filtro de contenido) nunca llega al
 * usuario. Es exactamente el defecto que tuvo la versión Python hasta que se centralizó.
 *
 * <p>El cuerpo usa la clave {@code detail} para que el cliente del frontend, ya escrito
 * contra FastAPI, siga funcionando sin cambios.
 */
@Provider
public class ManejadorErrores {

    /** Cuerpo de error uniforme. */
    public record Detalle(String detail) {}

    @ServerExceptionMapper
    public Response errorDelAgente(ErroresIA.ErrorAgente error) {
        return Response.status(error.estadoHttp())
                .entity(new Detalle(error.getMessage()))
                .build();
    }

    @ServerExceptionMapper
    public Response documentoNoSoportado(DocumentoNoSoportado error) {
        return Response.status(415).entity(new Detalle(error.getMessage())).build();
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
        return Response.status(422).entity(new Detalle(detalle)).build();
    }
}
