"use client";

/** Carga del pliego y extracción de requisitos técnicos con IA. */

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { api, ErrorApi } from "@/lib/api";
import { useEspacio } from "@/lib/estado";
import { formatearNumero } from "@/lib/formato";
import { useIA } from "@/lib/ia";
import {
  Aviso,
  Cargando,
  Etiqueta,
  EtiquetaCriticidad,
  EtiquetaRiesgo,
  Tarjeta,
  Vacio,
} from "../comunes";

const MINIMO_CARACTERES = 40;

export function Analizar() {
  const espacio = useEspacio();
  const ia = useIA();
  const router = useRouter();

  const [texto, setTexto] = useState(espacio.textoPliego);
  const [objeto, setObjeto] = useState("");
  const [entidad, setEntidad] = useState("");
  const [modalidad, setModalidad] = useState("");
  const [analizando, setAnalizando] = useState(false);
  const [subiendo, setSubiendo] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [avisoDocumento, setAvisoDocumento] = useState<string | null>(null);
  const entradaArchivo = useRef<HTMLInputElement>(null);

  // El espacio se restaura del almacenamiento después del primer render, así
  // que el pliego puede llegar más tarde que el montaje de esta vista.
  useEffect(() => {
    setTexto((previo) => previo || espacio.textoPliego);
  }, [espacio.textoPliego]);

  // Si se llegó desde la búsqueda con un proceso elegido, se precargan sus datos
  // sin pisar lo que el usuario ya haya escrito.
  useEffect(() => {
    const proceso = espacio.procesoSeleccionado;
    if (!proceso) return;
    setObjeto((previo) => previo || (proceso.objeto ?? ""));
    setEntidad((previo) => previo || (proceso.entidad ?? ""));
    setModalidad((previo) => previo || (proceso.modalidad ?? ""));
  }, [espacio.procesoSeleccionado]);

  async function subirDocumento(archivo: File) {
    setSubiendo(true);
    setError(null);
    setAvisoDocumento(null);
    try {
      const documento = await api.cargarDocumento(archivo);
      setTexto(documento.texto);
      espacio.fijarTextoPliego(documento.texto, documento.nombreArchivo);
      const paginas = documento.paginas ? `, ${documento.paginas} páginas` : "";
      setAvisoDocumento(
        `«${documento.nombreArchivo}» cargado: ` +
          `${formatearNumero(documento.caracteres)} caracteres${paginas}.` +
          (documento.truncado
            ? " El documento se truncó por tamaño; revisa que la parte relevante esté incluida."
            : ""),
      );
    } catch (excepcion) {
      setError(excepcion instanceof ErrorApi ? excepcion.message : String(excepcion));
    } finally {
      setSubiendo(false);
      if (entradaArchivo.current) entradaArchivo.current.value = "";
    }
  }

  async function analizar() {
    setAnalizando(true);
    setError(null);
    try {
      espacio.fijarTextoPliego(texto, espacio.origenPliego);
      const analisis = await api.analizarRequisitos({
        textoPliego: texto,
        objetoContractual: objeto || null,
        entidad: entidad || null,
        modalidad: modalidad || null,
        valorEstimado: espacio.procesoSeleccionado?.valor ?? null,
        contextoProveedor: espacio.perfilProveedor || null,
        ...ia.seleccion,
      });
      espacio.fijarAnalisis(analisis);
    } catch (excepcion) {
      setError(excepcion instanceof ErrorApi ? excepcion.message : String(excepcion));
    } finally {
      setAnalizando(false);
    }
  }

  const analisis = espacio.analisis;
  const suficiente = texto.trim().length >= MINIMO_CARACTERES;

  return (
    <>
      <Tarjeta
        titulo="Analizar pliego y requisitos técnicos"
        subtitulo="Pega el texto del pliego, el anexo técnico o los estudios previos, o sube el archivo. El agente extrae requisitos verificables, riesgos y alertas normativas."
      >
        {espacio.procesoSeleccionado && (
          <Aviso tipo="info">
            Proceso seleccionado:{" "}
            <strong>{espacio.procesoSeleccionado.numeroProceso ?? "s/n"}</strong> —{" "}
            {(espacio.procesoSeleccionado.objeto ?? "").slice(0, 160)}
            <div className="tenue" style={{ marginTop: "0.35rem" }}>
              El conjunto de datos abierto no incluye los documentos del proceso.
              Descárgalos desde el enlace de SECOP y súbelos aquí.
            </div>
          </Aviso>
        )}

        <div className="rejilla">
          <div>
            <label htmlFor="a-objeto">Objeto contractual</label>
            <input
              id="a-objeto"
              type="text"
              value={objeto}
              onChange={(e) => setObjeto(e.target.value)}
              placeholder="Desarrollo e implementación de…"
            />
          </div>
          <div>
            <label htmlFor="a-entidad">Entidad contratante</label>
            <input
              id="a-entidad"
              type="text"
              value={entidad}
              onChange={(e) => setEntidad(e.target.value)}
            />
          </div>
          <div>
            <label htmlFor="a-modalidad">Modalidad de selección</label>
            <input
              id="a-modalidad"
              type="text"
              value={modalidad}
              onChange={(e) => setModalidad(e.target.value)}
              placeholder="Concurso de méritos, subasta inversa…"
            />
          </div>
        </div>

        <div style={{ marginTop: "1rem" }}>
          <label htmlFor="a-texto">
            Texto del pliego / anexo técnico ({formatearNumero(texto.length)} caracteres)
          </label>
          <textarea
            id="a-texto"
            value={texto}
            onChange={(e) => setTexto(e.target.value)}
            placeholder="Pega aquí el contenido del pliego o sube un PDF/DOCX…"
            style={{ minHeight: 220 }}
          />
        </div>

        {avisoDocumento && <Aviso tipo="exito">{avisoDocumento}</Aviso>}
        {error && <Aviso tipo="error">{error}</Aviso>}

        <div className="fila" style={{ marginTop: "0.9rem" }}>
          <input
            ref={entradaArchivo}
            type="file"
            accept=".pdf,.docx,.txt,.md"
            aria-label="Archivo del pliego"
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
            {subiendo ? <Cargando texto="Leyendo…" /> : "Subir PDF / DOCX / TXT"}
          </button>
          <button
            className="principal"
            onClick={analizar}
            disabled={analizando || !suficiente}
            title={
              suficiente
                ? undefined
                : `Se requieren al menos ${MINIMO_CARACTERES} caracteres`
            }
          >
            {analizando ? <Cargando texto="Analizando pliego…" /> : "Analizar requisitos"}
          </button>
          {texto && (
            <button
              className="secundario"
              onClick={() => {
                setTexto("");
                espacio.fijarTextoPliego("", null);
                setAvisoDocumento(null);
              }}
            >
              Limpiar
            </button>
          )}
        </div>
        <p className="tenue" style={{ marginTop: "0.6rem" }}>
          Los PDF escaneados sin capa de texto no se pueden leer: requieren OCR previo.
        </p>
      </Tarjeta>

      {analisis && (
        <>
          <Tarjeta
            titulo="Resumen del análisis"
            acciones={
              <button className="principal" onClick={() => router.push("/proponer")}>
                Generar propuesta →
              </button>
            }
          >
            <p>{analisis.resumenEjecutivo}</p>
            {analisis.objetoNormalizado && (
              <p className="tenue">
                <strong>Objeto normalizado:</strong> {analisis.objetoNormalizado}
              </p>
            )}
            <Aviso tipo="info">
              <strong>Recomendación:</strong> {analisis.recomendacion}
            </Aviso>

            {analisis.alertasNormativas.length > 0 && (
              <>
                <h3>Alertas normativas</h3>
                <ul className="lista-limpia">
                  {analisis.alertasNormativas.map((alerta, i) => (
                    <li key={i}>{alerta}</li>
                  ))}
                </ul>
              </>
            )}
          </Tarjeta>

          <Tarjeta
            titulo={`Requisitos técnicos (${analisis.requisitos.length})`}
            subtitulo="Se transfieren automáticamente a las vistas de propuesta y validación."
          >
            {analisis.requisitos.length === 0 ? (
              <Vacio>El agente no identificó requisitos técnicos en el material.</Vacio>
            ) : (
              <div className="contenedor-tabla">
                <table>
                  <thead>
                    <tr>
                      <th style={{ width: "4.5rem" }}>ID</th>
                      <th style={{ width: "8rem" }}>Categoría</th>
                      <th>Requisito</th>
                      <th style={{ width: "7rem" }}>Criticidad</th>
                      <th>Evidencia esperada</th>
                    </tr>
                  </thead>
                  <tbody>
                    {analisis.requisitos.map((requisito) => (
                      <tr key={requisito.id}>
                        <td className="mono">{requisito.id}</td>
                        <td>
                          <Etiqueta>{requisito.categoria}</Etiqueta>
                        </td>
                        <td>
                          {requisito.requisito}
                          {requisito.citaPliego && (
                            <blockquote className="cita tenue">
                              «{requisito.citaPliego}»
                            </blockquote>
                          )}
                          {requisito.normaRelacionada && (
                            <div className="tenue" style={{ marginTop: "0.3rem" }}>
                              Norma: {requisito.normaRelacionada}
                            </div>
                          )}
                        </td>
                        <td>
                          <EtiquetaCriticidad valor={requisito.criticidad} />
                        </td>
                        <td className="tenue">{requisito.evidenciaEsperada}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Tarjeta>

          {analisis.riesgos.length > 0 && (
            <Tarjeta titulo={`Riesgos identificados (${analisis.riesgos.length})`}>
              {analisis.riesgos.map((riesgo, indice) => (
                <div key={indice} className="riesgo">
                  <div className="fila">
                    <EtiquetaRiesgo valor={riesgo.nivel} />
                    <Etiqueta>{riesgo.tipo}</Etiqueta>
                    <strong>{riesgo.descripcion}</strong>
                  </div>
                  <div className="tenue" style={{ marginTop: "0.35rem" }}>
                    <strong>Impacto:</strong> {riesgo.impacto}
                  </div>
                  <div className="tenue">
                    <strong>Mitigación:</strong> {riesgo.mitigacion}
                  </div>
                </div>
              ))}
            </Tarjeta>
          )}

          <div className="rejilla">
            <ListaTarjeta
              titulo="Criterios de evaluación"
              items={analisis.criteriosEvaluacion}
            />
            <ListaTarjeta
              titulo="Documentos habilitantes"
              items={analisis.documentosHabilitantes}
            />
            <ListaTarjeta
              titulo="Preguntas a la entidad"
              items={analisis.preguntasALaEntidad}
              nota="Para radicar como observaciones al proyecto de pliego."
            />
          </div>
        </>
      )}
    </>
  );
}

function ListaTarjeta({
  titulo,
  items,
  nota,
}: {
  titulo: string;
  items: string[];
  nota?: string;
}) {
  if (items.length === 0) return null;
  return (
    <Tarjeta titulo={titulo} subtitulo={nota}>
      <ul className="lista-limpia">
        {items.map((item, indice) => (
          <li key={indice}>{item}</li>
        ))}
      </ul>
    </Tarjeta>
  );
}
