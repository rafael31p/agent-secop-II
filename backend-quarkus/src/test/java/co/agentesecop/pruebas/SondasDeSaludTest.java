package co.agentesecop.pruebas;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import co.agentesecop.PerfilSinCredenciales;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sondas que comprueban en vez de recitar la configuración.
 *
 * <p>{@code /api/salud} construye su respuesta a partir de qué hay configurado: devolvía
 * «ok» con el proveedor caído y con la clave revocada. Como sonda para un orquestador eso
 * es peor que no tener ninguna, porque afirma que todo está bien mientras el servicio no
 * puede hacer nada útil. Se conserva por compatibilidad con el pie del frontend; quien
 * decide operaciones son estas.
 *
 * <p>Sin credenciales de ningún proveedor, la disponibilidad debe declararse caída: es
 * justo el caso que el endpoint anterior no distinguía.
 */
@QuarkusTest
@TestProfile(PerfilSinCredenciales.class)
class SondasDeSaludTest {

    @Test
    @DisplayName("La vivacidad no depende de nadie de fuera")
    void vivacidad() {
        // Si mirase al proveedor, una caída de Gemini haría que el orquestador reiniciara
        // un proceso sano una y otra vez sin arreglar nada.
        given().when().get("/q/health/live")
                .then().statusCode(200)
                .body("status", is("UP"))
                .body("checks.name", hasItem("agente-vivo"));
    }

    @Test
    @DisplayName("Sin ningún proveedor configurado, la disponibilidad es DOWN")
    void disponibilidadSinProveedores() {
        given().when().get("/q/health/ready")
                .then().statusCode(503)
                .body("status", is("DOWN"))
                .body("checks.find { it.name == 'modelos-de-lenguaje' }.status", is("DOWN"));
    }

    @Test
    @DisplayName("La sonda declara el estado del cortacircuitos, que es su sensor")
    void declaraElCortacircuitos() {
        // Consultarlo es gratis y refleja las llamadas reales; sondear al proveedor
        // costaría dinero en cada latido para averiguar lo mismo.
        given().when().get("/q/health/ready")
                .then()
                .body("checks.find { it.name == 'modelos-de-lenguaje' }.data.cortacircuitos",
                        is("closed"));
    }

    @Test
    @DisplayName("La fuente de datos se declara aparte: sin modelos aún se puede buscar")
    void laFuenteVaAparte() {
        // Si los modelos caen pero SECOP responde, el servicio sigue siendo parcialmente
        // útil. Fundir las dos sondas en una borraría esa distinción.
        given().when().get("/q/health/ready")
                .then()
                .body("checks.find { it.name == 'fuente-secop' }.status", is("UP"));
    }
}
