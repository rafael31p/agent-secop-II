package co.agentesecop.secop;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Puntúa qué tan tecnológico es el objeto de un proceso, sin usar el modelo.
 *
 * <p>Es un filtro de primer nivel para ordenar y descartar ruido antes de gastar tokens;
 * la clasificación fina la hace el endpoint de relevancia.
 *
 * <p>Los términos se comparan con límites de palabra. Sin eso, los acrónimos cortos
 * producen falsos positivos masivos: «api» dentro de «capital», «soc» dentro de
 * «social», «tic» dentro de «logística», «erp» dentro de «cuerpo».
 */
public final class HeuristicaTI {

    /** Puntaje mínimo para considerar que un proceso es de tecnología. */
    public static final int UMBRAL = 8;

    private static final Map<String, Integer> PESOS = crearPesos();
    private static final Map<String, Pattern> PATRONES = compilarPatrones();

    private HeuristicaTI() {}

    /** Resultado de la evaluación: puntaje 0-100 y términos encontrados. */
    public record Resultado(int puntaje, List<String> senales) {}

    public static Resultado evaluar(String texto) {
        if (texto == null || texto.isBlank()) {
            return new Resultado(0, List.of());
        }
        String plano = normalizar(texto);
        int puntos = 0;
        List<String> senales = new ArrayList<>();
        for (var entrada : PATRONES.entrySet()) {
            if (entrada.getValue().matcher(plano).find()) {
                puntos += PESOS.get(entrada.getKey());
                senales.add(entrada.getKey());
            }
        }
        senales.sort(String::compareTo);
        return new Resultado(Math.min(puntos, 100), List.copyOf(senales));
    }

    static String normalizar(String texto) {
        String descompuesto = Normalizer.normalize(texto.toLowerCase(), Normalizer.Form.NFD);
        return descompuesto.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    private static Map<String, Pattern> compilarPatrones() {
        Map<String, Pattern> patrones = new LinkedHashMap<>();
        for (String termino : PESOS.keySet()) {
            patrones.put(
                    termino,
                    Pattern.compile("(?<![a-z0-9])" + Pattern.quote(normalizar(termino))
                            + "(?![a-z0-9])"));
        }
        return Map.copyOf(patrones);
    }

    private static Map<String, Integer> crearPesos() {
        Map<String, Integer> pesos = new LinkedHashMap<>();
        // Núcleo de software
        pesos.put("software", 10);
        pesos.put("aplicativo", 9);
        pesos.put("aplicación", 7);
        pesos.put("aplicaciones", 7);
        pesos.put("desarrollo de software", 14);
        pesos.put("fábrica de software", 14);
        pesos.put("sistema de información", 12);
        pesos.put("plataforma tecnológica", 11);
        pesos.put("portal web", 8);
        pesos.put("sitio web", 7);
        pesos.put("microservicios", 9);
        pesos.put("api", 6);
        // Infraestructura y nube
        pesos.put("nube", 8);
        pesos.put("cloud", 8);
        pesos.put("datacenter", 9);
        pesos.put("centro de datos", 9);
        pesos.put("servidores", 7);
        pesos.put("hosting", 7);
        pesos.put("iaas", 8);
        pesos.put("paas", 8);
        pesos.put("saas", 8);
        pesos.put("virtualización", 7);
        pesos.put("kubernetes", 9);
        pesos.put("contenedores", 7);
        // Seguridad
        pesos.put("ciberseguridad", 12);
        pesos.put("seguridad de la información", 11);
        pesos.put("soc", 6);
        pesos.put("siem", 8);
        pesos.put("pentesting", 9);
        pesos.put("ethical hacking", 9);
        pesos.put("iso 27001", 9);
        pesos.put("firewall", 7);
        pesos.put("mspi", 9);
        // Datos
        pesos.put("datos abiertos", 7);
        pesos.put("big data", 9);
        pesos.put("analítica", 7);
        pesos.put("inteligencia artificial", 10);
        pesos.put("machine learning", 10);
        pesos.put("business intelligence", 9);
        pesos.put("bodega de datos", 8);
        pesos.put("data warehouse", 8);
        pesos.put("etl", 7);
        // Servicios de TI
        pesos.put("mesa de ayuda", 9);
        pesos.put("mesa de servicio", 9);
        pesos.put("help desk", 8);
        pesos.put("soporte técnico", 8);
        pesos.put("outsourcing tecnológico", 10);
        pesos.put("interventoría tecnológica", 9);
        pesos.put("consultoría en tecnología", 10);
        pesos.put("arquitectura empresarial", 9);
        // Conectividad y hardware
        pesos.put("conectividad", 7);
        pesos.put("canal dedicado", 7);
        pesos.put("fibra óptica", 6);
        pesos.put("telecomunicaciones", 7);
        pesos.put("computadores", 6);
        pesos.put("equipos de cómputo", 7);
        pesos.put("impresoras", 4);
        pesos.put("licenciamiento", 8);
        pesos.put("licencias", 6);
        // Gobierno digital
        pesos.put("gobierno digital", 11);
        pesos.put("transformación digital", 10);
        pesos.put("interoperabilidad", 9);
        pesos.put("firma electrónica", 8);
        pesos.put("erp", 8);
        pesos.put("crm", 7);
        pesos.put("tecnologías de la información", 11);
        pesos.put("tic", 5);
        return Map.copyOf(pesos);
    }

    /**
     * Prefiltro enviado a la API de SECOP cuando se pide «solo TI».
     *
     * <p>Debe ser preciso: un término demasiado genérico (por ejemplo «inform», que casa
     * con «información» e «informe») agota el límite de la consulta con falsos positivos
     * que el puntaje local luego descarta, devolviendo cero resultados.
     */
    public static final List<String> TERMINOS_CONSULTA = List.of(
            "software", "tecnolog", "sistema de informaci", "aplicativo", "aplicaci",
            "plataforma", "ciberseguridad", "seguridad de la informaci", "nube", "cloud",
            "licenciamiento", "licencias de uso", "conectividad", "telecomunicaci",
            "computo", "informatic", "base de datos", "datos abiertos",
            "gobierno digital", "digital", "servidor", "hosting", "mesa de ayuda",
            "mesa de servicio", "portal web", "interoperabilidad", "TIC");
}
