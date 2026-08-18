package co.agentesecop.adapter.out.llm.error;

/** El proveedor pedido no existe (400). El mensaje lista los que sí. */
public final class ProveedorDesconocido extends ErrorDelAgente {

    public ProveedorDesconocido(String mensaje) {
        super(mensaje);
    }
}
