"""Agente de IA: análisis de pliegos, generación y validación de propuestas.

Usa el SDK oficial de Google (`google-genai`). Las respuestas estructuradas se obtienen
con `response_schema=<modelo Pydantic>` + `response_mime_type="application/json"`, que
hace que el modelo emita JSON conforme al esquema; el SDK lo devuelve ya validado en
`response.parsed`.
"""

from __future__ import annotations

import asyncio
import json
import logging
import random
from collections.abc import AsyncIterator
from typing import TypeVar

from google import genai
from google.genai import errors as genai_errors
from google.genai import types
from pydantic import BaseModel, ValidationError

from .. import prompts
from ..config import Settings
from ..schemas import (
    MensajeChat,
    RespuestaAnalisis,
    RespuestaPropuesta,
    RespuestaRelevancia,
    RespuestaValidacion,
    SolicitudAnalisis,
    SolicitudPropuesta,
    SolicitudRelevancia,
    SolicitudValidacion,
)

log = logging.getLogger(__name__)

T = TypeVar("T", bound=BaseModel)

# Límite de caracteres enviados en un solo turno. Gemini 2.5 admite 1M de tokens de
# contexto; el tope está por debajo para dejar margen al razonamiento y a la respuesta.
LIMITE_PLIEGO = 800_000

# Los pliegos de ciberseguridad, control de acceso o seguridad penitenciaria disparan
# falsos positivos en los filtros por defecto. Se relajan al nivel más permisivo que
# sigue bloqueando contenido de alto riesgo.
CATEGORIAS_SEGURIDAD = (
    types.HarmCategory.HARM_CATEGORY_HARASSMENT,
    types.HarmCategory.HARM_CATEGORY_HATE_SPEECH,
    types.HarmCategory.HARM_CATEGORY_SEXUALLY_EXPLICIT,
    types.HarmCategory.HARM_CATEGORY_DANGEROUS_CONTENT,
)

# Motivos de fin de generación que no producen una respuesta utilizable.
FINALES_PROBLEMATICOS = {
    types.FinishReason.SAFETY: "los filtros de seguridad del modelo bloquearon la respuesta",
    types.FinishReason.PROHIBITED_CONTENT: "el contenido fue clasificado como prohibido",
    types.FinishReason.RECITATION: "la respuesta se detuvo por recitación de material protegido",
    types.FinishReason.BLOCKLIST: "la respuesta contenía términos vetados",
    types.FinishReason.SPII: "la respuesta contenía información personal sensible",
    types.FinishReason.MALFORMED_FUNCTION_CALL: "el modelo produjo una llamada malformada",
}


# Códigos que justifican reintentar: saturación del modelo, cuota momentánea y fallos
# transitorios del servicio. El 503 "high demand" es frecuente en el plan gratuito.
CODIGOS_REINTENTABLES = {429, 500, 502, 503, 504}
INTENTOS_MAXIMOS = 3
ESPERA_BASE_SEGUNDOS = 2.0


class AgenteNoConfigurado(RuntimeError):
    """Se intentó usar el agente sin GEMINI_API_KEY."""


class ContextoDemasiadoGrande(ValueError):
    """El material excede lo que se puede enviar en una sola solicitud."""


class RespuestaBloqueada(RuntimeError):
    """El modelo no devolvió contenido utilizable (filtros, truncamiento, etc.)."""


class ErrorProveedorIA(RuntimeError):
    """Fallo del proveedor de IA, con el código HTTP que debe devolver la API.

    Existe para que el mensaje traducido llegue al usuario: un `RuntimeError` sin más
    termina como «500 Internal Server Error» y se pierde toda la explicación.
    """

    def __init__(self, mensaje: str, estado_http: int = 502) -> None:
        super().__init__(mensaje)
        self.estado_http = estado_http


