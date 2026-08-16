package co.agentesecop.adapter.in.rest.mapper;

import co.agentesecop.adapter.in.rest.dto.Solicitudes.SolicitudAnalisis;
import co.agentesecop.adapter.in.rest.dto.Solicitudes.SolicitudChat;
import co.agentesecop.adapter.in.rest.dto.Solicitudes.SolicitudPropuesta;
import co.agentesecop.adapter.in.rest.dto.Solicitudes.SolicitudRelevancia;
import co.agentesecop.adapter.in.rest.dto.Solicitudes.SolicitudValidacion;
import co.agentesecop.application.port.in.AnalizarPliego.ComandoDeAnalisis;
import co.agentesecop.application.port.in.ConversarConElAgente.ComandoDeChat;
import co.agentesecop.application.port.in.ConversarConElAgente.Mensaje;
import co.agentesecop.application.port.in.GenerarPropuesta.ComandoDePropuesta;
import co.agentesecop.application.port.in.PriorizarProcesos.ComandoDePriorizacion;
import co.agentesecop.application.port.in.ValidarPropuesta.ComandoDeValidacion;
import co.agentesecop.application.port.out.SeleccionDeModelo;

/**
 * Traduce las solicitudes HTTP a comandos de caso de uso.
 *
 * <p>Es la traducción que permite que los servicios de aplicación no sepan que existe
 * HTTP. Antes no existía: {@code AgenteSecop} recibía los records de solicitud tal cual,
 * con sus anotaciones de validación y de OpenAPI encima, y por eso la capa de aplicación
 * dependía del adaptador de entrada.
 */
public final class ComandosMapper {

    private ComandosMapper() {}

    public static ComandoDeAnalisis aComando(SolicitudAnalisis solicitud) {
        return new ComandoDeAnalisis(
                solicitud.textoPliego(),
                solicitud.objetoContractual(),
                solicitud.entidad(),
                solicitud.modalidad(),
                solicitud.valorEstimado(),
                solicitud.contextoProveedor(),
                seleccion(solicitud.proveedor(), solicitud.modelo()));
    }

    public static ComandoDePropuesta aComando(SolicitudPropuesta solicitud) {
        return new ComandoDePropuesta(
                solicitud.objetoContractual(),
                solicitud.perfilProveedor(),
                solicitud.requisitos(),
                solicitud.textoPliego(),
                solicitud.entidad(),
                solicitud.valorEstimado(),
                solicitud.plazoMeses(),
                solicitud.enfasis(),
                seleccion(solicitud.proveedor(), solicitud.modelo()));
    }

    public static ComandoDeValidacion aComando(SolicitudValidacion solicitud) {
        return new ComandoDeValidacion(
                solicitud.textoPropuesta(),
                solicitud.requisitos(),
                solicitud.textoPliego(),
                solicitud.objetoContractual(),
                seleccion(solicitud.proveedor(), solicitud.modelo()));
    }

    public static ComandoDePriorizacion aComando(SolicitudRelevancia solicitud) {
        return new ComandoDePriorizacion(
                solicitud.procesos(),
                solicitud.perfilProveedor(),
                solicitud.maximo(),
                seleccion(solicitud.proveedor(), solicitud.modelo()));
    }

    public static ComandoDeChat aComando(SolicitudChat solicitud) {
        return new ComandoDeChat(
                solicitud.mensajes().stream()
                        .map(m -> new Mensaje(m.rol(), m.contenido()))
                        .toList(),
                solicitud.contexto(),
                seleccion(solicitud.proveedor(), solicitud.modelo()));
    }

    private static SeleccionDeModelo seleccion(String proveedor, String modelo) {
        return new SeleccionDeModelo(proveedor, modelo);
    }
}
