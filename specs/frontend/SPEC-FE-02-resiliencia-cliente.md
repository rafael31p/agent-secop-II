# SPEC-FE-02 · Fronteras de error, timeouts, cancelación y persistencia

| | |
|---|---|
| **Estado** | Propuesta |
| **Prioridad** | 🔴 Alta |
| **Cierra** | FE-C1, FE-C2, FE-M11, FE-M14 |
| **Depende de** | Los puntos 1.7–1.8 van en la **fase 1**; el resto tras SPEC-FE-01 |
| **Esfuerzo** | 3–5 jornadas |

---

## 1. Problema

### 1.1 Una excepción de render se lleva la sesión entera (FE-C1)

No existe ningún `error.tsx`, `global-error.tsx`, `loading.tsx` ni `not-found.tsx` en `app/`,
ni ningún `<ErrorBoundary>`. El App Router ofrece fronteras de error por segmento
precisamente para esto.

Sin ellas, cualquier excepción durante el render sustituye toda la interfaz por la pantalla
genérica de Next y **el espacio de trabajo se pierde**: el pliego cargado, el análisis pagado
y la propuesta generada.

Lo que eleva el riesgo de teórico a real: buena parte de lo que se renderiza viene de un
modelo de lenguaje, es decir, de una fuente que no cumple contratos de forma determinista. El
código ya lo reconoce a medias —`VARIANTE_VEREDICTO[resultado.veredicto] ?? "neutro"` se
defiende de una clave inesperada— pero esa defensa es puntual y no cubre, por ejemplo, un
campo que llegue como objeto donde se espera una cadena.

### 1.2 Ninguna llamada tiene límite de tiempo (FE-C2)

En `lib/api.ts`, la función `pedir` no pasa `AbortSignal` en ninguna de sus siete
operaciones. Solo `chatStream` lo acepta, y solo `Consultar.tsx` lo usa.

El backend puede tardar legítimamente cinco minutos por llamada, y hasta media hora en
`validarPropuesta` con dos llamadas encadenadas (`BE-A11`). Durante todo ese tiempo el
usuario ve un botón girando: sin cancelar, sin estimación, sin saber si sigue vivo. La salida
natural es recargar —que en `Analizar` y `Validar` es justo lo que no debe hacer, porque el
texto del área de texto no está persistido hasta que pulsa el botón—.

Tampoco hay reintento para lecturas idempotentes: si `/api/proveedores` falla al arrancar por
una carrera con el backend, el selector queda en error hasta que el usuario pulse
«Reintentar» a mano.

### 1.3 La persistencia puede romperse en silencio (FE-M11)

```tsx
useEffect(() => {
  if (!restaurado.current) return;
  try { sessionStorage.setItem(CLAVE_ESPACIO, JSON.stringify(datos)); }
  catch { /* Cuota llena … */ }
}, [datos]);
```

La instantánea incluye `textoPliego` (hasta 800 000 caracteres), el análisis completo y la
propuesta completa, y se reserializa entera en cada cambio. Con un pliego grande se supera la
cuota de ~5 MB; el `catch` lo absorbe y **la persistencia deja de funcionar sin que nadie se
entere**. El usuario recarga y pierde todo — exactamente lo que el mecanismo existía para
evitar.

### 1.4 `descargar()` tiene dos defectos de compatibilidad (FE-M14)

```tsx
const enlace = document.createElement("a");
enlace.href = url;
enlace.download = `propuesta-….md`;
enlace.click();
URL.revokeObjectURL(url);
```

El ancla nunca se añade al documento —Firefox históricamente ignora el `click()` sobre un
elemento desconectado— y la URL del blob se revoca de forma síncrona inmediatamente después
del `click()`, lo que puede cancelar la descarga antes de que empiece. Fallo intermitente y
dependiente del navegador: el peor tipo para diagnosticar desde el reporte de un usuario.

---

## 2. Decisión

1. **Fronteras de error por segmento**, que recuperan en vez de reiniciar.
2. **Todo lo que sale a la red tiene plazo y se puede cancelar**, garantizado por la firma
   del puerto (`SPEC-FE-01` §3.2) y por `useAsyncAction`.
3. **La persistencia falla de forma visible**, con el pliego almacenado aparte y con
   recorte.
4. **Reintento solo en lecturas idempotentes**; nunca en operaciones que cuestan dinero.

Esa última decisión merece énfasis: reintentar automáticamente un análisis fallido duplica el
gasto sin que el usuario lo pida. El reintento de las operaciones caras es un botón, no un
comportamiento.

---

## 3. Diseño

### 3.1 Fronteras de error