class AgenteSecop:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._cliente: genai.Client | None = None
        if settings.ia_configurada:
            self._cliente = genai.Client(api_key=settings.gemini_api_key)

    @property
    def disponible(self) -> bool:
        return self._cliente is not None

    def _exigir_cliente(self) -> genai.Client:
        if self._cliente is None:
            raise AgenteNoConfigurado(
                "GEMINI_API_KEY no está configurada. Copia backend/.env.example a "
                "backend/.env y agrega tu API key de Google AI Studio "
                "(https://aistudio.google.com/apikey)."
            )
        return self._cliente

    async def cerrar(self) -> None:
        # El cliente de google-genai no expone un cierre explícito; sus conexiones se
        # liberan con el recolector de basura.
        self._cliente = None

    # -- núcleo -------------------------------------------------------------------

    def _configuracion(
        self, instruccion: str, modelo_salida: type[BaseModel] | None = None
    ) -> types.GenerateContentConfig:
        """Configuración común. El prompt de sistema es estable para aprovechar el
        almacenamiento en caché implícito de Gemini 2.5."""
        config = types.GenerateContentConfig(
            system_instruction=f"{prompts.SISTEMA_BASE}\n\n{instruccion}",
            max_output_tokens=self.settings.gemini_max_tokens,
            temperature=self.settings.gemini_temperature,
            safety_settings=[
                types.SafetySetting(
                    category=categoria,
                    threshold=types.HarmBlockThreshold.BLOCK_ONLY_HIGH,
                )
                for categoria in CATEGORIAS_SEGURIDAD
            ],
        )
        if modelo_salida is not None:
            config.response_mime_type = "application/json"
            config.response_schema = modelo_salida
        if self.settings.gemini_thinking_budget is not None:
            config.thinking_config = types.ThinkingConfig(
                thinking_budget=self.settings.gemini_thinking_budget
            )
        return config

    @staticmethod
    def _revisar_respuesta(respuesta: types.GenerateContentResponse) -> None:
        """Convierte los fallos silenciosos del modelo en excepciones explícitas."""
        realimentacion = respuesta.prompt_feedback
        if realimentacion is not None and realimentacion.block_reason is not None:
            raise RespuestaBloqueada(
                "El material enviado fue bloqueado por los filtros del modelo "
                f"({realimentacion.block_reason}). Revisa el contenido del pliego."
            )

        candidatos = respuesta.candidates or []
        if not candidatos:
            raise RespuestaBloqueada("El modelo no devolvió ninguna respuesta.")

        motivo = candidatos[0].finish_reason
        if motivo == types.FinishReason.MAX_TOKENS:
            raise RespuestaBloqueada(
                "La respuesta se truncó por límite de tokens. Reduce el material de "
                "entrada o aumenta GEMINI_MAX_TOKENS."
            )
        if motivo in FINALES_PROBLEMATICOS:
            raise RespuestaBloqueada(
                f"El modelo detuvo la generación: {FINALES_PROBLEMATICOS[motivo]}."
            )

    async def _con_reintentos(self, operacion):
        """Ejecuta la llamada reintentando los fallos transitorios del proveedor.

        El plan gratuito de Gemini devuelve 503 («high demand») y 429 con frecuencia;
        sin reintentos, una petición perfectamente válida falla ante el usuario.
        """
        ultimo: genai_errors.APIError | None = None
        for intento in range(1, INTENTOS_MAXIMOS + 1):
            try:
                return await operacion()
            except genai_errors.APIError as exc:
                codigo = getattr(exc, "code", None)
                if codigo not in CODIGOS_REINTENTABLES or intento == INTENTOS_MAXIMOS:
                    raise ErrorProveedorIA(
                        _mensaje_error_api(exc, self.settings.gemini_model),
                        estado_http=429 if codigo == 429 else 502,
                    ) from exc
                ultimo = exc
                # Retroceso exponencial con jitter, para no sincronizar reintentos.
                espera = ESPERA_BASE_SEGUNDOS * (2 ** (intento - 1))
                espera += random.uniform(0, 1)
                log.warning(
                    "Gemini devolvió %s (intento %s/%s); reintentando en %.1fs",
                    codigo, intento, INTENTOS_MAXIMOS, espera,
                )
                await asyncio.sleep(espera)

        # Inalcanzable: la última iteración siempre lanza. Presente por exhaustividad.
        raise ErrorProveedorIA(
            _mensaje_error_api(ultimo, self.settings.gemini_model)
            if ultimo
            else "Fallo desconocido del proveedor de IA."
        )

    async def _estructurado(
        self,
        instruccion: str,
        contenido_usuario: str,
        modelo_salida: type[T],
    ) -> T:
        cliente = self._exigir_cliente()
        if len(contenido_usuario) > LIMITE_PLIEGO:
            raise ContextoDemasiadoGrande(
                f"El material recibido tiene {len(contenido_usuario):,} caracteres y supera "
                f"el límite de {LIMITE_PLIEGO:,}. Divídelo por capítulos (por ejemplo, "
                "anexo técnico por separado) y analiza cada parte."
            )

        respuesta = await self._con_reintentos(
            lambda: cliente.aio.models.generate_content(
                model=self.settings.gemini_model,
                contents=contenido_usuario,
                config=self._configuracion(instruccion, modelo_salida),
            )
        )

        self._revisar_respuesta(respuesta)

        # `parsed` ya viene validado contra el modelo Pydantic. Si el JSON llegó
        # malformado, el SDK lo deja en None y reintentamos el análisis a mano para
        # poder dar un mensaje útil.
        analizado = respuesta.parsed
        if isinstance(analizado, modelo_salida):
            return analizado

        texto = (respuesta.text or "").strip()
        if not texto:
            raise RespuestaBloqueada("El modelo devolvió una respuesta vacía.")
        try:
            return modelo_salida.model_validate(json.loads(texto))
        except (json.JSONDecodeError, ValidationError) as exc:
            log.warning("Respuesta no conforme al esquema: %s", texto[:500])
            raise RuntimeError(
                "El modelo devolvió una respuesta que no cumple el esquema esperado. "
                "Reintenta; si persiste, prueba con un modelo más capaz "
                "(GEMINI_MODEL=gemini-2.5-pro)."
            ) from exc

    # -- casos de uso -------------------------------------------------------------

    async def analizar_requisitos(self, solicitud: SolicitudAnalisis) -> RespuestaAnalisis:
        partes = ["# Material del proceso a analizar", ""]
        if solicitud.entidad:
            partes.append(f"**Entidad:** {solicitud.entidad}")
        if solicitud.objeto_contractual:
            partes.append(f"**Objeto contractual:** {solicitud.objeto_contractual}")
        if solicitud.modalidad:
            partes.append(f"**Modalidad de selección:** {solicitud.modalidad}")
        if solicitud.valor_estimado is not None:
            partes.append(f"**Valor estimado (COP):** {solicitud.valor_estimado:,.0f}")
        if solicitud.contexto_proveedor:
            partes.append(
                "\n## Contexto del oferente (para calibrar riesgos)\n"
                f"{solicitud.contexto_proveedor}"
            )
        partes.append("\n## Texto del pliego / anexo técnico / estudios previos\n")
        partes.append(solicitud.texto_pliego)
        return await self._estructurado(
            prompts.INSTRUCCION_ANALISIS, "\n".join(partes), RespuestaAnalisis
        )

    async def generar_propuesta(self, solicitud: SolicitudPropuesta) -> RespuestaPropuesta:
        partes = [
            "# Insumos para redactar la propuesta técnica",
            "",
            f"**Objeto contractual:** {solicitud.objeto_contractual}",
        ]
        if solicitud.entidad:
            partes.append(f"**Entidad contratante:** {solicitud.entidad}")
        if solicitud.valor_estimado is not None:
            partes.append(f"**Valor estimado (COP):** {solicitud.valor_estimado:,.0f}")
        if solicitud.plazo_meses:
            partes.append(f"**Plazo de ejecución (meses):** {solicitud.plazo_meses}")
        if solicitud.enfasis:
            partes.append(f"**Énfasis solicitado:** {', '.join(solicitud.enfasis)}")

        partes.append("\n## Perfil y capacidades declaradas del oferente\n")
        partes.append(solicitud.perfil_proveedor)

        if solicitud.requisitos:
            partes.append("\n## Requisitos técnicos identificados (JSON)\n")
            partes.append(
                "```json\n"
                + json.dumps(
                    [r.model_dump(mode="json") for r in solicitud.requisitos],
                    ensure_ascii=False,
                    indent=2,
                )
                + "\n```"
            )
        if solicitud.texto_pliego:
            partes.append("\n## Texto del pliego (referencia)\n")
            partes.append(solicitud.texto_pliego)

        return await self._estructurado(
            prompts.INSTRUCCION_PROPUESTA, "\n".join(partes), RespuestaPropuesta
        )

    async def validar_propuesta(self, solicitud: SolicitudValidacion) -> RespuestaValidacion:
        requisitos = solicitud.requisitos
        if not requisitos:
            if not solicitud.texto_pliego:
                raise ValueError(
                    "Debes enviar `requisitos` estructurados o `texto_pliego` para "
                    "extraerlos antes de validar."
                )
            analisis = await self.analizar_requisitos(
                SolicitudAnalisis(
                    texto_pliego=solicitud.texto_pliego,
                    objeto_contractual=solicitud.objeto_contractual,
                )
            )
            requisitos = analisis.requisitos

        partes = ["# Validación de propuesta contra requisitos", ""]
        if solicitud.objeto_contractual:
            partes.append(f"**Objeto contractual:** {solicitud.objeto_contractual}")
        partes.append("\n## Requisitos a verificar (JSON)\n")
        partes.append(
            "```json\n"
            + json.dumps(
                [r.model_dump(mode="json") for r in requisitos],
                ensure_ascii=False,
                indent=2,
            )
            + "\n```"
        )
        partes.append("\n## Texto de la propuesta a evaluar\n")
        partes.append(solicitud.texto_propuesta)

        return await self._estructurado(
            prompts.INSTRUCCION_VALIDACION, "\n".join(partes), RespuestaValidacion
        )

    async def priorizar_procesos(
        self, solicitud: SolicitudRelevancia
    ) -> RespuestaRelevancia:
        resumen_procesos = [
            {
                "id": p.id,
                "entidad": p.entidad,
                "objeto": p.objeto,
                "modalidad": p.modalidad,
                "estado": p.estado,
                "tipo_contrato": p.tipo_contrato,
                "valor": p.valor,
                "duracion": p.duracion,
                "fecha_publicacion": p.fecha_publicacion,
                "departamento": p.departamento,
            }
            for p in solicitud.procesos
        ]
        partes = [
            "# Procesos a clasificar y priorizar",
            "",
            f"Devuelve como máximo {solicitud.maximo} procesos priorizados.",
        ]
        if solicitud.perfil_proveedor:
            partes.append("\n## Perfil del proveedor\n")
            partes.append(solicitud.perfil_proveedor)
        partes.append("\n## Procesos (JSON)\n")
        partes.append(
            "```json\n" + json.dumps(resumen_procesos, ensure_ascii=False, indent=2) + "\n```"
        )

        return await self._estructurado(
            prompts.INSTRUCCION_RELEVANCIA, "\n".join(partes), RespuestaRelevancia
        )

    # -- chat ---------------------------------------------------------------------

    async def chat_stream(
        self, mensajes: list[MensajeChat], contexto: str | None = None
    ) -> AsyncIterator[str]:
        """Emite fragmentos de texto conforme el modelo los genera."""
        cliente = self._exigir_cliente()

        historial: list[types.Content] = []
        if contexto:
            historial.append(
                types.Content(
                    role="user",
                    parts=[
                        types.Part.from_text(
                            text=(
                                "Contexto de trabajo para esta conversación "
                                "(material del proceso o de la propuesta):\n\n"
                                f"{contexto}"
                            )
                        )
                    ],
                )
            )
            historial.append(
                types.Content(
                    role="model",
                    parts=[
                        types.Part.from_text(
                            text="Contexto recibido. ¿Qué necesitas analizar?"
                        )
                    ],
                )
            )

        # Gemini nombra "model" al turno del asistente.
        historial.extend(
            types.Content(
                role="model" if m.rol == "assistant" else "user",
                parts=[types.Part.from_text(text=m.contenido)],
            )
            for m in mensajes
        )

        # El reintento cubre la apertura del flujo. Una vez que empieza a emitir texto
        # no se puede reintentar sin duplicar lo ya enviado al navegador.
        flujo = await self._con_reintentos(
            lambda: cliente.aio.models.generate_content_stream(
                model=self.settings.gemini_model,
                contents=historial,
                config=self._configuracion(prompts.INSTRUCCION_CHAT),
            )
        )
        try:
            async for fragmento in flujo:
                texto = fragmento.text
                if texto:
                    yield texto
        except genai_errors.APIError as exc:
            raise ErrorProveedorIA(
                _mensaje_error_api(exc, self.settings.gemini_model)
            ) from exc


def _mensaje_error_api(exc: genai_errors.APIError, modelo: str) -> str:
    """Traduce los errores de la API a algo accionable para el usuario."""
    codigo = getattr(exc, "code", None)
    detalle = (getattr(exc, "message", None) or str(exc))[:300]

    if codigo == 400 and "API key" in detalle:
        return "La GEMINI_API_KEY es inválida. Verifica el valor en backend/.env."
    if codigo == 403:
        return (
            "La API key no tiene permiso para usar este modelo. Verifica tu cuenta en "
            "Google AI Studio."
        )
    if codigo == 404:
        return (
            f"El modelo '{modelo}' no existe o tu key no tiene acceso a él. "
            "Ejecuta `python listar_modelos.py` para ver los disponibles y ajusta "
            "GEMINI_MODEL en backend/.env."
        )
    if codigo == 429:
        return (
            "Se agotó la cuota de la API de Gemini. Espera unos minutos, o revisa los "
            "límites de tu plan en Google AI Studio."
        )
    if codigo is not None and codigo >= 500:
        return f"El servicio de Gemini falló temporalmente ({codigo}). Reintenta."
    return f"Error de la API de Gemini ({codigo}): {detalle}"
