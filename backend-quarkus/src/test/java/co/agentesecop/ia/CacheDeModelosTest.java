package co.agentesecop.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Los dos defectos de concurrencia de la caché de modelos, comprobados.
 *
 * <p>Ninguno de los dos se manifiesta como un fallo visible, y por eso llevaban ahí desde
 * el principio: el primero solo cuesta latencia bajo carga, y el segundo se «recupera»
 * solo, reintentando y pagando la llamada dos veces. Los dos necesitan una prueba que los
 * provoque a propósito, porque el uso normal no los saca.
 *
 * <p>Son pruebas de concurrencia, así que se construyen con {@code CountDownLatch} y
 * esperas con condición, nunca con esperas a ciegas: una prueba intermitente se arregla o
 * se borra, no se tolera.
 */
class CacheDeModelosTest {

    /** Un cliente de mentira que sabe si lo cerraron. */
    private static final class ModeloFalso implements AutoCloseable {
        private final String nombre;
        private final AtomicBoolean cerrado = new AtomicBoolean();

        private ModeloFalso(String nombre) {
            this.nombre = nombre;
        }

        @Override
        public void close() {
            cerrado.set(true);
        }
    }

    private final ExecutorService hilos = Executors.newCachedThreadPool();

    @AfterEach
    void apagar() {
        hilos.shutdownNow();
    }

    private static CierreDiferido cierreTras(Duration gracia) {
        return new CierreDiferido(gracia);
    }

    private static void dormir(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ BE-K1

    @Test
    @DisplayName("Cincuenta hilos pidiendo el mismo modelo lo construyen una sola vez")
    void seConstruyeUnaSolaVez() throws Exception {
        var cache = new CacheDeModelos<ModeloFalso>(cierreTras(Duration.ofMinutes(3)));
        AtomicInteger construcciones = new AtomicInteger();
        int cuantos = 50;
        CountDownLatch todosListos = new CountDownLatch(cuantos);
        CountDownLatch salida = new CountDownLatch(1);
        Set<ModeloFalso> obtenidos = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < cuantos; i++) {
            hilos.submit(() -> {
                todosListos.countDown();
                salida.await();
                obtenidos.add(cache.obtener("gemini-3.6-flash", nombre -> {
                    construcciones.incrementAndGet();
                    // Construir un cliente de verdad tarda: DNS, TLS, pool.
                    dormir(50);
                    return new ModeloFalso(nombre);
                }));
                return null;
            });
        }
        assertTrue(todosListos.await(5, TimeUnit.SECONDS), "No arrancaron todos los hilos");
        salida.countDown();
        hilos.shutdown();
        assertTrue(hilos.awaitTermination(15, TimeUnit.SECONDS), "Se colgó alguno");

        assertEquals(1, construcciones.get(),
                "Se abrieron %d clientes HTTP para el mismo modelo"
                        .formatted(construcciones.get()));
        assertEquals(1, obtenidos.size(), "No todos recibieron la misma instancia");
    }

    @Test
    @DisplayName("Construir un modelo no detiene a quien pide otro distinto")
    void construirUnoNoBloqueaALosDemas() throws Exception {
        var cache = new CacheDeModelos<ModeloFalso>(cierreTras(Duration.ofMinutes(3)));
        CountDownLatch construyendo = new CountDownLatch(1);
        CountDownLatch sueltaAlLento = new CountDownLatch(1);

        hilos.submit(() -> {
            cache.obtener("lento", nombre -> {
                construyendo.countDown();
                try {
                    sueltaAlLento.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ModeloFalso(nombre);
            });
            return null;
        });
        assertTrue(construyendo.await(5, TimeUnit.SECONDS), "El lento no llegó a construir");

        // Con el diseño anterior, esta llamada esperaba a que terminara la de arriba.
        CountDownLatch rapidoListo = new CountDownLatch(1);
        hilos.submit(() -> {
            cache.obtener("rapido", ModeloFalso::new);
            rapidoListo.countDown();
            return null;
        });

        assertTrue(rapidoListo.await(3, TimeUnit.SECONDS),
                "Pedir un modelo distinto se quedó esperando a que se construyera otro: "
                        + "eso es el punto de serialización global que este cambio elimina.");
        sueltaAlLento.countDown();
    }

    /**
     * El defecto anterior, ejecutable.
     *
     * <p>No prueba código de producción: prueba <em>el diseño que se retiró</em>, para que
     * quede escrito por qué se retiró. Un comentario que dice «esto serializaba» se puede
     * discutir; una prueba que lo demuestra, no.
     */
    @Test
    @DisplayName("Y el diseño anterior sí lo hacía: synchronizedMap serializaba a todos")
    void elDisenoAnteriorSerializaba() throws Exception {
        Map<String, ModeloFalso> comoAntes =
                Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true));
        CountDownLatch construyendo = new CountDownLatch(1);
        CountDownLatch sueltaAlLento = new CountDownLatch(1);

        hilos.submit(() -> {
            comoAntes.computeIfAbsent("lento", nombre -> {
                construyendo.countDown();
                try {
                    sueltaAlLento.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ModeloFalso(nombre);
            });
            return null;
        });
        assertTrue(construyendo.await(5, TimeUnit.SECONDS));

        CountDownLatch rapidoListo = new CountDownLatch(1);
        hilos.submit(() -> {
            comoAntes.computeIfAbsent("rapido", ModeloFalso::new);
            rapidoListo.countDown();
            return null;
        });

        assertFalse(rapidoListo.await(1, TimeUnit.SECONDS),
                "Si esto pasa, synchronizedMap dejó de serializar y el motivo del cambio "
                        + "habría que revisarlo.");
        sueltaAlLento.countDown();
    }

    // ------------------------------------------------------------------ BE-K2

