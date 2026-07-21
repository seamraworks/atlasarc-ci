package demo.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.atlasarc.archunit.AtlasArcGovernanceRules;

import java.nio.file.Path;

@AnalyzeClasses(packages = "demo")
final class CycleGovernanceTest {
    @ArchTest
    static final ArchRule repository_cycle_governance =
        AtlasArcGovernanceRules.governedCycles()
            .fromRepository(Path.of("."))
            .forAnalysisSource("jvm:whole-project")
            .withSourceRoot(Path.of("src/main/java"))
            .withClassRoot(Path.of("target/classes"))
            .build();
}
