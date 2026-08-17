package co.agentesecop.adapter.in.rest.error;

import co.agentesecop.adapter.out.llm.error.ModeloDesconocido;
import jakarta.ws.rs.ext.Provider;

/** El modelo pedido no existe en ese proveedor. */
@Provider
public class MapeadorModeloDesconocido extends MapeadorDeError<ModeloDesconocido> {

    @Override
    protected int estado() {
        return 404;
    }
}
