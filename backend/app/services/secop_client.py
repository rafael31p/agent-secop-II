"""Cliente de la API abierta de SECOP II (datos.gov.co / Socrata + SoQL)."""

from __future__ import annotations

import logging
import re
from typing import Any

import httpx

from ..config import Settings
from ..schemas import FiltroProcesos, ProcesoResumen, RespuestaProcesos

log = logging.getLogger(__name__)

# Alias verificados contra el esquema real del dataset `p6dx-8zbt` (SECOP II - Procesos
# de Contratación). Se mantienen alternativas porque el esquema ha cambiado entre
# versiones del dataset; se usa el primer alias presente en la fila.
# Para diagnosticar un cambio de esquema: `python inspeccionar_dataset.py`.
ALIAS_CAMPOS: dict[str, tuple[str, ...]] = {
    "id": ("id_del_proceso", "id_proceso"),
    "numero_proceso": ("referencia_del_proceso", "numero_del_proceso"),
    "entidad": ("entidad", "nombre_de_la_entidad", "nombre_entidad"),
    "nit_entidad": ("nit_entidad", "nit_de_la_entidad"),
    "departamento": ("departamento_entidad", "departamento"),
    "ciudad": ("ciudad_entidad", "ciudad", "ciudad_de_la_unidad_de"),
    "objeto": ("descripci_n_del_procedimiento", "nombre_del_procedimiento"),
    "modalidad": ("modalidad_de_contratacion", "modalidad_de_contrataci_n"),
    "estado": ("estado_del_procedimiento", "estado_resumen", "fase"),
    "valor": ("precio_base", "valor_total_adjudicacion"),
    "fecha_publicacion": ("fecha_de_publicacion_del", "fecha_de_publicacion"),
    "fecha_ultima_publicacion": (
        "fecha_de_ultima_publicaci",
        "fecha_de_publicacion_fase_3",
    ),
    "url": ("urlproceso", "url_del_proceso"),
    "codigo_unspsc": ("codigo_principal_de_categoria", "codigo_de_categoria_principal"),
    "duracion": ("duracion", "duraci_n"),
    "unidad_duracion": ("unidad_de_duracion",),
    "tipo_contrato": ("tipo_de_contrato",),
    "orden_entidad": ("ordenentidad",),
    "adjudicado": ("adjudicado",),
}

# Campos sobre los que se hace búsqueda de texto libre. Ambos existen en el dataset;
# `descripci_n_del_procedimiento` es el objeto extendido y `nombre_del_procedimiento`
# el título corto.
CAMPOS_TEXTO = ("descripci_n_del_procedimiento", "nombre_del_procedimiento")

PALABRAS_TI: dict[str, int] = {
    # núcleo software
    "software": 10, "aplicativo": 9, "aplicación": 7, "aplicaciones": 7,
    "desarrollo de software": 14, "fábrica de software": 14, "fabrica de software": 14,
    "sistema de información": 12, "sistema de informacion": 12,
    "plataforma tecnológica": 11, "plataforma tecnologica": 11,
    "portal web": 8, "sitio web": 7, "microservicios": 9, "api": 6,
    # infraestructura / nube
    "nube": 8, "cloud": 8, "datacenter": 9, "centro de datos": 9,
    "servidores": 7, "hosting": 7, "iaas": 8, "paas": 8, "saas": 8,
    "virtualización": 7, "virtualizacion": 7, "kubernetes": 9, "contenedores": 7,
    # seguridad
    "ciberseguridad": 12, "seguridad de la información": 11,
    "seguridad de la informacion": 11, "soc": 6, "siem": 8, "pentesting": 9,
    "ethical hacking": 9, "iso 27001": 9, "firewall": 7, "mspi": 9,
    # datos
    "datos abiertos": 7, "big data": 9, "analítica": 7, "analitica": 7,
    "inteligencia artificial": 10, "machine learning": 10, "business intelligence": 9,
    "bodega de datos": 8, "data warehouse": 8, "etl": 7,
    # servicios TI
    "mesa de ayuda": 9, "mesa de servicio": 9, "help desk": 8, "soporte técnico": 8,
    "soporte tecnico": 8, "outsourcing tecnológico": 10, "interventoría tecnológica": 9,
    "consultoría en tecnología": 10, "arquitectura empresarial": 9,
    # conectividad / hardware
    "conectividad": 7, "canal dedicado": 7, "fibra óptica": 6, "fibra optica": 6,
    "telecomunicaciones": 7, "computadores": 6, "equipos de cómputo": 7,
    "equipos de computo": 7, "impresoras": 4, "licenciamiento": 8, "licencias": 6,
    # gobierno digital
    "gobierno digital": 11, "transformación digital": 10, "transformacion digital": 10,
    "interoperabilidad": 9, "firma electrónica": 8, "firma electronica": 8,
    "gestión documental electrónica": 8, "erp": 8, "crm": 7,
    "tecnologías de la información": 11, "tecnologias de la informacion": 11,
    "tic": 5,
}

