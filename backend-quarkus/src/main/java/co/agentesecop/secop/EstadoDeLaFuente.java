package co.agentesecop.secop;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cómo le fue a la última consulta real a datos.gov.co.
 *
 * <h2>Por qué no se sondea</h2>
 *
 * <p>Una sonda de disponibilidad que llama a la fuente añade tráfico cada pocos segundos
 * para averiguar algo que el tráfico real ya sabe. Aquí se invierte: la búsqueda que hace
 * un usuario deja constancia de cómo le fue, y la sonda se limita a leerla. Cuesta cero y
 * refleja exactamente lo que están viendo los usuarios, que es lo que se quiere saber.
 *
 * <p>La contrapartida honesta es que en un servicio sin tráfico el dato envejece. De ahí
 * la ventana: pasado el plazo, el último fallo deja de contar y la fuente vuelve a
 * considerarse disponible mientras nadie demuestre lo contrario. Declarar «caído» algo que
 * lleva media hora sin que nadie consulte sería inventarse un diagnóstico.
 */
@ApplicationScoped
public class EstadoDeLaFuente {

    /** Cuánto tiempo pesa un fallo antes de dejar de considerarse noticia fresca. */
    static final Duration VENTANA = Duration.ofSeconds(30);

    /** Qué pasó y cuándo. Una sola referencia para que la lectura sea consistente. */
    public record Ultima(boolean exito, String motivo, Instant momento) {}

    private final AtomicReference<Ultima> ultima = new AtomicReference<>();

    public void registrarExito() {
        ultima.set(new Ultima(true, null, Instant.now()));
    }

    public void registrarFallo(String motivo) {
        ultima.set(new Ultima(false, motivo, Instant.now()));
    }

    public Optional<Ultima> ultima() {
        return Optional.ofNullable(ultima.get());
    }

    /** Disponible salvo que la última consulta fallara y sea reciente. */
    public boolean disponible() {
        return disponibleEn(Instant.now());
    }

    boolean disponibleEn(Instant ahora) {
        Ultima vista = ultima.get();
        if (vista == null || vista.exito()) {
            return true;
        }
        return vista.momento().plus(VENTANA).isBefore(ahora);
    }
}
