"""Modelos Pydantic de entrada/salida de la API."""

from __future__ import annotations

from enum import Enum
from typing import Any, Literal

from pydantic import BaseModel, Field

# ---------------------------------------------------------------------------
# Comunes
# ---------------------------------------------------------------------------


class Criticidad(str, Enum):
    obligatorio = "obligatorio"
    ponderable = "ponderable"
    deseable = "deseable"
    informativo = "informativo"


class EstadoCumplimiento(str, Enum):
    cumple = "cumple"
    cumple_parcial = "cumple_parcial"
    no_cumple = "no_cumple"
    no_evaluable = "no_evaluable"


class NivelRiesgo(str, Enum):
    alto = "alto"
    medio = "medio"
    bajo = "bajo"


# ---------------------------------------------------------------------------
# Procesos SECOP II
# ---------------------------------------------------------------------------


class FiltroProcesos(BaseModel):
    """Filtros para la búsqueda de procesos en SECOP II."""

    texto: str | None = Field(
        default=None,
        description="Búsqueda de texto libre sobre el objeto del proceso.",
        examples=["desarrollo de software"],
    )
    entidad: str | None = Field(default=None, description="Nombre (parcial) de la entidad.")
    departamento: str | None = Field(default=None, examples=["Distrito Capital de Bogotá"])
    modalidad: str | None = Field(
        default=None,
        description="Modalidad de contratación (ej. 'Licitación pública').",
    )
    estado: str | None = Field(default=None, examples=["Convocado", "Publicado"])
    valor_min: float | None = Field(default=None, ge=0)
    valor_max: float | None = Field(default=None, ge=0)
    fecha_desde: str | None = Field(default=None, description="ISO date, ej. 2026-01-01")
    fecha_hasta: str | None = Field(default=None, description="ISO date, ej. 2026-12-31")
    solo_ti: bool = Field(
        default=False,
        description="Aplica un filtro heurístico por palabras clave de tecnología.",
    )
    limite: int = Field(default=50, ge=1, le=500)
    offset: int = Field(default=0, ge=0)


class ProcesoResumen(BaseModel):
    """Vista normalizada de un proceso de contratación."""

    id: str | None = None
    numero_proceso: str | None = None
    entidad: str | None = None
    nit_entidad: str | None = None
    departamento: str | None = None
    ciudad: str | None = None
    objeto: str | None = None
    modalidad: str | None = None
    estado: str | None = None
    tipo_contrato: str | None = None
    orden_entidad: str | None = Field(
        default=None, description="Nacional / Territorial."
    )
    adjudicado: str | None = None
    valor: float | None = Field(default=None, description="Precio base del proceso (COP).")
    fecha_publicacion: str | None = None
    fecha_ultima_publicacion: str | None = Field(
        default=None,
        description=(
            "Fecha de última publicación del proceso. El dataset abierto no expone una "
            "fecha de cierre de recepción de ofertas; consúltala en el enlace del proceso."
        ),
    )
    url: str | None = None
    codigo_unspsc: str | None = None
    duracion: str | None = None
    # Enriquecimiento local
    score_ti: int | None = Field(
        default=None, description="0-100. Heurística local de relevancia tecnológica."
    )
    señales_ti: list[str] = Field(default_factory=list)
    crudo: dict[str, Any] = Field(default_factory=dict, exclude=True)


class RespuestaProcesos(BaseModel):
    total: int
    procesos: list[ProcesoResumen]
    dataset: str
    advertencias: list[str] = Field(default_factory=list)


class SolicitudRelevancia(BaseModel):
    procesos: list[ProcesoResumen]
    perfil_proveedor: str | None = Field(
        default=None,
        description="Capacidades del proveedor, para priorizar por encaje.",
        examples=["Fábrica de software Java/Angular, nube AWS, 40 desarrolladores, CMMI 3"],
    )
    maximo: int = Field(default=15, ge=1, le=50)


class ProcesoPriorizado(BaseModel):
    id: str | None = None
    objeto: str | None = None
    entidad: str | None = None
    valor: float | None = None
    puntaje: int = Field(ge=0, le=100)
    categoria_ti: str
    justificacion: str
    encaje_proveedor: str | None = None
    banderas: list[str] = Field(default_factory=list)


class RespuestaRelevancia(BaseModel):
    priorizados: list[ProcesoPriorizado]
    resumen: str


# ---------------------------------------------------------------------------
# Análisis de requisitos
# ---------------------------------------------------------------------------


class SolicitudAnalisis(BaseModel):
    texto_pliego: str = Field(
        min_length=40,
        description="Texto del pliego, anexo técnico o estudios previos.",
    )
    objeto_contractual: str | None = None
    entidad: str | None = None
    modalidad: str | None = None
    valor_estimado: float | None = None
    contexto_proveedor: str | None = Field(
        default=None,
        description="Capacidades/limitaciones del proveedor, para contextualizar riesgos.",
    )


