package co.agentesecop.adapter.in.rest.dto;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Descriptor de un proveedor de modelos, para que el frontend arme su selector.
 *
 * <p>Vivía en {@code dominio/Secop.java}, y eso hacía que el adaptador de IA dependiera
 * del dominio para describirse a sí mismo: la dependencia iba justo al revés de lo que
 * debe. Un proveedor de modelos de lenguaje no es un concepto de contratación pública.
 */
public record ProveedorDisponible(
        String nombre,
        String etiqueta,
        boolean configurado,
        @Schema(description = "Modelos sugeridos. No es exhaustivo: cualquier "
                + "identificador válido del proveedor se acepta.")
        List<String> modelos,
        String modeloPorDefecto,
        @Schema(description = "Por qué no está disponible, si no lo está.")
        String motivo) {}
