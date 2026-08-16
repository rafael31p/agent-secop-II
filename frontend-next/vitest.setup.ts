import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach, vi } from "vitest";

/**
 * Enrutador simulado.
 *
 * Los componentes navegan con `useRouter().push()` y se resaltan con
 * `usePathname()`. En pruebas no hay servidor de Next, así que se sustituye el
 * módulo por un doble cuyo estado exponemos en `enrutadorDePrueba` para poder
 * afirmar sobre la navegación y fijar la ruta activa.
 */
export const enrutadorDePrueba = {
  push: vi.fn(),
  replace: vi.fn(),
  prefetch: vi.fn(),
  back: vi.fn(),
  ruta: "/",
};

vi.mock("next/navigation", () => ({
  useRouter: () => enrutadorDePrueba,
  usePathname: () => enrutadorDePrueba.ruta,
  useSearchParams: () => new URLSearchParams(),
}));

// jsdom no implementa el desplazamiento; el hilo del chat lo usa para seguir la
// respuesta según se escribe. Basta con que exista y no haga nada.
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = function scrollIntoView() {};
}

beforeEach(() => {
  enrutadorDePrueba.push.mockClear();
  enrutadorDePrueba.replace.mockClear();
  enrutadorDePrueba.ruta = "/";
  localStorage.clear();
  sessionStorage.clear();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
