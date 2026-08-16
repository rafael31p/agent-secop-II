import coreWebVitals from "eslint-config-next/core-web-vitals";
import next from "eslint-config-next/typescript";
import prettier from "eslint-config-prettier";

/**
 * Configuración plana de ESLint.
 *
 * `eslint-config-next` 16 ya publica configuración plana nativa, así que no
 * hace falta el puente `FlatCompat` —que además rompe con ESLint 10—.
 *
 * Arranca **en modo aviso**, a propósito. Introducir un linter sobre código
 * existente con las reglas en «error» produce cientos de fallos el primer día,
 * y la reacción natural es desactivarlo. En aviso cumple su función —señalar—
 * sin bloquear, y las reglas suben a error a medida que se paga la deuda que
 * señalan.
 *
 * `eslint-config-prettier` va al final para apagar las reglas de formato: el
 * formato lo decide Prettier y discutirlo dos veces solo genera conflictos.
 */
const config = [
  {
    ignores: [".next/**", "node_modules/**", "coverage/**", "next-env.d.ts"],
  },

  ...coreWebVitals,
  ...next,
  prettier,

  {
    rules: {
      // Aviso, no error: las fases siguientes del plan tocan estos archivos y
      // limpiarlos ahora mezclaría ruido con los cambios de comportamiento.
      "@typescript-eslint/no-unused-vars": [
        "warn",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
      ],
      "@typescript-eslint/no-explicit-any": "warn",

      // Este sí es error desde el primer día: un `<a>` para navegación interna
      // recarga la página entera y tira el espacio de trabajo en memoria.
      "@next/next/no-html-link-for-pages": "error",

      // Señala 7 puntos, todos del mismo patrón: restaurar en un efecto lo que
      // vive en `sessionStorage` o llega de la red. En una aplicación que
      // renderiza en el servidor ese estado NO existe durante el render —leerlo
      // ahí rompería la hidratación—, así que el efecto es deliberado y está
      // comentado en cada sitio.
      //
      // No por eso es ruido: la regla tiene razón en que el patrón encadena
      // renders. La forma correcta es un reducer con estado inicial resuelto en
      // un solo paso, que es exactamente lo que hacen los puntos 4.2 y 4.3 del
      // plan de mejora. Hasta entonces queda en aviso, visible y contado.
      "react-hooks/set-state-in-effect": "warn",
    },
  },

  {
    // Las pruebas montan dobles y sondas; ahí las reglas de producción estorban
    // más de lo que ayudan.
    files: ["pruebas/**/*.{ts,tsx}", "vitest.setup.ts"],
    rules: {
      "@typescript-eslint/no-explicit-any": "off",
    },
  },
];

export default config;
