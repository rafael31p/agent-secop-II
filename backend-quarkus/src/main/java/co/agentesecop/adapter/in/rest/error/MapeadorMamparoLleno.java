package co.agentesecop.adapter.in.rest.error;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException;

/**
 * El servicio está atendiendo el máximo de trabajos simultáneos.
 *
 * <h2>Por qué no comparte respuesta con el cortacircuitos</h2>
 *
 * <p>Porque no es lo mismo, y decir que sí lo es manda al usuario a una salida que no
 * existe. El repliegue del modelo responde «el proveedor no está respondiendo o está
 * saturado, prueba con otro proveedor»: con el mamparo lleno eso es doblemente falso —el
 * proveedor está perfectamente sano, y cambiar de proveedor no ayuda porque el mamparo es
 * el mismo para todos—. Con doce análisis en vuelo, cosa que consiguen cuatro usuarios, el
 * decimotercero recibía una explicación equivocada y un consejo inútil.
 *
 * <p>429 y no 503: la petición no se atendió por un límite <b>nuestro</b> y de duración
 * corta, que es exactamente lo que significa «demasiadas peticiones». Y con
 * {@code Retry-After}, porque a diferencia del circuito abierto aquí sí se sabe que en
 * unos segundos habrá sitio. El frontend ya trata los 429 con {@code Retry-After}, así que
 * el arreglo llega a la interfaz sin trabajo adicional.
 */
@Provider
public class MapeadorMamparoLleno extends MapeadorDeError<BulkheadException> {

    /** Corto a propósito: un mamparo se libera en cuanto termina una llamada en curso. */
    private static final int SEGUNDOS = 10;

    @Override
    protected int estado() {
        return 429;
    }

    @Override
    protected String detalle(BulkheadException error) {
        return "El servicio está atendiendo el máximo de trabajos simultáneos. Reintenta "
                + "en unos segundos: no es un problema del proveedor de modelos.";
    }

    @Override
    protected void decorar(Response.ResponseBuilder respuesta) {
        respuesta.header("Retry-After", SEGUNDOS);
    }
}
