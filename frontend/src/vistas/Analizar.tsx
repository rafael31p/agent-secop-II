/** Vista: carga del pliego y extracción de requisitos técnicos con IA. */

import { useEffect, useRef, useState } from "react";
import { api, ErrorApi } from "../api/client";
import {
  Aviso,
  Cargando,
  Etiqueta,
  EtiquetaCriticidad,
  EtiquetaRiesgo,
  Tarjeta,
  Vacio,
} from "../componentes/comunes";
import { useEspacio } from "../estado";

const MINIMO_CARACTERES = 40;

export default function Analizar({ irA }: { irA: (vista: string) => void }) {
  const espacio = useEspacio();
  const [texto, setTexto] = useState(espacio.textoPliego);
  const [objeto, setObjeto] = useState(espacio.procesoSeleccionado?.objeto ?? "");
  const [entidad, setEntidad] = useState(espacio.procesoSeleccionado?.entidad ?? "");
  const [modalidad, setModalidad] = useState(
    espacio.procesoSeleccionado?.modalidad ?? "",
  );
  const [analizando, setAnalizando] = useState(false);
  const [subiendo, setSubiendo] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [avisoDocumento, setAvisoDocumento] = useState<string | null>(null);
  const entradaArchivo = useRef<HTMLInputElement>(null);

  // Si el usuario llegó desde la búsqueda con un proceso seleccionado, precarga sus datos.
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
      espacio.fijarTextoPliego(documento.texto, documento.nombre_archivo);
      const paginas = documento.paginas ? `, ${documento.paginas} páginas` : "";
      setAvisoDocumento(
        `«${documento.nombre_archivo}» cargado: ${documento.caracteres.toLocaleString(
          "es-CO",
        )} caracteres${paginas}.` +
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
        texto_pliego: texto,
        objeto_contractual: objeto || null,
        entidad: entidad || null,
        modalidad: modalidad || null,
        valor_estimado: espacio.procesoSeleccionado?.valor ?? null,
        contexto_proveedor: espacio.perfilProveedor || null,
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
            <strong>{espacio.procesoSeleccionado.numero_proceso ?? "s/n"}</strong> —{" "}
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
            Texto del pliego / anexo técnico ({texto.length.toLocaleString("es-CO")}{" "}
            caracteres)
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
            title={suficiente ? undefined : `Se requieren al menos ${MINIMO_CARACTERES} caracteres`}
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
              <button className="principal" onClick={() => irA("proponer")}>
                Generar propuesta →
              </button>
            }
          >
            <p>{analisis.resumen_ejecutivo}</p>
            {analisis.objeto_normalizado && (
              <p className="tenue">
                <strong>Objeto normalizado:</strong> {analisis.objeto_normalizado}
              </p>
            )}
            <Aviso tipo="info">
              <strong>Recomendación:</strong> {analisis.recomendacion}
            </Aviso>

            {analisis.alertas_normativas.length > 0 && (
              <>
                <h3>Alertas normativas</h3>
                <ul className="lista-limpia">
                  {analisis.alertas_normativas.map((alerta, i) => (
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
                          {requisito.cita_pliego && (
                            <blockquote
                              className="tenue"
                              style={{
                                margin: "0.4rem 0 0",
                                paddingLeft: "0.7rem",
                                borderLeft: "3px solid var(--borde)",
                                fontStyle: "italic",
                              }}
                            >
                              «{requisito.cita_pliego}»
                            </blockquote>
                          )}
                          {requisito.norma_relacionada && (
                            <div className="tenue" style={{ marginTop: "0.3rem" }}>
                              Norma: {requisito.norma_relacionada}
                            </div>
                          )}
                        </td>
                        <td>
                          <EtiquetaCriticidad valor={requisito.criticidad} />
                        </td>
                        <td className="tenue">{requisito.evidencia_esperada}</td>
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
                <div
                  key={indice}
                  style={{
                    borderBottom:
                      indice < analisis.riesgos.length - 1
                        ? "1px solid var(--borde)"
                        : "none",
                    padding: "0.7rem 0",
                  }}
                >
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
              items={analisis.criterios_evaluacion}
            />
            <ListaTarjeta
              titulo="Documentos habilitantes"
              items={analisis.documentos_habilitantes}
            />
            <ListaTarjeta
              titulo="Preguntas a la entidad"
              items={analisis.preguntas_a_la_entidad}
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
