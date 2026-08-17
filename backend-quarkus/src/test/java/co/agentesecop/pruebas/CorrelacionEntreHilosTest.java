package co.agentesecop.pruebas;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.agentesecop.PerfilSinCredenciales;
import co.agentesecop.adapter.in.rest.FiltroCorrelacion;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El identificador de correlación sobrevive al cambio de hilo, o no sirve para nada.
 *
 * <h2>Por qué esto no se puede razonar, hay que medirlo</h2>
 *
 * <p>El identificador se guarda en el MDC, que es un {@code ThreadLocal}. El filtro es
 * {@code @PreMatching} y el recurso lleva {@code @Blocking}: sobre el papel, uno corre en
 * el bucle de eventos y el otro en un hilo de trabajo, y el valor no cruzaría. Si eso
 * fuera cierto, las líneas de registro del servicio y de los adaptadores saldrían con el
 * identificador vacío — justo las que interesan cuando algo falla— y el mecanismo entero
 * sería decorativo.
 *
 * <p>Resulta que <b>sí cruza</b>: Quarkus propaga el contexto de diagnóstico por el
 * contexto duplicado de la petición, y se comprobó midiéndolo —filtro en
 * {@code vert.x-eventloop-thread-2}, mapeador en {@code executor-thread-1}, mismo valor—.
 * Esta clase lo deja fijado: si algún día la propagación deja de funcionar, por una
 * versión o por una anotación que cambie de sitio, se pone roja en vez de degradarse en
 * silencio y dejar los registros mudos.
 *
 * <p>Un detalle que costó un rato: el contexto <b>no</b> se puede leer del
 * {@code LogRecord}. Su copia la rellena la cadena de manejadores más tarde, así que sale
 * vacía y hace creer que la propagación está rota cuando no lo está. Hay que leerlo en el
 * momento de escribir la línea, desde la misma fachada que usa la aplicación.
 *
 * <h2>Y la contaminación cruzada, que es peor</h2>
 *
 * <p>Un registro sin identificador es incómodo. Un registro que atribuye un error a la
 * petición equivocada es <b>peor que no tener ninguno</b>, porque manda a quien investiga
 * a mirar la petición de otro usuario. La segunda prueba es la que vigila eso.
 */
@QuarkusTest
@TestProfile(PerfilSinCredenciales.class)
class CorrelacionEntreHilosTest {

    private ColectorDeRegistros registro;

    @BeforeEach
    void engancharse() {
        registro = new ColectorDeRegistros();
    }

    @AfterEach
    void soltarse() {
        registro.close();
    }

    /** Provoca una línea de registro escrita por el adaptador, en el hilo de trabajo. */
    private void peticionQueRegistra(String identificador) {
        given().contentType(ContentType.JSON)
                .header(FiltroCorrelacion.CABECERA, identificador)
                .body("""
                        {"textoPliego": "%s", "proveedor": "openai"}
                        """.formatted("Anexo tecnico con requisitos de accesibilidad. ".repeat(3)))
                .when().post("/api/analisis/requisitos")
                .then().statusCode(503);
    }

    @Test
    @DisplayName("El identificador llega a las líneas que escribe el adaptador")
    void llegaAlRegistroDelAdaptador() {
        peticionQueRegistra("peticion-alfa");

        List<String> conContexto = registro.correlacionesVistas();
        assertFalse(conContexto.isEmpty(),
                "Ninguna línea llevó identificador: el MDC no cruzó al hilo de trabajo y "
                        + "el identificador que ve el usuario no sirve para encontrar nada.");
        assertTrue(conContexto.stream().anyMatch("peticion-alfa"::equals),
                "Se registró con otro identificador: " + conContexto);
    }

    @Test
    @DisplayName("Un hilo reutilizado no arrastra el identificador de la petición anterior")
    void sinContaminacionCruzada() {
        peticionQueRegistra("peticion-alfa");
        registro.limpiar();

        // Varias seguidas para forzar la reutilización de hilos del pool.
        for (int i = 0; i < 5; i++) {
            peticionQueRegistra("peticion-beta");
        }

        List<String> vistos = registro.correlacionesVistas();
        assertFalse(vistos.contains("peticion-alfa"),
                "Una línea de la segunda petición se registró con el identificador de la "
                        + "primera. Quien investigue el fallo irá a mirar la petición "
                        + "equivocada, que es peor que no tener identificador: " + vistos);
    }
}
