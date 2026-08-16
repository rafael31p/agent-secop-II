package co.agentesecop.secop;

import co.agentesecop.dominio.Secop.ProcesoResumen;
import co.agentesecop.dominio.Secop.RespuestaProcesos;
import co.agentesecop.dominio.Solicitudes.FiltroProcesos;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/** Consulta de procesos de contratación en SECOP II. */
@ApplicationScoped
public class SecopCliente {

    private static final Logger LOG = Logger.getLogger(SecopCliente.class);

    private static final Pattern FECHA_ISO = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** Columnas del objeto contractual: la extendida y el título corto. */
    private static final List<String> CAMPOS_TEXTO =
            List.of("descripci_n_del_procedimiento", "nombre_del_procedimiento");

    /**
     * Alias verificados contra el conjunto de datos {@code p6dx-8zbt}. Se conservan
     * alternativas porque el esquema ha cambiado entre versiones; se usa el primer alias
     * presente en la fila.
     */
    private static final Map<String, List<String>> ALIAS = Map.ofEntries(
            Map.entry("id", List.of("id_del_proceso", "id_proceso")),
            Map.entry("numeroProceso", List.of("referencia_del_proceso", "numero_del_proceso")),
            Map.entry("entidad", List.of("entidad", "nombre_de_la_entidad")),
            Map.entry("nitEntidad", List.of("nit_entidad", "nit_de_la_entidad")),
            Map.entry("departamento", List.of("departamento_entidad", "departamento")),
            Map.entry("ciudad", List.of("ciudad_entidad", "ciudad", "ciudad_de_la_unidad_de")),
            Map.entry("objeto",
                    List.of("descripci_n_del_procedimiento", "nombre_del_procedimiento")),
            Map.entry("modalidad", List.of("modalidad_de_contratacion")),
            Map.entry("estado",
                    List.of("estado_del_procedimiento", "estado_resumen", "fase")),
            Map.entry("valor", List.of("precio_base", "valor_total_adjudicacion")),
            Map.entry("fechaPublicacion",
                    List.of("fecha_de_publicacion_del", "fecha_de_publicacion")),
            Map.entry("fechaUltimaPublicacion",
                    List.of("fecha_de_ultima_publicaci", "fecha_de_publicacion_fase_3")),
            Map.entry("url", List.of("urlproceso", "url_del_proceso")),
            Map.entry("codigoUnspsc", List.of("codigo_principal_de_categoria")),
            Map.entry("duracion", List.of("duracion")),
            Map.entry("unidadDuracion", List.of("unidad_de_duracion")),
            Map.entry("tipoContrato", List.of("tipo_de_contrato")),
            Map.entry("ordenEntidad", List.of("ordenentidad")),
            Map.entry("adjudicado", List.of("adjudicado")));

    /**
     * Con «solo TI» se sobremuestrea: el prefiltro de la consulta es de alto recall y el
     * filtro fino se aplica en local. El mínimo evita que una petición de pocos
     * resultados traiga una muestra demasiado pequeña para filtrar.
     */
    private static final int FACTOR_SOBREMUESTREO = 8;
    private static final int MUESTRA_MINIMA = 200;
    private static final int LIMITE_MAXIMO_SOCRATA = 1000;

    private final SecopApi api;
    private final String datasetProcesos;
    private final String appToken;

    /**
     * El token va como {@code Optional} porque SmallRye convierte una propiedad definida
     * pero vacía ({@code agente.secop.app-token=}) en {@code null}, y un {@code String}
     * simple aborta el arranque con un error poco evidente. El token es opcional: sin él
     * la API pública funciona, solo con menos cuota.
     */
    @Inject
    public SecopCliente(
            @RestClient SecopApi api,
            @ConfigProperty(name = "agente.secop.dataset-procesos") String datasetProcesos,
            @ConfigProperty(name = "agente.secop.app-token") Optional<String> appToken) {
        this.api = api;
        this.datasetProcesos = datasetProcesos;
        this.appToken = appToken.orElse("");
    }

    /** Constructor directo, para pruebas que no necesitan el contenedor. */
    SecopCliente(SecopApi api, String datasetProcesos, String appToken) {
        this.api = api;
        this.datasetProcesos = datasetProcesos;
        this.appToken = appToken == null ? "" : appToken;
    }

    public String datasetProcesos() {
        return datasetProcesos;
    }

    public boolean tokenConfigurado() {
        return appToken != null && !appToken.isBlank();
    }

