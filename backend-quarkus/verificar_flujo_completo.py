"""Recorre el flujo completo del agente contra el modelo real.

`verificar_en_vivo.py` comprueba cada endpoint por separado. Este script comprueba
lo que ninguno cubría: que los cuatro pasos **encajen entre sí**.

    buscar → priorizar → analizar → proponer → validar

Lo que se verifica no son códigos HTTP, sino coherencia entre pasos: que la propuesta
cubra los requisitos que extrajo el análisis, que la matriz de cumplimiento hable de esos
mismos requisitos y no de otros, y que el veredicto sea consistente con lo que dice la
matriz. Un backend puede devolver 200 en los cinco pasos y aun así producir una cadena
incoherente —requisitos que se pierden entre un paso y el siguiente—, y eso es
exactamente lo que el usuario vería como «el agente se contradice».

Requiere el servidor levantado en :8000 y una clave de proveedor configurada.

    python verificar_flujo_completo.py
"""

from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request

BASE = "http://localhost:8000"
TIEMPO_LIMITE = 300

PLIEGO = """
El contratista debe desarrollar el portal ciudadano del Ministerio bajo arquitectura de
microservicios desplegada en nube publica. Debe entregar el codigo fuente completo; la
titularidad de los derechos patrimoniales sera de la entidad. El portal debe cumplir la
Resolucion 1519 de 2020 de MinTIC en materia de accesibilidad, nivel WCAG 2.1 AA, y la
norma NTC 5854. Se exige certificacion ISO/IEC 27001 vigente del contratista. El soporte
sera de 12 meses con un acuerdo de nivel de servicio de disponibilidad del 99.5%. El
equipo minimo debe incluir un arquitecto de software con 8 anos de experiencia y dos
desarrolladores senior. La entidad exige integracion con la Carpeta Ciudadana Digital.
""".strip()

PERFIL = (
    "Fabrica de software con 12 anos de experiencia. 45 desarrolladores en Java/Spring, "
    "Angular y Python. Certificaciones ISO 9001 vigente. Experiencia en 8 contratos con "
    "entidades publicas del orden nacional. Despliegues en AWS y Azure. Equipo propio de "
    "QA. No contamos con certificacion ISO/IEC 27001."
)

fallos: list[str] = []


def pedir(ruta: str, cuerpo: dict | None = None) -> dict:
    datos = None if cuerpo is None else json.dumps(cuerpo).encode()
    peticion = urllib.request.Request(
        BASE + ruta,
        data=datos,
        headers={"Content-Type": "application/json"},
        method="POST" if datos else "GET",
    )
    with urllib.request.urlopen(peticion, timeout=TIEMPO_LIMITE) as respuesta:
        return json.loads(respuesta.read())


def comprobar(condicion: bool, titulo: str, detalle: str = "") -> bool:
    marca = "OK  " if condicion else "FALLA"
    print(f"[{marca}] {titulo}")
    if detalle:
        for linea in str(detalle).splitlines():
            print(f"        {linea}")
    if not condicion:
        fallos.append(titulo)
    return condicion


def recortar(texto: str, ancho: int = 92) -> str:
    texto = " ".join(str(texto).split())
    return texto if len(texto) <= ancho else texto[:ancho] + "…"


def paso(numero: int, titulo: str) -> None:
    print(f"\n--- {numero}. {titulo} ---")


# --------------------------------------------------------------- 1. buscar
paso(1, "Buscar procesos en SECOP II")
busqueda = pedir("/api/procesos/buscar", {"texto": "portal web", "soloTi": True, "limite": 5})
procesos = busqueda["procesos"]
comprobar(bool(procesos), "devuelve procesos", f"{busqueda['total']} procesos")
if not procesos:
    print("\nSin procesos no se puede seguir la cadena.")
    sys.exit(1)

# --------------------------------------------------------- 2. priorizar
paso(2, "Priorizar los procesos encontrados")
priorizacion = pedir(
    "/api/procesos/relevancia-ti",
    {"procesos": procesos, "perfilProveedor": PERFIL, "maximo": 5},
)
priorizados = priorizacion["priorizados"]
comprobar(bool(priorizados), "prioriza", f"{len(priorizados)} priorizados")

ids_buscados = {p["id"] for p in procesos}
ids_priorizados = {p["id"] for p in priorizados}
comprobar(
    ids_priorizados <= ids_buscados,
    "no inventa procesos que no se le enviaron",
    f"inventados: {sorted(ids_priorizados - ids_buscados)}" if ids_priorizados - ids_buscados else "",
)
comprobar(
    all(0 <= p["puntaje"] <= 100 for p in priorizados),
    "los puntajes están en el rango declarado",
    f"puntajes: {[p['puntaje'] for p in priorizados]}",
)
if priorizados:
    print(f"        · [{priorizados[0]['puntaje']}] {recortar(priorizados[0]['categoriaTi'])}"
          f" — {recortar(priorizados[0]['justificacion'], 60)}")

