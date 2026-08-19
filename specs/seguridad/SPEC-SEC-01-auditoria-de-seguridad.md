# SPEC-SEC-01 · Auditoría de seguridad: inyección, entrada no confiable y superficie expuesta

| | |
|---|---|
| **Estado** | Propuesta |
| **Prioridad** | 🔴 Alta |
| **Cierra** | SEC-1 … SEC-12 (hallazgos nuevos) |
| **Depende de** | SPEC-BE-06 (implementada en su mayor parte) |
| **Esfuerzo** | 4–6 jornadas |
| **Alcance** | `backend-quarkus/` y `frontend-next/` |
| **Línea base** | 164/164 backend, 64/64 frontend, 18 de agosto de 2026 |

> Revisión de seguridad del propio proyecto, sobre código propio y en un repositorio
> propio. No sustituye una prueba de penetración: es análisis de código y de configuración.

---

## 0. Lo que se revisó y salió limpio

Conviene empezar por aquí, porque hay decisiones defensivas bien tomadas que no hay que
deshacer al arreglar el resto.

| Superficie | Resultado |
|---|---|
| XSS en el frontend | ✅ Ni un `dangerouslySetInnerHTML`, ni `innerHTML`, ni `eval`, ni `new Function` en `app/`, `componentes/` ni `lib/` |
| Enlaces externos | ✅ `rel="noreferrer noopener"` en el único enlace a terceros |
| `javascript:` desde datos de Socrata | ✅ `extraerUrl` filtra a `http://`/`https://`; un esquema hostil se descarta |
| Inyección de registros | ✅ `Texto.paraRegistro` sustituye los caracteres de control por `␣` en vez de borrarlos — deja constancia del intento |
| Cabecera de correlación entrante | ✅ Validada contra `[A-Za-z0-9\-]{1,64}` antes de tocar el registro |
| Comparación de claves de API | ✅ Se comparan **hashes**, con `MessageDigest.isEqual` y sin salida anticipada del bucle |
| Arranque en producción | ✅ `ValidacionDeArranque` falla sin orígenes CORS y sin ninguna clave |
| Fechas en el filtro | ✅ `FECHA_ISO.matcher(...).matches()` exige coincidencia completa |
| Travesía de rutas en la carga | ✅ La ruta la fija el contenedor; el nombre del cliente nunca construye una ruta |
| Deserialización | ✅ Sin tipado polimórfico de Jackson; sin `enableDefaultTyping` |
| SSRF por petición | ✅ Las URL base son configuración; el `proveedor` sólo elige entre los ya configurados |

---

## 1. Hallazgos

### 🔴 SEC-1 · Los importes se inyectan en SoQL en notación científica, y se dispara siempre

`secop/SecopCliente.java:212-217`:

```java
if (f.valorMin() != null) {
    clausulas.add("precio_base >= " + f.valorMin());
}
```

`valorMin` es un `Double`. **Java emite notación científica a partir de 10 000 000.**
Verificado ejecutando:

```
precio_base >= 1.0E7      ← valorMin = 10 000 000
precio_base >= 5.0E8      ← valorMin = 500 000 000
precio_base >= 9999999.0  ← valorMin =  9 999 999   (y aun así con `.0` de más)
```

**Esto no es un caso extremo, es el caso normal.** En contratación pública colombiana diez
millones de pesos es un contrato pequeño, y el frontend usa `step={1000000}` en ese campo.
Prácticamente cualquier búsqueda por importe real emite SoQL malformado.

La prueba que debería haberlo detectado usa 1 000 000 y 5 000 000
(`SecopClienteFiltroTest:127-128`): las dos por debajo del umbral. Pasa en verde y no cubre
el rango que se usa de verdad.

**Y la consecuencia encadena con la degradación silenciosa.** SoQL rechaza la cláusula →
`consultar` captura la excepción y devuelve `null` → `buscar` reintenta **sin cláusula
`WHERE`** (`BE-A8`). El usuario pide «procesos de ciberseguridad entre 100 y 500 millones» y
recibe los últimos N procesos de cualquier tipo del país, con HTTP 200 y un aviso entre
otros.

