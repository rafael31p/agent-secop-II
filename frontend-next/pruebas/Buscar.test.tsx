import { screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Buscar } from "@/componentes/vistas/Buscar";
import { api, ErrorApi } from "@/lib/api";
import { useEspacio } from "@/lib/estado";
import { CATALOGO, proceso, renderizar } from "./ayudas";
import { enrutadorDePrueba } from "../vitest.setup";

vi.mock("@/lib/api", async (original) => {
  const real = await original<typeof import("@/lib/api")>();
  return {
    ...real,
    api: {
      ...real.api,
      proveedores: vi.fn(),
      buscarProcesos: vi.fn(),
      priorizarProcesos: vi.fn(),
    },
  };
});

function Sonda() {
  const espacio = useEspacio();
  return <span data-testid="elegido">{espacio.procesoSeleccionado?.id ?? "(ninguno)"}</span>;
}

function montar() {
  return renderizar(
    <>
      <Buscar />
      <Sonda />
    </>,
  );
}

const SIN_RESULTADOS = {
  total: 0,
  procesos: [],
  dataset: "p6dx-8zbt",
  advertencias: [],
};

describe("vista Buscar", () => {
  beforeEach(() => {
    vi.mocked(api.proveedores).mockResolvedValue(CATALOGO);
    vi.mocked(api.buscarProcesos).mockResolvedValue(SIN_RESULTADOS);
  });

  it("no envía como filtro los campos vacíos", async () => {
    const { usuario } = montar();

    await usuario.type(screen.getByLabelText("Texto en el objeto"), "ciberseguridad");
    await usuario.click(screen.getByRole("button", { name: "Buscar" }));

    await waitFor(() =>
      expect(api.buscarProcesos).toHaveBeenCalledWith({
        texto: "ciberseguridad",
        soloTi: true,
        limite: 30,
      }),
    );
  });

  it("muestra los procesos con su valor y fecha en formato colombiano", async () => {
    vi.mocked(api.buscarProcesos).mockResolvedValue({
      ...SIN_RESULTADOS,
      total: 1,
      procesos: [proceso()],
    });
    const { usuario } = montar();

    await usuario.click(screen.getByRole("button", { name: "Buscar" }));

    const tarjeta = await screen.findByRole("article");
    expect(within(tarjeta).getByText("Desarrollo del portal ciudadano")).toBeVisible();
    expect(within(tarjeta).getByText(/1\.200\.000\.000/)).toBeVisible();
    expect(within(tarjeta).getByText(/Publicado.*11/)).toBeVisible();
    expect(within(tarjeta).getByText("TI 54")).toBeVisible();
  });

  it("muestra las advertencias que devuelve el backend", async () => {
    vi.mocked(api.buscarProcesos).mockResolvedValue({
      ...SIN_RESULTADOS,
      advertencias: ["Se ignoró el filtro de modalidad."],
    });
    const { usuario } = montar();

    await usuario.click(screen.getByRole("button", { name: "Buscar" }));

    expect(await screen.findByText("Se ignoró el filtro de modalidad.")).toBeVisible();
  });

  it("elegir un proceso lo lleva al análisis con el proceso cargado", async () => {
    vi.mocked(api.buscarProcesos).mockResolvedValue({
      ...SIN_RESULTADOS,
      total: 1,
      procesos: [proceso()],
    });
    const { usuario } = montar();
    await usuario.click(screen.getByRole("button", { name: "Buscar" }));

    await usuario.click(await screen.findByRole("button", { name: "Analizar este proceso" }));

    expect(screen.getByTestId("elegido")).toHaveTextContent("CO1.PCC.1");
    expect(enrutadorDePrueba.push).toHaveBeenCalledWith("/analizar");
  });

  it("explica el fallo en vez de dejar la lista en blanco", async () => {
    vi.mocked(api.buscarProcesos).mockRejectedValue(
      new ErrorApi("La fuente de datos no respondió.", 502),
    );
    const { usuario } = montar();

    await usuario.click(screen.getByRole("button", { name: "Buscar" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "La fuente de datos no respondió.",
    );
  });

  it("priorizar adjunta el proveedor elegido y el perfil del oferente", async () => {
    vi.mocked(api.buscarProcesos).mockResolvedValue({
      ...SIN_RESULTADOS,
      total: 1,
      procesos: [proceso()],
    });
    vi.mocked(api.priorizarProcesos).mockResolvedValue({ priorizados: [], resumen: "ok" });
    localStorage.setItem("agente-secop:ia", JSON.stringify({ proveedor: "gemini", modelo: null }));
    const { usuario } = montar();
    await usuario.click(screen.getByRole("button", { name: "Buscar" }));

    await usuario.click(await screen.findByRole("button", { name: "Priorizar con IA" }));

    await waitFor(() =>
      expect(api.priorizarProcesos).toHaveBeenCalledWith(
        expect.objectContaining({ proveedor: "gemini", modelo: null, maximo: 15 }),
      ),
    );
  });
});
