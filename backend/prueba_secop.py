"""Prueba de integración contra SECOP II en vivo (requiere red, no requiere API key).

Ejercita cada filtro por separado para detectar qué cláusula SoQL falla si el esquema
del dataset cambia.

Uso:  python prueba_secop.py
"""

from __future__ import annotations

import asyncio
import sys

from app.config import get_settings
from app.schemas import FiltroProcesos
from app.services.secop_client import SecopClient

CASOS: list[tuple[str, dict]] = [
    ("sin filtros", {"limite": 2}),
    ("texto libre", {"texto": "software", "limite": 3}),
    ("solo_ti", {"solo_ti": True, "limite": 5}),
    ("entidad", {"entidad": "DANE", "limite": 2}),
    ("departamento", {"departamento": "Antioquia", "limite": 2}),
    ("modalidad", {"modalidad": "Licitación", "limite": 2}),
    ("estado", {"estado": "Presentación", "limite": 2}),
    ("rango de valor", {"valor_min": 50_000_000, "valor_max": 5_000_000_000, "limite": 2}),
    ("rango de fechas", {"fecha_desde": "2025-01-01", "fecha_hasta": "2026-12-31", "limite": 2}),
    (
        "combinado TI + valor",
        {"solo_ti": True, "valor_min": 100_000_000, "limite": 5},
    ),
]


async def main() -> int:
    cliente = SecopClient(get_settings())
    fallos = 0
    try:
        for nombre, argumentos in CASOS:
            respuesta = await cliente.buscar_procesos(FiltroProcesos(**argumentos))
            problema = any(
                "no-such-column" in a or "se devuelven resultados sin filtrar" in a
                for a in respuesta.advertencias
            )
            marca = "FALLA" if problema else ("VACIO" if respuesta.total == 0 else "OK  ")
            if problema:
                fallos += 1
            print(f"[{marca}] {nombre:<22} -> {respuesta.total} resultado(s)")
            for advertencia in respuesta.advertencias:
                print(f"        ! {advertencia[:220]}")
            sin_fecha = [p for p in respuesta.procesos if not p.fecha_publicacion]
            if sin_fecha:
                print(
                    f"        ! {len(sin_fecha)} proceso(s) sin fecha de publicación "
                    "(el orden por fecha DESC estaría degradado)"
                )
                fallos += 1
            for proceso in respuesta.procesos[:2]:
                objeto = (proceso.objeto or "")[:60]
                valor = f"{proceso.valor:,.0f}" if proceso.valor else "s/d"
                fecha = (proceso.fecha_publicacion or "s/d")[:10]
                print(
                    f"        · [TI {proceso.score_ti:>3}] {fecha} {valor:>16} COP | {objeto}"
                )

        print("\n--- Detalle de un proceso ---")
        base = await cliente.buscar_procesos(FiltroProcesos(solo_ti=True, limite=1))
        if base.procesos and base.procesos[0].id:
            detalle = await cliente.obtener_proceso(base.procesos[0].id)
            if detalle:
                print(f"[OK  ] obtener_proceso({detalle.id})")
                print(f"        entidad : {detalle.entidad}")
                print(f"        objeto  : {(detalle.objeto or '')[:100]}")
                print(f"        url     : {detalle.url}")
                print(f"        señales : {', '.join(detalle.señales_ti[:8])}")
            else:
                print("[FALLA] obtener_proceso devolvió None")
                fallos += 1
    finally:
        await cliente.cerrar()

    print()
    print("RESULTADO:", "todo OK" if fallos == 0 else f"{fallos} caso(s) con problemas")
    return 1 if fallos else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
