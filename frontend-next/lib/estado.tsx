"use client";

/**
 * Espacio de trabajo compartido entre vistas: el proceso elegido, el pliego que
 * se está analizando, los requisitos extraídos y la propuesta generada. Permite
 * encadenar Buscar → Analizar → Proponer → Validar sin volver a pegar el texto.
 *
 * Vive en el layout raíz, así que sobrevive a la navegación entre rutas. Lo que
 * no sobrevive por sí solo es recargar la página, y con rutas propias eso pasa
 * de ser improbable a ser normal (marcadores, F5, enlace compartido). Por eso se
 * respalda en `sessionStorage`: dura lo que la pestaña, que es justo lo que dura
 * el trabajo sobre un pliego. El perfil del oferente sí va a `localStorage`,
 * porque es del oferente y no del pliego.
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
import type {
  ProcesoResumen,
  RequisitoTecnico,
  RespuestaAnalisis,
  RespuestaPropuesta,
} from "./tipos";

export const CLAVE_PERFIL = "agente-secop:perfil-proveedor";
export const CLAVE_ESPACIO = "agente-secop:espacio";

interface Instantanea {
  procesoSeleccionado: ProcesoResumen | null;
  textoPliego: string;
  origenPliego: string | null;
  analisis: RespuestaAnalisis | null;
  propuesta: RespuestaPropuesta | null;
}

const VACIO: Instantanea = {
  procesoSeleccionado: null,
  textoPliego: "",
  origenPliego: null,
  analisis: null,
  propuesta: null,
};

export interface Espacio extends Instantanea {
  perfilProveedor: string;
  requisitos: RequisitoTecnico[];

  seleccionarProceso: (proceso: ProcesoResumen | null) => void;
  fijarTextoPliego: (texto: string, origen?: string | null) => void;
  fijarAnalisis: (analisis: RespuestaAnalisis | null) => void;
  fijarPropuesta: (propuesta: RespuestaPropuesta | null) => void;
  fijarPerfilProveedor: (perfil: string) => void;
  limpiar: () => void;
}

const Contexto = createContext<Espacio | null>(null);

export function ProveedorEspacio({ children }: { children: ReactNode }) {
  const [datos, setDatos] = useState<Instantanea>(VACIO);
  const [perfilProveedor, setPerfil] = useState("");

  /**
   * Si ya se intentó restaurar. Hasta entonces no se guarda nada.
   *
   * <p>Es estado y no una referencia, y la diferencia no es de estilo: era un
   * `useRef` puesto a `true` dentro del efecto de restauración, y eso **borraba
   * el espacio de trabajo en cada carga de página**. Los dos efectos corren en
   * el mismo commit y en orden de declaración: el de restauración leía lo
   * guardado y programaba el estado, pero el de guardado se ejecutaba a
   * continuación viendo todavía `datos` vacío —el nuevo valor no llega hasta el
   * render siguiente— y escribía ese vacío encima. En desarrollo, StrictMode
   * repite los efectos, así que la segunda restauración ya leía lo que la
   * primera había machacado y la pérdida quedaba consolidada.
   *
   * <p>Con estado, el guardado se salta el primer commit y solo corre cuando el
   * render ya lleva los datos restaurados.
   */
  const [restaurado, setRestaurado] = useState(false);

  useEffect(() => {
    setPerfil(leer(localStorage, CLAVE_PERFIL) ?? "");
    const guardado = leer(sessionStorage, CLAVE_ESPACIO);
    if (guardado) {
      try {
        setDatos({ ...VACIO, ...(JSON.parse(guardado) as Partial<Instantanea>) });
      } catch {
        // Formato viejo o corrupto: se empieza limpio en vez de romper la app.
      }
    }
    setRestaurado(true);
  }, []);

  useEffect(() => {
    if (!restaurado) return;
    try {
      sessionStorage.setItem(CLAVE_ESPACIO, JSON.stringify(datos));
    } catch {
      // Cuota llena (un pliego largo) o modo privado: el espacio sigue en
      // memoria y solo se pierde la recuperación tras recargar.
    }
  }, [datos, restaurado]);

  const fijarPerfilProveedor = useCallback((perfil: string) => {
    setPerfil(perfil);
    try {
      localStorage.setItem(CLAVE_PERFIL, perfil);
    } catch {
      // Igual que arriba: el perfil vive solo en memoria.
    }
  }, []);

  const valor = useMemo<Espacio>(
    () => ({
      ...datos,
      perfilProveedor,
      requisitos: datos.analisis?.requisitos ?? [],

      seleccionarProceso: (proceso) =>
        setDatos((previo) => ({ ...previo, procesoSeleccionado: proceso })),
      fijarTextoPliego: (texto, origen = null) =>
        setDatos((previo) => ({ ...previo, textoPliego: texto, origenPliego: origen })),
      fijarAnalisis: (analisis) => setDatos((previo) => ({ ...previo, analisis })),
      fijarPropuesta: (propuesta) => setDatos((previo) => ({ ...previo, propuesta })),
      fijarPerfilProveedor,
      limpiar: () => setDatos(VACIO),
    }),
    [datos, perfilProveedor, fijarPerfilProveedor],
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

/** Lectura tolerante: un almacenamiento bloqueado no debe tumbar la aplicación. */
function leer(almacen: Storage, clave: string): string | null {
  try {
    return almacen.getItem(clave);
  } catch {
    return null;
  }
}
