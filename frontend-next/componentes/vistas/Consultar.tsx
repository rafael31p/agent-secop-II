"use client";

/** Consulta libre al agente experto, con respuesta en streaming. */

import { useEffect, useRef, useState } from "react";
import { chatStream } from "@/lib/api";
import { mensajeDeError } from "@/lib/errores";
import { type Espacio, useEspacio } from "@/lib/estado";
import { formatearNumero } from "@/lib/formato";
import { useIA } from "@/lib/ia";
import type { MensajeChat } from "@/lib/tipos";
import { Aviso, Cargando, Tarjeta } from "../comunes";

const SUGERENCIAS = [
  "¿Qué modalidad de selección aplica para contratar una fábrica de software?",
  "¿Qué requisitos de accesibilidad exige la Resolución 1519 de 2020 a un portal público?",
  "¿La entidad puede exigir una marca específica de software en el pliego?",
  "¿Qué requisitos habilitantes son subsanables y hasta cuándo?",
  "¿Quién queda como titular del código fuente en un desarrollo a la medida?",
];

const LIMITE_CONTEXTO = 60000;

export function Consultar() {
  const espacio = useEspacio();
  const ia = useIA();

  const [mensajes, setMensajes] = useState<MensajeChat[]>([]);
  const [entrada, setEntrada] = useState("");
  const [respondiendo, setRespondiendo] = useState(false);
  const [parcial, setParcial] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [usarContexto, setUsarContexto] = useState(true);
  const finHilo = useRef<HTMLDivElement>(null);
  const abortar = useRef<AbortController | null>(null);

  useEffect(() => {
    finHilo.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [mensajes, parcial]);

  // Al salir de la vista se corta el flujo abierto; si no, seguiría consumiendo
  // cuota y escribiendo sobre un componente desmontado.
  useEffect(() => () => abortar.current?.abort(), []);

  const contexto = construirContexto(espacio);

  async function enviar(texto: string) {
    const contenido = texto.trim();
    if (!contenido || respondiendo) return;

    const historial: MensajeChat[] = [...mensajes, { rol: "user", contenido }];
    setMensajes(historial);
    setEntrada("");
    setParcial("");
    setError(null);
    setRespondiendo(true);

    const controlador = new AbortController();
    abortar.current = controlador;
    let acumulado = "";
    let completo = "";

    try {
      completo = await chatStream(
        {
          mensajes: historial,
          contexto: usarContexto ? contexto : null,
          ...ia.seleccion,
        },
        (fragmento) => {
          acumulado += fragmento;
          setParcial(acumulado);
        },
        controlador.signal,
      );
    } catch (excepcion) {
      // Cancelar es una decisión del usuario, no un error que reportar.
      if (!controlador.signal.aborted) {
        setError(mensajeDeError(excepcion));
      }
    } finally {
      // Manda lo que devolvió el flujo completo; si se cortó a la mitad, lo
      // acumulado hasta el corte, que sigue siendo útil.
      const respuesta = completo || acumulado;
      if (respuesta) {
        setMensajes([...historial, { rol: "assistant", contenido: respuesta }]);
      }
      setParcial("");
      setRespondiendo(false);
      abortar.current = null;
    }
  }

  return (
    <Tarjeta
      titulo="Consultar al agente"
      subtitulo="Preguntas abiertas sobre contratación pública de TI. Las respuestas son insumo de análisis, no un concepto jurídico."
      acciones={
        mensajes.length > 0 ? (
          <button
            className="secundario"
            onClick={() => {
              setMensajes([]);
              setError(null);
            }}
            disabled={respondiendo}
          >
            Nueva conversación
          </button>
        ) : undefined
      }
    >
      {contexto !== null && (
        <label className="casilla" style={{ marginBottom: "0.8rem" }}>
          <input
            type="checkbox"
            checked={usarContexto}
            onChange={(e) => setUsarContexto(e.target.checked)}
          />
          Incluir el material cargado como contexto
          <span className="tenue">({formatearNumero(contexto.length)} caracteres)</span>
        </label>
      )}

      {mensajes.length === 0 && !parcial && (
        <div style={{ marginBottom: "1rem" }}>
          <p className="tenue">Preguntas frecuentes:</p>
          <div className="fila">
            {SUGERENCIAS.map((sugerencia) => (
              <button
                key={sugerencia}
                className="secundario"
                style={{ fontSize: "0.82rem", fontWeight: 400 }}
                onClick={() => void enviar(sugerencia)}
              >
                {sugerencia}
              </button>
            ))}
          </div>
        </div>
      )}

      <div className="chat-hilo">
        {mensajes.map((mensaje, indice) => (
          <div key={indice} className={`chat-mensaje ${mensaje.rol}`}>
            {mensaje.contenido}
          </div>
        ))}
        {parcial && <div className="chat-mensaje assistant">{parcial}</div>}
        {respondiendo && !parcial && (
          <div className="chat-mensaje assistant">
            <Cargando texto="Pensando…" />
          </div>
        )}
        <div ref={finHilo} />
      </div>

      {error && <Aviso tipo="error">{error}</Aviso>}

      <form
        onSubmit={(e) => {
          e.preventDefault();
          void enviar(entrada);
        }}
        style={{ marginTop: "1rem" }}
      >
        <textarea
          value={entrada}
          aria-label="Consulta"
          onChange={(e) => setEntrada(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              void enviar(entrada);
            }
          }}
          placeholder="Escribe tu consulta… (Enter para enviar, Shift+Enter para salto de línea)"
          style={{ minHeight: 80, fontFamily: "inherit", fontSize: "0.92rem" }}
          disabled={respondiendo}
        />
        <div className="fila" style={{ marginTop: "0.6rem" }}>
          <button
            className="principal"
            type="submit"
            disabled={respondiendo || !entrada.trim()}
          >
            Enviar
          </button>
          {respondiendo && (
            <button
              className="secundario"
              type="button"
              onClick={() => abortar.current?.abort()}
            >
              Detener
            </button>
          )}
        </div>
      </form>
    </Tarjeta>
  );
}

/**
 * Arma el contexto con lo que haya en el espacio de trabajo. Se prefieren los
 * requisitos extraídos al pliego completo: dicen lo mismo en una fracción del
 * espacio y dejan sitio para la conversación.
 */
export function construirContexto(espacio: Espacio): string | null {
  const partes: string[] = [];

  if (espacio.procesoSeleccionado?.objeto) {
    partes.push(`Proceso seleccionado: ${espacio.procesoSeleccionado.objeto}`);
  }
  if (espacio.requisitos.length) {
    partes.push(
      "Requisitos extraídos:\n" +
        espacio.requisitos
          .map((r) => `- [${r.id}] (${r.criticidad}) ${r.requisito}`)
          .join("\n"),
    );
  } else if (espacio.textoPliego) {
    partes.push(`Texto del pliego:\n${espacio.textoPliego.slice(0, LIMITE_CONTEXTO)}`);
  }

  return partes.length ? partes.join("\n\n") : null;
}
