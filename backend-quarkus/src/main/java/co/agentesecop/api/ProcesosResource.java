package co.agentesecop.api;

import co.agentesecop.dominio.Propuestas.RespuestaRelevancia;
import co.agentesecop.dominio.Secop.ProcesoResumen;
import co.agentesecop.dominio.Secop.RespuestaProcesos;
import co.agentesecop.dominio.Solicitudes.FiltroProcesos;
import co.agentesecop.dominio.Solicitudes.SolicitudRelevancia;
import co.agentesecop.ia.ErroresIA;
import co.agentesecop.secop.SecopCliente;
import co.agentesecop.servicio.AgenteSecop;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Consulta de procesos publicados en SECOP II. */
@Path("/api/procesos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "procesos", description = "Búsqueda y priorización de procesos de SECOP II")
public class ProcesosResource {

    private final SecopCliente secop;
    private final AgenteSecop agente;

    @Inject
    public ProcesosResource(SecopCliente secop, AgenteSecop agente) {
        this.secop = secop;
        this.agente = agente;
    }

    @POST
    @Path("/buscar")
    @Operation(summary = "Busca procesos en el conjunto de datos abierto de SECOP II")
    public RespuestaProcesos buscar(@Valid FiltroProcesos filtro) {
        if (filtro.rangoDeValorInvertido()) {
            throw new WebApplicationException(
                    Response.status(422)
                            .entity(new ManejadorErrores.Detalle(
                                    "valorMin no puede ser mayor que valorMax."))
                            .build());
        }
        return secop.buscar(filtro);
    }

    @GET
    @Path("/{idProceso}")
    @Operation(summary = "Detalle de un proceso por su identificador")
    public ProcesoResumen obtener(@PathParam("idProceso") String idProceso) {
        return secop.obtenerPorId(idProceso)
                .orElseThrow(() -> new WebApplicationException(
                        Response.status(404)
                                .entity(new ManejadorErrores.Detalle(
                                        "No se encontró el proceso '%s' en SECOP II."
                                                .formatted(idProceso)))
                                .build()));
    }

    @POST
    @Path("/relevancia-ti")
    @Operation(summary = "Clasifica procesos por categoría de TI y encaje con el proveedor")
    public RespuestaRelevancia priorizar(@Valid SolicitudRelevancia solicitud) {
        if (solicitud.procesos().isEmpty()) {
            throw new ErroresIA.PeticionInvalida("Envía al menos un proceso para priorizar.");
        }
        return agente.priorizarProcesos(solicitud);
    }
}
