import type { Metadata } from "next";
import { Analizar } from "@/componentes/vistas/Analizar";

export const metadata: Metadata = {
  title: "Analizar pliego — Agente SECOP II",
};

export default function PaginaAnalizar() {
  return <Analizar />;
}
