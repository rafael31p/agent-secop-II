"""Endpoints de consulta de procesos en SECOP II."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from ..dependencies import get_agente, get_secop_client
from ..schemas import (
    FiltroProcesos,
    ProcesoResumen,
    RespuestaProcesos,
    RespuestaRelevancia,
    SolicitudRelevancia,
)
from ..services.ai_agent import AgenteSecop
from ..services.secop_client import SecopClient

router = APIRouter(prefix="/api/procesos", tags=["procesos"])


@router.post("/buscar", response_model=RespuestaProcesos)
async def buscar_procesos(
    filtro: FiltroProcesos,
    cliente: SecopClient = Depends(get_secop_client),
) -> RespuestaProcesos:
    """Busca procesos de contratación en el dataset abierto de SECOP II."""
    if (
        filtro.valor_min is not None
        and filtro.valor_max is not None
        and filtro.valor_min > filtro.valor_max
    ):
        raise HTTPException(422, "valor_min no puede ser mayor que valor_max.")
    return await cliente.buscar_procesos(filtro)


@router.get("/{id_proceso}", response_model=ProcesoResumen)
async def obtener_proceso(
    id_proceso: str,
    cliente: SecopClient = Depends(get_secop_client),
) -> ProcesoResumen:
    proceso = await cliente.obtener_proceso(id_proceso)
    if proceso is None:
        raise HTTPException(404, f"No se encontró el proceso '{id_proceso}' en SECOP II.")
    return proceso


@router.post("/relevancia-ti", response_model=RespuestaRelevancia)
async def priorizar_por_relevancia(
    solicitud: SolicitudRelevancia,
    agente: AgenteSecop = Depends(get_agente),
) -> RespuestaRelevancia:
    """Clasifica procesos por categoría de TI y encaje con el perfil del proveedor.

    Los fallos del agente los traduce el manejador centralizado de `app/main.py`.
    """
    if not solicitud.procesos:
        raise HTTPException(422, "Envía al menos un proceso para priorizar.")
    return await agente.priorizar_procesos(solicitud)
