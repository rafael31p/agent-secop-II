package co.agentesecop.domain.shared;

/**
 * La petición no trae lo mínimo para poder trabajar.
 *
 * <p>Vive en el dominio y no en el adaptador de IA, donde estaba: que una validación no
 * pueda hacerse sin requisitos ni pliego es una regla del negocio, no un problema del
 * proveedor de modelos. El adaptador de entrada la traduce a un 422.
 */
public class PeticionInvalida extends RuntimeException {

    public PeticionInvalida(String mensaje) {
        super(mensaje);
    }
}
