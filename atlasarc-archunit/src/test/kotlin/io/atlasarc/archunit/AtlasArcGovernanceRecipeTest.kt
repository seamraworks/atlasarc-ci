package io.atlasarc.archunit

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import io.atlasarc.governance.CycleGovernanceCodec
import io.atlasarc.governance.CycleGovernanceDocument
import io.atlasarc.governance.CycleGovernanceKind
import io.atlasarc.governance.CycleGovernanceRecord
import io.atlasarc.governance.GovernanceAnalysisSource
import io.atlasarc.governance.GovernanceBackend
import io.atlasarc.governance.GovernanceEncodeResult
import io.atlasarc.governance.GovernanceIdentity
import io.atlasarc.governance.GovernanceLanguage
import io.atlasarc.governance.GovernanceOwnerSide
import io.atlasarc.governance.GovernancePaths
import io.atlasarc.governance.GovernanceScope
import io.atlasarc.scope.RepositoryScopeCodec
import io.atlasarc.scope.RepositoryScopeDocument
import io.atlasarc.scope.RepositoryScopeEncodeResult
import io.atlasarc.scope.RepositoryScopeExclusion
import io.atlasarc.scope.RepositoryScopeSelector
import io.atlasarc.scope.RepositoryScopeSelectorKind
import io.atlasarc.scope.REPOSITORY_SCOPE_RELATIVE_PATH
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

/** Executable proof for the public repository-backed ArchUnit recipe. */
class AtlasArcGovernanceRecipeTest {
    @TempDir
    lateinit var temp: Path

    private val governedClasses = ClassFileImporter().importPackages(GOVERNED)
    private val allRecipeClasses = ClassFileImporter().importPackages(FIXTURES)
    private val allJavaRecipeClasses = ClassFileImporter().importPackages("$FIXTURES.governed", "$FIXTURES.ungoverned")

    @Test
    fun `a plain ArchUnit rule detects the deliberate fixture cycle`() {
        assertTrue(
            slices().matching("$GOVERNED.(*)..").should().beFreeOfCycles()
                .evaluate(governedClasses).hasViolation(),
            "the fixture must contain a real package cycle, or the governance proof is meaningless",
        )
    }

    @Test
    fun `the recipe rule passes when repository governance accepts the cycle`() {
        val repository = repositoryWithGovernance()

        assertDoesNotThrow {
            recipeRule(repository).check(governedClasses)
        }
    }

    @Test
    fun `the recipe rule still fails for a new ungoverned cycle`() {
        val repository = repositoryWithGovernance()

        assertTrue(
            recipeRule(repository).evaluate(allRecipeClasses).hasViolation(),
            "an ungoverned cycle must still fail; the repository-backed rule must not fail open",
        )
    }

    @Test
    fun `the recipe applies the same repository scope policy as standalone CI`() {
        val repository = repositoryWithGovernance()
        val scope = RepositoryScopeDocument(
            exclusions = mapOf(
                "ungoverned-catalog-fixture" to RepositoryScopeExclusion(
                    RepositoryScopeSelector(
                        RepositoryScopeSelectorKind.JVM_PACKAGE_PATTERN,
                        "$FIXTURES.ungoverned.catalog.**",
                        module = "test",
                    ),
                    "Fixture package is outside the governed architecture.",
                ),
            ),
        )
        val encoded = RepositoryScopeCodec().encode(scope) as RepositoryScopeEncodeResult.Success
        val path = repository.resolve(REPOSITORY_SCOPE_RELATIVE_PATH)
        Files.createDirectories(path.parent)
        Files.writeString(path, encoded.text)

        assertDoesNotThrow { recipeRule(repository).check(allJavaRecipeClasses) }
    }

    @Test
    fun `the recipe fails closed when repository scope is invalid`() {
        val repository = repositoryWithGovernance()
        val path = repository.resolve(REPOSITORY_SCOPE_RELATIVE_PATH)
        Files.createDirectories(path.parent)
        Files.writeString(path, "{}")

        val result = recipeRule(repository).evaluate(governedClasses)

        assertTrue(result.hasViolation())
        assertTrue(result.failureReport.details.any { it.contains("repository scope is invalid") })
    }

