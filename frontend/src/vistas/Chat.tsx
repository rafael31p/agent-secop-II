/** Vista: consulta libre al agente experto, con respuesta en streaming. */

import { useEffect, useRef, useState } from "react";
import { chatStream, ErrorApi } from "../api/client";
import { Aviso, Cargando, Tarjeta } from "../componentes/comunes";
import { useEspacio } from "../estado";
import type { MensajeChat } from "../types";

const SUGERENCIAS = [
  "¿Qué modalidad de selección aplica para contratar una fábrica de software?",
  "¿Qué requisitos de accesibilidad exige la Resolución 1519 de 2020 a un portal público?",
  "¿La entidad puede exigir una marca específica de software en el pliego?",
  "¿Qué requisitos habilitantes son subsanables y hasta cuándo?",
  "¿Quién queda como titular del código fuente en un desarrollo a la medida?",
];

export default function Chat() {
  const espacio = useEspacio();
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

  useEffect(() => () => abortar.current?.abort(), []);

  const contexto = construirContexto(espacio, usarContexto);

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

    try {
      await chatStream(
        historial,
        contexto,
        (fragmento) => {
          acumulado += fragmento;
          setParcial(acumulado);
        },
        controlador.signal,
      );
      setMensajes([...historial, { rol: "assistant", contenido: acumulado }]);
    } catch (excepcion) {
      if (controlador.signal.aborted) {
        // Cancelación deliberada: se conserva lo recibido hasta el momento.
        if (acumulado) {
          setMensajes([...historial, { rol: "assistant", contenido: acumulado }]);
        }
      } else {
        setError(
          excepcion instanceof ErrorApi ? excepcion.message : String(excepcion),
        );
        if (acumulado) {
          setMensajes([...historial, { rol: "assistant", contenido: acumulado }]);
        }
      }
    } finally {
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
          <span className="tenue">
            ({contexto.length.toLocaleString("es-CO")} caracteres)
          </span>
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

function construirContexto(
  espacio: ReturnType<typeof useEspacio>,
  activo: boolean,
): string | null {
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
    partes.push(`Texto del pliego:\n${espacio.textoPliego.slice(0, 60000)}`);
  }
  if (!partes.length) return null;
  return activo ? partes.join("\n\n") : null;
}
