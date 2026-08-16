import type { Metadata } from "next";
import { Proponer } from "@/componentes/vistas/Proponer";

export const metadata: Metadata = {
  title: "Generar propuesta — Agente SECOP II",
};

export default function PaginaProponer() {
  return <Proponer />;
}
