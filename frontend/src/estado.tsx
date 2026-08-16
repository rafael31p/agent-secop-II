/**
 * Espacio de trabajo compartido entre vistas: el pliego que se está analizando,
 * los requisitos extraídos y la propuesta generada. Permite encadenar el flujo
 * Buscar → Analizar → Proponer → Validar sin volver a pegar el texto.
 */

import {
  createContext,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import type {
  ProcesoResumen,
  RequisitoTecnico,
  RespuestaAnalisis,
  RespuestaPropuesta,
} from "./types";

interface Espacio {
  procesoSeleccionado: ProcesoResumen | null;
  textoPliego: string;
  origenPliego: string | null;
  analisis: RespuestaAnalisis | null;
  propuesta: RespuestaPropuesta | null;
  perfilProveedor: string;

  seleccionarProceso: (proceso: ProcesoResumen | null) => void;
  fijarTextoPliego: (texto: string, origen?: string | null) => void;
  fijarAnalisis: (analisis: RespuestaAnalisis | null) => void;
  fijarPropuesta: (propuesta: RespuestaPropuesta | null) => void;
  fijarPerfilProveedor: (perfil: string) => void;
  requisitos: RequisitoTecnico[];
  limpiar: () => void;
}

const Contexto = createContext<Espacio | null>(null);

const CLAVE_PERFIL = "agente-secop:perfil-proveedor";

export function ProveedorEspacio({ children }: { children: ReactNode }) {
  const [procesoSeleccionado, setProceso] = useState<ProcesoResumen | null>(null);
  const [textoPliego, setTexto] = useState("");
  const [origenPliego, setOrigen] = useState<string | null>(null);
  const [analisis, setAnalisis] = useState<RespuestaAnalisis | null>(null);
  const [propuesta, setPropuesta] = useState<RespuestaPropuesta | null>(null);
  const [perfilProveedor, setPerfil] = useState<string>(
    () => localStorage.getItem(CLAVE_PERFIL) ?? "",
  );

  const valor = useMemo<Espacio>(
    () => ({
      procesoSeleccionado,
      textoPliego,
      origenPliego,
      analisis,
      propuesta,
      perfilProveedor,
      requisitos: analisis?.requisitos ?? [],

      seleccionarProceso: setProceso,
      fijarTextoPliego: (texto, origen = null) => {
        setTexto(texto);
        setOrigen(origen);
      },
      fijarAnalisis: setAnalisis,
      fijarPropuesta: setPropuesta,
      fijarPerfilProveedor: (perfil) => {
        setPerfil(perfil);
        try {
          localStorage.setItem(CLAVE_PERFIL, perfil);
        } catch {
          // Modo privado o almacenamiento lleno: el perfil solo vive en memoria.
        }
      },
      limpiar: () => {
        setProceso(null);
        setTexto("");
        setOrigen(null);
        setAnalisis(null);
        setPropuesta(null);
      },
    }),
    [procesoSeleccionado, textoPliego, origenPliego, analisis, propuesta, perfilProveedor],
  );

  return <Contexto.Provider value={valor}>{children}</Contexto.Provider>;
}

export function useEspacio(): Espacio {
  const contexto = useContext(Contexto);
  if (!contexto) {
    throw new Error("useEspacio debe usarse dentro de <ProveedorEspacio>");
  }
  return contexto;
}
