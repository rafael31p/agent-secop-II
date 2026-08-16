"""Dependencias compartidas (singletons de cliente SECOP y agente IA)."""

from __future__ import annotations

from functools import lru_cache

from .config import get_settings
from .services.ai_agent import AgenteSecop
from .services.secop_client import SecopClient


@lru_cache
def get_secop_client() -> SecopClient:
    return SecopClient(get_settings())


@lru_cache
def get_agente() -> AgenteSecop:
    return AgenteSecop(get_settings())


async def cerrar_recursos() -> None:
    await get_secop_client().cerrar()
    await get_agente().cerrar()
