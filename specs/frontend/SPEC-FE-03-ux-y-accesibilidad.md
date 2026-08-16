# SPEC-FE-03 · Experiencia de usuario y accesibilidad WCAG 2.2 AA

| | |
|---|---|
| **Estado** | Propuesta |
| **Prioridad** | 🔴 Alta |
| **Cierra** | FE-A3, FE-A4, FE-A5, FE-A6, FE-A7, FE-A8, FE-M12, FE-M13 |
| **Depende de** | Los puntos de accesibilidad van en la **fase 1**; el resto tras SPEC-FE-01 |
| **Esfuerzo** | 5–7 jornadas |

---

## 1. Problema

### 1.1 Nada tiene indicador de foco (FE-A5)

`app/globals.css:244-248` define el contorno de foco **solo** para `input`, `select` y
`textarea`. En 638 líneas no hay ni una regla `:focus-visible`. `button.principal`,
`button.secundario`, `a.boton`, `button.enlace`, los enlaces de navegación y
`details > summary` definen `:hover` y no `:focus`.

Un usuario que navegue con teclado no puede saber dónde está. La aplicación tiene cuatro
pasos de flujo con varios botones cada uno: sin foco visible es inoperable. Incumple **WCAG
2.2 criterio 2.4.7 (nivel AA)**.

Para una herramienta de contratación pública de TI —que en sus propias sugerencias de chat
pregunta por «los requisitos de accesibilidad que exige la Resolución 1519 de 2020 a un
portal público»— es una contradicción incómoda.

### 1.2 El chat en streaming no se anuncia (FE-A6)

En `Consultar.tsx`, ni `.chat-hilo` ni el bloque de texto parcial tienen `aria-live`,
`role="log"` ni `aria-busy`. La respuesta aparece progresivamente y un lector de pantalla no
dice nada: el usuario pulsa «Enviar» y no recibe indicación de que algo ocurre ni de que
terminó. Es el caso de uso donde `aria-live` es imprescindible, y es donde falta.

Los botones de operación larga cambian su etiqueta a «Analizando pliego…» pero no marcan
`aria-busy`, y el cambio de etiqueta de un elemento sin foco no se anuncia.

### 1.3 Lo que cuesta dinero se pierde al navegar (FE-A3)

`Buscar.tsx:40-46` guarda `resultado` y `priorizacion` en `useState` local; `Validar.tsx`
hace lo mismo con `resultado`. El espacio compartido persiste el proceso, el pliego, el
análisis y la propuesta — pero no la búsqueda ni la validación.

- Buscar → «Analizar este proceso» → atrás = **formulario vacío**, hay que repetir la
  consulta a SECOP.
- «Priorizar con IA» (llamada facturada) → navegar → volver = **priorización perdida**.
- Validar (la operación más cara, hasta dos llamadas al modelo) → navegar → volver =
  **veredicto y matriz perdidos**.

Se conserva lo barato de recalcular y se pierde lo caro. Es incoherente con el modelo mental
que el rediseño a rutas promete.

### 1.4 «Compartir una vista por URL» no funciona (FE-A4)

Ninguna ruta lee ni escribe parámetros de consulta. `/analizar` enviado a un compañero abre
una página vacía; `/` abre la búsqueda sin filtros. Ni el propio usuario recupera su búsqueda
con el historial del navegador. Falta además `/procesos/[id]`, pese a que el backend expone
`GET /api/procesos/{idProceso}` y el cliente ya tiene `api.obtenerProceso` — **sin usar**.

### 1.5 Sin diseño adaptable (FE-A7)

638 líneas de CSS con exactamente dos consultas de medios: `prefers-color-scheme` y
`prefers-reduced-motion`. **Cero puntos de ruptura por anchura.** Las rejillas
(`auto-fit minmax(220px, 1fr)`) se adaptan solas; la cabecera con cinco enlaces, las filas de
acciones y las tablas con `min-width: 700px` no.

### 1.6 El Markdown se muestra como texto plano (FE-A8)

`<div className="markdown">{propuesta.markdown}</div>` — React escapa el contenido, así que
el usuario lee `## Alcance técnico` literalmente. Lo mismo con `seccion.contenido`, que sí
está pensado para leerse. El resultado principal del caso de uso «generar propuesta» se
presenta sin formato.

### 1.7 No hay forma de borrar el espacio de trabajo (FE-M12)

