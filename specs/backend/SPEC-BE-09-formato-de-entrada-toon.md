# SPEC-BE-09 · Formato del material que se le envía al modelo (TOON)

| | |
|---|---|
| **Estado** | Implementada |
| **Prioridad** | 🟠 Media |
| **Cierra** | Hallazgo de la revisión del PR #2 |
| **Depende de** | SPEC-BE-01 (el redactor de prompts es un puerto de salida) |
| **Esfuerzo** | 0,5 jornadas |
| **Módulo** | `backend-quarkus/` |

---

## 1. Problema

Cada llamada al modelo se paga por token, y tres de los cuatro casos de uso le mandan una
**lista** al modelo:

| Caso de uso | Qué lista | Tamaño típico |
|---|---|---|
| Generar propuesta | Requisitos técnicos del análisis | 5–15 |
| Validar propuesta | Los mismos requisitos | 5–15 |
| Priorizar procesos | Procesos resumidos de SECOP | hasta 30 |

Las tres iban como **JSON con sangrado**:

```json
[ {
  "id" : "REQ-01",
  "categoria" : "Técnico",
  "requisito" : "El portal debe cumplir la Resolución 1519 de 2020…",
  "criticidad" : "obligatorio",
  …
}, {
  "id" : "REQ-02",
  "categoria" : "Técnico",
  …
```

Dos desperdicios, y ninguno aporta nada:

1. **Los nombres de los campos se repiten en cada elemento.** Con siete requisitos, los
   siete nombres se escriben siete veces para decir lo mismo.
2. **El sangrado se tokeniza.** `writerWithDefaultPrettyPrinter()` produce espacios y saltos
   que el modelo lee y que se pagan.

No es un problema de rendimiento: es el coste directo del producto. La métrica
`llm.tokens` de `SPEC-BE-05` existe precisamente porque «cuánto nos cuesta esto» es la
pregunta que decide si el proyecto sigue.

---

## 2. Decisión

Codificar las listas uniformes en **TOON** (Token-Oriented Object Notation), forma tabular,
que declara los campos una sola vez en una cabecera y escribe cada elemento como una fila:

```toon
requisitos[7]{id,categoria,requisito,criticidad,evidenciaEsperada,normaRelacionada,citaPliego}:
  REQ-01,Técnico,"El portal debe cumplir la Resolución 1519 de 2020, nivel AA",obligatorio,…
  REQ-02,Técnico,"Entrega del código fuente completo",obligatorio,…
```

Tres decisiones dentro de la decisión:

1. **Solo la forma tabular.** Es la única que aparece aquí. Lo que no sea una lista de
   objetos con los mismos campos y valores primitivos **cae de vuelta a JSON**, que es
   correcto aunque cueste más. Implementar el formato entero sería escribir un serializador
   de propósito general para un caso que no lo pide.
2. **Se implementa, no se importa.** No hay implementación oficial para Java —el SDK de
   referencia es TypeScript— y la parte que se usa son unas pocas reglas de entrecomillado
   y escape. Traer una dependencia para eso sería peor negocio que las cien líneas de
   `adapter/out/llm/Toon.java`.
3. **Pasa por Jackson.** La serialización de los tipos del dominio ya está decidida en otro
   sitio: las enumeraciones salen con su código por cable (`obligatorio`, no
   `OBLIGATORIO`) gracias a `CodedEnumCustomizer`, y los prompts le piden al modelo
   exactamente esos códigos. Codificar por reflexión propia rompería esa correspondencia en
   silencio, que es como se rompen las cosas caras de encontrar.

**La salida del modelo no cambia.** Sigue siendo JSON con esquema impuesto: es lo que
permite deserializar contra un record y lo que `EsquemasJson` refuerza. TOON entra, JSON
sale.

---

## 3. Diseño

### 3.1 Reglas implementadas (spec TOON 4.1)

| Regla | Implementación |
|---|---|
| Cabecera `clave[N]{campos}:` | El `N` no es decorativo: le dice al modelo cuántas filas debe haber |
| Sangrado de dos espacios | El valor por defecto del spec |
| Delimitador coma | El de por defecto; sin símbolo en la cabecera |
| `null`, `true`, `false` | Literales en minúscula |
| Entrecomillado obligatorio | Vacío; espacios al principio o al final; parecido a número; `true`/`false`/`null`; contiene coma, dos puntos, comillas, barra invertida, corchetes, llaves o control; empieza por `-` o `#` |
| Escapes | `\\`, `\"`, `\n`, `\r`, `\t`, `\uXXXX` |

