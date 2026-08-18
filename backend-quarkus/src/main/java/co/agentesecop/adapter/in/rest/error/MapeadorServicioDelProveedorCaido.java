package co.agentesecop.adapter.in.rest.error;

import co.agentesecop.adapter.out.llm.error.ServicioDelProveedorCaido;
import jakarta.ws.rs.ext.Provider;

/**
 * El proveedor está caído o devolvió algo inclasificable.
 *
 * <p>502 y no 500: el fallo es de un servicio de terceros, no nuestro, y esa distinción es
 * la que le dice a quien opera dónde mirar.
 */
@Provider
public class MapeadorServicioDelProveedorCaido
        extends MapeadorDeError<ServicioDelProveedorCaido> {

    @Override
    protected int estado() {
        return 502;
    }
}
