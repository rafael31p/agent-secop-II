package co.agentesecop.adapter.in.rest.mapper;

import co.agentesecop.adapter.in.rest.dto.ProcesosDto.ProcesoResumenDto;
import co.agentesecop.adapter.in.rest.dto.ProcesosDto.RespuestaDocumento;
import co.agentesecop.adapter.in.rest.dto.ProcesosDto.RespuestaProcesos;
import co.agentesecop.domain.model.procurement.ProcesoDeContratacion;
import co.agentesecop.domain.model.procurement.ResultadoDeBusqueda;
import co.agentesecop.domain.model.procurement.TextoDeDocumento;

/** Traduce procesos y documentos al contrato HTTP. */
public final class ProcesosMapper {

    private ProcesosMapper() {}

    public static RespuestaProcesos aDto(ResultadoDeBusqueda resultado) {
        return new RespuestaProcesos(
                resultado.total(),
                resultado.procesos().stream().map(ProcesosMapper::aDto).toList(),
                resultado.dataset(),
                resultado.advertencias());
    }

    public static ProcesoResumenDto aDto(ProcesoDeContratacion proceso) {
        return new ProcesoResumenDto(
                proceso.id(),
                proceso.numeroProceso(),
                proceso.entidad(),
                proceso.nitEntidad(),
                proceso.departamento(),
                proceso.ciudad(),
                proceso.objeto(),
                proceso.modalidad(),
                proceso.estado(),
                proceso.tipoContrato(),
                proceso.ordenEntidad(),
                proceso.adjudicado(),
                proceso.valor(),
                proceso.fechaPublicacion(),
                proceso.fechaUltimaPublicacion(),
                proceso.url(),
                proceso.codigoUnspsc(),
                proceso.duracion(),
                proceso.scoreTi(),
                proceso.senalesTi());
    }

    public static RespuestaDocumento aDto(TextoDeDocumento documento) {
        return new RespuestaDocumento(
                documento.nombreArchivo(),
                documento.tipo(),
                documento.caracteres(),
                documento.paginas(),
                documento.texto(),
                documento.truncado());
    }
}
