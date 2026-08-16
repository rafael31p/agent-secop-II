# SPEC-NT-02 · Tratamiento de datos, terceros y cumplimiento

| | |
|---|---|
| **Estado** | Propuesta |
| **Prioridad** | 🔴 Alta — **bloqueante para uso real** |
| **Cierra** | TR-C1; complementa FE-M12 y BE-B21 |
| **Depende de** | Nada. Es lo primero del plano no técnico |
| **Esfuerzo** | 2–3 jornadas + revisión jurídica externa |
| **Audiencia** | Propietario, responsable de datos, asesoría jurídica |

> **Aviso.** Este documento identifica riesgos y propone controles desde la ingeniería. **No
> es un concepto jurídico.** Las referencias normativas son puntos de partida para que quien
> tenga competencia legal las valide. La ironía no se nos escapa: es exactamente el mismo
> descargo que la herramienta hace sobre sus propias salidas.

---

## 1. Problema

### 1.1 Qué sale del control del usuario, hoy, sin que se le diga

| Caso de uso | Qué se transmite | A dónde |
|---|---|---|
| Analizar pliego | **Texto íntegro del pliego**, más el perfil del oferente como «contexto» | Google, OpenAI, Anthropic, DeepSeek u Ollama |
| Generar propuesta | Requisitos, **perfil y capacidades declaradas del oferente**, énfasis | Ídem |
| Validar propuesta | **Texto íntegro de la propuesta técnica** + requisitos | Ídem |
| Priorizar procesos | Datos públicos de SECOP + perfil del oferente | Ídem |
| Chat | Historial de la conversación + contexto del pliego o de la propuesta | Ídem |

La interfaz **no lo dice en ninguna parte**. El pie lleva un descargo sobre el valor jurídico
de las respuestas; nada sobre a dónde viajan los datos. El selector de proveedor permite
elegir entre cinco empresas sin explicar que la elección determina a qué empresa se le envía
el pliego.

### 1.2 Por qué importa más de lo que parece

Tres características del dominio elevan el riesgo por encima del caso genérico:

**La propuesta técnica es información comercial sensible antes de la adjudicación.** Contiene
metodología, equipo, precios implícitos, alianzas y estrategia de la oferta. Que salga hacia
un tercero es una decisión que el oferente debe tomar con conocimiento, no por defecto.

**El perfil del oferente contiene datos de la organización y, con frecuencia, de personas.**
El campo «Perfil y capacidades del oferente» invita literalmente a escribir «45
desarrolladores (Java/Spring, Angular, Python)… equipo de QA y seguridad ofensiva propio».
Un usuario que pegue hojas de vida —perfectamente plausible, porque los pliegos exigen
acreditar personal— estará transmitiendo datos personales a un tercero en otra jurisdicción.

**El pliego puede no ser público todavía.** El caso de uso incluye analizar el **proyecto de
pliego** para radicar observaciones. En esa fase puede circular material no publicado.

### 1.3 Marco normativo aplicable, para validación jurídica

| Norma | Por qué aparece |
|---|---|
| **Ley 1581 de 2012** y Decreto 1074 de 2015 | Protección de datos personales. Aplica si el perfil o el pliego contienen datos de personas naturales. Exige autorización previa, informada y expresa, y tiene reglas propias para la **transferencia y transmisión internacional** |
| **Ley 1712 de 2014** | Transparencia y acceso a la información pública. Relevante para clasificar qué del pliego es público |
| **Ley 80 de 1993**, **Ley 1150 de 2007**, **Decreto 1082 de 2015** | Régimen de contratación estatal: deberes de selección objetiva y reserva. Marco del contenido que se procesa |
| Términos de uso de cada proveedor | Determinan retención y si el contenido se usa para entrenar |

**Preguntas concretas para la revisión jurídica:**

1. ¿Enviar un pliego y una propuesta a un proveedor de modelos en Estados Unidos constituye
   transmisión internacional de datos personales cuando el material incluye hojas de vida?
2. ¿Basta la autorización del usuario de la herramienta, o hace falta la de los titulares
   cuyos datos aparecen en el material?
3. ¿Los términos de la capa gratuita de Gemini —la configuración por defecto del proyecto—
   permiten el uso para entrenamiento? Si sí, ¿es compatible con material de una oferta?
4. ¿Qué obligación de aviso hay hacia el cliente cuyo pliego se procesa, si la herramienta se
   usa como servicio a terceros?

La pregunta 3 no es retórica. Las capas gratuitas de varios proveedores tienen condiciones de
retención y uso distintas de las de pago. El proyecto viene configurado con Gemini por
defecto y `ESTADO.md` documenta que se usa el plan gratuito.

### 1.4 Qué se almacena, y dónde

| Dato | Dónde | Cuánto dura | Control del usuario |
|---|---|---|---|
| Pliego, análisis, propuesta | `sessionStorage` | Lo que dura la pestaña | **Ninguno hoy** (`FE-M12`) |
| Perfil del oferente | `localStorage` | Indefinido | **Ninguno hoy** |
| Selección de proveedor | `localStorage` | Indefinido | Cambiable |
| Contenido de las peticiones | Servidor: **no se almacena** | — | — |
| Contenido en el proveedor | Según sus términos | Según sus términos | Ninguno |

