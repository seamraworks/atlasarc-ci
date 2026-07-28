package io.atlasarc.junit;

import java.nio.file.Path;

/** Compile-time mirror of the Java recipe published at /docs/recipe-junit-build. */
final class DocumentedJUnitRecipeExample {
    void repositoryCycleGovernance() {
        AtlasArcGovernanceAssertions.assertGovernance(
            Path.of(".atlasarc/evaluator.json")
        );
    }
}
