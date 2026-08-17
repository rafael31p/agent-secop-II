package co.agentesecop.adapter.in.rest.error;

import co.agentesecop.adapter.out.llm.error.ProveedorNoConfigurado;
import jakarta.ws.rs.ext.Provider;

/** Falta la credencial: el servicio no puede atender, pero no es culpa de quien llama. */
@Provider
public class MapeadorProveedorNoConfigurado extends MapeadorDeError<ProveedorNoConfigurado> {

    @Override
    protected int estado() {
        return 503;
    }
}
