"""Chat con el agente experto (respuesta en streaming SSE)."""

from __future__ import annotations

import json
import logging
from collections.abc import AsyncIterator

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from ..dependencies import get_agente
from ..schemas import SolicitudChat
from ..services.ai_agent import AgenteNoConfigurado, AgenteSecop

log = logging.getLogger(__name__)

router = APIRouter(prefix="/api", tags=["chat"])


@router.post("/chat")
async def chat(
    solicitud: SolicitudChat,
    agente: AgenteSecop = Depends(get_agente),
) -> StreamingResponse:
    """Devuelve la respuesta del agente como Server-Sent Events.

    Eventos emitidos: `delta` (fragmento de texto), `error`, `fin`.
    """

    async def generar() -> AsyncIterator[str]:
        try:
            async for fragmento in agente.chat_stream(solicitud.mensajes, solicitud.contexto):
                yield _sse("delta", {"texto": fragmento})
        except AgenteNoConfigurado as exc:
            yield _sse("error", {"mensaje": str(exc)})
        except Exception as exc:  # noqa: BLE001 — el error debe llegar al cliente
            log.exception("Fallo en el streaming de chat")
            yield _sse("error", {"mensaje": f"Error del agente: {exc}"})
        finally:
            yield _sse("fin", {})

    return StreamingResponse(
        generar(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


def _sse(evento: str, datos: dict) -> str:
    return f"event: {evento}\ndata: {json.dumps(datos, ensure_ascii=False)}\n\n"