# Prefiltro SoQL para `solo_ti=True`. Debe ser preciso: un término demasiado genérico
# (p. ej. "inform", que casa con "información" e "informe") agota el `$limit` con falsos
# positivos que luego el puntaje local descarta, devolviendo cero resultados.
TERMINOS_SOQL_TI = (
    "software", "tecnolog", "sistema de informaci", "aplicativo", "aplicaci",
    "plataforma", "ciberseguridad", "seguridad de la informaci", "nube", "cloud",
    "licenciamiento", "licencias de uso", "conectividad", "telecomunicaci",
    "computo", "cómputo", "informatic", "informátic", "base de datos",
    "datos abiertos", "gobierno digital", "transformaci n digital", "digital",
    "servidor", "hosting", "mesa de ayuda", "mesa de servicio", "portal web",
    "interoperabilidad", "TIC",
)

# Puntaje mínimo de la heurística local para considerar que un proceso es de TI.
UMBRAL_TI = 8

# Cuando se filtra por TI, se pide más de lo solicitado a la API porque el prefiltro
# SoQL es de alto recall y el filtro fino se aplica en local. El mínimo evita que una
# petición de pocos resultados traiga una muestra demasiado pequeña para filtrar.
FACTOR_SOBREMUESTREO_TI = 8
MUESTRA_MINIMA_TI = 200
LIMITE_MAXIMO_SOCRATA = 1000


def _primer_valor(fila: dict[str, Any], alias: tuple[str, ...]) -> Any | None:
    for clave in alias:
        valor = fila.get(clave)
        if valor not in (None, "", "No Definido", "No definido"):
            return valor
    return None


def _a_float(valor: Any) -> float | None:
    if valor is None:
        return None
    try:
        return float(str(valor).replace("$", "").replace(",", "").strip())
    except (TypeError, ValueError):
        return None


def _normalizar(texto: str) -> str:
    reemplazos = str.maketrans("áéíóúüñÁÉÍÓÚÜÑ", "aeiouunAEIOUUN")
    return texto.lower().translate(reemplazos)


# Los términos se comparan con límites de palabra. Sin esto, los acrónimos cortos
# producen falsos positivos masivos: "api" dentro de "capital", "soc" dentro de
# "social", "tic" dentro de "logística", "erp" dentro de "cuerpo".
_PATRONES_TI: dict[str, tuple[re.Pattern[str], int]] = {
    termino: (
        re.compile(rf"(?<![a-z0-9]){re.escape(_normalizar(termino))}(?![a-z0-9])"),
        peso,
    )
    for termino, peso in PALABRAS_TI.items()
}


def puntuar_relevancia_ti(texto: str | None) -> tuple[int, list[str]]:
    """Heurística local (sin IA) de relevancia tecnológica: 0-100 + señales encontradas.

    Es un filtro de primer nivel, no un clasificador: sirve para ordenar y descartar
    ruido antes de gastar tokens en el modelo. La clasificación fina la hace el
    endpoint `/api/procesos/relevancia-ti`.
    """
    if not texto:
        return 0, []
    plano = _normalizar(texto)
    puntos = 0
    señales: list[str] = []
    for termino, (patron, peso) in _PATRONES_TI.items():
        if patron.search(plano):
            puntos += peso
            señales.append(termino)
    return min(puntos, 100), sorted(set(señales))


