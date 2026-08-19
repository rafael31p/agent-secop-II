package co.agentesecop.adapter.out.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Extracción de texto de los pliegos subidos. */
class ExtractorDocumentosTest {

    private final ExtractorDocumentos extractor = new ExtractorDocumentos();

    @Test
    @DisplayName("Lee un archivo de texto plano")
    void textoPlano() {
        String contenido = "ANEXO TECNICO\nEl contratista debe entregar el codigo fuente.";

        var resultado = extractor.extraer(
                "anexo.txt", contenido.getBytes(StandardCharsets.UTF_8));

        assertEquals("texto", resultado.tipo());
        assertTrue(resultado.texto().contains("codigo fuente"));
        assertFalse(resultado.truncado());
    }

    @Test
    @DisplayName("Conserva las tildes y la eñe")
    void conservaAcentos() {
        String contenido = "Adquisición de licencias para el Ministerio de Educación Señalada";

        var resultado = extractor.extraer(
                "pliego.txt", contenido.getBytes(StandardCharsets.UTF_8));

        assertTrue(resultado.texto().contains("Adquisición"));
        assertTrue(resultado.texto().contains("Señalada"));
    }

    @Test
    @DisplayName("Extrae texto de un PDF real, con marca de página")
    void pdfReal() throws Exception {
        byte[] pdf = crearPdf("Resolucion 1519 de 2020 accesibilidad WCAG 2.1 AA");

        var resultado = extractor.extraer("pliego.pdf", pdf);

        assertEquals("pdf", resultado.tipo());
        assertEquals(1, resultado.paginas());
        assertTrue(resultado.texto().contains("Resolucion 1519"),
                "Texto extraído: " + resultado.texto());
        assertTrue(resultado.texto().contains("Página 1"));
    }

    @Test
    @DisplayName("Extrae también el contenido de las tablas de un DOCX")
    void docxConTabla() throws Exception {
        byte[] docx = crearDocxConTabla();

        var resultado = extractor.extraer("anexo.docx", docx);

        assertEquals("docx", resultado.tipo());
        assertTrue(resultado.texto().contains("Parrafo introductorio"));
        // Los requisitos de los anexos técnicos suelen venir en tablas; perderlas sería
        // perder justo lo que hay que analizar.
        assertTrue(resultado.texto().contains("RT-01"),
                "No se extrajo la tabla: " + resultado.texto());
        assertTrue(resultado.texto().contains("ISO 27001"));
    }

    @Test
    @DisplayName("Rechaza un formato no soportado explicando la alternativa")
    void formatoNoSoportado() {
        var error = assertThrows(
                ExtractorDocumentos.DocumentoNoSoportado.class,
                () -> extractor.extraer("pliego.xls", new byte[] {1, 2, 3}));

        assertTrue(error.getMessage().contains(".docx"),
                "Debe indicar los formatos válidos: " + error.getMessage());
    }

    @Test
    @DisplayName("Rechaza un archivo vacío")
    void archivoVacio() {
        assertThrows(
                ExtractorDocumentos.DocumentoNoSoportado.class,
                () -> extractor.extraer("pliego.txt", new byte[0]));
    }

    @Test
    @DisplayName("Un PDF sin capa de texto explica que hace falta OCR")
    void pdfEscaneadoSinTexto() throws Exception {
        byte[] pdfVacio = crearPdfSinTexto();

        var error = assertThrows(
                ExtractorDocumentos.DocumentoNoSoportado.class,
                () -> extractor.extraer("escaneado.pdf", pdfVacio));

        assertTrue(error.getMessage().contains("OCR"),
                "Debe mencionar el OCR: " + error.getMessage());
    }

    @Test
    @DisplayName("Colapsa los huecos largos a un máximo de dos líneas en blanco")
    void colapsaLineasEnBlanco() {
        String conHuecos = "linea uno\n\n\n\n\n\n\n\nlinea dos";

        String limpio = ExtractorDocumentos.limpiar(conHuecos);

        // Se conservan hasta dos líneas en blanco (tres saltos) como separación de
        // párrafos; cualquier hueco mayor se recorta.
        assertFalse(limpio.contains("\n\n\n\n"),
                "Quedaron huecos: " + limpio.replace("\n", "\\n"));
        assertTrue(limpio.contains("linea uno"));
        assertTrue(limpio.contains("linea dos"));
    }

