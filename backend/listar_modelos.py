"""Diagnóstico: qué modelos de Gemini puede usar realmente tu API key.

Aparecer en el catálogo NO significa poder usarlo: un modelo retirado sigue listándose
pero devuelve 404, y los modelos `pro` devuelven 429 en el plan gratuito. Por eso este
script sondea cada candidato con una llamada real (mínima) en vez de confiar en la
lista.

Uso:
    python listar_modelos.py           # sondea los candidatos recomendados
    python listar_modelos.py --todos   # además, lista el catálogo completo
    python listar_modelos.py gemini-3.6-flash gemini-3.5-flash   # sondea los indicados
"""

from __future__ import annotations

import asyncio
import sys

from google import genai
from google.genai import errors as genai_errors
from google.genai import types
from pydantic import BaseModel

from app.config import get_settings

# Candidatos por defecto: modelos de texto de propósito general, del más capaz al más
# barato. Se excluyen los especializados (imagen, TTS, robótica, computer-use).
CANDIDATOS = [
    "gemini-3.6-flash",
    "gemini-3.5-flash",
    "gemini-3.1-pro-preview",
    "gemini-3-pro-preview",
    "gemini-3.5-flash-lite",
    "gemini-3.1-flash-lite",
    "gemini-flash-latest",
    "gemini-pro-latest",
]


class _Sonda(BaseModel):
    """Esquema mínimo: comprueba de paso que el modelo respeta salidas estructuradas."""

    respuesta: str


async def sondear(cliente: genai.Client, modelo: str) -> tuple[str, bool, str]:
    """Devuelve (modelo, utilizable, detalle)."""
    try:
        respuesta = await cliente.aio.models.generate_content(
            model=modelo,
            contents="Responde únicamente con la palabra: ok",
            config=types.GenerateContentConfig(
                response_mime_type="application/json",
                response_schema=_Sonda,
                max_output_tokens=2000,
                temperature=0.0,
            ),
        )
        if not isinstance(respuesta.parsed, _Sonda):
            return modelo, False, "responde, pero no respeta el esquema estructurado"
        uso = respuesta.usage_metadata
        pensamiento = getattr(uso, "thoughts_token_count", None) or 0
        razona = "con razonamiento" if pensamiento else "sin razonamiento"
        return modelo, True, f"salidas estructuradas OK, {razona}"
    except genai_errors.APIError as exc:
        codigo = getattr(exc, "code", "?")
        mensaje = (getattr(exc, "message", None) or str(exc)).strip()
        if codigo == 404:
            return modelo, False, "404 · retirado o sin acceso para tu key"
        if codigo == 429:
            return modelo, False, "429 · fuera de cuota (suele requerir plan de pago)"
        return modelo, False, f"{codigo} · {mensaje[:90]}"
    except Exception as exc:  # noqa: BLE001
        return modelo, False, f"{type(exc).__name__}: {str(exc)[:90]}"


async def main() -> int:
    settings = get_settings()
    if not settings.ia_configurada:
        print(
            "GEMINI_API_KEY no está configurada.\n"
            "Copia backend/.env.example a backend/.env y agrega tu key de "
            "https://aistudio.google.com/apikey"
        )
        return 1

    argumentos = [a for a in sys.argv[1:] if not a.startswith("--")]
    mostrar_catalogo = "--todos" in sys.argv
    candidatos = argumentos or CANDIDATOS
    if settings.gemini_model not in candidatos:
        candidatos = [settings.gemini_model, *candidatos]

    cliente = genai.Client(api_key=settings.gemini_api_key)

    if mostrar_catalogo:
        print("--- Catálogo completo (listado ≠ acceso) ---")
        try:
            for modelo in sorted(cliente.models.list(), key=lambda m: m.name or ""):
                if "generateContent" not in (modelo.supported_actions or []):
                    continue
                nombre = (modelo.name or "").removeprefix("models/")
                print(
                    f"  {nombre:<40} entrada {modelo.input_token_limit or 0:>9,} "
                    f"salida {modelo.output_token_limit or 0:>7,}"
                )
        except genai_errors.APIError as exc:
            print(f"  no se pudo listar: {exc}")
        print()

    print("--- Sondeo con llamada real ---")
    resultados = await asyncio.gather(
        *(sondear(cliente, modelo) for modelo in candidatos)
    )

    utilizables: list[str] = []
    for modelo, ok, detalle in resultados:
        marca = " ← configurado" if modelo == settings.gemini_model else ""
        print(f"  [{'OK  ' if ok else 'NO  '}] {modelo:<26} {detalle}{marca}")
        if ok:
            utilizables.append(modelo)

    print()
    if settings.gemini_model in utilizables:
        print(f"GEMINI_MODEL='{settings.gemini_model}' funciona correctamente.")
        return 0

    print(f"ATENCIÓN: GEMINI_MODEL='{settings.gemini_model}' NO es utilizable.")
    if utilizables:
        print(f"Cámbialo en backend/.env por uno de estos: {', '.join(utilizables)}")
    else:
        print(
            "Ningún candidato respondió. Revisa la validez de la key y tu cuota en "
            "https://aistudio.google.com"
        )
    return 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
