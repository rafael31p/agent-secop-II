package co.agentesecop.domain.shared;

import java.util.List;

/**
 * Normalización de listas que llegan de fuera.
 *
 * <p>Existe por una decisión de contrato que conviene no perder de vista: el frontend
 * <strong>nunca</strong> recibe {@code null} en una lista. Una lista vacía y una ausente
 * significan lo mismo para quien pinta la interfaz —«no hay nada que mostrar»— y
 * distinguirlas obliga a comprobar el nulo en cada punto de uso, que es donde aparecen los
 * fallos.
 *
 * <p>Estaba repartida en dos ayudantes de paquete, {@code Analisis.vacioSiNulo} y
 * {@code Propuestas.sinNulos}, con nombres que no decían lo mismo aunque hacían lo mismo.
 * Al salir los DTO del dominio dejaron de ser accesibles, que fue la ocasión para ponerlos
 * donde debían estar.
 */
public final class Listas {

    private Listas() {}

    /**
     * Copia inmutable, o vacía si la lista es nula.
     *
     * <p>{@link List#copyOf} rechaza elementos nulos, y eso es deliberado: un elemento
     * nulo dentro de la lista sí es un dato corrupto, no una ausencia.
     */
    public static <T> List<T> copiaOVacia(List<T> lista) {
        return lista == null ? List.of() : List.copyOf(lista);
    }
}
