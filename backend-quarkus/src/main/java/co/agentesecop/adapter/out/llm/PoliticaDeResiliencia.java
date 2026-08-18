package co.agentesecop.adapter.out.llm;

import co.agentesecop.application.port.out.ModeloDeLenguaje;
import co.agentesecop.application.port.out.SeleccionDeModelo;
import co.agentesecop.adapter.out.llm.error.FalloTransitorio;
import co.agentesecop.adapter.out.llm.error.ProveedorNoDisponible;
import co.agentesecop.domain.shared.Texto;
import co.agentesecop.ia.RegistroProveedores;
import io.smallrye.faulttolerance.api.CircuitBreakerName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.jboss.logging.Logger;

/**
 * La política de resiliencia frente al proveedor de modelos, declarada en un solo sitio.
 *
 * <h2>Por qué esta clase existe y no son anotaciones sobre el puerto</h2>
 *
 * <p>Por dos restricciones de CDI y de MicroProfile que conviene dejar escritas, porque
 * las dos se descubren fallando:
 *
 * <ol>
 *   <li><b>Los interceptores no actúan sobre llamadas internas.</b> Anotar un método y
 *       llamarlo desde otro método de la misma clase no ejecuta ninguna política: la
 *       invocación no pasa por el proxy. Por eso la política vive en un bean aparte al que
 *       {@link ModeloDeLenguajeResiliente} llama de verdad.
 *   <li><b>Fault Tolerance no sabe emparejar un repliegue con una firma genérica.</b> El
 *       puerto declara {@code <T> T estructurado(…, Class<T>)}, y el despliegue falla con
 *       «can't find fallback method with matching parameter types and return type».
 *       Aquí la firma va borrada —{@code Object} y {@code Class<?>}— y el emparejamiento
 *       funciona; el {@code cast} que devuelve el tipo lo hace el decorador, que sí lo
 *       conoce. La alternativa era renunciar al {@code @Fallback}, y con ella al único
 *       mensaje que le dice al usuario que puede cambiar de proveedor.
 * </ol>
 *
 * <h2>Qué sustituye</h2>
 *
 * <p>Un bucle de reintentos escrito a mano en {@code ia.ProveedorLangChain4j}: treinta
 * líneas con {@code Thread.sleep} que, con la configuración de entonces —tres intentos,
 * 300 s de timeout—, podían retener un hilo de plataforma quince minutos. El pool de
 * trabajo de Quarkus son 200 hilos: doscientos análisis simultáneos lo agotaban y a partir
 * de ahí no respondía <em>nada</em>, incluida la sonda de salud que un orquestador
 * consultaría para decidir si reiniciar. Y además reimplementaba peor —bloqueando, sin
 * métricas y sin cortacircuitos— lo que la plataforma ya traía.
 *
 * <h2>Por qué tipos y no códigos HTTP</h2>
 *
 * <p>{@code retryOn} y {@code failOn} razonan sobre clases. Que
 * {@link FalloTransitorio} sea un tipo propio —y no un {@code if} sobre
 * {@code estadoHttp()}— es exactamente lo que permite que la política sea declarativa. Un
 * {@link co.agentesecop.adapter.out.llm.error.CredencialInvalida} ni se reintenta ni cuenta para abrir el circuito:
 * una clave mal puesta falla rápido y no envenena al proveedor para todos.
 *
 * <h2>Todo esto es configuración</h2>
 *
 * <p>Los valores de las anotaciones son el punto de partida; cada uno se sobrescribe por
 * propiedad sin recompilar. Ver la sección de resiliencia de {@code application.properties},
 * incluida la advertencia sobre las unidades.
 */
@ApplicationScoped
public class PoliticaDeResiliencia {

    private static final Logger LOG = Logger.getLogger(PoliticaDeResiliencia.class);

    /** Nombre del cortacircuitos, para que la sonda de salud pueda consultar su estado. */
    public static final String CIRCUITO = "modelo-de-lenguaje";

    private final ModeloDeLenguaje delegado;
    private final RegistroProveedores registro;

    @Inject
    public PoliticaDeResiliencia(
            @Medido ModeloDeLenguaje delegado, RegistroProveedores registro) {
        this.delegado = delegado;
        this.registro = registro;
    }

