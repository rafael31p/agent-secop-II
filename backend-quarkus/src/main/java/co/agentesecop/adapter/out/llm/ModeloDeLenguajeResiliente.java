package co.agentesecop.adapter.out.llm;

import co.agentesecop.application.port.out.ModeloDeLenguaje;
import co.agentesecop.application.port.out.SeleccionDeModelo;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * La cima de la pila: lo que reciben los casos de uso cuando inyectan el puerto.
 *
 * <p>No decide nada. Su trabajo es que la política —que vive en
 * {@link PoliticaDeResiliencia} y trabaja sobre una firma borrada, por las razones que
 * allí se explican— se aplique de verdad, y devolver el tipo que el llamante pidió. El
 * {@code cast} es seguro por construcción: el objeto lo produjo el proveedor
 * deserializando contra ese mismo {@code tipo}.
 *
 * <p>El reparto es el que quería SPEC-BE-01: la aplicación pide un modelo de lenguaje y
 * recibe uno resiliente y medido sin escribir ni una línea sobre reintentos, sin poder
 * olvidarse de ellos, y sin que un proveedor nuevo tenga que volver a implementarlos.
 */
@ApplicationScoped
public class ModeloDeLenguajeResiliente implements ModeloDeLenguaje {

    private final PoliticaDeResiliencia politica;
    private final PoliticaDeStreaming streaming;

    @Inject
    public ModeloDeLenguajeResiliente(
            PoliticaDeResiliencia politica, PoliticaDeStreaming streaming) {
        this.politica = politica;
        this.streaming = streaming;
    }

    @Override
    public <T> T estructurado(
            String sistema, String usuario, SeleccionDeModelo seleccion, Class<T> tipo) {
        return tipo.cast(politica.estructurado(sistema, usuario, seleccion, tipo));
    }

    /**
     * El flujo tiene su propia política, y por buenas razones distintas.
     *
     * <p>No lleva reintentos ni cortacircuitos: no se reintenta una respuesta que ya empezó
     * a emitirse al navegador. Pero sí lleva una cota de conversaciones simultáneas, que es
     * lo que faltaba —ver {@link PoliticaDeStreaming}—. El Javadoc anterior afirmaba que el
     * chat quedaba protegido por el mamparo de las llamadas estructuradas; no era cierto,
     * porque los permisos se cuentan por método y el flujo no pasa por ese.
     */
    @Override
    public Multi<String> flujo(String sistema, List<Turno> turnos, SeleccionDeModelo seleccion) {
        return streaming.flujo(sistema, turnos, seleccion);
    }
}
