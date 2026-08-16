"use client";

import type { ReactNode } from "react";
import { ProveedorEspacio } from "@/lib/estado";
import { ProveedorIA } from "@/lib/ia";

/**
 * Contextos de cliente montados desde el layout, que es un componente de
 * servidor. Están juntos para que el layout no tenga que declararse cliente
 * entero solo por envolver dos proveedores.
 */
export function Proveedores({ children }: { children: ReactNode }) {
  return (
    <ProveedorIA>
      <ProveedorEspacio>{children}</ProveedorEspacio>
    </ProveedorIA>
  );
}
