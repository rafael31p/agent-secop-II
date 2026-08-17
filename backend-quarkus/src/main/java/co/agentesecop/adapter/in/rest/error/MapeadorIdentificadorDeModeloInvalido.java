package co.agentesecop.adapter.in.rest.error;

import co.agentesecop.adapter.out.llm.error.IdentificadorDeModeloInvalido;
import jakarta.ws.rs.ext.Provider;

/** El identificador de modelo no tiene forma admisible. */
@Provider
public class MapeadorIdentificadorDeModeloInvalido extends MapeadorDeError<IdentificadorDeModeloInvalido> {

    @Override
    protected int estado() {
        return 422;
    }
}
