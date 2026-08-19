package co.agentesecop.adapter.out.llm;

import jakarta.inject.Qualifier;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * El modelo sin adornos: la llamada real al proveedor.
 *
 * <p>Los calificadores son lo que permite apilar decoradores sobre un mismo puerto sin
 * que la aplicación se entere. Los servicios inyectan {@code ModeloDeLenguaje} a secas y
 * reciben el de la cima de la pila; cada decorador inyecta el de debajo por su
 * calificador. Añadir una capa —una caché, una cuota por cliente— es intercalar un bean,
 * no tocar ningún caso de uso.
 *
 * <pre>
 *   ModeloDeLenguaje            ← lo que ven los casos de uso
 *     ModeloDeLenguajeResiliente  @Retry @Timeout @CircuitBreaker @Bulkhead @Fallback
 *       &#64;Medido  ModeloDeLenguajeMedido      latencia, resultado y tokens
 *         &#64;Directo ModeloDeLenguajeLangChain4j  la llamada real
 * </pre>
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface Directo {}
