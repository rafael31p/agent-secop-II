package co.agentesecop.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.agentesecop.PerfilSinCredenciales;
import co.agentesecop.domain.model.tender.RequisitoTecnico;
import co.agentesecop.domain.model.proposal.ItemCumplimiento;
import co.agentesecop.domain.model.proposal.EstadoCumplimiento;
import co.agentesecop.domain.model.proposal.Veredicto;
import co.agentesecop.domain.model.tender.Criticidad;
import co.agentesecop.domain.model.tender.NivelRiesgo;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * El contrato por cable de las enumeraciones, tras sacarlas del dominio.
 *
 * <p>Se usa el {@link ObjectMapper} <strong>inyectado</strong>, no uno construido a mano:
 * lo que hay que demostrar no es que el módulo funcione, sino que Quarkus lo registra. Un
 * módulo correcto que nadie registra deja el JSON saliendo con nombres de constante
 * —{@code "OBLIGATORIO"} en vez de {@code "obligatorio"}— y rompe el frontend en
 * silencio, porque del lado de Java todo sigue compilando y pasando.
 */
@QuarkusTest
@TestProfile(PerfilSinCredenciales.class)
class CodedEnumModuleTest {

    @Inject
    ObjectMapper jackson;

    @ParameterizedTest(name = "{0} sale como \"{1}\"")
    @CsvSource({
        "OBLIGATORIO,      obligatorio",
        "PONDERABLE,       ponderable",
        "DESEABLE,         deseable",
        "INFORMATIVO,      informativo"
    })
    @DisplayName("Cada criticidad se serializa por su código, no por su nombre")
    void serializaPorCodigo(Criticidad valor, String esperado) throws Exception {
        assertEquals("\"" + esperado + "\"", jackson.writeValueAsString(valor));
    }

    @Test
    @DisplayName("Los códigos con guion bajo se conservan tal cual")
    void codigosConGuionBajo() throws Exception {
        assertEquals("\"cumple_parcial\"",
                jackson.writeValueAsString(EstadoCumplimiento.CUMPLE_PARCIAL));
        assertEquals("\"riesgo_de_rechazo\"",
                jackson.writeValueAsString(Veredicto.RIESGO_DE_RECHAZO));
    }

    @Test
    @DisplayName("Un record del contrato serializa sus enumeraciones por código")
    void dentroDeUnRecord() throws Exception {
        var requisito = new RequisitoTecnico(
                "RT-01", "Seguridad", "Cifrado en reposo",
                Criticidad.OBLIGATORIO, "Certificado", null, null);

        String json = jackson.writeValueAsString(requisito);

        assertTrue(json.contains("\"criticidad\":\"obligatorio\""),
                "El frontend lee este valor tal cual: " + json);
    }

    @ParameterizedTest(name = "«{0}» se lee como {1}")
    @CsvSource({
        // El código, que es lo que manda el frontend.
        "obligatorio,   OBLIGATORIO",
        // El nombre de la constante, que es lo que devuelve el modelo por el esquema JSON.
        "OBLIGATORIO,   OBLIGATORIO",
        // Variantes de espaciado y caja: el modelo no siempre es literal.
        "'  Ponderable ', PONDERABLE",
        // Lo irreconocible cae al valor por defecto en lugar de tirar el análisis entero.
        "inventado,     INFORMATIVO"
    })
    @DisplayName("La lectura tolera variantes y no falla ante lo desconocido")
    void deserializacionTolerante(String entrada, Criticidad esperado) throws Exception {
        assertEquals(esperado, jackson.readValue("\"" + entrada.trim() + "\"", Criticidad.class));
    }

    @Test
    @DisplayName("Cada familia cae a su propio valor por defecto, no a uno cualquiera")
    void valoresPorDefectoPorFamilia() throws Exception {
        // Un estado de cumplimiento desconocido debe ser «no evaluable» y no «cumple»:
        // afirmar cumplimiento que el modelo no dijo es el error caro.
        assertEquals(EstadoCumplimiento.NO_EVALUABLE,
                jackson.readValue("\"???\"", EstadoCumplimiento.class));
        assertEquals(Veredicto.RIESGO_DE_RECHAZO,
                jackson.readValue("\"???\"", Veredicto.class));
        assertEquals(NivelRiesgo.MEDIO, jackson.readValue("\"???\"", NivelRiesgo.class));
        assertEquals(Criticidad.INFORMATIVO, jackson.readValue("\"???\"", Criticidad.class));
    }

    @Test
    @DisplayName("Ida y vuelta completa de un ítem de la matriz")
    void idaYVuelta() throws Exception {
        var original = new ItemCumplimiento(
                "RT-01", "Cifrado", Criticidad.OBLIGATORIO,
                EstadoCumplimiento.CUMPLE_PARCIAL, "cita", "brecha", "acción");

        var json = jackson.writeValueAsString(original);

        assertEquals(original, jackson.readValue(json, ItemCumplimiento.class));
    }
}
