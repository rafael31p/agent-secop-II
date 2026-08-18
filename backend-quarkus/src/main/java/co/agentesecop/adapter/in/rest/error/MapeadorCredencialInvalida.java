package co.agentesecop.adapter.in.rest.error;

import co.agentesecop.adapter.out.llm.error.CredencialInvalida;
import jakarta.ws.rs.ext.Provider;

/** La clave del operador no sirve. Se registra como error: hay que arreglarlo. */
@Provider
public class MapeadorCredencialInvalida extends MapeadorDeError<CredencialInvalida> {

    @Override
    protected int estado() {
        return 401;
    }
}
