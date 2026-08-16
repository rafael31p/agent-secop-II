package co.agentesecop.application.port.in;

import co.agentesecop.application.port.out.SeleccionDeModelo;
import co.agentesecop.domain.model.proposal.InformeDeCumplimiento;
import co.agentesecop.domain.model.tender.RequisitoTecnico;
import co.agentesecop.domain.shared.Listas;
import java.util.List;

/** Contrastar una propuesta contra los requisitos del pliego. */
public interface ValidarPropuesta {

    InformeDeCumplimiento validar(ComandoDeValidacion comando);

    record ComandoDeValidacion(
            String textoPropuesta,
            List<RequisitoTecnico> requisitos,
            String textoPliego,
            String objetoContractual,
            SeleccionDeModelo seleccion) {

        public ComandoDeValidacion {
            requisitos = Listas.copiaOVacia(requisitos);
        }

        public boolean traeRequisitos() {
            return !requisitos.isEmpty();
        }

        public boolean traePliego() {
            return textoPliego != null && !textoPliego.isBlank();
        }
    }
}
