package co.agentesecop.application.service;

import co.agentesecop.application.port.in.ConversarConElAgente;
import co.agentesecop.application.port.out.ModeloDeLenguaje;
import co.agentesecop.application.port.out.ModeloDeLenguaje.Rol;
import co.agentesecop.application.port.out.ModeloDeLenguaje.Turno;
import co.agentesecop.application.port.out.RedactorDePrompts;
import co.agentesecop.application.port.out.RedactorDePrompts.Tarea;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/** Consulta libre al agente experto. */
@ApplicationScoped
public class ServicioDeChat implements ConversarConElAgente {

    private final ModeloDeLenguaje modelo;
    private final RedactorDePrompts prompts;

    @Inject
    public ServicioDeChat(ModeloDeLenguaje modelo, RedactorDePrompts prompts) {
        this.modelo = modelo;
        this.prompts = prompts;
    }

    @Override
    public Multi<String> conversar(ComandoDeChat comando) {
        List<Turno> turnos = new ArrayList<>(prompts.aperturaDelChat(comando));
        for (Mensaje mensaje : comando.mensajes()) {
            turnos.add(new Turno(
                    mensaje.esDelAsistente() ? Rol.ASISTENTE : Rol.USUARIO,
                    mensaje.contenido()));
        }
        return modelo.flujo(prompts.sistema(Tarea.CHAT), turnos, comando.seleccion());
    }
}
