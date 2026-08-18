package co.agentesecop.adapter.out.llm.error;

/**
 * El identificador de modelo no tiene una forma admisible (422).
 *
 * <p>Antes se llamaba {@code ErroresIA.PeticionInvalida} y compartía nombre simple con
 * {@code domain.shared.PeticionInvalida}, que es otra cosa y sale con otro código. Dos
 * clases distintas con el mismo nombre a un import de distancia es una confusión esperando
 * a ocurrir; el nombre dice ahora qué petición es inválida y por qué.
 */
public final class IdentificadorDeModeloInvalido extends ErrorDelAgente {

    public IdentificadorDeModeloInvalido(String mensaje) {
        super(mensaje);
    }
}
