package co.agentesecop.ia;

/**
 * Excepciones del agente, cada una con el código HTTP que le corresponde.
 *
 * <p>Existen para que el mensaje explicativo llegue al usuario. En la versión Python el
 * error del proveedor se lanzaba como excepción genérica y salía como «500 Internal
 * Server Error», perdiendo toda la explicación; aquí el código viaja con la excepción y
 * {@code ManejadorErrores} lo traduce en un solo sitio.
 */
public final class ErroresIA {

    private ErroresIA() {}

    /** Base con el estado HTTP asociado. */
    public abstract static class ErrorAgente extends RuntimeException {
        private final int estadoHttp;

        protected ErrorAgente(String mensaje, int estadoHttp) {
            super(mensaje);
            this.estadoHttp = estadoHttp;
        }

        protected ErrorAgente(String mensaje, int estadoHttp, Throwable causa) {
            super(mensaje, causa);
            this.estadoHttp = estadoHttp;
        }

        public int estadoHttp() {
            return estadoHttp;
        }
    }

    /** Faltan credenciales del proveedor solicitado. */
    public static class ProveedorNoConfigurado extends ErrorAgente {
        public ProveedorNoConfigurado(String mensaje) {
            super(mensaje, 503);
        }
    }

    /** El proveedor pedido no existe. */
    public static class ProveedorDesconocido extends ErrorAgente {
        public ProveedorDesconocido(String mensaje) {
            super(mensaje, 400);
        }
    }

    /** El proveedor falló: cuota, saturación, modelo inexistente, red. */
    public static class FalloDelProveedor extends ErrorAgente {
        public FalloDelProveedor(String mensaje, int estadoHttp, Throwable causa) {
            super(mensaje, estadoHttp, causa);
        }
    }

    /**
     * Fallo que puede desaparecer solo: cuota agotada, saturación, corte de red.
     *
     * <p>Es un tipo y no un código HTTP a propósito. La política de resiliencia se declara
     * con {@code @Retry(retryOn = …)} y {@code @CircuitBreaker(failOn = …)}, y esas
     * anotaciones aceptan clases, no predicados. Mientras la clasificación vivió en un
     * {@code if} sobre {@code estadoHttp()}, la única forma de reintentar era escribir el
     * bucle a mano; con la jerarquía tipada, reintentar es una anotación.
     *
     * <p>Es también lo único que cuenta para abrir el cortacircuitos: una clave inválida
     * no debe envenenar al proveedor para todo el mundo.
     */
    public static class FalloTransitorio extends FalloDelProveedor {
        public FalloTransitorio(String mensaje, int estadoHttp, Throwable causa) {
            super(mensaje, estadoHttp, causa);
        }
    }

    /** La clave del proveedor es inválida o falta. Reintentar no la arregla. */
    public static class CredencialInvalida extends FalloDelProveedor {
        public CredencialInvalida(String mensaje, Throwable causa) {
            super(mensaje, 401, causa);
        }
    }

    /** El modelo pedido no existe o la clave no tiene acceso a él. Tampoco se reintenta. */
    public static class ModeloDesconocido extends FalloDelProveedor {
        public ModeloDesconocido(String mensaje, Throwable causa) {
            super(mensaje, 404, causa);
        }
    }

    /**
     * El proveedor no está en condiciones de atender: circuito abierto, mamparo lleno o
     * presupuesto de tiempo agotado.
     *
     * <p>Se distingue de {@link FalloTransitorio} porque no describe una llamada que
     * falló, sino una llamada que <em>no se hizo</em>. El mensaje debe ser accionable: hay
     * cinco proveedores configurables y decirle al usuario que puede cambiar de proveedor
     * convierte una caída total en una degradación.
     */
    public static class ProveedorNoDisponible extends ErrorAgente {
        public ProveedorNoDisponible(String mensaje) {
            super(mensaje, 503);
        }

        public ProveedorNoDisponible(String mensaje, Throwable causa) {
            super(mensaje, 503, causa);
        }
    }

    /** Respondió, pero con algo que no se puede usar: filtro, truncamiento, JSON inválido. */
    public static class RespuestaInutilizable extends ErrorAgente {
        public RespuestaInutilizable(String mensaje) {
            super(mensaje, 422);
        }

        public RespuestaInutilizable(String mensaje, Throwable causa) {
            super(mensaje, 422, causa);
        }
    }

    /** El material excede lo que se puede enviar en una sola solicitud. */
    public static class ContextoDemasiadoGrande extends ErrorAgente {
        public ContextoDemasiadoGrande(String mensaje) {
            super(mensaje, 413);
        }
    }

    /** La petición no trae lo mínimo para poder trabajar. */
    public static class PeticionInvalida extends ErrorAgente {
        public PeticionInvalida(String mensaje) {
            super(mensaje, 422);
        }
    }
}
