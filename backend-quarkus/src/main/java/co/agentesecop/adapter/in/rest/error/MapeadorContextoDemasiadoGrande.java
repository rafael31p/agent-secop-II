package co.agentesecop.adapter.in.rest.error;

import co.agentesecop.adapter.out.llm.error.ContextoDemasiadoGrande;
import jakarta.ws.rs.ext.Provider;

/** El material no cabe en una sola solicitud. */
@Provider
public class MapeadorContextoDemasiadoGrande extends MapeadorDeError<ContextoDemasiadoGrande> {

    @Override
    protected int estado() {
        return 413;
    }
}
