package co.agentesecop.domain.model.proposal;

import co.agentesecop.domain.shared.CodedEnum;

/** Resultado de contrastar un requisito contra la propuesta. */
public enum EstadoCumplimiento implements CodedEnum {

    CUMPLE("cumple"),
    CUMPLE_PARCIAL("cumple_parcial"),
    NO_CUMPLE("no_cumple"),
    NO_EVALUABLE("no_evaluable");

    private final String code;

    EstadoCumplimiento(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