No es inyección en sentido estricto, y el efecto es el mismo que el de una inyección
lograda: **la consulta que se ejecuta no es la que se pidió**, y el usuario no lo sabe.

### 🔴 SEC-2 · La autenticación es lista de inclusión por sufijo, no denegación por defecto

`adapter/in/rest/FiltroClaveApi.java:151-174`. El filtro sólo actúa si `Operacion.de(ruta)`
encuentra coincidencia; lo que no está enumerado **no requiere clave ni consume cuota**.

Tres problemas de distinta gravedad:

**(a) Hay un endpoint desprotegido que nadie decidió abrir.**
`GET /api/procesos/{idProceso}` (`ProcesosResource.obtener`) no figura en el enumerado. Es
accesible sin clave, sin límite de tasa, y **provoca una llamada saliente a datos.gov.co por
cada invocación** —de hecho dos, porque `obtenerPorId` prueba dos campos—. Sirve como
amplificador de tráfico hacia un tercero usando nuestra infraestructura y nuestra reputación
de IP. El Javadoc enumera los abiertos a propósito —salud, catálogo de proveedores,
documentación— y éste no está en esa lista: es un olvido, no una decisión.

**(b) La postura por defecto está invertida.** Todo endpoint nuevo nace sin protección hasta
que alguien recuerde añadirlo al enumerado. La postura correcta es proteger todo salvo una
lista explícita de excepciones.

**(c) El emparejamiento por sufijo es frágil.** `normalizada.endsWith("/analisis/requisitos")`
falla ante una barra final, parámetros de matriz (`;v=1`) o formas codificadas, si el
enrutador de RESTEasy es más permisivo que el `endsWith`. Cualquier divergencia entre lo que
encamina el contenedor y lo que reconoce el filtro **es una elusión de autenticación**. Debe
comprobarse con pruebas, no razonarse.

### 🔴 SEC-3 · Bomba de descompresión en la carga de DOCX

`adapter/out/document/ExtractorDocumentos.java:123`:

```java
try (var documento = new XWPFDocument(new ByteArrayInputStream(contenido))) {
```

Sin `ZipSecureFile.setMinInflateRatio(...)`, sin `ZipSecureFile.setMaxEntrySize(...)` y sin
`IOUtils.setByteArrayMaxOverride(...)`. Un DOCX es un ZIP: con los valores por defecto de POI
—ratio mínimo de 0,01— un archivo de 25 MB puede expandirse hasta el orden de 2,5 GB antes de
que la biblioteca proteste.

El mamparo que introdujo `SPEC-BE-08` (`@Bulkhead(4)`, 60 s) **acota la concurrencia, no la
memoria por petición**. Cuatro extracciones simultáneas de una bomba siguen siendo cuatro
veces el desbordamiento. Y el timeout de 60 s no ayuda: el proceso muere por memoria antes.

Es el vector de denegación de servicio más barato que queda en el sistema: un archivo, una
petición, dentro de la cuota de 60/hora.

### 🟠 SEC-4 · La defensa contra XXE es heredada, no propia ni verificada

POI 5.x y PDFBox 3.x configuran sus analizadores XML con entidades externas deshabilitadas,
así que muy probablemente **no es explotable hoy**. El problema es de otra naturaleza: es una
propiedad que el proyecto recibe de una versión de biblioteca y que ninguna prueba afirma.
Una subida de versión, un cambio de analizador o una configuración distinta la revierten sin
que nada falle.

Sobre entrada no confiable —el usuario sube el archivo— esa garantía debe ser explícita.

### 🟠 SEC-5 · El tipo de documento se decide por la extensión que envía el cliente

`ExtractorDocumentos.java:48-63`: `nombre.endsWith(".pdf")`, `.docx`, `.txt`, `.md`, donde
`nombre` es `archivo.fileName()`, un valor del multipart totalmente controlado por quien
llama.

No hay travesía de rutas —la ruta real la pone el contenedor y el nombre sólo se usa para
decidir el analizador y para devolverlo—, pero quedan dos aristas:

