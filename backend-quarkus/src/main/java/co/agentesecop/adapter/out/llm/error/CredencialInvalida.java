package co.agentesecop.adapter.out.llm.error;

/** La clave del proveedor es inválida o falta (401). Reintentar no la arregla. */
public final class CredencialInvalida extends FalloDelProveedor {

    public CredencialInvalida(String etiquetaDelProveedor, Throwable causa) {
        super("La clave de %s es inválida o falta. Revisa la configuración."
                .formatted(etiquetaDelProveedor), causa);
    }
}
