"""Endpoints de análisis de pliegos y requisitos técnicos."""

from __future__ import annotations

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile

from ..dependencies import get_agente
from ..schemas import RespuestaAnalisis, RespuestaDocumento, SolicitudAnalisis
from ..services.ai_agent import AgenteSecop
from ..services.document_parser import DocumentoNoSoportado, extraer_texto

router = APIRouter(prefix="/api/analisis", tags=["analisis"])

TAMANO_MAXIMO = 25 * 1024 * 1024  # 25 MB


@router.post("/requisitos", response_model=RespuestaAnalisis)
async def analizar_requisitos(
    solicitud: SolicitudAnalisis,
    agente: AgenteSecop = Depends(get_agente),
) -> RespuestaAnalisis:
    """Extrae requisitos técnicos, riesgos y alertas normativas del material del proceso.

    Los fallos del agente los traduce el manejador centralizado de `app/main.py`.
    """
    return await agente.analizar_requisitos(solicitud)


@router.post("/documento", response_model=RespuestaDocumento)
async def cargar_documento(archivo: UploadFile = File(...)) -> RespuestaDocumento:
    """Sube un PDF/DOCX/TXT y devuelve su texto para alimentar el análisis."""
    contenido = await archivo.read()
    if not contenido:
        raise HTTPException(422, "El archivo está vacío.")
    if len(contenido) > TAMANO_MAXIMO:
        raise HTTPException(
            413, f"El archivo supera el máximo de {TAMANO_MAXIMO // (1024 * 1024)} MB."
        )

    try:
        texto, tipo, paginas, truncado = extraer_texto(archivo.filename or "", contenido)
    except DocumentoNoSoportado as exc:
        raise HTTPException(415, str(exc)) from exc
    except Exception as exc:  # noqa: BLE001 — archivo corrupto o protegido
        raise HTTPException(422, f"No se pudo leer el documento: {exc}") from exc

    if not texto.strip():
        raise HTTPException(
            422,
            "No se extrajo texto del documento. Si es un PDF escaneado, requiere OCR "
            "previo (esta aplicación no realiza reconocimiento óptico de caracteres).",
        )

    return RespuestaDocumento(
        nombre_archivo=archivo.filename or "documento",
        tipo=tipo,
        caracteres=len(texto),
        paginas=paginas,
        texto=texto,
        truncado=truncado,
    )
