/** Cliente HTTP tipado hacia el backend del agente. */

import type {
  EstadoSalud,
  FiltroProcesos,
  MensajeChat,
  ProcesoResumen,
  RequisitoTecnico,
  RespuestaAnalisis,
  RespuestaDocumento,
  RespuestaProcesos,
  RespuestaPropuesta,
  RespuestaRelevancia,
  RespuestaValidacion,
} from "../types";

const BASE = import.meta.env.VITE_API_URL ?? "";

export class ErrorApi extends Error {
  constructor(
    message: string,
    readonly estado: number,
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
        ...(opciones.body instanceof FormData
          ? {}
          : { "Content-Type": "application/json" }),
        ...opciones.headers,
      },
    });
  } catch {
    throw new ErrorApi(
      "No se pudo contactar el backend. ¿Está corriendo en http://localhost:8000?",
      0,
    );
  }

  if (!respuesta.ok) {
    throw new ErrorApi(await extraerDetalle(respuesta), respuesta.status);
  }
  return (await respuesta.json()) as T;
}

async function extraerDetalle(respuesta: Response): Promise<string> {
  try {
    const cuerpo = await respuesta.json();
    const detalle = cuerpo?.detail;
    if (typeof detalle === "string") return detalle;
    if (Array.isArray(detalle)) {
      // Errores de validación de FastAPI/Pydantic
      return detalle
        .map((e: { loc?: unknown[]; msg?: string }) => {
          const campo = Array.isArray(e.loc) ? e.loc.slice(1).join(".") : "";
          return campo ? `${campo}: ${e.msg}` : (e.msg ?? "");
        })
        .filter(Boolean)
        .join(" · ");
    }
    return JSON.stringify(cuerpo).slice(0, 300);
  } catch {
    return `${respuesta.status} ${respuesta.statusText}`;
  }
}

export const api = {
  salud: () => pedir<EstadoSalud>("/api/salud"),

  buscarProcesos: (filtro: FiltroProcesos) =>
    pedir<RespuestaProcesos>("/api/procesos/buscar", {
      method: "POST",
      body: JSON.stringify(filtro),
    }),

  obtenerProceso: (id: string) =>
    pedir<ProcesoResumen>(`/api/procesos/${encodeURIComponent(id)}`),

  priorizarProcesos: (procesos: ProcesoResumen[], perfil?: string, maximo = 15) =>
    pedir<RespuestaRelevancia>("/api/procesos/relevancia-ti", {
      method: "POST",
      body: JSON.stringify({
        procesos,
        perfil_proveedor: perfil || null,
        maximo,
      }),
    }),

  analizarRequisitos: (cuerpo: {
    texto_pliego: string;
    objeto_contractual?: string | null;
    entidad?: string | null;
    modalidad?: string | null;
    valor_estimado?: number | null;
    contexto_proveedor?: string | null;
  }) =>
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

  generarPropuesta: (cuerpo: {
    objeto_contractual: string;
    perfil_proveedor: string;
    requisitos?: RequisitoTecnico[];
    texto_pliego?: string | null;
    entidad?: string | null;
    valor_estimado?: number | null;
    plazo_meses?: number | null;
    enfasis?: string[];
  }) =>
    pedir<RespuestaPropuesta>("/api/propuestas/generar", {
      method: "POST",
      body: JSON.stringify(cuerpo),
    }),

  validarPropuesta: (cuerpo: {
    texto_propuesta: string;
    requisitos?: RequisitoTecnico[];
    texto_pliego?: string | null;
    objeto_contractual?: string | null;
  }) =>
    pedir<RespuestaValidacion>("/api/propuestas/validar", {
      method: "POST",
      body: JSON.stringify(cuerpo),
    }),
};

/**
 * Chat con respuesta en streaming (SSE). Invoca `alFragmento` por cada trozo de texto.
 * Devuelve el texto completo al terminar.
 */
export async function chatStream(
  mensajes: MensajeChat[],
  contexto: string | null,
  alFragmento: (texto: string) => void,
  señal?: AbortSignal,
): Promise<string> {
  const respuesta = await fetch(`${BASE}/api/chat`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ mensajes, contexto }),
    signal: señal,
  });

  if (!respuesta.ok) {
    throw new ErrorApi(await extraerDetalle(respuesta), respuesta.status);
  }
  if (!respuesta.body) {
    throw new ErrorApi("El servidor no devolvió un cuerpo de respuesta.", 500);
  }

  const lector = respuesta.body.getReader();
  const decodificador = new TextDecoder();
  let bufer = "";
  let completo = "";

  while (true) {
    const { done, value } = await lector.read();
    if (done) break;
    bufer += decodificador.decode(value, { stream: true });

    // Los eventos SSE se separan por línea en blanco.
    const bloques = bufer.split("\n\n");
    bufer = bloques.pop() ?? "";

    for (const bloque of bloques) {
      let evento = "message";
      const datos: string[] = [];
      for (const linea of bloque.split("\n")) {
        if (linea.startsWith("event:")) evento = linea.slice(6).trim();
        else if (linea.startsWith("data:")) datos.push(linea.slice(5).trim());
      }
      if (!datos.length) continue;

      let carga: { texto?: string; mensaje?: string };
      try {
        carga = JSON.parse(datos.join("\n"));
      } catch {
        continue;
      }

      if (evento === "delta" && carga.texto) {
        completo += carga.texto;
        alFragmento(carga.texto);
      } else if (evento === "error") {
        throw new ErrorApi(carga.mensaje ?? "Error del agente", 500);
      }
    }
  }

  return completo;
}