    /**
     * Ojo con lo que se afirma: <b>no</b> se da por supuesto cuál es la entrada expulsada.
     *
     * <p>La primera versión de esta prueba metía tres modelos en una caché de dos y
     * comprobaba que el primero no se hubiera cerrado. Pasaba, pero por el motivo
     * equivocado: Caffeine no es LRU estricto sino W-TinyLFU, y con una caché diminuta
     * puede <em>rechazar la entrada nueva</em> en vez de expulsar la vieja. El primero no
     * se cerraba porque nunca lo expulsaron.
     *
     * <p>La afirmación correcta no depende de la política: sea cual sea la víctima,
     * ninguna se cierra dentro del plazo de gracia.
     */
    @Test
    @DisplayName("Expulsar de la caché no cierra ningún cliente dentro del plazo de gracia")
    void laExpulsionNoCierraDeInmediato() {
        var cache = new CacheDeModelos<ModeloFalso>(cierreTras(Duration.ofSeconds(30)), 2);
        List<ModeloFalso> todos = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            todos.add(cache.obtener("modelo-" + i, ModeloFalso::new));
        }
        assertTrue(cache.tamano() <= 2, "La caché no respetó su tope: " + cache.tamano());

        // Ocho de los diez están fuera de la caché, y aun así ninguno se ha cerrado: quien
        // tuviera la referencia sigue pudiendo usarla. Una llamada estructurada dura hasta
        // dos minutos, y cerrar aquí la abortaría a mitad.
        assertTrue(todos.stream().noneMatch(m -> m.cerrado.get()),
                "Se cerró un cliente en el momento de expulsarlo, con la referencia todavía "
                        + "viva en manos de otro hilo.");
    }

    @Test
    @DisplayName("Pero acaban cerrándose: el plazo de gracia no es excusa para no cerrar")
    void acabaCerrandose() throws Exception {
        var cache = new CacheDeModelos<ModeloFalso>(cierreTras(Duration.ofMillis(200)), 2);
        List<ModeloFalso> todos = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            todos.add(cache.obtener("modelo-" + i, ModeloFalso::new));
        }
        // Caffeine amortiza el mantenimiento: la expulsión no ocurre en el instante de la
        // escritura que la provoca. Con tráfico real llega solo; aquí hay que pedirlo, o se
        // mediría el retraso del mantenimiento en vez del plazo de gracia.
        cache.tamano();

        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (todos.stream().noneMatch(m -> m.cerrado.get()) && System.nanoTime() < limite) {
            Thread.sleep(20);
        }

        long cerrados = todos.stream().filter(m -> m.cerrado.get()).count();
        assertTrue(cerrados > 0,
                "Ningún cliente expulsado llegó a cerrarse: eso es una fuga de sockets.");
    }

    @Test
    @DisplayName("Veinte hilos con modelos distintos: ninguna llamada en vuelo se aborta")
    void ningunaLlamadaEnVueloSeAborta() throws Exception {
        // Caché pequeña y veinte modelos distintos: la expulsión es constante, que es
        // justo el escenario en el que el cierre inmediato rompía llamadas vivas.
        var cache = new CacheDeModelos<ModeloFalso>(cierreTras(Duration.ofSeconds(30)), 4);
        int cuantos = 20;
        CountDownLatch salida = new CountDownLatch(1);
        CountDownLatch terminados = new CountDownLatch(cuantos);
        Set<String> abortadas = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < cuantos; i++) {
            String nombre = "modelo-" + i;
            hilos.submit(() -> {
                try {
                    salida.await();
                    ModeloFalso modelo = cache.obtener(nombre, ModeloFalso::new);
                    // «Usar» el modelo un rato, como haría una llamada al proveedor.
                    for (int paso = 0; paso < 10; paso++) {
                        Thread.sleep(30);
                        if (modelo.cerrado.get()) {
                            abortadas.add(modelo.nombre);
                            return null;
                        }
                    }
                } finally {
                    terminados.countDown();
                }
                return null;
            });
        }
        salida.countDown();
        assertTrue(terminados.await(20, TimeUnit.SECONDS), "Algún hilo no terminó");

        assertTrue(abortadas.isEmpty(),
                ("Se cerró el cliente por debajo de %d llamadas en curso: %s. El fallo se "
                        + "traduciría como transitorio, se reintentaría y nadie vería la "
                        + "carrera —solo latencia y coste duplicados—.")
                        .formatted(abortadas.size(), abortadas));
    }

    @Test
    @DisplayName("La caché sigue acotada: mil nombres distintos no dejan mil clientes")
    void sigueAcotada() {
        var cache = new CacheDeModelos<ModeloFalso>(cierreTras(Duration.ofMinutes(3)));

        for (int i = 0; i < 1_000; i++) {
            cache.obtener("modelo-" + i, ModeloFalso::new);
        }

        assertTrue(cache.tamano() <= CacheDeModelos.MAXIMO,
                "Quedaron %d entradas vivas".formatted(cache.tamano()));
    }

    @Test
    @DisplayName("Si construir falla, no queda una entrada rota cacheada")
    void unFalloAlConstruirNoSeCachea() {
        var cache = new CacheDeModelos<ModeloFalso>(cierreTras(Duration.ofMinutes(3)));

        try {
            cache.obtener("roto", nombre -> {
                throw new IllegalStateException("sin credenciales");
            });
        } catch (IllegalStateException esperado) {
            // El fallo se propaga, que es lo correcto.
        }

        // Si el fallo quedara cacheado, un problema pasajero de red dejaría ese modelo
        // inservible hasta reiniciar.
        ModeloFalso segundo = cache.obtener("roto", ModeloFalso::new);
        assertSame("roto", segundo.nombre);
    }
}
