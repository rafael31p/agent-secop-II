package co.agentesecop.adapter.out.llm.error;

/** El material excede lo que se puede enviar en una sola solicitud (413). */
public final class ContextoDemasiadoGrande extends ErrorDelAgente {

    public ContextoDemasiadoGrande(String mensaje) {
        super(mensaje);
    }
}
