"""Verificación en vivo del backend Quarkus (requiere el servidor levantado en :8000).

Comprueba la paridad funcional con el backend Python y, sobre todo, lo nuevo:
la selección de proveedor y modelo por petición.

Uso:  python verificar_en_vivo.py
"""

from __future__ import annotations

import json
import sys

import httpx

BASE = "http://127.0.0.1:8000"
fallos: list[str] = []


def verificar(nombre: str, condicion: bool, detalle: str = "") -> None:
    print(f"[{'OK  ' if condicion else 'FALLA'}] {nombre}")
    if detalle:
        print(f"        {detalle}")
    if not condicion:
        fallos.append(nombre)


PLIEGO = (
    "ANEXO TECNICO LP-2026-014. El contratista debe desarrollar el portal ciudadano "
    "bajo arquitectura de microservicios con API REST documentada en OpenAPI 3.0. "
    "Debe entregar el codigo fuente completo; la titularidad sera de la entidad. "
    "El portal debe cumplir la Resolucion 1519 de 2020 de MinTIC y el nivel AA de las "
    "WCAG 2.1, acreditado con informe de auditoria de accesibilidad. El contratista "
    "debe acreditar certificacion vigente ISO/IEC 27001. Se otorgaran hasta 100 puntos "
    "por experiencia adicional en proyectos de gobierno digital."
)


