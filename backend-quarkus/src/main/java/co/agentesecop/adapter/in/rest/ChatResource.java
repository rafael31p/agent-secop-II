package co.agentesecop.adapter.in.rest;

import co.agentesecop.adapter.in.rest.dto.Solicitudes.SolicitudChat;
import co.agentesecop.adapter.out.llm.error.ErrorDelAgente;
import co.agentesecop.adapter.in.rest.mapper.ComandosMapper;
import co.agentesecop.application.port.in.ConversarConElAgente;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Chat con el agente, servido como Server-Sent Events.
 *
 * <p>Mantiene el protocolo de la versión Python —eventos {@code delta}, {@code error} y
 * {@code fin}, con carga JSON— para que el cliente del frontend no cambie ni una línea.
 *
 * <p>Se usa {@link OutboundSseEvent} en lugar de emitir cadenas: es lo único que permite
 * fijar el nombre del evento, y deja el formateo SSE en manos del framework.
 *
 * <p>El método va anotado con {@link Blocking} aunque devuelva un {@code Multi}. Devolver
 * un tipo reactivo hace que RESTEasy Reactive ejecute el método en el bucle de eventos, y
 * lo que ocurre antes de que exista el flujo <em>no</em> es reactivo: resolver el
 * proveedor, comprobar credenciales y construir el modelo, que abre un cliente HTTP. Ese
 * trabajo en el hilo de E/S bloquea a todas las peticiones del servidor, no solo a esta.
 */
@Path("/api/chat")
@Tag(name = "chat", description = "Consulta libre al agente experto")
public class ChatResource {

    private final ConversarConElAgente conversar;

    @Inject
    public ChatResource(ConversarConElAgente conversar) {
        this.conversar = conversar;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Operation(summary = "Respuesta del agente en streaming (SSE)")
    @Blocking
    public Multi<OutboundSseEvent> chat(@Valid SolicitudChat solicitud, @Context Sse sse) {
        Multi<OutboundSseEvent> fragmentos;
        try {
            fragmentos = conversar.conversar(ComandosMapper.aComando(solicitud))
                    .map(texto -> evento(sse, "delta", Map.of("texto", texto)));
        } catch (ErrorDelAgente error) {
            // El proveedor puede fallar antes de abrir el flujo (clave ausente, modelo
            // inexistente). Se reporta por el mismo canal en vez de romper la conexión.
            fragmentos = Multi.createFrom()
                    .item(evento(sse, "error", Map.of("mensaje", error.getMessage())));
        }

        return fragmentos
                .onFailure().recoverWithMulti(error -> Multi.createFrom()
                        .item(evento(sse, "error", Map.of("mensaje", mensajeDe(error)))))
                .onCompletion().continueWith(() -> java.util.List.of(
                        evento(sse, "fin", Map.of())));
    }

    private static OutboundSseEvent evento(Sse sse, String nombre, Map<String, String> datos) {
        return sse.newEventBuilder()
                .name(nombre)
                .mediaType(MediaType.APPLICATION_JSON_TYPE)
                .data(Map.class, datos)
                .build();
    }

    private static String mensajeDe(Throwable error) {
        if (error instanceof ErrorDelAgente agente) {
            return agente.getMessage();
        }
        return error.getMessage() == null
                ? "Error del agente: " + error.getClass().getSimpleName()
                : error.getMessage();
    }
}
