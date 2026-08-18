package co.agentesecop.adapter.out.llm.error;

/**
 * Raíz de los fallos que el agente sabe explicar.
 *
 * <h2>Qué sustituye, y por qué</h2>
 *
 * <p>Una clase única, {@code ia.ErroresIA}, con las nueve excepciones anidadas dentro y un
 * {@code estadoHttp} en la base. Tenía tres problemas y el tercero era un defecto en
 * producción:
 *
 * <ol>
 *   <li><b>Crecía sin límite.</b> Cada situación nueva añadía una clase anidada a un
 *       archivo que ya nadie leía entero.
 *   <li><b>El código HTTP viajaba dentro de la excepción.</b> Una decisión de transporte
 *       incrustada en el núcleo: la misma excepción es un 429 por HTTP y no es nada por
 *       una cola de mensajes. Ahora el estado lo pone el mapeador, que es adaptador de
 *       entrada y sí tiene por qué saber de HTTP.
 *   <li><b>Un solo mapeador para toda la jerarquía deja fuera lo que no herede de ella,
 *       en silencio.</b> Ocurrió: {@code domain.shared.PeticionInvalida} se movió al
 *       dominio en la fase 2, dejó de heredar de la base, y desde entonces
 *       {@code POST /api/propuestas/generar} sin requisitos ni pliego devolvía
 *       <em>500 Internal Server Error</em> sin {@code detail} ni identificador —el defecto
 *       exacto que el manejador existía para evitar—. Con un mapeador por excepción, un
 *       tipo sin mapear se nota al escribirlo.
 * </ol>
 *
 * <h2>La regla que no cambia</h2>
 *
 * <p>El mensaje de estas excepciones lo <b>redacta este código</b> y es seguro por
 * construcción: puede ir tal cual a la respuesta HTTP. El texto del proveedor viaja como
 * {@code causa} y solo se registra. No es pudor: la API de Google AI lleva la clave en la
 * cadena de consulta, así que un error que incluya la URL incluye la credencial.
 *
 * <p>La jerarquía es sellada para que el compilador obligue a decidir dónde encaja cada
 * caso nuevo, en vez de dejar que se cuele una excepción sin mapeador.
 */
public abstract sealed class ErrorDelAgente extends RuntimeException
        permits ContextoDemasiadoGrande,
                FalloDelProveedor,
                IdentificadorDeModeloInvalido,
                ProveedorDesconocido,
                ProveedorNoConfigurado,
                ProveedorNoDisponible,
                RespuestaInutilizable {

    protected ErrorDelAgente(String mensaje) {
        super(mensaje);
    }

    protected ErrorDelAgente(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
