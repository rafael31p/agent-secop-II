package co.agentesecop.domain.model.procurement;

/** Texto extraído de un documento del proceso. */
public record TextoDeDocumento(
        String nombreArchivo,
        String tipo,
        int caracteres,
        Integer paginas,
        String texto,
        boolean truncado) {}
