package co.agentesecop.ia;

import io.smallrye.mutiny.Multi;
import java.util.List;

/**
 * Contrato de un proveedor de modelos de lenguaje.
 *
 * <p>Es una interfaz propia y no la de una librería concreta, a propósito: LangChain4j
 * es un detalle de implementación. Si su soporte de salidas estructuradas resulta
 * insuficiente para un proveedor, esa implementación se reescribe contra la API REST
 * nativa sin que el resto de la aplicación se entere.
 */
public interface ProveedorIA {

    /** Identificador estable usado en la API y en la configuración, ej. {@code gemini}. */
    String nombre();

    /** Nombre legible para mostrar en la interfaz. */
    String etiqueta();

    /** {@code true} si hay credenciales (o, en Ollama, si está habilitado). */
    boolean configurado();

    /** Explica por qué no está disponible. Nulo si lo está. */
    String motivoNoDisponible();

    /** Modelos sugeridos. No es exhaustivo: se acepta cualquier identificador válido. */
    List<String> modelosSugeridos();

    /** Modelo usado cuando la petición no especifica uno. */
    String modeloPorDefecto();

    /**
     * Pide una respuesta que cumpla el esquema JSON derivado de {@code tipo}.
     *
     * @throws ErroresIA.ProveedorNoConfigurado si faltan credenciales
     * @throws ErroresIA.FalloDelProveedor si el proveedor falla de forma no recuperable
     * @throws ErroresIA.RespuestaInutilizable si responde algo que no cumple el esquema
     */
    <T> T estructurado(PeticionIA peticion, Class<T> tipo);

    /** Emite la respuesta por fragmentos, para servirla como SSE. */
    Multi<String> flujo(PeticionIA peticion);
}
