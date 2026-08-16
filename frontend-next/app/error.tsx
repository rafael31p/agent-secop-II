"use client";

import Link from "next/link";
import { useEffect } from "react";

/**
 * Frontera de error de las rutas.
 *
 * <p>Sin esto, una excepción durante el render deja la pantalla en blanco y el
 * usuario pierde el pliego que estaba analizando sin saber por qué. Con la
 * frontera, el layout sobrevive —y con él el espacio de trabajo, que vive
 * arriba—, así que reintentar o cambiar de paso recupera el trabajo intacto.
 */
export default function ErrorDeRuta({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    // Sin esto el fallo solo existiría en el momento en que ocurrió.
    console.error("Fallo al renderizar la ruta:", error);
  }, [error]);

  return (
    <section className="tarjeta">
      <h2>Algo falló en esta sección</h2>
      <div className="aviso error" role="alert">
        {error.message || "Error inesperado al mostrar la página."}
        {error.digest && (
          <div className="tenue" style={{ marginTop: "0.35rem" }}>
            Referencia: <span className="mono">{error.digest}</span>
          </div>
        )}
      </div>
      <p className="tenue">
        El material que hayas cargado sigue en la sesión: no se ha perdido nada.
      </p>
      <div className="fila">
        <button className="principal" onClick={reset}>
          Reintentar
        </button>
        {/*
          `Link` y no `<a>`: la frontera de error deja el layout en pie, así que
          navegar del lado del cliente conserva el espacio de trabajo en memoria.
          Un enlace normal recargaría el documento entero sin necesidad.
        */}
        <Link className="boton" href="/">
          Volver a la búsqueda
        </Link>
      </div>
    </section>
  );
}
