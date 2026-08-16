import type { EstadoCumplimiento, ItemCumplimiento } from "./tipos";

/**
 * Severidad decreciente. La matriz se presenta en este orden porque lo que
 * decide si una propuesta se rechaza es lo que no cumple, no lo que sí.
 */
export const ORDEN_ESTADOS: EstadoCumplimiento[] = [
  "no_cumple",
  "cumple_parcial",
  "no_evaluable",
  "cumple",
];

/** Ordena por severidad sin alterar el arreglo recibido. */
export function ordenarPorSeveridad(matriz: ItemCumplimiento[]): ItemCumplimiento[] {
  return [...matriz].sort(
    (a, b) => severidad(a.estado) - severidad(b.estado),
  );
}

/** Totales por estado, en orden de severidad y omitiendo los que no aparecen. */
export function contarPorEstado(
  matriz: ItemCumplimiento[],
): { estado: EstadoCumplimiento; total: number }[] {
  return ORDEN_ESTADOS.map((estado) => ({
    estado,
    total: matriz.filter((item) => item.estado === estado).length,
  })).filter((conteo) => conteo.total > 0);
}

/** Un estado desconocido se va al final en vez de colarse entre los graves. */
function severidad(estado: EstadoCumplimiento): number {
  const posicion = ORDEN_ESTADOS.indexOf(estado);
  return posicion === -1 ? ORDEN_ESTADOS.length : posicion;
}
