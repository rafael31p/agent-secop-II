"""Health check y estado de configuración."""

from __future__ import annotations

from fastapi import APIRouter, Depends

from ..config import Settings, get_settings
from ..schemas import EstadoSalud

router = APIRouter(prefix="/api", tags=["salud"])

VERSION = "0.1.0"


@router.get("/salud", response_model=EstadoSalud)
async def salud(settings: Settings = Depends(get_settings)) -> EstadoSalud:
    return EstadoSalud(
        estado="ok" if settings.ia_configurada else "degradado",
        version=VERSION,
        proveedor_ia="google-gemini",
        ia_configurada=settings.ia_configurada,
        modelo=settings.gemini_model,
        secop_dataset_procesos=settings.secop_procesos_dataset,
        secop_token_configurado=bool(settings.secop_app_token),
    )
