import { describe, expect, it } from "vitest";
import { limpiarFiltro } from "@/lib/filtros";
import { contarPorEstado, ordenarPorSeveridad } from "@/lib/matriz";
import { item } from "./ayudas";

describe("limpiarFiltro", () => {
  it("quita los campos que el usuario dejó en blanco", () => {
    const limpio = limpiarFiltro({
      texto: "software",
      entidad: "",
      departamento: null,
      valorMin: undefined,
    });

    expect(limpio).toEqual({ texto: "software" });
  });

  it("conserva el cero, que es un filtro legítimo", () => {
    expect(limpiarFiltro({ valorMin: 0 })).toEqual({ valorMin: 0 });
  });

  it("conserva «solo TI» desactivado", () => {
    // Es la diferencia entre buscar todo y buscar solo tecnología: si se
    // descartara por ser falso, desmarcar la casilla no tendría efecto.
    expect(limpiarFiltro({ soloTi: false })).toEqual({ soloTi: false });
  });

  it("no modifica el filtro original", () => {
    const original = { texto: "", limite: 30 };
    limpiarFiltro(original);
    expect(original).toEqual({ texto: "", limite: 30 });
  });
});

describe("matriz de cumplimiento", () => {
  it("pone primero lo que no cumple", () => {
    const matriz = [
      item({ requisitoId: "A", estado: "cumple" }),
      item({ requisitoId: "B", estado: "no_cumple" }),
      item({ requisitoId: "C", estado: "no_evaluable" }),
      item({ requisitoId: "D", estado: "cumple_parcial" }),
    ];

    expect(ordenarPorSeveridad(matriz).map((i) => i.requisitoId)).toEqual([
      "B",
      "D",
      "C",
      "A",
    ]);
  });

  it("no altera el arreglo recibido", () => {
    const matriz = [
      item({ requisitoId: "A", estado: "cumple" }),
      item({ requisitoId: "B", estado: "no_cumple" }),
    ];

    ordenarPorSeveridad(matriz);

    expect(matriz.map((i) => i.requisitoId)).toEqual(["A", "B"]);
  });

  it("manda al final un estado que no reconoce", () => {
    const matriz = [
      item({ requisitoId: "X", estado: "inventado" as never }),
      item({ requisitoId: "B", estado: "no_cumple" }),
    ];

    expect(ordenarPorSeveridad(matriz)[0].requisitoId).toBe("B");
  });

  it("cuenta por estado y omite los que no aparecen", () => {
    const conteo = contarPorEstado([
      item({ estado: "cumple" }),
      item({ estado: "cumple" }),
      item({ estado: "no_cumple" }),
    ]);

    expect(conteo).toEqual([
      { estado: "no_cumple", total: 1 },
      { estado: "cumple", total: 2 },
    ]);
  });
});
