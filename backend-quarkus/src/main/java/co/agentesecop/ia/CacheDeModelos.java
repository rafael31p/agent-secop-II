package co.agentesecop.ia;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import java.time.Duration;
import java.util.function.Function;

/**
 * Los clientes de modelo, cacheados sin que construir uno detenga a los demás.
 *
 * <h2>Lo que sustituye, y por qué era un punto de serialización</h2>
 *
 * <p>Un {@code Collections.synchronizedMap} sobre un {@code LinkedHashMap} en modo LRU.
 * Parecía razonable y tenía un defecto grave: {@code synchronizedMap} envuelve
 * <b>todos</b> los métodos en {@code synchronized (mutex)}, y la implementación por defecto
 * de {@code computeIfAbsent} ejecuta la función de mapeo <b>dentro</b> de ese bloque. Esa
 * función es {@code construirModelo}, que abre un cliente HTTP: resolución DNS, negociación
 * TLS, creación del pool de conexiones.
 *
 * <p>Es decir: mientras se construía un modelo, <b>ningún otro hilo podía leer la caché de
 * ese proveedor</b>, ni siquiera para un modelo que ya estaba cacheado. Con el mamparo en
 * doce, hasta once hilos esperando en un monitor por un trabajo que no les incumbe. El
 * arranque en frío era el peor caso: nadie tiene nada cacheado y todos convergen en el
 * mismo lock. Y la expulsión, que cerraba el cliente, ocurría también bajo ese monitor.
 *
 * <p>Caffeine bloquea <b>por clave</b>: quien pide un modelo que ya está cacheado no espera
 * a nadie, y quien pide el mismo que se está construyendo espera a ese y solo a ese.
 *
 * <h2>Por qué Caffeine y no escribirlo a mano</h2>
 *
 * <p>La parte difícil no es «construir fuera del lock» —eso sale con un
 * {@code CompletableFuture} y un {@code putIfAbsent}— sino combinarlo con un tope de
 * tamaño y una política de expulsión. Eso es una estructura de datos, no un truco, y
 * escribirla a mano para dieciséis entradas no compensa. La versión de Caffeine del BOM de
 * Quarkus es la que ya está fijada por la plataforma.
 *
 * <h2>Deja de ser LRU, y conviene saberlo</h2>
 *
 * <p>El diseño anterior expulsaba siempre la entrada menos usada recientemente. Caffeine
 * usa W-TinyLFU, que también puede <b>rechazar la entrada nueva</b> si estima que la que ya
 * está vale más. Para este uso da igual —lo que importa es que el tope se respete y que
 * nadie pague la construcción dos veces seguidas— pero no es la misma política, y dar por
 * supuesto lo contrario hace escribir pruebas que pasan por el motivo equivocado. Ocurrió:
 * la primera versión de {@code CacheDeModelosTest} comprobaba que el primer modelo no se
 * hubiera cerrado tras expulsarlo, y pasaba porque nunca lo expulsaron.
 *
 * <h2>Ejecutor directo, a propósito</h2>
 *
 * <p>Caffeine hace su mantenimiento —expulsiones incluidas— en un ejecutor. Por defecto es
 * un pool común y el momento exacto de la expulsión queda indeterminado. Aquí se ejecuta en
 * el hilo que llama: con dieciséis entradas el trabajo es de microsegundos, y a cambio la
 * expulsión es predecible, que es lo que permite razonar —y probar— cuándo se programa un
 * cierre.
 *
 * @param <V> el tipo de cliente cacheado: síncrono o de streaming
 */
public final class CacheDeModelos<V> {

    /**
     * Tope de modelos vivos por proveedor.
     *
     * <p>Dieciséis es holgado para el uso real —un operador alterna entre dos o tres
     * modelos— y acotado frente al abuso. La caché se indexa por un nombre que viene de la
     * petición: sin tope, pedir mil modelos distintos deja mil clientes HTTP abiertos.
     */
    static final int MAXIMO = 16;

    /** Un modelo que nadie usa en dos horas no vale el socket que ocupa. */
    private static final Duration CADUCIDAD = Duration.ofHours(2);

    private final Cache<String, V> entradas;

    public CacheDeModelos(CierreDiferido cierreDiferido) {
        this(cierreDiferido, MAXIMO);
    }

    CacheDeModelos(CierreDiferido cierreDiferido, int maximo) {
        this.entradas = Caffeine.newBuilder()
                .maximumSize(maximo)
                .expireAfterAccess(CADUCIDAD)
                .executor(Runnable::run)
                // Solo se PROGRAMA el cierre. Cerrar aquí mismo abortaría llamadas en
                // curso que todavía tienen la referencia; ver CierreDiferido.
                .evictionListener((String nombre, V modelo, RemovalCause causa) ->
                        cierreDiferido.programar(modelo))
                .build();
    }

    /**
     * El modelo para ese nombre, construyéndolo si hace falta.
     *
     * <p>El constructor se ejecuta una sola vez por nombre aunque lo pidan cincuenta hilos
     * a la vez, y sin bloquear las lecturas de los demás nombres.
     */
    public V obtener(String nombre, Function<String, V> constructor) {
        return entradas.get(nombre, constructor);
    }

    /** Cuántas entradas vivas hay. Para pruebas y para la métrica de caché. */
    public long tamano() {
        entradas.cleanUp();
        return entradas.estimatedSize();
    }
}
