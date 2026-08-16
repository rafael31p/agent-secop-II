package co.agentesecop.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import co.agentesecop.secop.InyectarSecopFalso;
import co.agentesecop.secop.ServidorSecopFalso;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import co.agentesecop.PerfilSinCredenciales;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Búsqueda de procesos contra una API de SECOP simulada. */
@QuarkusTest
@TestProfile(PerfilSinCredenciales.class)
@QuarkusTestResource(ServidorSecopFalso.class)
class ProcesosResourceTest {

    @InyectarSecopFalso
    WireMockServer secop;

    private static final String RUTA = "/resource/p6dx-8zbt.json";

    /** Dos procesos: uno claramente de TI y otro que no lo es. */
    private static final String RESPUESTA_MIXTA = """
            [
              {
                "id_del_proceso": "CO1.REQ.111",
                "referencia_del_proceso": "LP-2026-001",
                "entidad": "MINISTERIO DE TECNOLOGIAS",
                "departamento_entidad": "Distrito Capital de Bogotá",
                "descripci_n_del_procedimiento":
                    "Desarrollo de software para el sistema de informacion misional en la nube",
                "modalidad_de_contratacion": "Licitación pública",
                "estado_del_procedimiento": "Publicado",
                "precio_base": "1500000000",
                "fecha_de_publicacion_del": "2026-08-05T00:00:00.000",
                "duracion": "12",
                "unidad_de_duracion": "Mes(es)",
                "urlproceso": {"url": "https://community.secop.gov.co/proceso/111"}
              },
              {
                "id_del_proceso": "CO1.REQ.222",
                "entidad": "ALCALDIA MUNICIPAL",
                "descripci_n_del_procedimiento":
                    "Suministro de refrigerios para la jornada de bienestar",
                "precio_base": "50000000",
                "fecha_de_publicacion_del": "2026-08-04T00:00:00.000"
              }
            ]
            """;

    @BeforeEach
    void reiniciarEscenarios() {
        secop.resetAll();
    }

    private void secopResponde(String cuerpo) {
        secop.stubFor(get(urlPathEqualTo(RUTA))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(cuerpo)));
    }

    @Test
    @DisplayName("Devuelve los procesos mapeados desde el conjunto de datos")
    void buscaYMapea() {
        secopResponde(RESPUESTA_MIXTA);

        given().contentType(ContentType.JSON)
                .body("{\"limite\": 30}")
                .when().post("/api/procesos/buscar")
                .then().statusCode(200)
                .body("total", is(2))
                .body("dataset", is("p6dx-8zbt"))
                .body("procesos[0].id", is("CO1.REQ.111"))
                .body("procesos[0].numeroProceso", is("LP-2026-001"))
                .body("procesos[0].valor", is(1.5E9f))
                .body("procesos[0].duracion", is("12 Mes(es)"))
                .body("procesos[0].url", is("https://community.secop.gov.co/proceso/111"))
                .body("procesos[0].scoreTi", greaterThan(20));
    }

    @Test
    @DisplayName("Con soloTi descarta lo que no es tecnología")
    void filtraSoloTi() {
        secopResponde(RESPUESTA_MIXTA);

        given().contentType(ContentType.JSON)
                .body("{\"limite\": 30, \"soloTi\": true}")
                .when().post("/api/procesos/buscar")
                .then().statusCode(200)
                .body("total", is(1))
                .body("procesos[0].id", is("CO1.REQ.111"));
    }

    @Test
    @DisplayName("Un rango de valor invertido se rechaza sin llamar a SECOP")
    void rangoInvertido() {
        given().contentType(ContentType.JSON)
                .body("{\"valorMin\": 100, \"valorMax\": 10}")
                .when().post("/api/procesos/buscar")
                .then().statusCode(422)
                .body("detail", containsString("valorMin"));
    }

    @Test
    @DisplayName("Si SECOP falla se devuelve respuesta vacía con advertencia, no un 500")
    void secopCaido() {
        secop.stubFor(get(urlPathEqualTo(RUTA))
                .willReturn(aResponse().withStatus(503).withBody("Service Unavailable")));

        given().contentType(ContentType.JSON)
                .body("{\"limite\": 5}")
                .when().post("/api/procesos/buscar")
                .then().statusCode(200)
                .body("total", is(0))
                .body("advertencias", hasSize(greaterThan(0)));
    }

    @Test
    @DisplayName("Un identificador inexistente da 404 con mensaje claro")
    void procesoInexistente() {
        secopResponde("[]");

        given()
                .when().get("/api/procesos/CO1.REQ.NOEXISTE")
                .then().statusCode(404)
                .body("detail", containsString("CO1.REQ.NOEXISTE"));
    }

    @Test
    @DisplayName("Recupera un proceso por su identificador")
    void obtienePorId() {
        secopResponde(RESPUESTA_MIXTA);

        given()
                .when().get("/api/procesos/CO1.REQ.111")
                .then().statusCode(200)
                .body("id", is("CO1.REQ.111"))
                .body("entidad", notNullValue());
    }

    @Test
    @DisplayName("Priorizar sin procesos se rechaza antes de llamar al modelo")
    void priorizarSinProcesos() {
        given().contentType(ContentType.JSON)
                .body("{\"procesos\": []}")
                .when().post("/api/procesos/relevancia-ti")
                .then().statusCode(422);
    }

    @Test
    @DisplayName("Priorizar sin credenciales de IA da 503, no un 500")
    void priorizarSinCredenciales() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"procesos": [{"id": "1", "objeto": "Desarrollo de software"}]}
                        """)
                .when().post("/api/procesos/relevancia-ti")
                .then().statusCode(503)
                .body("detail", containsString("aistudio.google.com"));
    }
}