class RequisitoTecnico(BaseModel):
    id: str = Field(description="Identificador corto, ej. RT-01")
    categoria: str = Field(
        description="Arquitectura, Seguridad, Datos, Integraciones, Infraestructura, "
        "Soporte, Metodología, Accesibilidad, Interoperabilidad, Personal, Otros"
    )
    requisito: str
    criticidad: Criticidad
    evidencia_esperada: str = Field(
        description="Qué documento/certificado debe aportarse para acreditarlo."
    )
    norma_relacionada: str | None = Field(
        default=None,
        description="Norma o marco aplicable (Ley 1712, Decreto 1078/2015, ISO 27001, etc.)",
    )
    cita_pliego: str | None = Field(
        default=None, description="Fragmento textual del pliego que lo sustenta."
    )


class RiesgoDetectado(BaseModel):
    descripcion: str
    nivel: NivelRiesgo
    impacto: str
    mitigacion: str
    tipo: Literal[
        "tecnico", "juridico", "financiero", "operativo", "cronograma", "competencia"
    ] = "tecnico"


class RespuestaAnalisis(BaseModel):
    resumen_ejecutivo: str
    objeto_normalizado: str | None = None
    requisitos: list[RequisitoTecnico]
    riesgos: list[RiesgoDetectado]
    criterios_evaluacion: list[str] = Field(default_factory=list)
    documentos_habilitantes: list[str] = Field(default_factory=list)
    preguntas_a_la_entidad: list[str] = Field(default_factory=list)
    alertas_normativas: list[str] = Field(default_factory=list)
    recomendacion: str


# ---------------------------------------------------------------------------
# Generación de propuestas
# ---------------------------------------------------------------------------


class SolicitudPropuesta(BaseModel):
    objeto_contractual: str
    requisitos: list[RequisitoTecnico] = Field(default_factory=list)
    texto_pliego: str | None = None
    perfil_proveedor: str = Field(
        min_length=10,
        description="Capacidades, experiencia, equipo y tecnologías del oferente.",
    )
    entidad: str | None = None
    valor_estimado: float | None = None
    plazo_meses: int | None = Field(default=None, ge=1, le=120)
    enfasis: list[str] = Field(
        default_factory=list,
        description="Aspectos a destacar (ej. 'seguridad', 'accesibilidad', 'nube pública').",
    )


class SeccionPropuesta(BaseModel):
    titulo: str
    contenido: str
    requisitos_cubiertos: list[str] = Field(default_factory=list)


class RespuestaPropuesta(BaseModel):
    titulo: str
    resumen_ejecutivo: str
    secciones: list[SeccionPropuesta]
    supuestos: list[str] = Field(default_factory=list)
    vacios_de_informacion: list[str] = Field(default_factory=list)
    markdown: str = Field(description="Propuesta completa en Markdown, lista para exportar.")


# ---------------------------------------------------------------------------
# Validación de propuestas
# ---------------------------------------------------------------------------


class SolicitudValidacion(BaseModel):
    texto_propuesta: str = Field(min_length=40)
    requisitos: list[RequisitoTecnico] = Field(default_factory=list)
    texto_pliego: str | None = Field(
        default=None,
        description="Si no se envían requisitos estructurados, se extraen de aquí.",
    )
    objeto_contractual: str | None = None


class ItemCumplimiento(BaseModel):
    requisito_id: str
    requisito: str
    criticidad: Criticidad
    estado: EstadoCumplimiento
    evidencia_en_propuesta: str | None = None
    brecha: str | None = None
    accion_correctiva: str | None = None


class RespuestaValidacion(BaseModel):
    puntaje_cumplimiento: int = Field(ge=0, le=100)
    veredicto: Literal[
        "apta", "apta_con_ajustes", "riesgo_de_rechazo", "no_apta"
    ]
    resumen: str
    matriz: list[ItemCumplimiento]
    causales_de_rechazo: list[str] = Field(default_factory=list)
    mejoras_prioritarias: list[str] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# Documentos y chat
# ---------------------------------------------------------------------------


class RespuestaDocumento(BaseModel):
    nombre_archivo: str
    tipo: str
    caracteres: int
    paginas: int | None = None
    texto: str
    truncado: bool = False


class MensajeChat(BaseModel):
    rol: Literal["user", "assistant"]
    contenido: str


class SolicitudChat(BaseModel):
    mensajes: list[MensajeChat] = Field(min_length=1)
    contexto: str | None = Field(
        default=None,
        description="Contexto adicional: pliego, proceso o propuesta en curso.",
    )


class EstadoSalud(BaseModel):
    estado: Literal["ok", "degradado"]
    version: str
    proveedor_ia: str
    ia_configurada: bool
    modelo: str
    secop_dataset_procesos: str
    secop_token_configurado: bool