**Que el servidor no almacene nada es una propiedad valiosa del diseño actual y hay que
protegerla deliberadamente.** Cualquier funcionalidad futura —auditoría, caché de respuestas,
histórico de análisis— la rompe. Debe registrarse como una decisión, no seguir siendo un
accidente feliz.

El otro lado: hay contenido confidencial en el navegador y **el usuario no tiene ningún botón
para borrarlo**. `limpiar()` existe en el código y no se invoca desde ninguna parte.

---

## 2. Decisión

Cinco compromisos, en orden de urgencia:

1. **Divulgación explícita**: la interfaz dice a qué empresa se envía el material, antes de
   enviarlo.
2. **El usuario puede borrar** su espacio de trabajo en cualquier momento.
3. **El operador elige el nivel de confidencialidad** que la instalación admite, y esa
   elección restringe los proveedores disponibles.
4. **Minimización**: no sale nada que no sea necesario para la tarea.
5. **El servidor sigue sin almacenar contenido**, y eso se convierte en un requisito
   arquitectónico registrado.

---

## 3. Diseño

### 3.1 Divulgación en el punto de decisión

En la cabecera, junto al selector, permanentemente visible:

> **El material que analices se envía a Google (Gemini 3.6 Flash).**
> [Qué se envía y qué no →]

Cambia con el proveedor seleccionado, porque el destinatario cambia. Poner esto donde se
elige el proveedor convierte una decisión técnica en una decisión informada.

El enlace abre una explicación breve, escrita para alguien sin formación técnica:

- Qué se envía en cada caso de uso, en una tabla.
- Qué **no** se envía nunca: nada del navegador que no hayas pegado o subido; el servicio no
  guarda copia.
- Que el destinatario depende del proveedor elegido, con enlace a la política de cada uno.
- Que Ollama procesa en local y no envía nada fuera.
- Recomendación explícita: **si el material es reservado, usa Ollama o no uses la
  herramienta.**

Además, una confirmación **la primera vez** que se sube un documento o se pega un texto de
más de N caracteres, con «no volver a mostrar». Una vez, no en cada uso: un aviso que sale
siempre deja de leerse.

### 3.2 Borrado del espacio de trabajo

Implementación en `SPEC-FE-03` §3.7. Aquí se fija el requisito de producto:

- Acción **visible**, no escondida en un menú.
- Confirmación que enumera qué se pierde.
- Borra `sessionStorage` **y** el perfil de `localStorage`, con casilla para conservar el
  perfil —que es del oferente, no del pliego, y volver a escribirlo cada vez es hostil—.
- Aviso en la interfaz: «Tu trabajo se guarda en este navegador hasta que cierres la pestaña
  o pulses Borrar.»

### 3.3 Perfiles de confidencialidad

El operador decide, por configuración, qué admite la instalación:

| Perfil | Proveedores habilitados | Para qué |
|---|---|---|
| `abierto` | Los cinco | Material público. Comportamiento actual |
| `restringido` | Solo los que tengan acuerdo de tratamiento firmado | Uso comercial con clientes |
| `local` | Solo Ollama | Material reservado; nada sale de la red |

```properties
agente.datos.perfil-confidencialidad=abierto
agente.datos.proveedores-permitidos=gemini,openai,anthropic,deepseek,ollama
```

`GET /api/proveedores` devuelve los no permitidos como no configurados, con el motivo
—reutilizando el mecanismo que ya existe para las claves ausentes, sin inventar nada—. El
selector los muestra deshabilitados y explicados, que es justo lo que ya sabe hacer.

Esta es la pieza que hace la herramienta utilizable en un entorno con requisitos serios de
confidencialidad, sin renunciar a la comodidad en los que no los tienen.

### 3.4 Minimización

Qué se corrige de lo que hoy se envía de más:

| Hoy | Cambio |
|---|---|
| `priorizarProcesos` envía el perfil del oferente completo | Solo si el usuario marca «priorizar según mi perfil». Sin marcar, se clasifica por categoría TI y no sale nada del oferente |
| El chat envía el pliego completo si no hay requisitos | Ya recorta a 60 000 caracteres. Se añade aviso de cuánto se envía — hoy se muestra la cifra, falta decir a dónde |
| La validación envía pliego **y** propuesta si no hay requisitos | Se recomienda activamente analizar primero (`SPEC-NT-01` §3.5): además de más barato, envía menos |
| El perfil viaja como «contexto» en el análisis | Se hace opcional con una casilla. Sirve para calibrar riesgos, no es imprescindible |

Regla general, aplicable a cualquier caso de uso futuro: **si la tarea funciona sin ese dato,
ese dato no sale.**

### 3.5 El servidor no almacena contenido

Requisito arquitectónico registrado, no accidente:

