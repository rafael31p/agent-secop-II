package co.agentesecop.adapter.out.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.agentesecop.application.port.in.AnalizarPliego.ComandoDeAnalisis;
import co.agentesecop.application.port.in.ConversarConElAgente.ComandoDeChat;
import co.agentesecop.application.port.in.GenerarPropuesta.ComandoDePropuesta;
import co.agentesecop.application.port.in.PriorizarProcesos.ComandoDePriorizacion;
import co.agentesecop.application.port.out.ModeloDeLenguaje.Rol;
import co.agentesecop.application.port.out.RedactorDePrompts.Tarea;
import co.agentesecop.application.port.out.SeleccionDeModelo;
import co.agentesecop.domain.model.procurement.ProcesoDeContratacion;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Composición de los prompts.
 *
 * <p>La prueba que justifica este archivo es {@link #elChatNoPideJson}. El prompt del
 * chat se construye quitando del prompt base el bloque que exige salida JSON, y eso se
 * hacía con un {@code replace} contra un literal de tres líneas copiado a mano en el
 * punto de uso. Cualquier cambio de espaciado en el original —incluido el de un
 * formateador automático— dejaba el {@code replace} sin efecto <strong>en silencio</strong>:
 * el prompt seguía pidiendo JSON, el chat empezaba a responder JSON, y nada fallaba.
 *
 * <p>Sin Quarkus: el redactor solo necesita un {@code ObjectMapper}.
 */
class RedactorDePromptsTest {

    private final RedactorDePromptsMarkdown redactor =
            new RedactorDePromptsMarkdown(new ObjectMapper());

    private static final String SENAL_DE_JSON = "Responde ÚNICAMENTE con un objeto JSON";

    @Test
    @DisplayName("El prompt del chat no arrastra la instrucción de responder JSON")
    void elChatNoPideJson() {
        String sistema = redactor.sistema(Tarea.CHAT);

        assertFalse(sistema.contains(SENAL_DE_JSON),
                "El chat responde en texto conversacional. Si esto falla, el bloque de "
                        + "formato JSON cambió y el `replace` dejó de coincidir: el chat "
                        + "empezará a responder JSON sin que nada más avise.");
        // Y sí conserva el resto: no se ha vaciado el prompt por accidente.
        assertTrue(sistema.contains("contratación"), sistema.substring(0, 120));
        assertTrue(sistema.length() > 500, "El prompt del chat quedó sospechosamente corto.");
    }

    @Test
    @DisplayName("Las tareas estructuradas sí piden JSON")
    void lasDemasTareasPidenJson() {
        for (Tarea tarea : List.of(
                Tarea.ANALISIS, Tarea.PROPUESTA, Tarea.VALIDACION, Tarea.PRIORIZACION)) {
            assertTrue(redactor.sistema(tarea).contains(SENAL_DE_JSON),
                    "La tarea " + tarea + " se vincula a un record y necesita JSON.");
        }
    }

    @Test
    @DisplayName("El material del análisis omite los campos que no vienen")
    void materialSinCamposVacios() {
        String material = redactor.materialDelAnalisis(new ComandoDeAnalisis(
                "Texto del pliego", null, null, null, null, null,
                SeleccionDeModelo.porDefecto()));

        assertFalse(material.contains("Entidad:"),
                "Una etiqueta sin valor gasta tokens y confunde al modelo:\n" + material);
        assertTrue(material.contains("Texto del pliego"));
    }

    @Test
    @DisplayName("El material del análisis incluye lo que sí viene")
    void materialConDatos() {
        String material = redactor.materialDelAnalisis(new ComandoDeAnalisis(
                "Pliego", "Portal ciudadano", "MinTIC", "Licitación", 1_000_000.0,
                "Fábrica de software", SeleccionDeModelo.porDefecto()));

        assertTrue(material.contains("**Entidad:** MinTIC"));
        assertTrue(material.contains("**Objeto contractual:** Portal ciudadano"));
        assertTrue(material.contains("Contexto del oferente"));
        // Miles con separador: es lo que hace legible una cifra en pesos colombianos.
        assertTrue(material.contains("1.000.000") || material.contains("1,000,000"), material);
    }

    @Test
    @DisplayName("La priorización envía solo los campos que sirven para clasificar")
    void priorizacionEnviaLoNecesario() {
        var proceso = new ProcesoDeContratacion(
                "CO1", "LP-001", "MinTIC", "899999", "Bogotá", "Bogotá", "Portal",
                "Licitación", "Publicado", "Prestación", "Nacional", "No", 1000.0,
                "2026-01-01", "2026-01-02", "https://secop.example/proceso",
                "V1.81112", "12 meses", 47, List.of("software", "portal"));

        String contenido = redactor.procesosAClasificar(new ComandoDePriorizacion(
                List.of(proceso), null, 15, SeleccionDeModelo.porDefecto()));

        // La lista va como tabla TOON: los campos se declaran una vez en la cabecera y
        // cada proceso es una fila. Antes iba como JSON con sangrado, repitiendo los diez
        // nombres de campo en cada elemento.
        assertTrue(contenido.contains("procesos[1]{id,entidad,objeto,"), contenido);
        assertTrue(contenido.contains("Portal"), contenido);
        // Lo que no aporta a la decisión no se manda: son tokens que se pagan.
        assertFalse(contenido.contains("secop.example"),
                "La URL no ayuda a clasificar y ocupa contexto:\n" + contenido);
        assertFalse(contenido.contains("senalesTi"),
                "El puntaje heurístico local no es insumo para el modelo.");
    }

    @Test
    @DisplayName("Sin contexto, el chat no abre con turnos de relleno")
    void chatSinContexto() {
        var apertura = redactor.aperturaDelChat(
                new ComandoDeChat(List.of(), null, SeleccionDeModelo.porDefecto()));

        assertEquals(List.of(), apertura);
    }

    @Test
    @DisplayName("Con contexto, el chat abre con el par usuario/asistente")
    void chatConContexto() {
        var apertura = redactor.aperturaDelChat(new ComandoDeChat(
                List.of(), "Requisitos del pliego", SeleccionDeModelo.porDefecto()));

        assertEquals(2, apertura.size());
        assertEquals(Rol.USUARIO, apertura.get(0).rol());
        assertTrue(apertura.get(0).contenido().contains("Requisitos del pliego"));
        // El turno del asistente evita que el modelo trate el contexto como la pregunta.
        assertEquals(Rol.ASISTENTE, apertura.get(1).rol());
    }

    @Test
    @DisplayName("La propuesta sin requisitos estructurados cae al texto del pliego")
    void propuestaConPliego() {
        String insumos = redactor.insumosDeLaPropuesta(new ComandoDePropuesta(
                "Objeto", "Perfil del oferente", List.of(), "Texto del pliego",
                null, null, null, List.of(), SeleccionDeModelo.porDefecto()));

        assertFalse(insumos.contains("Requisitos técnicos identificados"));
        assertTrue(insumos.contains("Texto del pliego (referencia)"));
    }
}
