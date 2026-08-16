package co.agentesecop.api;

import co.agentesecop.dominio.Propuestas.RespuestaPropuesta;
import co.agentesecop.dominio.Propuestas.RespuestaValidacion;
import co.agentesecop.adapter.in.rest.dto.Solicitudes.SolicitudPropuesta;
import co.agentesecop.adapter.in.rest.dto.Solicitudes.SolicitudValidacion;
import co.agentesecop.servicio.AgenteSecop;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Generación y validación de propuestas técnicas. */
@Path("/api/propuestas")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "propuestas", description = "Redacción y verificación de propuestas")
public class PropuestasResource {

    private final AgenteSecop agente;

    @Inject
    public PropuestasResource(AgenteSecop agente) {
        this.agente = agente;
    }

    @POST
    @Path("/generar")
    @Operation(summary = "Redacta un borrador de propuesta alineado al pliego")
    public RespuestaPropuesta generar(@Valid SolicitudPropuesta solicitud) {
        return agente.generarPropuesta(solicitud);
    }

    @POST
    @Path("/validar")
    @Operation(summary = "Compara la propuesta contra los requisitos del proceso")
    public RespuestaValidacion validar(@Valid SolicitudValidacion solicitud) {
        return agente.validarPropuesta(solicitud);
    }
}
