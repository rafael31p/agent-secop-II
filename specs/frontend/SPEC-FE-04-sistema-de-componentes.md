# SPEC-FE-04 · Sistema de componentes reutilizables

| | |
|---|---|
| **Estado** | Propuesta |
| **Prioridad** | 🟠 Media |
| **Cierra** | FE-A9 (parte de presentación), FE-M15 (parcial) |
| **Depende de** | SPEC-FE-01, SPEC-FE-03 |
| **Esfuerzo** | 3–4 jornadas |

---

## 1. Problema

`componentes/comunes.tsx` ya es un sistema de componentes incipiente y bastante bueno:
`Tarjeta`, `Aviso`, `Etiqueta`, `Cargando`, `Vacio`, `Medidor` y las etiquetas semánticas de
criticidad, cumplimiento y riesgo. Las tres tablas de variantes
(`VARIANTE_CRITICIDAD`, `VARIANTE_CUMPLIMIENTO`, `VARIANTE_RIESGO`) mapean valor de dominio a
color en un solo sitio, que es exactamente lo correcto.

Lo que falta es que las vistas lo usen para todo lo repetido. Hoy hay cuatro patrones
duplicados que deberían ser componentes:

**Uno — el cargador de documento.** El bloque input oculto + `useRef` + botón + limpieza del
`value` está duplicado literalmente entre `Analizar.tsx` y `Validar.tsx`, con su estado
`subiendo` y su manejo de error.

**Dos — la tabla con contenedor de desplazamiento.** El patrón
`<div className="contenedor-tabla"><table>…` se repite en `Buscar` (priorización), `Analizar`
(requisitos) y `Validar` (matriz), cada uno con su propia cabecera a mano, sin `caption` y
sin `scope` — el defecto de accesibilidad de `SPEC-FE-03` §3.2, tres veces.

**Tres — el estado de la operación.** Cada vista decide a mano cómo mostrar cargando, error
y vacío, con estilos ligeramente distintos.

**Cuatro — estilos en línea.** Hay 23 usos de `style={{…}}` repartidos por las vistas
(`marginTop: "0.9rem"`, `minHeight: 220`, `fontSize: "0.82rem"`…). Son decisiones de diseño
que escapan a `globals.css` y que hacen imposible cambiar el espaciado de forma coherente.

El riesgo concreto: `SPEC-FE-03` añade `aria-busy`, `caption`, `scope`, `aria-sort` y
objetivos táctiles de 44 px. Sin componentes, cada uno hay que ponerlo en tres o cuatro
sitios y mantenerlo sincronizado.

---

## 2. Decisión

Extraer los cuatro patrones a `componentes/ui/`, con la accesibilidad **dentro** del
componente. Un componente que no se puede usar mal es la única forma realista de mantener
WCAG en el tiempo.

No se adopta ninguna biblioteca de componentes. El CSS actual son 638 líneas legibles con
variables y modo oscuro; sustituirlo por Tailwind, MUI o shadcn sería un rediseño con riesgo
de regresión visual, a cambio de resolver un problema que no se tiene.

---

## 3. Diseño

### 3.1 Estructura

```
componentes/
├── ui/
│   ├── Tarjeta.tsx            de comunes.tsx
│   ├── Aviso.tsx              + variante accionable
│   ├── Etiqueta.tsx           + las tres semánticas
│   ├── Medidor.tsx
│   ├── Vacio.tsx
│   ├── AsyncBoundary.tsx      NUEVO — cargando / error / vacío / contenido
│   ├── DataTable.tsx          NUEVO — accesible por construcción
│   ├── CargadorDocumento.tsx  NUEVO — el bloque duplicado
│   ├── BotonAccion.tsx        NUEVO — pendiente + cancelar + aria-busy
│   ├── Markdown.tsx           NUEVO — saneado (SPEC-FE-03 §3.6)
│   └── indice.ts
└── vistas/                    solo composición
```

### 3.2 `AsyncBoundary` — el estado de una operación, una sola vez