1. **Se elige el analizador por lo que dice el cliente, no por lo que contiene el archivo.**
   Un ZIP renombrado a `.pdf` entra a PDFBox. Los dos analizadores validan sus números
   mágicos y fallan limpiamente, así que hoy el resultado es un 415; la decisión, aun así,
   debería tomarla el contenido.
2. **El nombre se devuelve al cliente** en `RespuestaDocumento.nombreArchivo` y el frontend
   lo pinta. React lo escapa, así que hoy es inerte — **pero `SPEC-FE-03` §3.6 introduce
   render de Markdown**, y un nombre de archivo que atraviese ese renderizador deja de serlo.

### 🟠 SEC-6 · Expresión regular de coste cuadrático sobre texto del atacante

`ExtractorDocumentos.sinContenidoUtil`:

```java
texto.replaceAll("(?m)^\\s*---\\s*Página\\s+\\d+\\s*---\\s*$", "")
```

Aplicada sobre hasta 800 000 caracteres de texto extraído del documento del usuario. En Java
`\s` **incluye el salto de línea**, de modo que `\s*` puede atravesar líneas y el retroceso no
queda acotado por línea: un documento compuesto de espacios y saltos hace que el motor pruebe
y descarte desde cada posición.

Se ejecuta dentro del mamparo de extracción, así que el daño está acotado a cuatro hilos —
pero cuatro hilos girando 60 s cada uno, dentro de la cuota, son una denegación de servicio
barata sobre la ruta de extracción.

El arreglo es de una línea: `[ \t]` en lugar de `\s` donde no se quiere cruzar líneas.

### 🟠 SEC-7 · La salida del modelo es entrada no confiable, y va a dejar de estar contenida

El pliego lo sube el usuario y puede contener instrucciones dirigidas al modelo —inyección de
prompt—. Lo que el modelo devuelve (`markdown`, `citaPliego`, `resumen`, `justificacion`)
vuelve al navegador y se pinta.

**Hoy está contenido**: React escapa todo y no existe ningún `dangerouslySetInnerHTML`
—verificado—. La contención es circunstancial, no deliberada.

`SPEC-FE-03` §3.6 propone renderizar Markdown, que es lo correcto para el producto y es
exactamente el momento en que la salida del modelo se convierte en HTML. Aquella spec ya pide
`rehype-sanitize`; esta auditoría lo eleva a **requisito bloqueante y no negociable**: lista
blanca de etiquetas, sin HTML embebido, sin atributos `on*`, esquemas de enlace limitados a
`http`/`https`/`mailto`, y una prueba con una salida hostil por cada campo que se renderice.

### 🟡 SEC-8 · `registrarPeticiones` vuelca pliegos completos al registro y nada lo impide en producción

`ConfiguracionIA.registrarPeticiones()` alimenta `logRequests`/`logRequestsAndResponses` de
los cinco proveedores. En `true`, el texto íntegro del pliego y de la propuesta acaba en el
registro del servicio.

Está en `false` por defecto y el comentario advierte que es «ruidoso en producción», pero
`ValidacionDeArranque` —que sí protege CORS y claves— no lo comprueba. Una variable de
entorno mal puesta convierte el registro en un archivo de material confidencial, que es
precisamente lo que `SPEC-NT-02` §3.5 prohíbe por escrito.

### 🟡 SEC-9 · La respuesta distingue por tiempo «sin cabecera» de «cabecera inválida»

`FiltroClaveApi.identificar` retorna `null` de inmediato si la cabecera falta o está en
blanco; con un valor presente calcula SHA-256 y recorre todas las claves. El bucle está bien
resuelto —no sale antes al encontrar coincidencia, y el comentario explica por qué—, pero la
rama temprana filtra un bit: si hubo o no cabecera.

Valor real para un atacante: prácticamente nulo, ya que la propia respuesta 401 lo dice. Se
anota por completitud del inventario, no por riesgo.

### 🟡 SEC-10 · Faltan las cabeceras de seguridad y no hay CSP en ninguno de los dos módulos

`SPEC-BE-06` §3.5 especificaba `X-Content-Type-Options`, `Referrer-Policy` y
`X-Frame-Options`; `application.properties` no las incluye. `SPEC-FE-01` §3.6 especificaba lo
equivalente más `poweredByHeader: false` en `next.config.ts`; tampoco está.

