package co.agentesecop.secop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.agentesecop.adapter.in.rest.dto.Solicitudes.FiltroProcesos;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pruebas de la construcción de la consulta SoQL y del mapeo de filas.
 *
 * <p>No necesitan Quarkus ni red: se instancia el cliente con una API nula porque estos
 * métodos no la usan.
 */
class SecopClienteFiltroTest {

    private final SecopCliente cliente = new SecopCliente(null, "p6dx-8zbt", "");

    private static FiltroProcesos filtro(
            String texto, String entidad, String estado,
            BigDecimal min, BigDecimal max, String desde, String hasta, boolean soloTi) {
        return new FiltroProcesos(
                texto, entidad, null, null, estado, min, max, desde, hasta, soloTi, 30, 0);
    }

    /**
     * Regresión del defecto más grave de la versión Python: Socrata ordena los nulos
     * primero al ordenar de forma descendente, así que sin esta cláusula el listado se
     * llenaba de procesos cancelados de 2018 en vez de los recientes.
     */
    @Test
    @DisplayName("Siempre excluye las filas sin fecha de publicación")
    void siempreExcluyeFechasNulas() {
        var advertencias = new ArrayList<String>();

        String where = cliente.construirFiltro(
                filtro(null, null, null, null, null, null, null, false), advertencias);

        assertTrue(where.contains("fecha_de_publicacion_del IS NOT NULL"),
                "Falta la cláusula que evita los nulos: " + where);
    }

    @Test
    @DisplayName("La búsqueda de texto cubre el objeto extendido y el título corto")
    void textoBuscaEnAmbasColumnas() {
        var advertencias = new ArrayList<String>();

        String where = cliente.construirFiltro(
                filtro("software", null, null, null, null, null, null, false), advertencias);

        assertTrue(where.contains("descripci_n_del_procedimiento"));
        assertTrue(where.contains("nombre_del_procedimiento"));
        assertTrue(where.contains("upper('%software%')"));
    }

    /**
     * El estado vive repartido en tres columnas con vocabularios distintos:
     * «Seleccionado» en una, «Presentación de oferta» en otra.
     */
    @Test
    @DisplayName("El estado se busca en las tres columnas que lo contienen")
    void estadoBuscaEnTresColumnas() {
        var advertencias = new ArrayList<String>();

        String where = cliente.construirFiltro(
                filtro(null, null, "Presentación", null, null, null, null, false),
                advertencias);

        assertTrue(where.contains("estado_del_procedimiento"));
        assertTrue(where.contains("estado_resumen"));
        assertTrue(where.contains("fase"));
    }

    @Test
    @DisplayName("Escapa las comillas simples para no romper el literal SoQL")
    void escapaComillas() {
        var advertencias = new ArrayList<String>();

        String where = cliente.construirFiltro(
                filtro(null, "O'Higgins", null, null, null, null, null, false), advertencias);

        assertTrue(where.contains("O''Higgins"), "Comilla sin escapar: " + where);
    }

    @Test
    @DisplayName("Ignora las fechas con formato inválido y lo advierte")
    void fechaInvalidaSeIgnora() {
        var advertencias = new ArrayList<String>();

        String where = cliente.construirFiltro(
                filtro(null, null, null, null, null, "ayer", null, false), advertencias);

        assertFalse(where.contains("ayer"), "No debería inyectar la fecha inválida");
        assertEquals(1, advertencias.size());
        assertTrue(advertencias.get(0).contains("fechaDesde"));
    }

    @Test
    @DisplayName("Acepta las fechas ISO y las convierte a rango completo del día")
    void fechaValidaSeAplica() {
        var advertencias = new ArrayList<String>();

        String where = cliente.construirFiltro(
                filtro(null, null, null, null, null, "2026-01-01", "2026-12-31", false),
                advertencias);

        assertTrue(where.contains("'2026-01-01T00:00:00.000'"));
        assertTrue(where.contains("'2026-12-31T23:59:59.999'"));
        assertTrue(advertencias.isEmpty());
    }

