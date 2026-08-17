package co.agentesecop.adapter.out.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.agentesecop.adapter.in.rest.CodedEnumModule;
import co.agentesecop.domain.model.tender.Criticidad;
import co.agentesecop.domain.model.tender.RequisitoTecnico;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La codificación TOON: que sea correcta, y que de verdad ahorre.
 *
 * <p>Lo segundo no se da por supuesto. Un formato compacto que se aplica a datos donde casi
 * todos los valores hay que entrecomillar puede ahorrar mucho menos de lo que promete, y la
 * única forma de saberlo es medirlo contra los datos reales de este proyecto: requisitos de
 * pliegos, que llevan comas y dos puntos en casi todas las frases.
 */
class ToonTest {

    private final ObjectMapper jackson = new ObjectMapper();

    private static RequisitoTecnico requisito(int numero) {
        return new RequisitoTecnico(
                "REQ-%02d".formatted(numero),
                "Técnico",
                "El portal debe cumplir la Resolución 1519 de 2020 de MinTIC, nivel AA",
                Criticidad.OBLIGATORIO,
                "Informe de auditoría de accesibilidad emitido por tercero",
                "Resolución 1519 de 2020",
                "«El portal debe cumplir la Resolucion 1519 de 2020 de MinTIC»");
    }

    // ------------------------------------------------------------- corrección

    @Test
    @DisplayName("Declara el número de filas y los campos una sola vez")
    void cabecera() {
        var tabla = Toon.tabla(jackson, "requisitos", List.of(requisito(1), requisito(2)));

        assertTrue(tabla.tabular());
        assertTrue(tabla.texto().startsWith(
                "requisitos[2]{id,categoria,requisito,criticidad,evidenciaEsperada,"
                        + "normaRelacionada,citaPliego}:"),
                tabla.texto());
        // Una fila por elemento, más la cabecera.
        assertEquals(3, tabla.texto().split("\n").length);
    }

    @Test
    @DisplayName("Un valor con comas va entrecomillado, o desplazaría todas las columnas")
    void entrecomillaLoQueRompeLaFila() {
        var tabla = Toon.tabla(jackson, "requisitos", List.of(requisito(1)));

        // Sin comillas, la coma de «MinTIC, nivel AA» partiría la fila y el modelo leería
        // «nivel AA» como si fuera la criticidad.
        assertTrue(tabla.texto().contains("\"El portal debe cumplir la Resolución 1519 de "
                + "2020 de MinTIC, nivel AA\""), tabla.texto());
    }

    @Test
    @DisplayName("Las enumeraciones salen con su código por cable, no con el nombre Java")
    void respetaElCodigoPorCable() {
        var jacksonDelProyecto = new ObjectMapper();
        jacksonDelProyecto.registerModule(new CodedEnumModule());

        var tabla = Toon.tabla(jacksonDelProyecto, "requisitos", List.of(requisito(1)));

        // Los prompts le piden al modelo exactamente estos códigos. Si la tabla dijera
        // OBLIGATORIO y el prompt «obligatorio», discreparían en silencio.
        assertTrue(tabla.texto().contains(",obligatorio,"), tabla.texto());
        assertFalse(tabla.texto().contains("OBLIGATORIO"), tabla.texto());
    }

    @Test
    @DisplayName("Una lista vacía se declara, no se omite")
    void listaVacia() {
        var tabla = Toon.tabla(jackson, "requisitos", List.of());

        // Decir «cero» es distinto de no decir nada: le ahorra al modelo suponer que se
        // nos olvidó mandar los requisitos.
        assertEquals("requisitos[0]:", tabla.texto());
    }

    @Test
    @DisplayName("Lo que no es tabulable se rechaza en vez de codificarse mal")
    void caeAJsonSiNoEsUniforme() {
        record ConLista(String id, List<String> etiquetas) {}

        var tabla = Toon.tabla(jackson, "cosas",
                List.of(new ConLista("A", List.of("x", "y"))));

        // El redactor de prompts lo manda entonces como JSON: correcto aunque más caro.
        assertFalse(tabla.tabular());
    }

    @Test
    @DisplayName("Un valor que parece número o booleano se entrecomilla para no cambiar de tipo")
    void noCambiaDeTipo() {
        assertEquals("\"42\"", Toon.comoCelda("42"));
        assertEquals("\"true\"", Toon.comoCelda("true"));
        assertEquals("\"\"", Toon.comoCelda(""));
        assertEquals("obligatorio", Toon.comoCelda("obligatorio"));
    }

    @Test
    @DisplayName("Un salto de línea dentro de un valor se escapa y no parte la fila")
    void escapaLosSaltosDeLinea() {
        String celda = Toon.comoCelda("primera\nsegunda");

        assertFalse(celda.contains("\n"), celda);
        assertTrue(celda.contains("\\n"), celda);
    }

    // ------------------------------------------------------------- el ahorro

    /**
     * La razón de existir del formato, comprobada y no supuesta.
     *
     * <p>Se compara contra el JSON con sangrado que se enviaba antes, sobre siete
     * requisitos, que es un análisis típico. Si el ahorro dejara de ser apreciable, este
     * código no valdría lo que cuesta mantenerlo y esta prueba lo diría.
     */
    @Test
    @DisplayName("Ahorra al menos un tercio frente al JSON con sangrado que se enviaba")
    void ahorraDeVerdad() throws Exception {
        List<RequisitoTecnico> siete = List.of(
                requisito(1), requisito(2), requisito(3), requisito(4),
                requisito(5), requisito(6), requisito(7));

        String comoJson = jackson.writerWithDefaultPrettyPrinter().writeValueAsString(siete);
        String comoToon = Toon.tabla(jackson, "requisitos", siete).texto();

        double ahorro = 1.0 - (double) comoToon.length() / comoJson.length();
        assertTrue(ahorro >= 0.33,
                "El ahorro cayó al %.0f%% (%d caracteres frente a %d). Si es estable, "
                        .formatted(ahorro * 100, comoToon.length(), comoJson.length())
                        + "revisa si el formato sigue valiendo la pena.");
        System.out.printf("TOON frente a JSON con sangrado: %d -> %d caracteres (-%.0f%%)%n",
                comoJson.length(), comoToon.length(), ahorro * 100);
    }
}
