/** Vista: validación de la propuesta contra los requisitos del pliego. */

import { useEffect, useRef, useState } from "react";
import { api, ErrorApi } from "../api/client";
import {
  Aviso,
  Cargando,
  Etiqueta,
  EtiquetaCriticidad,
  EtiquetaCumplimiento,
  Medidor,
  TEXTO_VEREDICTO,
  Tarjeta,
  VARIANTE_VEREDICTO,
  Vacio,
} from "../componentes/comunes";
import { useEspacio } from "../estado";
import type { EstadoCumplimiento, RespuestaValidacion } from "../types";

const ORDEN_ESTADOS: EstadoCumplimiento[] = [
  "no_cumple",
  "cumple_parcial",
  "no_evaluable",
  "cumple",
];

export default function Validar() {
  const espacio = useEspacio();
  const [texto, setTexto] = useState("");
  const [validando, setValidando] = useState(false);
  const [subiendo, setSubiendo] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [resultado, setResultado] = useState<RespuestaValidacion | null>(null);
  const entradaArchivo = useRef<HTMLInputElement>(null);

  // Si ya se generó una propuesta, se precarga para validarla.
  useEffect(() => {
    if (espacio.propuesta && !texto) {
      setTexto(espacio.propuesta.markdown);
    }
    // Solo al montar o cuando aparece una propuesta nueva.
  }, [espacio.propuesta]); // eslint-disable-line react-hooks/exhaustive-deps

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
          texto_propuesta: texto,
          requisitos: espacio.requisitos,
          texto_pliego: espacio.requisitos.length ? null : espacio.textoPliego || null,
          objeto_contractual:
            espacio.analisis?.objeto_normalizado ??
            espacio.procesoSeleccionado?.objeto ??
            null,
        }),
      );
    } catch (excepcion) {
      setError(excepcion instanceof ErrorApi ? excepcion.message : String(excepcion));
    } finally {
      setValidando(false);
    }
  }

  const sinReferencia =
    espacio.requisitos.length === 0 && espacio.textoPliego.trim().length < 40;
  const listo = texto.trim().length >= 40 && !sinReferencia;

  const matrizOrdenada = resultado
    ? [...resultado.matriz].sort(
        (a, b) => ORDEN_ESTADOS.indexOf(a.estado) - ORDEN_ESTADOS.indexOf(b.estado),
      )
    : [];

  const conteo = resultado
    ? ORDEN_ESTADOS.map((estado) => ({
        estado,
        total: resultado.matriz.filter((item) => item.estado === estado).length,
      })).filter((c) => c.total > 0)
    : [];

  return (
    <>
      <Tarjeta
        titulo="Validar propuesta contra el pliego"
        subtitulo="Compara el texto de la propuesta con los requisitos y devuelve una matriz de cumplimiento, causales de rechazo y acciones correctivas."
      >
        {sinReferencia ? (
          <Aviso tipo="alerta">
            No hay contra qué validar. Ve a «Analizar» y carga el pliego o sus
            requisitos.
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
            Texto de la propuesta ({texto.length.toLocaleString("es-CO")} caracteres)
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
          {espacio.propuesta && (
            <button
              className="secundario"
              onClick={() => setTexto(espacio.propuesta!.markdown)}
            >
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
                <Etiqueta key={estado}>
                  {total} × <EtiquetaCumplimiento valor={estado} />
                </Etiqueta>
              ))}
            </div>
            <Medidor
              valor={resultado.puntaje_cumplimiento}
              etiqueta="puntaje de cumplimiento"
            />
            <p>{resultado.resumen}</p>

            {resultado.causales_de_rechazo.length > 0 && (
              <Aviso tipo="error">
                <strong>Posibles causales de rechazo</strong>
                <ul className="lista-limpia">
                  {resultado.causales_de_rechazo.map((causal, i) => (
                    <li key={i}>{causal}</li>
                  ))}
                </ul>
              </Aviso>
            )}

            {resultado.mejoras_prioritarias.length > 0 && (
              <>
                <h3>Mejoras prioritarias</h3>
                <ol className="lista-limpia">
                  {resultado.mejoras_prioritarias.map((mejora, i) => (
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
                      <tr key={`${item.requisito_id}-${indice}`}>
                        <td className="mono">{item.requisito_id}</td>
                        <td>
                          {item.requisito}
                          {item.evidencia_en_propuesta && (
                            <blockquote
                              className="tenue"
                              style={{
                                margin: "0.4rem 0 0",
                                paddingLeft: "0.7rem",
                                borderLeft: "3px solid var(--exito)",
                                fontStyle: "italic",
                              }}
                            >
                              «{item.evidencia_en_propuesta}»
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
                          {item.accion_correctiva && (
                            <div
                              className="tenue"
                              style={{ marginTop: "0.3rem" }}
                            >
                              <strong>Acción:</strong> {item.accion_correctiva}
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
