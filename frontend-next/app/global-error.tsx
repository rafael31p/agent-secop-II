"use client";

import { useEffect, useState } from "react";
import { CLAVE_ESPACIO } from "@/lib/estado";

/**
 * Último recurso: un fallo en el propio layout raíz.
 *
 * <p>Aquí `error.tsx` ya no sirve —el layout que lo contendría es justo el que
 * falló—, así que este componente reemplaza el documento entero y tiene que
 * traer sus propias etiquetas `html` y `body`. Por lo mismo no puede usar los
 * contextos de la aplicación ni los estilos que dependan de ellos.
 *
 * <p>Lo importante es lo que ofrece: si el fallo se debe a un espacio de trabajo
 * corrupto en `sessionStorage`, recargar no arregla nada porque se vuelve a
 * restaurar lo mismo. El botón de descartarlo rompe ese bucle, y descargar la
 * copia antes evita que la salida sea perder el pliego.
 */
export default function ErrorGlobal({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  const [descartado, setDescartado] = useState(false);

  useEffect(() => {
    console.error("Fallo en el layout raíz:", error);
  }, [error]);

  function descargarEspacio() {
    try {
      const guardado = sessionStorage.getItem(CLAVE_ESPACIO);
      if (!guardado) return;
      const url = URL.createObjectURL(new Blob([guardado], { type: "application/json" }));
      const enlace = document.createElement("a");
      enlace.href = url;
      enlace.download = "espacio-de-trabajo.json";
      enlace.click();
      URL.revokeObjectURL(url);
    } catch {
      // Si ni siquiera se puede leer, no queda nada que rescatar.
    }
  }

  function descartarEspacio() {
    try {
      sessionStorage.removeItem(CLAVE_ESPACIO);
    } catch {
      // Modo privado: no había nada persistido que descartar.
    }
    setDescartado(true);
  }

  return (
    <html lang="es-CO">
      <body
        style={{
          fontFamily: '"Segoe UI", system-ui, sans-serif',
          maxWidth: "44rem",
          margin: "3rem auto",
          padding: "0 1.25rem",
          lineHeight: 1.55,
        }}
      >
        <h1 style={{ fontSize: "1.3rem" }}>La aplicación no pudo cargar</h1>
        <p role="alert">{error.message || "Error inesperado."}</p>
        {error.digest && (
          <p style={{ fontSize: "0.85rem", opacity: 0.75 }}>
            Referencia: <code>{error.digest}</code>
          </p>
        )}

        <p>
          Si el fallo se repite al recargar, puede que el material guardado en esta
          pestaña esté corrupto. Descárgalo antes de descartarlo.
        </p>

        <div style={{ display: "flex", gap: "0.6rem", flexWrap: "wrap" }}>
          <button onClick={reset}>Reintentar</button>
          <button onClick={descargarEspacio}>Descargar el material guardado</button>
          <button onClick={descartarEspacio} disabled={descartado}>
            {descartado ? "Descartado — recarga la página" : "Descartar y empezar limpio"}
          </button>
        </div>
      </body>
    </html>
  );
}
