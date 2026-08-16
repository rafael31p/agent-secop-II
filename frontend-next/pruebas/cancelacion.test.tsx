import { screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Analizar } from "@/componentes/vistas/Analizar";
import { api, ErrorApi } from "@/lib/api";
import { fueCancelada } from "@/lib/cancelacion";
import { analisis, CATALOGO, renderizar } from "./ayudas";

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

const PLIEGO = "El contratista debe desarrollar el portal ciudadano bajo microservicios.";

describe("fueCancelada", () => {
  it("reconoce la señal abortada", () => {
    const controlador = new AbortController();
    controlador.abort();

    expect(fueCancelada(controlador.signal, new Error("lo que sea"))).toBe(true);
  });

  it("reconoce el AbortError aunque la señal no llegue", () => {
    expect(fueCancelada(undefined, new DOMException("Aborted", "AbortError"))).toBe(true);
  });

  it("no confunde un fallo real con una cancelación", () => {
    // Si los confundiera, un error del proveedor se tragaría en silencio y el
    // usuario vería la operación terminar sin resultado y sin explicación.
    expect(
      fueCancelada(new AbortController().signal, new ErrorApi("Cuota agotada", 429)),
    ).toBe(false);
  });
});

describe("cancelación de operaciones largas", () => {
  beforeEach(() => {
    vi.mocked(api.proveedores).mockResolvedValue(CATALOGO);
  });

  it("ofrece cancelar mientras el análisis está en curso, y no antes", async () => {
    // Una promesa que no se resuelve deja la operación colgada, que es justo el
    // estado en el que el botón tiene que existir.
    vi.mocked(api.analizarRequisitos).mockReturnValue(new Promise(() => {}));
    const { usuario } = renderizar(<Analizar />);

    expect(screen.queryByRole("button", { name: "Cancelar" })).not.toBeInTheDocument();

    await usuario.type(screen.getByLabelText(/Texto del pliego/), PLIEGO);
    await usuario.click(screen.getByRole("button", { name: "Analizar requisitos" }));

    expect(await screen.findByRole("button", { name: "Cancelar" })).toBeVisible();
  });

  it("cancelar aborta la señal que recibió el cliente", async () => {
    vi.mocked(api.analizarRequisitos).mockReturnValue(new Promise(() => {}));
    const { usuario } = renderizar(<Analizar />);
    await usuario.type(screen.getByLabelText(/Texto del pliego/), PLIEGO);
    await usuario.click(screen.getByRole("button", { name: "Analizar requisitos" }));

    const senal = vi.mocked(api.analizarRequisitos).mock.calls[0][1];
    expect(senal?.aborted).toBe(false);

    await usuario.click(await screen.findByRole("button", { name: "Cancelar" }));

    expect(senal?.aborted).toBe(true);
  });

  it("cancelar no deja un error en pantalla: fue una decisión del usuario", async () => {
    vi.mocked(api.analizarRequisitos).mockImplementation(
      (_cuerpo, senal) =>
        new Promise((_resolver, rechazar) => {
          senal?.addEventListener("abort", () =>
            rechazar(new DOMException("Aborted", "AbortError")),
          );
        }),
    );
    const { usuario } = renderizar(<Analizar />);
    await usuario.type(screen.getByLabelText(/Texto del pliego/), PLIEGO);
    await usuario.click(screen.getByRole("button", { name: "Analizar requisitos" }));

    await usuario.click(await screen.findByRole("button", { name: "Cancelar" }));

    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Analizar requisitos" }),
      ).toBeInTheDocument(),
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("un fallo real sí se reporta", async () => {
    vi.mocked(api.analizarRequisitos).mockRejectedValue(
      new ErrorApi("Cuota agotada", 429),
    );
    const { usuario } = renderizar(<Analizar />);
    await usuario.type(screen.getByLabelText(/Texto del pliego/), PLIEGO);

    await usuario.click(screen.getByRole("button", { name: "Analizar requisitos" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Cuota agotada");
  });

  it("al terminar bien, el botón de cancelar desaparece", async () => {
    vi.mocked(api.analizarRequisitos).mockResolvedValue(analisis());
    const { usuario } = renderizar(<Analizar />);
    await usuario.type(screen.getByLabelText(/Texto del pliego/), PLIEGO);

    await usuario.click(screen.getByRole("button", { name: "Analizar requisitos" }));

    await waitFor(() =>
      expect(screen.queryByRole("button", { name: "Cancelar" })).not.toBeInTheDocument(),
    );
  });
});
