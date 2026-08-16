package co.agentesecop.servicio;

import co.agentesecop.dominio.Analisis.RequisitoTecnico;
import co.agentesecop.dominio.Analisis.RespuestaAnalisis;
import co.agentesecop.dominio.Propuestas.RespuestaPropuesta;
import co.agentesecop.dominio.Propuestas.RespuestaRelevancia;
import co.agentesecop.dominio.Propuestas.RespuestaValidacion;
import co.agentesecop.adapter.in.rest.dto.Solicitudes.SolicitudAnalisis;
import co.agentesecop.adapter.in.rest.dto.Solicitudes.SolicitudChat;
import co.agentesecop.adapter.in.rest.dto.Solicitudes.SolicitudPropuesta;
import co.agentesecop.adapter.in.rest.dto.Solicitudes.SolicitudRelevancia;
import co.agentesecop.adapter.in.rest.dto.Solicitudes.SolicitudValidacion;
import co.agentesecop.ia.ErroresIA;
import co.agentesecop.ia.PeticionIA;
import co.agentesecop.ia.ProveedorIA;
import co.agentesecop.ia.RegistroProveedores;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/**
 * Orquesta los casos de uso del agente.
 *
 * <p>No conoce ningún proveedor concreto: pide uno al registro según lo que traiga la
 * solicitud. Cambiar de Gemini a otro proveedor no toca esta clase.
 */
@ApplicationScoped
public class AgenteSecop {

    private final RegistroProveedores registro;
    private final ObjectMapper jackson;

    @Inject
    public AgenteSecop(RegistroProveedores registro, ObjectMapper jackson) {
        this.registro = registro;
        this.jackson = jackson;
    }

    // ------------------------------------------------------------------- análisis

    public RespuestaAnalisis analizarRequisitos(SolicitudAnalisis solicitud) {
        StringBuilder contenido = new StringBuilder("# Material del proceso a analizar\n\n");
        agregarSi(contenido, "Entidad", solicitud.entidad());
        agregarSi(contenido, "Objeto contractual", solicitud.objetoContractual());
        agregarSi(contenido, "Modalidad de selección", solicitud.modalidad());
        if (solicitud.valorEstimado() != null) {
            contenido.append("**Valor estimado (COP):** %,.0f%n"
                    .formatted(solicitud.valorEstimado()));
        }
        if (noVacio(solicitud.contextoProveedor())) {
            contenido.append("\n## Contexto del oferente (para calibrar riesgos)\n")
                    .append(solicitud.contextoProveedor())
                    .append('\n');
        }
        contenido.append("\n## Texto del pliego / anexo técnico / estudios previos\n\n")
                .append(solicitud.textoPliego());

        return proveedor(solicitud.proveedor()).estructurado(
                PeticionIA.unica(
                        sistema(Prompts.INSTRUCCION_ANALISIS),
                        contenido.toString(),
                        solicitud.modelo()),
                RespuestaAnalisis.class);
    }

    // ------------------------------------------------------------------ propuesta

    public RespuestaPropuesta generarPropuesta(SolicitudPropuesta solicitud) {
        if (solicitud.sinReferencia()) {
            throw new ErroresIA.PeticionInvalida(
                    "Envía requisitos estructurados o el texto del pliego; sin al menos uno "
                            + "de los dos la propuesta no puede alinearse al proceso.");
        }

        StringBuilder contenido =
                new StringBuilder("# Insumos para redactar la propuesta técnica\n\n");
        contenido.append("**Objeto contractual:** ")
                .append(solicitud.objetoContractual())
                .append('\n');
        agregarSi(contenido, "Entidad contratante", solicitud.entidad());
        if (solicitud.valorEstimado() != null) {
            contenido.append("**Valor estimado (COP):** %,.0f%n"
                    .formatted(solicitud.valorEstimado()));
        }
        if (solicitud.plazoMeses() != null) {
            contenido.append("**Plazo de ejecución (meses):** ")
                    .append(solicitud.plazoMeses())
                    .append('\n');
        }
        if (!solicitud.enfasis().isEmpty()) {
            contenido.append("**Énfasis solicitado:** ")
                    .append(String.join(", ", solicitud.enfasis()))
                    .append('\n');
        }
        contenido.append("\n## Perfil y capacidades declaradas del oferente\n\n")
                .append(solicitud.perfilProveedor())
                .append('\n');

        if (!solicitud.requisitos().isEmpty()) {
            contenido.append("\n## Requisitos técnicos identificados (JSON)\n\n")
                    .append(comoJson(solicitud.requisitos()))
                    .append('\n');
        }
        if (noVacio(solicitud.textoPliego())) {
            contenido.append("\n## Texto del pliego (referencia)\n\n")
                    .append(solicitud.textoPliego());
        }

        return proveedor(solicitud.proveedor()).estructurado(
                PeticionIA.unica(
                        sistema(Prompts.INSTRUCCION_PROPUESTA),
                        contenido.toString(),
                        solicitud.modelo()),
                RespuestaPropuesta.class);
    }

    // ----------------------------------------------------------------- validación

