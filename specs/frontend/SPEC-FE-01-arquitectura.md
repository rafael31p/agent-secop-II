# SPEC-FE-01 · Arquitectura hexagonal en el cliente

| | |
|---|---|
| **Estado** | Propuesta |
| **Prioridad** | 🟠 Media |
| **Cierra** | FE-A9, FE-A10, FE-B16, FE-M15 (parcial) |
| **Depende de** | Fase 0 (ESLint) |
| **Esfuerzo** | 4–6 jornadas |
| **Módulo** | `frontend-next/` |

---

## 1. Problema

### 1.1 `lib/` mezcla capas

`lib/api.ts` reúne tres cosas: el transporte HTTP (`pedir`, cabeceras, manejo de errores), el
catálogo de endpoints (el objeto `api`) y el análisis del protocolo SSE (`chatStream`,
`interpretarBloque`). Las cuatro vistas y `Pie.tsx` importan `api` directamente.

Consecuencia inmediata: probar una vista exige simular `fetch` global, que es lo que hace
`pruebas/ayudas.tsx`. Las pruebas quedan atadas a detalles de transporte —forma del cuerpo,
cabeceras, códigos de estado— en vez de al comportamiento de la vista. Cambiar de `fetch` a
otro mecanismo obligaría a reescribir pruebas de componentes que no deberían enterarse.

Lo que **sí** está bien y hay que extender: `lib/formato.ts`, `lib/filtros.ts` y
`lib/matriz.ts` son funciones puras sin React, probadas por separado. Ese es exactamente el
modelo.

### 1.2 El mismo patrón asíncrono, siete veces (FE-A9)

`Buscar`, `Analizar`, `Proponer` y `Validar` repiten:

```tsx
const [cargando, setCargando] = useState(false);
const [error, setError] = useState<string | null>(null);
try { … } catch (excepcion) {
  setError(excepcion instanceof ErrorApi ? excepcion.message : String(excepcion));
} finally { setCargando(false); }
```

Esa línea del `catch` aparece **siete veces idéntica**. El bloque de subida de archivo
—input oculto, ref, botón, limpieza del `value`— está duplicado literalmente entre
`Analizar.tsx` y `Validar.tsx`.

No es la repetición de líneas lo que duele, es la repetición de **decisión**: el timeout, la
cancelación, el reintento y el `aria-busy` que piden `SPEC-FE-02` y `SPEC-FE-03` habría que
añadirlos en siete sitios y mantenerlos sincronizados.

### 1.3 Código muerto

`api.obtenerProceso` está definido y no se usa en ninguna parte (`FE-B16`). `estado.limpiar`
está definido y nunca se invoca (`FE-M12`).

### 1.4 Sin analizador estático ni cabeceras de seguridad

No hay ESLint —`next lint` desapareció en Next 16 y nadie lo reemplazó—, ni Prettier. Y
`next.config.ts` no define `headers()`, ni `poweredByHeader: false`, ni política de seguridad
de contenido; en una aplicación que renderiza texto generado por un modelo, esa red de
contención vale.

---

## 2. Decisión

Aplicar el mismo principio que en el backend, adaptado a React: **el componente depende de un
caso de uso, no de un transporte.**

```
componentes/  →  aplicacion/ (hooks)  →  puertos  →  infraestructura/
                        ↓
                    dominio/ (tipos + funciones puras)
```

Los puertos se inyectan por contexto de React, no se importan. Eso es lo que permite que una
prueba monte una vista con una pasarela falsa en lugar de parchear `fetch`.

---

## 3. Diseño

### 3.1 Estructura objetivo

