package co.agentesecop.domain.model.tender;

import co.agentesecop.domain.shared.CodedEnum;

/** Gravedad de un riesgo detectado en el proceso. */
public enum NivelRiesgo implements CodedEnum {

    ALTO("alto"),
    MEDIO("medio"),
    BAJO("bajo");

    private final String code;

    NivelRiesgo(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
