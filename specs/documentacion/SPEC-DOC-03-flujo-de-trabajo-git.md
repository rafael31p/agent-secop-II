# SPEC-DOC-03 · Flujo de trabajo con GitFlow

| | |
|---|---|
| **Estado** | Aceptada |
| **Prioridad** | 🔴 Alta |
| **Cierra** | TR-A2 (parcialmente: el versionado existía, el flujo no) |
| **Depende de** | Fase 0 del plan (el repositorio y CI ya existen) |
| **Esfuerzo** | 0,5 jornadas de montaje; el resto es disciplina |

---

## 1. Problema

**Todo el trabajo hasta hoy se hizo directamente sobre `main`.** Veintitrés commits, entre
ellos la migración del frontend, la fase 0, la fase 1 completa y la reestructuración
hexagonal de la fase 2, entraron sin pasar por ninguna rama de trabajo.

Funcionó porque hubo una sola persona trabajando y porque CI corría en cada empuje. Pero
tiene tres consecuencias que ya se pueden nombrar:

- **`main` estuvo roja dos veces.** La primera compilación falló por el permiso de
  ejecución de `mvnw` y por el rango de gitleaks; la segunda, porque el `.gitignore` dejó
  seis archivos fuera del repositorio. En ambos casos el defecto llegó a la rama que se
  supone desplegable. Con una rama de integración por delante, `main` no se habría
  enterado.
- **No hay revisión posible.** Un cambio como el de la fase 2 —que borra tres paquetes y
  mueve cuarenta archivos— no se puede revisar después de estar en `main`; se revisa antes
  o no se revisa.
- **No hay forma de decir «esta es la versión desplegada».** La etiqueta
  `v0.2.0-legacy` marca lo que se retiró, no lo que está vivo.

Nada de esto es urgente hoy. Se escribe ahora porque el coste de introducir un flujo crece
con cada commit que se hace sin él.

---

## 2. Decisión

Adoptar **GitFlow**, en su forma clásica y sin herramientas adicionales: `git` normal, sin
la extensión `git-flow`. Los comandos son cuatro y aprenderlos cuesta menos que instalar y
mantener otra dependencia.

Se descartan las alternativas por motivos concretos, no por gusto:

- **Trunk-based development** encaja mal aquí. Presupone integración continua real y
  despliegue frecuente; este proyecto avanza por fases largas y muy invasivas —la fase 2
  tocó cuarenta archivos— que no se pueden integrar a medias sin dejar el sistema en un
  estado que no es ni el viejo ni el nuevo.
- **GitHub Flow** (solo `main` y ramas de función) no da dónde acumular varias fases antes
  de declarar una versión. Es más simple, y sería la elección correcta si hubiera
  despliegue continuo; no lo hay.

---

## 3. Diseño

### 3.1 Las ramas y qué significa cada una

| Rama | Vive | Qué es | Quién la toca |
|---|---|---|---|
| `main` | Siempre | Lo desplegable. Cada commit lleva etiqueta de versión. | Nadie directamente |
| `develop` | Siempre | Integración. De aquí sale la próxima versión. | Nadie directamente |
| `feature/*` | Días | Un cambio con sentido propio | Quien lo hace |
| `release/*` | Horas o días | Estabilizar una versión: documentación, número de versión, correcciones menores | Quien publica |
| `hotfix/*` | Horas | Arreglo urgente de algo que está en producción | Quien apaga el fuego |

La regla que da sentido a todo lo demás: **`main` y `develop` no reciben commits
directos.** Todo entra por fusión desde una rama de trabajo.

### 3.2 Nombres

```
feature/fase-3-resiliencia
feature/prompts-a-qute
release/0.3.0
hotfix/0.2.1-fuga-en-el-limitador
```

En minúsculas y con guiones. Si el cambio corresponde a un punto del plan de mejora, el
nombre lo dice: `feature/fase-4-frontend` se entiende sin abrir nada.

### 3.3 El ciclo, en comandos

```bash
# Empezar un cambio
git switch develop && git pull
git switch -c feature/fase-3-resiliencia

# ... trabajo y commits ...

# Publicarlo y abrir la solicitud de cambios
git push -u origin feature/fase-3-resiliencia
gh pr create --base develop --fill

# Cuando CI esté en verde
gh pr merge --merge --delete-branch
```

**La integración va por solicitud de cambios, no por fusión local.** La primera versión de
esta spec proponía `git merge --no-ff` en local y `git push`, que es el GitFlow de manual;
al hacerlo, GitHub respondió «Bypassed rule violations for refs/heads/develop: 3 of 3
required status checks are expected». Es decir: la protección de rama y la fusión local
son incompatibles, porque un empuje del commit de fusión **es** un empuje directo. Se
saltó porque quien lo hizo era administrador del repositorio.