```
frontend-next/
├── app/                        rutas, layouts, error.tsx, loading.tsx
├── componentes/                presentación pura, sin llamadas de red
│   ├── vistas/
│   └── ui/                     sistema de componentes (SPEC-FE-04)
├── dominio/
│   ├── tipos.ts                alias sobre el esquema generado (SPEC-FE-05)
│   ├── matriz.ts               ordenarPorSeveridad, contarPorEstado   ← ya existe
│   ├── filtros.ts              limpiarFiltro                           ← ya existe
│   ├── formato.ts              COP, fechas, números                    ← ya existe
│   └── contexto.ts             construirContexto (hoy dentro de Consultar.tsx)
├── aplicacion/
│   ├── puertos.ts              AgentGateway, ChatStream, WorkspaceStorage
│   ├── useAsyncAction.ts       estado + error + timeout + cancelación
│   ├── useBuscarProcesos.ts    un hook por caso de uso
│   ├── useAnalizarPliego.ts
│   ├── useGenerarPropuesta.ts
│   ├── useValidarPropuesta.ts
│   ├── useConsultarAgente.ts
│   └── useEspacioTrabajo.ts    reducer del espacio (SPEC-FE-02)
└── infraestructura/
    ├── HttpAgentGateway.ts     implementa AgentGateway con fetch
    ├── SseChatStream.ts        implementa ChatStream
    ├── BrowserStorage.ts       implementa WorkspaceStorage
    └── ProveedorDependencias.tsx  inyecta las implementaciones por contexto
```

### 3.2 El puerto

```ts
// aplicacion/puertos.ts
export interface AgentGateway {
  buscarProcesos(filtro: FiltroProcesos, señal?: AbortSignal): Promise<RespuestaProcesos>;
  obtenerProceso(id: string, señal?: AbortSignal): Promise<ProcesoResumen>;
  priorizarProcesos(peticion: PeticionRelevancia, señal?: AbortSignal): Promise<RespuestaRelevancia>;
  analizarPliego(peticion: PeticionAnalisis, señal?: AbortSignal): Promise<RespuestaAnalisis>;
  cargarDocumento(archivo: File, señal?: AbortSignal): Promise<RespuestaDocumento>;
  generarPropuesta(peticion: PeticionPropuesta, señal?: AbortSignal): Promise<RespuestaPropuesta>;
  validarPropuesta(peticion: PeticionValidacion, señal?: AbortSignal): Promise<RespuestaValidacion>;
  proveedores(señal?: AbortSignal): Promise<ProveedorDisponible[]>;
  salud(señal?: AbortSignal): Promise<EstadoSalud>;
}

export interface ChatStream {
  abrir(
    peticion: PeticionChat,
    alFragmento: (texto: string) => void,
    señal: AbortSignal,
  ): Promise<string>;
}
```

**El `AbortSignal` es obligatorio en la firma del puerto.** Hoy solo `chatStream` lo acepta;
ponerlo en el contrato hace imposible añadir una operación sin cancelación, que es la causa
raíz de `FE-C2`. Un detalle de firma que cierra una clase entera de defectos.

`obtenerProceso` se conserva —hoy es código muerto— porque `SPEC-FE-03` introduce la ruta
`/procesos/[id]` que lo usa. Si esa ruta no se hiciera, se elimina.

### 3.3 Inyección por contexto

```tsx
// infraestructura/ProveedorDependencias.tsx
const Contexto = createContext<Dependencias | null>(null);

export function ProveedorDependencias({
  children,
  gateway = new HttpAgentGateway(BASE),
  chat = new SseChatStream(BASE),
  almacen = new BrowserStorage(),
}: Props) {
  const valor = useMemo(() => ({ gateway, chat, almacen }), [gateway, chat, almacen]);
  return <Contexto.Provider value={valor}>{children}</Contexto.Provider>;
}

export function useDependencias(): Dependencias {
  const ctx = useContext(Contexto);
  if (!ctx) throw new Error("useDependencias debe usarse dentro de <ProveedorDependencias>");
  return ctx;
}
```

Los valores por defecto en los parámetros son lo que hace que producción no necesite
configuración y las pruebas no necesiten `vi.mock`:

