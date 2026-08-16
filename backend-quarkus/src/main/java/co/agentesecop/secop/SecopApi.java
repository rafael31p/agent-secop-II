package co.agentesecop.secop;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * API abierta de datos.gov.co (Socrata / SoQL).
 *
 * <p>Se mapea a {@code List<Map<String, Object>>} y no a un record tipado a propósito: el
 * esquema de los conjuntos de datos de SECOP II cambia entre versiones, y un record
 * fijo rompería ante cualquier renombrado de columna. El mapeo a nuestro dominio se
 * hace con alias tolerantes en {@link SecopCliente}.
 */
@Path("/resource")
@RegisterRestClient(configKey = "secop")
public interface SecopApi {

    @GET
    @Path("/{dataset}.json")
    @Produces(MediaType.APPLICATION_JSON)
    List<Map<String, Object>> consultar(
            @PathParam("dataset") String dataset,
            @QueryParam("$limit") int limite,
            @QueryParam("$offset") int offset,
            @QueryParam("$order") String orden,
            @QueryParam("$where") String filtro,
            @HeaderParam("X-App-Token") String token);
}
