# SPEC-BE-06 · Autenticación, límites de consumo y fuga de datos

| | |
|---|---|
| **Estado** | Propuesta |
| **Prioridad** | 🔴 Alta |
| **Cierra** | BE-C4, BE-A5, BE-M16, BE-M17, BE-B21 |
| **Depende de** | Nada. **Se ejecuta en la fase 1, antes de la reestructuración** |
| **Esfuerzo** | 3–5 jornadas |

---

## 1. Problema

### 1.1 Un proxy abierto a modelos de pago (BE-C4)

No hay ninguna extensión de seguridad en `pom.xml`, ninguna anotación de autorización en
`api/`, ningún límite de tasa. Cinco endpoints gastan dinero del operador con la clave del
operador:

| Endpoint | Coste por llamada |
|---|---|
| `POST /api/analisis/requisitos` | 1 llamada al modelo, hasta 800 000 caracteres de entrada |
| `POST /api/propuestas/generar` | 1 llamada |
| `POST /api/propuestas/validar` | **hasta 2** llamadas encadenadas |
| `POST /api/procesos/relevancia-ti` | 1 llamada, con lista de procesos sin cota |
| `POST /api/chat` | 1 llamada en streaming, sin límite de historial |

CORS está configurado a `localhost`, pero **CORS no es un control de acceso**: es una
política que aplica el navegador. `curl` la ignora por completo. Cualquiera con ruta de red
al puerto 8000 puede consumir el presupuesto de tokens, sin límite y sin dejar constancia de
quién fue —porque tampoco hay identidad ni registro de auditoría—.

### 1.2 El detalle del proveedor llega al navegador (BE-A5)

`ia/ProveedorLangChain4j.java:302-303`, rama por defecto de `traducir`:

```java
return new ErroresIA.FalloDelProveedor(
        "Error de %s: %s".formatted(etiqueta(), recortar(mensaje)), 502, error);
```

`mensaje` concatena la excepción y hasta cuatro causas (`:307-319`); `recortar` deja pasar
300 caracteres. `ManejadorErrores` lo serializa en `{"detail": …}` y el frontend lo pinta.

El riesgo concreto: **la API de Google AI Studio —el proveedor por defecto— lleva la clave en
la cadena de consulta** (`?key=AIza…`). Basta con que una versión de LangChain4j incluya la
URL en el mensaje de error para publicar la credencial del operador en el navegador de
cualquier usuario. Aun sin ese extremo, se filtran nombres de host, versiones de biblioteca
y estructura interna de la petición.

La misma clase acierta en sus cinco ramas clasificadas y falla en la rama por defecto, que es
justo la que recibirá lo inesperado.

Variante menor del mismo defecto en `secop/SecopCliente.java:170`, que mete
`recortar(e.getMessage())` en la lista de advertencias que ve el usuario.

### 1.3 Sin cotas de entrada por caso de uso (BE-M16)

- `SolicitudAnalisis.textoPliego`: `@Size(min = 40)`, **sin máximo**.
- `SolicitudRelevancia.procesos`: `@NotEmpty`, sin `@Size(max)`.
- `SolicitudChat.mensajes` y `contexto`: sin cota.

El único freno es `exigirTamanoRazonable` con `LIMITE_CARACTERES = 800_000`, y llega tarde
—después de deserializar, construir el prompt y serializar el contexto JSON— y es demasiado
alto: 800 000 caracteres son del orden de 200 000 tokens, por encima de la ventana de la
mayoría de modelos. La petición se acepta, se envía, se factura y falla en el proveedor.

### 1.4 CORS de desarrollo en la configuración base (BE-M17)

`quarkus.http.cors.origins` fija cuatro orígenes `localhost`, incluido el puerto 5173 del
frontend Vite ya retirado. No hay perfil `%prod`.

### 1.5 Credencial real en el árbol de trabajo (BE-B21)

`backend-quarkus/.env` contiene una clave de Google AI válida. Está cubierta por
`backend-quarkus/.gitignore` y el repositorio aún no es git, así que **no hay exposición en
historial**. Es riesgo latente, no realizado.

---

## 2. Decisión

