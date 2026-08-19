package co.agentesecop.pruebas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Las cotas que impiden que un archivo hostil se lleve por delante el proceso.
 *
 * <h2>Por qué se comprueban en el arranque y no en la extracción</h2>
 *
 * <p>Porque los límites de POI son <b>estado global de la biblioteca</b>, no un parámetro
 * de la llamada. Se fijan una vez y valen para todas las extracciones; si nadie los fija,
 * valen los de fábrica, que están pensados para un proceso de escritorio abriendo un
 * archivo propio y no para un servidor abriendo lo que le suban.
 *
 * <p>Y se fijan en <b>todos los perfiles</b>, no solo en producción: un archivo hostil no
 * es menos hostil en desarrollo, y una suite que corriera sin las cotas puestas no estaría
 * probando lo que se despliega.
 */
@QuarkusTest
class EndurecimientoDeArranqueTest {

    /**
     * Un DOCX es un ZIP. Con los valores de fábrica de POI, 25 MB comprimidos pueden
     * expandirse al orden de gigabytes: el mamparo de la extracción acota <em>cuántas</em>
     * corren a la vez, no <em>cuánta</em> memoria consume cada una. Son dos cotas distintas
     * y hacían falta las dos.
     */
    @Test
    @DisplayName("La cota de descompresión está puesta antes de atender la primera petición")
    void lasCotasDeDescompresionEstanPuestas() {
        assertEquals(0.02, ZipSecureFile.getMinInflateRatio(), 1e-9,
                "Sin ratio mínimo, una bomba de descompresión pasa el filtro de tamaño");
        assertEquals(80L * 1024 * 1024, ZipSecureFile.getMaxEntrySize(),
                "Sin tope por entrada, una sola entrada del ZIP puede agotar el montón");
        assertTrue(ZipSecureFile.getMaxTextSize() <= 800_000,
                "El texto extraído debe quedar bajo el mismo límite que admite el modelo");
    }
}
