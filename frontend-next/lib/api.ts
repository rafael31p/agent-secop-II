/** Cliente HTTP tipado hacia el backend Quarkus del agente. */

import type {
  EstadoSalud,
  FiltroProcesos,
  MensajeChat,
  ProcesoResumen,
  ProveedorDisponible,
  RequisitoTecnico,
  RespuestaAnalisis,
  RespuestaDocumento,
  RespuestaProcesos,
  RespuestaPropuesta,
  RespuestaRelevancia,
  RespuestaValidacion,
  SeleccionIA,
} from "./tipos";

/**
 * URL del backend. Se llama directo (no por un proxy de Next) para que el chat
 * en streaming no pase por un intermediario que lo almacene en búfer; el CORS
 * del backend ya autoriza el puerto 3000.
 *
 * Cadena vacía = mismo origen, útil si algún día se sirve todo tras el mismo
 * dominio. Se lee de `NEXT_PUBLIC_API_URL`; sin ella se asume desarrollo local.
 */
export const BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8000";

const NO_HAY_BACKEND =
  "No se pudo contactar el backend. ¿Está corriendo en " +
  (BASE || "el mismo origen") +
  "?";

export class ErrorApi extends Error {
  constructor(
    message: string,
    readonly estado: number,
    /**
     * Identificador que el backend asignó al error y escribió en su registro.
     * El mensaje que llega es genérico a propósito —el detalle del proveedor
     * puede arrastrar credenciales—, así que esto es lo único que permite
     * conectar lo que vio el usuario con lo que pasó en el servidor.
     */
    readonly correlationId: string | null = null,
  ) {
    super(message);
    this.name = "ErrorApi";
  }
}

async function pedir<T>(ruta: string, opciones: RequestInit = {}): Promise<T> {
  let respuesta: Response;
  try {
    respuesta = await fetch(`${BASE}${ruta}`, {
      ...opciones,
      headers: {
        // FormData necesita que el navegador ponga su propio boundary.
        ...(opciones.body instanceof FormData
          ? {}
          : { "Content-Type": "application/json" }),
        ...opciones.headers,
      },
    });
  } catch {
    throw new ErrorApi(NO_HAY_BACKEND, 0);
  }

  if (!respuesta.ok) {
    throw await leerError(respuesta);
  }
  return (await respuesta.json()) as T;
}

/**
 * Construye el error a partir del cuerpo. El backend responde
 * `{"detail": "...", "correlationId": "..."}` en todos sus errores (ver
 * `ManejadorErrores`), pero un fallo del contenedor o de un proxy puede
 * devolver otra cosa; en ese caso se cae al código de estado.
 */
async function leerError(respuesta: Response): Promise<ErrorApi> {
  try {
    const cuerpo = await respuesta.json();
    const identificador =
      typeof cuerpo?.correlationId === "string" ? cuerpo.correlationId : null;
    return new ErrorApi(mensajeDe(cuerpo, respuesta), respuesta.status, identificador);
  } catch {
    return new ErrorApi(
      `${respuesta.status} ${respuesta.statusText}`.trim(),
      respuesta.status,
    );
  }
}

function mensajeDe(cuerpo: { detail?: unknown }, respuesta: Response): string {
  const detalle = cuerpo?.detail;
  if (typeof detalle === "string" && detalle.trim()) return detalle;
  if (Array.isArray(detalle)) {
    return detalle
      .map((e: { loc?: unknown[]; msg?: string }) => {
        const campo = Array.isArray(e.loc) ? e.loc.slice(1).join(".") : "";
        return campo ? `${campo}: ${e.msg}` : (e.msg ?? "");
      })
      .filter(Boolean)
      .join(" · ");
  }
  if (cuerpo === null || cuerpo === undefined) {
    return `${respuesta.status} ${respuesta.statusText}`.trim();
  }
  return JSON.stringify(cuerpo).slice(0, 300);
}

