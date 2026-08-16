"""Endpoints de generación y validación de propuestas técnicas."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from ..dependencies import get_agente
from ..schemas import (
    RespuestaPropuesta,
    RespuestaValidacion,
    SolicitudPropuesta,
    SolicitudValidacion,
)
from ..services.ai_agent import AgenteSecop, ContextoDemasiadoGrande

router = APIRouter(prefix="/api/propuestas", tags=["propuestas"])


@router.post("/generar", response_model=RespuestaPropuesta)
async def generar_propuesta(
    solicitud: SolicitudPropuesta,
    agente: AgenteSecop = Depends(get_agente),
) -> RespuestaPropuesta:
    """Redacta un borrador de propuesta técnica alineado al pliego.

    Los fallos del agente los traduce el manejador centralizado de `app/main.py`.
    """
    if not solicitud.requisitos and not solicitud.texto_pliego:
        raise HTTPException(
            422,
            "Envía `requisitos` estructurados o `texto_pliego`; sin al menos uno de los "
            "dos la propuesta no puede alinearse al proceso.",
        )
    return await agente.generar_propuesta(solicitud)


@router.post("/validar", response_model=RespuestaValidacion)
async def validar_propuesta(
    solicitud: SolicitudValidacion,
    agente: AgenteSecop = Depends(get_agente),
) -> RespuestaValidacion:
    """Compara la propuesta contra los requisitos y devuelve la matriz de cumplimiento.

    Los fallos del agente los traduce el manejador centralizado de `app/main.py`.
    """
    try:
        return await agente.validar_propuesta(solicitud)
    except ContextoDemasiadoGrande:
        # Hereda de ValueError: sin esta rama caería en la de abajo y devolvería 422
        # en lugar del 413 que le corresponde.
        raise
    except ValueError as exc:
        # Falta el pliego Y los requisitos: es un error de la petición, no del agente.
        raise HTTPException(422, str(exc)) from exc
