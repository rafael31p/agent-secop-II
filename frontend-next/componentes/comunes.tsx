/** Componentes de presentación reutilizables. */

import type { ReactNode } from "react";
import type { Criticidad, EstadoCumplimiento, NivelRiesgo, Veredicto } from "@/lib/tipos";

export function Tarjeta({
  titulo,
  subtitulo,
  acciones,
  children,
}: {
  titulo?: string;
  subtitulo?: string;
  acciones?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="tarjeta">
      {(titulo || acciones) && (
        <div className="fila" style={{ justifyContent: "space-between" }}>
          {titulo && <h2>{titulo}</h2>}
          {acciones}
        </div>
      )}
      {subtitulo && <p className="subtitulo">{subtitulo}</p>}
      {children}
    </section>
  );
}

export function Aviso({
  tipo = "info",
  children,
}: {
  tipo?: "info" | "error" | "alerta" | "exito";
  children: ReactNode;
}) {
  return (
    <div className={`aviso ${tipo}`} role={tipo === "error" ? "alert" : "status"}>
      {children}
    </div>
  );
}

export function Etiqueta({
  children,
  variante = "neutro",
  titulo,
}: {
  children: ReactNode;
  variante?: "neutro" | "acento" | "exito" | "alerta" | "error";
  titulo?: string;
}) {
  const clase = variante === "neutro" ? "etiqueta" : `etiqueta ${variante}`;
  return (
    <span className={clase} title={titulo}>
      {children}
    </span>
  );
}

export function Cargando({ texto }: { texto: string }) {
  return (
    <span>
      <span className="cargando" aria-hidden="true" />
      {texto}
    </span>
  );
}

export function Vacio({ children }: { children: ReactNode }) {
  return <div className="vacio">{children}</div>;
}

/** Barra de puntaje 0-100 con color según umbral. */
export function Medidor({ valor, etiqueta }: { valor: number; etiqueta?: string }) {
  const color =
    valor >= 80 ? "var(--exito)" : valor >= 55 ? "var(--alerta)" : "var(--error)";
  return (
    <div className="medidor">
      <span className="medidor-valor" style={{ color }}>
        {valor}
      </span>
      <div
        className="medidor-barra"
        role="progressbar"
        aria-valuenow={valor}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={etiqueta ?? "Puntaje"}
      >
        <div
          className="medidor-relleno"
          style={{ width: `${Math.max(0, Math.min(100, valor))}%`, background: color }}
        />
      </div>
      {etiqueta && <span className="tenue">{etiqueta}</span>}
    </div>
  );
}

const VARIANTE_CRITICIDAD: Record<Criticidad, "error" | "alerta" | "acento" | "neutro"> =
  {
    obligatorio: "error",
    ponderable: "alerta",
    deseable: "acento",
    informativo: "neutro",
  };

export function EtiquetaCriticidad({ valor }: { valor: Criticidad }) {
  return <Etiqueta variante={VARIANTE_CRITICIDAD[valor] ?? "neutro"}>{valor}</Etiqueta>;
}

export const TEXTO_CUMPLIMIENTO: Record<EstadoCumplimiento, string> = {
  cumple: "Cumple",
  cumple_parcial: "Cumple parcial",
  no_cumple: "No cumple",
  no_evaluable: "No evaluable",
};

const VARIANTE_CUMPLIMIENTO: Record<
  EstadoCumplimiento,
  "exito" | "alerta" | "error" | "neutro"
> = {
  cumple: "exito",
  cumple_parcial: "alerta",
  no_cumple: "error",
  no_evaluable: "neutro",
};

export function EtiquetaCumplimiento({ valor }: { valor: EstadoCumplimiento }) {
  return (
    <Etiqueta variante={VARIANTE_CUMPLIMIENTO[valor] ?? "neutro"}>
      {TEXTO_CUMPLIMIENTO[valor] ?? valor}
    </Etiqueta>
  );
}

const VARIANTE_RIESGO: Record<NivelRiesgo, "error" | "alerta" | "exito"> = {
  alto: "error",
  medio: "alerta",
  bajo: "exito",
};

export function EtiquetaRiesgo({ valor }: { valor: NivelRiesgo }) {
  return (
    <Etiqueta variante={VARIANTE_RIESGO[valor] ?? "neutro"}>riesgo {valor}</Etiqueta>
  );
}

export const TEXTO_VEREDICTO: Record<Veredicto, string> = {
  apta: "Apta",
  apta_con_ajustes: "Apta con ajustes",
  riesgo_de_rechazo: "Riesgo de rechazo",
  no_apta: "No apta",
};

export const VARIANTE_VEREDICTO: Record<Veredicto, "exito" | "alerta" | "error"> = {
  apta: "exito",
  apta_con_ajustes: "alerta",
  riesgo_de_rechazo: "alerta",
  no_apta: "error",
};
