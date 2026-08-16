package co.agentesecop.application.port.in;

import co.agentesecop.application.port.out.SeleccionDeModelo;
import co.agentesecop.domain.model.procurement.ProcesoDeContratacion;
import co.agentesecop.domain.model.proposal.Priorizacion;
import co.agentesecop.domain.shared.Listas;
import java.util.List;

/** Clasificar y ordenar procesos por su encaje con el oferente. */
public interface PriorizarProcesos {

    Priorizacion priorizar(ComandoDePriorizacion comando);

    record ComandoDePriorizacion(
            List<ProcesoDeContratacion> procesos,
            String perfilProveedor,
            int maximo,
            SeleccionDeModelo seleccion) {

        public ComandoDePriorizacion {
            procesos = Listas.copiaOVacia(procesos);
        }
    }
}
