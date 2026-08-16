import type { NextConfig } from "next";

/**
 * El frontend habla con el backend Quarkus por CORS (ya autorizado en
 * `application.properties` para el puerto 3000), no por un proxy de desarrollo.
 *
 * El motivo es el chat: llega por Server-Sent Events y un proxy intermedio puede
 * almacenar la respuesta en búfer y romper el efecto de escritura progresiva. Con
 * llamada directa el navegador recibe cada trozo tal como lo emite el servidor.
 *
 * La URL del backend se configura en `NEXT_PUBLIC_API_URL` (ver `.env.local.example`).
 */
const config: NextConfig = {
  reactStrictMode: true,
  // El agente no sirve imágenes; se desactiva el optimizador para no arrastrar
  // dependencias nativas innecesarias en el despliegue.
  images: { unoptimized: true },
};

export default config;
