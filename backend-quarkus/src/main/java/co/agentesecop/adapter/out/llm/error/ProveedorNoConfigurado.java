package co.agentesecop.adapter.out.llm.error;

/** Falta la credencial del proveedor solicitado (503). */
public final class ProveedorNoConfigurado extends ErrorDelAgente {

    public ProveedorNoConfigurado(String mensaje) {
        super(mensaje);
    }
}
