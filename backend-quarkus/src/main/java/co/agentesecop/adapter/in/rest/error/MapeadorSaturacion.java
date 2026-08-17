package co.agentesecop.adapter.in.rest.error;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.faulttolerance.exceptions.FaultToleranceException;

/**
 * Las excepciones que lanzan las propias políticas de resiliencia.
 *
 * <p>Sin este mapeo, un mamparo lleno o un cortacircuitos abierto salen como «500 Internal
 * Server Error»: el defecto que esta capa existe para evitar, reaparecido por la puerta de
 * atrás al añadir Fault Tolerance.
 *
 * <p>El mensaje se reescribe en vez de reenviar el de la excepción, que es de biblioteca y
 * está en inglés. Cubre la ruta que el repliegue del modelo no atrapa: el presupuesto de
 * tiempo del caso de uso, que vive una capa más arriba.
 */
@Provider
public class MapeadorSaturacion extends MapeadorDeError<FaultToleranceException> {

    @Override
    protected int estado() {
        return 503;
    }

    @Override
    protected String detalle(FaultToleranceException error) {
        return "El servicio está saturado o el proveedor no responde. Prueba con otro "
                + "proveedor desde el selector, o reintenta en un minuto.";
    }

    @Override
    protected void decorar(Response.ResponseBuilder respuesta) {
        respuesta.header("Retry-After", 30);
    }
}