- No se persiste el contenido de peticiones ni respuestas.
- Los registros llevan `correlationId`, proveedor, modelo, duración y resultado.
  **Nunca** texto de pliego, propuesta o prompt (criterio de aceptación en `SPEC-BE-05`).
- Las trazas de OpenTelemetry no propagan contenido a los atributos del tramo.
- `registrar-peticiones` (que vuelca prompts al registro) permanece en `false` y se
  documenta como **prohibido en producción**.

Cualquier propuesta futura de auditoría de contenido, caché de respuestas o histórico
requiere revisar esta especificación primero. Se registra como **ADR-0010**.

### 3.6 Secretos

Ya cubierto en `SPEC-BE-06` §3.6. Se repite aquí lo que es decisión de gestión y no técnica:

- La clave de Gemini presente en `backend-quarkus/.env` **se rota** antes de compartir el
  repositorio de cualquier forma.
- En producción, las claves salen de un gestor de secretos, nunca de un archivo en la imagen.
- Una clave por entorno; nunca la misma en desarrollo y en producción.

### 3.7 Qué decir si la herramienta se ofrece a terceros

Si deja de ser interna y se ofrece como servicio, hacen falta —y son decisiones de negocio,
no de ingeniería—: política de tratamiento de datos publicada, autorización explícita
registrada, canal para ejercer derechos de los titulares, y acuerdos con los proveedores de
modelos que soporten la cadena de responsabilidad.

Hasta entonces, el uso debería limitarse a material **propio o público**, y eso debe estar
escrito en el README.

---

## 4. Plan de ejecución

| Paso | Contenido | Quién | Bloqueante |
|---|---|---|---|
| 1 | Inventario de datos de §1.1 revisado y aprobado. | Propietario | — |
| 2 | Consulta jurídica sobre las cuatro preguntas de §1.3. | Asesoría | **Sí, para uso con clientes** |
| 3 | Revisar los términos de uso de los cinco proveedores, con foco en el uso para entrenamiento en las capas gratuitas. | Propietario | **Sí** |
| 4 | Divulgación en la cabecera + página explicativa. | Producto + frontend | — |
| 5 | Borrado del espacio de trabajo. | Frontend | — |
| 6 | Perfiles de confidencialidad. | Backend | — |
| 7 | Minimización (las cuatro filas de §3.4). | Ambos | — |
| 8 | ADR-0010 «el servidor no almacena contenido». | Arquitectura | — |
| 9 | Rotar la clave de Gemini. | Propietario | — |
| 10 | Nota en el README: solo material propio o público hasta completar 2 y 3. | Propietario | — |

Los pasos 4, 5 y 10 se pueden hacer **esta semana** y cambian la situación de forma
sustancial: el usuario sabe qué pasa con su material y puede borrarlo.

---

## 5. Criterios de aceptación

1. Antes de enviar material por primera vez, el usuario ve a qué empresa se envía y lo
   confirma.
2. La cabecera indica en todo momento el destinatario del material según el proveedor
   elegido.
3. Existe una acción visible de borrado que deja el navegador sin pliego, análisis ni
   propuesta.
4. Con `perfil-confidencialidad=local`, ningún proveedor remoto está seleccionable y el
   backend rechaza una petición que pida uno, aunque tenga clave configurada.
5. Ningún registro, métrica ni traza contiene texto de pliego, propuesta o prompt —hay una
   prueba que lo verifica.
6. `priorizarProcesos` no envía el perfil del oferente salvo que el usuario lo marque.
7. Existe ADR-0010 y está referenciado desde el README.
8. El README indica el alcance de uso permitido mientras los pasos 2 y 3 no estén cerrados.
9. La clave de Gemini del árbol de trabajo está rotada.

---

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| La divulgación asusta y reduce el uso. | Es el resultado correcto si el usuario decide que su material no debe salir. Una herramienta que oculta esto para retener usuarios no es defendible. El perfil `local` da una salida a quien la necesite. |
| La consulta jurídica bloquea el desarrollo. | Solo bloquea el uso con material de clientes. El desarrollo con material público sigue. |
| Ollama como única opción confidencial da peor calidad. | Es cierto y ya está documentado en el propio código. Se dice claramente en la explicación en vez de dejar que el usuario lo descubra. |
| Los perfiles de confidencialidad añaden configuración que nadie usa. | Son diez líneas y reutilizan el mecanismo de «proveedor no disponible» que ya existe. El coste es bajo y habilitan un caso de uso que hoy está cerrado. |
| Se implementa la valoración 👍/👎 de `SPEC-NT-01` recogiendo contenido. | Explícitamente bloqueada hasta que esta spec esté resuelta. |

---

## 7. Fuera de alcance

- Redactar la política de tratamiento de datos: es un documento legal.
- Certificaciones (ISO 27001 y similares).
- Cifrado en reposo: no se almacena contenido, y así debe seguir.
- Anonimización automática del pliego antes de enviarlo. Interesante y frágil: un
  anonimizador que falla da una falsa sensación de seguridad, peor que no tenerlo.
