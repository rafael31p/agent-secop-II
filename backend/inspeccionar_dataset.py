"""Utilidad de diagnóstico: imprime el esquema real del dataset SECOP II configurado.

Úsala cuando la API responda `no-such-column` para ajustar los alias en
`app/services/secop_client.py`.

Uso:  python inspeccionar_dataset.py [dataset_id]
"""

from __future__ import annotations

import json
import sys

import httpx

from app.config import get_settings


def main() -> int:
    settings = get_settings()
    dataset = sys.argv[1] if len(sys.argv) > 1 else settings.secop_procesos_dataset
    url = f"{settings.secop_base_url.rstrip('/')}/{dataset}.json"
    cabeceras = {"X-App-Token": settings.secop_app_token} if settings.secop_app_token else {}

    respuesta = httpx.get(url, params={"$limit": 1}, headers=cabeceras, timeout=60.0)
    respuesta.raise_for_status()
    filas = respuesta.json()
    if not filas:
        print(f"El dataset {dataset} no devolvió filas.")
        return 1

    fila = filas[0]
    print(f"Dataset: {dataset}   ({len(fila)} columnas en la primera fila)\n")
    for clave in sorted(fila):
        valor = str(fila[clave]).replace("\n", " ")
        print(f"  {clave:<45} = {valor[:70]}")

    print("\nFila completa (JSON):")
    print(json.dumps(fila, ensure_ascii=False, indent=2)[:4000])
    return 0


if __name__ == "__main__":
    sys.exit(main())
