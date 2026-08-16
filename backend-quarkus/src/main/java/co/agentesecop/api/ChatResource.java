package co.agentesecop.api;

import co.agentesecop.dominio.Solicitudes.SolicitudChat;
import co.agentesecop.ia.ErroresIA;
import co.agentesecop.servicio.AgenteSecop;
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
 */
@Path("/api/chat")
@Tag(name = "chat", description = "Consulta libre al agente experto")
public class ChatResource {

    private final AgenteSecop agente;

    @Inject
    public ChatResource(AgenteSecop agente) {
        this.agente = agente;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Operation(summary = "Respuesta del agente en streaming (SSE)")
    public Multi<OutboundSseEvent> chat(@Valid SolicitudChat solicitud, @Context Sse sse) {
        Multi<OutboundSseEvent> fragmentos;
        try {
            fragmentos = agente.chat(solicitud)
                    .map(texto -> evento(sse, "delta", Map.of("texto", texto)));
        } catch (ErroresIA.ErrorAgente error) {
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
        if (error instanceof ErroresIA.ErrorAgente agente) {
            return agente.getMessage();
        }
        return error.getMessage() == null
                ? "Error del agente: " + error.getClass().getSimpleName()
                : error.getMessage();
    }
}
