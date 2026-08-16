package co.agentesecop.application.port.in;

import co.agentesecop.application.port.out.SeleccionDeModelo;
import co.agentesecop.domain.model.tender.AnalisisDePliego;

/** Extraer de un pliego sus requisitos técnicos, riesgos y alertas normativas. */
public interface AnalizarPliego {

    AnalisisDePliego analizar(ComandoDeAnalisis comando);

    /**
     * Lo que hace falta para analizar.
     *
     * <p>No es el DTO HTTP: no lleva anotaciones de validación ni depende del protocolo.
     * El recurso REST traduce uno en otro, y esa traducción es lo que permite que el caso
     * de uso no sepa que existe HTTP.
     */
    record ComandoDeAnalisis(
            String textoPliego,
            String objetoContractual,
            String entidad,
            String modalidad,
            Double valorEstimado,
            String contextoProveedor,
            SeleccionDeModelo seleccion) {}
}
