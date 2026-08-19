package co.agentesecop.adapter.out.document;

import co.agentesecop.domain.model.procurement.TextoDeDocumento;
import co.agentesecop.domain.shared.Texto;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jboss.logging.Logger;

/** Extrae texto de los pliegos subidos: PDF, DOCX y texto plano. */
@ApplicationScoped
public class ExtractorDocumentos {

    private static final Logger LOG = Logger.getLogger(ExtractorDocumentos.class);

    public static final int LIMITE_CARACTERES = 800_000;
    public static final long TAMANO_MAXIMO_BYTES = 25L * 1024 * 1024;

    /** Formato no soportado o archivo ilegible. */
    public static class DocumentoNoSoportado extends RuntimeException {
        public DocumentoNoSoportado(String mensaje) {
            super(mensaje);
        }

        public DocumentoNoSoportado(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    public TextoDeDocumento extraer(String nombreArchivo, byte[] contenido) {
        if (contenido == null || contenido.length == 0) {
            throw new DocumentoNoSoportado("El archivo está vacío.");
        }
        if (contenido.length > TAMANO_MAXIMO_BYTES) {
            throw new DocumentoNoSoportado(
                    "El archivo supera el máximo de %d MB."
                            .formatted(TAMANO_MAXIMO_BYTES / (1024 * 1024)));
        }

        String nombre = nombreArchivo == null ? "" : nombreArchivo.toLowerCase(Locale.ROOT);
        String texto;
        String tipo;
        Integer paginas = null;

        if (nombre.endsWith(".pdf")) {
            var resultado = desdePdf(contenido);
            texto = resultado.texto();
            paginas = resultado.paginas();
            tipo = "pdf";
        } else if (nombre.endsWith(".docx")) {
            texto = desdeDocx(contenido);
            tipo = "docx";
        } else if (nombre.endsWith(".txt") || nombre.endsWith(".md")) {
            texto = new String(contenido, StandardCharsets.UTF_8);
            tipo = "texto";
        } else {
            throw new DocumentoNoSoportado(
                    "Formato no soportado. Usa .pdf, .docx, .txt o .md. Los .doc antiguos "
                            + "deben convertirse a .docx o PDF.");
        }

        texto = limpiar(texto);
        boolean truncado = texto.length() > LIMITE_CARACTERES;
        if (truncado) {
            texto = texto.substring(0, LIMITE_CARACTERES);
        }

        if (sinContenidoUtil(texto)) {
            throw new DocumentoNoSoportado(
                    "No se extrajo texto del documento. Si es un PDF escaneado, requiere OCR "
                            + "previo: esta aplicación no realiza reconocimiento óptico de "
                            + "caracteres.");
        }

        return new TextoDeDocumento(
                nombreArchivo == null ? "documento" : nombreArchivo,
                tipo, texto.length(), paginas, texto, truncado);
    }

    private record TextoPdf(String texto, int paginas) {}

    private TextoPdf desdePdf(byte[] contenido) {
        try (PDDocument documento = Loader.loadPDF(contenido)) {
            var extractor = new PDFTextStripper();
            int paginas = documento.getNumberOfPages();
            StringBuilder acumulado = new StringBuilder();
            // Página a página para que una corrupta no tumbe el documento entero.
            for (int pagina = 1; pagina <= paginas; pagina++) {
                extractor.setStartPage(pagina);
                extractor.setEndPage(pagina);
                acumulado.append("\n\n--- Página ").append(pagina).append(" ---\n");
                try {
                    acumulado.append(extractor.getText(documento));
                } catch (IOException e) {
                    // El mensaje lo produce PDFBox al leer un archivo que subió el usuario,
                    // así que su contenido depende del archivo: se sanea antes de registrarlo.
                    LOG.warnf("No se pudo extraer la página %d: %s",
                            pagina, Texto.paraRegistro(e.getMessage(), 120));
                }
            }
            return new TextoPdf(acumulado.toString(), paginas);
        } catch (IOException e) {
            // Aquí sí viaja el mensaje de la biblioteca al usuario, y es la excepción
            // consciente a la regla de no publicar texto ajeno: el archivo es suyo, y
            // «documento cifrado» es justo lo que le dice qué hacer. Se acota y se sanea
            // para que no pueda ser ni un vector de inyección ni un volcado enorme.
            throw new DocumentoNoSoportado(
                    "No se pudo leer el PDF: " + Texto.paraRegistro(e.getMessage(), 120)
                            + ". Si está protegido con contraseña, quítala antes de subirlo.",
                    e);
        }
    }

    private String desdeDocx(byte[] contenido) {
        try (var documento = new XWPFDocument(new ByteArrayInputStream(contenido))) {
            List<String> partes = new ArrayList<>();
            documento.getParagraphs().stream()
                    .map(p -> p.getText() == null ? "" : p.getText())
                    .filter(t -> !t.isBlank())
                    .forEach(partes::add);

            // Las tablas de los anexos técnicos suelen contener los requisitos.
            documento.getTables().forEach(tabla ->
                    tabla.getRows().forEach(fila -> {
                        List<String> celdas = fila.getTableCells().stream()
                                .map(c -> c.getText() == null ? "" : c.getText().trim())
                                .toList();
                        if (celdas.stream().anyMatch(c -> !c.isEmpty())) {
                            partes.add(String.join(" | ", celdas));
                        }
                    }));

            return String.join("\n", partes);
        } catch (IOException e) {
            throw new DocumentoNoSoportado("No se pudo leer el DOCX: " + e.getMessage(), e);
        }
    }

    /**
     * Decide si el documento quedó realmente sin texto aprovechable.
     *
     * <p>No basta con {@code isBlank()}: las marcas «--- Página N ---» que inserta el
     * extractor de PDF cuentan como texto, así que un PDF escaneado sin capa de texto
     * pasaría el filtro y el usuario recibiría un documento con solo separadores en vez
     * del aviso de que necesita OCR.
     */
    static boolean sinContenidoUtil(String texto) {
        return MARCA_DE_PAGINA.matcher(texto).replaceAll("").isBlank();
    }

    /**
     * Las marcas de página, con el espacio acotado a espacios y tabuladores.
     *
     * <p>La versión anterior usaba {@code \\s}, que <b>en Java incluye el salto de
     * línea</b>. Con {@code (?m)^\\s*...\\s*$} el patrón dejaba de estar acotado por
     * línea y podía cruzar varias, así que el retroceso del motor crecía de forma
     * cuadrática sobre un texto que elige quien sube el documento: un PDF con cientos
     * de miles de espacios y saltos bastaba para dejar el hilo dando vueltas.
     *
     * <p>Con {@code [ \\t]} el patrón hace lo que dice —una marca ocupa una línea— y su
     * coste vuelve a ser lineal. El tope de dígitos es la otra mitad: sin él, una tira
     * de un millón de cifras seguiría siendo un ancla para el retroceso.
     */
    private static final Pattern MARCA_DE_PAGINA = Pattern.compile(
            "(?m)^[ \\t]*---[ \\t]*Página[ \\t]+\\d{1,6}[ \\t]*---[ \\t]*$");

    /** Colapsa las líneas en blanco repetidas para no gastar tokens en espacio vacío. */
    static String limpiar(String texto) {
        String[] lineas = texto.replace("\r\n", "\n").split("\n", -1);
        StringBuilder salida = new StringBuilder();
        int vacias = 0;
        for (String linea : lineas) {
            String recortada = linea.stripTrailing();
            if (recortada.isEmpty()) {
                vacias++;
                if (vacias <= 2) {
                    salida.append('\n');
                }
            } else {
                vacias = 0;
                salida.append(recortada).append('\n');
            }
        }
        return salida.toString().strip();
    }
}
