package demo.architecture;

import io.atlasarc.junit.AtlasArcGovernanceAssertions;
import org.junit.jupiter.api.Test;

final class ConfiguredEvaluatorTest {
    @Test
    void repositoryCycleGovernance() {
        AtlasArcGovernanceAssertions.assertGovernance();
    }
}