    public RespuestaProcesos buscar(FiltroProcesos filtro) {
        List<String> advertencias = new ArrayList<>();

        int limiteApi = filtro.soloTi()
                ? Math.min(
                        Math.max(filtro.limite() * FACTOR_SOBREMUESTREO, MUESTRA_MINIMA),
                        LIMITE_MAXIMO_SOCRATA)
                : filtro.limite();

        String where = construirFiltro(filtro, advertencias);
        List<Map<String, Object>> filas =
                consultar(limiteApi, filtro.offset(), "fecha_de_publicacion_del DESC",
                        where, advertencias);

        if (filas == null) {
            advertencias.add("La consulta con filtros falló; se devuelven resultados sin "
                    + "filtrar. Verifica el esquema del conjunto de datos configurado.");
            filas = Optional.ofNullable(
                            consultar(filtro.limite(), filtro.offset(), null, null, advertencias))
                    .orElse(List.of());
        }

        List<ProcesoResumen> procesos = new ArrayList<>(filas.stream().map(this::mapear).toList());

        if (filtro.soloTi()) {
            int revisados = procesos.size();
            procesos = new ArrayList<>(procesos.stream()
                    .filter(p -> p.scoreTi() != null && p.scoreTi() >= HeuristicaTI.UMBRAL)
                    .sorted(Comparator.comparing(
                            (ProcesoResumen p) -> p.scoreTi()).reversed())
                    .limit(filtro.limite())
                    .toList());
            if (procesos.isEmpty() && revisados > 0) {
                advertencias.add(
                        "Se revisaron %d procesos y ninguno superó el umbral de relevancia "
                                .formatted(revisados)
                                + "tecnológica (%d). Prueba con un texto específico o "
                                        .formatted(HeuristicaTI.UMBRAL)
                                + "desactiva el filtro de tecnología.");
            }
        }

        return new RespuestaProcesos(procesos.size(), procesos, datasetProcesos, advertencias);
    }

    public Optional<ProcesoResumen> obtenerPorId(String idProceso) {
        List<String> advertencias = new ArrayList<>();
        String seguro = escapar(idProceso);
        for (String campo : List.of("id_del_proceso", "referencia_del_proceso")) {
            List<Map<String, Object>> filas =
                    consultar(1, 0, null, campo + " = '" + seguro + "'", advertencias);
            if (filas != null && !filas.isEmpty()) {
                return Optional.of(mapear(filas.get(0)));
            }
        }
        return Optional.empty();
    }

    private List<Map<String, Object>> consultar(
            int limite, int offset, String orden, String where, List<String> advertencias) {
        try {
            return api.consultar(
                    datasetProcesos, limite, offset, orden, where,
                    appToken.isBlank() ? null : appToken);
        } catch (RuntimeException e) {
            // El detalle va al registro; al usuario, un mensaje de catálogo cerrado. La
            // excepción del cliente HTTP puede incluir la URL completa —con el app token
            // en la cadena de consulta— y esa cadena se pintaría en el navegador.
            LOG.warn("Error consultando SECOP", e);
            advertencias.add("La fuente de datos de SECOP II no respondió correctamente. "
                    + "Reintenta en unos minutos.");
            return null;
        }
    }

    // ------------------------------------------------------------------ consulta

    String construirFiltro(FiltroProcesos f, List<String> advertencias) {
        List<String> clausulas = new ArrayList<>();

        // Socrata coloca los nulos primero al ordenar de forma descendente, de modo que
        // sin esta cláusula el listado se llena de procesos antiguos sin fecha de
        // publicación en lugar de los más recientes.
        clausulas.add("fecha_de_publicacion_del IS NOT NULL");

        if (noVacio(f.texto())) {
            clausulas.add(orDeCampos(CAMPOS_TEXTO, f.texto()));
        }
        if (noVacio(f.entidad())) {
            clausulas.add(like("entidad", f.entidad()));
        }
        if (noVacio(f.departamento())) {
            clausulas.add(like("departamento_entidad", f.departamento()));
        }
        if (noVacio(f.modalidad())) {
            clausulas.add(like("modalidad_de_contratacion", f.modalidad()));
        }
        if (noVacio(f.estado())) {
            // El estado vive repartido en tres columnas con vocabularios distintos.
            clausulas.add(orDeCampos(
                    List.of("estado_del_procedimiento", "estado_resumen", "fase"), f.estado()));
        }
        if (f.valorMin() != null) {
            clausulas.add("precio_base >= " + f.valorMin());
        }
        if (f.valorMax() != null) {
            clausulas.add("precio_base <= " + f.valorMax());
        }
        if (noVacio(f.fechaDesde())) {
            if (FECHA_ISO.matcher(f.fechaDesde().trim()).matches()) {
                clausulas.add("fecha_de_publicacion_del >= '"
                        + f.fechaDesde().trim() + "T00:00:00.000'");
            } else {
                advertencias.add("fechaDesde ignorada (formato inválido): " + f.fechaDesde());
            }
        }
        if (noVacio(f.fechaHasta())) {
            if (FECHA_ISO.matcher(f.fechaHasta().trim()).matches()) {
                clausulas.add("fecha_de_publicacion_del <= '"
                        + f.fechaHasta().trim() + "T23:59:59.999'");
            } else {
                advertencias.add("fechaHasta ignorada (formato inválido): " + f.fechaHasta());
            }
        }
        if (f.soloTi()) {
            List<String> ors = new ArrayList<>();
            for (String termino : HeuristicaTI.TERMINOS_CONSULTA) {
                for (String campo : CAMPOS_TEXTO) {
                    ors.add(likeCrudo(campo, termino));
                }
            }
            clausulas.add("(" + String.join(" OR ", ors) + ")");
        }
        return String.join(" AND ", clausulas);
    }