Por sí solas no cierran ningún hallazgo concreto de esta lista. Importan por lo que viene:
sin CSP, el día que entre el render de Markdown (`SEC-7`) no queda ninguna red de contención
por debajo del saneamiento.

### ⚪ SEC-11 · Sin análisis de dependencias ni de secretos en la integración continua

No hay `gitleaks`, ni OWASP Dependency-Check, ni `dependency-review`. `SPEC-NT-03` §3.2 ya lo
listaba y quedó pendiente.

Importa más de lo habitual aquí porque **PDFBox y POI procesan archivos que sube un
desconocido**: son las dos dependencias con mayor histórico de CVE sobre entrada no confiable
de todo el árbol. Hace falta enterarse el día que salga uno, no en la siguiente auditoría.

### ⚪ SEC-12 · SSRF: no explotable hoy, y `SPEC-BE-03` lo acerca

Las URL base de OpenAI, DeepSeek y Ollama son configuración del operador; el parámetro
`proveedor` de la petición sólo elige entre los ya configurados. **No hay SSRF por petición.**

`SPEC-BE-03` §3.5 propone mover la configuración a `Map<String, ProviderSettings>` con
`baseUrl` por proveedor. En cuanto esa configuración sea más dinámica, hay que exigir `https`
y una lista blanca de hosts. Se anota ahora para que la spec nazca con el requisito, no para
arreglar nada hoy.

---

## 2. Decisión

Seis principios, en orden de aplicación:

1. **Denegación por defecto en la frontera.** Se protege todo; se abre por lista explícita.
2. **Los datos no se concatenan en consultas.** Ni SoQL, ni ningún otro lenguaje.
3. **Todo formato comprimido o estructurado que venga de fuera lleva cotas declaradas**, y
   esas cotas son del proyecto, no heredadas de una biblioteca.
4. **La salida del modelo se trata como entrada del usuario**, porque lo es.
5. **Lo que garantiza la seguridad se afirma en una prueba.** Una defensa que nadie verifica
   caduca en la siguiente subida de versión.
6. **El material confidencial no puede llegar al registro por una variable de entorno.**

---

## 3. Diseño

### 3.1 Importes en SoQL (SEC-1)

Dos cambios, y ninguno es cosmético.

**Tipo:** `Double` → `BigDecimal` en `FiltroProcesos` y en el modelo de dominio.
Los importes son dinero; el punto flotante binario nunca fue el tipo correcto.

**Representación:** nunca `toString`.

```java
/** Sin notación científica: SoQL rechaza `1.0E7`, y diez millones de pesos es un contrato pequeño. */
static String numero(BigDecimal valor) {
    return valor.stripTrailingZeros().toPlainString();
}
```

Y las pruebas que faltaban, en el rango que se usa de verdad:

```java
@ParameterizedTest
@ValueSource(strings = {"10000000", "500000000", "1000000000000", "9999999"})
void losImportesNuncaSalenEnNotacionCientifica(String importe) {
    String where = cliente.construirFiltro(filtroConValorMin(new BigDecimal(importe)), avisos);
    assertThat(where).contains("precio_base >= " + importe);
    assertThat(where).doesNotContainPattern("[0-9]E[0-9]");
}
```

Esta corrección es también la mejor razón para hacer `SPEC-BE-04` §3.3 completa —el
constructor tipado de SoQL con escapado de `%` y `_`—: el mismo defecto de fondo, que es
construir una consulta pegando cadenas, produjo los dos hallazgos.

### 3.2 Denegación por defecto (SEC-2)

```java
/**
 * Rutas deliberadamente abiertas. Todo lo demás exige clave.
 *
 * La lista invertida —enumerar lo protegido— dejaba fuera cualquier endpoint nuevo por
 * omisión, y así quedó `GET /api/procesos/{id}`: sin clave, sin cuota y provocando dos
 * llamadas salientes a datos.gov.co por invocación.
 */
private static final Set<String> RUTAS_ABIERTAS = Set.of(
        "/api/salud", "/api/proveedores", "/q/health", "/q/health/live",
        "/q/health/ready", "/q/metrics", "/openapi", "/docs");
```

