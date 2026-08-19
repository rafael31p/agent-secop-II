package co.agentesecop.adapter.in.rest.error;

import co.agentesecop.domain.shared.PeticionInvalida;
import jakarta.ws.rs.ext.Provider;

/**
 * La petición no trae lo mínimo para trabajar. Regla de negocio, no fallo de proveedor.
 *
 * <h2>El mapeador que faltaba</h2>
 *
 * <p>{@code PeticionInvalida} se movió al dominio en la fase 2 —con razón: que no se pueda
 * validar sin requisitos ni pliego es una regla del negocio— y su documentación decía «el
 * adaptador de entrada la traduce a un 422». No lo hacía. Al dejar de heredar de la clase
 * base de errores del agente dejó de tener mapeador, y nadie se enteró porque el único
 * mapeador de entonces capturaba la jerarquía entera y lo que quedaba fuera caía en el
 * comportamiento por defecto.
 *
 * <p>El resultado, comprobado contra el servicio real: {@code POST /api/propuestas/generar}
 * sin requisitos ni pliego devolvía <em>500 Internal Server Error</em>, sin {@code detail}
 * y sin identificador de correlación. Justo el defecto que el manejador de errores existe
 * para evitar, reintroducido por la puerta de atrás.
 */
@Provider
public class MapeadorPeticionInvalida extends MapeadorDeError<PeticionInvalida> {

    @Override
    protected int estado() {
        return 422;
    }
}
