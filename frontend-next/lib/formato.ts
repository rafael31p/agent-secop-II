/** Formateo para lectores colombianos. Sin React: se prueba y se reutiliza aparte. */

/** Formatea pesos colombianos sin decimales. */
export function formatearCOP(valor: number | null | undefined): string {
  if (valor === null || valor === undefined || Number.isNaN(valor)) return "s/d";
  return new Intl.NumberFormat("es-CO", {
    style: "currency",
    currency: "COP",
    maximumFractionDigits: 0,
  }).format(valor);
}

/**
 * Formatea una fecha ISO de Socrata a formato local corto.
 *
 * Se recorta a la parte de fecha antes de construir el `Date`: los valores
 * llegan como `2026-08-11T00:00:00.000`, y dejar que el navegador los
 * interprete como hora local o UTC cambia el día mostrado según la zona.
 */
export function formatearFecha(iso: string | null | undefined): string {
  if (!iso) return "s/d";

  const soloFecha = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  const fecha = soloFecha
    ? new Date(Number(soloFecha[1]), Number(soloFecha[2]) - 1, Number(soloFecha[3]))
    : new Date(iso);

  if (Number.isNaN(fecha.getTime())) return iso;
  return fecha.toLocaleDateString("es-CO", {
    year: "numeric",
    month: "short",
    day: "2-digit",
  });
}

/** Miles con separador local. Evita repetir `toLocaleString("es-CO")` por ahí. */
export function formatearNumero(valor: number): string {
  return valor.toLocaleString("es-CO");
}
