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
    coverage: {
      provider: "v8",
      include: ["lib/**", "componentes/**"],
      reporter: ["text", "html"],
    },
  },
});
