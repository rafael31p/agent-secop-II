package co.agentesecop.domain.model.proposal;

import co.agentesecop.domain.shared.CodedEnum;

/** Conclusión global de la validación de una propuesta. */
public enum Veredicto implements CodedEnum {

    APTA("apta"),
    APTA_CON_AJUSTES("apta_con_ajustes"),
    RIESGO_DE_RECHAZO("riesgo_de_rechazo"),
    NO_APTA("no_apta");

    private final String code;

    Veredicto(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