class SecopClient:
    """Acceso de solo lectura a los datasets abiertos de SECOP II."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        cabeceras = {"Accept": "application/json"}
        if settings.secop_app_token:
            cabeceras["X-App-Token"] = settings.secop_app_token
        self._cliente = httpx.AsyncClient(
            base_url=settings.secop_base_url.rstrip("/"),
            headers=cabeceras,
            timeout=httpx.Timeout(45.0, connect=10.0),
            follow_redirects=True,
        )

    async def cerrar(self) -> None:
        await self._cliente.aclose()

    # -- construcción de consultas ------------------------------------------------

    @staticmethod
    def _escapar(valor: str) -> str:
        """Escapa comillas simples para literales SoQL."""
        return valor.replace("'", "''")

    def _construir_where(self, f: FiltroProcesos, advertencias: list[str]) -> str | None:
        # Socrata coloca los nulos primero al ordenar DESC, de modo que sin esta
        # cláusula el listado se llena de procesos antiguos sin fecha de publicación
        # en lugar de los más recientes.
        clausulas: list[str] = ["fecha_de_publicacion_del IS NOT NULL"]

        if f.texto:
            termino = self._escapar(f.texto.strip())
            ors = [f"upper({c}) like upper('%{termino}%')" for c in CAMPOS_TEXTO]
            clausulas.append("(" + " OR ".join(ors) + ")")

        if f.entidad:
            clausulas.append(f"upper(entidad) like upper('%{self._escapar(f.entidad)}%')")

        if f.departamento:
            clausulas.append(
                f"upper(departamento_entidad) like upper('%{self._escapar(f.departamento)}%')"
            )

        if f.modalidad:
            clausulas.append(
                "upper(modalidad_de_contratacion) like "
                f"upper('%{self._escapar(f.modalidad)}%')"
            )

        if f.estado:
            # El estado vive repartido en tres columnas con vocabularios distintos:
            # `estado_del_procedimiento` ("Seleccionado"), `estado_resumen` y `fase`
            # ("Presentación de oferta"). Se buscan las tres.
            termino = self._escapar(f.estado.strip())
            ors = [
                f"upper({c}) like upper('%{termino}%')"
                for c in ("estado_del_procedimiento", "estado_resumen", "fase")
            ]
            clausulas.append("(" + " OR ".join(ors) + ")")

        if f.valor_min is not None:
            clausulas.append(f"precio_base >= {f.valor_min}")
        if f.valor_max is not None:
            clausulas.append(f"precio_base <= {f.valor_max}")

        if f.fecha_desde:
            if _es_fecha_iso(f.fecha_desde):
                clausulas.append(f"fecha_de_publicacion_del >= '{f.fecha_desde}T00:00:00.000'")
            else:
                advertencias.append(f"fecha_desde ignorada (formato inválido): {f.fecha_desde}")
        if f.fecha_hasta:
            if _es_fecha_iso(f.fecha_hasta):
                clausulas.append(f"fecha_de_publicacion_del <= '{f.fecha_hasta}T23:59:59.999'")
            else:
                advertencias.append(f"fecha_hasta ignorada (formato inválido): {f.fecha_hasta}")

        if f.solo_ti:
            ors = [
                f"upper({campo}) like upper('%{self._escapar(t)}%')"
                for t in TERMINOS_SOQL_TI
                for campo in CAMPOS_TEXTO
            ]
            clausulas.append("(" + " OR ".join(ors) + ")")

        return " AND ".join(clausulas)

    # -- operaciones --------------------------------------------------------------

    async def buscar_procesos(self, filtro: FiltroProcesos) -> RespuestaProcesos:
        advertencias: list[str] = []
        dataset = self.settings.secop_procesos_dataset

        # Con `solo_ti` se sobremuestrea: el prefiltro SoQL es de alto recall y el
        # filtro fino (heurística local) se aplica después.
        limite_api = (
            min(
                max(filtro.limite * FACTOR_SOBREMUESTREO_TI, MUESTRA_MINIMA_TI),
                LIMITE_MAXIMO_SOCRATA,
            )
            if filtro.solo_ti
            else filtro.limite
        )
        params: dict[str, Any] = {
            "$limit": limite_api,
            "$offset": filtro.offset,
            "$order": "fecha_de_publicacion_del DESC",
        }
        where = self._construir_where(filtro, advertencias)
        if where:
            params["$where"] = where

        filas = await self._get(f"/{dataset}.json", params, advertencias)

        # Si la consulta falló por un campo inexistente en el dataset, reintentamos sin
        # $order y sin $where para al menos devolver datos utilizables.
        if filas is None:
            advertencias.append(
                "La consulta con filtros falló; se devuelven resultados sin filtrar. "
                "Verifica el esquema del dataset con `python inspeccionar_dataset.py`."
            )
            params = {"$limit": filtro.limite, "$offset": filtro.offset}
            filas = await self._get(f"/{dataset}.json", params, advertencias) or []

        procesos = [self._mapear(fila) for fila in filas]

        if filtro.solo_ti:
            revisados = len(procesos)
            procesos = [p for p in procesos if (p.score_ti or 0) >= UMBRAL_TI]
            procesos.sort(key=lambda p: p.score_ti or 0, reverse=True)
            procesos = procesos[: filtro.limite]
            if not procesos and revisados:
                advertencias.append(
                    f"Se revisaron {revisados} procesos y ninguno superó el umbral de "
                    f"relevancia tecnológica ({UMBRAL_TI}). Prueba con `texto` específico "
                    "o desactiva `solo_ti`."
                )

        return RespuestaProcesos(
            total=len(procesos),
            procesos=procesos,
            dataset=dataset,
            advertencias=advertencias,
        )

    async def obtener_proceso(self, id_proceso: str) -> ProcesoResumen | None:
        dataset = self.settings.secop_procesos_dataset
        advertencias: list[str] = []
        seguro = self._escapar(id_proceso)
        for campo in ("id_del_proceso", "referencia_del_proceso"):
            filas = await self._get(
                f"/{dataset}.json",
                {"$where": f"{campo} = '{seguro}'", "$limit": 1},
                advertencias,
            )
            if filas:
                return self._mapear(filas[0])
        return None

    async def _get(
        self, ruta: str, params: dict[str, Any], advertencias: list[str]
    ) -> list[dict[str, Any]] | None:
        try:
            respuesta = await self._cliente.get(ruta, params=params)
            respuesta.raise_for_status()
            datos = respuesta.json()
            return datos if isinstance(datos, list) else []
        except httpx.HTTPStatusError as exc:
            detalle = exc.response.text[:300]
            log.warning("SECOP %s -> %s: %s", ruta, exc.response.status_code, detalle)
            advertencias.append(f"SECOP respondió {exc.response.status_code}: {detalle}")
            return None
        except httpx.HTTPError as exc:
            log.warning("Error de red hacia SECOP: %s", exc)
            advertencias.append(f"Error de red hacia SECOP: {exc}")
            return None

    @staticmethod
    def _mapear(fila: dict[str, Any]) -> ProcesoResumen:
        datos = {
            campo: _primer_valor(fila, alias) for campo, alias in ALIAS_CAMPOS.items()
        }
        # El objeto extendido y el título corto suelen diferir; se puntúan juntos para
        # no perder señales que solo aparecen en uno de los dos.
        texto_objeto = " ".join(
            filtro
            for filtro in (
                _texto(fila.get("descripci_n_del_procedimiento")),
                _texto(fila.get("nombre_del_procedimiento")),
            )
            if filtro
        )
        score, señales = puntuar_relevancia_ti(texto_objeto)

        duracion = _texto(datos.get("duracion"))
        unidad = _texto(datos.get("unidad_duracion"))
        if duracion and unidad:
            duracion = f"{duracion} {unidad}"

        return ProcesoResumen(
            id=_texto(datos.get("id")),
            numero_proceso=_texto(datos.get("numero_proceso")),
            entidad=_texto(datos.get("entidad")),
            nit_entidad=_texto(datos.get("nit_entidad")),
            departamento=_texto(datos.get("departamento")),
            ciudad=_texto(datos.get("ciudad")),
            objeto=_texto(datos.get("objeto")),
            modalidad=_texto(datos.get("modalidad")),
            estado=_texto(datos.get("estado")),
            tipo_contrato=_texto(datos.get("tipo_contrato")),
            orden_entidad=_texto(datos.get("orden_entidad")),
            adjudicado=_texto(datos.get("adjudicado")),
            valor=_a_float(datos.get("valor")),
            fecha_publicacion=_texto(datos.get("fecha_publicacion")),
            fecha_ultima_publicacion=_texto(datos.get("fecha_ultima_publicacion")),
            url=_extraer_url(datos.get("url")),
            codigo_unspsc=_texto(datos.get("codigo_unspsc")),
            duracion=duracion,
            score_ti=score,
            señales_ti=señales,
            crudo=fila,
        )


def _texto(valor: Any) -> str | None:
    if valor is None:
        return None
    if isinstance(valor, dict):  # Socrata a veces devuelve {"url": ...}
        valor = valor.get("url") or valor.get("description") or ""
    texto = str(valor).strip()
    return texto or None


def _extraer_url(valor: Any) -> str | None:
    texto = _texto(valor)
    if not texto:
        return None
    return texto if texto.startswith(("http://", "https://")) else None


def _es_fecha_iso(valor: str) -> bool:
    return bool(re.fullmatch(r"\d{4}-\d{2}-\d{2}", valor.strip()))