    /**
     * El orden de las políticas lo fija MicroProfile y conviene tenerlo presente:
     * {@code Fallback} envuelve a {@code Retry}, que envuelve a {@code CircuitBreaker},
     * que envuelve a {@code Timeout}, que envuelve a {@code Bulkhead}. De ahí que el
     * timeout sea <em>por intento</em> y no por operación; el techo de la operación
     * completa lo pone el {@code @Timeout} del caso de uso.
     */
    @Timeout(value = 120_000, unit = ChronoUnit.MILLIS)
    @Retry(maxRetries = 2,
            delay = 2_000, delayUnit = ChronoUnit.MILLIS,
            jitter = 1_000, jitterDelayUnit = ChronoUnit.MILLIS,
            // Por encima del peor caso de los tres intentos (3 x 120 s + esperas). Existe
            // para no ser un tercer límite sorpresa: quien debe cortar antes es el timeout
            // por intento o el presupuesto del caso de uso, no este tope.
            maxDuration = 400_000, durationUnit = ChronoUnit.MILLIS,
            retryOn = FalloTransitorio.class)
    // Los valores salen de una medición, no de una intuición, y la primera versión
    // estaba mal. Con umbral 8 y proporción 0,5 el circuito se abrió contra Gemini real
    // MIENTRAS TODAS LAS LLAMADAS FUNCIONABAN: `ft_retry_calls_total{retryResult=
    // "maxRetriesReached"}` marcaba cero —ninguna petición agotó sus reintentos— y sin
    // embargo `ft_circuitbreaker_calls_total{circuitBreakerResult="failure"}` iba por 6
    // de 13. La causa está en el orden que fija MicroProfile: Retry envuelve a
    // CircuitBreaker, así que el circuito cuenta INTENTOS y no llamadas. Un proveedor que
    // falla el 40 % de los intentos y responde bien al reintentar —el plan gratuito, en
    // horas punta— rozaba el umbral de continuo.
    //
    // Abrir el circuito ahí es peor que no tenerlo: retira durante treinta segundos un
    // servicio que estaba funcionando. Con 0,8 sobre 20 intentos hace falta que fallen 16
    // seguidos, cosa que con una tasa base del 50 % ocurre por azar menos del 1 % de las
    // veces: el circuito vuelve a significar «el proveedor está caído».
    @CircuitBreaker(requestVolumeThreshold = 20,
            failureRatio = 0.8,
            delay = 30_000, delayUnit = ChronoUnit.MILLIS,
            successThreshold = 2,
            failOn = FalloTransitorio.class)
    @CircuitBreakerName(CIRCUITO)
    @Bulkhead(12)
    // BulkheadException ya NO entra aquí, y la diferencia importa. El repliegue dice
    // «el proveedor no responde, prueba con otro», y con el mamparo lleno eso es dos
    // veces falso: el proveedor está sano, y cambiar de proveedor no ayuda porque el
    // mamparo es el mismo para todos. Culpar a un tercero de un límite propio manda al
    // usuario a una solución que no puede funcionar. Lo traduce `MapeadorMamparoLleno`
    // a un 429 con Retry-After, que además el frontend ya sabe presentar.
    @Fallback(fallbackMethod = "noDisponible",
            applyOn = {
                CircuitBreakerOpenException.class,
                TimeoutException.class
            })
    public Object estructurado(
            String sistema, String usuario, SeleccionDeModelo seleccion, Class<?> tipo) {
        return delegado.estructurado(sistema, usuario, seleccion, tipo);
    }

    /**
     * Lo que ve el usuario cuando la llamada ni siquiera se intentó.
     *
     * <p>Cubre los dos casos en que la culpa <em>sí</em> es del proveedor: el circuito
     * abierto porque venía fallando, y el presupuesto agotado porque no contestó a tiempo.
     * MicroProfile no pasa la excepción al método de repliegue, así que el mensaje no
     * distingue cuál de los dos fue; el detalle queda en el registro junto al identificador
     * de correlación, que es lo que permite reconstruirlo después.
     *
     * <p>El mensaje nombra al proveedor y sugiere cambiarlo. Hay cinco configurables: esa
     * frase es lo que convierte una caída total en una degradación.
     */
    public Object noDisponible(
            String sistema, String usuario, SeleccionDeModelo seleccion, Class<?> tipo) {
        // `proveedor` sale del cuerpo de la petición, así que va saneado a los dos sitios
        // donde acaba. Al registro, porque un salto de línea en ese valor permite fabricar
        // una entrada de registro entera: basta con enviar un nombre de proveedor que
        // lleve un salto de línea seguido de una línea de auditoría inventada.
        // Y al mensaje, porque se le devuelve al usuario y no tiene sentido reflejarle diez
        // mil caracteres de lo que envió.
        String proveedor = Texto.paraRegistro(proveedorDe(seleccion), 40);
        LOG.warnf("Repliegue del modelo: %s no está disponible (circuito abierto, mamparo "
                + "lleno o tiempo agotado) para %s", proveedor, tipo.getSimpleName());
        throw new ProveedorNoDisponible(
                ("%s no está respondiendo o está saturado. Prueba con otro proveedor desde "
                        + "el selector, o reintenta en unos minutos.").formatted(proveedor));
    }

    private String proveedorDe(SeleccionDeModelo seleccion) {
        String pedido = seleccion == null ? null : seleccion.proveedor();
        return (pedido == null || pedido.isBlank())
                ? registro.nombrePorDefecto()
                : pedido.trim();
    }
}
