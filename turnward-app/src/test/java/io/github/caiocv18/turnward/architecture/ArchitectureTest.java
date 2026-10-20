package io.github.caiocv18.turnward.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Enforces ADR-0001. Maven module boundaries already stop most illegal imports at compile time;
 * these rules catch what they cannot: anything beyond the JDK slipped into the core, and package
 * rules inside a module.
 *
 * <p>Every module ships a {@code package-info}, so no rule runs on an empty set. The presence
 * test below keeps it that way: if a module drops off this test classpath, the build fails
 * instead of the rules passing silently.
 */
class ArchitectureTest {

    private static final String BASE = "io.github.caiocv18.turnward";

    private static final String JDK = "java..";
    private static final String DOMAIN = "..turnward.domain..";
    private static final String APPLICATION = "..turnward.application..";
    private static final String PORTS = "..turnward.application.port..";
    private static final String ADAPTERS = "..turnward.adapter..";

    private static JavaClasses classes;

    @BeforeAll
    static void importProductionClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    @Test
    void everyModuleIsOnTheClasspath() {
        List<String> packages = List.of(
                "domain", "application", "application.port",
                "adapter.web", "adapter.persistence", "adapter.kafka", "adapter.llm",
                "adapter.mcpclient", "adapter.mcpserver", "adapter.redis");

        assertThat(packages)
                .allSatisfy(name -> assertThat(classes.containPackage(BASE + "." + name))
                        .as("package %s.%s imported", BASE, name)
                        .isTrue());
    }

    /** Allow list, not ban list: a new framework cannot reach the domain by being forgotten. */
    @Test
    void domainDependsOnlyOnTheJdk() {
        classes().that().resideInAPackage(DOMAIN)
                .should().onlyDependOnClassesThat().resideInAnyPackage(JDK, DOMAIN)
                .check(classes);
    }

    @Test
    void applicationDependsOnlyOnTheDomainAndTheJdk() {
        classes().that().resideInAPackage(APPLICATION)
                .should().onlyDependOnClassesThat().resideInAnyPackage(JDK, DOMAIN, APPLICATION)
                .check(classes);
    }

    /** Implied by the allow list above; kept so the ADR rule reads as its own failure message. */
    @Test
    void applicationDoesNotDependOnAdapters() {
        noClasses().that().resideInAPackage(APPLICATION)
                .should().dependOnClassesThat().resideInAPackage(ADAPTERS)
                .check(classes);
    }

    @Test
    void adaptersDoNotDependOnEachOther() {
        slices().matching("..turnward.adapter.(*)..")
                .should().notDependOnEachOther()
                .check(classes);
    }

    @Test
    void topLevelPackagesAreFreeOfCycles() {
        slices().matching("..turnward.(*)..")
                .should().beFreeOfCycles()
                .check(classes);
    }

    /** Subpackages of the core appear with the domain model; until then these slices are empty. */
    @Test
    void corePackagesAreFreeOfCycles() {
        slices().matching("..turnward.domain.(**)")
                .should().beFreeOfCycles()
                .allowEmptyShould(true)
                .check(classes);
        slices().matching("..turnward.application.(**)")
                .should().beFreeOfCycles()
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void portsAreInterfaces() {
        classes().that().resideInAPackage(PORTS)
                .should().beInterfaces()
                .check(classes);
    }
}