    private static String orDeCampos(List<String> campos, String termino) {
        List<String> ors = campos.stream().map(c -> like(c, termino)).toList();
        return "(" + String.join(" OR ", ors) + ")";
    }

    private static String like(String campo, String valor) {
        return likeCrudo(campo, valor.trim());
    }

    private static String likeCrudo(String campo, String valor) {
        return "upper(%s) like upper('%%%s%%')".formatted(campo, escapar(valor));
    }

    /** Escapa comillas simples para literales SoQL. */
    static String escapar(String valor) {
        return valor == null ? "" : valor.replace("'", "''");
    }

    private static boolean noVacio(String valor) {
        return valor != null && !valor.isBlank();
    }

    // -------------------------------------------------------------------- mapeo

    ProcesoResumen mapear(Map<String, Object> fila) {
        // El objeto extendido y el título corto suelen diferir; se puntúan juntos para no
        // perder señales que solo aparecen en uno de los dos.
        String textoObjeto = String.join(" ",
                List.of(
                        Optional.ofNullable(texto(fila.get("descripci_n_del_procedimiento")))
                                .orElse(""),
                        Optional.ofNullable(texto(fila.get("nombre_del_procedimiento")))
                                .orElse("")));
        HeuristicaTI.Resultado ti = HeuristicaTI.evaluar(textoObjeto);

        String duracion = valor(fila, "duracion");
        String unidad = valor(fila, "unidadDuracion");
        if (duracion != null && unidad != null) {
            duracion = duracion + " " + unidad;
        }

        return new ProcesoResumen(
                valor(fila, "id"),
                valor(fila, "numeroProceso"),
                valor(fila, "entidad"),
                valor(fila, "nitEntidad"),
                valor(fila, "departamento"),
                valor(fila, "ciudad"),
                valor(fila, "objeto"),
                valor(fila, "modalidad"),
                valor(fila, "estado"),
                valor(fila, "tipoContrato"),
                valor(fila, "ordenEntidad"),
                valor(fila, "adjudicado"),
                aDouble(valor(fila, "valor")),
                valor(fila, "fechaPublicacion"),
                valor(fila, "fechaUltimaPublicacion"),
                extraerUrl(fila.get("urlproceso")),
                valor(fila, "codigoUnspsc"),
                duracion,
                ti.puntaje(),
                ti.senales());
    }

    private static String valor(Map<String, Object> fila, String campo) {
        for (String alias : ALIAS.getOrDefault(campo, List.of())) {
            String encontrado = texto(fila.get(alias));
            if (encontrado != null) {
                return encontrado;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static String texto(Object valor) {
        if (valor == null) {
            return null;
        }
        // Socrata devuelve las URL como {"url": "..."}.
        if (valor instanceof Map<?, ?> mapa) {
            Object url = ((Map<String, Object>) mapa).get("url");
            valor = url == null ? "" : url;
        }
        String texto = String.valueOf(valor).trim();
        if (texto.isEmpty()
                || texto.equalsIgnoreCase("No Definido")
                || texto.equalsIgnoreCase("No definido")) {
            return null;
        }
        return texto;
    }

    static Double aDouble(String valor) {
        if (valor == null) {
            return null;
        }
        try {
            return Double.valueOf(valor.replace("$", "").replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String extraerUrl(Object valor) {
        String texto = texto(valor);
        if (texto == null) {
            return null;
        }
        return texto.startsWith("http://") || texto.startsWith("https://") ? texto : null;
    }

}