    public RespuestaValidacion validarPropuesta(SolicitudValidacion solicitud) {
        List<RequisitoTecnico> requisitos = solicitud.requisitos();

        // Sin requisitos estructurados hay que extraerlos del pliego primero: validar
        // contra nada no tendría sentido.
        if (requisitos.isEmpty()) {
            if (!noVacio(solicitud.textoPliego())) {
                throw new ErroresIA.PeticionInvalida(
                        "Debes enviar requisitos estructurados o el texto del pliego para "
                                + "extraerlos antes de validar.");
            }
            requisitos = analizarRequisitos(new SolicitudAnalisis(
                            solicitud.textoPliego(),
                            solicitud.objetoContractual(),
                            null, null, null, null,
                            solicitud.proveedor(),
                            solicitud.modelo()))
                    .requisitos();
        }

        StringBuilder contenido =
                new StringBuilder("# Validación de propuesta contra requisitos\n\n");
        agregarSi(contenido, "Objeto contractual", solicitud.objetoContractual());
        contenido.append("\n## Requisitos a verificar (JSON)\n\n")
                .append(comoJson(requisitos))
                .append("\n\n## Texto de la propuesta a evaluar\n\n")
                .append(solicitud.textoPropuesta());

        return proveedor(solicitud.proveedor()).estructurado(
                PeticionIA.unica(
                        sistema(Prompts.INSTRUCCION_VALIDACION),
                        contenido.toString(),
                        solicitud.modelo()),
                RespuestaValidacion.class);
    }

    // ----------------------------------------------------------------- relevancia

    public RespuestaRelevancia priorizarProcesos(SolicitudRelevancia solicitud) {
        // Se envía solo lo necesario para clasificar: mandar el registro completo
        // gastaría tokens en campos que no aportan a la decisión.
        List<Object> resumidos = new ArrayList<>();
        for (var p : solicitud.procesos()) {
            resumidos.add(new ResumenParaClasificar(
                    p.id(), p.entidad(), p.objeto(), p.modalidad(), p.estado(),
                    p.tipoContrato(), p.valor(), p.duracion(), p.fechaPublicacion(),
                    p.departamento()));
        }

        StringBuilder contenido = new StringBuilder("# Procesos a clasificar y priorizar\n\n");
        contenido.append("Devuelve como máximo ")
                .append(solicitud.maximo())
                .append(" procesos priorizados.\n");
        if (noVacio(solicitud.perfilProveedor())) {
            contenido.append("\n## Perfil del proveedor\n\n")
                    .append(solicitud.perfilProveedor())
                    .append('\n');
        }
        contenido.append("\n## Procesos (JSON)\n\n").append(comoJson(resumidos));

        return proveedor(solicitud.proveedor()).estructurado(
                PeticionIA.unica(
                        sistema(Prompts.INSTRUCCION_RELEVANCIA),
                        contenido.toString(),
                        solicitud.modelo()),
                RespuestaRelevancia.class);
    }

    private record ResumenParaClasificar(
            String id, String entidad, String objeto, String modalidad, String estado,
            String tipoContrato, Double valor, String duracion, String fechaPublicacion,
            String departamento) {}

    // ----------------------------------------------------------------------- chat

    public Multi<String> chat(SolicitudChat solicitud) {
        List<PeticionIA.Turno> turnos = new ArrayList<>();
        if (noVacio(solicitud.contexto())) {
            turnos.add(new PeticionIA.Turno(
                    PeticionIA.Rol.USUARIO,
                    "Contexto de trabajo para esta conversación (material del proceso o de "
                            + "la propuesta):\n\n" + solicitud.contexto()));
            turnos.add(new PeticionIA.Turno(
                    PeticionIA.Rol.ASISTENTE, "Contexto recibido. ¿Qué necesitas analizar?"));
        }
        for (var mensaje : solicitud.mensajes()) {
            turnos.add(new PeticionIA.Turno(
                    "assistant".equalsIgnoreCase(mensaje.rol())
                            ? PeticionIA.Rol.ASISTENTE
                            : PeticionIA.Rol.USUARIO,
                    mensaje.contenido()));
        }

        // El chat responde en texto conversacional, así que no lleva el bloque de
        // «formato de salida JSON» del prompt base.
        String sistema = Prompts.SISTEMA_BASE
                .replace("""
                        ## Formato de salida
                        Responde ÚNICAMENTE con un objeto JSON que cumpla el esquema solicitado. Sin
                        texto antes ni después, sin bloques de código Markdown.
                        """, "")
                + "\n\n" + Prompts.INSTRUCCION_CHAT;

        return proveedor(solicitud.proveedor())
                .flujo(new PeticionIA(sistema, turnos, solicitud.modelo()));
    }

    // ---------------------------------------------------------------- auxiliares

    private ProveedorIA proveedor(String solicitado) {
        return registro.resolver(solicitado);
    }

    private static String sistema(String instruccion) {
        return Prompts.SISTEMA_BASE + "\n\n" + instruccion;
    }

    private String comoJson(Object valor) {
        try {
            return "```json\n"
                    + jackson.writerWithDefaultPrettyPrinter().writeValueAsString(valor)
                    + "\n```";
        } catch (JsonProcessingException e) {
            // Inalcanzable con records del propio dominio; si ocurriera, es un error de
            // programación y no algo que el usuario pueda corregir.
            throw new IllegalStateException("No se pudo serializar el contexto", e);
        }
    }

    private static void agregarSi(StringBuilder destino, String etiqueta, String valor) {
        if (noVacio(valor)) {
            destino.append("**").append(etiqueta).append(":** ").append(valor).append('\n');
        }
    }

    private static boolean noVacio(String valor) {
        return valor != null && !valor.isBlank();
    }
}
