"use client";

/** Validación de la propuesta contra los requisitos del pliego. */

import { useEffect, useRef, useState } from "react";
import { api, ErrorApi } from "@/lib/api";
import { useEspacio } from "@/lib/estado";
import { formatearNumero } from "@/lib/formato";
import { useIA } from "@/lib/ia";
import { contarPorEstado, ordenarPorSeveridad } from "@/lib/matriz";
import type { RespuestaValidacion } from "@/lib/tipos";
import {
  Aviso,
  Cargando,
  Etiqueta,
  EtiquetaCriticidad,
  EtiquetaCumplimiento,
  Medidor,
  Tarjeta,
  TEXTO_VEREDICTO,
  Vacio,
  VARIANTE_VEREDICTO,
} from "../comunes";

const MINIMO_CARACTERES = 40;

export function Validar() {
  const espacio = useEspacio();
  const ia = useIA();

  const [texto, setTexto] = useState("");
  const [validando, setValidando] = useState(false);
  const [subiendo, setSubiendo] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [resultado, setResultado] = useState<RespuestaValidacion | null>(null);
  const entradaArchivo = useRef<HTMLInputElement>(null);

  const markdownPropuesta = espacio.propuesta?.markdown;

  // Si ya se generó una propuesta se precarga, sin pisar lo que el usuario
  // hubiera pegado a mano.
  useEffect(() => {
    if (markdownPropuesta) setTexto((previo) => previo || markdownPropuesta);
  }, [markdownPropuesta]);

  async function subirDocumento(archivo: File) {
    setSubiendo(true);
    setError(null);
    try {
      const documento = await api.cargarDocumento(archivo);
      setTexto(documento.texto);
    } catch (excepcion) {
      setError(excepcion instanceof ErrorApi ? excepcion.message : String(excepcion));
    } finally {
      setSubiendo(false);
      if (entradaArchivo.current) entradaArchivo.current.value = "";
    }
  }

  async function validar() {
    setValidando(true);
    setError(null);
    try {
      setResultado(
        await api.validarPropuesta({
          textoPropuesta: texto,
          requisitos: espacio.requisitos,
          textoPliego: espacio.requisitos.length ? null : espacio.textoPliego || null,
          objetoContractual:
            espacio.analisis?.objetoNormalizado ??
            espacio.procesoSeleccionado?.objeto ??
            null,
          ...ia.seleccion,
        }),
      );
    } catch (excepcion) {
      setError(excepcion instanceof ErrorApi ? excepcion.message : String(excepcion));
    } finally {
      setValidando(false);
    }
  }

  const sinReferencia =
    espacio.requisitos.length === 0 &&
    espacio.textoPliego.trim().length < MINIMO_CARACTERES;
  const listo = texto.trim().length >= MINIMO_CARACTERES && !sinReferencia;

  const matrizOrdenada = resultado ? ordenarPorSeveridad(resultado.matriz) : [];
  const conteo = resultado ? contarPorEstado(resultado.matriz) : [];

  return (
    <>
      <Tarjeta
        titulo="Validar propuesta contra el pliego"
        subtitulo="Compara el texto de la propuesta con los requisitos y devuelve una matriz de cumplimiento, causales de rechazo y acciones correctivas."
      >
        {sinReferencia ? (
          <Aviso tipo="alerta">
            No hay contra qué validar. Ve a «Analizar» y carga el pliego o sus requisitos.
          </Aviso>
        ) : espacio.requisitos.length > 0 ? (
          <Aviso tipo="exito">
            Se validará contra los {espacio.requisitos.length} requisitos extraídos del
            pliego.
          </Aviso>
        ) : (
          <Aviso tipo="info">
            No hay requisitos estructurados: el agente los extraerá del pliego antes de
            validar (tarda un poco más).
          </Aviso>
        )}

        <div>
          <label htmlFor="v-texto">
            Texto de la propuesta ({formatearNumero(texto.length)} caracteres)
          </label>
          <textarea
            id="v-texto"
            value={texto}
            onChange={(e) => setTexto(e.target.value)}
            placeholder="Pega aquí la propuesta a evaluar, o súbela como PDF/DOCX…"
            style={{ minHeight: 220 }}
          />
        </div>

        {error && <Aviso tipo="error">{error}</Aviso>}

        <div className="fila" style={{ marginTop: "0.9rem" }}>
          <input
            ref={entradaArchivo}
            type="file"
            accept=".pdf,.docx,.txt,.md"
            aria-label="Archivo de la propuesta"
            style={{ display: "none" }}
            onChange={(e) => {
              const archivo = e.target.files?.[0];
              if (archivo) void subirDocumento(archivo);
            }}
          />
          <button
            className="secundario"
            onClick={() => entradaArchivo.current?.click()}
            disabled={subiendo}
          >
            {subiendo ? <Cargando texto="Leyendo…" /> : "Subir propuesta"}
          </button>
          {markdownPropuesta && (
            <button className="secundario" onClick={() => setTexto(markdownPropuesta)}>
              Usar la propuesta generada
            </button>
          )}
          <button className="principal" onClick={validar} disabled={validando || !listo}>
            {validando ? <Cargando texto="Validando…" /> : "Validar cumplimiento"}
          </button>
        </div>
      </Tarjeta>

      {resultado && (
        <>
          <Tarjeta titulo="Veredicto">
            <div className="fila" style={{ marginBottom: "0.5rem" }}>
              <Etiqueta variante={VARIANTE_VEREDICTO[resultado.veredicto] ?? "neutro"}>
                {TEXTO_VEREDICTO[resultado.veredicto] ?? resultado.veredicto}
              </Etiqueta>
              {conteo.map(({ estado, total }) => (
                <span key={estado} className="fila" style={{ gap: "0.3rem" }}>
                  <span className="tenue">{total} ×</span>
                  <EtiquetaCumplimiento valor={estado} />
                </span>
              ))}
            </div>
            <Medidor
              valor={resultado.puntajeCumplimiento}
              etiqueta="puntaje de cumplimiento"
            />
            <p>{resultado.resumen}</p>

            {resultado.causalesDeRechazo.length > 0 && (
              <Aviso tipo="error">
                <strong>Posibles causales de rechazo</strong>
                <ul className="lista-limpia">
                  {resultado.causalesDeRechazo.map((causal, i) => (
                    <li key={i}>{causal}</li>
                  ))}
                </ul>
              </Aviso>
            )}

            {resultado.mejorasPrioritarias.length > 0 && (
              <>
                <h3>Mejoras prioritarias</h3>
                <ol className="lista-limpia">
                  {resultado.mejorasPrioritarias.map((mejora, i) => (
                    <li key={i}>{mejora}</li>
                  ))}
                </ol>
              </>
            )}
          </Tarjeta>

          <Tarjeta
            titulo={`Matriz de cumplimiento (${resultado.matriz.length})`}
            subtitulo="Ordenada por severidad: primero lo que no cumple."
          >
            {resultado.matriz.length === 0 ? (
              <Vacio>La validación no devolvió ítems.</Vacio>
            ) : (
              <div className="contenedor-tabla">
                <table>
                  <thead>
                    <tr>
                      <th style={{ width: "4.5rem" }}>ID</th>
                      <th>Requisito</th>
                      <th style={{ width: "7rem" }}>Criticidad</th>
                      <th style={{ width: "8rem" }}>Estado</th>
                      <th>Brecha y acción correctiva</th>
                    </tr>
                  </thead>
                  <tbody>
                    {matrizOrdenada.map((item, indice) => (
                      <tr key={`${item.requisitoId}-${indice}`}>
                        <td className="mono">{item.requisitoId}</td>
                        <td>
                          {item.requisito}
                          {item.evidenciaEnPropuesta && (
                            <blockquote className="cita evidencia tenue">
                              «{item.evidenciaEnPropuesta}»
                            </blockquote>
                          )}
                        </td>
                        <td>
                          <EtiquetaCriticidad valor={item.criticidad} />
                        </td>
                        <td>
                          <EtiquetaCumplimiento valor={item.estado} />
                        </td>
                        <td>
                          {item.brecha && <div>{item.brecha}</div>}
                          {item.accionCorrectiva && (
                            <div className="tenue" style={{ marginTop: "0.3rem" }}>
                              <strong>Acción:</strong> {item.accionCorrectiva}
                            </div>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Tarjeta>
        </>
      )}
    </>
  );
}