Con emparejamiento **normalizado y exacto**, no por sufijo: se decodifica, se recorta la barra
final, se descartan los parámetros de matriz y se compara con igualdad.

`GET /api/procesos/{id}` pasa a exigir clave y consume la cuota de `BUSQUEDA`.

Y la prueba que convierte la postura en invariante:

```java
@Test
void ningunEndpointQuedaAbiertoPorOlvido() {
    // Recorre las rutas declaradas por JAX-RS y exige que cada una esté protegida o
    // figure explícitamente en RUTAS_ABIERTAS. Un endpoint nuevo rompe la prueba.
}

@ParameterizedTest
@ValueSource(strings = {
    "/api/analisis/requisitos/", "/api/analisis/requisitos;x=1",
    "/api/analisis%2Frequisitos", "//api/analisis/requisitos"})
void lasFormasAlternativasDeLaRutaNoEludenLaAutenticacion(String ruta) {
    given().body(SOLICITUD_VALIDA).post(ruta).then().statusCode(anyOf(is(401), is(404)));
}
```

La segunda es la que importa: comprueba que no hay ninguna forma de la URL que el enrutador
acepte y el filtro no reconozca.

### 3.3 Cotas para formatos comprimidos (SEC-3, SEC-4)

```java
@Startup
void endurecerAnalizadores() {
    // Un DOCX es un ZIP. Con los valores por defecto de POI, 25 MB comprimidos pueden
    // expandirse al orden de gigabytes: el mamparo acota cuántas extracciones corren a la
    // vez, no cuánta memoria consume cada una.
    ZipSecureFile.setMinInflateRatio(0.02);
    ZipSecureFile.setMaxEntrySize(80L * 1024 * 1024);
    ZipSecureFile.setMaxTextSize(LIMITE_CARACTERES);
    IOUtils.setByteArrayMaxOverride(80 * 1024 * 1024);
}
```

Ratio 0,02 y entrada máxima de 80 MB: holgado para un pliego real con imágenes, cerrado para
una bomba. Los valores se documentan con su razón, y superarlos produce un 413 con mensaje
útil, no un fallo de memoria.

Y las pruebas que hacen explícita la garantía, con artefactos generados en la propia prueba
—nunca con archivos maliciosos versionados en el repositorio—:

```java
@Test void unDocxBombaSeRechazaSinAgotarLaMemoria() { … }
@Test void unDocxConEntidadExternaNoResuelveElArchivoLocal() { … }
@Test void unDocxConEntidadExternaNoAbreNingunaConexionSaliente() { … }  // WireMock: 0 peticiones
@Test void unPdfConReferenciaExternaNoAbreNingunaConexionSaliente() { … }
```

La tercera y la cuarta son las que convierten «POI probablemente lo desactiva» en un hecho
del proyecto: si una subida de versión reactiva las entidades externas, WireMock registra la
petición y la prueba falla.

### 3.4 El contenido decide el analizador (SEC-5)

```java
private enum Formato {
    PDF(new byte[]{0x25, 0x50, 0x44, 0x46}),          // %PDF
    OOXML(new byte[]{0x50, 0x4B, 0x03, 0x04}),        // PK.. (ZIP)
    TEXTO(null);

    static Formato de(byte[] contenido, String nombre) { … }  // números mágicos primero
}
```

La extensión pasa a ser una pista para el caso del texto plano, no la decisión. Y el nombre
que se devuelve se sanea:

```java
/** El nombre viene del cliente y acaba pintado en la interfaz. Se devuelve una forma inocua. */
static String nombreSeguro(String original) {
    if (original == null || original.isBlank()) {
        return "documento";
    }
    String base = Path.of(original).getFileName().toString();
    return Texto.paraRegistro(base, 120).replaceAll("[^\\p{L}\\p{N} ._-]", "_");
}
```

### 3.5 La regex, acotada (SEC-6)

```java
// `\s` incluye el salto de línea en Java, así que `\s*` cruza líneas y el retroceso deja de
// estar acotado por línea. Con `[ \t]` el patrón hace lo que dice y su coste es lineal.
private static final Pattern MARCA_DE_PAGINA =
        Pattern.compile("(?m)^[ \\t]*---[ \\t]*Página[ \\t]+\\d{1,6}[ \\t]*---[ \\t]*$");
```

