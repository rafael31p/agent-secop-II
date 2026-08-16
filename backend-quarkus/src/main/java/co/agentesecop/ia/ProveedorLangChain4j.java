package co.agentesecop.ia;

import co.agentesecop.config.ConfiguracionIA;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.output.JsonSchemas;
import io.smallrye.mutiny.Multi;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * Base común de los proveedores implementados sobre LangChain4j.
 *
 * <p>Concentra lo que no depende del proveedor: caché de modelos, reintentos con
 * retroceso exponencial, imposición del esquema JSON y traducción de errores. Cada
 * subclase solo construye su modelo.
 */
public abstract class ProveedorLangChain4j implements ProveedorIA {

    private static final Logger LOG = Logger.getLogger(ProveedorLangChain4j.class);

    /** Tope de caracteres por petición, con margen para el razonamiento y la respuesta. */
    public static final int LIMITE_CARACTERES = 800_000;

    protected final ConfiguracionIA config;
    private final ObjectMapper jackson;

    /**
     * Construir un modelo abre un cliente HTTP; se cachean por nombre de modelo para no
     * repetirlo en cada petición.
     */
    private final Map<String, ChatModel> modelosCacheados = new ConcurrentHashMap<>();
    private final Map<String, StreamingChatModel> modelosFlujoCacheados =
            new ConcurrentHashMap<>();

    protected ProveedorLangChain4j(ConfiguracionIA config, ObjectMapper jackson) {
        this.config = config;
        this.jackson = jackson;
    }

    /** Construye el modelo síncrono del proveedor concreto. */
    protected abstract ChatModel construirModelo(String nombreModelo);

    /** Construye el modelo de streaming del proveedor concreto. */
    protected abstract StreamingChatModel construirModeloFlujo(String nombreModelo);

    @Override
    public String motivoNoDisponible() {
        return configurado() ? null : "Falta configurar la clave de " + etiqueta() + ".";
    }

    protected String resolverModelo(String solicitado) {
        return solicitado == null || solicitado.isBlank() ? modeloPorDefecto() : solicitado.trim();
    }

    private void exigirConfigurado() {
        if (!configurado()) {
            throw new ErroresIA.ProveedorNoConfigurado(motivoNoDisponible());
        }
    }

    private void exigirTamanoRazonable(PeticionIA peticion) {
        int caracteres = peticion.caracteres();
        if (caracteres > LIMITE_CARACTERES) {
            throw new ErroresIA.ContextoDemasiadoGrande(
                    "El material recibido tiene %,d caracteres y supera el límite de %,d. "
                            .formatted(caracteres, LIMITE_CARACTERES)
                            + "Divídelo por capítulos (por ejemplo, el anexo técnico "
                            + "por separado) y analiza cada parte.");
        }
    }

    // ---------------------------------------------------------------- estructurado

    @Override
    public <T> T estructurado(PeticionIA peticion, Class<T> tipo) {
        exigirConfigurado();
        exigirTamanoRazonable(peticion);

        String nombreModelo = resolverModelo(peticion.modelo());
        ChatModel modelo =
                modelosCacheados.computeIfAbsent(nombreModelo, this::construirModelo);

        ChatRequest solicitud = ChatRequest.builder()
                .messages(mensajes(peticion))
                .responseFormat(formatoJson(tipo))
                .build();

        ChatResponse respuesta = conReintentos(
                () -> modelo.chat(solicitud), nombreModelo);

        String texto = Optional.ofNullable(respuesta.aiMessage())
                .map(AiMessage::text)
                .map(String::trim)
                .orElse("");

        if (texto.isEmpty()) {
            throw new ErroresIA.RespuestaInutilizable(
                    "El modelo devolvió una respuesta vacía (motivo: %s)."
                            .formatted(respuesta.finishReason()));
        }

        try {
            return jackson.readValue(recortarACuerpoJson(texto), tipo);
        } catch (Exception e) {
            LOG.warnf("Respuesta no conforme al esquema (%s): %s",
                    nombreModelo, texto.substring(0, Math.min(500, texto.length())));
            throw new ErroresIA.RespuestaInutilizable(
                    "El modelo devolvió una respuesta que no cumple el esquema esperado. "
                            + "Reintenta; si persiste, prueba con un modelo más capaz.",
                    e);
        }
    }