`limpiar()` está definido en el contexto y **no se invoca desde ninguna parte**. El usuario
carga un pliego confidencial y una propuesta comercial en el navegador y no tiene ningún
botón para borrarlos. En un equipo compartido, el siguiente que abra la pestaña ve el trabajo
del anterior.

### 1.8 Sin paginación (FE-M13)

`FiltroProcesos` incluye `offset` y `Buscar.tsx` nunca lo fija. O se piden pocos resultados y
se pierden oportunidades, o se piden 500 y la lista es inmanejable.

---

## 2. Decisión

Cuatro compromisos, en orden de prioridad:

1. **WCAG 2.2 nivel AA como criterio de aceptación**, no como aspiración. Verificado con
   `axe` en CI sobre las cinco rutas.
2. **Nada que cueste dinero se pierde por navegar.**
3. **La URL representa el estado consultable** (filtros de búsqueda, proceso seleccionado).
4. **Adaptable de verdad hasta 380 px** en las vistas de consulta; las de redacción declaran
   un mínimo razonable en lugar de fingir que funcionan en un móvil.

Ese cuarto punto es una decisión de producto, no una omisión: revisar oportunidades en el
móvil es realista; redactar una propuesta técnica en un área de texto de 300 px no lo es.
Mejor soportar bien lo primero y decir con claridad lo segundo.

---

## 3. Diseño

### 3.1 Foco visible — la mejor relación valor/esfuerzo del plan

```css
:where(button, a, summary, [role="button"], input, select, textarea):focus-visible {
  outline: 3px solid var(--foco, #4c8dff);
  outline-offset: 2px;
  border-radius: 3px;
}

/* Contraste garantizado sobre fondos de acento */
button.principal:focus-visible { outline-color: #fff; outline-offset: -5px; }

@media (prefers-contrast: more) {
  :where(button, a, summary):focus-visible { outline-width: 4px; }
}
```

Quince líneas de CSS convierten una aplicación inoperable con teclado en una operable.
`:focus-visible` en lugar de `:focus` evita el contorno al hacer clic con el ratón, que es la
razón habitual por la que alguien desactiva el foco y crea este problema.

Se añade además el salto al contenido, que hoy falta:

```tsx
<a href="#contenido" className="saltar-al-contenido">Saltar al contenido</a>
```

### 3.2 Anuncios para lectores de pantalla

```tsx
<div
  className="chat-hilo"
  role="log"
  aria-live="polite"
  aria-relevant="additions text"
  aria-busy={respondiendo}
  aria-label="Conversación con el agente"
>
```

`aria-live="polite"` y no `assertive`: una respuesta larga en modo asertivo interrumpiría al
usuario constantemente. Y como el streaming emite muchos fragmentos pequeños, se anuncia por
**oraciones completas**, no por fragmento:

```ts
// Anunciar cada token es ruido inutilizable. Se acumula y se emite al cerrar una oración.
const anunciable = useTextoPorOraciones(parcial);
```

Para las operaciones largas, una región de estado en el layout:

```tsx
<p role="status" aria-live="polite" className="visualmente-oculto">
  {pendiente ? `${operacion} en curso` : ultimoResultado}
</p>
```

Y en las tablas, lo que falta hoy: `<caption>`, `scope="col"` en cada `<th>`, y `aria-sort`
en las columnas ordenables de la matriz de cumplimiento.

### 3.3 Nada caro se pierde

El espacio de trabajo incorpora los dos resultados que hoy son locales:

```ts
interface Instantanea {
  procesoSeleccionado: ProcesoResumen | null;
  busqueda: { filtro: FiltroProcesos; resultado: RespuestaProcesos } | null;  // nuevo
  priorizacion: RespuestaRelevancia | null;                                    // nuevo
  textoPliego: string;
  origenPliego: string | null;
  analisis: RespuestaAnalisis | null;
  propuesta: RespuestaPropuesta | null;
  validacion: RespuestaValidacion | null;                                      // nuevo
}
```

Criterio explícito, para que la próxima adición no vuelva a decidirse por inercia:

> **Si obtenerlo cuesta una llamada al modelo o a un servicio externo, se persiste.**

La búsqueda entra aunque sea gratuita porque la caché de cinco minutos del backend
(`SPEC-BE-04`) la hace instantánea al volver, y perderla obliga a rellenar un formulario de
nueve campos.

