package io.atlasarc.archunit;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchIgnore;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.nio.file.Path;

/** Compile-time mirror of the Java recipe published at /docs/recipe-archunit-build. */
@AnalyzeClasses(packages = "io.atlasarc.archunit.fixtures.governed")
@ArchIgnore(reason = "Compile-time mirror; AtlasArcGovernanceRecipeTest runs the bracketed proof against a temporary repository.")
final class DocumentedRecipeExample {

    @ArchTest
    static final ArchRule cyclesAcceptedByAtlasArc =
        AtlasArcGovernanceRules.governedCycles()
            .fromRepository(Path.of("."))
            .forAnalysisSource("jvm:whole-project")
            .withModuleSourceRoot("main", Path.of("src/main/java"))
            .build();
}
