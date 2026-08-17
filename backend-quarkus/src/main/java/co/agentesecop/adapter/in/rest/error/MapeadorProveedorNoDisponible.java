package co.agentesecop.adapter.in.rest.error;

import co.agentesecop.adapter.out.llm.error.ProveedorNoDisponible;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * La llamada no se intentó: circuito abierto, mamparo lleno o presupuesto agotado.
 *
 * <p>El {@code Retry-After} coincide con el reposo del cortacircuitos: antes de eso no hay
 * nada que ganar reintentando, y decírselo al cliente evita que insista contra un
 * proveedor que ya sabemos que no responde.
 */
@Provider
public class MapeadorProveedorNoDisponible extends MapeadorDeError<ProveedorNoDisponible> {

    @Override
    protected int estado() {
        return 503;
    }

    @Override
    protected void decorar(Response.ResponseBuilder respuesta) {
        respuesta.header("Retry-After", 30);
    }
}
