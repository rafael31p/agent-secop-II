"""Prueba de humo local: arranca la app en memoria y ejercita los endpoints.

No requiere GEMINI_API_KEY (los endpoints de IA deben responder 503 sin ella)
ni red hacia SECOP (esas pruebas se marcan como omitidas si falla la conexión).

Uso:  python smoke_test.py
"""

from __future__ import annotations

import sys

from fastapi.testclient import TestClient

from app.main import app  # noqa: F401  (usado también por dependency_overrides)

fallos: list[str] = []


def verificar(nombre: str, condicion: bool, detalle: str = "") -> None:
    marca = "OK  " if condicion else "FALLA"
    print(f"[{marca}] {nombre}{(' -> ' + detalle) if detalle and not condicion else ''}")
    if not condicion:
        fallos.append(nombre)


def main() -> int:
    with TestClient(app) as cliente:
        rutas = sorted(cliente.get("/openapi.json").json()["paths"])
        print("Rutas registradas:")
        for ruta in rutas:
            print("  ", ruta)
        print()

        esperadas = [
            "/api/salud",
            "/api/procesos/buscar",
            "/api/procesos/{id_proceso}",
            "/api/procesos/relevancia-ti",
            "/api/analisis/requisitos",
            "/api/analisis/documento",
            "/api/propuestas/generar",
            "/api/propuestas/validar",
            "/api/chat",
        ]
        for ruta in esperadas:
            verificar(f"ruta registrada {ruta}", ruta in rutas)

        # Health check
        r = cliente.get("/api/salud")
        verificar("GET /api/salud -> 200", r.status_code == 200, r.text[:200])
        if r.status_code == 200:
            print("       estado:", r.json())

        # Validación de entrada: valor_min > valor_max
        r = cliente.post(
            "/api/procesos/buscar", json={"valor_min": 100, "valor_max": 10, "limite": 1}
        )
        verificar("buscar con rango inválido -> 422", r.status_code == 422, r.text[:200])

        # Propuesta sin requisitos ni pliego
        r = cliente.post(
            "/api/propuestas/generar",
            json={
                "objeto_contractual": "Desarrollo de un sistema de información",
                "perfil_proveedor": "Fábrica de software con 30 desarrolladores.",
            },
        )
        verificar("generar sin requisitos ni pliego -> 422", r.status_code == 422, r.text[:200])

        # Documento con extensión no soportada
        r = cliente.post(
            "/api/analisis/documento",
            files={"archivo": ("pliego.xls", b"contenido", "application/vnd.ms-excel")},
        )
        verificar("documento .xls -> 415", r.status_code == 415, r.text[:200])

        # Documento .txt válido
        contenido = (
            "ANEXO TECNICO. El contratista debe desarrollar un portal web accesible "
            "conforme a la Resolucion 1519 de 2020 y entregar el codigo fuente."
        ).encode("utf-8")
        r = cliente.post(
            "/api/analisis/documento",
            files={"archivo": ("anexo.txt", contenido, "text/plain")},
        )
        verificar("documento .txt -> 200", r.status_code == 200, r.text[:200])
        if r.status_code == 200:
            verificar("texto extraído no vacío", r.json()["caracteres"] > 50)

        # Endpoints de IA sin API key deben responder 503 (no 500)
        from app.config import get_settings

        configuracion = get_settings()
        if not configuracion.ia_configurada:
            r = cliente.post(
                "/api/analisis/requisitos",
                json={"texto_pliego": "x" * 100},
            )
            verificar("analisis sin API key -> 503", r.status_code == 503, r.text[:200])
            verificar(
                "el 503 menciona GEMINI_API_KEY",
                "GEMINI_API_KEY" in r.text,
                r.text[:200],
            )
        else:
            print("[OMIT] GEMINI_API_KEY configurada: se omiten las pruebas de 503")

        # El agente debe construirse sin lanzar con la configuración actual.
        from app.services.ai_agent import AgenteSecop

        agente = AgenteSecop(configuracion)
        verificar(
            "el agente refleja el estado de la configuración",
            agente.disponible == configuracion.ia_configurada,
        )

        # Regresión: los fallos del proveedor deben llegar al cliente con su mensaje,
        # no como «500 Internal Server Error». Antes se perdían en la frontera HTTP.
        from app.dependencies import get_agente
        from app.services.ai_agent import (
            ContextoDemasiadoGrande,
            ErrorProveedorIA,
            RespuestaBloqueada,
        )

        casos_error: list[tuple[str, Exception, int, str]] = [
            ("cuota agotada -> 429", ErrorProveedorIA("sin cuota", 429), 429, "sin cuota"),
            ("servicio caído -> 502", ErrorProveedorIA("servicio caido"), 502, "caido"),
            ("filtro de contenido -> 422", RespuestaBloqueada("bloqueado"), 422, "bloqueado"),
            ("pliego enorme -> 413", ContextoDemasiadoGrande("muy grande"), 413, "grande"),
        ]

        class AgenteQueFalla:
            """Doble de prueba: solo necesita lanzar la excepción bajo estudio.

            No se instancia `AgenteSecop` porque crearía un cliente real de Gemini.
            """

            def __init__(self, excepcion: Exception) -> None:
                self.excepcion = excepcion

            async def analizar_requisitos(self, _solicitud):
                raise self.excepcion

        def sustituir_agente(doble):
            # El override debe ser una función SIN parámetros: FastAPI inspecciona la
            # firma y trataría cualquier argumento (incluso con valor por defecto)
            # como un campo de la petición, intentando copiarlo.
            def _dependencia():
                return doble

            return _dependencia

        for nombre, excepcion, esperado, fragmento in casos_error:
            app.dependency_overrides[get_agente] = sustituir_agente(
                AgenteQueFalla(excepcion)
            )
            try:
                r = cliente.post(
                    "/api/analisis/requisitos", json={"texto_pliego": "x" * 100}
                )
                verificar(
                    nombre,
                    r.status_code == esperado and fragmento in r.text,
                    f"recibido {r.status_code}: {r.text[:120]}",
                )
            finally:
                app.dependency_overrides.clear()

        # Heurística local de relevancia TI (sin red, sin IA)
        from app.services.secop_client import puntuar_relevancia_ti

        puntaje, señales = puntuar_relevancia_ti(
            "Desarrollo de software para el sistema de informacion misional en la nube"
        )
        verificar("heurística TI detecta objeto tecnológico", puntaje >= 20, str(puntaje))
        verificar("heurística TI reporta señales", len(señales) >= 2, str(señales))

        puntaje_no_ti, _ = puntuar_relevancia_ti(
            "Suministro de refrigerios para la jornada de bienestar institucional"
        )
        verificar("heurística TI descarta objeto no tecnológico", puntaje_no_ti == 0,
                  str(puntaje_no_ti))

        # Regresión: los acrónimos cortos no deben casar por subcadena.
        # "api" ⊄ "capital", "soc" ⊄ "social", "tic" ⊄ "logística", "erp" ⊄ "cuerpo".
        trampas = [
            "Apoyo al capital social de la poblacion en condicion de vulnerabilidad",
            "Servicios de operacion logistica y practica deportiva para el cuerpo de bomberos",
            "Otorgar apoyo economico para la recuperacion de la infraestructura vial",
            "Prestacion de servicios de terapia fisica y diagnostico clinico",
        ]
        for frase in trampas:
            puntaje_trampa, señales_trampa = puntuar_relevancia_ti(frase)
            verificar(
                f"sin falso positivo: '{frase[:42]}...'",
                puntaje_trampa == 0,
                f"puntaje={puntaje_trampa} señales={señales_trampa}",
            )

        # El límite de palabra no debe impedir coincidencias legítimas con puntuación.
        puntaje_valido, _ = puntuar_relevancia_ti(
            "Desarrollo de APIs REST; licenciamiento SaaS, mesa de ayuda y SOC 24/7."
        )
        verificar("términos legítimos con puntuación sí puntúan", puntaje_valido >= 15,
                  str(puntaje_valido))

        # SECOP real (opcional; puede fallar sin red)
        print("\n--- SECOP II en vivo (opcional) ---")
        r = cliente.post(
            "/api/procesos/buscar",
            json={"texto": "software", "limite": 3, "solo_ti": False},
        )
        if r.status_code == 200:
            cuerpo = r.json()
            print(f"[OK  ] SECOP respondió con {cuerpo['total']} procesos")
            for advertencia in cuerpo["advertencias"]:
                print("       advertencia:", advertencia)
            for proceso in cuerpo["procesos"][:3]:
                print(f"       - {(proceso.get('objeto') or '')[:90]}")
        else:
            print(f"[OMIT] SECOP no disponible ({r.status_code}) — se omite esta prueba")

    print()
    if fallos:
        print(f"RESULTADO: {len(fallos)} prueba(s) fallida(s): {', '.join(fallos)}")
        return 1
    print("RESULTADO: todas las pruebas locales pasaron.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