    @Test
    @DisplayName("El rango de valor se traduce a comparaciones numéricas")
    void rangoDeValor() {
        var advertencias = new ArrayList<String>();

        String where = cliente.construirFiltro(
                filtro(null, null, null, pesos("1000000"), pesos("5000000"),
                        null, null, false),
                advertencias);

        assertTrue(where.contains("precio_base >= 1000000"));
        assertTrue(where.contains("precio_base <= 5000000"));
    }

    private static BigDecimal pesos(String importe) {
        return new BigDecimal(importe);
    }

    /**
     * El defecto que la prueba de arriba no cubría, y que fallaba todos los días.
     *
     * <p>Los importes eran {@code Double} y se concatenaban con {@code +}. Java pasa a
     * notación científica <b>a partir de diez millones</b>, así que la cláusula salía como
     * {@code precio_base >= 1.0E7} y Socrata la rechazaba. La consulta fallaba, el cliente
     * capturaba la excepción y reintentaba <b>sin filtros</b>: quien pedía «procesos entre
     * 100 y 500 millones» recibía los últimos procesos de cualquier tipo del país, con HTTP
     * 200. La consulta que se ejecutaba no era la que se pidió, y nadie se enteraba.
     *
     * <p>El rango de esta prueba es el que se usa de verdad. La anterior se quedaba en uno
     * y cinco millones —las dos por debajo del umbral—, y por eso llevaba verde desde el
     * principio sobre un defecto activo. En contratación pública colombiana diez millones
     * de pesos es un contrato pequeño y el frontend avanza de millón en millón.
     */
    @ParameterizedTest(name = "{0} pesos")
    @ValueSource(strings = {
        "9999999", "10000000", "12500000", "100000000", "500000000", "1000000000000"
    })
    @DisplayName("Ningún importe sale nunca en notación científica")
    void losImportesNuncaSalenEnNotacionCientifica(String importe) {
        var advertencias = new ArrayList<String>();

        String where = cliente.construirFiltro(
                filtro(null, null, null, pesos(importe), pesos(importe), null, null, false),
                advertencias);

        assertTrue(where.contains("precio_base >= " + importe),
                "La cláusula no lleva el importe tal cual: " + where);
        assertFalse(where.matches("(?s).*[0-9]E[+-]?[0-9].*"),
                "Salió notación científica, que Socrata rechaza: " + where);
    }

    @Test
    @DisplayName("El prefiltro de TI usa términos precisos, no genéricos")
    void prefiltroTiEsPreciso() {
        var advertencias = new ArrayList<String>();

        String where = cliente.construirFiltro(
                filtro(null, null, null, null, null, null, null, true), advertencias);

        assertTrue(where.contains("%software%"));
        assertTrue(where.contains("%tecnolog%"));
        // "inform" a secas casaría con "información" e "informe" y agotaría el límite
        // de la consulta con falsos positivos.
        assertFalse(where.contains("'%inform%'"),
                "El término genérico 'inform' no debe estar en el prefiltro");
    }

    // ------------------------------------------------------------------- mapeo

    @Test
    @DisplayName("Mapea una fila real del conjunto de datos")
    void mapeaFilaReal() {
        Map<String, Object> fila = Map.of(
                "id_del_proceso", "CO1.REQ.123456",
                "referencia_del_proceso", "LP-2026-014",
                "entidad", "MINISTERIO DE TECNOLOGIAS",
                "descripci_n_del_procedimiento",
                        "Desarrollo de software para el sistema de informacion misional",
                "modalidad_de_contratacion", "Licitación pública",
                "precio_base", "1500000000",
                "duracion", "12",
                "unidad_de_duracion", "Mes(es)",
                "urlproceso", Map.of("url", "https://community.secop.gov.co/proceso/1"));

        var proceso = cliente.mapear(fila);

        assertEquals("CO1.REQ.123456", proceso.id());
        assertEquals("LP-2026-014", proceso.numeroProceso());
        // Mil quinientos millones exactos. Antes esta aserción se escribía como
        // `1_500_000_000.0` y el valor mapeado era el `Double` 1.5E9: la prueba llevaba el
        // defecto dentro, como valor esperado.
        assertEquals(new BigDecimal("1500000000"), proceso.valor());
        assertEquals("12 Mes(es)", proceso.duracion());
        assertEquals("https://community.secop.gov.co/proceso/1", proceso.url());
        assertTrue(proceso.scoreTi() >= 20, "Debería detectarse como TI: " + proceso.scoreTi());
    }