export const api = {
  salud: () => pedir<EstadoSalud>("/api/salud"),

  proveedores: () => pedir<ProveedorDisponible[]>("/api/proveedores"),

  buscarProcesos: (filtro: FiltroProcesos) =>
    pedir<RespuestaProcesos>("/api/procesos/buscar", {
      method: "POST",
      body: JSON.stringify(filtro),
    }),

  obtenerProceso: (id: string) =>
    pedir<ProcesoResumen>(`/api/procesos/${encodeURIComponent(id)}`),

  priorizarProcesos: (
    cuerpo: {
      procesos: ProcesoResumen[];
      perfilProveedor?: string | null;
      maximo?: number;
    } & SeleccionIA,
  ) =>
    pedir<RespuestaRelevancia>("/api/procesos/relevancia-ti", {
      method: "POST",
      body: JSON.stringify(cuerpo),
    }),

  analizarRequisitos: (
    cuerpo: {
      textoPliego: string;
      objetoContractual?: string | null;
      entidad?: string | null;
      modalidad?: string | null;
      valorEstimado?: number | null;
      contextoProveedor?: string | null;
    } & SeleccionIA,
  ) =>
    pedir<RespuestaAnalisis>("/api/analisis/requisitos", {
      method: "POST",
      body: JSON.stringify(cuerpo),
    }),

  cargarDocumento: (archivo: File) => {
    const datos = new FormData();
    datos.append("archivo", archivo);
    return pedir<RespuestaDocumento>("/api/analisis/documento", {
      method: "POST",
      body: datos,
    });
  },

  generarPropuesta: (
    cuerpo: {
      objetoContractual: string;
      perfilProveedor: string;
      requisitos?: RequisitoTecnico[];
      textoPliego?: string | null;
      entidad?: string | null;
      valorEstimado?: number | null;
      plazoMeses?: number | null;
      enfasis?: string[];
    } & SeleccionIA,
  ) =>
    pedir<RespuestaPropuesta>("/api/propuestas/generar", {
      method: "POST",
      body: JSON.stringify(cuerpo),
    }),

  validarPropuesta: (
    cuerpo: {
      textoPropuesta: string;
      requisitos?: RequisitoTecnico[];
      textoPliego?: string | null;
      objetoContractual?: string | null;
    } & SeleccionIA,
  ) =>
    pedir<RespuestaValidacion>("/api/propuestas/validar", {
      method: "POST",
      body: JSON.stringify(cuerpo),
    }),
};

/**
 * Chat con respuesta en streaming (SSE). Invoca `alFragmento` por cada trozo de
 * texto y devuelve el texto completo al terminar.
 *
 * No se usa `EventSource` porque solo sabe hacer GET y aquí hay que enviar el
 * historial y el contexto en el cuerpo; se lee el flujo a mano.
 */
export async function chatStream(
  cuerpo: {
    mensajes: MensajeChat[];
    contexto?: string | null;
  } & SeleccionIA,
  alFragmento: (texto: string) => void,
  senal?: AbortSignal,
): Promise<string> {
  let respuesta: Response;
  try {
    respuesta = await fetch(`${BASE}/api/chat`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(cuerpo),
      signal: senal,
    });
  } catch (excepcion) {
    // Una cancelación deliberada no es un fallo de red: se propaga tal cual
    // para que quien llama distinga los dos casos.
    if (senal?.aborted) throw excepcion;
    throw new ErrorApi(NO_HAY_BACKEND, 0);
  }

  if (!respuesta.ok) {
    throw await leerError(respuesta);
  }
  if (!respuesta.body) {
    throw new ErrorApi("El servidor no devolvió un cuerpo de respuesta.", 502);
  }

  const lector = respuesta.body.getReader();
  const decodificador = new TextDecoder();
  let bufer = "";
  let completo = "";

  while (true) {
    const { done, value } = await lector.read();
    if (done) break;
    bufer += decodificador.decode(value, { stream: true });

    // Los eventos SSE se separan por línea en blanco. El último trozo puede
    // quedar a medias, así que se conserva en el búfer hasta la próxima lectura.
    const bloques = bufer.split("\n\n");
    bufer = bloques.pop() ?? "";

    for (const bloque of bloques) {
      const suceso = interpretarBloque(bloque);
      if (!suceso) continue;
      if (suceso.evento === "delta" && suceso.datos.texto) {
        completo += suceso.datos.texto;
        alFragmento(suceso.datos.texto);
      } else if (suceso.evento === "error") {
        throw new ErrorApi(suceso.datos.mensaje ?? "Error del agente", 502);
      }
    }
  }

  return completo;
}

interface SucesoSse {
  evento: string;
  datos: { texto?: string; mensaje?: string };
}

/** Interpreta un bloque SSE ya delimitado. Devuelve null si no es utilizable. */
function interpretarBloque(bloque: string): SucesoSse | null {
  let evento = "message";
  const datos: string[] = [];

  for (const linea of bloque.split("\n")) {
    if (linea.startsWith(":")) continue; // comentario de keep-alive
    if (linea.startsWith("event:")) evento = linea.slice(6).trim();
    else if (linea.startsWith("data:")) datos.push(linea.slice(5).trim());
  }
  if (!datos.length) return null;

  try {
    return { evento, datos: JSON.parse(datos.join("\n")) };
  } catch {
    return null;
  }
}
