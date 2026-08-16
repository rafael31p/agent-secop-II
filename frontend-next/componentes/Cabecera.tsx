"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { ResumenIA, SelectorIA } from "./SelectorIA";

/**
 * Cada paso del flujo es una ruta propia. Antes eran estados de un componente,
 * lo que impedía enlazar o volver atrás; ahora el historial del navegador
 * funciona y una vista se puede compartir por URL.
 */
export const SECCIONES = [
  { ruta: "/", etiqueta: "1 · Buscar procesos" },
  { ruta: "/analizar", etiqueta: "2 · Analizar pliego" },
  { ruta: "/proponer", etiqueta: "3 · Generar propuesta" },
  { ruta: "/validar", etiqueta: "4 · Validar" },
  { ruta: "/consultar", etiqueta: "Consultar" },
] as const;

export function Cabecera() {
  const ruta = usePathname();

  return (
    <header className="encabezado">
      <div className="encabezado-interno">
        <div className="marca">
          Agente SECOP II
          <span>contratación pública de TI</span>
        </div>
        <nav className="navegacion" aria-label="Secciones">
          {SECCIONES.map((seccion) => (
            <Link
              key={seccion.ruta}
              href={seccion.ruta}
              aria-current={ruta === seccion.ruta ? "page" : undefined}
            >
              {seccion.etiqueta}
            </Link>
          ))}
        </nav>
      </div>

      <details className="panel-ia">
        <summary>
          <ResumenIA />
          <span className="tenue"> · cambiar proveedor o modelo</span>
        </summary>
        <div className="panel-ia-cuerpo">
          <SelectorIA />
        </div>
      </details>
    </header>
  );
}
