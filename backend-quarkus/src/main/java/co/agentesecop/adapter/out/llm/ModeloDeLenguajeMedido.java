package co.agentesecop.adapter.out.llm;

import co.agentesecop.application.port.out.ModeloDeLenguaje;
import co.agentesecop.application.port.out.SeleccionDeModelo;
import co.agentesecop.ia.ErroresIA;
import co.agentesecop.ia.RegistroProveedores;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mide cada llamada al modelo: cuánto tarda, cómo acaba y cuánto material mueve.
 *
 * <p>Es un decorador y no código dentro del proveedor por el mismo motivo que la
 * resiliencia: un proveedor nuevo hereda la instrumentación por composición, sin volver a
 * escribirla ni poder olvidarla.
 *
 * <h2>Por qué el contador de tokens es la métrica que importa</h2>
 *
 * <p>Es la única que responde «cuánto nos cuesta esto», que es la pregunta que decide si
 * el proyecto sigue. Se estima por caracteres —aproximada, pero suficiente para ver
 * tendencias— y se etiqueta como {@code estimacion=caracteres} para que nadie la confunda
 * con el conteo real del proveedor el día que se lea de la respuesta.
 *
 * <h2>Cardinalidad</h2>
 *
 * <p>El identificador de modelo llega de la petición del usuario y por tanto no está
 * acotado: etiquetar con él tal cual convierte una métrica en un millar de series
 * temporales, que es cómo se tumba un Prometheus. Se etiqueta solo si el modelo está en el
 * catálogo; cualquier otro cae en {@code otro}.
 */
@ApplicationScoped
@Medido
public class ModeloDeLenguajeMedido implements ModeloDeLenguaje {

    /** Caracteres por token. Regla de servilleta suficiente para una tendencia. */
    private static final int CARACTERES_POR_TOKEN = 4;

    private static final String OTRO = "otro";

    private final ModeloDeLenguaje delegado;
    private final MeterRegistry metricas;
    private final RegistroProveedores registro;

    /** Lo que se puede etiquetar. Se calcula una vez: el catálogo no cambia. */
    private volatile Set<String> modelosConocidos;

    private volatile Set<String> proveedoresConocidos;

    @Inject
    public ModeloDeLenguajeMedido(
            @Directo ModeloDeLenguaje delegado,
            MeterRegistry metricas,
            RegistroProveedores registro) {
        this.delegado = delegado;
        this.metricas = metricas;
        this.registro = registro;
    }

    @Override
    public <T> T estructurado(
            String sistema, String usuario, SeleccionDeModelo seleccion, Class<T> tipo) {
        Timer.Sample muestra = Timer.start(metricas);
        String resultado = "exito";
        try {
            T respuesta = delegado.estructurado(sistema, usuario, seleccion, tipo);
            contarTokens(seleccion, longitud(sistema) + longitud(usuario));
            return respuesta;
        } catch (ErroresIA.ErrorAgente e) {
            resultado = e.getClass().getSimpleName();
            throw e;
        } catch (RuntimeException e) {
            resultado = OTRO;
            throw e;
        } finally {
            muestra.stop(metricas.timer("llm.peticion",
                    "proveedor", proveedorDe(seleccion),
                    "modelo", modeloDe(seleccion),
                    // El tipo de destino identifica el caso de uso sin que el puerto tenga
                    // que arrastrar un parámetro solo para las métricas, y está acotado
                    // por definición: son los cuatro records del dominio.
                    "caso_de_uso", tipo.getSimpleName(),
                    "resultado", resultado));
        }
    }

    /**
     * El flujo se mide distinto: lo que interesa de un chat no es cuánto tardó la llamada
     * —termina cuando el usuario deja de leer— sino cuántos empezaron y cuántos acabaron
     * bien. Medir su duración como si fuera una petición mezclaría dos cosas distintas en
     * el mismo histograma.
     */
    @Override
    public Multi<String> flujo(String sistema, List<Turno> turnos, SeleccionDeModelo seleccion) {
        int caracteres = longitud(sistema)
                + turnos.stream().mapToInt(t -> longitud(t.contenido())).sum();
        contarTokens(seleccion, caracteres);

        AtomicInteger fragmentos = new AtomicInteger();
        return delegado.flujo(sistema, turnos, seleccion)
                .onItem().invoke(fragmentos::incrementAndGet)
                .onTermination().invoke((fallo, cancelado) -> {
                    String resultado = fallo != null
                            ? fallo.getClass().getSimpleName()
                            : (Boolean.TRUE.equals(cancelado) ? "cancelado" : "exito");
                    metricas.counter("llm.flujo",
                            "proveedor", proveedorDe(seleccion),
                            "modelo", modeloDe(seleccion),
                            "resultado", resultado).increment();
                    metricas.counter("llm.tokens",
                            "proveedor", proveedorDe(seleccion),
                            "modelo", modeloDe(seleccion),
                            "sentido", "salida",
                            "estimacion", "caracteres")
                            .increment(fragmentos.get());
                });
    }

    private void contarTokens(SeleccionDeModelo seleccion, int caracteres) {
        metricas.counter("llm.tokens",
                "proveedor", proveedorDe(seleccion),
                "modelo", modeloDe(seleccion),
                "sentido", "entrada",
                "estimacion", "caracteres")
                .increment((double) caracteres / CARACTERES_POR_TOKEN);
    }

    private static int longitud(String texto) {
        return texto == null ? 0 : texto.length();
    }

    /**
     * El nombre del proveedor también viene de la petición, y también hay que acotarlo.
     *
     * <p>Se descubrió mirando {@code /q/metrics} contra el servicio real: una petición con
     * {@code "proveedor": "inventado"} —que el registro rechaza con 400, pero que la
     * métrica ya había etiquetado— dejaba una serie temporal nueva. Basta con un bucle
     * pidiendo proveedores al azar para llenar de basura el almacén de métricas. Es el
     * mismo agujero que se había cerrado para el identificador de modelo, en el campo de
     * al lado.
     */
    private String proveedorDe(SeleccionDeModelo seleccion) {
        String pedido = seleccion == null ? null : seleccion.proveedor();
        if (pedido == null || pedido.isBlank()) {
            return registro.nombrePorDefecto();
        }
        String limpio = pedido.trim().toLowerCase();
        return proveedoresConocidos().contains(limpio) ? limpio : OTRO;
    }

    private String modeloDe(SeleccionDeModelo seleccion) {
        String pedido = seleccion == null ? null : seleccion.modelo();
        if (pedido == null || pedido.isBlank()) {
            return "(por defecto)";
        }
        return modelosConocidos().contains(pedido.trim()) ? pedido.trim() : OTRO;
    }

    private Set<String> modelosConocidos() {
        Set<String> cacheados = modelosConocidos;
        if (cacheados == null) {
            cacheados = registro.catalogo().stream()
                    .flatMap(p -> p.modelos().stream())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            modelosConocidos = cacheados;
        }
        return cacheados;
    }

    private Set<String> proveedoresConocidos() {
        Set<String> cacheados = proveedoresConocidos;
        if (cacheados == null) {
            cacheados = registro.catalogo().stream()
                    .map(p -> p.nombre())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            proveedoresConocidos = cacheados;
        }
        return cacheados;
    }
}
