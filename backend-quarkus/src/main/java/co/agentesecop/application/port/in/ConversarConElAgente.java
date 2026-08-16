package co.agentesecop.application.port.in;

import co.agentesecop.application.port.out.SeleccionDeModelo;
import co.agentesecop.domain.shared.Listas;
import io.smallrye.mutiny.Multi;
import java.util.List;

/** Consulta libre al agente, con respuesta progresiva. */
public interface ConversarConElAgente {

    Multi<String> conversar(ComandoDeChat comando);

    record ComandoDeChat(
            List<Mensaje> mensajes,
            String contexto,
            SeleccionDeModelo seleccion) {

        public ComandoDeChat {
            mensajes = Listas.copiaOVacia(mensajes);
        }

        public boolean traeContexto() {
            return contexto != null && !contexto.isBlank();
        }
    }

    /** Un mensaje del historial. El rol llega como texto desde el cliente. */
    record Mensaje(String rol, String contenido) {

        public boolean esDelAsistente() {
            return "assistant".equalsIgnoreCase(rol);
        }
    }
}
