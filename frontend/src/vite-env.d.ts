/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** URL base del backend. Vacío en desarrollo: se usa el proxy de Vite. */
  readonly VITE_API_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
