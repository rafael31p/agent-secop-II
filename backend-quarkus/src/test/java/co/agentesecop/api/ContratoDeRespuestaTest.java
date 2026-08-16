package co.agentesecop.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;

import co.agentesecop.PerfilSinCredenciales;
import co.agentesecop.dominio.Analisis.RequisitoTecnico;
import co.agentesecop.dominio.Analisis.RespuestaAnalisis;
import co.agentesecop.dominio.Analisis.RiesgoDetectado;
import co.agentesecop.dominio.Propuestas.ItemCumplimiento;
import co.agentesecop.dominio.Propuestas.ProcesoPriorizado;
import co.agentesecop.dominio.Propuestas.RespuestaPropuesta;
import co.agentesecop.dominio.Propuestas.RespuestaRelevancia;
import co.agentesecop.dominio.Propuestas.RespuestaValidacion;
import co.agentesecop.dominio.Propuestas.SeccionPropuesta;
import co.agentesecop.domain.model.proposal.EstadoCumplimiento;
import co.agentesecop.domain.model.proposal.Veredicto;
import co.agentesecop.domain.model.tender.Criticidad;
import co.agentesecop.domain.model.tender.NivelRiesgo;
import co.agentesecop.domain.model.tender.TipoRiesgo;
import co.agentesecop.servicio.AgenteSecop;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Fija el cuerpo JSON de las respuestas que invocan al modelo.
 *
 * <h2>Por qué existe</h2>
 *
 * <p>Estos cuatro endpoints no tenían ninguna prueba sobre su cuerpo: las de recursos
 * cubren la búsqueda y la salud, y las demás solo comprueban códigos de estado. El
 * contrato de las respuestas de análisis, propuesta y validación vivía únicamente en los
 * tipos de {@code frontend-next/lib/tipos.ts}, es decir, en otro repositorio de hecho.
 *
 * <p>La fase 2 va a mover esos tipos del dominio a un DTO del adaptador. Sin esta prueba,
 * un nombre de campo cambiado en el traslado no lo detectaría nada del lado de Java: el
 * síntoma sería el frontend mostrando huecos en blanco. Se escribe <em>antes</em> del
 * traslado, a propósito, para que caracterice el comportamiento actual y no el que
 * resulte.
 *
 * <p>El agente se sustituye por un doble: aquí se comprueba la forma de la respuesta, no
 * lo que dice el modelo.
 */
@QuarkusTest
@TestProfile(PerfilSinCredenciales.class)
class ContratoDeRespuestaTest {

    @InjectMock
    AgenteSecop agente;

    private static final String PLIEGO = "x".repeat(60);

    @Test
    @DisplayName("El análisis conserva los nombres de campo y los códigos de enumeración")
    void contratoDelAnalisis() {
        Mockito.when(agente.analizarRequisitos(Mockito.any())).thenReturn(new RespuestaAnalisis(
                "Resumen ejecutivo",
                "Objeto normalizado",
                List.of(new RequisitoTecnico("RT-01", "Seguridad", "Cifrado en reposo",
                        Criticidad.OBLIGATORIO, "Certificado", "ISO 27001", "cita del pliego")),
                List.of(new RiesgoDetectado("Riesgo", NivelRiesgo.ALTO, "Impacto",
                        "Mitigación", TipoRiesgo.JURIDICO)),
                List.of("Criterio"),
                List.of("Documento"),
                List.of("Pregunta"),
                List.of("Alerta"),
                "Recomendación"));

        given().contentType(ContentType.JSON)
                .body("{\"textoPliego\": \"" + PLIEGO + "\"}")
                .when().post("/api/analisis/requisitos")
                .then().statusCode(200)
                .body("resumenEjecutivo", is("Resumen ejecutivo"))
                .body("objetoNormalizado", is("Objeto normalizado"))
                .body("recomendacion", is("Recomendación"))
                .body("criteriosEvaluacion", contains("Criterio"))
                .body("documentosHabilitantes", contains("Documento"))
                .body("preguntasALaEntidad", contains("Pregunta"))
                .body("alertasNormativas", contains("Alerta"))
                .body("requisitos[0].id", is("RT-01"))
                .body("requisitos[0].categoria", is("Seguridad"))
                .body("requisitos[0].requisito", is("Cifrado en reposo"))
                .body("requisitos[0].criticidad", is("obligatorio"))
                .body("requisitos[0].evidenciaEsperada", is("Certificado"))
                .body("requisitos[0].normaRelacionada", is("ISO 27001"))
                .body("requisitos[0].citaPliego", is("cita del pliego"))
                .body("riesgos[0].descripcion", is("Riesgo"))
                .body("riesgos[0].nivel", is("alto"))
                .body("riesgos[0].impacto", is("Impacto"))
                .body("riesgos[0].mitigacion", is("Mitigación"))
                .body("riesgos[0].tipo", is("juridico"));
    }

    @Test
    @DisplayName("Una lista ausente sale como lista vacía, nunca como null")
    void listasNuncaNulas() {
        Mockito.when(agente.analizarRequisitos(Mockito.any())).thenReturn(new RespuestaAnalisis(
                "r", null, null, null, null, null, null, null, "rec"));

        given().contentType(ContentType.JSON)
                .body("{\"textoPliego\": \"" + PLIEGO + "\"}")
                .when().post("/api/analisis/requisitos")
                .then().statusCode(200)
                .body("requisitos", is(List.of()))
                .body("riesgos", is(List.of()))
                .body("alertasNormativas", is(List.of()));
    }

