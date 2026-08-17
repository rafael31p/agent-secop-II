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
    private final ModeloDeLenguaje medido;

    @Inject
    public ModeloDeLenguajeResiliente(
            PoliticaDeResiliencia politica, @Medido ModeloDeLenguaje medido) {
        this.politica = politica;
        this.medido = medido;
    }

    @Override
    public <T> T estructurado(
            String sistema, String usuario, SeleccionDeModelo seleccion, Class<T> tipo) {
        return tipo.cast(politica.estructurado(sistema, usuario, seleccion, tipo));
    }

    /**
     * El flujo va sin política declarada, y es una omisión consciente.
     *
     * <p>Fault Tolerance sabe envolver métodos síncronos y {@code Uni}, pero no
     * {@code Multi}, y tampoco tendría mucho sentido: no se reintenta una respuesta que ya
     * empezó a emitirse al navegador. Lo que protege al chat es el mamparo que acota las
     * llamadas estructuradas —las caras— y el límite de peticiones por clave.
     */
    @Override
    public Multi<String> flujo(String sistema, List<Turno> turnos, SeleccionDeModelo seleccion) {
        return medido.flujo(sistema, turnos, seleccion);
    }
}