Corregido de dos maneras: la protección se aplica ahora también a los administradores
—`enforce_admins`—, y la integración pasa por solicitud de cambios, que es como GitHub
comprueba que CI pasó antes de dejar fusionar.

Se usa `--merge` y no `--squash` ni `--rebase`, por el mismo motivo por el que el GitFlow
clásico exige `--no-ff`: el commit de fusión deja en el historial dónde empezó y dónde
acabó cada cambio. Con `--squash`, una rama de veinte commits se convierte en uno solo y
se pierde el detalle; con `--rebase`, se pierde la unidad.

Publicar una versión:

```bash
git switch -c release/0.3.0 develop
# subir el número de versión en pom.xml y package.json, cerrar el CHANGELOG
git switch main && git merge --no-ff release/0.3.0
git tag -a v0.3.0 -m "Fases 3 y 4"
git switch develop && git merge --no-ff release/0.3.0
git push --all && git push --tags
```

La fusión hacia `develop` al final es la que más se olvida, y omitirla hace que las
correcciones de la estabilización se pierdan en la siguiente versión.

### 3.4 Dónde corre CI

| Evento | Qué corre |
|---|---|
| Empuje a `feature/*` | Todo: backend, frontend, secretos |
| Solicitud de cambios hacia `develop` | Todo |
| Empuje a `develop` | Todo |
| Empuje a `main` | Todo |

No hay atajos por rama. Una suite que solo corre en algunas ramas es una suite en la que
no se puede confiar para decidir una fusión.

### 3.5 Protección de ramas

En GitHub, sobre `main` y `develop`:

- Prohibido el empuje directo.
- Exigir que CI pase antes de fusionar.
- Exigir que la rama esté al día con el destino.
- **Aplicar todo lo anterior también a los administradores.** Sin esto la protección es
  decorativa para quien más la necesita: se comprobó empujando una fusión local a
  `develop` y GitHub la dejó pasar avisando de que se saltaba tres comprobaciones.

Con un solo desarrollador la protección parece burocracia, y no lo es: es lo que impide
que un `git push` distraído a las once de la noche repita lo que ya pasó dos veces.

### 3.6 Mensajes de commit

Se conserva lo que ya se venía haciendo, que funciona: **español, en imperativo, con el
porqué en el cuerpo**. Un mensaje describe qué problema resuelve y qué se descartó, no qué
líneas cambiaron —eso ya lo dice el diff—.

Cuando el commit cierra un hallazgo del diagnóstico, lo declara al final:

```
Cierra: BE-C4
```

No se adopta Conventional Commits. Su valor está en generar el CHANGELOG automáticamente,
y un CHANGELOG generado a partir de prefijos dice «feat: añade puerto» donde este proyecto
necesita decir por qué el puerto hacía falta.

### 3.7 Qué se hace con lo ya hecho

`main` conserva los veintitrés commits tal cual. **No se reescribe historia publicada**:
el repositorio es público y reescribirlo rompería cualquier clon. `develop` nace del
`main` actual, y a partir de ahí el flujo aplica.

---

## 4. Plan de ejecución

| Paso | Contenido |
|---|---|
| 1 | Crear `develop` desde `main` y empujarla |
| 2 | Declararla rama por defecto del repositorio, para que las solicitudes de cambios apunten ahí |
| 3 | Extender CI a `develop`, `feature/*`, `release/*` y `hotfix/*` |
| 4 | Proteger `main` y `develop` |
| 5 | Etiquetar `v0.2.0` sobre el estado actual: lo que hay hoy es desplegable y no está marcado |
| 6 | Documentar el flujo en `docs/contribuir.md` cuando SPEC-DOC-01 cree esa carpeta |

---

## 5. Criterios de aceptación

1. `develop` existe, es la rama por defecto y CI corre sobre ella.
2. Un empuje directo a `main` o a `develop` es rechazado por el servidor, **incluso
   siendo administrador**.
3. CI corre sobre `feature/*` sin configuración adicional.
4. El estado desplegable actual está etiquetado.
5. El historial de `main` no se ha reescrito.

---

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| Con un solo desarrollador, el flujo se percibe como ceremonia y se abandona. | Los pasos son cuatro comandos. El coste real es el `--no-ff`, que no cuesta nada. |
| Ramas de función largas divergen y la fusión duele. | Cada punto del plan es una rama. Si una pasa de una semana, es señal de que el punto era demasiado grande. |
| La protección estorba a quien trabaja solo. | Estorba exactamente cuando debe: al empujar sin que CI haya pasado. |

---

## 7. Fuera de alcance

- La extensión `git-flow`. `git` basta.
- Firmar commits (GPG). Recomendable, pero es otra decisión.
- Versionado semántico estricto del contrato HTTP. Lo trata SPEC-BE-07.
- Despliegue automático al etiquetar. No hay entorno de despliegue todavía.
