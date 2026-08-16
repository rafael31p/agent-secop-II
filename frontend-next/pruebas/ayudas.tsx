/** Utilidades y datos de ejemplo compartidos por las pruebas. */

import { render, type RenderResult } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactElement, ReactNode } from "react";
import { ProveedorEspacio } from "@/lib/estado";
import { ProveedorIA } from "@/lib/ia";
import type {
  ItemCumplimiento,
  ProcesoResumen,
  ProveedorDisponible,
  RequisitoTecnico,
  RespuestaAnalisis,
} from "@/lib/tipos";

/**
 * Monta el componente con los dos contextos de la aplicación, en el mismo orden
 * que el layout real. Devuelve además un `usuario` de user-event ya preparado.
 */
export function renderizar(ui: ReactElement): RenderResult & {
  usuario: ReturnType<typeof userEvent.setup>;
} {
  const usuario = userEvent.setup();
  const resultado = render(ui, { wrapper: Envoltura });
  return { ...resultado, usuario };
}

function Envoltura({ children }: { children: ReactNode }) {
  return (
    <ProveedorIA>
      <ProveedorEspacio>{children}</ProveedorEspacio>
    </ProveedorIA>
  );
}

export const CATALOGO: ProveedorDisponible[] = [
  {
    nombre: "gemini",
    etiqueta: "Google Gemini",
    configurado: true,
    modelos: ["gemini-3.6-flash", "gemini-3.5-flash"],
    modeloPorDefecto: "gemini-3.6-flash",
    motivo: null,
  },
  {
    nombre: "openai",
    etiqueta: "OpenAI",
    configurado: false,
    modelos: ["gpt-4.1-mini"],
    modeloPorDefecto: "gpt-4.1-mini",
    motivo: "Falta AGENTE_IA_OPENAI_API_KEY.",
  },
];

export function proceso(sobrescribir: Partial<ProcesoResumen> = {}): ProcesoResumen {
  return {
    id: "CO1.PCC.1",
    numeroProceso: "LP-001-2026",
    entidad: "MinTIC",
    nitEntidad: "899999053",
    departamento: "Distrito Capital de Bogotá",
    ciudad: "Bogotá",
    objeto: "Desarrollo del portal ciudadano",
    modalidad: "Licitación pública",
    estado: "Presentación de oferta",
    tipoContrato: "Prestación de servicios",
    ordenEntidad: "Nacional",
    adjudicado: "No",
    valor: 1_200_000_000,
    fechaPublicacion: "2026-08-11T00:00:00.000",
    fechaUltimaPublicacion: "2026-08-12T00:00:00.000",
    url: "https://community.secop.gov.co/proceso",
    codigoUnspsc: "V1.81112204",
    duracion: "12 Mes(es)",
    scoreTi: 54,
    senalesTi: ["software", "portal"],
    ...sobrescribir,
  };
}

export function requisito(sobrescribir: Partial<RequisitoTecnico> = {}): RequisitoTecnico {
  return {
    id: "RT-01",
    categoria: "Arquitectura",
    requisito: "Arquitectura de microservicios",
    criticidad: "obligatorio",
    evidenciaEsperada: "Documento de arquitectura",
    normaRelacionada: null,
    citaPliego: null,
    ...sobrescribir,
  };
}

export function analisis(sobrescribir: Partial<RespuestaAnalisis> = {}): RespuestaAnalisis {
  return {
    resumenEjecutivo: "Proceso de desarrollo a la medida.",
    objetoNormalizado: "Portal ciudadano",
    requisitos: [requisito()],
    riesgos: [],
    criteriosEvaluacion: [],
    documentosHabilitantes: [],
    preguntasALaEntidad: [],
    alertasNormativas: [],
    recomendacion: "Participar.",
    ...sobrescribir,
  };
}

export function item(sobrescribir: Partial<ItemCumplimiento> = {}): ItemCumplimiento {
  return {
    requisitoId: "RT-01",
    requisito: "Arquitectura de microservicios",
    criticidad: "obligatorio",
    estado: "cumple",
    evidenciaEnPropuesta: null,
    brecha: null,
    accionCorrectiva: null,
    ...sobrescribir,
  };
}

/**
 * Construye una respuesta que entrega el cuerpo SSE en los trozos indicados,
 * para poder comprobar que el cliente reensambla eventos partidos por la mitad.
 */
export function respuestaSse(trozos: string[]): Response {
  const codificador = new TextEncoder();
  const flujo = new ReadableStream<Uint8Array>({
    start(controlador) {
      for (const trozo of trozos) controlador.enqueue(codificador.encode(trozo));
      controlador.close();
    },
  });
  return new Response(flujo, {
    status: 200,
    headers: { "Content-Type": "text/event-stream" },
  });
}