    /**
     * Construye el formato de respuesta a partir del record de destino.
     *
     * <p>Si el tipo no se puede convertir a esquema, se cae a JSON libre en vez de
     * fallar: es preferible una respuesta que Jackson quizá pueda leer a ninguna.
     */
    private ResponseFormat formatoJson(Class<?> tipo) {
        Optional<JsonSchema> esquema = EsquemasJson.exigente(tipo);
        if (esquema.isEmpty()) {
            LOG.warnf("Sin esquema JSON derivable para %s; se pide JSON libre.",
                    tipo.getSimpleName());
            return ResponseFormat.JSON;
        }
        return ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(esquema.get())
                .build();
    }

    /**
     * Algunos modelos envuelven el JSON en un bloque de código Markdown pese a que se
     * pidió JSON puro. Se recorta al primer objeto de nivel superior.
     */
    static String recortarACuerpoJson(String texto) {
        String limpio = texto.trim();
        if (limpio.startsWith("```")) {
            int inicio = limpio.indexOf('\n');
            int fin = limpio.lastIndexOf("```");
            if (inicio > 0 && fin > inicio) {
                limpio = limpio.substring(inicio + 1, fin).trim();
            }
        }
        int abre = limpio.indexOf('{');
        int cierra = limpio.lastIndexOf('}');
        if (abre >= 0 && cierra > abre) {
            return limpio.substring(abre, cierra + 1);
        }
        return limpio;
    }

    // ---------------------------------------------------------------------- flujo

