package co.agentesecop.adapter.in.rest;

import co.agentesecop.domain.model.proposal.EstadoCumplimiento;
import co.agentesecop.domain.model.proposal.Veredicto;
import co.agentesecop.domain.model.tender.Criticidad;
import co.agentesecop.domain.model.tender.NivelRiesgo;
import co.agentesecop.domain.model.tender.TipoRiesgo;
import co.agentesecop.domain.shared.CodedEnum;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.io.Serial;

/**
 * Serializa y deserializa las enumeraciones del dominio por su código.
 *
 * <p>Concentra aquí lo que antes eran cinco parejas de {@code @JsonValue} y
 * {@code @JsonCreator} repartidas por el dominio. El beneficio no es solo menos código:
 * el dominio deja de depender de Jackson, que es el requisito de la regla de
 * dependencias.
 *
 * <p>El registro lo hace {@link CodedEnumCustomizer}, no la anotación de un bean. Se
 * intentó primero declarar esta clase como bean CDI de tipo {@code Module} confiando en
 * que Quarkus lo recogiera solo: el bean se descubre —se puede inyectar— pero
 * <strong>no llega al {@code ObjectMapper}</strong>, y el JSON sigue saliendo con los
 * nombres de constante. Como nada fallaba del lado de Java, el único síntoma habría sido
 * el frontend recibiendo {@code "OBLIGATORIO"} donde espera {@code "obligatorio"}.
 *
 * <h2>Por qué el registro es explícito y no por reflexión</h2>
 *
 * <p>Cada enumeración necesita su propio valor por defecto —el que se usa cuando el
 * modelo devuelve algo que no se reconoce—, y ese valor es una decisión de negocio
 * distinta en cada caso: un requisito no clasificado es {@code INFORMATIVO}, pero una
 * validación no clasificada es {@code NO_EVALUABLE}, que es mucho más conservador.
 * Descubrirlo por reflexión exigiría una anotación adicional y ganaría poco: registrar
 * una enumeración nueva es una línea.
 */
public class CodedEnumModule extends SimpleModule {

    @Serial
    private static final long serialVersionUID = 1L;

    public CodedEnumModule() {
        super("CodedEnum");
        // Ante un valor irreconocible se cae al más conservador de cada familia: nunca a
        // uno que afirme algo que el modelo no dijo.
        registrar(Criticidad.class, Criticidad.INFORMATIVO);
        registrar(NivelRiesgo.class, NivelRiesgo.MEDIO);
        registrar(TipoRiesgo.class, TipoRiesgo.TECNICO);
        registrar(EstadoCumplimiento.class, EstadoCumplimiento.NO_EVALUABLE);
        registrar(Veredicto.class, Veredicto.RIESGO_DE_RECHAZO);
    }

    private <E extends Enum<E> & CodedEnum> void registrar(Class<E> tipo, E porDefecto) {
        addSerializer(tipo, new PorCodigo<>());
        addDeserializer(tipo, new DesdeCodigo<>(tipo, porDefecto));
    }

    private static final class PorCodigo<E extends Enum<E> & CodedEnum>
            extends JsonSerializer<E> {
        @Override
        public void serialize(E valor, JsonGenerator generador, SerializerProvider proveedor)
                throws IOException {
            generador.writeString(valor.code());
        }
    }

    private static final class DesdeCodigo<E extends Enum<E> & CodedEnum>
            extends JsonDeserializer<E> {

        private final Class<E> tipo;
        private final E porDefecto;

        private DesdeCodigo(Class<E> tipo, E porDefecto) {
            this.tipo = tipo;
            this.porDefecto = porDefecto;
        }

        @Override
        public E deserialize(JsonParser analizador, DeserializationContext contexto)
                throws IOException {
            return CodedEnum.parse(tipo, analizador.getValueAsString(), porDefecto);
        }

        /** Un campo ausente equivale a uno irreconocible: mismo valor por defecto. */
        @Override
        public E getNullValue(DeserializationContext contexto) {
            return porDefecto;
        }
    }
}
