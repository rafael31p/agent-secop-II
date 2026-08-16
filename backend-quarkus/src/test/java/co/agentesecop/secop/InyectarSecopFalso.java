package co.agentesecop.secop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Inyecta el servidor WireMock de {@link ServidorSecopFalso} en la prueba. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface InyectarSecopFalso {}