    @Override
    public Multi<String> flujo(PeticionIA peticion) {
        exigirConfigurado();
        exigirTamanoRazonable(peticion);

        String nombreModelo = resolverModelo(peticion.modelo());
        StreamingChatModel modelo =
                modelosFlujoCacheados.computeIfAbsent(nombreModelo, this::construirModeloFlujo);

        ChatRequest solicitud = ChatRequest.builder().messages(mensajes(peticion)).build();

        return Multi.createFrom().emitter(emisor ->
                modelo.chat(solicitud, new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String fragmento) {
                        if (fragmento != null && !fragmento.isEmpty()) {
                            emisor.emit(fragmento);
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse respuesta) {
                        emisor.complete();
                    }

                    @Override
                    public void onError(Throwable error) {
                        emisor.fail(traducir(error, nombreModelo));
                    }
                }));
    }

    // ------------------------------------------------------------------ auxiliares

    private List<ChatMessage> mensajes(PeticionIA peticion) {
        List<ChatMessage> mensajes = new ArrayList<>();
        if (peticion.sistema() != null && !peticion.sistema().isBlank()) {
            mensajes.add(SystemMessage.from(peticion.sistema()));
        }
        for (PeticionIA.Turno turno : peticion.turnos()) {
            mensajes.add(switch (turno.rol()) {
                case USUARIO -> UserMessage.from(turno.contenido());
                case ASISTENTE -> AiMessage.from(turno.contenido());
            });
        }
        return mensajes;
    }

    /**
     * Ejecuta la llamada reintentando los fallos transitorios.
     *
     * <p>Los planes gratuitos devuelven 429 y 503 con frecuencia; sin reintentos, una
     * petición perfectamente válida falla ante el usuario.
     */
    private <R> R conReintentos(java.util.function.Supplier<R> operacion, String nombreModelo) {
        RuntimeException ultimo = null;
        for (int intento = 1; intento <= config.intentosMaximos(); intento++) {
            try {
                return operacion.get();
            } catch (RuntimeException e) {
                ErroresIA.ErrorAgente traducido = traducir(e, nombreModelo);
                boolean reintentable = esReintentable(traducido);
                if (!reintentable || intento == config.intentosMaximos()) {
                    throw traducido;
                }
                ultimo = traducido;
                // Retroceso exponencial con jitter, para no sincronizar reintentos.
                long espera = (long) (config.esperaBaseMillis() * Math.pow(2, intento - 1)
                        + Math.random() * 1000);
                LOG.warnf("Fallo transitorio de %s (intento %d/%d); reintento en %d ms: %s",
                        nombre(), intento, config.intentosMaximos(), espera,
                        traducido.getMessage());
                try {
                    Thread.sleep(espera);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw traducido;
                }
            }
        }
        throw ultimo != null
                ? ultimo
                : new ErroresIA.FalloDelProveedor(
                        "Fallo desconocido del proveedor " + nombre() + ".", 502, null);
    }

    private static boolean esReintentable(ErroresIA.ErrorAgente error) {
        return error instanceof ErroresIA.FalloDelProveedor
                && (error.estadoHttp() == 429 || error.estadoHttp() == 502);
    }

    /** Traduce la excepción del proveedor a algo accionable para el usuario. */
    protected ErroresIA.ErrorAgente traducir(Throwable error, String nombreModelo) {
        if (error instanceof ErroresIA.ErrorAgente yaTraducido) {
            return yaTraducido;
        }
        String mensaje = mensajeCompleto(error);
        String minusculas = mensaje.toLowerCase();

        if (contiene(minusculas, "api key", "api_key", "unauthorized", "401", "invalid key")) {
            return new ErroresIA.FalloDelProveedor(
                    "La clave de %s es inválida o falta. Revisa la configuración."
                            .formatted(etiqueta()),
                    401, error);
        }
        if (contiene(minusculas, "quota", "rate limit", "429", "resource_exhausted")) {
            return new ErroresIA.FalloDelProveedor(
                    "Se agotó la cuota de %s. Espera unos minutos o revisa los límites "
                            .formatted(etiqueta()) + "de tu plan.",
                    429, error);
        }
        if (contiene(minusculas, "not found", "404", "no longer available", "does not exist")) {
            return new ErroresIA.FalloDelProveedor(
                    ("El modelo '%s' no existe en %s o tu clave no tiene acceso a él. "
                            + "Consulta GET /api/proveedores para ver los disponibles.")
                            .formatted(nombreModelo, etiqueta()),
                    404, error);
        }
        if (contiene(minusculas, "safety", "blocked", "content filter", "prohibited")) {
            return new ErroresIA.RespuestaInutilizable(
                    "Los filtros de contenido de %s bloquearon la respuesta. "
                            .formatted(etiqueta())
                            + "Revisa el material enviado.");
        }
        if (contiene(minusculas, "503", "unavailable", "overloaded", "high demand",
                "500", "internal error", "timeout", "timed out")) {
            return new ErroresIA.FalloDelProveedor(
                    "El servicio de %s falló temporalmente. Reintenta.".formatted(etiqueta()),
                    502, error);
        }
        // Rama por defecto: lo inesperado. Justo por eso NO puede llevar el texto del
        // proveedor a la respuesta HTTP. Las cinco ramas anteriores clasifican lo
        // conocido y redactan un mensaje propio; aquí no se sabe qué contiene `mensaje`,
        // y en Google AI la clave de la API viaja en la cadena de consulta de la URL.
        //
        // El detalle no se pierde: viaja como causa de la excepción y `ManejadorErrores`
        // lo registra junto al identificador que sí ve el usuario.
        return new ErroresIA.FalloDelProveedor(
                ("%s devolvió un error que no se pudo clasificar. Reintenta; si persiste, "
                        + "cita el identificador de este error al reportarlo.")
                        .formatted(etiqueta()),
                502, error);
    }

    /** Los proveedores suelen anidar el detalle útil en la causa. */
    private static String mensajeCompleto(Throwable error) {
        StringBuilder acumulado = new StringBuilder();
        Throwable actual = error;
        int profundidad = 0;
        while (actual != null && profundidad < 5) {
            if (actual.getMessage() != null) {
                acumulado.append(actual.getMessage()).append(' ');
            }
            actual = actual.getCause();
            profundidad++;
        }
        return acumulado.isEmpty() ? String.valueOf(error) : acumulado.toString();
    }

    private static boolean contiene(String texto, String... agujas) {
        for (String aguja : agujas) {
            if (texto.contains(aguja)) {
                return true;
            }
        }
        return false;
    }
}
