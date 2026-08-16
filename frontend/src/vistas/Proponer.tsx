/** Vista: generación del borrador de propuesta técnica. */

import { useState } from "react";
import { api, ErrorApi } from "../api/client";
import { Aviso, Cargando, Etiqueta, Tarjeta, Vacio } from "../componentes/comunes";
import { useEspacio } from "../estado";

const ENFASIS_DISPONIBLES = [
  "seguridad de la información",
  "protección de datos personales",
  "accesibilidad (Res. 1519/2020)",
  "interoperabilidad",
  "nube pública",
  "metodologías ágiles",
  "transferencia de conocimiento",
  "niveles de servicio (ANS)",
  "sostenibilidad y soporte",
];

export default function Proponer({ irA }: { irA: (vista: string) => void }) {
  const espacio = useEspacio();
  const [objeto, setObjeto] = useState(
    espacio.analisis?.objeto_normalizado ??
      espacio.procesoSeleccionado?.objeto ??
      "",
  );
  const [plazo, setPlazo] = useState<number | "">("");
  const [enfasis, setEnfasis] = useState<string[]>([]);
  const [generando, setGenerando] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copiado, setCopiado] = useState(false);

  const requisitos = espacio.requisitos;
  const propuesta = espacio.propuesta;

  function alternarEnfasis(valor: string) {
    setEnfasis((previo) =>
      previo.includes(valor)
        ? previo.filter((e) => e !== valor)
        : [...previo, valor],
    );
  }

  async function generar() {
    setGenerando(true);
    setError(null);
    try {
      const resultado = await api.generarPropuesta({
        objeto_contractual: objeto,
        perfil_proveedor: espacio.perfilProveedor,
        requisitos,
        texto_pliego: requisitos.length ? null : espacio.textoPliego || null,
        entidad: espacio.procesoSeleccionado?.entidad ?? null,
        valor_estimado: espacio.procesoSeleccionado?.valor ?? null,
        plazo_meses: plazo === "" ? null : plazo,
        enfasis,
      });
      espacio.fijarPropuesta(resultado);
    } catch (excepcion) {
      setError(excepcion instanceof ErrorApi ? excepcion.message : String(excepcion));
    } finally {
      setGenerando(false);
    }
  }

  async function copiar() {
    if (!propuesta) return;
    try {
      await navigator.clipboard.writeText(propuesta.markdown);
      setCopiado(true);
      setTimeout(() => setCopiado(false), 2500);
    } catch {
      setError(
        "El navegador bloqueó el acceso al portapapeles. Selecciona el texto y cópialo manualmente.",
      );
    }
  }

  function descargar() {
    if (!propuesta) return;
    const blob = new Blob([propuesta.markdown], {
      type: "text/markdown;charset=utf-8",
    });
    const url = URL.createObjectURL(blob);
    const enlace = document.createElement("a");
    enlace.href = url;
    enlace.download = `propuesta-${new Date().toISOString().slice(0, 10)}.md`;
    enlace.click();
    URL.revokeObjectURL(url);
  }

  const listoParaGenerar =
    objeto.trim().length > 5 &&
    espacio.perfilProveedor.trim().length >= 10 &&
    (requisitos.length > 0 || espacio.textoPliego.trim().length >= 40);

  return (
    <>
      <Tarjeta
        titulo="Generar propuesta técnica"
        subtitulo="El agente redacta un borrador alineado al pliego. No inventa certificaciones ni experiencia que no declares: lo que falte queda marcado como vacío de información."
      >
        <div>
          <label htmlFor="p-objeto">Objeto contractual</label>
          <input
            id="p-objeto"
            type="text"
            value={objeto}
            onChange={(e) => setObjeto(e.target.value)}
            placeholder="Desarrollo e implementación del sistema de información…"
          />
        </div>

        <div style={{ marginTop: "1rem" }}>
          <label htmlFor="p-perfil">
            Perfil y capacidades del oferente (se guarda en este navegador)
          </label>
          <textarea
            id="p-perfil"
            value={espacio.perfilProveedor}
            onChange={(e) => espacio.fijarPerfilProveedor(e.target.value)}
            placeholder={
              "Ej.: Fábrica de software con 12 años de experiencia. 45 desarrolladores " +
              "(Java/Spring, Angular, Python). Certificaciones ISO 9001 e ISO 27001. " +
              "Experiencia en 8 contratos con entidades públicas. Nube AWS y Azure. " +
              "Equipo de QA y seguridad ofensiva propio."
            }
            style={{ minHeight: 130 }}
          />
          <p className="tenue">
            Cuanto más específico y verificable sea este perfil, menos vacíos tendrá la
            propuesta.
          </p>
        </div>

        <div className="rejilla" style={{ marginTop: "1rem" }}>
          <div>
            <label htmlFor="p-plazo">Plazo de ejecución (meses)</label>
            <input
              id="p-plazo"
              type="number"
              min={1}
              max={120}
              value={plazo}
              onChange={(e) => setPlazo(e.target.value ? Number(e.target.value) : "")}
            />
          </div>
        </div>

        <div style={{ marginTop: "1rem" }}>
          <label>Énfasis en la redacción</label>
          <div className="fila">
            {ENFASIS_DISPONIBLES.map((opcion) => (
              <label key={opcion} className="casilla">
                <input
                  type="checkbox"
                  checked={enfasis.includes(opcion)}
                  onChange={() => alternarEnfasis(opcion)}
                />
                {opcion}
              </label>
            ))}
          </div>
        </div>

        {requisitos.length > 0 ? (
          <Aviso tipo="exito">
            Se usarán los {requisitos.length} requisitos extraídos en la vista de
            análisis.
          </Aviso>
        ) : espacio.textoPliego ? (
          <Aviso tipo="alerta">
            No hay requisitos estructurados; se usará el texto del pliego directamente.
            Para mejores resultados, ejecuta primero el análisis de requisitos.
          </Aviso>
        ) : (
          <Aviso tipo="alerta">
            Falta el pliego. Ve a «Analizar» y carga el documento o pega su texto.
          </Aviso>
        )}

        {error && <Aviso tipo="error">{error}</Aviso>}

        <div className="fila">
          <button
            className="principal"
            onClick={generar}
            disabled={generando || !listoParaGenerar}
          >
            {generando ? (
              <Cargando texto="Redactando propuesta…" />
            ) : (
              "Generar propuesta"
            )}
          </button>
          {!listoParaGenerar && (
            <span className="tenue">
              Requiere objeto contractual, perfil del oferente (10+ caracteres) y el
              pliego o sus requisitos.
            </span>
          )}
        </div>
      </Tarjeta>

      {propuesta && (
        <>
          <Tarjeta
            titulo={propuesta.titulo}
            acciones={
              <div className="fila">
                <button className="secundario" onClick={copiar}>
                  {copiado ? "✓ Copiado" : "Copiar Markdown"}
                </button>
                <button className="secundario" onClick={descargar}>
                  Descargar .md
                </button>
                <button className="principal" onClick={() => irA("validar")}>
                  Validar propuesta →
                </button>
              </div>
            }
          >
            <p>{propuesta.resumen_ejecutivo}</p>

            {propuesta.vacios_de_informacion.length > 0 && (
              <Aviso tipo="alerta">
                <strong>Vacíos de información</strong> — datos que el pliego exige y tu
                perfil no acredita:
                <ul className="lista-limpia">
                  {propuesta.vacios_de_informacion.map((vacio, i) => (
                    <li key={i}>{vacio}</li>
                  ))}
                </ul>
              </Aviso>
            )}

            {propuesta.supuestos.length > 0 && (
              <>
                <h3>Supuestos asumidos</h3>
                <ul className="lista-limpia">
                  {propuesta.supuestos.map((supuesto, i) => (
                    <li key={i}>{supuesto}</li>
                  ))}
                </ul>
              </>
            )}
          </Tarjeta>

          <Tarjeta titulo={`Secciones (${propuesta.secciones.length})`}>
            {propuesta.secciones.length === 0 ? (
              <Vacio>El agente no devolvió secciones.</Vacio>
            ) : (
              propuesta.secciones.map((seccion, indice) => (
                <details
                  key={indice}
                  open={indice === 0}
                  style={{
                    borderBottom: "1px solid var(--borde)",
                    padding: "0.6rem 0",
                  }}
                >
                  <summary
                    style={{ cursor: "pointer", fontWeight: 600, fontSize: "0.98rem" }}
                  >
                    {seccion.titulo}
                    {seccion.requisitos_cubiertos.length > 0 && (
                      <span style={{ marginLeft: "0.6rem" }}>
                        {seccion.requisitos_cubiertos.map((id) => (
                          <Etiqueta key={id} variante="acento">
                            {id}
                          </Etiqueta>
                        ))}
                      </span>
                    )}
                  </summary>
                  <div style={{ whiteSpace: "pre-wrap", marginTop: "0.6rem" }}>
                    {seccion.contenido}
                  </div>
                </details>
              ))
            )}
          </Tarjeta>

          <Tarjeta titulo="Documento completo (Markdown)">
            <div className="markdown">{propuesta.markdown}</div>
          </Tarjeta>
        </>
      )}
    </>
  );
}
