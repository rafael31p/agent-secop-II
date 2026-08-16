# SPEC-BE-04 · Puerto de catálogo SECOP y consultas SoQL seguras

| | |
|---|---|
| **Estado** | Propuesta |
| **Prioridad** | 🟠 Media |
| **Cierra** | BE-A7, BE-A8, BE-A9 (parcial) |
| **Depende de** | SPEC-BE-01 |
| **Esfuerzo** | 3–5 jornadas |

---

## 1. Problema

`secop/SecopCliente.java` son 354 líneas que hacen cinco cosas y esconden dos defectos
serios de comportamiento.

### 1.1 Cinco responsabilidades en una clase (SRP)

1. Invocar la API HTTP de Socrata.
2. Construir la cláusula `WHERE` en SoQL por concatenación de cadenas.
3. Mapear filas `Map<String,Object>` al dominio, resolviendo alias de columnas.
4. Aplicar la heurística de relevancia TI y filtrar por umbral.
5. Decidir la política de sobremuestreo y el tope de Socrata.

Las responsabilidades 3 y 4 no son de un cliente HTTP: son mapeo de adaptador y regla de
dominio, respectivamente.

### 1.2 La búsqueda filtrada se convierte en una sin filtrar (BE-A8)

`:162-173` usa `null` como valor centinela ante cualquier `RuntimeException`, y `:118-124`
reacciona **repitiendo la consulta sin cláusula `WHERE`**. El usuario pide procesos de
ciberseguridad en Antioquia entre 100 y 500 millones, y recibe los últimos N procesos de
cualquier tipo del país, con HTTP 200 y una advertencia entre otras en una lista.

Es la peor forma de degradación: datos plausibles que responden a una pregunta distinta.

### 1.3 Consultas construidas por concatenación (BE-A7)

```java
private static String likeCrudo(String campo, String valor) {
    return "upper(%s) like upper('%%%s%%')".formatted(campo, escapar(valor));
}
static String escapar(String valor) {
    return valor == null ? "" : valor.replace("'", "''");
}
```

`escapar` solo duplica comillas simples. **`%` y `_` no se escapan**, así que buscar `100%`
da semántica de comodín en lugar de literal. Y los rangos numéricos se interpolan con la
representación por defecto de `Double`: un valor grande produce `1.0E30`, que SoQL rechaza,
y el fallo se degrada por la vía del punto anterior.

No es inyección SQL clásica —Socrata es de solo lectura y el conjunto es público—, pero es
el mismo error de diseño con menor radio de daño *hoy*.

---

## 2. Decisión

1. **Un puerto**, `ProcurementCatalogPort`, y un adaptador Socrata detrás.
2. **La degradación deja de ser tácita.** Un tipo sellado `CatalogResult` obliga a quien
   llama a decidir qué hacer, y la búsqueda **nunca** se sustituye por otra distinta.
3. **La consulta se construye con un constructor tipado**, no concatenando cadenas.
4. **La heurística TI vuelve al dominio** como servicio puro y especificación.

---

## 3. Diseño

### 3.1 Descomposición

```
application/port/out/ProcurementCatalogPort
        ▲
adapter/out/procurement/
   ├── SocrataProcurementCatalog        implementa el puerto, orquesta
   ├── SocrataApiClient                 @RegisterRestClient (el actual SecopApi)
   ├── SoqlQueryBuilder                 Builder + Value Object
   ├── SocrataRowMapper                 alias de columnas → dominio
   └── SocrataFieldAliases              tabla de alias (constantes)

domain/service/TechRelevanceScorer      la actual HeuristicaTI, sin cambios de lógica
domain/model/procurement/TechRelevance  puntaje + señales, con la regla del umbral
```

### 3.2 El puerto y su resultado

```java
public interface ProcurementCatalogPort {
    CatalogResult search(ProcessFilter filter);
    Optional<ProcurementProcess> findById(ProcessId id);
}

/** El resultado dice explícitamente en qué estado llegó. Nada de null ni de sustituciones. */
public sealed interface CatalogResult {

    /** La consulta se ejecutó tal como se pidió. */
    record Complete(List<ProcurementProcess> processes, List<Warning> warnings)
            implements CatalogResult {}

    /**
     * Se ejecutó, pero con filtros que la fuente ignoró (p. ej. una fecha mal formada).
     * El usuario debe poder ver cuáles, y decidir.
     */
    record Partial(List<ProcurementProcess> processes, List<Warning> ignoredFilters)
            implements CatalogResult {}

    /** La fuente no respondió. No se inventa una consulta alternativa. */
    record Unavailable(String reason) implements CatalogResult {}
}
```

El recurso REST traduce:

