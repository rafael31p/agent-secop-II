package co.agentesecop.servicio;

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
}
