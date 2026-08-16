package co.agentesecop.adapter.in.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import co.agentesecop.ia.ErroresIA;
import co.agentesecop.application.port.in.AnalizarPliego;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifica que cada excepción del agente llegue al cliente con su código y su mensaje.
 *
 * <p>Es la regresión del defecto más costoso de la versión Python: el error del proveedor
 * se traducía a un mensaje accionable y luego se perdía en la frontera HTTP, saliendo
 * como «500 Internal Server Error» sin explicación alguna.
 */
@QuarkusTest
class ManejadorErroresTest {

    @InjectMock
    AnalizarPliego analizarPliego;

    private static final String CUERPO_VALIDO = """
            {"textoPliego": "%s", "objetoContractual": "Portal web"}
            """.formatted("Anexo tecnico con requisitos de accesibilidad. ".repeat(3));

    private void cuandoElAgenteLanza(RuntimeException error) {
        Mockito.when(analizarPliego.analizar(Mockito.any())).thenThrow(error);
    }

    @Test
    @DisplayName("Proveedor sin configurar -> 503 con la explicación")
    void proveedorNoConfigurado() {
        cuandoElAgenteLanza(new ErroresIA.ProveedorNoConfigurado(
                "Falta AGENTE_IA_GEMINI_API_KEY."));

        given().contentType(ContentType.JSON).body(CUERPO_VALIDO)
                .when().post("/api/analisis/requisitos")
                .then().statusCode(503)
                .body("detail", containsString("AGENTE_IA_GEMINI_API_KEY"));
    }

    @Test
    @DisplayName("Cuota agotada -> 429 con la explicación")
    void cuotaAgotada() {
        cuandoElAgenteLanza(new ErroresIA.FalloDelProveedor(
                "Se agotó la cuota de Google Gemini.", 429, null));

        given().contentType(ContentType.JSON).body(CUERPO_VALIDO)
                .when().post("/api/analisis/requisitos")
                .then().statusCode(429)
                .body("detail", containsString("cuota"));
    }

    @Test
    @DisplayName("Servicio caído -> 502, nunca 500 opaco")
    void servicioCaido() {
        cuandoElAgenteLanza(new ErroresIA.FalloDelProveedor(
                "El servicio de Google Gemini falló temporalmente. Reintenta.", 502, null));

        given().contentType(ContentType.JSON).body(CUERPO_VALIDO)
                .when().post("/api/analisis/requisitos")
                .then().statusCode(502)
                .body("detail", containsString("temporalmente"))
                .body("detail", not(containsString("Internal Server Error")));
    }

    @Test
    @DisplayName("Filtro de contenido -> 422")
    void filtroDeContenido() {
        cuandoElAgenteLanza(new ErroresIA.RespuestaInutilizable(
                "Los filtros de contenido bloquearon la respuesta."));

        given().contentType(ContentType.JSON).body(CUERPO_VALIDO)
                .when().post("/api/analisis/requisitos")
                .then().statusCode(422)
                .body("detail", containsString("filtros"));
    }

    @Test
    @DisplayName("Pliego demasiado grande -> 413 con el tamaño y el límite")
    void contextoDemasiadoGrande() {
        cuandoElAgenteLanza(new ErroresIA.ContextoDemasiadoGrande(
                "El material tiene 900.000 caracteres y supera el límite de 800.000."));

        given().contentType(ContentType.JSON).body(CUERPO_VALIDO)
                .when().post("/api/analisis/requisitos")
                .then().statusCode(413)
                .body("detail", containsString("supera el límite"));
    }

    @Test
    @DisplayName("Proveedor desconocido -> 400")
    void proveedorDesconocido() {
        cuandoElAgenteLanza(new ErroresIA.ProveedorDesconocido(
                "Proveedor 'inventado' desconocido. Disponibles: gemini, openai."));

        given().contentType(ContentType.JSON).body(CUERPO_VALIDO)
                .when().post("/api/analisis/requisitos")
                .then().statusCode(400)
                .body("detail", containsString("Disponibles"));
    }

    @Test
    @DisplayName("Un pliego demasiado corto lo rechaza la validación, sin llamar al modelo")
    void validacionDeEntrada() {
        given().contentType(ContentType.JSON)
                .body("{\"textoPliego\": \"muy corto\"}")
                .when().post("/api/analisis/requisitos")
                .then().statusCode(422)
                .body("detail", containsString("entre 40 y 400.000 caracteres"));

        Mockito.verifyNoInteractions(analizarPliego);
    }

    @Test
    @DisplayName("Un pliego enorme se rechaza en la frontera, sin llamar al modelo")
    void pliegoDemasiadoGrande() {
        // El punto de la cota: rechazar con 422 antes de construir el prompt, serializar
        // el contexto y facturar una llamada que iba a fallar en el proveedor.
        String enorme = "a".repeat(900_000);

        given().contentType(ContentType.JSON)
                .body("{\"textoPliego\": \"" + enorme + "\"}")
                .when().post("/api/analisis/requisitos")
                .then().statusCode(422)
                .body("detail", containsString("400.000 caracteres"));

        Mockito.verifyNoInteractions(analizarPliego);
    }

    @Test
    @DisplayName("Un identificador de modelo con forma inválida no llega al proveedor")
    void modeloConFormaInvalida() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"textoPliego": "%s", "modelo": "../../etc/passwd"}
                        """.formatted("x".repeat(50)))
                .when().post("/api/analisis/requisitos")
                .then().statusCode(422)
                .body("detail", containsString("modelo"));

        Mockito.verifyNoInteractions(analizarPliego);
    }
}