Más una prueba con 800 000 caracteres de espacios que debe completarse en menos de un segundo.

### 3.6 La salida del modelo, saneada (SEC-7)

Requisito bloqueante sobre `SPEC-FE-03` §3.6. No se fusiona el render de Markdown sin:

- lista blanca de etiquetas (encabezados, párrafos, listas, tablas, énfasis, código, enlaces);
- `allowedSchemes: ["http", "https", "mailto"]`;
- HTML embebido deshabilitado;
- ningún atributo `on*` ni `style`;
- `target="_blank"` siempre con `rel="noopener noreferrer nofollow"`;
- una prueba por cada campo renderizado, con una carga hostil.

```tsx
it("no ejecuta nada que venga en la salida del modelo", () => {
  const hostil = '<img src=x onerror="window.__pwned=1">\n\n[clic](javascript:alert(1))';
  render(<Markdown>{hostil}</Markdown>);
  expect(window).not.toHaveProperty("__pwned");
  expect(screen.queryByRole("link")).not.toHaveAttribute("href", expect.stringContaining("javascript:"));
});
```

Se añade además el aviso de producto: el contenido generado a partir de un documento de
terceros puede contener instrucciones dirigidas al modelo, y por eso `SPEC-NT-01` §3.1 lo
marca visualmente como generado.

### 3.7 El registro no puede tragarse un pliego (SEC-8)

```java
if (esProduccion() && configIa.registrarPeticiones()) {
    throw new IllegalStateException("""
            agente.ia.registrar-peticiones=true vuelca el texto íntegro del pliego y de la \
            propuesta al registro. En producción eso convierte el registro en un archivo de \
            material confidencial (ver SPEC-NT-02 §3.5). Desactívalo o usa otro perfil.""");
}
```

Mismo patrón que las comprobaciones de CORS y de claves que ya existen, y con el mismo tono:
el mensaje explica la consecuencia, no sólo la regla.

### 3.8 Cabeceras y CSP (SEC-10)

Backend — lo que `SPEC-BE-06` §3.5 ya especificaba:

```properties
quarkus.http.header."X-Content-Type-Options".value=nosniff
quarkus.http.header."Referrer-Policy".value=no-referrer
quarkus.http.header."X-Frame-Options".value=DENY
```

Frontend — con CSP, que llega junto con el render de Markdown:

```ts
"Content-Security-Policy":
  "default-src 'self'; " +
  "script-src 'self'; " +          // sin 'unsafe-inline' ni 'unsafe-eval'
  "style-src 'self' 'unsafe-inline'; " +
  "img-src 'self' data:; " +
  `connect-src 'self' ${process.env.NEXT_PUBLIC_API_URL}; ` +
  "frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
```

`connect-src` debe incluir la URL del backend porque la llamada es directa, sin proxy de Next
(ADR-0003). Es el punto donde esa decisión de arquitectura tiene consecuencia de seguridad, y
conviene que quede escrito junto a ella.

### 3.9 Cadena de suministro (SEC-11)

```yaml
- uses: gitleaks/gitleaks-action@v2
- name: Dependencias con CVE conocido
  run: ./mvnw -B org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7
- uses: actions/dependency-review-action@v4    # sólo en PR
- run: npm audit --audit-level=high
```

Con `suppressions.xml` para los falsos positivos, fechados y con motivo. Una supresión sin
fecha de revisión es una vulnerabilidad aceptada en silencio.

---

## 4. Plan de ejecución

Es la **fase 7** del plan general. Orden por relación daño/esfuerzo:

