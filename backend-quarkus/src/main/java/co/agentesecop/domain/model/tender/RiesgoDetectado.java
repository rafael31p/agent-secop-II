package co.agentesecop.domain.model.tender;

/** Riesgo identificado en el proceso, con su mitigación propuesta. */
public record RiesgoDetectado(
        String descripcion,
        NivelRiesgo nivel,
        String impacto,
        String mitigacion,
        TipoRiesgo tipo) {}
