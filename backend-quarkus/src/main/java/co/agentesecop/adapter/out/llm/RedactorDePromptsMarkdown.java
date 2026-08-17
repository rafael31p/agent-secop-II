package co.agentesecop.adapter.out.llm;

import co.agentesecop.application.port.in.AnalizarPliego.ComandoDeAnalisis;
import co.agentesecop.application.port.in.ConversarConElAgente.ComandoDeChat;
import co.agentesecop.application.port.in.GenerarPropuesta.ComandoDePropuesta;
import co.agentesecop.application.port.in.PriorizarProcesos.ComandoDePriorizacion;
import co.agentesecop.application.port.in.ValidarPropuesta.ComandoDeValidacion;
import co.agentesecop.application.port.out.ModeloDeLenguaje.Rol;
import co.agentesecop.application.port.out.ModeloDeLenguaje.Turno;
import co.agentesecop.application.port.out.RedactorDePrompts;
import co.agentesecop.domain.model.tender.RequisitoTecnico;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/**
 * Compone los prompts en Markdown.
 *
 * <p>Recoge los bloques de {@code StringBuilder} que ocupaban la mayor parte de
 * {@code AgenteSecop}. Que estén aquí y no en los casos de uso no es cosmética: el
 * formato con el que se le habla a un modelo es un detalle de este adaptador, del mismo
 * orden que la sintaxis de una consulta SQL, y mezclarlo con la lógica de negocio hacía
 * que ninguna de las dos se pudiera leer ni probar por separado.
 *
 * <p>El punto 2.5 del plan las llevará a plantillas Qute versionadas. Este paso las
 * agrupa y les pone una interfaz; el contenido de los prompts no se toca.
 */
@ApplicationScoped
public class RedactorDePromptsMarkdown implements RedactorDePrompts {

    private final ObjectMapper jackson;

    @Inject
    public RedactorDePromptsMarkdown(ObjectMapper jackson) {
        this.jackson = jackson;
    }

    @Override
    public String sistema(Tarea tarea) {
        return switch (tarea) {
            case ANALISIS -> conJson(Prompts.INSTRUCCION_ANALISIS);
            case PROPUESTA -> conJson(Prompts.INSTRUCCION_PROPUESTA);
            case VALIDACION -> conJson(Prompts.INSTRUCCION_VALIDACION);
            case PRIORIZACION -> conJson(Prompts.INSTRUCCION_RELEVANCIA);
            // El chat responde en texto conversacional: simplemente no se le añade
            // el bloque de formato JSON.
            case CHAT -> Prompts.SISTEMA_BASE + "\n\n" + Prompts.INSTRUCCION_CHAT;
        };
    }

    @Override
    public String materialDelAnalisis(ComandoDeAnalisis comando) {
        StringBuilder contenido = new StringBuilder("# Material del proceso a analizar\n\n");
        agregarSi(contenido, "Entidad", comando.entidad());
        agregarSi(contenido, "Objeto contractual", comando.objetoContractual());
        agregarSi(contenido, "Modalidad de selección", comando.modalidad());
        agregarValor(contenido, comando.valorEstimado());
        if (noVacio(comando.contextoProveedor())) {
            contenido.append("\n## Contexto del oferente (para calibrar riesgos)\n")
                    .append(comando.contextoProveedor())
                    .append('\n');
        }
        contenido.append("\n## Texto del pliego / anexo técnico / estudios previos\n\n")
                .append(comando.textoPliego());
        return contenido.toString();
    }

    @Override
    public String insumosDeLaPropuesta(ComandoDePropuesta comando) {
        StringBuilder contenido =
                new StringBuilder("# Insumos para redactar la propuesta técnica\n\n");
        contenido.append("**Objeto contractual:** ")
                .append(comando.objetoContractual())
                .append('\n');
        agregarSi(contenido, "Entidad contratante", comando.entidad());
        agregarValor(contenido, comando.valorEstimado());
        if (comando.plazoMeses() != null) {
            contenido.append("**Plazo de ejecución (meses):** ")
                    .append(comando.plazoMeses())
                    .append('\n');
        }
        if (!comando.enfasis().isEmpty()) {
            contenido.append("**Énfasis solicitado:** ")
                    .append(String.join(", ", comando.enfasis()))
                    .append('\n');
        }
        contenido.append("\n## Perfil y capacidades declaradas del oferente\n\n")
                .append(comando.perfilProveedor())
                .append('\n');
        if (!comando.requisitos().isEmpty()) {
            contenido.append("\n## Requisitos técnicos identificados (JSON)\n\n")
                    .append(comoJson(comando.requisitos()))
                    .append('\n');
        }
        if (noVacio(comando.textoPliego())) {
            contenido.append("\n## Texto del pliego (referencia)\n\n")
                    .append(comando.textoPliego());
        }
        return contenido.toString();
    }

    @Override
    public String materialDeLaValidacion(
            ComandoDeValidacion comando, List<RequisitoTecnico> requisitos) {
        StringBuilder contenido =
                new StringBuilder("# Validación de propuesta contra requisitos\n\n");
        agregarSi(contenido, "Objeto contractual", comando.objetoContractual());
        contenido.append("\n## Requisitos a verificar (JSON)\n\n")
                .append(comoJson(requisitos))
                .append("\n\n## Texto de la propuesta a evaluar\n\n")
                .append(comando.textoPropuesta());
        return contenido.toString();
    }

    @Override
    public String procesosAClasificar(ComandoDePriorizacion comando) {
        // Se envía solo lo necesario para clasificar: mandar el registro completo
        // gastaría tokens en campos que no aportan a la decisión.
        List<ResumenParaClasificar> resumidos = comando.procesos().stream()
                .map(p -> new ResumenParaClasificar(
                        p.id(), p.entidad(), p.objeto(), p.modalidad(), p.estado(),
                        p.tipoContrato(), p.valor(), p.duracion(), p.fechaPublicacion(),
                        p.departamento()))
                .toList();

        StringBuilder contenido = new StringBuilder("# Procesos a clasificar y priorizar\n\n");
        contenido.append("Devuelve como máximo ")
                .append(comando.maximo())
                .append(" procesos priorizados.\n");
        if (noVacio(comando.perfilProveedor())) {
            contenido.append("\n## Perfil del proveedor\n\n")
                    .append(comando.perfilProveedor())
                    .append('\n');
        }
        contenido.append("\n## Procesos (JSON)\n\n").append(comoJson(resumidos));
        return contenido.toString();
    }

    @Override
    public List<Turno> aperturaDelChat(ComandoDeChat comando) {
        if (!comando.traeContexto()) {
            return List.of();
        }
        List<Turno> apertura = new ArrayList<>();
        apertura.add(new Turno(Rol.USUARIO,
                "Contexto de trabajo para esta conversación (material del proceso o de "
                        + "la propuesta):\n\n" + comando.contexto()));
        apertura.add(new Turno(Rol.ASISTENTE, "Contexto recibido. ¿Qué necesitas analizar?"));
        return apertura;
    }

    private record ResumenParaClasificar(
            String id, String entidad, String objeto, String modalidad, String estado,
            String tipoContrato, Double valor, String duracion, String fechaPublicacion,
            String departamento) {}

    /** Base, más la exigencia de JSON, más la instrucción de la tarea. */
    private static String conJson(String instruccion) {
        return Prompts.SISTEMA_BASE + "\n" + Prompts.FORMATO_JSON + "\n" + instruccion;
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

    private static void agregarValor(StringBuilder destino, Double valorEstimado) {
        if (valorEstimado != null) {
            destino.append("**Valor estimado (COP):** %,.0f%n".formatted(valorEstimado));
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
