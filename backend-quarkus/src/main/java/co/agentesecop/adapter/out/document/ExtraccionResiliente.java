package co.agentesecop.adapter.out.document;

import co.agentesecop.domain.model.procurement.TextoDeDocumento;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.Timeout;

/**
 * La cota que le faltaba a la ruta que más recursos consume.
 *
 * <h2>Qué estaba sin gobierno</h2>
 *
 * <p>Subir un documento leía el archivo entero a un {@code byte[]} —hasta 25 MB— y lo
 * recorría página a página con PDFBox, o cargaba el DOCX completo con POI. Trabajo
 * intensivo en CPU y memoria, y la única ruta del servicio <b>sin ninguna cota de
 * concurrencia</b>: ni mamparo, ni timeout, solo el límite de sesenta por clave y hora,
 * que es caudal y no simultaneidad.
 *
 * <p>Diez cargas a la vez son 250 MB de arreglos de bytes vivos más las estructuras de
 * PDFBox, en un pool de hilos que nada impedía que se llenara de extracciones. La
 * resiliencia de la fase 3 cubrió las llamadas al modelo y las consultas a SECOP; esta
 * tercera ruta se quedó fuera.
 *
 * <h2>Por qué cuatro y no doce</h2>
 *
 * <p>Porque el recurso escaso aquí es CPU y memoria, no conexiones salientes. Un mamparo
 * generoso sobre trabajo local no protege de nada: el cuello de botella son los núcleos.
 * El valor se ajusta con la métrica {@code document.extraction} cuando haya datos.
 *
 * <p>Es un bean aparte de {@link ExtractorDocumentos} por la misma razón que
 * {@code PoliticaDeResiliencia}: los interceptores no actúan sobre llamadas internas, así
 * que anotar el propio extractor no habría hecho nada.
 */
@ApplicationScoped
public class ExtraccionResiliente {

    private final ExtractorDocumentos extractor;

    @Inject
    public ExtraccionResiliente(ExtractorDocumentos extractor) {
        this.extractor = extractor;
    }

    @Bulkhead(4)
    @Timeout(value = 60_000, unit = ChronoUnit.MILLIS)
    public TextoDeDocumento extraer(String nombreArchivo, byte[] contenido) {
        return extractor.extraer(nombreArchivo, contenido);
    }
}
