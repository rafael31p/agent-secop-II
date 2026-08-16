package co.agentesecop.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.agentesecop.domain.model.proposal.EstadoCumplimiento;
import co.agentesecop.domain.model.proposal.InformeDeCumplimiento;
import co.agentesecop.domain.model.proposal.ItemCumplimiento;
import co.agentesecop.domain.model.proposal.Propuesta;
import co.agentesecop.domain.model.proposal.Veredicto;
import co.agentesecop.domain.model.tender.AnalisisDePliego;
import co.agentesecop.domain.model.tender.Criticidad;
import co.agentesecop.domain.model.tender.RequisitoTecnico;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reglas de negocio del dominio, en JUnit puro.
 *
 * <p>Sin {@code @QuarkusTest}, sin contenedor y sin red: es la prueba de que el dominio
 * quedó libre de frameworks. Corre en milisegundos, y esa diferencia importa —las pruebas
 * que arrancan Quarkus tardan segundos, y una suite lenta se ejecuta menos—.
 *
 * <p>Las reglas que verifica no son adorno. La distinción entre «restar puntaje» y «quedar
 * fuera del proceso» es la que decide si vale la pena presentarse a una licitación, y
 * hasta ahora vivía repartida entre el prompt y la cabeza de quien leía el resultado.
 */
class ReglasDeNegocioTest {

    private static RequisitoTecnico requisito(Criticidad criticidad) {
        return new RequisitoTecnico("RT-01", "Seguridad", "Cifrado en reposo",
                criticidad, "Certificado", null, null);
    }

    private static ItemCumplimiento item(Criticidad criticidad, EstadoCumplimiento estado) {
        return new ItemCumplimiento("RT-01", "Cifrado", criticidad, estado, null, null, null);
    }

    @Test
    @DisplayName("Solo un requisito obligatorio puede causar rechazo")
    void soloLoObligatorioRechaza() {
        assertTrue(requisito(Criticidad.OBLIGATORIO).puedeCausarRechazo());
        assertFalse(requisito(Criticidad.PONDERABLE).puedeCausarRechazo());
        assertFalse(requisito(Criticidad.DESEABLE).puedeCausarRechazo());
        assertFalse(requisito(Criticidad.INFORMATIVO).puedeCausarRechazo());
    }

    @Test
    @DisplayName("Una criticidad ausente no se toma por obligatoria")
    void criticidadAusente() {
        // El modelo puede omitirla. Suponer «obligatorio» inventaría una causal de
        // rechazo que nadie escribió en el pliego.
        assertFalse(requisito(null).puedeCausarRechazo());
    }

    @Test
    @DisplayName("El análisis sabe cuáles de sus requisitos dejan fuera la oferta")
    void requisitosObligatorios() {
        var analisis = new AnalisisDePliego("r", null,
                List.of(requisito(Criticidad.OBLIGATORIO), requisito(Criticidad.DESEABLE)),
                null, null, null, null, null, "rec");

        assertEquals(1, analisis.obligatorios().size());
    }

    @Test
    @DisplayName("Incumplir un obligatorio es causal de rechazo; incumplir un ponderable no")
    void causalDeRechazo() {
        assertTrue(item(Criticidad.OBLIGATORIO, EstadoCumplimiento.NO_CUMPLE)
                .esCausalDeRechazo());
        // Resta puntaje, pero no deja fuera: la diferencia que decide si presentarse.
        assertFalse(item(Criticidad.PONDERABLE, EstadoCumplimiento.NO_CUMPLE)
                .esCausalDeRechazo());
    }

    @Test
    @DisplayName("Cumplir a medias un obligatorio no es todavía causal de rechazo")
    void cumplimientoParcial() {
        // Es subsanable en el régimen colombiano; tratarlo como rechazo desanimaría de
        // presentarse a quien solo tiene que aportar un documento.
        assertFalse(item(Criticidad.OBLIGATORIO, EstadoCumplimiento.CUMPLE_PARCIAL)
                .esCausalDeRechazo());
    }

    @Test
    @DisplayName("El informe destaca los incumplimientos que dejan fuera")
    void incumplimientosGraves() {
        var informe = new InformeDeCumplimiento(50, Veredicto.RIESGO_DE_RECHAZO, "r",
                List.of(
                        item(Criticidad.OBLIGATORIO, EstadoCumplimiento.NO_CUMPLE),
                        item(Criticidad.OBLIGATORIO, EstadoCumplimiento.CUMPLE),
                        item(Criticidad.PONDERABLE, EstadoCumplimiento.NO_CUMPLE)),
                null, null);

        assertEquals(1, informe.incumplimientosGraves().size());
    }

    @Test
    @DisplayName("Una propuesta con vacíos declarados no está lista para radicar")
    void propuestaConVacios() {
        var conVacios = new Propuesta("t", "r", null, null,
                List.of("No se acredita ISO 27001"), "# md");
        var completa = new Propuesta("t", "r", null, null, null, "# md");

        assertTrue(conVacios.requiereCompletarse());
        assertFalse(completa.requiereCompletarse());
    }

    @Test
    @DisplayName("Las listas nulas se normalizan a vacías, no explotan")
    void listasNulas() {
        var analisis = new AnalisisDePliego("r", null, null, null, null, null, null, null, "x");

        assertEquals(List.of(), analisis.requisitos());
        assertEquals(List.of(), analisis.alertasNormativas());
        assertEquals(List.of(), analisis.obligatorios());
    }
}