1. **Autenticación por clave de API** en los endpoints que gastan dinero, con límite de tasa
   por clave. No OIDC: no hay usuarios ni organización todavía, y montar un proveedor de
   identidad para una herramienta de un equipo es desproporcionado. La clave es reversible y
   se sustituye por OIDC cuando exista la necesidad.
2. **Ningún detalle interno sale al cliente.** Mensaje genérico + `correlationId`; el detalle
   va al registro.
3. **Cotas por caso de uso en el contrato**, rechazadas con 422 antes de construir nada.
4. **CORS por perfil**, con fallo al arrancar en producción si no está configurado.
5. **Los secretos nunca en el repositorio**, ni siquiera ignorados, en el destino de
   despliegue.

---

## 3. Diseño

### 3.1 Autenticación

```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-security</artifactId>
</dependency>
```

```java
@Provider
@Priority(Priorities.AUTHENTICATION)
public class ApiKeyFilter implements ContainerRequestFilter {

    private static final String HEADER = "X-Api-Key";

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!requiresKey(ctx.getUriInfo().getPath())) {
            return;                       // /api/salud y /api/proveedores quedan abiertos
        }
        String presented = ctx.getHeaderString(HEADER);
        Optional<ApiClient> client = clients.authenticate(presented);
        if (client.isEmpty()) {
            ctx.abortWith(Response.status(401)
                    .entity(new ProblemDetail(
                            "Falta o es inválida la cabecera X-Api-Key.",
                            MDC.get("correlationId")))
                    .build());
            return;
        }
        ctx.setProperty("apiClient", client.get());
    }
}
```

Detalles que importan:

- **La comparación es de tiempo constante** (`MessageDigest.isEqual` sobre el hash), no
  `String.equals`. Comparar secretos con igualdad de cadenas filtra información por tiempo.
- **Se almacenan hashes**, no claves. La configuración lleva `sha256:…`.
- **`/api/salud` y `/api/proveedores` quedan abiertos**: el frontend los consulta antes de
  tener contexto de usuario, y no gastan dinero. Es una decisión consciente, no un olvido.

```properties
agente.seguridad.api-keys.equipo-preventa=sha256:<hash>
agente.seguridad.api-keys.desarrollo=sha256:<hash>
%dev.agente.seguridad.autenticacion-requerida=false
```

En desarrollo la autenticación se desactiva para no estorbar; en cualquier otro perfil es
obligatoria y **el arranque falla si no hay ninguna clave configurada**. Un valor por defecto
permisivo es cómo se despliega un servicio abierto sin querer.

### 3.2 Límite de tasa

```java
@RateLimit(value = 20, window = 1, windowUnit = ChronoUnit.HOURS, key = "apiClient")
public TenderAnalysisDto analyze(@Valid AnalyzeTenderRequest request) { … }
```

SmallRye Fault Tolerance —ya presente por `SPEC-BE-02`— incluye `@RateLimit`, así que no hay
biblioteca nueva.

| Endpoint | Límite por clave y hora | Razón |
|---|---|---|
| Análisis | 20 | Un analista revisa unos pocos pliegos al día |
| Propuesta | 20 | |
| Validación | 15 | Puede costar dos llamadas |
| Relevancia | 40 | Más barato por proceso |
| Chat | 100 mensajes | Conversación fluida sin barra libre |
| Búsqueda | 300 | Gratis; el límite es para proteger a Socrata |

Al superarse: **429 con `Retry-After`**, no un error opaco. El frontend lo traduce a un aviso
con la hora a la que se restablece (`SPEC-FE-03`).

Los valores son un punto de partida deliberadamente holgado; se ajustan con las métricas de
`SPEC-BE-05`.

### 3.3 Errores que no filtran (BE-A5)

```java
public record ProblemDetail(String detail, String correlationId) {}
```

```java
@ServerExceptionMapper
public Response providerFailure(ProviderException error) {
    String correlationId = MDC.get("correlationId");
    // El detalle del proveedor va al registro, nunca al cliente: puede contener la URL de
    // la petición, y en Google AI la clave viaja en la cadena de consulta.
    LOG.errorf(error, "[%s] Fallo del proveedor %s", correlationId, error.provider());
    return Response.status(error.httpStatus())
            .entity(new ProblemDetail(error.userMessage(), correlationId))
            .build();
}
```

