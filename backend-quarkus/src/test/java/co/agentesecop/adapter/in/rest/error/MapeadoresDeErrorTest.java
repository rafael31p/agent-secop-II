package co.agentesecop.adapter.in.rest.error;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import co.agentesecop.adapter.out.llm.error.ContextoDemasiadoGrande;
import co.agentesecop.adapter.out.llm.error.CredencialInvalida;
import co.agentesecop.adapter.out.llm.error.CuotaAgotada;
import co.agentesecop.adapter.out.llm.error.ModeloDesconocido;
import co.agentesecop.adapter.out.llm.error.ProveedorDesconocido;
import co.agentesecop.adapter.out.llm.error.ProveedorNoConfigurado;
import co.agentesecop.adapter.out.llm.error.ProveedorNoDisponible;
import co.agentesecop.adapter.out.llm.error.RespuestaInutilizable;
import co.agentesecop.adapter.out.llm.error.ServicioDelProveedorCaido;
import co.agentesecop.application.port.in.AnalizarPliego;
import co.agentesecop.application.port.in.GenerarPropuesta;
import co.agentesecop.domain.shared.PeticionInvalida;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Cada excepción llega al cliente con su código y su mensaje, ninguna cae en el 500.
 *
 * <h2>La regresión que motiva esta clase</h2>
 *
 * <p>La versión anterior probaba un manejador único que capturaba la jerarquía entera de
 * errores del agente. Pasaba en verde, y aun así había un caso roto en producción:
 * {@code domain.shared.PeticionInvalida} se movió al dominio en la fase 2, dejó de heredar
 * de esa jerarquía y se quedó sin traducir. {@code POST /api/propuestas/generar} sin
 * requisitos ni pliego devolvía <em>500 Internal Server Error</em> —sin {@code detail} y
 * sin identificador— durante toda la fase 3.
 *
 * <p>Una prueba que enumera los tipos, en vez de probar la clase base, es la que hace
 * visible un tipo sin mapeador: si se añade uno nuevo y nadie lo prueba aquí, la ausencia
 * se ve al leer la lista.
 */
@QuarkusTest
class MapeadoresDeErrorTest {

    @InjectMock
    AnalizarPliego analizarPliego;

    @InjectMock
    GenerarPropuesta generarPropuesta;

    private static final String PLIEGO =
            "Anexo tecnico con requisitos de accesibilidad. ".repeat(3);

    private static final String CUERPO_VALIDO = """
            {"textoPliego": "%s", "objetoContractual": "Portal web"}
            """.formatted(PLIEGO);

    private void cuandoElAgenteLanza(RuntimeException error) {
        Mockito.when(analizarPliego.analizar(Mockito.any())).thenThrow(error);
    }

    /** Todo error responde con el mismo cuerpo, venga de donde venga. */
    private io.restassured.response.ValidatableResponse analizarEsperando(int estado) {
        return given().contentType(ContentType.JSON).body(CUERPO_VALIDO)
                .when().post("/api/analisis/requisitos")
                .then().statusCode(estado)
                .body("correlationId", notNullValue());
    }

    // ------------------------------------------------- errores del proveedor

    @Test
    @DisplayName("Proveedor sin configurar -> 503 con la explicación")
    void proveedorNoConfigurado() {
        cuandoElAgenteLanza(new ProveedorNoConfigurado("Falta AGENTE_IA_GEMINI_API_KEY."));

        analizarEsperando(503).body("detail", containsString("AGENTE_IA_GEMINI_API_KEY"));
    }

    @Test
    @DisplayName("Proveedor desconocido -> 400")
    void proveedorDesconocido() {
        cuandoElAgenteLanza(new ProveedorDesconocido(
                "Proveedor 'inventado' desconocido. Disponibles: gemini, openai."));

        analizarEsperando(400).body("detail", containsString("Disponibles"));
    }

    @Test
    @DisplayName("Clave inválida -> 401")
    void credencialInvalida() {
        cuandoElAgenteLanza(new CredencialInvalida("Google Gemini", null));

        analizarEsperando(401).body("detail", containsString("clave"));
    }

