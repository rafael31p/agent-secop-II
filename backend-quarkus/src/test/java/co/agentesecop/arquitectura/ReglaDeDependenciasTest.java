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
 * <h2>Qué queda congelado</h2>
 *
 * <p>Ya solo una regla: {@code servicio → adaptadores}, con 78 entradas.
 * {@code AgenteSecop} recibe los DTO de solicitud HTTP como parámetros e inyecta clases
 * concretas del adaptador de salida. Lo paga el punto 2.6 del plan, cuando los casos de
 * uso reciban comandos y dependan de puertos.
 *
 * <p>Las reglas del dominio ya <strong>no</strong> están congeladas: no admiten ni una
 * infracción. Es lo que hace que el trabajo hecho no se pueda deshacer por descuido.
 */
@AnalyzeClasses(
        packages = "co.agentesecop",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class ReglaDeDependenciasTest {

    /**
     * Paquetes que hacen de adaptador: entrada HTTP, salida y configuración.
     *
     * <p>Incluye {@code ..adapter..}, la estructura nueva, además de los paquetes viejos.
     * Añadirlo destapó una dependencia que ya existía y no se veía: {@code AgenteSecop}
     * recibe los records de solicitud HTTP como parámetros, y mientras esos records
     * vivieron en el paquete {@code dominio} la regla no tenía nada que señalar. Moverlos
     * a su sitio no creó la infracción; la hizo visible.
     */
    private static final String[] ADAPTADORES = {
        "..adapter..", "..api..", "..ia..", "..secop..", "..config.."
    };

    /** Bibliotecas que el núcleo no debe conocer. */
    private static final String[] FRAMEWORKS = {
        "com.fasterxml..",
        "jakarta..",
        "io.quarkus..",
        "io.smallrye..",
        "org.eclipse.microprofile..",
        "dev.langchain4j.."
    };

    /**
     * El dominio es puro, y esta regla <strong>no está congelada</strong>: no admite ni
     * una infracción.
     *
     * <p>Estuvo congelada mientras existió el paquete {@code dominio}, con 459 entradas.
     * Se pagó entera al trasladar los tipos a {@code domain} sin anotaciones de framework
     * y separar el contrato HTTP en {@code adapter.in.rest.dto}.
     */
    @ArchTest
    static final ArchRule elDominioNuevoEsPuro = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORKS)
            .because("lo que se mueve a domain/ ya no puede arrastrar frameworks "
                    + "(SPEC-BE-01 §3.3)");

    /** Y tampoco conoce lo que lo rodea, en ninguna de sus formas. */
    @ArchTest
    static final ArchRule elDominioNuevoNoConoceElResto = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..adapter..", "..application..", "..dominio..",
                    "..api..", "..ia..", "..secop..", "..servicio..", "..config..")
            .because("las dependencias apuntan hacia adentro (SPEC-BE-01 §2)");

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
