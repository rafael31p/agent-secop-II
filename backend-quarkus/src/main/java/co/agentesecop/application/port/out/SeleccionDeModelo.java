package co.agentesecop.application.port.out;

/**
 * Qué proveedor y qué modelo usar en una llamada concreta.
 *
 * <p>Ambos pueden ir nulos, y nulo significa «lo que el servidor tenga configurado», que
 * es distinto de «gemini». Esa distinción es la que permite que el usuario elija sin
 * obligarlo a elegir.
 */
public record SeleccionDeModelo(String proveedor, String modelo) {

    private static final SeleccionDeModelo POR_DEFECTO = new SeleccionDeModelo(null, null);

    public static SeleccionDeModelo porDefecto() {
        return POR_DEFECTO;
    }
}