| Paso | Contenido | Cierra | Esfuerzo |
|---|---|---|---|
| 1 | `BigDecimal` + `toPlainString` + pruebas en el rango real | SEC-1 | 0,5 j |
| 2 | Cotas de POI y `@Startup` que las fija | SEC-3 | 0,5 j |
| 3 | `registrar-peticiones` prohibido en `%prod` | SEC-8 | 0,2 j |
| 4 | Regex acotada + prueba de tiempo | SEC-6 | 0,2 j |
| 5 | Denegación por defecto + pruebas de formas alternativas de ruta | SEC-2 | 1 j |
| 6 | Números mágicos + saneo del nombre devuelto | SEC-5 | 0,5 j |
| 7 | Pruebas de XXE y de bomba con artefactos generados | SEC-4 | 1 j |
| 8 | Cabeceras de seguridad y CSP | SEC-10 | 0,5 j |
| 9 | `gitleaks` + Dependency-Check + `npm audit` en CI | SEC-11 | 0,5 j |
| 10 | Saneamiento de Markdown, **junto con** `SPEC-FE-03` §3.6 | SEC-7 | incluido allí |

Los pasos 1 a 4 suman menos de una jornada y media y cierran el hallazgo que se dispara todos
los días (SEC-1) más el vector de denegación de servicio más barato (SEC-3).

---

## 5. Criterios de aceptación

1. Ninguna cláusula SoQL contiene notación científica, con pruebas en 1e7, 5e8 y 1e12.
2. Una búsqueda con `valorMin=500000000` devuelve resultados **filtrados**, no la
   degradación sin filtros.
3. Toda ruta declarada por JAX-RS está protegida o figura en `RUTAS_ABIERTAS`; hay una
   prueba que falla si se añade un endpoint sin decidirlo.
4. `GET /api/procesos/{id}` exige clave y consume cuota.
5. Ninguna forma alternativa de una ruta protegida (barra final, parámetros de matriz,
   codificación, barra doble) devuelve algo distinto de 401 o 404.
6. Un DOCX bomba se rechaza con 413 sin superar los 200 MB de memoria del proceso.
7. Un DOCX y un PDF con entidad externa **no abren ninguna conexión saliente**, verificado
   contando peticiones en WireMock.
8. Un ZIP renombrado a `.pdf` se rechaza por contenido, no por extensión.
9. El nombre de archivo devuelto no contiene caracteres de control ni separadores de ruta.
10. `sinContenidoUtil` procesa 800 000 caracteres de espacios en menos de un segundo.
11. Arrancar en `%prod` con `registrar-peticiones=true` falla con mensaje explícito.
12. Las cinco cabeceras de seguridad están presentes en las respuestas de ambos módulos.
13. CI falla ante un secreto en el árbol o una dependencia con CVSS ≥ 7 sin supresión
    fechada.
14. Ningún render de contenido generado por el modelo ejecuta HTML ni esquemas `javascript:`.

---

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| `Double` → `BigDecimal` toca el contrato HTTP. | JSON no distingue: un número sigue siendo un número. Jackson deserializa a `BigDecimal` sin cambios en el cliente. Las pruebas de recurso lo confirman. |
| Las cotas de POI rechazan un pliego legítimo grande. | 80 MB por entrada y ratio 0,02 son holgados para un pliego con imágenes. El rechazo es un 413 explicativo, y los valores son configuración. |
| Cerrar `GET /api/procesos/{id}` rompe algún consumidor. | El frontend **no lo usa** (`FE-B16`: es código muerto allí). No hay otros consumidores conocidos. Se anuncia en el `CHANGELOG`. |
| Dependency-Check produce muchos falsos positivos y se desactiva. | Umbral en CVSS 7, supresiones con fecha y motivo, revisión trimestral. Mejor un umbral alto que se respeta que uno bajo que se ignora. |
| La CSP rompe el frontend por estilos en línea. | `style-src 'unsafe-inline'` se mantiene hasta que `SPEC-FE-04` §3.6 elimine los 23 `style={{…}}`; entonces se endurece. Documentado como deuda con salida. |

---

## 7. Fuera de alcance

- Prueba de penetración externa y análisis dinámico (DAST).
- WAF o protección de borde: son decisiones de despliegue (`SPEC-NT-03`).
- OIDC e identidad de persona (`SPEC-BE-06` §3.7 ya lo declara como destino).
- Defensas contra inyección de prompt más allá de tratar la salida como datos. Filtrar
  instrucciones dentro de un pliego es un problema abierto, y una defensa parcial que se
  presenta como completa es peor que ninguna.
