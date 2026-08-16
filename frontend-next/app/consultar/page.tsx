import type { Metadata } from "next";
import { Consultar } from "@/componentes/vistas/Consultar";

export const metadata: Metadata = {
  title: "Consultar al agente — Agente SECOP II",
};

export default function PaginaConsultar() {
  return <Consultar />;
}
