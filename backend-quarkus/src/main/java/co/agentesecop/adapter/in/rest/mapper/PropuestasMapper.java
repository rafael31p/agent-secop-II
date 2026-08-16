package co.agentesecop.adapter.in.rest.mapper;

import static co.agentesecop.adapter.in.rest.mapper.AnalisisMapper.codigo;

import co.agentesecop.adapter.in.rest.dto.PropuestasDto.ItemCumplimientoDto;
import co.agentesecop.adapter.in.rest.dto.PropuestasDto.ProcesoPriorizadoDto;
import co.agentesecop.adapter.in.rest.dto.PropuestasDto.RespuestaPropuesta;
import co.agentesecop.adapter.in.rest.dto.PropuestasDto.RespuestaRelevancia;
import co.agentesecop.adapter.in.rest.dto.PropuestasDto.RespuestaValidacion;
import co.agentesecop.adapter.in.rest.dto.PropuestasDto.SeccionPropuestaDto;
import co.agentesecop.domain.model.proposal.InformeDeCumplimiento;
import co.agentesecop.domain.model.proposal.ItemCumplimiento;
import co.agentesecop.domain.model.proposal.Priorizacion;
import co.agentesecop.domain.model.proposal.ProcesoPriorizado;
import co.agentesecop.domain.model.proposal.Propuesta;
import co.agentesecop.domain.model.proposal.SeccionPropuesta;

/** Traduce propuesta, validación y priorización al contrato HTTP. */
public final class PropuestasMapper {

    private PropuestasMapper() {}

    public static RespuestaPropuesta aDto(Propuesta propuesta) {
        return new RespuestaPropuesta(
                propuesta.titulo(),
                propuesta.resumenEjecutivo(),
                propuesta.secciones().stream().map(PropuestasMapper::aDto).toList(),
                propuesta.supuestos(),
                propuesta.vaciosDeInformacion(),
                propuesta.markdown());
    }

    private static SeccionPropuestaDto aDto(SeccionPropuesta seccion) {
        return new SeccionPropuestaDto(
                seccion.titulo(), seccion.contenido(), seccion.requisitosCubiertos());
    }

    public static RespuestaValidacion aDto(InformeDeCumplimiento informe) {
        return new RespuestaValidacion(
                informe.puntajeCumplimiento(),
                codigo(informe.veredicto()),
                informe.resumen(),
                informe.matriz().stream().map(PropuestasMapper::aDto).toList(),
                informe.causalesDeRechazo(),
                informe.mejorasPrioritarias());
    }

    private static ItemCumplimientoDto aDto(ItemCumplimiento item) {
        return new ItemCumplimientoDto(
                item.requisitoId(),
                item.requisito(),
                codigo(item.criticidad()),
                codigo(item.estado()),
                item.evidenciaEnPropuesta(),
                item.brecha(),
                item.accionCorrectiva());
    }

    public static RespuestaRelevancia aDto(Priorizacion priorizacion) {
        return new RespuestaRelevancia(
                priorizacion.priorizados().stream().map(PropuestasMapper::aDto).toList(),
                priorizacion.resumen());
    }

    private static ProcesoPriorizadoDto aDto(ProcesoPriorizado proceso) {
        return new ProcesoPriorizadoDto(
                proceso.id(),
                proceso.objeto(),
                proceso.entidad(),
                proceso.valor(),
                proceso.puntaje(),
                proceso.categoriaTi(),
                proceso.justificacion(),
                proceso.encajeProveedor(),
                proceso.banderas());
    }
}
