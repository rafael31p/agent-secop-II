package co.agentesecop.adapter.out.llm.error;

/**
 * La llamada no se intentó: circuito abierto, mamparo lleno o presupuesto agotado (503).
 *
 * <p>Se distingue de {@link FalloTransitorio} porque no describe una llamada que falló,
 * sino una que <em>no se hizo</em>. El mensaje sugiere cambiar de proveedor: hay cinco
 * configurables, y esa frase convierte una caída total en una degradación.
 */
public final class ProveedorNoDisponible extends ErrorDelAgente {

    public ProveedorNoDisponible(String mensaje) {
        super(mensaje);
    }
}
