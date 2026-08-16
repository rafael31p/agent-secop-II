# Frontend — Agente SECOP II

Next.js 16 (App Router) + React 19 + TypeScript. Habla con el backend Quarkus de
`../backend-quarkus`.

```bash
npm install
npm run dev        # http://localhost:3000
npm test           # 64 pruebas
npm run typecheck
npm run build
```

El backend tiene que estar corriendo en el puerto 8000. Si está en otro sitio, copia
`.env.local.example` a `.env.local` y ajusta `NEXT_PUBLIC_API_URL`.

## Cómo está organizado

| Carpeta | Qué contiene |
|---|---|
| `app/` | Rutas y layout. Cada paso del flujo es una ruta. |
| `componentes/vistas/` | Las cinco vistas, una por ruta. |
| `componentes/` | Presentación reutilizable y selector de proveedor de IA. |
| `lib/` | Cliente HTTP, tipos del contrato, contextos y lógica pura. |
| `pruebas/` | Vitest + Testing Library. |

## Decisiones que conviene conocer antes de tocar el código

**Los tipos de `lib/tipos.ts` son `camelCase` a propósito.** Son el espejo exacto de los
records del backend Quarkus. La versión anterior de este frontend usaba `snake_case`
porque hablaba con el backend Python; no copies nombres de allí.

**Se llama al backend directo, sin proxy de Next.** Un intermediario puede almacenar en
búfer la respuesta del chat y romper el streaming. El CORS del backend ya autoriza el
puerto 3000.

**Cada paso del flujo es una ruta** (`/`, `/analizar`, `/proponer`, `/validar`,
`/consultar`), no un estado de un componente. El historial del navegador funciona y una
vista se puede compartir por URL.

**El espacio de trabajo se respalda en `sessionStorage`.** Con rutas propias, recargar deja
de ser improbable, y perder el pliego analizado en un F5 sería caro (una llamada al modelo
ya gastada). Vive en el layout, así que sobrevive a la navegación entre rutas; el respaldo
cubre además la recarga. El perfil del oferente va a `localStorage`, porque es del oferente
y no del pliego.

**Todo lo que se restaura de almacenamiento llega después del primer render**, porque en el
servidor no hay `sessionStorage` y leerlo durante el render rompería la hidratación. Por eso
las vistas precargan sus campos en un `useEffect` que no pisa lo que el usuario ya escribió.

**El proveedor y el modelo son nulos por defecto**, y nulo no es lo mismo que «gemini»:
significa «lo que el servidor tenga configurado». Solo se envían cuando el usuario elige.

## Pruebas

Las pruebas cubren lo que puede romperse en silencio: el reensamblado de eventos SSE
partidos entre lecturas, la traducción de errores del backend, la limpieza de filtros
vacíos (un `""` viaja como filtro real y recorta resultados), el orden de la matriz de
cumplimiento por severidad y la persistencia del espacio de trabajo.

El enrutador de Next se sustituye por un doble en `vitest.setup.ts`, que expone
`enrutadorDePrueba` para afirmar sobre la navegación.
