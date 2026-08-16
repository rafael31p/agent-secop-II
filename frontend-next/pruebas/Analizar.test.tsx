import { screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Analizar } from "@/componentes/vistas/Analizar";
import { api, ErrorApi } from "@/lib/api";
import { CLAVE_ESPACIO } from "@/lib/estado";
import { CATALOGO, analisis, proceso, renderizar } from "./ayudas";
import { enrutadorDePrueba } from "../vitest.setup";

vi.mock("@/lib/api", async (original) => {
  const real = await original<typeof import("@/lib/api")>();
  return {
    ...real,
    api: {
      ...real.api,
      proveedores: vi.fn(),
      analizarRequisitos: vi.fn(),
      cargarDocumento: vi.fn(),
    },
  };
});

const PLIEGO =
  "El contratista debe desarrollar el portal ciudadano bajo arquitectura de microservicios.";

describe("vista Analizar", () => {
  beforeEach(() => {
    vi.mocked(api.proveedores).mockResolvedValue(CATALOGO);
    vi.mocked(api.analizarRequisitos).mockResolvedValue(analisis());
  });

  it("exige un mínimo de texto antes de gastar una llamada al modelo", async () => {
    const { usuario } = renderizar(<Analizar />);
    const boton = screen.getByRole("button", { name: "Analizar requisitos" });
    expect(boton).toBeDisabled();

    await usuario.type(screen.getByLabelText(/Texto del pliego/), PLIEGO);

    expect(boton).toBeEnabled();
  });

  it("envía el pliego con los nombres de campo del backend Quarkus", async () => {
    localStorage.setItem(
      "agente-secop:ia",
      JSON.stringify({ proveedor: "gemini", modelo: null }),
    );
    const { usuario } = renderizar(<Analizar />);

    await usuario.type(screen.getByLabelText(/Texto del pliego/), PLIEGO);
    await usuario.type(screen.getByLabelText("Entidad contratante"), "MinTIC");
    await usuario.click(screen.getByRole("button", { name: "Analizar requisitos" }));

    await waitFor(() =>
      expect(api.analizarRequisitos).toHaveBeenCalledWith(
        expect.objectContaining({
          textoPliego: PLIEGO,
          entidad: "MinTIC",
          proveedor: "gemini",
        }),
      ),
    );
  });

  it("presenta los requisitos extraídos y los deja disponibles para las demás vistas", async () => {
    const { usuario } = renderizar(<Analizar />);

    await usuario.type(screen.getByLabelText(/Texto del pliego/), PLIEGO);
    await usuario.click(screen.getByRole("button", { name: "Analizar requisitos" }));

    expect(await screen.findByText("Arquitectura de microservicios")).toBeVisible();
    expect(screen.getByText("Requisitos técnicos (1)")).toBeVisible();
    await waitFor(() => {
      const guardado = JSON.parse(sessionStorage.getItem(CLAVE_ESPACIO) ?? "{}");
      expect(guardado.analisis.requisitos).toHaveLength(1);
    });
  });

  it("lleva a la generación de la propuesta", async () => {
    const { usuario } = renderizar(<Analizar />);
    await usuario.type(screen.getByLabelText(/Texto del pliego/), PLIEGO);
    await usuario.click(screen.getByRole("button", { name: "Analizar requisitos" }));

    await usuario.click(
      await screen.findByRole("button", { name: "Generar propuesta →" }),
    );

    expect(enrutadorDePrueba.push).toHaveBeenCalledWith("/proponer");
  });

  it("precarga los datos del proceso que venía de la búsqueda", async () => {
    sessionStorage.setItem(
      CLAVE_ESPACIO,
      JSON.stringify({ procesoSeleccionado: proceso() }),
    );

    renderizar(<Analizar />);

    await waitFor(() =>
      expect(screen.getByLabelText("Objeto contractual")).toHaveValue(
        "Desarrollo del portal ciudadano",
      ),
    );
    expect(screen.getByLabelText("Entidad contratante")).toHaveValue("MinTIC");
  });

  it("avisa del PDF escaneado en lugar de mandar un pliego vacío al modelo", async () => {
    vi.mocked(api.cargarDocumento).mockRejectedValue(
      new ErrorApi(
        "El documento no contiene texto extraíble; probablemente sea un PDF escaneado.",
        422,
      ),
    );
    const { usuario } = renderizar(<Analizar />);

    await usuario.upload(
      screen.getByLabelText("Archivo del pliego"),
      new File(["%PDF"], "escaneado.pdf", { type: "application/pdf" }),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent("PDF escaneado");
  });

  it("carga el texto del documento subido", async () => {
    vi.mocked(api.cargarDocumento).mockResolvedValue({
      nombreArchivo: "pliego.pdf",
      tipo: "pdf",
      caracteres: PLIEGO.length,
      paginas: 12,
      texto: PLIEGO,
      truncado: false,
    });
    const { usuario } = renderizar(<Analizar />);

    await usuario.upload(
      screen.getByLabelText("Archivo del pliego"),
      new File(["%PDF"], "pliego.pdf", { type: "application/pdf" }),
    );

    await waitFor(() =>
      expect(screen.getByLabelText(/Texto del pliego/)).toHaveValue(PLIEGO),
    );
    expect(screen.getByText(/«pliego.pdf» cargado/)).toBeVisible();
    expect(screen.getByText(/12 páginas/)).toBeVisible();
  });
});
