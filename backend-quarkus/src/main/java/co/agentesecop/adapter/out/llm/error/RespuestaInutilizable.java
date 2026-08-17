package co.agentesecop.adapter.out.llm.error;

/** Respondió, pero con algo que no se puede usar: filtro, truncamiento, JSON inválido (422). */
public final class RespuestaInutilizable extends ErrorDelAgente {

    public RespuestaInutilizable(String mensaje) {
        super(mensaje);
    }

    public RespuestaInutilizable(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