```tsx
interface Props<T> {
  estado: EstadoAsync<T>;            // de useAsyncAction (SPEC-FE-01)
  textoCargando: string;
  vacio?: (datos: T) => boolean;
  mensajeVacio?: ReactNode;
  children: (datos: T) => ReactNode;
}

export function AsyncBoundary<T>({ estado, textoCargando, vacio, mensajeVacio, children }: Props<T>) {
  if (estado.pendiente && !estado.datos) {
    return (
      <div role="status" aria-live="polite">
        <Cargando texto={textoCargando} />
      </div>
    );
  }
  if (estado.error) {
    return <AvisoDeError error={estado.error} onReintentar={estado.reiniciar} />;
  }
  if (!estado.datos) return null;
  if (vacio?.(estado.datos)) return <Vacio>{mensajeVacio}</Vacio>;
  return <>{children(estado.datos)}</>;
}
```

`AvisoDeError` es donde vive el mapeo de código de estado a acción de `SPEC-FE-03` §3.9 —503
al selector de proveedor, 429 con la hora de restablecimiento, 408 con reintentar—. Se
escribe una vez y aplica en las siete operaciones.

Uso:

```tsx
<AsyncBoundary
  estado={validacion}
  textoCargando="Validando cumplimiento…"
  vacio={(r) => r.matriz.length === 0}
  mensajeVacio="La validación no devolvió ítems."
>
  {(resultado) => <MatrizCumplimiento resultado={resultado} />}
</AsyncBoundary>
```

### 3.3 `DataTable` — accesible por construcción

```tsx
interface Columna<T> {
  clave: string;
  encabezado: ReactNode;
  ancho?: string;
  ordenable?: boolean;
  celda: (fila: T) => ReactNode;
}

interface Props<T> {
  titulo: string;                 // se convierte en <caption>, obligatorio
  columnas: Columna<T>[];
  filas: T[];
  claveDeFila: (fila: T, indice: number) => string;
  orden?: { clave: string; direccion: "asc" | "desc" };
  onOrdenar?: (clave: string) => void;
}
```

El componente genera `<caption>` (visualmente oculto si hace falta), `scope="col"` en cada
`<th>`, `aria-sort` en las ordenables y el contenedor con desplazamiento y `tabindex={0}`
—un contenedor desplazable debe ser alcanzable con teclado, algo que hoy falta en las tres
tablas—. Por debajo de 600 px conmuta a tarjetas apiladas con `data-label` desde el
encabezado.

**El parámetro `titulo` es obligatorio a propósito.** Es la forma de garantizar que ninguna
tabla futura se quede sin `caption`: el tipo no compila sin él.

### 3.4 `CargadorDocumento`

```tsx
interface Props {
  etiqueta: string;                          // "Subir PDF / DOCX / TXT"
  etiquetaAccesible: string;                 // "Archivo del pliego"
  onTexto: (documento: RespuestaDocumento) => void;
  onError?: (error: ErrorApi) => void;
}
```

Encapsula el input oculto, la referencia, el reinicio del `value`, el estado `subiendo`, la
llamada a `gateway.cargarDocumento` con su plazo y su cancelación, y **añade lo que hoy no
existe**: zona de arrastrar y soltar, validación de extensión y de tamaño en el cliente
—antes de subir 25 MB para recibir un 415—, y el aviso de truncado.

Elimina la duplicación entre `Analizar` y `Validar` y convierte la mejora de UX en un cambio
de un solo sitio.

### 3.5 `BotonAccion`

```tsx
<BotonAccion
  variante="principal"
  estado={analisis}                    // EstadoAsync
  textoPendiente="Analizando pliego…"
  onClick={() => analizar(entrada)}
  disabled={!suficiente}
  motivoDeshabilitado={`Se requieren al menos ${MINIMO} caracteres`}
>
  Analizar requisitos
</BotonAccion>
```

Gestiona `aria-busy`, el botón «Cancelar» que aparece durante la ejecución
(`SPEC-FE-02` §3.3), el `title` explicativo cuando está deshabilitado y el objetivo táctil
mínimo. Cuatro requisitos de accesibilidad que hoy habría que recordar en cada uso.

### 3.6 Tokens en lugar de estilos en línea

Los 23 `style={{…}}` se sustituyen por utilidades mínimas sobre las variables que ya existen:

```css
:root {
  --espacio-1: .35rem;  --espacio-2: .6rem;  --espacio-3: .9rem;
  --espacio-4: 1.2rem;  --espacio-5: 1.8rem;
  --texto-sm: .82rem;   --texto-base: .92rem;
  --toque-min: 44px;
}
.pila-1 > * + * { margin-top: var(--espacio-1); }
.pila-3 > * + * { margin-top: var(--espacio-3); }
.area-alta { min-height: 220px; }
```

