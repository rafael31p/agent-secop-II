package co.agentesecop.ia;

import co.agentesecop.adapter.out.llm.error.ContextoDemasiadoGrande;
import co.agentesecop.adapter.out.llm.error.CredencialInvalida;
import co.agentesecop.adapter.out.llm.error.CuotaAgotada;
import co.agentesecop.adapter.out.llm.error.ErrorDelAgente;
import co.agentesecop.adapter.out.llm.error.IdentificadorDeModeloInvalido;
import co.agentesecop.adapter.out.llm.error.ModeloDesconocido;
import co.agentesecop.adapter.out.llm.error.ProveedorNoConfigurado;
import co.agentesecop.adapter.out.llm.error.RespuestaInutilizable;
import co.agentesecop.adapter.out.llm.error.ServicioDelProveedorCaido;
import co.agentesecop.config.ConfiguracionIA;
import co.agentesecop.domain.shared.Texto;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * Base común de los proveedores implementados sobre LangChain4j.
 *
 * <p>Concentra lo que no depende del proveedor: caché de modelos, imposición del esquema
 * JSON y traducción de errores. Cada subclase solo construye su modelo.
 */
public abstract class ProveedorLangChain4j implements ProveedorIA {

    private static final Logger LOG = Logger.getLogger(ProveedorLangChain4j.class);

    /** Tope de caracteres por petición, con margen para el razonamiento y la respuesta. */
    public static final int LIMITE_CARACTERES = 800_000;

    protected final ConfiguracionIA config;
    private final ObjectMapper jackson;

    /**
     * Cuánto puede acumularse sin que el cliente lea.
     *
     * <p>Mil fragmentos son una respuesta larguísima; más que eso no es un usuario
     * leyendo despacio, es un consumidor que no consume. Tener un techo convierte la fuga
     * de memoria en un fallo acotado y visible.
     */
    private static final int TOPE_DE_FRAGMENTOS_EN_VUELO = 1_000;

    /** Forma admitida de un identificador de modelo. Ver {@link #resolverModelo}. */
    private static final Pattern FORMA_DE_MODELO =
            Pattern.compile("[a-zA-Z0-9._:\\-]{1,80}");

    /**
     * Construir un modelo abre un cliente HTTP; se cachean por nombre para no repetirlo en
     * cada petición. Ver {@link CacheDeModelos}, que es donde vive todo lo delicado: el
     * tope, la expulsión y —sobre todo— que construir uno no detenga a los demás.
     */
    private final CacheDeModelos<ChatModel> modelosCacheados;
    private final CacheDeModelos<StreamingChatModel> modelosFlujoCacheados;

    protected ProveedorLangChain4j(
            ConfiguracionIA config, ObjectMapper jackson, CierreDiferido cierreDiferido) {
        this.config = config;
        this.jackson = jackson;
        this.modelosCacheados = new CacheDeModelos<>(cierreDiferido);
        this.modelosFlujoCacheados = new CacheDeModelos<>(cierreDiferido);
    }

    /** Construye el modelo síncrono del proveedor concreto. */
    protected abstract ChatModel construirModelo(String nombreModelo);

    /** Construye el modelo de streaming del proveedor concreto. */
    protected abstract StreamingChatModel construirModeloFlujo(String nombreModelo);

    @Override
    public String motivoNoDisponible() {
        return configurado() ? null : "Falta configurar la clave de " + etiqueta() + ".";
    }

    /**
     * Resuelve el modelo a usar y comprueba su forma.
     *
     * <p>La validación se repite aquí aunque el contrato HTTP ya la haga: este nombre
     * termina siendo la clave de una caché en memoria y parte de una petición saliente, y
     * no todos los caminos hasta aquí pasan por Bean Validation —las pruebas y cualquier
     * llamada interna futura, por ejemplo—. Es la frontera que de verdad importa.
     */
    protected String resolverModelo(String solicitado) {
        if (solicitado == null || solicitado.isBlank()) {
            return modeloPorDefecto();
        }
        String limpio = solicitado.trim();
        if (!FORMA_DE_MODELO.matcher(limpio).matches()) {
            throw new IdentificadorDeModeloInvalido(
                    "El identificador de modelo no tiene una forma válida. Consulta "
                            + "GET /api/proveedores para ver los disponibles.");
        }
        return limpio;
    }

    private void exigirConfigurado() {
        if (!configurado()) {
            throw new ProveedorNoConfigurado(motivoNoDisponible());
        }
    }

