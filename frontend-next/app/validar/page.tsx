import type { Metadata } from "next";
import { Validar } from "@/componentes/vistas/Validar";

export const metadata: Metadata = {
  title: "Validar propuesta — Agente SECOP II",
};

export default function PaginaValidar() {
  return <Validar />;
}
