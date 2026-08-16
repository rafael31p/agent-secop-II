import { describe, expect, it } from "vitest";
import { formatearCOP, formatearFecha } from "@/lib/formato";

describe("formatearCOP", () => {
  it("escribe pesos sin decimales", () => {
    expect(formatearCOP(1_200_000_000)).toMatch(/1\.200\.000\.000/);
  });

  it("devuelve «s/d» cuando el dato falta", () => {
    // Socrata deja el precio base vacío en buena parte de los procesos.
    expect(formatearCOP(null)).toBe("s/d");
    expect(formatearCOP(undefined)).toBe("s/d");
    expect(formatearCOP(Number.NaN)).toBe("s/d");
  });

  it("distingue el cero de la ausencia de valor", () => {
    expect(formatearCOP(0)).not.toBe("s/d");
  });
});

describe("formatearFecha", () => {
  it("respeta el día publicado sin importar la zona horaria", () => {
    // Interpretar «2026-08-11T00:00:00.000» como UTC restaría un día en Colombia.
    expect(formatearFecha("2026-08-11T00:00:00.000")).toContain("11");
  });

  it("acepta una fecha sin hora", () => {
    expect(formatearFecha("2026-01-05")).toContain("05");
  });

  it("devuelve «s/d» si no hay fecha", () => {
    expect(formatearFecha(null)).toBe("s/d");
    expect(formatearFecha("")).toBe("s/d");
  });

  it("devuelve el original si no se puede interpretar", () => {
    expect(formatearFecha("pendiente")).toBe("pendiente");
  });
});
