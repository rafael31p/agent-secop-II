package co.agentesecop.arquitectura;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

/**
 * La regla de dependencias de SPEC-BE-01, verificada en cada compilación.
 *
 * <h2>Por qué está en verde si el código la incumple</h2>
 *
 * <p>Cada regla se envuelve en {@link FreezingArchRule}. La primera ejecución guarda las
 * infracciones existentes en {@code src/test/resources/archunit_store} y pasa; a partir de
 * ahí, una infracción <em>nueva</em> falla y una infracción <em>resuelta</em> desaparece
 * del almacén. La deuda queda registrada, contada y sin poder crecer.
 *
 * <p>Introducir la regla en rojo tendría el efecto contrario al buscado: la compilación
 * quedaría rota durante las 8–12 jornadas de la fase 2, y una compilación rota de forma
 * permanente es una que se ignora.
 *
 * <h2>Por qué los nombres de paquete son los de hoy</h2>
 *
 * <p>SPEC-BE-01 escribe las reglas contra la estructura de destino
 * ({@code domain} / {@code application} / {@code adapter}), que todavía no existe. Una
 * regla sobre un paquete inexistente no tiene nada que examinar: pasaría siempre y
 * congelaría cero infracciones, que es justo lo que no se quiere de esta fase.
 *
 * <p>Se expresan por tanto sobre los paquetes actuales, con la correspondencia:
 *
 * <pre>
 *   dominio                     →  domain       (núcleo, no debe conocer a nadie)
 *   servicio                    →  application  (casos de uso)
 *   api, ia, secop, config      →  adapter      (entrada, salida y configuración)
 * </pre>
 *
 * <p>Cuando la fase 2 mueva los paquetes, se renombran aquí y el almacén se regenera.
 */
@AnalyzeClasses(
        packages = "co.agentesecop",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class ReglaDeDependenciasTest {

    /** Paquetes que hoy hacen de adaptador: entrada HTTP, salida y configuración. */
    private static final String[] ADAPTADORES = {"..api..", "..ia..", "..secop..", "..config.."};

    /**
     * El núcleo no depende de ningún framework.
     *
     * <p>Hoy se incumple: los cuatro archivos de {@code dominio} llevan anotaciones de
     * Jackson, de Bean Validation y de OpenAPI, porque los mismos records sirven de
     * contrato HTTP y de esquema para el modelo. Fue una decisión razonable mientras las
     * dos representaciones fueron idénticas; dejó de serlo cuando apareció
     * {@code EsquemasJson}, que existe precisamente porque ya no coinciden.
     */
    @ArchTest
    static final ArchRule elDominioNoConoceFrameworks = FreezingArchRule.freeze(
            noClasses()
                    .that().resideInAPackage("..dominio..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.fasterxml..",
                            "jakarta..",
                            "io.quarkus..",
                            "io.smallrye..",
                            "org.eclipse.microprofile..",
                            "dev.langchain4j..")
                    .because("el núcleo de contratación pública debe compilar sin "
                            + "bibliotecas de serialización ni de HTTP (SPEC-BE-01 §1)"));

    /** El núcleo tampoco conoce los paquetes que lo rodean. */
    @ArchTest
    static final ArchRule elDominioNoConoceLosAdaptadores = FreezingArchRule.freeze(
            noClasses()
                    .that().resideInAPackage("..dominio..")
                    .should().dependOnClassesThat().resideInAnyPackage(ADAPTADORES)
                    .because("las dependencias apuntan hacia adentro (SPEC-BE-01 §2)"));

    /**
     * Los casos de uso no dependen de adaptadores concretos.
     *
     * <p>Hoy se incumple en {@code servicio.AgenteSecop}, que inyecta
     * {@code ia.RegistroProveedores} —una clase concreta del adaptador de salida— en lugar
     * de un puerto, y en {@code servicio.ExtractorDocumentos}, que usa PDFBox y POI
     * directamente. El punto 2.3 del plan declara esos puertos.
     */
    @ArchTest
    static final ArchRule losCasosDeUsoNoDependenDeAdaptadores = FreezingArchRule.freeze(
            noClasses()
                    .that().resideInAPackage("..servicio..")
                    .should().dependOnClassesThat().resideInAnyPackage(ADAPTADORES)
                    .because("un caso de uso depende de puertos, no de implementaciones "
                            + "(SPEC-BE-01 §3.7)"));

    /**
     * Nadie depende de la capa de entrada HTTP.
     *
     * <p>Esta sí está en verde hoy y conviene que siga así: es la que impide que un
     * servicio empiece a devolver tipos de JAX-RS.
     */
    @ArchTest
    static final ArchRule nadieDependeDeLaCapaHttp = FreezingArchRule.freeze(
            noClasses()
                    .that().resideOutsideOfPackage("..api..")
                    .should().dependOnClassesThat().resideInAPackage("..api..")
                    .because("la capa de entrada es un detalle: nada de dentro la conoce"));
}
