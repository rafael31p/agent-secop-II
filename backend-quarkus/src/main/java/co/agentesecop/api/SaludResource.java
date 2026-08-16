package co.agentesecop.api;

import co.agentesecop.dominio.Secop.EstadoSalud;
import co.agentesecop.dominio.Secop.ProveedorDisponible;
import co.agentesecop.ia.RegistroProveedores;
import co.agentesecop.secop.SecopCliente;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Estado del servicio y catálogo de proveedores de IA. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "salud", description = "Estado y capacidades del servicio")
public class SaludResource {

    public static final String VERSION = "0.2.0";

    private final RegistroProveedores registro;
    private final SecopCliente secop;

    @Inject
    public SaludResource(RegistroProveedores registro, SecopCliente secop) {
        this.registro = registro;
        this.secop = secop;
    }

    @GET
    @Path("/salud")
    @Operation(summary = "Estado del servicio y de su configuración")
    public EstadoSalud salud() {
        boolean hayIa = registro.hayAlgunoConfigurado();
        return new EstadoSalud(
                hayIa ? "ok" : "degradado",
                VERSION,
                registro.nombrePorDefecto(),
                registro.modeloPorDefecto(),
                registro.nombresConfigurados(),
                hayIa,
                secop.datasetProcesos(),
                secop.tokenConfigurado());
    }

    /**
     * Catálogo para que el frontend arme el selector de proveedor y modelo. Incluye los
     * no configurados con su motivo, para poder mostrarlos deshabilitados y explicados.
     */
    @GET
    @Path("/proveedores")
    @Operation(summary = "Proveedores de IA disponibles y sus modelos sugeridos")
    public List<ProveedorDisponible> proveedores() {
        return registro.catalogo();
    }
}
