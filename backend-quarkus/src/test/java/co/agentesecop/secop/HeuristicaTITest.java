package co.agentesecop.secop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pruebas de la heurística local de relevancia tecnológica. */
class HeuristicaTITest {

    @Test
    @DisplayName("Puntúa alto un objeto claramente tecnológico")
    void objetoTecnologico() {
        var resultado = HeuristicaTI.evaluar(
                "Desarrollo de software para el sistema de información misional en la nube");

        assertTrue(resultado.puntaje() >= 20,
                "Puntaje insuficiente: " + resultado.puntaje());
        assertTrue(resultado.senales().size() >= 2,
                "Señales insuficientes: " + resultado.senales());
    }

    @Test
    @DisplayName("Descarta un objeto que no es de tecnología")
    void objetoNoTecnologico() {
        var resultado = HeuristicaTI.evaluar(
                "Suministro de refrigerios para la jornada de bienestar institucional");

        assertEquals(0, resultado.puntaje(), "No debería puntuar: " + resultado.senales());
    }

    /**
     * Regresión del defecto encontrado en la versión Python: los acrónimos cortos casaban
     * por subcadena. «api» dentro de «capital», «soc» dentro de «social», «tic» dentro de
     * «logística», «erp» dentro de «cuerpo». Producía falsos positivos masivos.
     */
    @ParameterizedTest(name = "sin falso positivo: {0}")
    @ValueSource(strings = {
        "Apoyo al capital social de la poblacion en condicion de vulnerabilidad",
        "Servicios de operacion logistica y practica deportiva para el cuerpo de bomberos",
        "Otorgar apoyo economico para la recuperacion de la infraestructura vial",
        "Prestacion de servicios de terapia fisica y diagnostico clinico",
        "Mantenimiento de parques y zonas verdes del municipio"
    })
    void sinFalsosPositivosPorSubcadena(String objeto) {
        var resultado = HeuristicaTI.evaluar(objeto);

        assertEquals(0, resultado.puntaje(),
                "Falso positivo con señales " + resultado.senales() + " en: " + objeto);
    }

    @Test
    @DisplayName("Los términos legítimos sí puntúan aunque lleven puntuación alrededor")
    void terminosLegitimosConPuntuacion() {
        var resultado = HeuristicaTI.evaluar(
                "Desarrollo de APIs REST; licenciamiento SaaS, mesa de ayuda y SOC 24/7.");

        assertTrue(resultado.puntaje() >= 15,
                "Los términos con puntuación deberían puntuar: " + resultado.puntaje());
    }

    @Test
    @DisplayName("Ignora las tildes al comparar")
    void insensibleATildes() {
        int conTilde = HeuristicaTI.evaluar("plataforma tecnológica").puntaje();
        int sinTilde = HeuristicaTI.evaluar("plataforma tecnologica").puntaje();

        assertEquals(conTilde, sinTilde);
        assertTrue(conTilde > 0);
    }

    @Test
    @DisplayName("Tolera nulos y cadenas vacías")
    void toleraVacios() {
        assertEquals(0, HeuristicaTI.evaluar(null).puntaje());
        assertEquals(0, HeuristicaTI.evaluar("").puntaje());
        assertEquals(0, HeuristicaTI.evaluar("   ").puntaje());
    }

    @Test
    @DisplayName("El puntaje nunca excede 100")
    void puntajeAcotado() {
        String todosLosTerminos = String.join(" ",
                "software desarrollo de software fabrica de software sistema de informacion",
                "plataforma tecnologica ciberseguridad seguridad de la informacion nube cloud",
                "inteligencia artificial machine learning gobierno digital interoperabilidad");

        assertTrue(HeuristicaTI.evaluar(todosLosTerminos).puntaje() <= 100);
    }
}