El entrecomillado no es un detalle: sin él, la coma de «MinTIC, nivel AA» parte la fila y
desplaza todas las columnas siguientes, de modo que el modelo leería «nivel AA» como
criticidad. Los requisitos de un pliego llevan comas y dos puntos casi siempre.

### 3.2 Al modelo hay que decirle qué está leyendo

Se añade un bloque de sistema a las tres tareas que reciben tablas —y solo a esas; el
análisis recibe el pliego en prosa y describirle un formato que no va a ver sería gastar
tokens en explicar nada—.

Se compone **por adición**, como `FORMATO_JSON`. Componer quitando bloques de un prompt base
ya costó una vez que el chat le pidiera JSON al modelo sin que nada avisara.

### 3.3 Qué pasa si la lista deja de ser uniforme

Cae a JSON. Que exista ese camino importa: el día que uno de estos records gane un campo de
lista, el prompt seguirá siendo correcto en vez de producir una tabla mal formada, y la
única consecuencia será gastar más tokens.

---

## 4. Resultado medido

Sobre siete requisitos reales, que es un análisis típico:

| Formato | Caracteres |
|---|---|
| JSON con sangrado (lo anterior) | 2 774 |
| TOON tabular | 1 810 |
| **Ahorro** | **35 %** |

`ToonTest.ahorraDeVerdad` lo comprueba en cada compilación con un umbral del 33 %. No es una
prueba de rendimiento por gusto: si el ahorro cayera, este código dejaría de valer lo que
cuesta mantenerlo, y conviene enterarse por una prueba roja y no por una factura.

El ahorro es menor que el 40–60 % que anuncia el formato, y la razón está en los datos: casi
todos los valores de un pliego llevan comas o dos puntos, así que hay que entrecomillarlos y
esa parte no se ahorra. Lo que sí se ahorra íntegro son los nombres de campo repetidos y el
sangrado.

---

## 5. Criterios de aceptación

1. Las tres listas viajan en TOON tabular. ✅
2. Una lista no uniforme cae a JSON en vez de producir una tabla mal formada. ✅
3. Las enumeraciones conservan su código por cable dentro de la tabla. ✅
4. Un valor con comas, comillas o saltos de línea no rompe la fila. ✅
5. El ahorro frente al JSON anterior se mide y se vigila. ✅ (35 %)
6. El modelo real lee las tablas sin perder elementos. ✅

El punto 6 quedó cerrado con `verificar_flujo_completo.py` contra Gemini real, que comprueba
coherencia entre pasos y no códigos HTTP:

| Tabla | Qué demuestra |
|---|---|
| `procesos` | La priorización devolvió exactamente los procesos enviados, sin inventar ninguno |
| `requisitos` (propuesta) | Las secciones referencian los requisitos del análisis y **ninguno inexistente** |
| `requisitos` (validación) | La matriz de cumplimiento coincide **7 de 7** con los requisitos de la tabla |

Ese «7 de 7» es la afirmación que importa: si el modelo hubiera perdido una fila al leer la
tabla, o hubiera desalineado una columna por una coma sin entrecomillar, la matriz habría
hablado de otros requisitos y la comprobación lo habría dicho.

---

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| El modelo lee peor una tabla que un JSON. | Es el riesgo real y por eso el criterio 6 existe. `verificar_flujo_completo.py` comprueba coherencia entre pasos —que la propuesta cubra los requisitos del análisis, que la matriz hable de esos mismos— y no solo códigos HTTP. |
| La implementación se desvía del spec TOON. | Cubre solo la forma tabular, y las reglas de entrecomillado y escape están tomadas del spec 4.1 y probadas una por una. Lo que no encaja no se codifica: cae a JSON. |
| Un campo nuevo de tipo lista rompe la tabla en silencio. | No puede: la comprobación de uniformidad rechaza objetos y listas anidadas, y el redactor cae a JSON. |

---

## 7. Fuera de alcance

- **Las otras tres formas de TOON** (inline, lista, tabular con clave). No aparecen aquí.
- **TOON en la salida del modelo.** La salida es JSON con esquema impuesto, que es lo que
  permite deserializar contra un record. Cambiarlo perdería esa garantía a cambio de unos
  tokens de respuesta.
- **Conteo real de tokens.** El ahorro se mide en caracteres. Para tendencias basta; el
  conteo real llegará cuando se lea el uso que devuelve el proveedor (`SPEC-BE-05` §3.2).