```java
return switch (catalog.search(filter)) {
    case Complete(var processes, var warnings) -> ok(processes, warnings);
    case Partial(var processes, var ignored)   -> ok(processes, ignored);
    case Unavailable(var reason)               -> throw new SourceUnavailableException(
            "SECOP II no está respondiendo (%s). Reintenta en unos minutos; los datos "
                    .formatted(reason)
                    + "publicados no se han perdido, solo la consulta.");
};
```

**Lo que se pierde y por qué está bien perderlo.** Hoy, con SECOP caído, el usuario recibe
una lista de procesos. Después de este cambio recibe un 502 con una explicación. Es
estrictamente mejor: una lista que no corresponde a lo que pidió es peor que ninguna lista,
porque el usuario no tiene forma de saber que está mirando otra cosa.

La distinción `Partial` conserva lo que la degradación actual sí hace bien: si solo se
ignoró `fechaDesde` por formato inválido, eso no justifica fallar —el resto de la consulta
sí se ejecutó—.

### 3.3 Constructor de consultas tipado (BE-A7)

```java
public final class SoqlQueryBuilder {

    private final List<String> clauses = new ArrayList<>();

    public SoqlQueryBuilder notNull(SocrataField field) {
        clauses.add(field.column() + " IS NOT NULL");
        return this;
    }

    /** Coincidencia parcial insensible a mayúsculas, con los comodines neutralizados. */
    public SoqlQueryBuilder containsAny(List<SocrataField> fields, String term) {
        String literal = SoqlLiteral.likePattern(term);
        clauses.add(fields.stream()
                .map(f -> "upper(%s) like upper(%s)".formatted(f.column(), literal))
                .collect(joining(" OR ", "(", ")")));
        return this;
    }

    public SoqlQueryBuilder atLeast(SocrataField field, BigDecimal amount) {
        clauses.add("%s >= %s".formatted(field.column(), SoqlLiteral.number(amount)));
        return this;
    }

    public SoqlQueryBuilder onOrAfter(SocrataField field, LocalDate date) { … }

    public Optional<String> build() {
        return clauses.isEmpty() ? Optional.empty() : Optional.of(String.join(" AND ", clauses));
    }
}
```

```java
final class SoqlLiteral {

    /**
     * Escapa el literal y, además, los comodines de LIKE.
     *
     * Sin escapar `%` y `_`, buscar "100%" o "SIS_TEMA" se convierte en una consulta con
     * comodín: el usuario recibe resultados que no pidió sin que nada se lo advierta.
     */
    static String likePattern(String raw) {
        String escaped = raw.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
                .replace("'", "''");
        return "'%" + escaped + "%'";
    }

    /** Sin notación científica: SoQL rechaza `1.0E30`. */
    static String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
```

El cambio de `Double` a `BigDecimal` en `AmountRange` cierra el defecto de notación
científica en el origen, no en el formateo.

**Los campos son un tipo, no cadenas.** `SocrataField` es una enumeración con la columna
real y sus alias, de modo que un nombre de columna mal escrito es un error de compilación en
vez de una consulta que falla en producción —que es lo que hoy produce la degradación
silenciosa—.

### 3.4 Mapeo aparte

`SocrataRowMapper` recibe la fila y la tabla de alias y devuelve dominio. Los comentarios
actuales sobre el porqué de los alias, del `"No Definido"` y del formato `{"url": …}` de
Socrata **se migran literalmente**: documentan comportamiento real de la fuente, aprendido a
base de golpes, y son irreemplazables.

La única regla nueva: el mapeador **no** calcula la relevancia TI. Devuelve
`ProcurementProcess` sin puntaje, y el puntaje lo aplica el dominio.

### 3.5 La heurística vuelve al dominio

`HeuristicaTI` ya es una clase pura sin dependencias: solo hay que moverla y darle nombre de
servicio de dominio. Se le añade la especificación que hoy vive suelta en el cliente
(`:131`, `p.scoreTi() >= HeuristicaTI.UMBRAL`):

```java
// domain/model/procurement/TechRelevance.java
public record TechRelevance(int score, List<String> signals) {
    public static final int THRESHOLD = 8;
    public boolean isTechnology() { return score >= THRESHOLD; }
}
```

Con eso, el filtro del adaptador pasa a leerse como la regla que es:

```java
processes.stream().filter(p -> p.techRelevance().isTechnology())
```

La política de sobremuestreo (`FACTOR_SOBREMUESTREO`, `MUESTRA_MINIMA`,
`LIMITE_MAXIMO_SOCRATA`) se queda en el adaptador: es una consecuencia de cómo funciona
Socrata, no una regla de negocio. El comentario que la explica se conserva.

### 3.6 Resiliencia y caché

