# SPEC-NT-01 · Producto, expectativas y confianza en un sistema con IA

| | |
|---|---|
| **Estado** | Propuesta |
| **Prioridad** | 🔴 Alta |
| **Cierra** | Parte de TR-C1; complementa FE-A3 y FE-A8 |
| **Depende de** | Nada. Corre en paralelo a la fase 1 |
| **Esfuerzo** | 2–3 jornadas (más una decisión del propietario) |
| **Audiencia** | Propietario del producto, diseño, quien redacte los textos |

---

## 1. Problema

Esta especificación no trata de código. Trata de lo que la herramienta **promete** frente a
lo que **entrega**, y de qué pasa cuando falla de la forma en que fallan los sistemas con
modelos de lenguaje: dando una respuesta segura y equivocada.

El proyecto es notablemente honesto en su descargo. Aparece en el `README`, en la
descripción de OpenAPI y en el pie de cada página:

> Herramienta de apoyo analítico. No sustituye asesoría jurídica ni el estudio de los
> documentos oficiales publicados en SECOP II.

Ese descargo es correcto y hay que conservarlo. El problema es que **cubre la
responsabilidad legal y no cubre el riesgo real de uso**. Cuatro huecos concretos:

### 1.1 No se distingue lo que la máquina calculó de lo que el modelo opinó

La vista de búsqueda muestra una etiqueta «TI 34» junto a cada proceso. Es una heurística
local por palabras clave —determinista, auditable, con un umbral en el código—. Al lado, el
botón «Priorizar con IA» produce puntajes con la misma pinta, generados por un modelo. Son
dos cosas con niveles de fiabilidad completamente distintos que se presentan igual.

El código sí lo sabe: el `@Schema` de `scoreTi` dice «Heurística local por palabras clave, no
una clasificación del modelo», y la etiqueta lleva un `title` con «Heurística local». Pero
eso es un tooltip, y la distinción merece más que un tooltip.

### 1.2 «Puntaje de cumplimiento: 78» parece una medida y no lo es

La validación devuelve un número de 0 a 100 y un veredicto («apta con ajustes», «riesgo de
rechazo»), presentados con un medidor de colores. Un número con un medidor se lee como una
medición. Es la salida de un modelo de lenguaje al que se le pidió puntuar: dos ejecuciones
sobre el mismo texto pueden dar 71 y 78, y ninguna es «la correcta».

El riesgo no es que el número esté mal. Es que **un oferente decida no presentarse** a un
proceso porque un modelo le dijo 43, o que se presente confiado con un 92 y le rechacen la
oferta por un requisito que el modelo pasó por alto.

### 1.3 No hay forma de decir «esto está mal»

Si el agente extrae un requisito que no existe en el pliego, o clasifica como «cumple» algo
que no cumple, el usuario no tiene ningún mecanismo para marcarlo. Ni para él —no puede
corregir la matriz antes de exportarla— ni para el producto —nadie se entera de que el
sistema falla en un caso concreto—.

Es el hueco más caro a medio plazo: sin señal de error no hay forma de saber si la
herramienta funciona bien, y la única evidencia disponible hoy es que las pruebas pasan, que
mide otra cosa.

### 1.4 Las esperas largas no están explicadas

Un análisis puede tardar minutos y una validación sin requisitos estructurados, bastante
más. La interfaz muestra «Analizando pliego…» sin estimación ni explicación. Un usuario que
no sabe si son diez segundos o cinco minutos recarga la página — y pierde el trabajo.

---

## 2. Decisión

Cinco compromisos de producto. Ninguno requiere tecnología nueva; requieren decidir qué se
dice y dónde.

1. **Separar visualmente lo determinista de lo generado.**
2. **Presentar la incertidumbre como parte del resultado**, no como letra pequeña.
3. **Dar al usuario control editorial** sobre lo que el agente produce.
4. **Recoger la señal de error** cuando el usuario la detecte.
5. **Ser explícito sobre el tiempo y el coste** de cada operación antes de lanzarla.

---

## 3. Diseño

### 3.1 Dos orígenes, dos tratamientos

Convención visual aplicada en toda la interfaz:

| Origen | Tratamiento | Ejemplos |
|---|---|---|
| **Calculado** — determinista, reproducible, auditable | Sin marca especial. Es un dato | Puntaje TI, importes, fechas, conteos de la matriz, ordenación por severidad |
| **Generado** — salida de un modelo, no reproducible | Marca «✨ generado» + tono diferenciado en el borde de la tarjeta | Requisitos extraídos, riesgos, priorización, propuesta, veredicto, respuestas del chat |