```tsx
render(
  <ProveedorDependencias gateway={new GatewayFalso({ analizarPliego: () => ANALISIS })}>
    <Analizar />
  </ProveedorDependencias>,
);
```

La prueba deja de saber que existe HTTP.

### 3.4 `useAsyncAction`: donde vive la decisión una sola vez

```ts
export interface EstadoAsync<T> {
  datos: T | null;
  pendiente: boolean;
  error: ErrorApi | null;
  ejecutar: (...args: unknown[]) => Promise<T | null>;
  cancelar: () => void;
  reiniciar: () => void;
}

export function useAsyncAction<A extends unknown[], T>(
  operacion: (señal: AbortSignal, ...args: A) => Promise<T>,
  opciones: { timeoutMs?: number } = {},
): EstadoAsync<T> {
  const [datos, setDatos] = useState<T | null>(null);
  const [pendiente, setPendiente] = useState(false);
  const [error, setError] = useState<ErrorApi | null>(null);
  const controlador = useRef<AbortController | null>(null);
  const montado = useMontado();

  const ejecutar = useCallback(async (...args: A) => {
    controlador.current?.abort();                 // una operación a la vez
    const ctrl = new AbortController();
    controlador.current = ctrl;
    const temporizador = opciones.timeoutMs
      ? setTimeout(() => ctrl.abort(new TimeoutError()), opciones.timeoutMs)
      : null;

    setPendiente(true);
    setError(null);
    try {
      const resultado = await operacion(ctrl.signal, ...args);
      if (montado.current && !ctrl.signal.aborted) setDatos(resultado);
      return resultado;
    } catch (e) {
      // Cancelar es una decisión del usuario, no un error que reportar.
      if (!ctrl.signal.aborted && montado.current) setError(comoErrorApi(e));
      return null;
    } finally {
      if (temporizador) clearTimeout(temporizador);
      if (montado.current) setPendiente(false);
      if (controlador.current === ctrl) controlador.current = null;
    }
  }, [operacion, opciones.timeoutMs]);

  useEffect(() => () => controlador.current?.abort(), []);   // aborta al desmontar
  return { datos, pendiente, error, ejecutar, cancelar, reiniciar };
}
```

Este hook concentra siete decisiones que hoy están dispersas o ausentes: estado de carga,
traducción de error, timeout, cancelación explícita, cancelación al desmontar, protección
contra escritura tras desmontaje y descarte de la operación anterior. `SPEC-FE-02` y
`SPEC-FE-03` construyen sobre él en lugar de tocar cuatro vistas.

### 3.5 Un hook por caso de uso

```ts
export function useAnalizarPliego() {
  const { gateway } = useDependencias();
  const espacio = useEspacioTrabajo();
  const ia = useSeleccionIA();

  const accion = useAsyncAction(
    (señal, entrada: EntradaAnalisis) =>
      gateway.analizarPliego({ ...entrada, ...ia.seleccion }, señal),
    { timeoutMs: 180_000 },
  );

  const analizar = useCallback(async (entrada: EntradaAnalisis) => {
    const analisis = await accion.ejecutar(entrada);
    if (analisis) espacio.fijarAnalisis(analisis);
    return analisis;
  }, [accion, espacio]);

  return { ...accion, analizar };
}
```

`Analizar.tsx` pasa de gestionar cinco `useState` y un `try/catch` a consumir esto. La vista
vuelve a ser presentación, que es lo único que un componente debería ser.

### 3.6 ESLint, Prettier y cabeceras

```js
// eslint.config.mjs
export default [
  ...next, ...typescriptStrict,
  {
    rules: {
      "@typescript-eslint/no-floating-promises": "error",   // los `void enviar(...)` actuales
      "react-hooks/exhaustive-deps": "error",
      "no-restricted-imports": ["error", {
        patterns: [{
          group: ["**/infraestructura/*"],
          message: "Los componentes dependen de puertos, no de implementaciones. " +
                   "Usa useDependencias().",
        }],
      }],
    },
  },
];
```