La distinción clave: cada excepción tipada de `SPEC-BE-03` lleva **dos** mensajes.
`userMessage()` está escrito para el usuario y es seguro por construcción —lo redacta el
código, no el proveedor—. `getMessage()`/`getCause()` van al registro. No hay ninguna ruta
por la que el texto del proveedor llegue a la respuesta HTTP.

Se conserva la clave `detail` para no romper el frontend, que ya la lee, y se **añade**
`correlationId`. Es aditivo: ningún cliente existente se entera.

Regla equivalente en `SocrataProcurementCatalog`: las advertencias visibles al usuario son de
un catálogo cerrado de mensajes, nunca `e.getMessage()`.

### 3.4 Cotas de entrada (BE-M16)

En los DTO de entrada, que tras `SPEC-BE-01` viven en `adapter/in/rest/dto`:

```java
public record AnalyzeTenderRequest(
        @NotBlank
        @Size(min = 40, max = 400_000,
              message = "El pliego debe tener entre 40 y 400.000 caracteres. Si es más "
                      + "largo, analiza el anexo técnico por separado.")
        String textoPliego,
        @Size(max = 500)  String objetoContractual,
        @Size(max = 300)  String entidad,
        @Size(max = 200)  String modalidad,
        @PositiveOrZero @DecimalMax("1e15") BigDecimal valorEstimado,
        @Size(max = 5_000) String contextoProveedor,
        @Pattern(regexp = "[a-z]{2,20}")           String proveedor,
        @Pattern(regexp = "[a-zA-Z0-9._:\\-]{1,80}") String modelo) {}
```

| Campo | Cota | Razón |
|---|---|---|
| `textoPliego` | 400 000 | La mitad del límite actual. Un pliego mayor no cabe en la ventana de contexto útil de los modelos disponibles: rechazarlo con 422 es más honesto que enviarlo y facturar el fallo |
| `procesos` (relevancia) | 100 elementos | El frontend ya envía como máximo 40 |
| `mensajes` (chat) | 50 turnos | |
| `contexto` (chat) | 100 000 | El frontend ya recorta a 60 000 |
| `proveedor` / `modelo` | patrón | Cierra `BE-C2` en la frontera, además de en la fábrica |

El límite de cuerpo HTTP baja en consecuencia:

```properties
quarkus.http.limits.max-body-size=30M          # sigue: la carga de documentos lo necesita
quarkus.http.limits.max-form-attribute-size=2M
```

**El orden importa.** Bean Validation se ejecuta antes de entrar al recurso, así que un
cuerpo de 900 000 caracteres se rechaza con 422 sin construir prompt, sin serializar
contexto y sin llamar a nadie. Hoy todo ese trabajo se hace antes de descubrir el problema.

### 3.5 CORS por perfil (BE-M17)

```properties
# Base: sin orígenes. Un valor por defecto permisivo es cómo se despliega un agujero.
quarkus.http.cors.enabled=true
quarkus.http.cors.origins=
quarkus.http.cors.methods=GET,POST,OPTIONS
quarkus.http.cors.headers=Content-Type,X-Api-Key,X-Correlation-Id
quarkus.http.cors.exposed-headers=X-Correlation-Id,Retry-After
quarkus.http.cors.access-control-max-age=24H

%dev.quarkus.http.cors.origins=http://localhost:3000,http://127.0.0.1:3000
%test.quarkus.http.cors.origins=http://localhost:3000
%prod.quarkus.http.cors.origins=${AGENTE_CORS_ORIGINS}
```

Sin `AGENTE_CORS_ORIGINS` el arranque en producción falla, que es el comportamiento
deseado. Se elimina el puerto 5173 (Vite retirado) y se quitan `PUT`/`DELETE`, que ninguna
ruta usa.

Cabeceras de seguridad, baratas y ausentes hoy:

```properties
quarkus.http.header."X-Content-Type-Options".value=nosniff
quarkus.http.header."Referrer-Policy".value=no-referrer
quarkus.http.header."X-Frame-Options".value=DENY
```

### 3.6 Secretos (BE-B21)

| Entorno | Mecanismo |
|---|---|
| Desarrollo | `.env`, ignorado por git. Se conserva |
| CI | Sin claves. El perfil `test` las fuerza vacías y `TestIsolationGuardTest` lo verifica (`SPEC-BE-05`) |
| Producción | Variables de entorno del orquestador o gestor de secretos. **Nunca** un archivo en la imagen |