def main() -> int:
    with httpx.Client(base_url=BASE, timeout=420.0) as cliente:
        # 1. Salud
        print("--- 1. Salud ---")
        r = cliente.get("/api/salud")
        verificar("GET /api/salud -> 200", r.status_code == 200, r.text[:200])
        salud = r.json()
        print(f"        {json.dumps(salud, ensure_ascii=False)}")
        verificar("reporta IA configurada", salud["iaConfigurada"] is True)
        verificar("modelo por defecto correcto",
                  salud["modeloPorDefecto"] == "gemini-3.6-flash",
                  salud["modeloPorDefecto"])

        # 1b. Sondas, métricas y correlación (fase 3)
        #
        # `/api/salud` informa de qué hay CONFIGURADO; estas informan de si FUNCIONA.
        # La diferencia importa: el primero devolvía «ok» con la clave revocada.
        print("\n--- 1b. Sondas de operación, métricas y correlación ---")
        r = cliente.get("/q/health/live")
        verificar("GET /q/health/live -> UP", r.json().get("status") == "UP", r.text[:200])

        r = cliente.get("/q/health/ready")
        sondas = {c["name"]: c for c in r.json().get("checks", [])}
        verificar("la disponibilidad reporta los modelos",
                  "modelos-de-lenguaje" in sondas,
                  f"sondas: {sorted(sondas)}")
        if "modelos-de-lenguaje" in sondas:
            datos = sondas["modelos-de-lenguaje"].get("data", {})
            print(f"        modelos: {sondas['modelos-de-lenguaje']['status']} "
                  f"(cortacircuitos: {datos.get('cortacircuitos')})")
        verificar("la fuente de SECOP se reporta aparte", "fuente-secop" in sondas,
                  "Fundirlas borraría que se puede buscar sin poder analizar")

        r = cliente.get("/q/metrics")
        verificar("GET /q/metrics -> 200", r.status_code == 200)

        mio = "verificacion-en-vivo"
        r = cliente.get("/api/salud", headers={"X-Correlation-Id": mio})
        verificar("conserva el identificador de correlación del cliente",
                  r.headers.get("X-Correlation-Id") == mio,
                  r.headers.get("X-Correlation-Id"))
        # El salto de línea, que es el caso feo, no se puede probar desde aquí: httpx se
        # niega a enviarlo («Illegal header value»). Lo cubre CorrelacionTest, que llega
        # al filtro sin pasar por un cliente HTTP escrupuloso. Aquí se prueban los dos que
        # sí viajan por la red: el desmesurado y el de caracteres no admitidos.
        abusivo = "a" * 10_000
        r = cliente.get("/api/salud", headers={"X-Correlation-Id": abusivo})
        devuelto = r.headers.get("X-Correlation-Id") or ""
        verificar("descarta un identificador desmesurado",
                  len(devuelto) <= 64, f"{len(devuelto)} caracteres")
        r = cliente.get("/api/salud", headers={"X-Correlation-Id": "id con espacios y ;"})
        verificar("descarta un identificador con caracteres no admitidos",
                  r.headers.get("X-Correlation-Id") != "id con espacios y ;",
                  r.headers.get("X-Correlation-Id"))

        # 2. Catálogo de proveedores (lo nuevo frente a la versión Python)
        print("\n--- 2. Catálogo de proveedores ---")
        r = cliente.get("/api/proveedores")
        verificar("GET /api/proveedores -> 200", r.status_code == 200)
        proveedores = r.json()
        verificar("expone los cinco proveedores", len(proveedores) == 5,
                  f"{len(proveedores)} proveedores")
        for p in proveedores:
            estado = "configurado" if p["configurado"] else f"no disponible: {p['motivo'][:60]}"
            print(f"        · {p['nombre']:<10} {p['modeloPorDefecto']:<22} {estado}")

        # 3. SECOP real
        print("\n--- 3. Búsqueda en SECOP II ---")
        r = cliente.post("/api/procesos/buscar",
                         json={"texto": "software", "limite": 3, "soloTi": True})
        verificar("POST /api/procesos/buscar -> 200", r.status_code == 200, r.text[:200])
        cuerpo = r.json()
        verificar("devuelve procesos", cuerpo["total"] > 0, f"{cuerpo['total']} procesos")
        for proceso in cuerpo["procesos"][:3]:
            objeto = (proceso.get("objeto") or "")[:66]
            print(f"        · [TI {proceso['scoreTi']:>3}] {proceso['fechaPublicacion'][:10]} "
                  f"| {objeto}")

        # 4. Análisis con el proveedor por defecto
        print("\n--- 4. Análisis de requisitos (proveedor por defecto) ---")
        r = cliente.post("/api/analisis/requisitos",
                         json={"textoPliego": PLIEGO,
                               "objetoContractual": "Portal web ciudadano"})
        verificar("POST /api/analisis/requisitos -> 200", r.status_code == 200, r.text[:300])
        if r.status_code == 200:
            analisis = r.json()
            verificar("extrae requisitos", len(analisis["requisitos"]) >= 4,
                      f"{len(analisis['requisitos'])} requisitos")
            verificar("identifica riesgos", len(analisis["riesgos"]) >= 1,
                      f"{len(analisis['riesgos'])} riesgos")
            criticidades = {req["criticidad"] for req in analisis["requisitos"]}
            verificar("serializa las enumeraciones por valor",
                      criticidades <= {"obligatorio", "ponderable", "deseable", "informativo"},
                      str(criticidades))
            for req in analisis["requisitos"][:3]:
                print(f"        · [{req['id']}] ({req['criticidad']}) "
                      f"{req['requisito'][:64]}")
                if req.get("citaPliego"):
                    print(f"            cita: «{req['citaPliego'][:60]}»")

        # 5. Selección explícita de proveedor y modelo (la funcionalidad nueva)
        print("\n--- 5. Selección de proveedor y modelo por petición ---")
        r = cliente.post("/api/analisis/requisitos",
                         json={"textoPliego": PLIEGO,
                               "proveedor": "gemini",
                               "modelo": "gemini-3.5-flash"})
        verificar("acepta modelo alterno (gemini-3.5-flash) -> 200",
                  r.status_code == 200, r.text[:200])

        r = cliente.post("/api/analisis/requisitos",
                         json={"textoPliego": PLIEGO, "proveedor": "openai"})
        verificar("proveedor sin credenciales -> 503", r.status_code == 503, r.text[:200])
        if r.status_code == 503:
            print(f"        {r.json()['detail'][:110]}")

        r = cliente.post("/api/analisis/requisitos",
                         json={"textoPliego": PLIEGO, "proveedor": "inventado"})
        verificar("proveedor desconocido -> 400", r.status_code == 400, r.text[:200])

        r = cliente.post("/api/analisis/requisitos",
                         json={"textoPliego": PLIEGO,
                               "proveedor": "gemini",
                               "modelo": "modelo-que-no-existe"})
        verificar("modelo inexistente -> 404 con explicación",
                  r.status_code == 404, f"{r.status_code}: {r.text[:150]}")

        # 6. Errores de validación
        print("\n--- 6. Validación de entrada ---")
        r = cliente.post("/api/analisis/requisitos", json={"textoPliego": "corto"})
        verificar("pliego demasiado corto -> 422", r.status_code == 422, r.text[:150])

        r = cliente.post("/api/procesos/buscar", json={"valorMin": 100, "valorMax": 10})
        verificar("rango de valor invertido -> 422", r.status_code == 422, r.text[:150])

        # 7. Chat en streaming
        print("\n--- 7. Chat en streaming (SSE) ---")
        eventos: list[str] = []
        texto = ""
        with cliente.stream(
            "POST", "/api/chat",
            json={"mensajes": [{"rol": "user",
                                "contenido": "En una frase: ¿qué es el SECOP II?"}]},
        ) as respuesta:
            verificar("POST /api/chat -> 200", respuesta.status_code == 200)
            for linea in respuesta.iter_lines():
                if linea.startswith("event:"):
                    eventos.append(linea.split(":", 1)[1].strip())
                elif linea.startswith("data:"):
                    carga = linea.split(":", 1)[1].strip()
                    try:
                        datos = json.loads(carga)
                        texto += datos.get("texto", "")
                    except json.JSONDecodeError:
                        pass
        verificar("emite eventos 'delta'", "delta" in eventos,
                  f"eventos: {sorted(set(eventos))}")
        verificar("cierra con evento 'fin'", "fin" in eventos)
        verificar("produce texto", len(texto) > 20, texto[:140])

        # 8. Métricas, ahora que ya hubo tráfico
        #
        # Va al final y no junto a las sondas por un motivo que costó una comprobación
        # fallida: Fault Tolerance y Micrometer registran sus medidores la primera vez que
        # se invoca el método guardado. Antes del primer análisis no existe ni una sola
        # métrica que mirar, así que comprobarlas al principio siempre da rojo.
        print("\n--- 8. Métricas tras el tráfico ---")
        metricas = cliente.get("/q/metrics").text
        verificar("mide la latencia por caso de uso",
                  "llm_peticion_seconds" in metricas and 'caso_de_uso="AnalisisDePliego"' in metricas)
        verificar("cuenta el consumo estimado", "llm_tokens_total" in metricas,
                  "Es la métrica que responde cuánto cuesta esto")
        verificar("publica el estado del cortacircuitos",
                  "ft_circuitbreaker_calls_total" in metricas)
        verificar("publica la ocupación del mamparo",
                  "ft_bulkhead" in metricas,
                  "Es con lo que se calibra el valor, en vez de adivinarlo")
        # La cardinalidad: los dos campos vienen de la petición del usuario.
        verificar("no etiqueta con un proveedor inventado",
                  'proveedor="inventado"' not in metricas,
                  "Un bucle pidiendo proveedores al azar llenaría el almacén de métricas")
        verificar("no etiqueta con un modelo inventado",
                  'modelo="modelo-que-no-existe"' not in metricas)

    print()
    if fallos:
        print(f"RESULTADO: {len(fallos)} prueba(s) fallida(s): {', '.join(fallos)}")
        return 1
    print("RESULTADO: el backend Quarkus funciona de extremo a extremo.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
