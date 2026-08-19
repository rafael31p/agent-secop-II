package co.agentesecop.pruebas;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.agentesecop.PerfilSinCredenciales;
import co.agentesecop.adapter.in.rest.FiltroCorrelacion;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El identificador que convierte «me falló a las 3» en una línea de registro.
 *
 * <p>Sin él, un error reportado por un usuario no se puede localizar: el registro es un
 * texto continuo sin nada que ate una petición concreta. Y es también lo que permite que
 * los mensajes de error sean genéricos sin volverse inútiles —hace falta que lo sean: un
 * error del proveedor puede arrastrar la clave de API en la URL—, porque el detalle sigue
 * estando, solo que en el registro.
 */
@QuarkusTest
@TestProfile(PerfilSinCredenciales.class)
class CorrelacionTest {

    @Test
    @DisplayName("Toda respuesta lleva el identificador, aunque el cliente no lo pida")
    void siempreViajaDeVuelta() {
        Response respuesta = given().when().get("/api/salud").then().statusCode(200)
                .extract().response();

        String identificador = respuesta.header(FiltroCorrelacion.CABECERA);
        assertNotNull(identificador, "Falta la cabecera " + FiltroCorrelacion.CABECERA);
        assertFalse(identificador.isBlank());
    }

    @Test
    @DisplayName("Se conserva el identificador del cliente, para cruzar el rastro entero")
    void conservaElDelCliente() {
        String mio = "peticion-de-prueba-42";

        given().header(FiltroCorrelacion.CABECERA, mio)
                .when().get("/api/salud")
                .then().statusCode(200)
                .header(FiltroCorrelacion.CABECERA, mio);
    }

    @Test
    @DisplayName("Un identificador con salto de línea se descarta: sería inyección de registros")
    void descartaLaInyeccionDeRegistros() {
        // Aceptar esto tal cual y escribirlo en el registro permite fabricar entradas
        // falsas: basta un salto de línea para simular una línea de auditoría entera.
        String malicioso = "abc\n2026-01-01 ERROR Sesion iniciada por administrador";

        Response respuesta = given().header(FiltroCorrelacion.CABECERA, malicioso)
                .when().get("/api/salud")
                .then().statusCode(200)
                .extract().response();

        String devuelto = respuesta.header(FiltroCorrelacion.CABECERA);
        assertNotEquals(malicioso, devuelto);
        assertFalse(devuelto.contains("\n"), "El identificador devuelto lleva un salto de línea");
    }

    @Test
    @DisplayName("Un identificador desmesurado también se descarta")
    void descartaLoDesmesurado() {
        String enorme = "a".repeat(10_000);

        Response respuesta = given().header(FiltroCorrelacion.CABECERA, enorme)
                .when().get("/api/salud")
                .then().statusCode(200)
                .extract().response();

        assertTrue(respuesta.header(FiltroCorrelacion.CABECERA).length() <= 64);
    }

    @Test
    @DisplayName("El cuerpo del error trae el mismo identificador que la cabecera")
    void elErrorLoLleva() {
        // Un pliego demasiado corto: error del contrato, sin llamar a ningún proveedor.
        Response respuesta = given().contentType(ContentType.JSON)
                .body("{\"textoPliego\": \"muy corto\"}")
                .when().post("/api/analisis/requisitos")
                .then().statusCode(422)
                .extract().response();

        String enElCuerpo = respuesta.jsonPath().getString("correlationId");
        assertNotNull(enElCuerpo, "El cuerpo de error debe traer correlationId");
        // Que coincidan es lo que hace utilizable el mecanismo: el usuario copia uno de los
        // dos sitios, y el operador encuentra la misma petición.
        assertEquals(respuesta.header(FiltroCorrelacion.CABECERA), enElCuerpo);
    }

    private static void assertNotEquals(String noEsperado, String real) {
        assertFalse(noEsperado.equals(real),
                "El identificador del cliente no debería haberse aceptado: " + real);
    }
}
