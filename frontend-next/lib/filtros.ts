import type { FiltroProcesos } from "./tipos";

/**
 * Quita del filtro los campos que el usuario dejó en blanco.
 *
 * Importa más de lo que parece: una cadena vacía viaja como filtro real y el
 * backend la traduce a `WHERE upper(campo) LIKE '%%'`, que en Socrata descarta
 * las filas donde ese campo es nulo. El resultado sería una búsqueda que
 * silenciosamente devuelve menos procesos por un campo que nadie llenó.
 */
export function limpiarFiltro(filtro: FiltroProcesos): FiltroProcesos {
  const limpio: Record<string, unknown> = {};
  for (const [clave, valor] of Object.entries(filtro)) {
    if (valor === "" || valor === null || valor === undefined) continue;
    limpio[clave] = valor;
  }
  return limpio as FiltroProcesos;
}
