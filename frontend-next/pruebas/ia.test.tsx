import { screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SelectorIA } from "@/componentes/SelectorIA";
import { api, ErrorApi } from "@/lib/api";
import { CLAVE_IA, useIA } from "@/lib/ia";
import { CATALOGO, renderizar } from "./ayudas";

vi.mock("@/lib/api", async (original) => {
  const real = await original<typeof import("@/lib/api")>();
  return { ...real, api: { ...real.api, proveedores: vi.fn(), salud: vi.fn() } };
});

/** Muestra lo que se adjuntaría a cada petición al modelo. */
function Sonda() {
  const ia = useIA();
  return (
    <span data-testid="seleccion">
      {ia.seleccion.proveedor ?? "(servidor)"}/{ia.seleccion.modelo ?? "(por defecto)"}
    </span>
  );
}

function montar() {
  return renderizar(
    <>
      <SelectorIA />
      <Sonda />
    </>,
  );
}

describe("selección de proveedor y modelo", () => {
  beforeEach(() => {
    vi.mocked(api.proveedores).mockResolvedValue(CATALOGO);
  });

  it("por defecto no impone nada: decide el servidor", async () => {
    montar();

    await waitFor(() => expect(screen.getByLabelText("Proveedor")).toBeInTheDocument());
    expect(screen.getByTestId("seleccion")).toHaveTextContent("(servidor)/(por defecto)");
  });

  it("lista los proveedores sin credenciales, deshabilitados y con el motivo", async () => {
    montar();

    const sinConfigurar = await screen.findByRole("option", {
      name: /OpenAI — sin configurar/,
    });
    expect(sinConfigurar).toBeDisabled();
    expect(sinConfigurar).toHaveAttribute("title", "Falta AGENTE_IA_OPENAI_API_KEY.");
  });

  it("al elegir proveedor lo adjunta a las peticiones y lo recuerda", async () => {
    const { usuario } = montar();
    await screen.findByLabelText("Proveedor");

    await usuario.selectOptions(screen.getByLabelText("Proveedor"), "gemini");

    expect(screen.getByTestId("seleccion")).toHaveTextContent("gemini/(por defecto)");
    expect(JSON.parse(localStorage.getItem(CLAVE_IA)!)).toEqual({
      proveedor: "gemini",
      modelo: null,
    });
  });

  it("ofrece los modelos del proveedor elegido", async () => {
    const { usuario } = montar();
    await screen.findByLabelText("Proveedor");
    // Sin proveedor no hay modelo que escoger: lo pone el servidor.
    expect(screen.getByLabelText("Modelo")).toBeDisabled();

    await usuario.selectOptions(screen.getByLabelText("Proveedor"), "gemini");
    await usuario.selectOptions(screen.getByLabelText("Modelo"), "gemini-3.5-flash");

    expect(screen.getByTestId("seleccion")).toHaveTextContent("gemini/gemini-3.5-flash");
  });

  it("olvida el modelo al cambiar de proveedor", async () => {
    const { usuario } = montar();
    await screen.findByLabelText("Proveedor");
    await usuario.selectOptions(screen.getByLabelText("Proveedor"), "gemini");
    await usuario.selectOptions(screen.getByLabelText("Modelo"), "gemini-3.5-flash");

    await usuario.selectOptions(screen.getByLabelText("Proveedor"), "");

    // Un modelo de Gemini enviado a otro proveedor sería un 404 seguro.
    expect(screen.getByTestId("seleccion")).toHaveTextContent("(servidor)/(por defecto)");
  });

  it("acepta un modelo escrito a mano, porque la lista es de sugerencias", async () => {
    const { usuario } = montar();
    await screen.findByLabelText("Proveedor");
    await usuario.selectOptions(screen.getByLabelText("Proveedor"), "gemini");

    await usuario.selectOptions(screen.getByLabelText("Modelo"), "__otro__");
    await usuario.type(screen.getByLabelText("Identificador del modelo"), "gemini-4-pro");

    expect(screen.getByTestId("seleccion")).toHaveTextContent("gemini/gemini-4-pro");
  });

  it("restaura la preferencia guardada", async () => {
    localStorage.setItem(
      CLAVE_IA,
      JSON.stringify({ proveedor: "gemini", modelo: "gemini-3.5-flash" }),
    );

    montar();

    await waitFor(() =>
      expect(screen.getByTestId("seleccion")).toHaveTextContent(
        "gemini/gemini-3.5-flash",
      ),
    );
  });

  it("descarta la preferencia si al proveedor ya no le quedan credenciales", async () => {
    // Si no, cada petición fallaría con 503 por una clave que se retiró del
    // servidor y el usuario no tendría forma de saber por qué.
    localStorage.setItem(CLAVE_IA, JSON.stringify({ proveedor: "openai", modelo: null }));

    montar();

    await waitFor(() =>
      expect(screen.getByTestId("seleccion")).toHaveTextContent(
        "(servidor)/(por defecto)",
      ),
    );
    expect(localStorage.getItem(CLAVE_IA)).toBeNull();
  });

  it("avisa cuando ningún proveedor tiene credenciales", async () => {
    vi.mocked(api.proveedores).mockResolvedValue(
      CATALOGO.map((p) => ({ ...p, configurado: false })),
    );

    montar();

    expect(await screen.findByText(/Ningún proveedor tiene credenciales/)).toBeVisible();
  });

  it("si el catálogo no carga, sigue funcionando con el predeterminado", async () => {
    vi.mocked(api.proveedores).mockRejectedValue(new ErrorApi("Backend caído", 0));

    montar();

    expect(await screen.findByText(/No se pudo leer el catálogo/)).toBeVisible();
    expect(screen.getByTestId("seleccion")).toHaveTextContent("(servidor)/(por defecto)");
  });
});
