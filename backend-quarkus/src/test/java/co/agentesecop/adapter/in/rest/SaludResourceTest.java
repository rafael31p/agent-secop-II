package co.agentesecop.adapter.in.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import co.agentesecop.PerfilSinCredenciales;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Estado del servicio y catálogo de proveedores. */
@QuarkusTest
@TestProfile(PerfilSinCredenciales.class)
class SaludResourceTest {

    @Test
    @DisplayName("Sin credenciales el estado es degradado, pero el servicio responde")
    void saludDegradadaSinCredenciales() {
        given()
                .when().get("/api/salud")
                .then().statusCode(200)
                .body("estado", is("degradado"))
                .body("iaConfigurada", is(false))
                .body("proveedorIaPorDefecto", is("gemini"))
                .body("secopDatasetProcesos", is("p6dx-8zbt"))
                .body("version", notNullValue());
    }

    @Test
    @DisplayName("El catálogo expone los cinco proveedores con sus modelos")
    void catalogoDeProveedores() {
        given()
                .when().get("/api/proveedores")
                .then().statusCode(200)
                .body("", hasSize(5))
                .body("nombre", hasItems("gemini", "openai", "anthropic", "deepseek", "ollama"));
    }

    @Test
    @DisplayName("Cada proveedor no disponible explica por qué")
    void motivoDeNoDisponibilidad() {
        given()
                .when().get("/api/proveedores")
                .then().statusCode(200)
                .body("find { it.nombre == 'gemini' }.configurado", is(false))
                .body("find { it.nombre == 'gemini' }.motivo",
                        containsString("aistudio.google.com"))
                .body("find { it.nombre == 'ollama' }.motivo",
                        containsString("deshabilitado"));
    }

    @Test
    @DisplayName("Los modelos sugeridos de Gemini reflejan los verificados contra la API")
    void modelosSugeridosDeGemini() {
        given()
                .when().get("/api/proveedores")
                .then().statusCode(200)
                .body("find { it.nombre == 'gemini' }.modeloPorDefecto",
                        equalTo("gemini-3.6-flash"))
                .body("find { it.nombre == 'gemini' }.modelos", hasItems("gemini-3.6-flash"));
    }

    @Test
    @DisplayName("La documentación OpenAPI se genera")
    void documentacionDisponible() {
        given()
                .when().get("/q/openapi")
                .then().statusCode(200);
    }

    /**
     * La versión que anuncia la API es la del {@code pom.xml}.
     *
     * <p>Se compara contra la propiedad de configuración y no contra un literal: escribir
     * «0.2.0» aquí volvería a crear la segunda fuente de verdad que este cambio elimina, y
     * la prueba seguiría pasando el día que las dos divergieran.
     */
    @Test
    @DisplayName("La versión viene de la compilación, no de una constante en el código")
    void versionDeLaCompilacion() {
        String delPom = ConfigProvider.getConfig()
                .getValue("quarkus.application.version", String.class);

        given().when().get("/api/salud")
                .then().statusCode(200)
                .body("version", is(delPom));
    }
}