Acciones inmediatas:

1. Verificar, tras el `git init` de la fase 0, que `.env` no entra en el primer commit.
2. **Rotar la clave de Gemini** si el directorio se ha compartido de cualquier forma (copia,
   captura, respaldo en la nube). Cuesta dos minutos y elimina un riesgo latente.
3. Añadir un análisis de secretos (`gitleaks`) al CI de la fase 0.

### 3.7 Lo que este diseño deliberadamente no resuelve

- **No hay identidad de persona**, solo de cliente. No se puede saber *quién* analizó un
  pliego, solo con qué clave. Suficiente para controlar el gasto, insuficiente para
  auditoría. Si `SPEC-NT-02` exige trazabilidad individual, hace falta OIDC.
- **No hay cifrado en reposo del contenido de los pliegos**, porque no se almacena ninguno.
  Es una propiedad valiosa del diseño actual y conviene no perderla sin discutirlo.
- **La inyección de prompt no se mitiga aquí.** Un pliego hostil puede contener
  instrucciones dirigidas al modelo. La contención es que la salida se trata como datos, no
  como HTML ni como órdenes (`SPEC-FE-03` §Markdown saneado).

---

## 4. Plan de ejecución

| Paso | Contenido | Riesgo |
|---|---|---|
| 1 | Cotas de entrada. Cambio local, efecto inmediato. | Bajo |
| 2 | `ProblemDetail` + mensajes seguros + detalle solo al registro. Cierra `BE-A5`. | Bajo |
| 3 | CORS por perfil + cabeceras de seguridad. | Bajo |
| 4 | Autenticación por clave + `/api/salud` y `/api/proveedores` abiertos. | **Medio**: rompe clientes existentes |
| 5 | Límite de tasa por clave. | Bajo |
| 6 | `gitleaks` en CI; verificación de `.env`; rotación de la clave. | Bajo |

El paso 4 exige el cambio coordinado de `SPEC-FE-02` (el cliente debe enviar la cabecera) y
no debe entregarse por separado.

---

## 5. Criterios de aceptación

1. Una petición sin `X-Api-Key` a cualquier endpoint que invoque al modelo devuelve 401 y
   **no realiza ninguna llamada saliente**, verificado con WireMock.
2. `/api/salud` y `/api/proveedores` siguen respondiendo sin clave.
3. La comparación de claves es de tiempo constante y solo se almacenan hashes.
4. Superar el límite devuelve 429 con `Retry-After`.
5. Existe una prueba que inyecta un error de proveedor cuyo mensaje contiene
   `key=AIzaSyTEST` y verifica que la respuesta HTTP **no** contiene esa cadena, y que el
   registro sí.
6. Un `textoPliego` de 900 000 caracteres devuelve 422 sin llamar al proveedor.
7. Arrancar con `%prod` sin `AGENTE_CORS_ORIGINS` falla con un mensaje explícito.
8. Arrancar con `%prod` sin ninguna clave configurada falla.
9. `gitleaks` pasa sobre todo el historial.
10. Ninguna respuesta de error contiene texto originado en un sistema externo.

---

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| La autenticación rompe el frontend en desarrollo. | `%dev` la desactiva; el frontend envía la cabecera solo si está configurada. |
| Los mensajes genéricos dificultan el diagnóstico al usuario. | Los mensajes de las excepciones tipadas siguen siendo accionables («cuota agotada», «modelo inexistente»); lo que se elimina es el texto crudo del proveedor. El `correlationId` cubre el resto. |
| Los límites de tasa molestan en un pico legítimo. | Son por clave y holgados; se ajustan con métricas. El 429 lleva `Retry-After`, no es opaco. |
| Una clave de API en una cabecera se filtra en registros de proxies. | Se documenta no registrar `X-Api-Key` y se prohíbe explícitamente en la configuración de acceso. Es la limitación conocida del esquema y el motivo por el que OIDC es el destino. |

---

## 7. Fuera de alcance

- OIDC y usuarios individuales.
- Cifrado en reposo (no se almacena nada).
- Auditoría de contenido (`SPEC-NT-02`).
- Defensas específicas contra inyección de prompt más allá de tratar la salida como datos.