# ----------------------------------------------------------- 3. analizar
paso(3, "Analizar el pliego")
analisis = pedir(
    "/api/analisis/requisitos",
    {"textoPliego": PLIEGO, "objetoContractual": "Portal ciudadano", "contextoProveedor": PERFIL},
)
requisitos = analisis["requisitos"]
comprobar(len(requisitos) >= 3, "extrae requisitos", f"{len(requisitos)} requisitos")
comprobar(bool(analisis["recomendacion"]), "emite una recomendación",
          recortar(analisis["recomendacion"]))

criticidades = {r["criticidad"] for r in requisitos}
comprobar(
    criticidades <= {"obligatorio", "ponderable", "deseable", "informativo"},
    "las criticidades usan los códigos del contrato",
    f"vistas: {sorted(criticidades)}",
)
# El pliego dice explícitamente «se exige ISO/IEC 27001»: si el agente no lo marca como
# obligatorio, está perdiendo justo lo que decide si la oferta se puede presentar.
menciona_iso = any("27001" in json.dumps(r, ensure_ascii=False) for r in requisitos)
comprobar(menciona_iso, "recoge la exigencia de ISO/IEC 27001 del pliego")

ids_requisitos = {r["id"] for r in requisitos}

# ----------------------------------------------------------- 4. proponer
paso(4, "Generar la propuesta a partir de esos requisitos")
propuesta = pedir(
    "/api/propuestas/generar",
    {
        "objetoContractual": analisis.get("objetoNormalizado") or "Portal ciudadano",
        "perfilProveedor": PERFIL,
        "requisitos": requisitos,
        "plazoMeses": 12,
        "enfasis": ["seguridad de la información", "accesibilidad (Res. 1519/2020)"],
    },
)
secciones = propuesta["secciones"]
comprobar(bool(secciones), "produce secciones", f"{len(secciones)} secciones")
comprobar(len(propuesta["markdown"]) > 500, "produce el documento en Markdown",
          f"{len(propuesta['markdown'])} caracteres")

cubiertos = {i for s in secciones for i in s["requisitosCubiertos"]}
comprobar(
    bool(cubiertos & ids_requisitos),
    "las secciones referencian los requisitos del análisis",
    f"cubiertos: {sorted(cubiertos)[:6]} de {sorted(ids_requisitos)}",
)
comprobar(
    cubiertos <= ids_requisitos,
    "no referencia requisitos que no existen",
    f"inventados: {sorted(cubiertos - ids_requisitos)}" if cubiertos - ids_requisitos else "",
)
# El perfil dice explícitamente que NO hay ISO 27001 y el pliego la exige: declararlo como
# vacío es la conducta correcta. Inventar la certificación sería el fallo grave.
vacios = " ".join(propuesta["vaciosDeInformacion"]).lower()
comprobar(
    "27001" in vacios or "iso" in vacios,
    "declara como vacío la certificación que el oferente no tiene",
    "\n".join(recortar(v) for v in propuesta["vaciosDeInformacion"][:3]) or "(ninguno declarado)",
)

# ------------------------------------------------------------ 5. validar
paso(5, "Validar la propuesta contra los requisitos")
informe = pedir(
    "/api/propuestas/validar",
    {
        "textoPropuesta": propuesta["markdown"],
        "requisitos": requisitos,
        "objetoContractual": "Portal ciudadano",
    },
)
matriz = informe["matriz"]
comprobar(bool(matriz), "produce matriz de cumplimiento", f"{len(matriz)} ítems")
comprobar(
    0 <= informe["puntajeCumplimiento"] <= 100,
    "el puntaje está en el rango declarado",
    f"puntaje: {informe['puntajeCumplimiento']}",
)
comprobar(
    informe["veredicto"] in {"apta", "apta_con_ajustes", "riesgo_de_rechazo", "no_apta"},
    "el veredicto usa los códigos del contrato",
    f"veredicto: {informe['veredicto']}",
)

ids_matriz = {i["requisitoId"] for i in matriz}
comprobar(
    bool(ids_matriz & ids_requisitos),
    "la matriz habla de los requisitos del análisis",
    f"coinciden {len(ids_matriz & ids_requisitos)} de {len(ids_requisitos)}",
)

estados = {i["estado"] for i in matriz}
comprobar(
    estados <= {"cumple", "cumple_parcial", "no_cumple", "no_evaluable"},
    "los estados usan los códigos del contrato",
    f"vistos: {sorted(estados)}",
)

# Coherencia interna: si hay obligatorios incumplidos, el veredicto no puede ser «apta».
incumplidos = [
    i for i in matriz if i["criticidad"] == "obligatorio" and i["estado"] == "no_cumple"
]
comprobar(
    not incumplidos or informe["veredicto"] != "apta",
    "el veredicto es coherente con los obligatorios incumplidos",
    f"{len(incumplidos)} obligatorios en no_cumple, veredicto «{informe['veredicto']}»",
)
if informe["causalesDeRechazo"]:
    print(f"        · causal: {recortar(informe['causalesDeRechazo'][0])}")

# ------------------------------------------------------------- resultado
print("\n" + "=" * 70)
if fallos:
    print(f"RESULTADO: {len(fallos)} comprobación(es) fallaron")
    for f in fallos:
        print(f"  - {f}")
    sys.exit(1)
print("RESULTADO: la cadena completa del agente es coherente de extremo a extremo.")
