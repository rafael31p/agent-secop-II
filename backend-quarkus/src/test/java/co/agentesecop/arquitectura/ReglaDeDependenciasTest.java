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
 * <h2>Dos juegos de reglas, a propósito</h2>
 *
 * <p>La fase 2 está en curso, así que conviven la estructura vieja y la nueva. Se vigilan
 * de forma distinta:
 *
 * <ul>
 *   <li><b>{@code domain} —la nueva— con reglas sin congelar.</b> Nace limpia y cada tipo
 *       que se traslade allí entra por esa puerta: si arrastra una anotación de framework,
 *       la compilación falla en el acto.
 *   <li><b>{@code dominio} y {@code servicio} —las viejas— con reglas congeladas.</b>
 *       Admiten la deuda que ya existía, que no puede crecer y va bajando conforme la
 *       fase 2 traslada tipos. La correspondencia con la estructura de destino es
 *       {@code dominio → domain}, {@code servicio → application} y
 *       {@code api, ia, secop, config → adapter}.
 * </ul>
 *
 * <p>Cuando no quede nada en los paquetes viejos, sus reglas y el almacén desaparecen.
 */
@AnalyzeClasses(
        packages = "co.agentesecop",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class ReglaDeDependenciasTest {

    /** Paquetes que hoy hacen de adaptador: entrada HTTP, salida y configuración. */
    private static final String[] ADAPTADORES = {"..api..", "..ia..", "..secop..", "..config.."};

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
     * El dominio nuevo nace puro y <strong>no se congela</strong>.
     *
     * <p>Es la diferencia importante respecto a las reglas de abajo: aquellas admiten la
     * deuda que ya existía, esta no admite ninguna. Cada tipo que la fase 2 traslade a
     * {@code domain} entra por esta puerta, y si trae una anotación de framework encima,
     * la compilación falla en el acto en lugar de sumarse a una lista.
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
                    .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORKS)
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