No es un sistema de utilidades completo: son las diez clases que cubren los 23 casos. La
regla de ESLint `react/forbid-dom-props` con `style` en modo aviso evita que vuelvan a
aparecer.

### 3.7 Composición, no configuración

`Tarjeta` recibe hoy `titulo`, `subtitulo`, `acciones` y `children`. Funciona, y si crece el
número de variantes se convierte en componentes compuestos:

```tsx
<Tarjeta>
  <Tarjeta.Cabecera>
    <Tarjeta.Titulo>Matriz de cumplimiento (12)</Tarjeta.Titulo>
    <Tarjeta.Acciones><BotonExportar /></Tarjeta.Acciones>
  </Tarjeta.Cabecera>
  <Tarjeta.Subtitulo>Ordenada por severidad.</Tarjeta.Subtitulo>
  <Tarjeta.Cuerpo>…</Tarjeta.Cuerpo>
</Tarjeta>
```

**No se hace ahora.** Con cuatro usos, la API de propiedades actual es más simple y KISS
manda. Se deja anotado como el camino cuando aparezca la quinta variante, para que la
decisión ya esté pensada cuando toque.

---

## 4. Plan de ejecución

| Paso | Contenido |
|---|---|
| 1 | Mover `comunes.tsx` a `ui/` sin cambios; añadir `indice.ts`. |
| 2 | `AsyncBoundary` + `AvisoDeError` con el mapeo de códigos de estado. |
| 3 | `BotonAccion`; migrar las cuatro vistas. |
| 4 | `DataTable`; migrar las tres tablas. Cierra tres defectos de accesibilidad de golpe. |
| 5 | `CargadorDocumento`; migrar `Analizar` y `Validar`. |
| 6 | `Markdown` saneado (implementa `SPEC-FE-03` §3.6). |
| 7 | Tokens de espaciado; eliminar los estilos en línea. |
| 8 | Pruebas de accesibilidad por componente, no solo por ruta. |

El paso 4 es el de mayor rendimiento: convierte «poner `caption` y `scope` en tres tablas y
acordarse en las futuras» en «es imposible crear una tabla sin ellos».

---

## 5. Criterios de aceptación

1. `componentes/vistas/` no contiene ningún `<table>`, ningún `<input type="file">` ni ningún
   `style={{…}}`.
2. `DataTable` no compila sin `titulo`; toda tabla renderizada tiene `<caption>` y
   `scope="col"`.
3. El bloque de subida de documento existe una sola vez en el código.
4. La línea que traduce un error de API a mensaje de usuario existe una sola vez.
5. Cada componente de `ui/` tiene prueba de accesibilidad con `axe`.
6. `BotonAccion` marca `aria-busy` y ofrece cancelar durante la ejecución, verificado por
   prueba.
7. Las 64 pruebas existentes siguen pasando; las que afirman sobre marcado se ajustan solo
   donde el marcado cambió por accesibilidad.
8. Ningún componente de `ui/` importa de `aplicacion/` ni de `infraestructura/`: reciben
   todo por propiedades.

El criterio 8 es el que mantiene reutilizable el sistema. Un componente de presentación que
llama a un hook de caso de uso deja de ser reutilizable en el momento en que lo hace.

---

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| `DataTable` se vuelve genérico e incomprensible. | Tres usos concretos como guía; si una tabla necesita algo muy distinto, se escribe a mano. Un componente con doce propiedades opcionales es peor que dos componentes. |
| Migrar las tablas rompe pruebas que afirman sobre el marcado. | Se migran de una en una con las pruebas en verde; los cambios de aserción se justifican por accesibilidad, no por conveniencia. |
| `AsyncBoundary` con `children` como función incomoda. | Es el patrón estándar para estado discriminado en React y evita renderizar con datos nulos. |
| El sistema de componentes crece más allá de lo que hace falta. | Solo entra en `ui/` lo que se usa al menos dos veces. Sin componentes «por si acaso». |

---

## 7. Fuera de alcance

- Biblioteca de componentes de terceros.
- Storybook: con diez componentes no compensa el mantenimiento.
- Rediseño visual o cambio de paleta.
- Componentes compuestos para `Tarjeta` (anotado para cuando haya una quinta variante).
