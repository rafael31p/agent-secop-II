package co.agentesecop.application.port.out;

import co.agentesecop.application.port.in.AnalizarPliego.ComandoDeAnalisis;
import co.agentesecop.application.port.in.ConversarConElAgente.ComandoDeChat;
import co.agentesecop.application.port.in.GenerarPropuesta.ComandoDePropuesta;
import co.agentesecop.application.port.in.PriorizarProcesos.ComandoDePriorizacion;
import co.agentesecop.application.port.in.ValidarPropuesta.ComandoDeValidacion;
import co.agentesecop.domain.model.tender.RequisitoTecnico;
import java.util.List;

/**
 * Cómo se le habla al modelo.
 *
 * <p>Es un puerto de salida, no una utilidad del caso de uso: el formato del prompt
 * —Markdown, secciones, bloques JSON— es un detalle de cómo se comunica uno con un modelo
 * de lenguaje, del mismo orden que el formato de una consulta SQL. Sacarlo de los
 * servicios los deja diciendo qué hace el negocio, y deja el cómo se prueba por separado.
 */
public interface RedactorDePrompts {

    /** Instrucciones de sistema para una tarea, incluido el rol y el marco normativo. */
    String sistema(Tarea tarea);

    String materialDelAnalisis(ComandoDeAnalisis comando);

    String insumosDeLaPropuesta(ComandoDePropuesta comando);

    String materialDeLaValidacion(ComandoDeValidacion comando, List<RequisitoTecnico> requisitos);

    String procesosAClasificar(ComandoDePriorizacion comando);

    /** El contexto de trabajo, si lo hay, como par de turnos que abre la conversación. */
    List<ModeloDeLenguaje.Turno> aperturaDelChat(ComandoDeChat comando);

    enum Tarea {
        ANALISIS,
        PROPUESTA,
        VALIDACION,
        PRIORIZACION,
        /** La única que no pide salida JSON: responde en texto conversacional. */
        CHAT
    }
}