    private void exigirTamanoRazonable(PeticionIA peticion) {
        int caracteres = peticion.caracteres();
        if (caracteres > LIMITE_CARACTERES) {
            throw new ContextoDemasiadoGrande(
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
        ChatModel modelo = modelosCacheados.obtener(nombreModelo, this::construirModelo);

        ChatRequest solicitud = ChatRequest.builder()
                .messages(mensajes(peticion))
                .responseFormat(formatoJson(tipo))
                .build();

        ChatResponse respuesta;
        try {
            respuesta = modelo.chat(solicitud);
        } catch (RuntimeException e) {
            // Traducir aquí y no arriba es lo que permite que la política de resiliencia
            // se declare: quien decora este puerto ve tipos, no cadenas del proveedor.
            throw traducir(e, nombreModelo);
        }

        String texto = Optional.ofNullable(respuesta.aiMessage())
                .map(AiMessage::text)
                .map(String::trim)
                .orElse("");

        if (texto.isEmpty()) {
            throw new RespuestaInutilizable(
                    "El modelo devolvió una respuesta vacía (motivo: %s)."
                            .formatted(respuesta.finishReason()));
        }

        try {
            return jackson.readValue(recortarACuerpoJson(texto), tipo);
        } catch (Exception e) {
            // El texto es la respuesta del modelo: entra sin más control que el de su
            // proveedor, y sin sanear puede plantar líneas falsas en el registro.
            LOG.warnf("Respuesta no conforme al esquema (%s): %s",
                    nombreModelo, Texto.paraRegistro(texto, 500));
            throw new RespuestaInutilizable(
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
                modelosFlujoCacheados.obtener(nombreModelo, this::construirModeloFlujo);

        ChatRequest solicitud = ChatRequest.builder().messages(mensajes(peticion)).build();

        return Multi.createFrom().<String>emitter(
                emisor -> {
                    // Si el cliente se va, deja de emitirse. No aborta la llamada al
                    // proveedor —LangChain4j no expone ningún asidero para cancelar una
                    // respuesta en curso— pero sí corta el consumo de memoria: sin esto,
                    // el manejador seguía llamando a `emit` sobre un emisor muerto para
                    // una respuesta que ya nadie iba a leer.
                    AtomicBoolean vivo = new AtomicBoolean(true);
                    emisor.onTermination(() -> vivo.set(false));

                    modelo.chat(solicitud, new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String fragmento) {
                            if (vivo.get() && fragmento != null && !fragmento.isEmpty()) {
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
                    });
                },
                // Buffer ACOTADO, y ahí está el cambio. Sin este parámetro, el emisor usa
                // BUFFER sin cota: un cliente lento —o una pestaña en segundo plano—
                // acumulaba fragmentos en memoria sin techo mientras el proveedor
                // entregaba a toda velocidad. Descartar fragmentos no es opción, porque
                // mutilaría la respuesta; lo correcto es que un consumidor que no consume
                // produzca un fallo acotado y visible en vez de crecer sin control.
                TOPE_DE_FRAGMENTOS_EN_VUELO);
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
     * Traduce la excepción del proveedor a algo accionable para el usuario.
     *
     * <p>Aquí acabó el reintento artesanal que vivía en esta clase. Eran unas treinta
     * líneas con {@code Thread.sleep} que retenían un hilo de plataforma hasta quince
     * minutos en el peor caso, sin cortacircuitos —la petición mil pagaba los mismos tres
     * intentos que la primera— y sin métricas. Lo sustituye
     * {@code adapter.out.llm.PoliticaDeResiliencia}, que la declara con anotaciones de
     * MicroProfile Fault Tolerance.
     *
     * <p>Lo que queda de aquel código es lo único que no era reimplementar el estándar: la
     * <em>clasificación</em>. El tipo que devuelve este método es lo que decide si se
     * reintenta, porque {@code @Retry(retryOn = …)} razona sobre clases.
     */
    protected ErrorDelAgente traducir(Throwable error, String nombreModelo) {
        if (error instanceof ErrorDelAgente yaTraducido) {
            return yaTraducido;
        }
        String mensaje = mensajeCompleto(error);
        String minusculas = mensaje.toLowerCase();

        if (contiene(minusculas, "api key", "api_key", "unauthorized", "401", "invalid key")) {
            return new CredencialInvalida(etiqueta(), error);
        }
        if (contiene(minusculas, "quota", "rate limit", "429", "resource_exhausted")) {
            return new CuotaAgotada(etiqueta(), error);
        }
        if (contiene(minusculas, "not found", "404", "no longer available", "does not exist")) {
            return new ModeloDesconocido(nombreModelo, etiqueta(), error);
        }
        if (contiene(minusculas, "safety", "blocked", "content filter", "prohibited")) {
            return new RespuestaInutilizable(
                    "Los filtros de contenido de %s bloquearon la respuesta. "
                            .formatted(etiqueta())
                            + "Revisa el material enviado.");
        }
        if (contiene(minusculas, "503", "unavailable", "overloaded", "high demand",
                "500", "internal error", "timeout", "timed out")) {
            return ServicioDelProveedorCaido.temporal(etiqueta(), error);
        }
        // Rama por defecto: lo inesperado. Justo por eso NO puede llevar el texto del
        // proveedor a la respuesta HTTP. Las cinco ramas anteriores clasifican lo
        // conocido y redactan un mensaje propio; aquí no se sabe qué contiene `mensaje`,
        // y en Google AI la clave de la API viaja en la cadena de consulta de la URL.
        //
        // El detalle no se pierde: viaja como causa de la excepción y el mapeador
        // lo registra junto al identificador que sí ve el usuario.
        //
        // Se clasifica como transitorio, que es lo que ya hacía el reintento artesanal al
        // devolver 502: ante lo desconocido conviene reintentar una vez antes de rendirse.
        return ServicioDelProveedorCaido.sinClasificar(etiqueta(), error);
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
