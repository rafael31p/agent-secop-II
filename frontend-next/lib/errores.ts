import { ErrorApi } from "./api";

/**
 * Convierte cualquier excepción en el texto que se le muestra al usuario.
 *
 * <p>Añade la referencia que devuelve el backend cuando existe. Desde que los
 * errores del proveedor dejaron de viajar al navegador —podían arrastrar la
 * clave de la API—, el mensaje que llega es deliberadamente genérico, y esa
 * referencia es lo único que conecta lo que vio el usuario con la traza
 * completa del registro del servidor.
 */
export function mensajeDeError(excepcion: unknown): string {
  if (!(excepcion instanceof ErrorApi)) {
    return String(excepcion);
  }
  return excepcion.correlationId
    ? `${excepcion.message} (referencia ${excepcion.correlationId})`
    : excepcion.message;
}