Y una leyenda breve, una sola vez por vista, no un aviso por elemento. La convención tiene
que ser aprendible en el primer uso y luego desaparecer.

Cambio concreto en la vista de búsqueda: la etiqueta «TI 34» pasa a «TI 34 · palabras clave»
y la tabla de priorización lleva la cabecera «Priorización generada por
{proveedor}/{modelo}» — que además hace visible **con qué modelo** se produjo, información
que hoy existe en la cabecera y no viaja con el resultado.

### 3.2 La incertidumbre, en el resultado

**El puntaje de cumplimiento deja de ser un número solo.**

En vez de `78` con un medidor, se muestra una banda y qué la sostiene:

> **Cumplimiento estimado: alto** (78 / 100)
> 12 requisitos verificados · 2 no cumplen · 1 no evaluable
> Estimación generada por Gemini 3.6 Flash. **Los 2 requisitos incumplidos son
> obligatorios**: revísalos antes de decidir.

Lo que decide la presentación de la oferta no es el número agregado, son los incumplimientos
obligatorios. La interfaz debe llevar la atención ahí, y hoy la lleva al medidor.

**Los ítems «no evaluable» son de primera clase.** Ya existen en el modelo
(`EstadoCumplimiento.NO_EVALUABLE`) y son la forma en que el agente dice «no pude
determinarlo». Deben mostrarse como una lista de acción —«3 requisitos que debes verificar a
mano»— y no como una fila más de la matriz.

**Los vacíos de información ya funcionan bien.** `RespuestaPropuesta.vaciosDeInformacion`
—«exigencias que el pliego pide y tu perfil no acredita, el agente las declara en lugar de
inventarlas»— es el mejor acierto de diseño del producto. Se conserva tal cual y se toma
como modelo para el resto.

### 3.3 Control editorial

El usuario debe poder corregir antes de exportar:

- **Requisitos extraídos:** editar el texto, cambiar la criticidad, eliminar un requisito
  inventado, añadir uno omitido. Los requisitos alimentan la propuesta y la validación, así
  que un error aquí se propaga a los dos pasos siguientes — es el punto de mayor
  apalancamiento de toda la herramienta.
- **Matriz de cumplimiento:** cambiar el estado de un ítem y añadir una nota propia.
- **Propuesta:** ya es Markdown copiable y descargable. Suficiente.

Todo lo editado se marca como **«editado por ti»** y sobrevive a la regeneración: si el
usuario corrigió RT-04 y vuelve a analizar, no se pierde su corrección sin preguntar.

Esto convierte la herramienta de «oráculo que hay que creer o descartar» en «borrador que se
corrige», que es lo que realmente es y lo que su propio descargo dice que es.

### 3.4 Señal de error

Junto a cada elemento generado, dos acciones discretas: **👍 / 👎 con un campo de motivo
opcional**.

Qué se registra: el identificador del elemento, el proveedor y modelo, el caso de uso, el
sentido de la valoración y el motivo si lo escribe.
**Qué NO se registra: el contenido del pliego ni el de la propuesta.** Sin eso, la
funcionalidad se convierte en un problema de tratamiento de datos (`SPEC-NT-02`) que no
compensa.

La limitación hay que aceptarla y decirla: sin el contenido, una valoración negativa dice
*que* algo falló y no *qué*. Aun así, la tasa de valoraciones negativas por modelo y por caso
de uso es la primera métrica de calidad que tendría el producto, y hoy no tiene ninguna.

### 3.5 Tiempo y coste, antes de pulsar

Cada operación cara declara qué va a pasar:

| Operación | Antes de pulsar |
|---|---|
| Analizar requisitos | «≈ 30–90 s · 1 consulta al modelo» |
| Generar propuesta | «≈ 60–180 s · 1 consulta al modelo» |
| Validar con requisitos | «≈ 30–90 s · 1 consulta al modelo» |
| Validar **sin** requisitos | «≈ 2–4 min · **2 consultas al modelo**. Analiza primero el pliego para que sea más rápido y barato.» |
| Priorizar con IA | «≈ 20–60 s · 1 consulta sobre N procesos» |

La última fila es la que más vale: convierte una decisión invisible del backend
(`AgenteSecop.validarPropuesta` encadena dos llamadas) en información que permite al usuario
elegir el camino barato. Es `BE-A11` explicado en la interfaz en vez de sufrido.

