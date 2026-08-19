package co.agentesecop.adapter.in.rest.error;

import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.ext.Provider;
import java.util.stream.Collectors;

/**
 * Errores del contrato de entrada, listados campo por campo como hacía Pydantic.
 *
 * <p>Estos mensajes son los de las anotaciones de validación: los escribimos nosotros y
 * describen lo que el cliente envió mal, así que se devuelven tal cual. Es la única
 * excepción a la regla de no publicar detalle, y lo es porque el detalle es nuestro.
 */
@Provider
public class MapeadorValidacion extends MapeadorDeError<ConstraintViolationException> {

    @Override
    protected int estado() {
        return 422;
    }

    @Override
    protected String detalle(ConstraintViolationException error) {
        return error.getConstraintViolations().stream()
                .map(violacion -> {
                    String ruta = String.valueOf(violacion.getPropertyPath());
                    // La ruta llega como "metodo.parametro.campo"; el campo es lo útil.
                    int ultimoPunto = ruta.lastIndexOf('.');
                    String campo = ultimoPunto >= 0 ? ruta.substring(ultimoPunto + 1) : ruta;
                    return campo + ": " + violacion.getMessage();
                })
                .collect(Collectors.joining(" · "));
    }
}
