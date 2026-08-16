import type { Metadata } from "next";
import type { ReactNode } from "react";
import { Cabecera } from "@/componentes/Cabecera";
import { Pie } from "@/componentes/Pie";
import { Proveedores } from "./proveedores";
import "./globals.css";

export const metadata: Metadata = {
  title: "Agente SECOP II — Contratación pública de TI",
  description:
    "Analiza procesos de SECOP II, extrae requisitos técnicos, genera y valida " +
    "propuestas de tecnología.",
};

/**
 * El layout no se vuelve a montar al navegar entre rutas, así que el espacio de
 * trabajo y la elección de proveedor sobreviven al paso de Buscar a Analizar.
 */
export default function LayoutRaiz({ children }: { children: ReactNode }) {
  return (
    <html lang="es-CO">
      <body>
        <Proveedores>
          {/*
            Primer elemento enfocable de la página. La navegación se repite en
            las cinco rutas; sin este salto, llegar al contenido con teclado
            obliga a atravesarla entera cada vez.
          */}
          <a href="#contenido" className="saltar-al-contenido">
            Saltar al contenido
          </a>
          <Cabecera />
          <main id="contenido" tabIndex={-1}>
            {children}
          </main>
          <Pie />
        </Proveedores>
      </body>
    </html>
  );
}