- Firma y verificación de los artefactos de compilación.

---

## 8. Estado de ejecución

Los cuatro primeros pasos de la fase 7 salieron en el hotfix `0.2.1`, por la razón que ya
daba el plan: **SEC-1 no es endurecimiento sino corrección**, y estaba fallando todos los
días.

| Hallazgo | Estado |
|---|---|
| SEC-1 · Importes en notación científica | ✅ `BigDecimal` + `toPlainString`, con prueba en el rango real |
| SEC-3 · Bomba de descompresión | ✅ Cotas de POI fijadas al arranque, en todos los perfiles |
| SEC-6 · Regex de coste cuadrático | ✅ Acotada a `[ \t]`, con prueba de tiempo |
| SEC-8 · Volcado de pliegos al registro | ✅ El arranque en producción falla, verificado en el jar |
| SEC-2, SEC-4, SEC-5, SEC-7, SEC-9, SEC-10, SEC-11, SEC-12 | ⏳ Fase 7, pasos 7.5 a 7.10 |

### 8.1 SEC-1 reproducido antes de tocarlo

```
valorMin=      9.999.999  ->  precio_base >= 9999999.0
valorMin=     10.000.000  ->  precio_base >= 1.0E7
valorMin=    500.000.000  ->  precio_base >= 5.0E8
```

Exactamente lo que decía la auditoría. Y la prueba que debía cubrirlo usaba uno y cinco
millones, las dos por debajo del umbral.

**Había una segunda prueba con el defecto dentro.** `mapeaFilaReal` afirmaba
`assertEquals(1_500_000_000.0, proceso.valor())`, es decir, daba por bueno el `Double`
`1.5E9` como valor esperado. No solo no detectaba el problema: lo documentaba como el
comportamiento correcto.

### 8.2 El mismo defecto, en el otro extremo

Pasar a `BigDecimal` arregla la consulta y abre el mismo agujero hacia el navegador: Jackson
serializa un `BigDecimal` con `toString()`, que **también** produce notación científica. Con
los valores que llegan de Socrata la escala es cero y sale bien, pero es una coincidencia
afortunada y no una garantía, así que hay una prueba que fija el formato por cable.

Conviene tener presente el detalle que lo hace fácil de romper: `stripTrailingZeros()` sobre
`1000000000` devuelve `1E+9`. Por eso `numero()` encadena `toPlainString()`, y por eso no se
llama a `stripTrailingZeros()` en el camino de serialización.

### 8.3 SEC-6: la primera prueba no probaba nada

Se escribió con 800 000 espacios **sin saltos de línea**, y el patrón defectuoso la pasaba
en 8 ms. El coste cuadrático necesita muchas **líneas**: como `\s` incluye el salto, cada
inicio de línea podía consumir hasta el final del texto, y el número de inicios de línea es
el que eleva el coste al cuadrado.

Medido con 50 000 líneas de espacios —200 KB, muy por debajo del límite de carga—:

| Patrón | Tiempo |
|---|---|
| `(?m)^\s*---\s*Página\s+\d+\s*---\s*$` | **seguía corriendo tras 8 s** |
| `(?m)^[ \t]*---[ \t]*Página[ \t]+\d{1,6}[ \t]*---[ \t]*$` | 23 ms |

La prueba de la suite usa ahora esa entrada.

### 8.4 SEC-3 se aplica en todos los perfiles

Las cotas de POI son **estado global de la biblioteca**, no un parámetro de la llamada, y se
fijan también en desarrollo y en pruebas: un archivo hostil no es menos hostil en
desarrollo, y una suite que corriera sin las cotas puestas no estaría probando lo que se
despliega. Las comprobaciones que sí dependen del perfil —CORS, claves, SEC-8— siguen
saltándose en `dev` y `test`.

### 8.5 SEC-8 verificado en el jar, no en una prueba

Como las otras comprobaciones de arranque, porque es lo único que demuestra que el
comportamiento es el que se cree:

```
prod + registrar-peticiones=true   -> IllegalStateException: …vuelca el texto integro del pliego…
prod + registrar-peticiones=false  -> started in 2.012s
```
