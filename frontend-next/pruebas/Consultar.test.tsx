import { screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { construirContexto, Consultar } from "@/componentes/vistas/Consultar";
import { api, chatStream, ErrorApi } from "@/lib/api";
import type { Espacio } from "@/lib/estado";
import { CATALOGO, analisis, proceso, renderizar, requisito } from "./ayudas";

vi.mock("@/lib/api", async (original) => {
  const real = await original<typeof import("@/lib/api")>();
  return {
    ...real,
    api: { ...real.api, proveedores: vi.fn() },
    chatStream: vi.fn(),
  };
});

/** Espacio mínimo para probar la construcción del contexto sin montar nada. */
function espacioDe(parcial: Partial<Espacio>): Espacio {
  return {
    procesoSeleccionado: null,
    textoPliego: "",
    origenPliego: null,
    analisis: null,
    propuesta: null,
    perfilProveedor: "",
    requisitos: [],
    seleccionarProceso: () => {},
    fijarTextoPliego: () => {},
    fijarAnalisis: () => {},
    fijarPropuesta: () => {},
    fijarPerfilProveedor: () => {},
    limpiar: () => {},
    ...parcial,
  };
}

describe("contexto que acompaña la consulta", () => {
  it("no manda contexto si no hay nada cargado", () => {
    expect(construirContexto(espacioDe({}))).toBeNull();
  });

  it("prefiere los requisitos extraídos al pliego completo", () => {
    const contexto = construirContexto(
      espacioDe({
        analisis: analisis(),
        requisitos: [requisito({ id: "RT-07", requisito: "Cifrado en reposo" })],
        textoPliego: "TEXTO COMPLETO DEL PLIEGO",
      }),
    );

    // Dicen lo mismo ocupando mucho menos, y dejan sitio para la conversación.
    expect(contexto).toContain("[RT-07] (obligatorio) Cifrado en reposo");
    expect(contexto).not.toContain("TEXTO COMPLETO DEL PLIEGO");
  });

  it("recorta un pliego enorme", () => {
    const contexto = construirContexto(espacioDe({ textoPliego: "x".repeat(90_000) }));

    expect(contexto!.length).toBeLessThan(70_000);
  });

  it("incluye el proceso elegido", () => {
    const contexto = construirContexto(espacioDe({ procesoSeleccionado: proceso() }));

    expect(contexto).toContain("Desarrollo del portal ciudadano");
  });
});

describe("vista Consultar", () => {
  beforeEach(() => {
    vi.mocked(api.proveedores).mockResolvedValue(CATALOGO);
  });

  it("muestra la respuesta a medida que llega y la conserva al terminar", async () => {
    vi.mocked(chatStream).mockImplementation(async (_cuerpo, alFragmento) => {
      alFragmento("El SECOP II ");
      alFragmento("es la plataforma.");
      return "El SECOP II es la plataforma.";
    });
    const { usuario } = renderizar(<Consultar />);

    await usuario.type(screen.getByLabelText("Consulta"), "¿Qué es el SECOP II?");
    await usuario.click(screen.getByRole("button", { name: "Enviar" }));

    expect(await screen.findByText("El SECOP II es la plataforma.")).toBeVisible();
    expect(screen.getByText("¿Qué es el SECOP II?")).toBeVisible();
  });

  it("conserva la respuesta parcial cuando el proveedor falla a mitad", async () => {
    vi.mocked(chatStream).mockImplementation(async (_cuerpo, alFragmento) => {
      alFragmento("La modalidad aplicable");
      throw new ErrorApi("Cuota agotada", 429);
    });
    const { usuario } = renderizar(<Consultar />);

    await usuario.type(screen.getByLabelText("Consulta"), "hola");
    await usuario.click(screen.getByRole("button", { name: "Enviar" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Cuota agotada");
    // Media respuesta sigue siendo útil; tirarla obligaría a gastar cuota otra vez.
    expect(screen.getByText("La modalidad aplicable")).toBeVisible();
  });

  it("envía el historial completo, no solo el último mensaje", async () => {
    vi.mocked(chatStream).mockResolvedValue("respuesta");
    const { usuario } = renderizar(<Consultar />);

    await usuario.type(screen.getByLabelText("Consulta"), "primera");
    await usuario.click(screen.getByRole("button", { name: "Enviar" }));
    await waitFor(() => expect(screen.getByText("respuesta")).toBeVisible());
    await usuario.type(screen.getByLabelText("Consulta"), "segunda");
    await usuario.click(screen.getByRole("button", { name: "Enviar" }));

    await waitFor(() => {
      const ultima = vi.mocked(chatStream).mock.calls.at(-1)![0];
      expect(ultima.mensajes.map((m) => m.contenido)).toEqual([
        "primera",
        "respuesta",
        "segunda",
      ]);
    });
  });

  it("una pregunta sugerida se envía con un clic", async () => {
    vi.mocked(chatStream).mockResolvedValue("depende de la cuantía");
    const { usuario } = renderizar(<Consultar />);

    await usuario.click(
      screen.getByRole("button", { name: /modalidad de selección aplica/ }),
    );

    expect(await screen.findByText("depende de la cuantía")).toBeVisible();
  });
});
