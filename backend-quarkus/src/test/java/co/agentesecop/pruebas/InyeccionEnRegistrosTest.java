package co.agentesecop.pruebas;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.agentesecop.PerfilSinCredenciales;
import co.agentesecop.domain.shared.Texto;
import co.agentesecop.ia.RegistroProveedores;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Nadie puede fabricar una línea de registro desde una petición HTTP.
 *
 * <h2>Qué se está evitando</h2>
 *
 * <p>Un registro es texto plano donde cada línea es un evento, y esa es toda su
 * estructura. Quien mete un salto de línea en un valor que se registra puede inventar
 * eventos: enviar como nombre de proveedor {@code gemini}, un salto de línea y
 * {@code 2026-01-01 INFO [Seguridad] Sesión iniciada por administrador} deja en el archivo
 * una línea de auditoría que jamás ocurrió. Quien investigue un incidente leerá ese
 * archivo dándolo por bueno, y eso es lo que hace grave el defecto: no rompe nada,
 * corrompe la evidencia.
 *
 * <p>El agente registra tres clases de texto ajeno —lo que envía el usuario, lo que
 * responde el proveedor de modelos y lo que responde datos.gov.co— y hasta ahora la única
 * validación cubría una sola cabecera.
 *
 * <h2>Por qué se mira el registro y no el saneador</h2>
 *
 * <p>Una prueba que llame a {@link Texto#paraRegistro} y compruebe su salida verifica el
 * saneador, no que se esté usando donde hace falta. El defecto que se quiere evitar es
 * justamente olvidarse de llamarlo, así que la afirmación tiene que ser sobre lo que
 * acaba escrito.
 */
@QuarkusTest
@TestProfile(PerfilSinCredenciales.class)
class InyeccionEnRegistrosTest {

    /** Lo que un atacante querría ver aparecer como si fuera una línea propia. */
    private static final String LINEA_FALSA =
            "2026-01-01 12:00:00 INFO [Seguridad] Sesion iniciada por administrador";

    private static final String PLIEGO = "Anexo tecnico con requisitos de accesibilidad. "
            .repeat(3);

    @Inject
    RegistroProveedores proveedores;

    private ColectorDeRegistros registro;

    @BeforeEach
    void engancharse() {
        registro = new ColectorDeRegistros();
    }

    @AfterEach
    void soltarse() {
        registro.close();
    }

    private void ningunaLineaFabricada() {
        for (String mensaje : registro.mensajes()) {
            assertFalse(mensaje.contains(LINEA_FALSA),
                    "Se fabricó una línea de registro desde la petición: " + mensaje);
            assertFalse(mensaje.contains("\n"),
                    "Un mensaje con salto de línea es un evento partido en dos, y el "
                            + "segundo trozo lo escribió quien llamó: " + mensaje);
        }
    }

    /**
     * Primera línea de defensa: el contrato HTTP.
     *
     * <p>{@code proveedor} lleva un {@code @Pattern} de solo minúsculas, así que el valor
     * con salto de línea ni siquiera llega al servicio. Conviene fijarlo con una prueba
     * porque es una cota fácil de relajar sin darse cuenta —basta con querer admitir un
     * proveedor con guion— y entonces la única defensa pasaría a ser la segunda.
     */
    @Test
    @DisplayName("El contrato rechaza el nombre de proveedor con salto de línea")
    void elContratoLoRechaza() {
        String respuesta = given().contentType(ContentType.JSON)
                .body("""
                        {"textoPliego": "%s", "proveedor": "gemini\\n%s"}
                        """.formatted(PLIEGO, LINEA_FALSA))
                .when().post("/api/analisis/requisitos")
                .then().statusCode(422)
                .extract().jsonPath().getString("detail");

        assertFalse(respuesta.contains(LINEA_FALSA), respuesta);
        ningunaLineaFabricada();
    }

    /**
     * Segunda línea: el saneado, para los caminos que no pasan por Bean Validation.
     *
     * <p>No es paranoia: el puerto se invoca directamente desde las pruebas de resiliencia
     * y desde cualquier llamada interna futura, y ninguna de las dos pasa por el contrato
     * HTTP. Una defensa que solo existe en la frontera desaparece en cuanto alguien entra
     * por otra puerta.
     */
    @Test
    @DisplayName("Y si se salta el contrato, el saneado impide fabricar la línea igual")
    void elSaneadoAguantaSinElContrato() {
        try {
            proveedores.resolver("gemini\n" + LINEA_FALSA);
        } catch (RuntimeException esperado) {
            assertFalse(esperado.getMessage().contains(LINEA_FALSA),
                    "El mensaje arrastra la línea falsa: " + esperado.getMessage());
        }

        ningunaLineaFabricada();
    }

    // ------------------------------------------------- la función, aparte

    @Test
    @DisplayName("El saneador deja constancia del carácter de control en vez de borrarlo")
    void dejaConstancia() {
        String saneado = Texto.paraRegistro("gemini\n" + LINEA_FALSA);

        assertFalse(saneado.contains("\n"));
        // Borrarlo sin dejar rastro escondería justo el intento que interesa ver: quien
        // lea el registro debe poder notar que alguien envió un salto de línea.
        assertTrue(saneado.contains("␣"),
                "Debe verse que había un carácter de control: " + saneado);
    }

    @Test
    @DisplayName("Y acota la longitud, porque inundar el registro también es un ataque")
    void acotaLaLongitud() {
        String saneado = Texto.paraRegistro("a".repeat(100_000), 50);

        assertTrue(saneado.length() < 100, "Quedó en " + saneado.length() + " caracteres");
        assertTrue(saneado.contains("+99950"),
                "Debe decir cuánto se recortó, o el recorte engaña: " + saneado);
    }

    @Test
    @DisplayName("No mutila los acentos ni la eñe: el problema es la estructura, no el alfabeto")
    void conservaElEspanol() {
        String saneado = Texto.paraRegistro("Adquisición de licencias para la Alcaldía de Ñuñoa");

        assertTrue(saneado.contains("Adquisición"), saneado);
        assertTrue(saneado.contains("Ñuñoa"), saneado);
    }
}
