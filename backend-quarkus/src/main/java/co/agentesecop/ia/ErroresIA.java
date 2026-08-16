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
