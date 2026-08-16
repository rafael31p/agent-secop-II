import { beforeEach, describe, expect, it, vi } from "vitest";
import { api, chatStream, ErrorApi } from "@/lib/api";
import { respuestaSse } from "./ayudas";

/** Respuesta JSON de un solo uso, como la devolvería el backend. */
function respuesta(cuerpo: unknown, estado = 200): Response {
  return new Response(JSON.stringify(cuerpo), {
    status: estado,
    headers: { "Content-Type": "application/json" },
  });
}

describe("cliente HTTP", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  it("envía el filtro tal cual y devuelve el cuerpo tipado", async () => {
    vi.mocked(fetch).mockResolvedValue(
      respuesta({ total: 0, procesos: [], dataset: "p6dx-8zbt", advertencias: [] }),
    );

    const resultado = await api.buscarProcesos({ texto: "software", soloTi: true });

    expect(resultado.dataset).toBe("p6dx-8zbt");
    const [url, opciones] = vi.mocked(fetch).mock.calls[0];
    expect(String(url)).toContain("/api/procesos/buscar");
    // El backend Quarkus espera camelCase; con snake_case los campos llegarían
    // nulos y la validación respondería 422 sin explicar por qué.
    expect(JSON.parse(String(opciones?.body))).toEqual({
      texto: "software",
      soloTi: true,
    });
  });

  it("traduce el error del backend al mensaje que ve el usuario", async () => {
    vi.mocked(fetch).mockResolvedValue(
      respuesta({ detail: "Falta AGENTE_IA_OPENAI_API_KEY." }, 503),
    );

    const fallo = await api.salud().catch((e) => e);

    expect(fallo).toBeInstanceOf(ErrorApi);
    expect(fallo.estado).toBe(503);
    expect(fallo.message).toBe("Falta AGENTE_IA_OPENAI_API_KEY.");
  });

  it("cae al código de estado si el cuerpo del error no es JSON", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response("<html>502 Bad Gateway</html>", {
        status: 502,
        statusText: "Bad Gateway",
      }),
    );

    const fallo = await api.salud().catch((e) => e);

    expect(fallo.message).toBe("502 Bad Gateway");
  });

  it("distingue el backend caído de un error del backend", async () => {
    vi.mocked(fetch).mockRejectedValue(new TypeError("Failed to fetch"));

    const fallo = await api.salud().catch((e) => e);

    expect(fallo.estado).toBe(0);
    expect(fallo.message).toContain("No se pudo contactar el backend");
  });

  it("sube el documento como multipart sin fijar el Content-Type", async () => {
    vi.mocked(fetch).mockResolvedValue(
      respuesta({ nombreArchivo: "pliego.pdf", tipo: "pdf", caracteres: 10, texto: "x" }),
    );

    await api.cargarDocumento(new File(["contenido"], "pliego.pdf"));

    const [, opciones] = vi.mocked(fetch).mock.calls[0];
    expect(opciones?.body).toBeInstanceOf(FormData);
    // Fijarlo a mano rompería el boundary que calcula el navegador.
    expect(opciones?.headers).not.toHaveProperty("Content-Type");
  });
});

describe("chat en streaming", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  it("reensambla eventos partidos entre dos lecturas", async () => {
    vi.mocked(fetch).mockResolvedValue(
      respuestaSse([
        'event: delta\ndata: {"texto": "El SECOP',
        ' II es"}\n\nevent: delta\ndata: {"texto": " una plataforma."}\n\n',
        "event: fin\ndata: {}\n\n",
      ]),
    );
    const fragmentos: string[] = [];

    const completo = await chatStream({ mensajes: [] }, (t) => fragmentos.push(t));

    expect(completo).toBe("El SECOP II es una plataforma.");
    expect(fragmentos).toEqual(["El SECOP II es", " una plataforma."]);
  });

  it("convierte el evento de error del servidor en excepción", async () => {
    vi.mocked(fetch).mockResolvedValue(
      respuestaSse([
        'event: delta\ndata: {"texto": "parcial"}\n\n',
        'event: error\ndata: {"mensaje": "Cuota agotada"}\n\n',
      ]),
    );
    const fragmentos: string[] = [];

    const fallo = await chatStream({ mensajes: [] }, (t) => fragmentos.push(t)).catch(
      (e) => e,
    );

    expect(fallo).toBeInstanceOf(ErrorApi);
    expect(fallo.message).toBe("Cuota agotada");
    // Lo emitido antes del fallo ya llegó a la vista y no se descarta.
    expect(fragmentos).toEqual(["parcial"]);
  });

  it("ignora comentarios de keep-alive y bloques ilegibles", async () => {
    vi.mocked(fetch).mockResolvedValue(
      respuestaSse([
        ": keep-alive\n\n",
        "event: delta\ndata: {esto no es json}\n\n",
        'event: delta\ndata: {"texto": "hola"}\n\n',
      ]),
    );

    await expect(chatStream({ mensajes: [] }, () => {})).resolves.toBe("hola");
  });

  it("adjunta el proveedor y el modelo elegidos", async () => {
    vi.mocked(fetch).mockResolvedValue(respuestaSse(["event: fin\ndata: {}\n\n"]));

    await chatStream(
      {
        mensajes: [{ rol: "user", contenido: "hola" }],
        proveedor: "gemini",
        modelo: "x",
      },
      () => {},
    );

    const [, opciones] = vi.mocked(fetch).mock.calls[0];
    expect(JSON.parse(String(opciones?.body))).toMatchObject({
      proveedor: "gemini",
      modelo: "x",
    });
  });

  it("propaga la cancelación sin disfrazarla de backend caído", async () => {
    const controlador = new AbortController();
    vi.mocked(fetch).mockImplementation(() => {
      controlador.abort();
      return Promise.reject(new DOMException("Aborted", "AbortError"));
    });

    const fallo = await chatStream({ mensajes: [] }, () => {}, controlador.signal).catch(
      (e) => e,
    );

    expect(fallo).not.toBeInstanceOf(ErrorApi);
    expect(fallo.name).toBe("AbortError");
  });
});
