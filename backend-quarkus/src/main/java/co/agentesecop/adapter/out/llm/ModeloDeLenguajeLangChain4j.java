package co.agentesecop.adapter.out.llm;

import co.agentesecop.application.port.out.ModeloDeLenguaje;
import co.agentesecop.application.port.out.SeleccionDeModelo;
import co.agentesecop.ia.PeticionIA;
import co.agentesecop.ia.RegistroProveedores;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Implementa el puerto del modelo sobre el registro de proveedores existente.
 *
 * <p>Es una capa fina y a propósito: toda la lógica valiosa —caché de clientes,
 * reintentos con retroceso, imposición del esquema JSON, traducción de errores— ya vive
 * en {@code ia.ProveedorLangChain4j} y no se toca. Lo único que hace este adaptador es
 * traducir el vocabulario del puerto al de esa implementación.
 *
 * <p>Es también donde se decide qué proveedor atiende la llamada, a partir de lo que
 * traiga la selección. Los casos de uso no saben que existe esa decisión.
 */
@ApplicationScoped
public class ModeloDeLenguajeLangChain4j implements ModeloDeLenguaje {

    private final RegistroProveedores registro;

    @Inject
    public ModeloDeLenguajeLangChain4j(RegistroProveedores registro) {
        this.registro = registro;
    }

    @Override
    public <T> T estructurado(
            String sistema, String usuario, SeleccionDeModelo seleccion, Class<T> tipo) {
        return registro.resolver(seleccion.proveedor())
                .estructurado(PeticionIA.unica(sistema, usuario, seleccion.modelo()), tipo);
    }

    @Override
    public Multi<String> flujo(String sistema, List<Turno> turnos, SeleccionDeModelo seleccion) {
        List<PeticionIA.Turno> traducidos = turnos.stream()
                .map(turno -> new PeticionIA.Turno(
                        turno.rol() == Rol.ASISTENTE
                                ? PeticionIA.Rol.ASISTENTE
                                : PeticionIA.Rol.USUARIO,
                        turno.contenido()))
                .toList();

        return registro.resolver(seleccion.proveedor())
                .flujo(new PeticionIA(sistema, traducidos, seleccion.modelo()));
    }
}
