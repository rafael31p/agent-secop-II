import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./", import.meta.url)),
    },
  },
  test: {
    globals: true,
    environment: "jsdom",
    setupFiles: ["./vitest.setup.ts"],
    include: ["pruebas/**/*.test.{ts,tsx}"],
    // Los dobles creados en la fábrica de `vi.mock` viven una vez por archivo,
    // así que sin esto el historial de llamadas se filtra de una prueba a la
    // siguiente. Cuesta encontrarlo: `mock.calls[0]` empieza a devolver la
    // llamada de otra prueba, cuyo componente ya se desmontó. Solo limpia el
    // historial, no las implementaciones que fija cada `beforeEach`.
    clearMocks: true,
    coverage: {
      provider: "v8",
      include: ["lib/**", "componentes/**"],
      reporter: ["text", "html"],
    },
  },
});