`SPEC-BE-02` §3.5 define las anotaciones del adaptador. Se añade aquí una caché de corta
duración, que en esta fuente es especialmente rentable:

```java
@CacheResult(cacheName = "procurement-search")
public CatalogResult search(ProcessFilter filter) { … }
```

```properties
quarkus.cache.caffeine."procurement-search".expire-after-write=5M
quarkus.cache.caffeine."procurement-search".maximum-size=200
```

El conjunto de datos de SECOP II se actualiza a lo sumo unas veces al día; cinco minutos de
caché son invisibles para el usuario y eliminan el grueso de las llamadas repetidas —que hoy
se producen constantemente porque el frontend pierde los resultados al navegar (`FE-A3`)—.
`ProcessFilter` debe ser un record con `equals` por valor, lo cual ya cumple.

**Solo se cachea `Complete`.** Cachear un `Unavailable` durante cinco minutos convertiría un
fallo transitorio en cinco minutos de indisponibilidad.

---

## 4. Plan de migración

| Paso | Contenido | Riesgo |
|---|---|---|
| 1 | Extraer `SocrataFieldAliases` y `SocrataRowMapper` sin cambiar lógica. | Bajo |
| 2 | Mover `HeuristicaTI` a `domain/service`; `TechRelevance` con el umbral. | Bajo |
| 3 | `SoqlQueryBuilder` + `SoqlLiteral`; migrar `construirFiltro`. `SecopClienteFiltroTest` se adapta a la nueva API pero **sus aserciones sobre la cláusula resultante no cambian**, salvo las de comodines, que ahora escapan. | Medio |
| 4 | `BigDecimal` en el rango de importes. | Bajo |
| 5 | `CatalogResult` sellado; eliminar el retorno `null` y la consulta sin filtros. | **Medio-alto**: cambia el comportamiento observable |
| 6 | Declarar `ProcurementCatalogPort`; `ProcessResource` contra el puerto. | Bajo |
| 7 | Caché de 5 minutos. | Bajo |

El paso 5 cambia una respuesta 200 por una 502 en un escenario concreto. Debe ir acompañado
del cambio de `SPEC-FE-03` que presenta ese 502 de forma útil, y anunciarse en el
`CHANGELOG`.

---

## 5. Criterios de aceptación

1. Buscar el texto `100%` produce una cláusula con el comodín escapado, verificado por
   prueba, y no altera el número de resultados frente a buscar `100`.
2. `valorMin = 1e30` genera `1000000000000000000000000000000` y no `1.0E30`.
3. Con SECOP devolviendo 500, la respuesta es 502 con mensaje accionable. **Nunca** una
   lista de procesos que no corresponden al filtro.
4. Una fecha con formato inválido produce `Partial` con el filtro ignorado listado, y el
   resto de la consulta se ejecuta —comportamiento idéntico al actual—.
5. No queda ningún `return null` en el adaptador.
6. `SocrataProcurementCatalog` no supera las 120 líneas; `SoqlQueryBuilder` y
   `SocrataRowMapper` se prueban sin arrancar Quarkus.
7. Dos búsquedas idénticas en menos de cinco minutos producen una sola llamada saliente.
8. Existe una prueba parametrizada con al menos diez entradas hostiles (comillas, `%`, `_`,
   barras invertidas, `--`, `OR 1=1`, caracteres de control) que verifica que la cláusula
   resultante mantiene el término como literal.
9. Las 225 líneas de `SecopClienteFiltroTest` se conservan en su intención; ninguna
   aserción de comportamiento se relaja para hacerla pasar.

---

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| El escapado de `%`/`_` cambia resultados de búsquedas que dependían del comodín sin saberlo. | Es una corrección, no una regresión: nadie escribe `%` esperando un comodín en un campo etiquetado «Texto en el objeto». Se anuncia en el `CHANGELOG`. |
| El paso 5 empeora la percepción («antes veía algo, ahora un error»). | Es el objetivo. El mensaje del 502 debe explicar qué pasó y que los datos no se han perdido. `SPEC-FE-03` lo presenta con opción de reintentar. |
| La caché de 5 minutos oculta datos recién publicados. | El conjunto se actualiza a lo sumo unas veces al día. Se documenta y se expone en la respuesta el instante de la consulta. |
| Socrata cambia el esquema y los alias dejan de resolver. | Ese riesgo ya existe y está bien gestionado con los alias. El paso 1 lo conserva íntegro; `Partial` permite además reportarlo en vez de degradar en silencio. |

---

## 7. Fuera de alcance

- Añadir el conjunto de datos de contratos (`jbjy-vk9h`), ya configurado pero sin usar.
  El puerto lo deja preparado.
- Sincronización local o índice de búsqueda propio.
- Búsqueda semántica sobre los objetos contractuales.
