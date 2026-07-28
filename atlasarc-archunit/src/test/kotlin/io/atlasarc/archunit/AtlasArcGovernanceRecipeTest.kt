package io.atlasarc.archunit

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import io.atlasarc.evaluation.CycleGovernanceEvaluator
import io.atlasarc.evaluation.GovernanceEvaluationVerdict
import io.atlasarc.governance.CycleGovernanceCodec
import io.atlasarc.governance.CycleGovernanceDocument
import io.atlasarc.governance.CycleGovernanceKind
import io.atlasarc.governance.CycleGovernanceRecord
import io.atlasarc.governance.GovernanceAnalysisSource
import io.atlasarc.governance.GovernanceBackend
import io.atlasarc.governance.GovernanceDependencyKind
import io.atlasarc.governance.GovernanceEncodeResult
import io.atlasarc.governance.GovernanceIdentity
import io.atlasarc.governance.GovernanceLanguage
import io.atlasarc.governance.GovernanceOwnerSide
import io.atlasarc.governance.GovernancePaths
import io.atlasarc.governance.GovernanceRecordStatus
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
import kotlin.test.assertEquals
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
    fun `same-line constructor and method remain two observed reference tuples`() {
        val repository = repositoryWithGovernance()
        val evidence = ArchUnitGovernanceEvidence().build(
            classes = governedClasses,
            sourceRoots = listOf(JvmEvidenceRoot(Path.of("src/test/java").toAbsolutePath(), "test")),
            analysisSourceId = "jvm:whole-project",
            repositoryRoot = repository,
        ).evidence

        val tuples = evidence.references.asSequence()
            .filter { it.source.type == "$GOVERNED.orders.OrderService" }
            .filter { it.source.member?.name == "roundTrip" }
            .filter { it.target.type == "$GOVERNED.billing.Invoice" }
            .map { it.target.member?.name to it.dependencyKind }
            .toSet()

        assertEquals(
            setOf(
                "<init>" to GovernanceDependencyKind.CONSTRUCTOR_CALL,
                "orders" to GovernanceDependencyKind.METHOD_CALL,
            ),
            tuples,
        )
    }

    @Test
    fun `the ArchUnit recipe ignores valid TypeScript records while evaluating JVM governance`() {
        val document = governanceDocument(
            "accepted-typescript-cycle" to typeScriptRecord(),
        )
        val repository = repositoryWithGovernance(document)
        val evidence = ArchUnitGovernanceEvidence().build(
            classes = governedClasses,
            sourceRoots = listOf(JvmEvidenceRoot(Path.of("src/test/java").toAbsolutePath(), "test")),
            analysisSourceId = "jvm:whole-project",
            repositoryRoot = repository,
        )

        val evaluation = CycleGovernanceEvaluator().evaluate(document, listOf(evidence), "test")

        assertEquals(GovernanceEvaluationVerdict.CLEAN, evaluation.verdict)
        assertEquals(2, evaluation.summary.recordCount)
        assertEquals(1, evaluation.summary.activeRecordCount)
        assertEquals(0, evaluation.summary.invalidRecordCount)
        assertEquals(
            GovernanceRecordStatus.NOT_IN_ANALYSIS,
            evaluation.records.single { it.recordId == "accepted-typescript-cycle" }.status,
        )
        assertDoesNotThrow { recipeRule(repository).check(governedClasses) }
    }

    @Test
    fun `the recipe rule still fails for a new ungoverned cycle`() {
        val repository = repositoryWithGovernance(
            governanceDocument("accepted-typescript-cycle" to typeScriptRecord()),
        )

        assertTrue(
            recipeRule(repository).evaluate(allRecipeClasses).hasViolation(),
            "an ungoverned cycle must still fail; the repository-backed rule must not fail open",
        )
    }

    @Test
    fun `the recipe still fails closed when the shared governance document is malformed`() {
        val repository = repositoryWithGovernance()
        Files.writeString(GovernancePaths.documentPath(repository), "{}")

        val result = recipeRule(repository).evaluate(governedClasses)

        assertTrue(result.hasViolation())
        assertTrue(result.failureReport.details.any { it.contains("governance is invalid") })
    }

    @Test
    fun `the recipe still rejects invalid JVM records in a mixed backend document`() {
        val repository = repositoryWithGovernance(
            governanceDocument(
                "accepted-typescript-cycle" to typeScriptRecord(),
                "missing-jvm-cycle" to CycleGovernanceRecord(
                    analysisSource = GovernanceAnalysisSource(
                        id = "jvm:whole-project",
                        backend = GovernanceBackend.JVM_BYTECODE,
                        language = GovernanceLanguage.JAVA,
                    ),
                    scope = GovernanceScope.PACKAGE,
                    ownerSide = GovernanceOwnerSide.SOURCE,
                    source = GovernanceIdentity("missing.source"),
                    target = GovernanceIdentity("missing.target"),
                    kind = CycleGovernanceKind.INTENTIONAL,
                    reason = "A covered-backend record must still be validated.",
                ),
            ),
        )

        val result = recipeRule(repository).evaluate(governedClasses)

        assertTrue(result.hasViolation())
        assertTrue(result.failureReport.details.any { it.contains("source identity no longer exists") })
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

    private fun repositoryWithGovernance(document: CycleGovernanceDocument = governanceDocument()): Path {
        val root = Files.createDirectories(temp.resolve("repository"))
        Files.createDirectories(root.resolve(".git"))
        val encoded = CycleGovernanceCodec().encode(document) as GovernanceEncodeResult.Success
        val path = GovernancePaths.documentPath(root)
        Files.createDirectories(path.parent)
        Files.writeString(path, encoded.text)
        return root
    }

    private fun governanceDocument(
        vararg additionalRecords: Pair<String, CycleGovernanceRecord>,
    ): CycleGovernanceDocument = CycleGovernanceDocument(
        records = buildMap {
            put(
                "accepted-billing-orders",
                CycleGovernanceRecord(
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
            )
            putAll(additionalRecords)
        },
    )

    private fun typeScriptRecord(): CycleGovernanceRecord = CycleGovernanceRecord(
        analysisSource = GovernanceAnalysisSource(
            id = "ts:manifest:web",
            backend = GovernanceBackend.TYPESCRIPT_ARTIFACT,
            language = GovernanceLanguage.TYPESCRIPT,
        ),
        scope = GovernanceScope.SOURCE_FOLDER,
        ownerSide = GovernanceOwnerSide.SOURCE,
        source = GovernanceIdentity("apps/web/src/billing"),
        target = GovernanceIdentity("apps/web/src/orders"),
        kind = CycleGovernanceKind.INTENTIONAL,
        reason = "A valid TypeScript record belongs to the standalone evaluator, not ArchUnit.",
    )

    private companion object {
        const val FIXTURES = "io.atlasarc.archunit.fixtures"
        const val GOVERNED = "$FIXTURES.governed"
        const val KOTLIN_FIXTURES = "$FIXTURES.kotlin"
    }
}