    @Test
    @DisplayName("Trata «No Definido» como ausencia de dato")
    void noDefinidoEsNulo() {
        assertNull(SecopCliente.texto("No Definido"));
        assertNull(SecopCliente.texto("No definido"));
        assertNull(SecopCliente.texto("   "));
        assertNull(SecopCliente.texto(null));
        assertEquals("Bogotá", SecopCliente.texto("  Bogotá  "));
    }

    @Test
    @DisplayName("Convierte importes con símbolos y separadores, sin perder exactitud")
    void conversionDeImportes() {
        assertEquals(new BigDecimal("1500000"), SecopCliente.aDecimal("$1,500,000"));
        assertEquals(new BigDecimal("64500000"), SecopCliente.aDecimal("64500000"));
        assertNull(SecopCliente.aDecimal("no aplica"));
        assertNull(SecopCliente.aDecimal(null));
    }

    /**
     * Y el importe tampoco puede salir en notación científica <b>hacia el navegador</b>.
     *
     * <p>Pasar de {@code Double} a {@code BigDecimal} arregla la consulta, pero abre el
     * mismo agujero en el otro extremo: Jackson serializa un {@code BigDecimal} con
     * {@code toString()} salvo que se le diga lo contrario, y {@code toString()} también
     * produce notación científica. El frontend leería {@code 1.0E9} donde antes leía un
     * número, y el importe se pintaría mal.
     */
    @Test
    @DisplayName("Un importe grande viaja al cliente como número, no como 1.0E9")
    void elImporteNoSaleEnNotacionCientificaEnElJson() throws Exception {
        var jackson = new com.fasterxml.jackson.databind.ObjectMapper();
        record Respuesta(BigDecimal valor) {}

        String json = jackson.writeValueAsString(
                new Respuesta(SecopCliente.aDecimal("1000000000")));

        assertTrue(json.contains("1000000000"), json);
        assertFalse(json.matches("(?s).*[0-9]E[+-]?[0-9].*"), json);
    }

    @Test
    @DisplayName("Un importe grande conserva todos sus dígitos")
    void importeGrandeExacto() {
        // Con Double, un billón de pesos se guardaba como 1.0E12 y salía así en el JSON de
        // respuesta. Son pesos: el punto flotante binario nunca fue el tipo correcto.
        assertEquals("1000000000000", SecopCliente.aDecimal("1000000000000").toPlainString());
    }

    @Test
    @DisplayName("Descarta la URL cuando no es una dirección http")
    void urlInvalidaSeDescarta() {
        var proceso = cliente.mapear(Map.of(
                "id_del_proceso", "X",
                "urlproceso", "no disponible"));

        assertNull(proceso.url());
    }

    @Test
    @DisplayName("Puntúa uniendo el objeto extendido y el título corto")
    void puntuaAmbasColumnas() {
        // La señal solo aparece en el título, no en la descripción.
        var proceso = cliente.mapear(Map.of(
                "id_del_proceso", "X",
                "descripci_n_del_procedimiento", "Contratar servicios profesionales",
                "nombre_del_procedimiento", "Desarrollo de software misional"));

        assertTrue(proceso.scoreTi() > 0,
                "Debería puntuar por el título aunque la descripción no tenga señales");
    }

    @Test
    @DisplayName("Una fila vacía no rompe el mapeo")
    void filaVacia() {
        var proceso = cliente.mapear(Map.of());

        assertNull(proceso.id());
        assertEquals(0, proceso.scoreTi());
        assertEquals(List.of(), proceso.senalesTi());
    }
}
