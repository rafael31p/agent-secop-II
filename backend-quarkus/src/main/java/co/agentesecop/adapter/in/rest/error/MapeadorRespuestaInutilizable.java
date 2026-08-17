package co.agentesecop.adapter.in.rest.error;

import co.agentesecop.adapter.out.llm.error.RespuestaInutilizable;
import jakarta.ws.rs.ext.Provider;

/** Respondió algo que no se puede usar: filtro de contenido, truncamiento o JSON inválido. */
@Provider
public class MapeadorRespuestaInutilizable extends MapeadorDeError<RespuestaInutilizable> {

    @Override
    protected int estado() {
        return 422;
    }
}
