"use client";

/**
 * Elección de proveedor y modelo de IA, compartida por todas las vistas.
 *
 * El backend acepta `proveedor` y `modelo` opcionales en cada petición: si van
 * nulos usa su configuración por defecto. Aquí «nulo» es un valor legítimo y
 * distinto de «gemini»: significa «lo que el servidor tenga configurado», que es
 * lo correcto cuando el usuario no ha elegido nada.
 */

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { api, ErrorApi } from "./api";
import type { ProveedorDisponible, SeleccionIA } from "./tipos";

export const CLAVE_IA = "agente-secop:ia";

export interface EstadoIA {
  catalogo: ProveedorDisponible[];
  cargando: boolean;
  error: string | null;
  /** Nulo = usar el proveedor por defecto del servidor. */
  proveedor: string | null;
  /** Nulo = usar el modelo por defecto del proveedor. */
  modelo: string | null;
  /** Lo que se adjunta a cada petición que invoca al modelo. */
  seleccion: SeleccionIA;
  proveedorElegido: ProveedorDisponible | null;
  hayProveedorConfigurado: boolean;
  fijarProveedor: (nombre: string | null) => void;
  fijarModelo: (modelo: string | null) => void;
  recargar: () => void;
}

const Contexto = createContext<EstadoIA | null>(null);

export function ProveedorIA({ children }: { children: ReactNode }) {
  const [catalogo, setCatalogo] = useState<ProveedorDisponible[]>([]);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [proveedor, setProveedor] = useState<string | null>(null);
  const [modelo, setModelo] = useState<string | null>(null);

  const cargar = useCallback(async () => {
    setCargando(true);
    setError(null);
    try {
      const disponibles = await api.proveedores();
      setCatalogo(disponibles);

      // La preferencia guardada solo se aplica si sigue siendo válida: una clave
      // retirada del servidor no debe condenar cada petición a un 503.
      const guardada = leerPreferencia();
      const elegido = disponibles.find((p) => p.nombre === guardada?.proveedor);
      if (elegido?.configurado) {
        setProveedor(elegido.nombre);
        setModelo(guardada?.modelo ?? null);
      } else if (guardada) {
        borrarPreferencia();
      }
    } catch (excepcion) {
      setError(excepcion instanceof ErrorApi ? excepcion.message : String(excepcion));
    } finally {
      setCargando(false);
    }
  }, []);

  useEffect(() => {
    void cargar();
  }, [cargar]);

  const fijarProveedor = useCallback((nombre: string | null) => {
    setProveedor(nombre);
    // Un modelo de otro proveedor no tiene sentido: se vuelve al suyo por defecto.
    setModelo(null);
    guardarPreferencia(nombre, null);
  }, []);

  const fijarModelo = useCallback(
    (nuevo: string | null) => {
      setModelo(nuevo);
      guardarPreferencia(proveedor, nuevo);
    },
    [proveedor],
  );

  const valor = useMemo<EstadoIA>(
    () => ({
      catalogo,
      cargando,
      error,
      proveedor,
      modelo,
      seleccion: { proveedor, modelo },
      proveedorElegido: catalogo.find((p) => p.nombre === proveedor) ?? null,
      hayProveedorConfigurado: catalogo.some((p) => p.configurado),
      fijarProveedor,
      fijarModelo,
      recargar: () => void cargar(),
    }),
    [catalogo, cargando, error, proveedor, modelo, fijarProveedor, fijarModelo, cargar],
  );

  return <Contexto.Provider value={valor}>{children}</Contexto.Provider>;
}

export function useIA(): EstadoIA {
  const contexto = useContext(Contexto);
  if (!contexto) {
    throw new Error("useIA debe usarse dentro de <ProveedorIA>");
  }
  return contexto;
}

function leerPreferencia(): SeleccionIA | null {
  try {
    const crudo = localStorage.getItem(CLAVE_IA);
    return crudo ? (JSON.parse(crudo) as SeleccionIA) : null;
  } catch {
    return null;
  }
}

function guardarPreferencia(proveedor: string | null, modelo: string | null) {
  try {
    if (!proveedor) localStorage.removeItem(CLAVE_IA);
    else localStorage.setItem(CLAVE_IA, JSON.stringify({ proveedor, modelo }));
  } catch {
    // Modo privado: la elección vale para esta sesión y no se recuerda.
  }
}

function borrarPreferencia() {
  try {
    localStorage.removeItem(CLAVE_IA);
  } catch {
    // Nada que hacer: no poder borrar no impide seguir.
  }
}