### 3.4 La URL como estado

**Filtros en la cadena de consulta:**

```
/?texto=ciberseguridad&depto=Antioquia&soloTi=true&min=100000000&limite=30&pagina=2
```

```ts
const [filtro, setFiltro] = useFiltroEnUrl();   // useSearchParams + router.replace
```

Con esto funcionan el historial del navegador dentro de la búsqueda, el marcador y el enlace
compartido — las tres cosas que el rediseño a rutas prometía y no cumplía.

**Ruta de detalle:**

```
/procesos/[id]     → usa api.obtenerProceso, hoy código muerto
```

Enlazable, compartible, y con «Analizar este proceso» que lleva a `/analizar` con el proceso
ya en el espacio.

**Lo que NO va a la URL:** el pliego, el análisis, la propuesta y la validación. Son
voluminosos y confidenciales; una URL con el texto de un pliego acabaría en el historial de
un proxy. `/analizar` sigue dependiendo del espacio de trabajo, y eso se documenta en vez de
prometer lo contrario.

### 3.5 Paginación

```tsx
<nav aria-label="Paginación de resultados">
  <button onClick={anterior} disabled={pagina === 1}>← Anteriores</button>
  <span aria-current="page">Página {pagina}</span>
  <button onClick={siguiente} disabled={!hayMas}>Siguientes →</button>
</nav>
```

`hayMas` se deduce pidiendo `limite + 1` y descartando el sobrante — el conjunto de Socrata
no da total exacto de forma barata. El tamaño de página baja de 30 a 20, que cabe en una
pantalla sin desplazamiento infinito.

### 3.6 Markdown saneado

```tsx
<ReactMarkdown
  remarkPlugins={[remarkGfm]}
  rehypePlugins={[[rehypeSanitize, ESQUEMA_ESTRICTO]]}
  components={{
    a: ({ href, children }) => (
      <a href={href} target="_blank" rel="noopener noreferrer nofollow">{children}</a>
    ),
  }}
>
  {propuesta.markdown}
</ReactMarkdown>
```

**Esto no es negociable y conviene dejarlo escrito:** el contenido lo genera un modelo de
lenguaje a partir de un PDF que sube el usuario, es decir, una fuente susceptible de
inyección de prompt. La solución **nunca** puede ser `dangerouslySetInnerHTML`. El esquema de
saneamiento es una lista blanca: encabezados, párrafos, listas, tablas, énfasis, código y
enlaces `http(s)`. Sin `<script>`, sin `<iframe>`, sin `<style>`, sin atributos `on*`, sin
`javascript:`.

La tarjeta «Documento completo (Markdown)» conserva el texto crudo en un `<pre>` con los
botones de copiar y descargar: ahí el formato sin procesar es lo útil.

### 3.7 Borrar el espacio de trabajo

```tsx
<button className="secundario" onClick={pedirConfirmacion}>
  Borrar espacio de trabajo
</button>
```

Con diálogo de confirmación que enumera lo que se va a perder (pliego, análisis, propuesta,
validación) y que invoca `limpiar()` —hoy código muerto— además de vaciar
`sessionStorage`. Visible en la cabecera junto al selector de IA.

No es solo higiene: es la única forma que tiene el usuario de sacar un pliego confidencial
del navegador (`SPEC-NT-02`).

### 3.8 Puntos de ruptura

```css
@media (max-width: 900px) {
  .navegacion { overflow-x: auto; scrollbar-width: thin; }
  .encabezado-interno { flex-direction: column; align-items: flex-start; gap: .5rem; }
}

@media (max-width: 600px) {
  .fila { flex-wrap: wrap; }
  .fila > button { flex: 1 1 auto; min-height: 44px; }   /* objetivo táctil WCAG 2.5.8 */
  .tarjeta { padding: 1rem .8rem; }
  table { min-width: 0; }
  .contenedor-tabla table { display: block; }             /* tarjetas apiladas */
}
```

Las tablas de requisitos y de cumplimiento pasan a tarjetas apiladas por debajo de 600 px: una
tabla de cinco columnas con desplazamiento horizontal es peor que ilegible, es engañosa,
porque el usuario no ve que hay columnas fuera de pantalla.

Las vistas de redacción (`/proponer`) muestran un aviso por debajo de 600 px recomendando una
pantalla mayor, sin bloquear.

