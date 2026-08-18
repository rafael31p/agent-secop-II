package co.agentesecop.adapter.in.rest.error;

import co.agentesecop.adapter.in.rest.FiltroCorrelacion;

/**
 * El cuerpo de error, idéntico para todas las respuestas de fallo.
 *
 * <p>Conserva la clave {@code detail}, que el frontend ya lee, y añade
 * {@code correlationId}. Es aditivo: ningún cliente existente se entera.
 *
 * <p>El identificador es lo que hace utilizable devolver un mensaje genérico. Se puede
 * —hay que— ocultar el detalle técnico <em>porque</em> queda una llave para encontrarlo en
 * el registro.
 */
public record CuerpoDeError(String detail, String correlationId) {

    /**
     * Con el identificador de la petición en curso.
     *
     * <p>Existe para que ninguna respuesta de error salga sin él: un filtro que construye
     * su propio 401 no debería tener que acordarse de pedirlo.
     */
    public static CuerpoDeError de(String detalle) {
        return new CuerpoDeError(detalle, FiltroCorrelacion.actual());
    }
}
