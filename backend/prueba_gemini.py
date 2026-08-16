"""Prueba de integración real contra la API de Gemini (requiere GEMINI_API_KEY).

Ejercita los cuatro casos de uso del agente con un pliego de ejemplo pequeño, para
validar la key, el modelo y la conformidad de las salidas estructuradas sin gastar
muchos tokens.

Uso:  python prueba_gemini.py
"""

from __future__ import annotations

import asyncio
import sys

from app.config import get_settings
from app.schemas import (
    ProcesoResumen,
    SolicitudAnalisis,
    SolicitudPropuesta,
    SolicitudRelevancia,
    SolicitudValidacion,
)
from app.services.ai_agent import AgenteSecop

PLIEGO = """
ANEXO TÉCNICO — PROCESO LP-2026-014
Objeto: Desarrollo, implementación y puesta en marcha del sistema de información
misional para la gestión de trámites ciudadanos de la entidad, incluida su migración
a la nube y el soporte durante doce (12) meses.

1. REQUISITOS TÉCNICOS OBLIGATORIOS
1.1 El sistema debe desarrollarse bajo una arquitectura de microservicios, desplegable
    en contenedores, con API REST documentada en OpenAPI 3.0.
1.2 El contratista debe entregar el código fuente completo y su documentación. La
    titularidad de los desarrollos a la medida será de la entidad.
1.3 El portal ciudadano debe cumplir la Resolución 1519 de 2020 de MinTIC y el nivel
    AA de las WCAG 2.1. Se acreditará con informe de auditoría de accesibilidad.
1.4 El contratista debe acreditar certificación vigente ISO/IEC 27001 mediante
    certificado expedido por organismo acreditado.
1.5 Debe garantizarse disponibilidad del 99,5% mensual, con RTO de 4 horas y RPO de
    1 hora, acreditado mediante el plan de continuidad.
1.6 El tratamiento de datos personales debe cumplir la Ley 1581 de 2012.

2. EQUIPO MÍNIMO DE TRABAJO
2.1 Un (1) gerente de proyecto con certificación PMP vigente y diez (10) años de
    experiencia. Dedicación 50%.
2.2 Un (1) arquitecto de software con cinco (5) años de experiencia en microservicios.

3. FACTORES DE PONDERACIÓN
3.1 Se otorgarán hasta 100 puntos por experiencia adicional acreditada en proyectos de
    gobierno digital.
3.2 Se otorgarán hasta 50 puntos por metodología de pruebas automatizadas.
"""

PERFIL = """
Fábrica de software con 12 años de operación. 45 desarrolladores (Java/Spring Boot,
Angular, Python). Certificación ISO 9001 vigente. Experiencia en 8 contratos con
entidades públicas colombianas, tres de ellos de gobierno digital. Despliegue en AWS
y Azure con Kubernetes. Equipo interno de QA con automatización en Cypress y JUnit.
No contamos con certificación ISO 27001 ni con un gerente certificado PMP.
"""

fallos: list[str] = []


def verificar(nombre: str, condicion: bool, detalle: str = "") -> None:
    print(f"[{'OK  ' if condicion else 'FALLA'}] {nombre}")
    if detalle:
        print(f"        {detalle}")
    if not condicion:
        fallos.append(nombre)


