package co.agentesecop.adapter.in.rest;

import co.agentesecop.adapter.in.rest.dto.PropuestasDto.RespuestaPropuesta;
import co.agentesecop.adapter.in.rest.dto.PropuestasDto.RespuestaValidacion;
import co.agentesecop.adapter.in.rest.mapper.PropuestasMapper;
import co.agentesecop.adapter.in.rest.dto.Solicitudes.SolicitudPropuesta;
import co.agentesecop.adapter.in.rest.dto.Solicitudes.SolicitudValidacion;
import co.agentesecop.adapter.in.rest.mapper.ComandosMapper;
import co.agentesecop.application.port.in.GenerarPropuesta;
import co.agentesecop.application.port.in.ValidarPropuesta;
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

    private final GenerarPropuesta generarPropuesta;
    private final ValidarPropuesta validarPropuesta;

    @Inject
    public PropuestasResource(
            GenerarPropuesta generarPropuesta, ValidarPropuesta validarPropuesta) {
        this.generarPropuesta = generarPropuesta;
        this.validarPropuesta = validarPropuesta;
    }

    @POST
    @Path("/generar")
    @Operation(summary = "Redacta un borrador de propuesta alineado al pliego")
    public RespuestaPropuesta generar(@Valid SolicitudPropuesta solicitud) {
        return PropuestasMapper.aDto(generarPropuesta.generar(ComandosMapper.aComando(solicitud)));
    }

    @POST
    @Path("/validar")
    @Operation(summary = "Compara la propuesta contra los requisitos del proceso")
    public RespuestaValidacion validar(@Valid SolicitudValidacion solicitud) {
        return PropuestasMapper.aDto(validarPropuesta.validar(ComandosMapper.aComando(solicitud)));
    }
}