    @Test
    @DisplayName("Un texto que solo tiene marcas de página se considera sin contenido")
    void marcasDePaginaNoSonContenido() {
        assertTrue(ExtractorDocumentos.sinContenidoUtil(
                "\n\n--- Página 1 ---\n\n--- Página 2 ---\n"));
        assertFalse(ExtractorDocumentos.sinContenidoUtil(
                "--- Página 1 ---\nObjeto: desarrollo de software"));
    }

    // ------------------------------------------------------------------ auxiliares

    private static byte[] crearPdf(String texto) throws Exception {
        try (var documento = new PDDocument(); var salida = new ByteArrayOutputStream()) {
            var pagina = new PDPage();
            documento.addPage(pagina);
            try (var flujo = new PDPageContentStream(documento, pagina)) {
                flujo.beginText();
                flujo.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                flujo.newLineAtOffset(50, 700);
                flujo.showText(texto);
                flujo.endText();
            }
            documento.save(salida);
            return salida.toByteArray();
        }
    }

    private static byte[] crearPdfSinTexto() throws Exception {
        try (var documento = new PDDocument(); var salida = new ByteArrayOutputStream()) {
            documento.addPage(new PDPage());
            documento.save(salida);
            return salida.toByteArray();
        }
    }

    private static byte[] crearDocxConTabla() throws Exception {
        try (var documento = new XWPFDocument(); var salida = new ByteArrayOutputStream()) {
            documento.createParagraph().createRun().setText("Parrafo introductorio");

            var tabla = documento.createTable(2, 2);
            tabla.getRow(0).getCell(0).setText("ID");
            tabla.getRow(0).getCell(1).setText("Requisito");
            tabla.getRow(1).getCell(0).setText("RT-01");
            tabla.getRow(1).getCell(1).setText("Certificacion ISO 27001 vigente");

            documento.write(salida);
            return salida.toByteArray();
        }
    }

    /**
     * La regex de marcas de página, acotada (SEC-6).
     *
     * <p>El patrón anterior usaba {@code \s}, que en Java incluye el salto de línea, así que
     * {@code ^\s*...\s*$} cruzaba líneas y el retroceso del motor dejaba de estar acotado.
     * El texto que lo alimenta lo elige quien sube el documento: bastaba un PDF con cientos
     * de miles de espacios para dejar el hilo dando vueltas, y con el mamparo de extracción
     * en cuatro, cuatro archivos así agotan la capacidad.
     *
     * <p>El límite de un segundo es holgado a propósito: el patrón correcto resuelve esto en
     * milisegundos, y el incorrecto no termina. No se está midiendo rendimiento, se está
     * distinguiendo lineal de cuadrático.
     */
    @Test
    @org.junit.jupiter.api.Timeout(10)
    @DisplayName("Un texto con muchas líneas en blanco no atasca la detección de marcas")
    void laRegexDeMarcasNoSeAtasca() {
        // Muchas LÍNEAS, no muchos espacios, y la diferencia es todo el defecto. Con `\s`
        // —que en Java incluye el salto de línea— cada inicio de línea podía consumir hasta
        // el final del texto, así que el coste crecía con el cuadrado del número de líneas.
        // La primera versión de esta prueba usaba 800.000 espacios SIN saltos: un único
        // inicio de línea, coste lineal, y el patrón defectuoso la pasaba en 8 ms.
        //
        // Medido con este texto: el patrón anterior seguía corriendo tras ocho segundos; el
        // acotado tarda veintitrés milisegundos. Y son 200 KB, muy por debajo del límite de
        // 800.000 caracteres que admite la carga.
        String hostil = "   \n".repeat(50_000);

        long inicio = System.nanoTime();
        boolean vacio = ExtractorDocumentos.sinContenidoUtil(hostil);
        long millis = (System.nanoTime() - inicio) / 1_000_000;

        assertTrue(vacio, "Solo espacios y saltos: no hay contenido útil");
        assertTrue(millis < 1_000,
                "La detección tardó %d ms: el retroceso volvió a ser cuadrático".formatted(millis));
    }

    @Test
    @DisplayName("Y sigue reconociendo las marcas que inserta el propio extractor")
    void laRegexSigueReconociendoLasMarcas() {
        assertTrue(ExtractorDocumentos.sinContenidoUtil(
                "\n\n--- Página 1 ---\n\n\n--- Página 2 ---\n"),
                "Un PDF escaneado sin capa de texto debe detectarse como vacío");
        assertFalse(ExtractorDocumentos.sinContenidoUtil(
                "--- Página 1 ---\nObjeto del contrato: portal ciudadano"),
                "Con texto real no está vacío");
    }
}
