package co.agentesecop.adapter.out.llm.error;

/** El modelo pedido no existe o la clave no tiene acceso a él (404). Tampoco se reintenta. */
public final class ModeloDesconocido extends FalloDelProveedor {

    public ModeloDesconocido(String modelo, String etiquetaDelProveedor, Throwable causa) {
        super(("El modelo '%s' no existe en %s o tu clave no tiene acceso a él. "
                + "Consulta GET /api/proveedores para ver los disponibles.")
                .formatted(modelo, etiquetaDelProveedor), causa);
    }
}
