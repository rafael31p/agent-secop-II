package co.agentesecop.ia;

import co.agentesecop.config.ConfiguracionIA;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * Cierra un recurso compartido cuando ya nadie puede estar usándolo.
 *
 * <h2>El problema que resuelve</h2>
 *
 * <p>La caché de modelos cerraba el cliente HTTP en el momento de expulsarlo. Pero
 * expulsar de una caché no es lo mismo que dejar de usarse: la referencia que la caché ya
 * entregó a otro hilo sigue viva, y una llamada estructurada puede durar hasta dos
 * minutos. En ese rato, peticiones a modelos distintos podían empujar la entrada fuera del
 * tope de dieciséis y cerrar el cliente <b>por debajo de una llamada en curso</b>.
 *
 * <p>Lo peor no es el fallo, es que no se nota. La conexión cerrada a mitad de respuesta se
 * traduce como fallo transitorio, se reintenta, el reintento reconstruye el modelo y todo
 * funciona: el sistema se recupera y nadie se entera de que hay una carrera. Lo único que
 * queda es latencia y coste duplicados, que las métricas atribuyen al proveedor.
 *
 * <h2>Por qué un plazo de gracia y no un contador de referencias</h2>
 *
 * <p>Contar referencias con un {@code AtomicInteger} y cerrar al llegar a cero es exacto, y
 * es bastante más código: hay que incrementar al entregar, decrementar en todos los caminos
 * de salida —incluidas las excepciones— y no equivocarse en ninguno. El plazo de gracia
 * acota el problema con una fracción de la superficie de error: basta con que sea mayor que
 * lo que puede durar la llamada más larga.
 *
 * <p>El coste de equivocarse por exceso es despreciable: un puñado de clientes HTTP
 * ociosos unos minutos de más. El coste de equivocarse por defecto es abortar llamadas que
 * el usuario está esperando.
 */
@ApplicationScoped
public class CierreDiferido {

    private static final Logger LOG = Logger.getLogger(CierreDiferido.class);

    private final Duration gracia;
    private final ScheduledExecutorService planificador;

    @Inject
    public CierreDiferido(ConfiguracionIA config) {
        this(config.graciaDeCierre());
    }

    /**
     * @param gracia cuánto se espera antes de cerrar. Por encima del {@code @Timeout} por
     *     intento (120 s) con margen: si fuera menor, seguiría cerrando llamadas vivas, que
     *     es exactamente el defecto que esto corrige.
     */
    CierreDiferido(Duration gracia) {
        this.gracia = gracia;
        this.planificador = Executors.newSingleThreadScheduledExecutor(tarea -> {
            Thread hilo = new Thread(tarea, "cierre-diferido-de-modelos");
            // Demonio: cerrar clientes ociosos no puede impedir que la JVM termine.
            hilo.setDaemon(true);
            return hilo;
        });
    }

    /** Programa el cierre del recurso, si es que se puede cerrar. */
    public void programar(Object recurso) {
        if (!(recurso instanceof AutoCloseable cerrable)) {
            // Algunos modelos no exponen cierre; en ese caso el recolector se encarga.
            return;
        }
        planificador.schedule(() -> cerrar(cerrable), gracia.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void cerrar(AutoCloseable cerrable) {
        try {
            cerrable.close();
        } catch (Exception e) {
            // Un cliente que no cierra bien no debe tumbar nada: ya está fuera de la caché
            // y nadie lo va a volver a usar.
            LOG.debugf(e, "No se pudo cerrar un modelo expulsado de la caché.");
        }
    }

    @PreDestroy
    void detener() {
        // Los cierres pendientes se descartan: la JVM se está apagando y va a soltar los
        // sockets de todas formas. Esperar aquí solo alargaría la parada.
        planificador.shutdownNow();
    }
}