    @Test
    @DisplayName("Modelo inexistente -> 404, nombrando el modelo y dónde listarlos")
    void modeloDesconocido() {
        cuandoElAgenteLanza(new ModeloDesconocido("modelo-fantasma", "Google Gemini", null));

        analizarEsperando(404)
                .body("detail", containsString("modelo-fantasma"))
                .body("detail", containsString("/api/proveedores"));
    }

    @Test
    @DisplayName("Cuota agotada -> 429 con Retry-After")
    void cuotaAgotada() {
        cuandoElAgenteLanza(new CuotaAgotada("Google Gemini", null));

        // Sin Retry-After, el cliente adivina; y lo que adivina suele ser reintentar en
        // seguida, que con una cuota agotada es exactamente lo que no ayuda.
        analizarEsperando(429)
                .body("detail", containsString("cuota"))
                .header("Retry-After", notNullValue());
    }

    @Test
    @DisplayName("Servicio caído -> 502, nunca 500 opaco")
    void servicioCaido() {
        cuandoElAgenteLanza(ServicioDelProveedorCaido.temporal("Google Gemini", null));

        analizarEsperando(502)
                .body("detail", containsString("temporalmente"))
                .body("detail", not(containsString("Internal Server Error")));
    }

    @Test
    @DisplayName("Circuito abierto o mamparo lleno -> 503 con Retry-After")
    void proveedorNoDisponible() {
        cuandoElAgenteLanza(new ProveedorNoDisponible(
                "gemini no está respondiendo o está saturado. Prueba con otro proveedor."));

        analizarEsperando(503)
                .body("detail", containsString("otro proveedor"))
                .header("Retry-After", notNullValue());
    }

    @Test
    @DisplayName("Filtro de contenido -> 422")
    void respuestaInutilizable() {
        cuandoElAgenteLanza(new RespuestaInutilizable(
                "Los filtros de contenido bloquearon la respuesta."));

        analizarEsperando(422).body("detail", containsString("filtros"));
    }

    @Test
    @DisplayName("Pliego demasiado grande para el modelo -> 413")
    void contextoDemasiadoGrande() {
        cuandoElAgenteLanza(new ContextoDemasiadoGrande(
                "El material tiene 900.000 caracteres y supera el límite de 800.000."));

        analizarEsperando(413).body("detail", containsString("supera el límite"));
    }

    // --------------------------------------------- errores de la aplicación

    /**
     * El caso que estaba roto.
     *
     * <p>{@code ServicioDePropuestas} lanza {@code domain.shared.PeticionInvalida} cuando
     * la petición no trae ni requisitos ni pliego. Es una regla del negocio, no un fallo
     * del proveedor, y por eso vive en el dominio; pero al no heredar de la jerarquía del
     * agente se quedó sin mapeador y salía como 500 sin cuerpo.
     */
    @Test
    @DisplayName("Una regla del negocio incumplida -> 422, no 500")
    void peticionInvalidaDelDominio() {
        Mockito.when(generarPropuesta.generar(Mockito.any()))
                .thenThrow(new PeticionInvalida(
                        "Envía requisitos estructurados o el texto del pliego."));

        given().contentType(ContentType.JSON)
                .body("""
                        {"objetoContractual": "Portal ciudadano",
                         "perfilProveedor": "Fabrica de software con doce anos"}
                        """)
                .when().post("/api/propuestas/generar")
                .then().statusCode(422)
                .body("detail", containsString("requisitos estructurados"))
                .body("correlationId", notNullValue());
    }

    @Test
    @DisplayName("Un pliego demasiado corto lo rechaza el contrato, sin llamar al modelo")
    void validacionDeEntrada() {
        given().contentType(ContentType.JSON)
                .body("{\"textoPliego\": \"muy corto\"}")
                .when().post("/api/analisis/requisitos")
                .then().statusCode(422)
                .body("detail", containsString("entre 40 y 400.000 caracteres"))
                .body("correlationId", notNullValue());

        Mockito.verifyNoInteractions(analizarPliego);
    }

    @Test
    @DisplayName("Un pliego enorme se rechaza en la frontera, sin llamar al modelo")
    void pliegoDemasiadoGrande() {
        // El punto de la cota: rechazar antes de construir el prompt, serializar el
        // contexto y facturar una llamada que iba a fallar en el proveedor.
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
