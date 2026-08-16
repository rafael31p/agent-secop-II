import { useEffect, useState } from "react";
import { api } from "./api/client";
import { Aviso } from "./componentes/comunes";
import { ProveedorEspacio, useEspacio } from "./estado";
import type { EstadoSalud } from "./types";
import Analizar from "./vistas/Analizar";
import Buscar from "./vistas/Buscar";
import Chat from "./vistas/Chat";
import Proponer from "./vistas/Proponer";
import Validar from "./vistas/Validar";

const VISTAS = [
  { clave: "buscar", etiqueta: "1 · Buscar procesos" },
  { clave: "analizar", etiqueta: "2 · Analizar pliego" },
  { clave: "proponer", etiqueta: "3 · Generar propuesta" },
  { clave: "validar", etiqueta: "4 · Validar" },
  { clave: "chat", etiqueta: "Consultar" },
] as const;

type Vista = (typeof VISTAS)[number]["clave"];

export default function App() {
  return (
    <ProveedorEspacio>
      <Interfaz />
    </ProveedorEspacio>
  );
}

function Interfaz() {
  const [vista, setVista] = useState<Vista>("buscar");
  const [salud, setSalud] = useState<EstadoSalud | null>(null);
  const [errorSalud, setErrorSalud] = useState<string | null>(null);
  const espacio = useEspacio();

  useEffect(() => {
    api
      .salud()
      .then(setSalud)
      .catch((excepcion) => setErrorSalud(String(excepcion?.message ?? excepcion)));
  }, []);

  const irA = (destino: string) => setVista(destino as Vista);

  return (
    <>
      <header className="encabezado">
        <div className="encabezado-interno">
          <div className="marca">
            Agente SECOP II
            <span>contratación pública de TI</span>
          </div>
          <nav className="navegacion" aria-label="Secciones">
            {VISTAS.map((item) => (
              <button
                key={item.clave}
                onClick={() => setVista(item.clave)}
                aria-current={vista === item.clave ? "page" : undefined}
              >
                {item.etiqueta}
              </button>
            ))}
          </nav>
        </div>
      </header>

      <main>
        {errorSalud && (
          <Aviso tipo="error">
            No se pudo contactar el backend ({errorSalud}). Verifica que esté corriendo:{" "}
            <span className="mono">uvicorn app.main:app --reload --port 8000</span>
          </Aviso>
        )}

        {salud && !salud.ia_configurada && (
          <Aviso tipo="alerta">
            <strong>Funciones de IA deshabilitadas.</strong> No hay{" "}
            <span className="mono">GEMINI_API_KEY</span> configurada en{" "}
            <span className="mono">backend/.env</span>. La búsqueda en SECOP II funciona,
            pero el análisis, la generación y la validación responderán 503.
          </Aviso>
        )}

        {vista === "buscar" && <Buscar irA={irA} />}
        {vista === "analizar" && <Analizar irA={irA} />}
        {vista === "proponer" && <Proponer irA={irA} />}
        {vista === "validar" && <Validar />}
        {vista === "chat" && <Chat />}
      </main>

      <footer className="pie">
        <p>
          Herramienta de apoyo analítico. No sustituye asesoría jurídica ni el estudio de
          los documentos oficiales publicados en SECOP II. Verifica toda conclusión
          contra la fuente oficial antes de tomar decisiones contractuales.
        </p>
        {salud && (
          <p className="tenue">
            Backend v{salud.version} · {salud.proveedor_ia} / {salud.modelo} · conjunto
            de datos {salud.secop_dataset_procesos}
            {espacio.origenPliego && ` · pliego cargado: ${espacio.origenPliego}`}
          </p>
        )}
      </footer>
    </>
  );
}
