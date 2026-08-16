"""Configuración de la aplicación (variables de entorno)."""

from functools import lru_cache

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # --- Google Gemini ---
    gemini_api_key: str = ""
    # Verificado contra la API en agosto de 2026. Los identificadores se retiran con el
    # tiempo (gemini-2.5-flash ya devuelve 404 a keys nuevas): si falla, ejecuta
    # `python listar_modelos.py`, que sondea cada modelo con una llamada real.
    gemini_model: str = "gemini-3.6-flash"
    # Incluye los tokens de razonamiento de Gemini 2.5, por eso el margen amplio.
    gemini_max_tokens: int = 32000
    # Bajo, porque el trabajo es extracción y verificación, no redacción creativa.
    gemini_temperature: float = 0.2
    # None = el modelo decide cuánto razonar. 0 lo desactiva (más barato y rápido,
    # peor en análisis normativo). Máximo 24576 en los modelos 2.5.
    gemini_thinking_budget: int | None = None

    # --- SECOP II / Socrata ---
    secop_app_token: str = ""
    secop_base_url: str = "https://www.datos.gov.co/resource"
    secop_procesos_dataset: str = "p6dx-8zbt"
    secop_contratos_dataset: str = "jbjy-vk9h"

    # --- App ---
    cors_origins: str = "http://localhost:5173,http://127.0.0.1:5173"
    log_level: str = "INFO"

    @field_validator("gemini_thinking_budget", mode="before")
    @classmethod
    def _vacio_es_nulo(cls, valor: object) -> object:
        """`GEMINI_THINKING_BUDGET=` (vacío) significa «el modelo decide», no error.

        Sin esto, la propia línea que trae .env.example aborta el arranque: pydantic
        recibe la cadena vacía e intenta convertirla a entero.
        """
        if isinstance(valor, str) and not valor.strip():
            return None
        return valor

    @property
    def cors_origins_list(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]

    @property
    def ia_configurada(self) -> bool:
        return bool(self.gemini_api_key.strip())


@lru_cache
def get_settings() -> Settings:
    return Settings()
