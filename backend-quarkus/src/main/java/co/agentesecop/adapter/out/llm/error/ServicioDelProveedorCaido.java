package co.agentesecop.adapter.out.llm.error;

/**
 * El proveedor está caído, saturado, o devolvió algo que no se pudo clasificar (502).
 *
 * <p>Lo inclasificable cae aquí a propósito: ante lo desconocido conviene reintentar una
 * vez antes de rendirse. Y el mensaje **no** incluye el texto original —eso es lo que
 * viaja como causa—, porque en la rama por defecto no se sabe qué contiene.
 */
public final class ServicioDelProveedorCaido extends FalloTransitorio {

    public ServicioDelProveedorCaido(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }

    public static ServicioDelProveedorCaido temporal(String etiquetaDelProveedor, Throwable causa) {
        return new ServicioDelProveedorCaido(
                "El servicio de %s falló temporalmente. Reintenta."
                        .formatted(etiquetaDelProveedor), causa);
    }

    public static ServicioDelProveedorCaido sinClasificar(
            String etiquetaDelProveedor, Throwable causa) {
        return new ServicioDelProveedorCaido(
                ("%s devolvió un error que no se pudo clasificar. Reintenta; si persiste, "
                        + "cita el identificador de este error al reportarlo.")
                        .formatted(etiquetaDelProveedor), causa);
    }
}
