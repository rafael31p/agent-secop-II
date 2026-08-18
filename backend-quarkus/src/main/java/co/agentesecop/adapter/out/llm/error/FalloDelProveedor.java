package co.agentesecop.adapter.out.llm.error;

/**
 * El proveedor de modelos respondió mal, o no respondió.
 *
 * <p>Es intermedia y sellada porque lo que decide la política de resiliencia no es este
 * tipo sino sus hijos: {@link FalloTransitorio} se reintenta, {@link CredencialInvalida} y
 * {@link ModeloDesconocido} no. Tenerlos separados por tipo —y no por un {@code if} sobre
 * un código de estado— es lo que permite que {@code @Retry(retryOn = …)} exista.
 */
public abstract sealed class FalloDelProveedor extends ErrorDelAgente
        permits CredencialInvalida, FalloTransitorio, ModeloDesconocido {

    protected FalloDelProveedor(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
