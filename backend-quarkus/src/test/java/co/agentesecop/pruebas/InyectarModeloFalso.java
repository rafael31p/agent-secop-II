package co.agentesecop.pruebas;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Inyecta el servidor WireMock de {@link ServidorModeloFalso} en la prueba. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface InyectarModeloFalso {}