### 3.9 Mensajes de error accionables

El backend devuelve mensajes útiles (`SPEC-BE-06`); el cliente los presenta con la acción
correspondiente:

| Situación | Qué se muestra |
|---|---|
| 503, ningún proveedor | Aviso + enlace directo al selector de proveedor |
| 429, límite de tasa | «Has alcanzado el límite. Se restablece a las HH:MM.» (de `Retry-After`) |
| 408, plazo agotado | «Reintentar» + sugerencia de probar otro proveedor |
| 502, SECOP caído | «Reintentar» + nota de que los datos publicados no se han perdido |
| 413, pliego demasiado grande | Sugerencia de analizar el anexo técnico por separado |

Cada aviso lleva el `correlationId` en letra pequeña, para que el reporte del usuario sea
localizable en el registro.

---

## 4. Plan de ejecución

| Paso | Contenido | Fase |
|---|---|---|
| 1 | Foco visible + salto al contenido. | **1** |
| 2 | `aria-live` en el chat, `aria-busy`, región de estado. | **1** |
| 3 | `caption` y `scope` en las tablas. | **1** |
| 4 | Persistir búsqueda, priorización y validación. | 4 |
| 5 | Borrar espacio de trabajo, con confirmación. | 4 |
| 6 | Filtros en la URL. | 4 |
| 7 | Ruta `/procesos/[id]`. | 4 |
| 8 | Paginación. | 4 |
| 9 | Markdown saneado + CSP. | 4 |
| 10 | Puntos de ruptura + tablas a tarjetas. | 4 |
| 11 | Mensajes accionables por código de estado. | 4 |
| 12 | `axe` en CI sobre las cinco rutas. | 4 |

Los pasos 1–3 son de fase 1, cuestan menos de una jornada entre los tres y cierran las tres
barreras de accesibilidad más graves.

---

## 5. Criterios de aceptación

1. `axe-core` no reporta ninguna infracción de nivel A ni AA en las cinco rutas, en CI.
2. Todas las rutas se recorren completas con teclado, con foco siempre visible; hay una
   prueba de recorrido con `user-event` y `Tab`.
3. Un lector de pantalla anuncia el inicio, el progreso y el fin de la respuesta del chat.
4. Navegar a otra ruta y volver **conserva** la búsqueda, la priorización y la validación.
5. Copiar la URL de una búsqueda y abrirla en otra pestaña reproduce los mismos filtros.
6. `/procesos/[id]` funciona con un enlace directo; `api.obtenerProceso` deja de ser código
   muerto.
7. Existe un botón de borrado del espacio, con confirmación, y tras usarlo no queda nada en
   `sessionStorage` ni en `localStorage` salvo el perfil del oferente.
8. El Markdown se renderiza con formato; un documento que contenga `<script>` o
   `<img onerror=…>` se muestra inerte — hay una prueba con esa entrada.
9. A 380 px de ancho no hay desplazamiento horizontal en ninguna ruta y los objetivos
   táctiles miden al menos 44 × 44 px.
10. Cada uno de los cinco códigos de error de §3.9 muestra su mensaje y su acción.

---

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| `react-markdown` + `rehype-sanitize` añaden peso al paquete. | ~40 kB comprimidos, cargados solo en las rutas que renderizan Markdown. El resultado es el producto del caso de uso principal. |
| Persistir más cosas agrava el problema de cuota (`FE-M11`). | `SPEC-FE-02` §3.5 separa el pliego y avisa al degradar. La validación y la búsqueda son órdenes de magnitud menores que el pliego. |
| Los filtros en la URL chocan con el estado en `sessionStorage`. | La URL manda para la búsqueda; el espacio no la persiste. Una única fuente por dato. |
| Convertir tablas en tarjetas duplica el marcado. | Se hace con CSS sobre el mismo marcado, no con dos árboles de componentes. |
| `axe` en CI resulta ruidoso. | Se fija en A y AA, con excepciones justificadas y fechadas, igual que ArchUnit. |

---

## 7. Fuera de alcance

- Rediseño visual. La estética actual es sobria y funciona; esto corrige comportamiento.
- Internacionalización: la interfaz es en español y el dominio es colombiano.
- Modo de alto contraste propio más allá de `prefers-contrast`.
- Exportación a PDF o DOCX de la propuesta (Markdown y copiar cubren el caso hoy).