Durante la espera, progreso honesto: «Consultando a Gemini 3.6 Flash… 45 s» con un contador
real y el botón de cancelar de `SPEC-FE-02`. Nada de barras de progreso falsas — no se sabe
cuánto falta y fingirlo destruye la confianza cuando se pasa de largo.

### 3.6 Dónde se dice cada cosa

| Mensaje | Dónde |
|---|---|
| Qué es y qué no es la herramienta | Pie, en todas las páginas (**ya existe, se conserva literal**) |
| A dónde viajan los datos | Cabecera, junto al selector de proveedor (`SPEC-NT-02`) |
| Origen calculado vs. generado | Leyenda por vista + marca por elemento |
| Tiempo y coste estimados | Junto al botón de acción |
| Qué revisar sí o sí | En el resultado, arriba, no al final |
| Cómo corregir | En el propio elemento |

### 3.7 Lo que este producto no debería llegar a ser

Conviene dejarlo escrito para que nadie lo proponga dentro de seis meses creyendo que mejora:

- **No debe decidir si presentarse a un proceso.** Informa; la decisión es del oferente.
- **No debe enviar nada a la entidad contratante**, ni radicar observaciones, ni presentar
  ofertas. La distancia entre «borrador generado» y «documento radicado» es donde vive la
  responsabilidad humana.
- **No debe presentar sus salidas como conceptos jurídicos**, aunque cite normas
  correctamente.
- **No debe ocultar con qué modelo trabajó.** El resultado depende del modelo, y el usuario
  eligió uno.

---

## 4. Plan de ejecución

| Paso | Contenido | Quién |
|---|---|---|
| 1 | Aprobar la distinción calculado/generado y su tratamiento visual. | Propietario |
| 2 | Redactar los textos: leyenda, estimaciones, avisos de revisión. | Producto |
| 3 | Marca de origen en toda la interfaz. | Frontend (con `SPEC-FE-04`) |
| 4 | Rediseño de la presentación del veredicto. | Frontend |
| 5 | Estimaciones de tiempo y coste junto a cada acción. | Frontend |
| 6 | Edición de requisitos y de la matriz. | Frontend + espacio de trabajo |
| 7 | Valoración 👍/👎, **solo si `SPEC-NT-02` está resuelta**. | Ambos |

Los pasos 1, 2 y 5 no requieren decisiones técnicas y se pueden hacer ya. El paso 6 es el de
más trabajo y el de más valor.

---

## 5. Criterios de aceptación

1. Un usuario nuevo distingue, sin leer documentación, qué salió de un cálculo y qué de un
   modelo.
2. La vista de validación destaca los incumplimientos obligatorios por encima del puntaje
   agregado.
3. Cada operación que invoca al modelo declara su tiempo y su número de consultas antes de
   ejecutarse.
4. La ruta de validación sin requisitos previos avisa de que son dos consultas y ofrece la
   alternativa.
5. El usuario puede editar requisitos y estados de cumplimiento, y sus ediciones se marcan y
   sobreviven a una regeneración.
6. Todo resultado generado muestra con qué proveedor y modelo se produjo.
7. El descargo actual sigue presente, literal, en el pie y en la documentación.
8. Ninguna barra de progreso simula avance que no se conoce.

---

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| Tanto aviso de incertidumbre hace que la herramienta parezca poco fiable. | La marca de origen es discreta y aprendible; los avisos van donde hay una decisión que tomar, no en cada elemento. Un usuario que entiende los límites confía **más**, no menos. |
| La edición de requisitos añade complejidad de estado. | Se hace sobre el espacio de trabajo que ya existe; el coste real está en la interfaz, no en el modelo de datos. |
| Las estimaciones de tiempo se quedan cortas y molestan. | Se calibran con las métricas de `SPEC-BE-05` y se expresan como rangos, no como promesas. |
| La valoración 👍/👎 se implementa antes de resolver el tratamiento de datos. | Bloqueada explícitamente por `SPEC-NT-02` en el paso 7. |

---

## 7. Fuera de alcance

- Rediseño visual completo.
- Métricas de calidad basadas en un conjunto de pliegos etiquetados. Sería lo correcto para
  medir de verdad, y requiere un esfuerzo de datos que hoy no existe.
- Comparación automática entre proveedores para el mismo pliego.
- Cualquier funcionalidad que envíe algo a la entidad contratante.
