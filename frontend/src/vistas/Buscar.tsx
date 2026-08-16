/** Vista: búsqueda de procesos en SECOP II y priorización por relevancia TI. */

import { useState } from "react";
import { api, ErrorApi } from "../api/client";
import {
  Aviso,
  Cargando,
  Etiqueta,
  Tarjeta,
  Vacio,
  formatearCOP,
  formatearFecha,
} from "../componentes/comunes";
import { useEspacio } from "../estado";
import type {
  FiltroProcesos,
  ProcesoResumen,
  RespuestaProcesos,
  RespuestaRelevancia,
} from "../types";

const MODALIDADES = [
  "",
  "Licitación pública",
  "Selección abreviada",
  "Concurso de méritos",
  "Contratación directa",
  "Mínima cuantía",
  "Contratación régimen especial",
];

export default function Buscar({ irA }: { irA: (vista: string) => void }) {
  const espacio = useEspacio();
  const [filtro, setFiltro] = useState<FiltroProcesos>({
    texto: "",
    solo_ti: true,
    limite: 30,
  });
  const [resultado, setResultado] = useState<RespuestaProcesos | null>(null);
  const [buscando, setBuscando] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [priorizacion, setPriorizacion] = useState<RespuestaRelevancia | null>(null);
  const [priorizando, setPriorizando] = useState(false);
  const [errorIA, setErrorIA] = useState<string | null>(null);

  function actualizar<C extends keyof FiltroProcesos>(
    campo: C,
    valor: FiltroProcesos[C],
  ) {
    setFiltro((previo) => ({ ...previo, [campo]: valor }));
  }

  async function buscar(evento?: React.FormEvent) {
    evento?.preventDefault();
    setBuscando(true);
    setError(null);
    setPriorizacion(null);
    setErrorIA(null);
    try {
      const limpio: FiltroProcesos = { ...filtro };
      // No enviar cadenas vacías: el backend las trataría como filtro real.
      for (const clave of Object.keys(limpio) as (keyof FiltroProcesos)[]) {
        if (limpio[clave] === "") delete limpio[clave];
      }
      setResultado(await api.buscarProcesos(limpio));
    } catch (excepcion) {
      setError(
        excepcion instanceof ErrorApi ? excepcion.message : String(excepcion),
      );
      setResultado(null);
    } finally {
      setBuscando(false);
    }
  }

  async function priorizar() {
    if (!resultado?.procesos.length) return;
    setPriorizando(true);
    setErrorIA(null);
    try {
      setPriorizacion(
        await api.priorizarProcesos(
          resultado.procesos.slice(0, 40),
          espacio.perfilProveedor,
          15,
        ),
      );
    } catch (excepcion) {
      setErrorIA(
        excepcion instanceof ErrorApi ? excepcion.message : String(excepcion),
      );
    } finally {
      setPriorizando(false);
    }
  }

  function analizarProceso(proceso: ProcesoResumen) {
    espacio.seleccionarProceso(proceso);
    irA("analizar");
  }

  return (
    <>
      <Tarjeta
        titulo="Buscar procesos en SECOP II"
        subtitulo="Consulta el conjunto de datos abierto de procesos de contratación (datos.gov.co). El filtro de tecnología es una heurística local sobre el objeto del proceso."
      >
        <form onSubmit={buscar}>
          <div className="rejilla">
            <div>
              <label htmlFor="f-texto">Texto en el objeto</label>
              <input
                id="f-texto"
                type="text"
                placeholder="desarrollo de software, ciberseguridad…"
                value={filtro.texto ?? ""}
                onChange={(e) => actualizar("texto", e.target.value)}
              />
            </div>
            <div>
              <label htmlFor="f-entidad">Entidad</label>
              <input
                id="f-entidad"
                type="text"
                placeholder="MinTIC, DANE, Alcaldía…"
                value={filtro.entidad ?? ""}
                onChange={(e) => actualizar("entidad", e.target.value)}
              />
            </div>
            <div>
              <label htmlFor="f-depto">Departamento</label>
              <input
                id="f-depto"
                type="text"
                placeholder="Antioquia, Distrito Capital de Bogotá…"
                value={filtro.departamento ?? ""}
                onChange={(e) => actualizar("departamento", e.target.value)}
              />
            </div>
            <div>
              <label htmlFor="f-modalidad">Modalidad</label>
              <select
                id="f-modalidad"
                value={filtro.modalidad ?? ""}
                onChange={(e) => actualizar("modalidad", e.target.value)}
              >
                {MODALIDADES.map((m) => (
                  <option key={m} value={m}>
                    {m || "Todas"}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="f-min">Valor mínimo (COP)</label>
              <input
                id="f-min"
                type="number"
                min={0}
                step={1000000}
                value={filtro.valor_min ?? ""}
                onChange={(e) =>
                  actualizar("valor_min", e.target.value ? Number(e.target.value) : null)
                }
              />
            </div>
            <div>
              <label htmlFor="f-max">Valor máximo (COP)</label>
              <input
                id="f-max"
                type="number"
                min={0}
                step={1000000}
                value={filtro.valor_max ?? ""}
                onChange={(e) =>
                  actualizar("valor_max", e.target.value ? Number(e.target.value) : null)
                }
              />
            </div>
            <div>
              <label htmlFor="f-desde">Publicado desde</label>
              <input
                id="f-desde"
                type="date"
                value={filtro.fecha_desde ?? ""}
                onChange={(e) => actualizar("fecha_desde", e.target.value || null)}
              />
            </div>
            <div>
              <label htmlFor="f-hasta">Publicado hasta</label>
              <input
                id="f-hasta"
                type="date"
                value={filtro.fecha_hasta ?? ""}
                onChange={(e) => actualizar("fecha_hasta", e.target.value || null)}
              />
            </div>
            <div>
              <label htmlFor="f-limite">Máximo de resultados</label>
              <input
                id="f-limite"
                type="number"
                min={1}
                max={500}
                value={filtro.limite ?? 30}
                onChange={(e) => actualizar("limite", Number(e.target.value))}
              />
            </div>
          </div>

          <div className="fila" style={{ marginTop: "1rem" }}>
            <label className="casilla">
              <input
                type="checkbox"
                checked={filtro.solo_ti ?? false}
                onChange={(e) => actualizar("solo_ti", e.target.checked)}
              />
              Solo procesos de tecnología
            </label>
            <button className="principal" type="submit" disabled={buscando}>
              {buscando ? <Cargando texto="Buscando…" /> : "Buscar"}
            </button>
          </div>
        </form>
      </Tarjeta>

      {error && <Aviso tipo="error">{error}</Aviso>}

      {resultado && (
        <Tarjeta
          titulo={`Resultados (${resultado.total})`}
          subtitulo={`Conjunto de datos: ${resultado.dataset}`}
          acciones={
            <button
              className="secundario"
              onClick={priorizar}
              disabled={priorizando || !resultado.procesos.length}
            >
              {priorizando ? (
                <Cargando texto="Analizando…" />
              ) : (
                "Priorizar con IA"
              )}
            </button>
          }
        >
          {resultado.advertencias.map((advertencia, indice) => (
            <Aviso key={indice} tipo="alerta">
              {advertencia}
            </Aviso>
          ))}

          {errorIA && <Aviso tipo="error">{errorIA}</Aviso>}

          {priorizacion && (
            <div style={{ marginBottom: "1.2rem" }}>
              <Aviso tipo="info">{priorizacion.resumen}</Aviso>
              <h3>Priorización del agente</h3>
              <div className="contenedor-tabla">
                <table>
                  <thead>
                    <tr>
                      <th style={{ width: "4rem" }}>Puntaje</th>
                      <th>Categoría</th>
                      <th>Objeto</th>
                      <th>Justificación</th>
                      <th>Banderas</th>
                    </tr>
                  </thead>
                  <tbody>
                    {priorizacion.priorizados.map((item, indice) => (
                      <tr key={`${item.id}-${indice}`}>
                        <td>
                          <strong>{item.puntaje}</strong>
                        </td>
                        <td>
                          <Etiqueta
                            variante={
                              item.categoria_ti === "No es TI" ? "neutro" : "acento"
                            }
                          >
                            {item.categoria_ti}
                          </Etiqueta>
                        </td>
                        <td>{(item.objeto ?? "").slice(0, 140)}</td>
                        <td>
                          {item.justificacion}
                          {item.encaje_proveedor && (
                            <div className="tenue" style={{ marginTop: "0.3rem" }}>
                              Encaje: {item.encaje_proveedor}
                            </div>
                          )}
                        </td>
                        <td>
                          {item.banderas.map((bandera, i) => (
                            <div key={i}>
                              <Etiqueta variante="alerta">{bandera}</Etiqueta>
                            </div>
                          ))}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {resultado.procesos.length === 0 ? (
            <Vacio>
              Sin resultados. Prueba con menos filtros o desactiva «Solo procesos de
              tecnología».
            </Vacio>
          ) : (
            resultado.procesos.map((proceso, indice) => (
              <FilaProceso
                key={`${proceso.id ?? indice}`}
                proceso={proceso}
                seleccionado={espacio.procesoSeleccionado?.id === proceso.id}
                onAnalizar={() => analizarProceso(proceso)}
              />
            ))
          )}
        </Tarjeta>
      )}
    </>
  );
}

function FilaProceso({
  proceso,
  seleccionado,
  onAnalizar,
}: {
  proceso: ProcesoResumen;
  seleccionado: boolean;
  onAnalizar: () => void;
}) {
  const puntaje = proceso.score_ti ?? 0;
  return (
    <article className={`proceso${seleccionado ? " seleccionado" : ""}`}>
      <div className="proceso-meta">
        <Etiqueta variante={puntaje >= 20 ? "acento" : "neutro"} titulo="Heurística local">
          TI {puntaje}
        </Etiqueta>
        {proceso.modalidad && <Etiqueta>{proceso.modalidad}</Etiqueta>}
        {proceso.estado && <Etiqueta>{proceso.estado}</Etiqueta>}
        {proceso.numero_proceso && (
          <span className="mono">{proceso.numero_proceso}</span>
        )}
      </div>

      <p className="proceso-objeto">{proceso.objeto ?? "(sin objeto registrado)"}</p>

      <div className="proceso-meta">
        <span>{proceso.entidad ?? "Entidad no registrada"}</span>
        <span>·</span>
        <span>{proceso.departamento ?? "s/d"}</span>
        <span>·</span>
        <span>{formatearCOP(proceso.valor)}</span>
        <span>·</span>
        <span>Publicado {formatearFecha(proceso.fecha_publicacion)}</span>
        {proceso.duracion && (
          <>
            <span>·</span>
            <span>Duración {proceso.duracion}</span>
          </>
        )}
      </div>

      <div className="fila" style={{ marginTop: "0.7rem" }}>
        <button className="secundario" onClick={onAnalizar}>
          Analizar este proceso
        </button>
        {proceso.url && (
          <a
            className="secundario"
            style={{
              textDecoration: "none",
              padding: "0.55rem 1.1rem",
              borderRadius: 8,
              border: "1px solid var(--borde)",
              fontSize: "0.92rem",
              fontWeight: 600,
              color: "var(--texto)",
            }}
            href={proceso.url}
            target="_blank"
            rel="noreferrer noopener"
          >
            Ver en SECOP ↗
          </a>
        )}
        {proceso.señales_ti.length > 0 && (
          <span className="tenue">
            Señales: {proceso.señales_ti.slice(0, 6).join(", ")}
          </span>
        )}
      </div>
    </article>
  );
}
