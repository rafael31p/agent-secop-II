package co.agentesecop.domain.model.tender;

import co.agentesecop.domain.shared.CodedEnum;

/** Naturaleza del riesgo detectado en el proceso. */
public enum TipoRiesgo implements CodedEnum {

    TECNICO("tecnico"),
    JURIDICO("juridico"),
    FINANCIERO("financiero"),
    OPERATIVO("operativo"),
    CRONOGRAMA("cronograma"),
    /** Requisitos que restringen la pluralidad de oferentes. */
    COMPETENCIA("competencia");

    private final String code;

    TipoRiesgo(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
