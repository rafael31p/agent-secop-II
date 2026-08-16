"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { useEspacio } from "@/lib/estado";
import type { EstadoSalud } from "@/lib/tipos";
import { Aviso } from "./comunes";

/**
 * Pie con el estado del backend. Consulta `/api/salud` una vez y, si no
 * responde, lo dice arriba del todo: es el fallo más común al arrancar y sin
 * este aviso el usuario solo ve formularios que no hacen nada.
 */
export function Pie() {
  const [salud, setSalud] = useState<EstadoSalud | null>(null);
  const [error, setError] = useState<string | null>(null);
  const espacio = useEspacio();

  useEffect(() => {
    let vigente = true;
    api
      .salud()
      .then((estado) => vigente && setSalud(estado))
      .catch((excepcion) => {
        if (vigente) setError(String(excepcion?.message ?? excepcion));
      });
    return () => {
      vigente = false;
    };
  }, []);

  return (
    <footer className="pie">
      {error && (
        <Aviso tipo="error">
          {error} Arráncalo con{" "}
          <span className="mono">
            cd backend-quarkus &amp;&amp; ./mvnw quarkus:dev
          </span>
          .
        </Aviso>
      )}
      <p>
        Herramienta de apoyo analítico. No sustituye asesoría jurídica ni el estudio
        de los documentos oficiales publicados en SECOP II. Verifica toda conclusión
        contra la fuente oficial antes de tomar decisiones contractuales.
      </p>
      {salud && (
        <p className="tenue">
          Backend v{salud.version} · predeterminado {salud.proveedorIaPorDefecto} /{" "}
          {salud.modeloPorDefecto} · conjunto de datos {salud.secopDatasetProcesos}
          {espacio.origenPliego && ` · pliego cargado: ${espacio.origenPliego}`}
        </p>
      )}
    </footer>
  );
}
