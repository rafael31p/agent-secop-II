package co.agentesecop.application.port.in;

import co.agentesecop.application.port.out.SeleccionDeModelo;
import co.agentesecop.domain.model.proposal.Propuesta;
import co.agentesecop.domain.model.tender.RequisitoTecnico;
import co.agentesecop.domain.shared.Listas;
import java.util.List;

/** Redactar un borrador de propuesta alineado a un pliego. */
public interface GenerarPropuesta {

    Propuesta generar(ComandoDePropuesta comando);

    record ComandoDePropuesta(
            String objetoContractual,
            String perfilProveedor,
            List<RequisitoTecnico> requisitos,
            String textoPliego,
            String entidad,
            Double valorEstimado,
            Integer plazoMeses,
            List<String> enfasis,
            SeleccionDeModelo seleccion) {

        public ComandoDePropuesta {
            requisitos = Listas.copiaOVacia(requisitos);
            enfasis = Listas.copiaOVacia(enfasis);
        }

        /** Sin requisitos ni pliego no hay nada contra qué alinear la propuesta. */
        public boolean sinReferencia() {
            return requisitos.isEmpty() && (textoPliego == null || textoPliego.isBlank());
        }
    }
}
