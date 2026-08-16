"use client";

/** Búsqueda de procesos en SECOP II y priorización por relevancia TI. */

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { api } from "@/lib/api";
import { mensajeDeError } from "@/lib/errores";
import { useEspacio } from "@/lib/estado";
import { limpiarFiltro } from "@/lib/filtros";
import { formatearCOP, formatearFecha } from "@/lib/formato";
import { useIA } from "@/lib/ia";
import type {
  FiltroProcesos,
  ProcesoResumen,
  RespuestaProcesos,
  RespuestaRelevancia,
} from "@/lib/tipos";
import { Aviso, Cargando, Etiqueta, Tarjeta, Vacio } from "../comunes";

const MODALIDADES = [
  "",
  "Licitación pública",
  "Selección abreviada",
  "Concurso de méritos",
  "Contratación directa",
  "Mínima cuantía",
  "Contratación régimen especial",
];

export function Buscar() {
  const espacio = useEspacio();
  const ia = useIA();
  const router = useRouter();

  const [filtro, setFiltro] = useState<FiltroProcesos>({
    texto: "",
    soloTi: true,
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

  async function buscar(evento?: FormEvent) {
    evento?.preventDefault();
    setBuscando(true);
    setError(null);
    setPriorizacion(null);
    setErrorIA(null);
    try {
      setResultado(await api.buscarProcesos(limpiarFiltro(filtro)));
    } catch (excepcion) {
      setError(mensajeDeError(excepcion));
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
        await api.priorizarProcesos({
          procesos: resultado.procesos.slice(0, 40),
          perfilProveedor: espacio.perfilProveedor || null,
          maximo: 15,
          ...ia.seleccion,
        }),
      );
    } catch (excepcion) {
      setErrorIA(mensajeDeError(excepcion));
    } finally {
      setPriorizando(false);
    }
  }

  function analizarProceso(proceso: ProcesoResumen) {
    espacio.seleccionarProceso(proceso);
    router.push("/analizar");
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
                {MODALIDADES.map((modalidad) => (
                  <option key={modalidad} value={modalidad}>
                    {modalidad || "Todas"}
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
                value={filtro.valorMin ?? ""}
                onChange={(e) =>
                  actualizar("valorMin", e.target.value ? Number(e.target.value) : null)
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
                value={filtro.valorMax ?? ""}
                onChange={(e) =>
                  actualizar("valorMax", e.target.value ? Number(e.target.value) : null)
                }
              />
            </div>
            <div>
              <label htmlFor="f-desde">Publicado desde</label>
              <input
                id="f-desde"
                type="date"
                value={filtro.fechaDesde ?? ""}
                onChange={(e) => actualizar("fechaDesde", e.target.value || null)}
              />
            </div>
            <div>
              <label htmlFor="f-hasta">Publicado hasta</label>
              <input
                id="f-hasta"
                type="date"
                value={filtro.fechaHasta ?? ""}
                onChange={(e) => actualizar("fechaHasta", e.target.value || null)}
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
                checked={filtro.soloTi ?? false}
                onChange={(e) => actualizar("soloTi", e.target.checked)}
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
              {priorizando ? <Cargando texto="Analizando…" /> : "Priorizar con IA"}
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
                              item.categoriaTi === "No es TI" ? "neutro" : "acento"
                            }
                          >
                            {item.categoriaTi}
                          </Etiqueta>
                        </td>
                        <td>{(item.objeto ?? "").slice(0, 140)}</td>
                        <td>
                          {item.justificacion}
                          {item.encajeProveedor && (
                            <div className="tenue" style={{ marginTop: "0.3rem" }}>
                              Encaje: {item.encajeProveedor}
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
                key={proceso.id ?? indice}
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
  const puntaje = proceso.scoreTi ?? 0;
  return (
    <article className={`proceso${seleccionado ? " seleccionado" : ""}`}>
      <div className="proceso-meta">
        <Etiqueta
          variante={puntaje >= 20 ? "acento" : "neutro"}
          titulo="Heurística local"
        >
          TI {puntaje}
        </Etiqueta>
        {proceso.modalidad && <Etiqueta>{proceso.modalidad}</Etiqueta>}
        {proceso.estado && <Etiqueta>{proceso.estado}</Etiqueta>}
        {proceso.numeroProceso && <span className="mono">{proceso.numeroProceso}</span>}
      </div>

      <p className="proceso-objeto">{proceso.objeto ?? "(sin objeto registrado)"}</p>

      <div className="proceso-meta">
        <span>{proceso.entidad ?? "Entidad no registrada"}</span>
        <span>·</span>
        <span>{proceso.departamento ?? "s/d"}</span>
        <span>·</span>
        <span>{formatearCOP(proceso.valor)}</span>
        <span>·</span>
        <span>Publicado {formatearFecha(proceso.fechaPublicacion)}</span>
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
            className="boton"
            href={proceso.url}
            target="_blank"
            rel="noreferrer noopener"
          >
            Ver en SECOP ↗
          </a>
        )}
        {proceso.senalesTi.length > 0 && (
          <span className="tenue">
            Señales: {proceso.senalesTi.slice(0, 6).join(", ")}
          </span>
        )}
      </div>
    </article>
  );
}
