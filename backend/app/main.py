"""Punto de entrada de la API del Agente SECOP II."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from .config import get_settings
from .dependencies import cerrar_recursos
from .routers import analisis, chat, procesos, propuestas, salud
from .services.ai_agent import (
    AgenteNoConfigurado,
    ContextoDemasiadoGrande,
    ErrorProveedorIA,
    RespuestaBloqueada,
)

settings = get_settings()
logging.basicConfig(
    level=getattr(logging, settings.log_level.upper(), logging.INFO),
    format="%(asctime)s %(levelname)-8s %(name)s: %(message)s",
)
log = logging.getLogger("agente-secop")


@asynccontextmanager
async def ciclo_de_vida(app: FastAPI):
    if not settings.ia_configurada:
        log.warning(
            "GEMINI_API_KEY no configurada: los endpoints de IA responderán 503. "
            "La búsqueda en SECOP II sí funciona."
        )
    log.info("Modelo: %s | dataset SECOP: %s",
             settings.gemini_model, settings.secop_procesos_dataset)
    yield
    await cerrar_recursos()


app = FastAPI(
    title="Agente SECOP II — Contratación pública de TI",
    description=(
        "Agente experto en contratación pública colombiana. Explora procesos de SECOP II, "
        "analiza requisitos técnicos de tecnología, genera propuestas y valida su "
        "cumplimiento.\n\n"
        "**Aviso:** herramienta de apoyo analítico. No sustituye asesoría jurídica ni el "
        "estudio de los documentos oficiales del proceso."
    ),
    version=salud.VERSION,
    lifespan=ciclo_de_vida,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins_list,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# --- Manejo centralizado de errores del agente -------------------------------------
# Sin esto, una excepción del agente sale como «500 Internal Server Error» y el mensaje
# explicativo (modelo inexistente, cuota agotada, filtro de contenido) nunca llega al
# usuario. Registrarlo aquí evita repetir el mismo try/except en cada router.


def _detalle(exc: Exception, estado: int) -> JSONResponse:
    return JSONResponse(status_code=estado, content={"detail": str(exc)})


@app.exception_handler(AgenteNoConfigurado)
async def _sin_configurar(_: Request, exc: AgenteNoConfigurado) -> JSONResponse:
    return _detalle(exc, 503)


@app.exception_handler(ContextoDemasiadoGrande)
async def _demasiado_grande(_: Request, exc: ContextoDemasiadoGrande) -> JSONResponse:
    return _detalle(exc, 413)


@app.exception_handler(RespuestaBloqueada)
async def _bloqueada(_: Request, exc: RespuestaBloqueada) -> JSONResponse:
    return _detalle(exc, 422)


@app.exception_handler(ErrorProveedorIA)
async def _proveedor(_: Request, exc: ErrorProveedorIA) -> JSONResponse:
    log.warning("Fallo del proveedor de IA: %s", exc)
    return _detalle(exc, exc.estado_http)


app.include_router(salud.router)
app.include_router(procesos.router)
app.include_router(analisis.router)
app.include_router(propuestas.router)
app.include_router(chat.router)


@app.get("/", include_in_schema=False)
async def raiz() -> dict[str, str]:
    return {
        "servicio": "Agente SECOP II",
        "version": salud.VERSION,
        "documentacion": "/docs",
        "salud": "/api/salud",
    }
