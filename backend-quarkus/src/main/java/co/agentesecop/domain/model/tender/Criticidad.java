package co.agentesecop.domain.model.tender;

import co.agentesecop.domain.shared.CodedEnum;

/**
 * Qué tan exigible es un requisito del pliego.
 *
 * <p>El nombre de las constantes sigue en español a propósito: además de serializarse,
 * viaja al modelo de lenguaje dentro del esquema JSON, que LangChain4j deriva del
 * <em>nombre de la constante</em> y no del código. Los prompts instruyen al modelo con
 * «obligatorio», «ponderable»… así que renombrarlas aquí dejaría el esquema diciendo una
 * cosa y el prompt otra. Se renombran cuando el adaptador tenga su propio tipo de carga
 * útil; hasta entonces, {@code EsquemaDelModeloTest} vigila que no se desincronicen.
 */
public enum Criticidad implements CodedEnum {

    /** Su incumplimiento es causal de rechazo o de no habilitación. */
    OBLIGATORIO("obligatorio"),
    /** Otorga puntaje en la evaluación. */
    PONDERABLE("ponderable"),
    /** Suma sin ser exigido. */
    DESEABLE("deseable"),
    /** Contexto, no es exigencia. */
    INFORMATIVO("informativo");

    private final String code;

    Criticidad(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }

    /** Regla de negocio: solo lo obligatorio puede causar rechazo. */
    public boolean puedeCausarRechazo() {
        return this == OBLIGATORIO;
    }
}