La regla `no-restricted-imports` es el equivalente de ArchUnit en el frontend: sin ella, la
separación en carpetas es una sugerencia.

```ts
// next.config.ts
const config: NextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  images: { unoptimized: true },
  async headers() {
    return [{
      source: "/:path*",
      headers: [
        { key: "X-Content-Type-Options", value: "nosniff" },
        { key: "Referrer-Policy", value: "no-referrer" },
        { key: "X-Frame-Options", value: "DENY" },
        { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
      ],
    }];
  },
};
```

La CSP se añade cuando `SPEC-FE-03` introduzca el render de Markdown, que es cuando empieza a
proteger de algo real.

---

## 4. Plan de migración

| Paso | Contenido | Riesgo |
|---|---|---|
| 1 | ESLint + Prettier, en modo aviso. Arreglar lo que salga. | Bajo |
| 2 | Mover `formato`, `filtros`, `matriz` a `dominio/`; extraer `construirContexto`. | Bajo |
| 3 | Declarar `puertos.ts`; `HttpAgentGateway`/`SseChatStream` envolviendo el `lib/api.ts` actual. | Bajo |
| 4 | `ProveedorDependencias` en el layout; migrar `Pie.tsx` (el consumidor más simple). | Bajo |
| 5 | `useAsyncAction` con sus pruebas. | Medio |
| 6 | Un hook de caso de uso por vista; migrar las cuatro vistas una a una. | **Medio-alto** |
| 7 | Reescribir las pruebas contra la pasarela falsa en vez de contra `fetch`. | Medio |
| 8 | `no-restricted-imports` a `error`; borrar `lib/api.ts`. | Bajo |

El paso 7 conviene hacerlo **después** del 6, no a la vez: primero se mueve el código con las
pruebas antiguas como red, luego se mejoran las pruebas.

---

## 5. Criterios de aceptación

1. Ningún componente de `componentes/` importa de `infraestructura/`, verificado por ESLint.
2. Ninguna prueba de componente parchea `fetch` global; todas inyectan una pasarela falsa.
3. Las 64 pruebas siguen pasando durante toda la migración.
4. La línea `excepcion instanceof ErrorApi ? … : String(excepcion)` aparece **una sola vez**
   en el código.
5. Toda operación de red acepta `AbortSignal`, garantizado por la firma del puerto.
6. `useAsyncAction` tiene pruebas de: timeout, cancelación manual, cancelación al desmontar y
   descarte de la operación anterior.
7. ESLint pasa sin avisos; `no-floating-promises` está activo.
8. No queda código muerto: `obtenerProceso` se usa o se elimina.
9. `npm run build` produce las cabeceras de seguridad, verificado con una petición real.

---

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| La inyección por contexto se percibe como ceremonia innecesaria. | El beneficio se cobra en el paso 7: las pruebas se simplifican mucho. Si tras migrar dos vistas no se nota, conviene parar y reconsiderar en vez de terminar por inercia. |
| `useAsyncAction` se vuelve un hook que hace de todo. | Su alcance es una operación asíncrona con cancelación. Estado del espacio y lógica de negocio quedan fuera, en los hooks de caso de uso. |
| Migrar las cuatro vistas a la vez genera un diff irrevisable. | Una vista por commit, con las pruebas existentes en verde en cada uno. |
| `exhaustive-deps` como error destapa muchos avisos. | Se activa en el paso 1 en modo aviso y se limpia antes de subirlo a error. |

---

## 7. Fuera de alcance

- Cambiar de framework o introducir gestión de estado externa (Redux, Zustand). Contexto +
  reducer bastan para este tamaño.
- React Query. Tentador para caché y reintentos, pero `useAsyncAction` cubre lo que hace
  falta sin una dependencia más; se reconsidera si aparecen necesidades de invalidación.
- Renderizado en servidor de las vistas. Son intrínsecamente interactivas y dependen de un
  espacio de trabajo del navegador.
