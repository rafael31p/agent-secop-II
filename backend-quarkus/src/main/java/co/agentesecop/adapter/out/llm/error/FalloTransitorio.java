package co.agentesecop.adapter.out.llm.error;

/**
 * Fallo que puede desaparecer solo: cuota agotada, saturación, corte de red.
 *
 * <p>Es lo único que se reintenta y lo único que cuenta para abrir el cortacircuitos. Una
 * clave inválida no debe envenenar al proveedor para todo el mundo.
 */
public abstract sealed class FalloTransitorio extends FalloDelProveedor
        permits CuotaAgotada, ServicioDelProveedorCaido {

    protected FalloTransitorio(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