```tsx
// app/error.tsx  — captura errores de cualquier ruta sin perder el layout
"use client";

export default function ErrorDeRuta({
  error, reset,
}: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <Aviso tipo="error">
      <h2>Algo falló al mostrar esta vista</h2>
      <p>
        Tu trabajo sigue guardado: el pliego, el análisis y la propuesta se conservan en
        esta pestaña.
      </p>
      <div className="fila">
        <button className="principal" onClick={reset}>Reintentar</button>
        <Link className="boton" href="/">Volver a la búsqueda</Link>
      </div>
      {error.digest && <p className="tenue mono">Referencia: {error.digest}</p>}
    </Aviso>
  );
}
```

La primera frase es la que importa. `error.tsx` vive **dentro** del layout, así que el
espacio de trabajo —que cuelga del layout raíz— sobrevive. Decírselo al usuario convierte un
susto en una molestia.

```tsx
// app/global-error.tsx — el layout mismo falló; aquí el espacio SÍ se pierde de memoria,
// pero sessionStorage lo conserva y se restaura al recargar.
```

Y `app/not-found.tsx` para rutas inexistentes, más `loading.tsx` por segmento para que la
navegación no parezca congelada.

### 3.2 Plazos por operación

```ts
export const PLAZOS = {
  salud: 5_000,
  proveedores: 10_000,
  buscarProcesos: 30_000,
  obtenerProceso: 15_000,
  cargarDocumento: 60_000,     // un PDF grande tarda
  priorizarProcesos: 120_000,
  analizarPliego: 180_000,
  generarPropuesta: 180_000,
  validarPropuesta: 300_000,   // puede encadenar dos llamadas al modelo
  chat: 300_000,               // por inactividad, no total: el streaming es largo por diseño
} as const;
```

Los plazos son **mayores** que los presupuestos del backend (`SPEC-BE-02`) a propósito: el
servidor debe ser quien corte, con un mensaje útil. El plazo del cliente es la red de
seguridad para cuando el servidor no responde en absoluto.

El chat es distinto: no se puede acotar el total de una respuesta larga. Se mide **tiempo sin
recibir fragmento**; si pasan 60 s sin un `delta`, se corta.

```ts
class TimeoutError extends ErrorApi {
  constructor(operacion: string) {
    super(
      `La operación «${operacion}» superó el tiempo de espera. El servidor puede estar ` +
      `saturado. Tu trabajo no se ha perdido: reintenta o prueba con otro proveedor.`,
      408,
    );
  }
}
```

### 3.3 Cancelar es una acción de primera clase

Toda operación larga expone un botón de cancelar, no solo el chat:

```tsx
<button className="principal" onClick={() => analizar(entrada)} disabled={pendiente || !suficiente}>
  {pendiente ? <Cargando texto="Analizando pliego…" /> : "Analizar requisitos"}
</button>
{pendiente && (
  <button className="secundario" onClick={cancelar}>Cancelar</button>
)}
```

Viene gratis de `useAsyncAction`, que ya gestiona el `AbortController`. Es una línea por
vista, no un mecanismo nuevo.

### 3.4 Reintento acotado, solo para lecturas

```ts
export async function conReintento<T>(
  operacion: (señal: AbortSignal) => Promise<T>,
  señal: AbortSignal,
  intentos = 3,
): Promise<T> {
  let ultimo: unknown;
  for (let i = 1; i <= intentos; i++) {
    try {
      return await operacion(señal);
    } catch (e) {
      ultimo = e;
      if (señal.aborted) throw e;
      if (!esTransitorio(e) || i === intentos) throw e;
      await esperar(Math.min(500 * 2 ** (i - 1), 4_000), señal);
    }
  }
  throw ultimo;
}

// Transitorio = fallo de red (estado 0) o 502/503/504. Nunca 4xx.
```

Se aplica **solo** a `salud`, `proveedores` y `obtenerProceso`. Nunca a análisis, propuesta,
validación ni chat: cada reintento de esas es una factura.

Un 429 del límite de tasa (`SPEC-BE-06`) tampoco se reintenta automáticamente: se traduce a
un aviso con la hora de restablecimiento leída de `Retry-After`.

### 3.5 Persistencia que falla en voz alta

Tres cambios sobre `lib/estado.tsx`:

**Uno — el pliego se guarda aparte y se recorta.**

```ts
const LIMITE_PLIEGO_PERSISTIDO = 300_000;

// El pliego es el 95 % del tamaño y el que cambia menos. Guardarlo en su propia clave
// evita reserializar 800 000 caracteres cada vez que cambia el análisis.
almacen.guardar(CLAVE_PLIEGO, recortar(datos.textoPliego, LIMITE_PLIEGO_PERSISTIDO));
almacen.guardar(CLAVE_ESPACIO, JSON.stringify(sinPliego(datos)));
```

**Dos — se escribe con retardo, no en cada cambio.**

```ts
const guardarDiferido = useDebouncedCallback(guardar, 800);
```

**Tres — el fallo se ve.**

```ts
catch (e) {
  if (esCuotaExcedida(e)) {
    setDegradacion(
      "El pliego es demasiado grande para guardarlo en esta pestaña. Si recargas, " +
      "tendrás que volver a cargarlo. El trabajo en curso no se ha perdido.",
    );
  }
}
```