    @Test
    fun `the recipe supports one genuinely module-less JVM universe`() {
        val repository = repositoryWithGovernance()
        val rule = AtlasArcGovernanceRules.governedCycles()
            .fromRepository(repository)
            .forAnalysisSource("jvm:whole-project")
            .withSourceRoot(Path.of("src/test/java").toAbsolutePath())
            .build()

        assertDoesNotThrow {
            rule.check(governedClasses)
        }
    }

    @Test
    fun `Kotlin bytecode uses the same repository governance contract`() {
        val root = Files.createDirectories(temp.resolve("kotlin-repository"))
        Files.createDirectories(root.resolve(".git"))
        val document = CycleGovernanceDocument(
            records = mapOf(
                "accepted-kotlin-cycle" to CycleGovernanceRecord(
                    analysisSource = GovernanceAnalysisSource(
                        id = "jvm:whole-project",
                        backend = GovernanceBackend.JVM_BYTECODE,
                        language = GovernanceLanguage.KOTLIN,
                    ),
                    scope = GovernanceScope.PACKAGE,
                    ownerSide = GovernanceOwnerSide.SOURCE,
                    source = GovernanceIdentity("$KOTLIN_FIXTURES.left"),
                    target = GovernanceIdentity("$KOTLIN_FIXTURES.right"),
                    kind = CycleGovernanceKind.INTENTIONAL,
                    reason = "Kotlin parity fixture.",
                ),
            ),
        )
        val encoded = CycleGovernanceCodec().encode(document) as GovernanceEncodeResult.Success
        val path = GovernancePaths.documentPath(root)
        Files.createDirectories(path.parent)
        Files.writeString(path, encoded.text)
        val classes = ClassFileImporter().importPackages(KOTLIN_FIXTURES)
        val rule = AtlasArcGovernanceRules.governedCycles()
            .fromRepository(root)
            .forAnalysisSource("jvm:whole-project")
            .withSourceRoot(Path.of("src/test/kotlin").toAbsolutePath())
            .build()

        assertDoesNotThrow { rule.check(classes) }
    }

    @Test
    fun `the recipe rejects mixed named and module-less source roots`() {
        val builder = AtlasArcGovernanceRules.governedCycles()
            .withModuleSourceRoot("main", Path.of("src/main/java"))
            .withSourceRoot(Path.of("src/generated/java"))

        val exception = assertFailsWith<IllegalArgumentException> { builder.build() }

        assertTrue(exception.message.orEmpty().contains("Do not mix named and module-less"))
    }

    private fun recipeRule(repository: Path) = AtlasArcGovernanceRules.governedCycles()
        .fromRepository(repository)
        .forAnalysisSource("jvm:whole-project")
        .withModuleSourceRoot("test", Path.of("src/test/java").toAbsolutePath())
        .build()

    private fun repositoryWithGovernance(): Path {
        val root = Files.createDirectories(temp.resolve("repository"))
        Files.createDirectories(root.resolve(".git"))
        val document = CycleGovernanceDocument(
            records = mapOf(
                "accepted-billing-orders" to CycleGovernanceRecord(
                    analysisSource = GovernanceAnalysisSource(
                        id = "jvm:whole-project",
                        backend = GovernanceBackend.JVM_BYTECODE,
                        language = GovernanceLanguage.JAVA,
                    ),
                    scope = GovernanceScope.PACKAGE,
                    ownerSide = GovernanceOwnerSide.SOURCE,
                    source = GovernanceIdentity("$GOVERNED.billing"),
                    target = GovernanceIdentity("$GOVERNED.orders"),
                    kind = CycleGovernanceKind.INTENTIONAL,
                    reason = "The fixture deliberately proves the governed ArchUnit recipe.",
                ),
            ),
        )
        val encoded = CycleGovernanceCodec().encode(document) as GovernanceEncodeResult.Success
        val path = GovernancePaths.documentPath(root)
        Files.createDirectories(path.parent)
        Files.writeString(path, encoded.text)
        return root
    }

    private companion object {
        const val FIXTURES = "io.atlasarc.archunit.fixtures"
        const val GOVERNED = "$FIXTURES.governed"
        const val KOTLIN_FIXTURES = "$FIXTURES.kotlin"
    }
}
