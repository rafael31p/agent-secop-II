package co.agentesecop.adapter.out.llm.error;

/**
 * Se acabó la cuota del plan (429).
 *
 * <p>Existe como tipo propio, y no como un {@code FalloTransitorio} con estado 429, porque
 * es la diferencia entre decirle al usuario «espera unos minutos, es tu cuota» y decirle
 * «el servicio falló». Lo primero le dice qué hacer.
 */
public final class CuotaAgotada extends FalloTransitorio {

    public CuotaAgotada(String etiquetaDelProveedor, Throwable causa) {
        super("Se agotó la cuota de %s. Espera unos minutos o revisa los límites de tu plan."
                .formatted(etiquetaDelProveedor), causa);
    }
}
