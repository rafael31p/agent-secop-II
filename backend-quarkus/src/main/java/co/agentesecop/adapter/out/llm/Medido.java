package co.agentesecop.adapter.out.llm;

import jakarta.inject.Qualifier;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** El modelo instrumentado: mide cada llamada real. Ver {@link Directo} para la pila. */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface Medido {}
