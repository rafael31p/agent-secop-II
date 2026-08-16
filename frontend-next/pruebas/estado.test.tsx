import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { CLAVE_ESPACIO, CLAVE_PERFIL, ProveedorEspacio, useEspacio } from "@/lib/estado";
import { analisis, proceso } from "./ayudas";

/** Sonda que expone el contexto para poder afirmar sobre él desde fuera. */
function Sonda() {
  const espacio = useEspacio();
  return (
    <div>
      <span data-testid="pliego">{espacio.textoPliego}</span>
      <span data-testid="origen">{espacio.origenPliego ?? "(ninguno)"}</span>
      <span data-testid="requisitos">{espacio.requisitos.length}</span>
      <span data-testid="perfil">{espacio.perfilProveedor}</span>
      <span data-testid="proceso">{espacio.procesoSeleccionado?.id ?? "(ninguno)"}</span>
      <button onClick={() => espacio.fijarTextoPliego("Un pliego largo", "pliego.pdf")}>
        cargar pliego
      </button>
      <button onClick={() => espacio.fijarAnalisis(analisis())}>fijar análisis</button>
      <button onClick={() => espacio.seleccionarProceso(proceso())}>
        elegir proceso
      </button>
      <button onClick={() => espacio.fijarPerfilProveedor("Fábrica de software")}>
        fijar perfil
      </button>
      <button onClick={() => espacio.limpiar()}>limpiar</button>
    </div>
  );
}

function montar() {
  const usuario = userEvent.setup();
  render(
    <ProveedorEspacio>
      <Sonda />
    </ProveedorEspacio>,
  );
  return usuario;
}

describe("espacio de trabajo", () => {
  it("deriva los requisitos del análisis", async () => {
    const usuario = montar();

    await usuario.click(screen.getByText("fijar análisis"));

    expect(screen.getByTestId("requisitos")).toHaveTextContent("1");
  });

  it("recuerda el pliego para que sobreviva a recargar la página", async () => {
    const usuario = montar();

    await usuario.click(screen.getByText("cargar pliego"));

    await waitFor(() => {
      const guardado = JSON.parse(sessionStorage.getItem(CLAVE_ESPACIO) ?? "{}");
      expect(guardado.textoPliego).toBe("Un pliego largo");
      expect(guardado.origenPliego).toBe("pliego.pdf");
    });
  });

  it("restaura lo guardado al montar", async () => {
    sessionStorage.setItem(
      CLAVE_ESPACIO,
      JSON.stringify({ textoPliego: "Pliego previo", analisis: analisis() }),
    );

    montar();

    await waitFor(() => {
      expect(screen.getByTestId("pliego")).toHaveTextContent("Pliego previo");
      expect(screen.getByTestId("requisitos")).toHaveTextContent("1");
    });
  });

  it("empieza limpio si lo guardado está corrupto", async () => {
    sessionStorage.setItem(CLAVE_ESPACIO, "{no es json");

    montar();

    await waitFor(() => {
      expect(screen.getByTestId("pliego")).toBeEmptyDOMElement();
    });
  });

  it("no borra lo guardado durante el primer render", async () => {
    // El servidor renderiza sin almacenamiento; si ese estado vacío se
    // persistiera antes de restaurar, se perdería el trabajo en cada recarga.
    sessionStorage.setItem(
      CLAVE_ESPACIO,
      JSON.stringify({ textoPliego: "Pliego previo" }),
    );

    montar();

    await waitFor(() => {
      expect(screen.getByTestId("pliego")).toHaveTextContent("Pliego previo");
    });
    expect(JSON.parse(sessionStorage.getItem(CLAVE_ESPACIO)!).textoPliego).toBe(
      "Pliego previo",
    );
  });

  it("guarda el perfil del oferente entre sesiones, no solo entre pestañas", async () => {
    const usuario = montar();

    await usuario.click(screen.getByText("fijar perfil"));

    expect(localStorage.getItem(CLAVE_PERFIL)).toBe("Fábrica de software");
    expect(sessionStorage.getItem(CLAVE_PERFIL)).toBeNull();
  });

  it("restaura el perfil guardado", async () => {
    localStorage.setItem(CLAVE_PERFIL, "Consultora de datos");

    montar();

    await waitFor(() => {
      expect(screen.getByTestId("perfil")).toHaveTextContent("Consultora de datos");
    });
  });

  it("limpiar vacía el pliego y el proceso pero conserva el perfil", async () => {
    const usuario = montar();
    await usuario.click(screen.getByText("cargar pliego"));
    await usuario.click(screen.getByText("elegir proceso"));
    await usuario.click(screen.getByText("fijar perfil"));

    await usuario.click(screen.getByText("limpiar"));

    expect(screen.getByTestId("pliego")).toBeEmptyDOMElement();
    expect(screen.getByTestId("proceso")).toHaveTextContent("(ninguno)");
    expect(screen.getByTestId("perfil")).toHaveTextContent("Fábrica de software");
  });
});
