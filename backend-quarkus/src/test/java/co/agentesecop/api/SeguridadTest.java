package co.agentesecop.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import co.agentesecop.servicio.AgenteSecop;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Autenticación por clave de API y límite de consumo.
 *
 * <p>La prueba que de verdad importa es {@link #sinClaveNoSeLlamaAlModelo}: comprueba no
 * solo que la respuesta es 401, sino que <em>no se llegó a invocar al agente</em>. Un 401
 * devuelto después de haber llamado al proveedor habría cerrado la puerta con el dinero ya
 * gastado, que es exactamente el defecto que este filtro existe para evitar.
 */
@QuarkusTest
@TestProfile(SeguridadTest.PerfilConAutenticacion.class)
class SeguridadTest {

    /** Clave en claro que usan las pruebas; el servidor solo conoce su hash. */
    private static final String CLAVE = "clave-de-prueba-123";

    public static class PerfilConAutenticacion implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            String hash = HexFormat.of().formatHex(FiltroClaveApi.sha256(CLAVE));
            return Map.of(
                    "agente.seguridad.autenticacion-requerida", "true",
                    "agente.seguridad.api-keys.pruebas", "sha256:" + hash,
                    // Un límite de 2 permite comprobar el rechazo sin 300 peticiones.
                    "agente.seguridad.limites.busqueda", "2",
                    "agente.ia.gemini.api-key", "",
                    "agente.ia.openai.api-key", "",
                    "agente.ia.anthropic.api-key", "",
                    "agente.ia.deepseek.api-key", "",
                    "agente.ia.ollama.habilitado", "false");
        }
    }

    @InjectMock
    AgenteSecop agente;

    @Test
    @DisplayName("Sin clave: 401 y el modelo no se llega a invocar")
    void sinClaveNoSeLlamaAlModelo() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"textoPliego": "%s"}
                        """.formatted("x".repeat(100)))
                .when().post("/api/analisis/requisitos")
                .then().statusCode(401)
                .body("detail", containsString("X-Api-Key"))
                .body("correlationId", notNullValue());

        // Lo esencial: se rechazó antes de gastar nada.
        Mockito.verifyNoInteractions(agente);
    }

    @Test
    @DisplayName("Una clave que no coincide tampoco pasa")
    void claveIncorrecta() {
        given().contentType(ContentType.JSON)
                .header("X-Api-Key", "clave-inventada")
                .body("""
                        {"textoPliego": "%s"}
                        """.formatted("x".repeat(100)))
                .when().post("/api/analisis/requisitos")
                .then().statusCode(401);

        Mockito.verifyNoInteractions(agente);
    }

    @Test
    @DisplayName("La salud y el catálogo de proveedores siguen abiertos")
    void endpointsAbiertos() {
        given().when().get("/api/salud").then().statusCode(200);
        given().when().get("/api/proveedores").then().statusCode(200);
    }

    @Test
    @DisplayName("Con la clave correcta la petición llega al agente")
    void claveCorrecta() {
        Mockito.when(agente.analizarRequisitos(Mockito.any()))
                .thenThrow(new co.agentesecop.ia.ErroresIA.ProveedorNoConfigurado("sin clave"));

        given().contentType(ContentType.JSON)
                .header("X-Api-Key", CLAVE)
                .body("""
                        {"textoPliego": "%s"}
                        """.formatted("x".repeat(100)))
                .when().post("/api/analisis/requisitos")
                // 503 y no 401: pasó la autenticación y falló más adelante, que es
                // justo lo que se quiere demostrar.
                .then().statusCode(503);

        Mockito.verify(agente).analizarRequisitos(Mockito.any());
    }

    @Test
    @DisplayName("Superar el límite devuelve 429 con Retry-After")
    void limiteDeTasa() {
        var cuerpo = "{\"texto\": \"software\", \"limite\": 1}";
        for (int i = 0; i < 2; i++) {
            given().contentType(ContentType.JSON)
                    .header("X-Api-Key", CLAVE)
                    .body(cuerpo)
                    .when().post("/api/procesos/buscar")
                    .then().statusCode(200);
        }

        String reintentar = given().contentType(ContentType.JSON)
                .header("X-Api-Key", CLAVE)
                .body(cuerpo)
                .when().post("/api/procesos/buscar")
                .then().statusCode(429)
                .body("detail", containsString("límite"))
                .extract().header("Retry-After");

        // Sin Retry-After el 429 es opaco y el cliente solo puede reintentar a ciegas.
        assertEquals(true, Long.parseLong(reintentar) > 0,
                "Retry-After debe decir cuántos segundos esperar, no llegar vacío.");
    }
}
