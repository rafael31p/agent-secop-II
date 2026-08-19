package co.agentesecop.application.service;

import co.agentesecop.application.port.in.AnalizarPliego;
import co.agentesecop.application.port.in.ValidarPropuesta;
import co.agentesecop.application.port.out.ModeloDeLenguaje;
import co.agentesecop.application.port.out.RedactorDePrompts;
import co.agentesecop.application.port.out.RedactorDePrompts.Tarea;
import co.agentesecop.domain.model.proposal.InformeDeCumplimiento;
import co.agentesecop.domain.model.tender.RequisitoTecnico;
import co.agentesecop.domain.shared.PeticionInvalida;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.eclipse.microprofile.faulttolerance.Timeout;

/**
 * Valida una propuesta contra los requisitos del pliego.
 *
 * <p>Cuando la petición no trae requisitos estructurados, los extrae antes de validar. Lo
 * hace <strong>invocando el caso de uso de análisis por su interfaz</strong>, no llamando
 * a un método de sí mismo: así la composición es visible en la firma, y el presupuesto de
 * tiempo puede acotar las dos llamadas encadenadas, que son las que hacen de este el
 * endpoint más caro.
 */
@ApplicationScoped
public class ServicioDeValidacion implements ValidarPropuesta {

    private final ModeloDeLenguaje modelo;
    private final RedactorDePrompts prompts;
    private final AnalizarPliego analisis;

    @Inject
    public ServicioDeValidacion(
            ModeloDeLenguaje modelo, RedactorDePrompts prompts, AnalizarPliego analisis) {
        this.modelo = modelo;
        this.prompts = prompts;
        this.analisis = analisis;
    }

    /**
     * El techo de la petición completa, no el de una llamada al modelo.
     *
     * <p>Es el único caso de uso que puede encadenar dos invocaciones —extraer requisitos
     * y luego validar contra ellos—, y ahí el timeout por llamada no acota nada: con la
     * configuración anterior podían sumar media hora de petición HTTP viva. 240 s por
     * encima de los 120 s por llamada deja margen para las dos y garantiza que ninguna
     * petición pueda vivir indefinidamente.
     */
    @Override
    @Timeout(value = 240_000, unit = ChronoUnit.MILLIS)
    public InformeDeCumplimiento validar(ComandoDeValidacion comando) {
        List<RequisitoTecnico> requisitos = requisitosDeReferencia(comando);
        return modelo.estructurado(
                prompts.sistema(Tarea.VALIDACION),
                prompts.materialDeLaValidacion(comando, requisitos),
                comando.seleccion(),
                InformeDeCumplimiento.class);
    }

    /** Validar contra nada no tendría sentido: o vienen dados, o se extraen del pliego. */
    private List<RequisitoTecnico> requisitosDeReferencia(ComandoDeValidacion comando) {
        if (comando.traeRequisitos()) {
            return comando.requisitos();
        }
        if (!comando.traePliego()) {
            throw new PeticionInvalida(
                    "Debes enviar requisitos estructurados o el texto del pliego para "
                            + "extraerlos antes de validar.");
        }
        return analisis.analizar(new AnalizarPliego.ComandoDeAnalisis(
                        comando.textoPliego(), comando.objetoContractual(),
                        null, null, null, null, comando.seleccion()))
                .requisitos();
    }
}
