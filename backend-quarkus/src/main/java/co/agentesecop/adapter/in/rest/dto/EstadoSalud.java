package co.agentesecop.adapter.in.rest.dto;

import java.util.List;

/**
 * Informe de operación del servicio.
 *
 * <p>Vivía en {@code dominio/Secop.java} y no pertenece ahí: el dominio de este sistema
 * es la contratación pública, y «qué proveedores tienen clave configurada» es una
 * pregunta sobre la instalación, no sobre un proceso de contratación. Estar en el mismo
 * archivo que {@code ProcesoResumen} era un accidente de organización por tipo técnico.
 */
public record EstadoSalud(
        String estado,
        String version,
        String proveedorIaPorDefecto,
        String modeloPorDefecto,
        List<String> proveedoresConfigurados,
        boolean iaConfigurada,
        String secopDatasetProcesos,
        boolean secopTokenConfigurado) {}