Ese aviso es el corazón del cambio. Un mecanismo de seguridad que falla en silencio es peor
que no tenerlo, porque el usuario confía en él. El aviso se muestra una vez, es descartable y
no bloquea nada.

**Sobre el contenido almacenado:** un pliego y una propuesta comercial quedan en
`sessionStorage`/`localStorage` del navegador. `SPEC-FE-03` añade la acción de borrado que
hoy no existe (`FE-M12`) y `SPEC-NT-02` documenta la decisión.

### 3.6 Descarga corregida

```ts
function descargarTexto(contenido: string, nombre: string, tipo: string) {
  const url = URL.createObjectURL(new Blob([contenido], { type: tipo }));
  const enlace = document.createElement("a");
  enlace.href = url;
  enlace.download = nombre;
  enlace.style.display = "none";
  document.body.appendChild(enlace);     // Firefox ignora el click en un nodo desconectado
  enlace.click();
  // Revocar de forma síncrona puede cancelar la descarga antes de que empiece.
  setTimeout(() => {
    URL.revokeObjectURL(url);
    enlace.remove();
  }, 0);
}
```

Se extrae a `dominio/descarga.ts` y se usa desde `Proponer` y desde la exportación de la
matriz que introduce `SPEC-FE-03`.

### 3.7 Cuando el backend no está

`Pie.tsx` ya detecta el backend caído y lo dice — es un buen comportamiento que conviene
conservar y ampliar: cuando `salud` falla tras los reintentos, las vistas deshabilitan sus
botones de acción con un `title` explicativo, en lugar de dejar que el usuario escriba un
pliego entero y descubra el problema al pulsar «Analizar».

---

## 4. Plan de ejecución

| Paso | Contenido | Fase |
|---|---|---|
| 1 | `error.tsx`, `global-error.tsx`, `not-found.tsx`, `loading.tsx`. | **1** |
| 2 | Plazos + cancelación en todas las operaciones (con `useAsyncAction` o, si aún no existe, pasando `AbortSignal` a `pedir`). | **1** |
| 3 | Botón «Cancelar» en las cuatro operaciones largas. | **1** |
| 4 | Corregir `descargar()`. | **1** |
| 5 | Reintento en las tres lecturas idempotentes. | 4 |
| 6 | Persistencia: clave separada, retardo, aviso de degradación. | 4 |
| 7 | Deshabilitar acciones con el backend caído. | 4 |

Los pasos 1–4 son de fase 1 y no dependen de `SPEC-FE-01`: se pueden hacer sobre el código
actual. Los pasos 2 y 3 quedan más limpios después, pero esperar no compensa: hoy no hay
forma de cancelar nada.

---

## 5. Criterios de aceptación

1. Una excepción lanzada durante el render de cualquier vista muestra `error.tsx` **con el
   espacio de trabajo intacto**, verificado por prueba.
2. Ninguna llamada de red puede quedar pendiente indefinidamente; hay una prueba por
   operación con un servidor que no responde.
3. Las cuatro operaciones largas tienen botón de cancelar, y cancelar no deja estado
   inconsistente ni muestra un error.
4. Desmontar una vista con una operación en curso la aborta.
5. `proveedores` reintenta hasta tres veces ante fallo de red; `analizarPliego` **no
   reintenta nunca** — hay una prueba que lo afirma contando llamadas.
6. Superar la cuota de almacenamiento muestra un aviso visible; existe prueba con un
   `Storage` simulado que lanza `QuotaExceededError`.
7. El pliego se persiste en su propia clave y no se reserializa al cambiar el análisis.
8. La descarga funciona en Firefox y Safari (verificación manual documentada).
9. Un 429 se traduce a un aviso con la hora de restablecimiento, no a un error genérico.

---

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| Los plazos cortan operaciones legítimas lentas. | Se fijan por encima de los presupuestos del backend; el servidor corta primero con mejor mensaje. Son constantes en un único archivo, fáciles de ajustar. |
| Recortar el pliego persistido pierde contenido al recargar. | Solo afecta a lo persistido, no a la sesión en curso, y el recorte se avisa. La alternativa —fallar en silencio— es peor. |
| El retardo en el guardado pierde los últimos 800 ms al cerrar. | Se fuerza el guardado en `visibilitychange`. |
| Reintentar `obtenerProceso` multiplica carga sobre Socrata. | Tres intentos con retroceso y solo ante fallo transitorio; la caché del backend (`SPEC-BE-04`) absorbe el resto. |

---

## 7. Fuera de alcance

- Modo sin conexión y service workers.
- Sincronización entre pestañas.
- Reanudar un análisis interrumpido: requiere trabajos asíncronos en el backend, fuera del
  alcance de `SPEC-BE-02`.
- Persistencia en servidor del espacio de trabajo (implicaciones en `SPEC-NT-02`).
