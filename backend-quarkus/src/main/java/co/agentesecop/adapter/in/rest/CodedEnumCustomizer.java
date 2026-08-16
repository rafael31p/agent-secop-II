package co.agentesecop.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

/**
 * Instala {@link CodedEnumModule} en el {@code ObjectMapper} de la aplicación.
 *
 * <p>{@code ObjectMapperCustomizer} es el punto de extensión documentado de Quarkus y el
 * que de verdad se aplica. Declarar el módulo como bean CDI de tipo {@code Module} no
 * basta: el bean se descubre pero no se instala, y el contrato HTTP se rompe en silencio.
 */
@Singleton
public class CodedEnumCustomizer implements ObjectMapperCustomizer {

    @Override
    public void customize(ObjectMapper mapper) {
        mapper.registerModule(new CodedEnumModule());
    }
}