    @Test
    @DisplayName("La propuesta conserva sus nombres de campo")
    void contratoDeLaPropuesta() {
        Mockito.when(agente.generarPropuesta(Mockito.any())).thenReturn(new RespuestaPropuesta(
                "Título",
                "Resumen",
                List.of(new SeccionPropuesta("Sección", "Contenido", List.of("RT-01"))),
                List.of("Supuesto"),
                List.of("Vacío"),
                "# Markdown"));

        given().contentType(ContentType.JSON)
                .body("""
                        {"objetoContractual": "Objeto", "perfilProveedor": "%s",
                         "textoPliego": "%s"}
                        """.formatted("p".repeat(20), PLIEGO))
                .when().post("/api/propuestas/generar")
                .then().statusCode(200)
                .body("titulo", is("Título"))
                .body("resumenEjecutivo", is("Resumen"))
                .body("markdown", is("# Markdown"))
                .body("supuestos", contains("Supuesto"))
                .body("vaciosDeInformacion", contains("Vacío"))
                .body("secciones[0].titulo", is("Sección"))
                .body("secciones[0].contenido", is("Contenido"))
                .body("secciones[0].requisitosCubiertos", contains("RT-01"));
    }

    @Test
    @DisplayName("La validación conserva sus nombres de campo y su veredicto")
    void contratoDeLaValidacion() {
        Mockito.when(agente.validarPropuesta(Mockito.any())).thenReturn(new RespuestaValidacion(
                87,
                Veredicto.APTA_CON_AJUSTES,
                "Resumen",
                List.of(new ItemCumplimiento("RT-01", "Cifrado", Criticidad.OBLIGATORIO,
                        EstadoCumplimiento.CUMPLE_PARCIAL, "evidencia", "brecha", "acción")),
                List.of("Causal"),
                List.of("Mejora")));

        given().contentType(ContentType.JSON)
                .body("""
                        {"textoPropuesta": "%s", "textoPliego": "%s"}
                        """.formatted(PLIEGO, PLIEGO))
                .when().post("/api/propuestas/validar")
                .then().statusCode(200)
                .body("puntajeCumplimiento", is(87))
                .body("veredicto", is("apta_con_ajustes"))
                .body("resumen", is("Resumen"))
                .body("causalesDeRechazo", contains("Causal"))
                .body("mejorasPrioritarias", contains("Mejora"))
                .body("matriz[0].requisitoId", is("RT-01"))
                .body("matriz[0].requisito", is("Cifrado"))
                .body("matriz[0].criticidad", is("obligatorio"))
                .body("matriz[0].estado", is("cumple_parcial"))
                .body("matriz[0].evidenciaEnPropuesta", is("evidencia"))
                .body("matriz[0].brecha", is("brecha"))
                .body("matriz[0].accionCorrectiva", is("acción"));
    }

    @Test
    @DisplayName("La priorización conserva sus nombres de campo")
    void contratoDeLaPriorizacion() {
        Mockito.when(agente.priorizarProcesos(Mockito.any())).thenReturn(new RespuestaRelevancia(
                List.of(new ProcesoPriorizado("CO1", "Objeto", "Entidad", 1000.0, 90,
                        "Desarrollo de software", "Justificación", "Encaje",
                        List.of("Bandera"))),
                "Resumen"));

        given().contentType(ContentType.JSON)
                .body("""
                        {"procesos": [{"id": "CO1"}]}
                        """)
                .when().post("/api/procesos/relevancia-ti")
                .then().statusCode(200)
                .body("resumen", is("Resumen"))
                .body("priorizados[0].id", is("CO1"))
                .body("priorizados[0].objeto", is("Objeto"))
                .body("priorizados[0].entidad", is("Entidad"))
                .body("priorizados[0].valor", is(1000.0f))
                .body("priorizados[0].puntaje", is(90))
                .body("priorizados[0].categoriaTi", is("Desarrollo de software"))
                .body("priorizados[0].justificacion", is("Justificación"))
                .body("priorizados[0].encajeProveedor", is("Encaje"))
                .body("priorizados[0].banderas", contains("Bandera"));
    }

    @Test
    @DisplayName("El documento cargado conserva sus nombres de campo")
    void contratoDelDocumento() {
        given().multiPart("archivo", "pliego.txt",
                        "Contenido del pliego de prueba".getBytes(), "text/plain")
                .when().post("/api/analisis/documento")
                .then().statusCode(200)
                .body("$", hasKey("nombreArchivo"))
                .body("$", hasKey("caracteres"))
                .body("$", hasKey("paginas"))
                .body("$", hasKey("truncado"))
                .body("nombreArchivo", is("pliego.txt"))
                // «texto» y no «txt»: el tipo que reporta el extractor es la familia de
                // formato, no la extensión del archivo.
                .body("tipo", is("texto"))
                .body("caracteres", is(30))
                .body("texto", is("Contenido del pliego de prueba"))
                .body("truncado", is(false));
    }
}
