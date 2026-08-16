package co.agentesecop.ia;

import co.agentesecop.config.ConfiguracionIA;
import co.agentesecop.dominio.Secop.ProveedorDisponible;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Punto único de resolución de proveedores.
 *
 * <p>CDI descubre todas las implementaciones de {@link ProveedorIA}; añadir un proveedor
 * nuevo es crear una clase, sin tocar este registro ni los servicios.
 */
@ApplicationScoped
public class RegistroProveedores {

    private final Map<String, ProveedorIA> porNombre = new LinkedHashMap<>();
    private final ConfiguracionIA config;

    @Inject
    public RegistroProveedores(Instance<ProveedorIA> proveedores, ConfiguracionIA config) {
        this.config = config;
        proveedores.stream()
                .sorted(Comparator.comparing(ProveedorIA::nombre))
                .forEach(p -> porNombre.put(p.nombre(), p));
    }

    /**
     * Devuelve el proveedor pedido, o el predeterminado si no se pide ninguno.
     *
     * @throws ErroresIA.ProveedorDesconocido si el nombre no corresponde a ninguno
     * @throws ErroresIA.ProveedorNoConfigurado si le faltan credenciales
     */
    public ProveedorIA resolver(String solicitado) {
        String nombre = (solicitado == null || solicitado.isBlank())
                ? config.proveedorPorDefecto()
                : solicitado.trim().toLowerCase();

        ProveedorIA proveedor = porNombre.get(nombre);
        if (proveedor == null) {
            throw new ErroresIA.ProveedorDesconocido(
                    "Proveedor '%s' desconocido. Disponibles: %s."
                            .formatted(nombre, String.join(", ", porNombre.keySet())));
        }
        if (!proveedor.configurado()) {
            throw new ErroresIA.ProveedorNoConfigurado(proveedor.motivoNoDisponible());
        }
        return proveedor;
    }

    /** Catálogo completo, incluidos los no configurados y su motivo. */
    public List<ProveedorDisponible> catalogo() {
        return porNombre.values().stream()
                .map(p -> new ProveedorDisponible(
                        p.nombre(),
                        p.etiqueta(),
                        p.configurado(),
                        p.modelosSugeridos(),
                        p.modeloPorDefecto(),
                        p.motivoNoDisponible()))
                .toList();
    }

    public List<String> nombresConfigurados() {
        return porNombre.values().stream()
                .filter(ProveedorIA::configurado)
                .map(ProveedorIA::nombre)
                .toList();
    }

    public boolean hayAlgunoConfigurado() {
        return porNombre.values().stream().anyMatch(ProveedorIA::configurado);
    }

    public String nombrePorDefecto() {
        return config.proveedorPorDefecto();
    }

    /** Modelo por defecto del proveedor predeterminado, para el informe de salud. */
    public String modeloPorDefecto() {
        ProveedorIA proveedor = porNombre.get(config.proveedorPorDefecto());
        return proveedor == null ? "(proveedor por defecto desconocido)" : proveedor.modeloPorDefecto();
    }
}
