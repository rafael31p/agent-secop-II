package co.agentesecop.adapter.in.rest.error;

import co.agentesecop.adapter.out.llm.error.CuotaAgotada;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Cuota del plan agotada.
 *
 * <p>Lleva {@code Retry-After} porque un 429 sin él obliga al cliente a adivinar, y lo que
 * adivina suele ser reintentar en seguida, que es exactamente lo que no ayuda. Las cuotas
 * de los planes gratuitos se reponen por minuto.
 */
@Provider
public class MapeadorCuotaAgotada extends MapeadorDeError<CuotaAgotada> {

    @Override
    protected int estado() {
        return 429;
    }

    @Override
    protected void decorar(Response.ResponseBuilder respuesta) {
        respuesta.header("Retry-After", 60);
    }
}
