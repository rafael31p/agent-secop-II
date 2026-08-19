package co.agentesecop.adapter.in.rest.error;

import co.agentesecop.adapter.out.llm.error.ProveedorDesconocido;
import jakarta.ws.rs.ext.Provider;

/** Pidió un proveedor que no existe. El mensaje lista los disponibles. */
@Provider
public class MapeadorProveedorDesconocido extends MapeadorDeError<ProveedorDesconocido> {

    @Override
    protected int estado() {
        return 400;
    }
}
