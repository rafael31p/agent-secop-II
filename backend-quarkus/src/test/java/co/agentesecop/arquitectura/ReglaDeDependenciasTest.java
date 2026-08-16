package co.agentesecop.arquitectura;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * La regla de dependencias de SPEC-BE-01, verificada en cada compilación.
 *
 * <h2>Ya no hay nada congelado</h2>
 *
 * <p>Estas reglas nacieron envueltas en {@code FreezingArchRule} con 482 infracciones
 * registradas: la deuda quedaba anotada y sin poder crecer mientras se pagaba. La fase 2
 * la pagó entera, así que el almacén desapareció y las reglas exigen cumplimiento pleno.
 * A partir de aquí, una violación nueva rompe la compilación en el acto.
 *
 * <p>Las tres capas y la única dirección permitida:
 *
 * <pre>
 *   adapter  ──▶  application  ──▶  domain
 * </pre>
 *
 * <p>{@code domain} no conoce a nadie —ni siquiera a Jackson—; {@code application} conoce
 * el dominio y sus propios puertos; {@code adapter} conoce a los dos. Nada conoce a
 * {@code adapter}.
 *
 * <p>Quedan fuera del árbol {@code adapter}, por ahora, los paquetes {@code ia},
 * {@code secop} y {@code config}: son adaptadores de hecho —hablan con sistemas externos
 * o con la configuración— pero todavía no se han recolocado. Las reglas los tratan como
 * tales, que es lo que importa para la dirección de las dependencias.
 */
@AnalyzeClasses(
        packages = "co.agentesecop",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class ReglaDeDependenciasTest {

    /** Paquetes que hacen de adaptador, estén o no ya bajo {@code adapter}. */
    private static final String[] ADAPTADORES = {
        "..adapter..", "..ia..", "..secop..", "..config.."
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
     * El dominio compila sin bibliotecas de serialización ni de HTTP.
     *
     * <p>Es la regla que costó más pagar: 459 infracciones, porque los mismos records
     * servían de contrato HTTP, de modelo y de esquema para el modelo de lenguaje. Se
     * saldó separando el contrato en {@code adapter.in.rest.dto} y dejando el núcleo
     * desnudo.
     */
    @ArchTest
    static final ArchRule elDominioNoConoceFrameworks = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORKS)
            .because("el núcleo de contratación pública no depende de cómo se serializa "
                    + "ni de cómo se transporta (SPEC-BE-01 §3.3)");

    /** El dominio tampoco conoce lo que lo rodea. */
    @ArchTest
    static final ArchRule elDominioNoConoceANadie = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..adapter..", "..application..", "..ia..", "..secop..", "..config..")
            .because("las dependencias apuntan hacia adentro (SPEC-BE-01 §2)");

    /**
     * Los casos de uso dependen de puertos, no de implementaciones.
     *
     * <p>La pagó el punto 2.6: mientras {@code AgenteSecop} recibía los DTO de solicitud
     * HTTP e inyectaba el registro de proveedores, esta regla acumulaba 78 entradas.
     */
    @ArchTest
    static final ArchRule laAplicacionNoConoceAdaptadores = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(ADAPTADORES)
            .because("un caso de uso depende de puertos, no de quién los implementa "
                    + "(SPEC-BE-01 §3.7)");

    /**
     * La capa REST usa los puertos de entrada, nunca las implementaciones.
     *
     * <p>Es la que más se olvida y la que más valor tiene: sin ella, un recurso vuelve a
     * inyectar la clase concreta y la interfaz queda de adorno.
     */
    @ArchTest
    static final ArchRule laCapaRestUsaPuertos = noClasses()
            .that().resideInAPackage("..adapter.in.rest..")
            .should().dependOnClassesThat().resideInAPackage("..application.service..")
            .because("los recursos hablan con port/in, no con quien lo implementa");

    /** Y la misma dirección, expresada como capas, por si alguna se escapa. */
    @ArchTest
    static final ArchRule lasCapasDelHexagono = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Dominio").definedBy("..domain..")
            .layer("Aplicacion").definedBy("..application..")
            .layer("Adaptadores").definedBy("..adapter..")
            .whereLayer("Adaptadores").mayNotBeAccessedByAnyLayer()
            .whereLayer("Aplicacion").mayOnlyBeAccessedByLayers("Adaptadores");
}