async def main() -> int:
    configuracion = get_settings()
    if not configuracion.ia_configurada:
        print(
            "GEMINI_API_KEY no está configurada.\n"
            "Copia backend/.env.example a backend/.env y agrega tu key de "
            "https://aistudio.google.com/apikey"
        )
        return 1

    print(f"Modelo: {configuracion.gemini_model}\n")
    agente = AgenteSecop(configuracion)

    # 1. Análisis de requisitos
    print("--- 1. Análisis de requisitos ---")
    analisis = await agente.analizar_requisitos(
        SolicitudAnalisis(
            texto_pliego=PLIEGO,
            objeto_contractual="Sistema de información misional para trámites ciudadanos",
            modalidad="Licitación pública",
            contexto_proveedor=PERFIL,
        )
    )
    verificar("extrae requisitos", len(analisis.requisitos) >= 5,
              f"{len(analisis.requisitos)} requisitos")
    verificar("marca requisitos obligatorios",
              any(r.criticidad == "obligatorio" for r in analisis.requisitos))
    verificar("identifica riesgos", len(analisis.riesgos) >= 1,
              f"{len(analisis.riesgos)} riesgos")
    verificar("produce recomendación", len(analisis.recomendacion) > 20)
    for requisito in analisis.requisitos[:4]:
        print(f"        · [{requisito.id}] ({requisito.criticidad}) "
              f"{requisito.requisito[:80]}")

    # La ISO 27001 es el hueco deliberado del perfil: el agente debería verlo.
    texto_analisis = " ".join(
        [analisis.recomendacion, *(r.descripcion for r in analisis.riesgos)]
    ).lower()
    verificar(
        "detecta la brecha de ISO 27001 declarada en el perfil",
        "27001" in texto_analisis,
        "(si falla, el modelo ignoró el contexto del oferente)",
    )

    # 2. Generación de propuesta
    print("\n--- 2. Generación de propuesta ---")
    propuesta = await agente.generar_propuesta(
        SolicitudPropuesta(
            objeto_contractual="Sistema de información misional para trámites ciudadanos",
            requisitos=analisis.requisitos,
            perfil_proveedor=PERFIL,
            plazo_meses=12,
            enfasis=["seguridad de la información", "accesibilidad"],
        )
    )
    verificar("genera secciones", len(propuesta.secciones) >= 4,
              f"{len(propuesta.secciones)} secciones")
    verificar("produce markdown exportable", len(propuesta.markdown) > 500,
              f"{len(propuesta.markdown):,} caracteres")
    verificar(
        "reporta los vacíos en vez de inventar certificaciones",
        len(propuesta.vacios_de_informacion) >= 1,
        f"vacíos: {propuesta.vacios_de_informacion[:2]}",
    )

    # 3. Validación
    print("\n--- 3. Validación de la propuesta ---")
    validacion = await agente.validar_propuesta(
        SolicitudValidacion(
            texto_propuesta=propuesta.markdown,
            requisitos=analisis.requisitos,
            objeto_contractual="Sistema de información misional",
        )
    )
    verificar("evalúa todos los requisitos",
              len(validacion.matriz) == len(analisis.requisitos),
              f"{len(validacion.matriz)} de {len(analisis.requisitos)}")
    verificar("puntaje en rango", 0 <= validacion.puntaje_cumplimiento <= 100,
              f"puntaje {validacion.puntaje_cumplimiento}, veredicto {validacion.veredicto}")

    # 4. Priorización
    print("\n--- 4. Priorización de procesos ---")
    relevancia = await agente.priorizar_procesos(
        SolicitudRelevancia(
            procesos=[
                ProcesoResumen(
                    id="1",
                    objeto="Desarrollo de software para el sistema misional en la nube",
                    entidad="MinTIC",
                    valor=2_000_000_000,
                ),
                ProcesoResumen(
                    id="2",
                    objeto="Suministro de refrigerios para jornadas de bienestar",
                    entidad="Alcaldía",
                    valor=50_000_000,
                ),
            ],
            perfil_proveedor=PERFIL,
            maximo=5,
        )
    )
    verificar("clasifica los procesos", len(relevancia.priorizados) >= 1)
    for item in relevancia.priorizados:
        print(f"        · [{item.puntaje:>3}] {item.categoria_ti}: "
              f"{(item.objeto or '')[:60]}")
    ti = next((p for p in relevancia.priorizados if p.id == "1"), None)
    no_ti = next((p for p in relevancia.priorizados if p.id == "2"), None)
    if ti and no_ti:
        verificar("prioriza el proceso de TI sobre el que no lo es",
                  ti.puntaje > no_ti.puntaje,
                  f"TI={ti.puntaje} vs no-TI={no_ti.puntaje}")

    # 5. Chat en streaming
    print("\n--- 5. Chat en streaming ---")
    from app.schemas import MensajeChat

    fragmentos = 0
    acumulado = ""
    async for fragmento in agente.chat_stream(
        [MensajeChat(rol="user", contenido="En una frase: ¿qué es el SECOP II?")],
        None,
    ):
        fragmentos += 1
        acumulado += fragmento
    verificar("el streaming emite fragmentos", fragmentos >= 1, f"{fragmentos} fragmentos")
    verificar("el streaming produce texto", len(acumulado) > 20, acumulado[:150])

    print()
    if fallos:
        print(f"RESULTADO: {len(fallos)} prueba(s) fallida(s): {', '.join(fallos)}")
        return 1
    print("RESULTADO: el agente funciona de extremo a extremo con Gemini.")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
