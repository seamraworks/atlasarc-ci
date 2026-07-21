package io.atlasarc.governance

import io.atlasarc.evaluation.GovernanceEvaluationInput
import io.atlasarc.evaluation.GovernanceEvaluationIssue
import io.atlasarc.evaluation.GovernanceEvaluationVerdict
import io.atlasarc.evaluation.CycleGovernanceEvaluator
import io.atlasarc.scope.RepositoryScopeDocument
import io.atlasarc.scope.RepositoryScopeEvaluationContext
import io.atlasarc.scope.RepositoryScopeExclusion
import io.atlasarc.scope.RepositoryScopeSelector
import io.atlasarc.scope.RepositoryScopeSelectorKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CycleDebtBaselinePlannerTest {
    private val planner = CycleDebtBaselinePlanner()

    @Test
    fun `creates exact debt only for a minimum cycle-breaking edge set and proves a clean result`() {
        val proposal = assertIs<CycleDebtBaselineResult.Proposed>(
            planner.propose(CycleGovernanceDocument(), listOf(cycle())),
        ).proposal

        assertEquals(1, proposal.problemGroupCount)
        assertEquals(3, proposal.problemReferenceCount)
        assertEquals(1, proposal.selectedEdgeCount)
        assertEquals(1, proposal.addedRecords.size)
        assertEquals(0, proposal.alreadyGovernedReferenceCount)
        assertEquals(GovernanceEvaluationVerdict.CLEAN, proposal.resultingEvaluation.verdict)
        proposal.addedRecords.values.forEach { record ->
            assertEquals(GovernanceScope.REFERENCE, record.scope)
            assertEquals(GovernanceOwnerSide.SOURCE, record.ownerSide)
            assertEquals(CycleGovernanceKind.DEBT, record.kind)
            assertEquals(1, record.referenceIds.size)
            assertEquals(DEFAULT_CYCLE_DEBT_BASELINE_REASON, record.reason)
        }
    }

    @Test
    fun `preserves module identity language kind ticket and existing decisions`() {
        val existing = CycleGovernanceRecord(
            analysisSource = GovernanceAnalysisSource(SOURCE, GovernanceBackend.JVM_BYTECODE, GovernanceLanguage.KOTLIN),
            scope = GovernanceScope.REFERENCE,
            ownerSide = GovernanceOwnerSide.SOURCE,
            source = GovernanceIdentity(
                "a",
                type = "a.A",
                sourceFile = "src/main/kotlin/a/A.kt",
                module = "billing",
            ),
            target = GovernanceIdentity(
                "b",
                type = "b.B",
                sourceFile = "src/main/kotlin/b/B.kt",
                module = "billing",
            ),
            dependencyKind = GovernanceDependencyKind.METHOD_CALL,
            referenceIds = setOf("ref-a-b-one"),
            kind = CycleGovernanceKind.INTENTIONAL,
            reason = "Reviewed decision.",
        )
        val proposal = assertIs<CycleDebtBaselineResult.Proposed>(
            planner.propose(
                CycleGovernanceDocument(records = mapOf("existing" to existing)),
                listOf(cycle(module = "billing", sourceLanguage = GovernanceLanguage.KOTLIN)),
                CycleDebtBaselineOptions(reason = "Existing debt at adoption.", ticket = "ARCH-42"),
            ),
        ).proposal

        assertEquals(existing, proposal.proposedDocument.records.getValue("existing"))
        assertEquals(1, proposal.alreadyGovernedReferenceCount)
        assertEquals(1, proposal.selectedEdgeCount)
        assertEquals(1, proposal.addedRecords.size)
        proposal.addedRecords.values.forEach { record ->
            assertEquals(GovernanceLanguage.KOTLIN, record.analysisSource.language)
            assertEquals("billing", record.source.module)
            assertEquals("billing", record.target.module)
            assertEquals("Existing debt at adoption.", record.reason)
            assertEquals("ARCH-42", record.ticket)
        }
    }

    @Test
    fun `rerunning an unchanged baseline is a semantic no-op`() {
        val first = assertIs<CycleDebtBaselineResult.Proposed>(
            planner.propose(CycleGovernanceDocument(), listOf(cycle())),
        ).proposal
        val second = assertIs<CycleDebtBaselineResult.Proposed>(
            planner.propose(first.proposedDocument, listOf(cycle())),
        ).proposal

        assertTrue(second.addedRecords.isEmpty())
        assertEquals(first.proposedDocument, second.proposedDocument)
        assertEquals(GovernanceEvaluationVerdict.CLEAN, second.startingEvaluation.verdict)
    }

    @Test
    fun `applies repository scope before selecting baseline edges and reports its impact`() {
        val scope = RepositoryScopeEvaluationContext(
            document = RepositoryScopeDocument(
                exclusions = mapOf(
                    "outside-a" to RepositoryScopeExclusion(
                        selector = RepositoryScopeSelector(
                            kind = RepositoryScopeSelectorKind.JVM_PACKAGE_PATTERN,
                            pattern = "a",
                        ),
                        reason = "Package A is outside the governed architecture.",
                    ),
                ),
            ),
            exists = true,
            revision = "scope-revision",
        )

        val proposal = assertIs<CycleDebtBaselineResult.Proposed>(
            planner.propose(
                document = CycleGovernanceDocument(),
                inputs = listOf(cycle()),
                repositoryScope = scope,
            ),
        ).proposal

        assertEquals(GovernanceEvaluationVerdict.CLEAN, proposal.startingEvaluation.verdict)
        assertEquals(0, proposal.problemGroupCount)
        assertEquals(0, proposal.selectedEdgeCount)
        assertTrue(proposal.addedRecords.isEmpty())
        assertEquals("scope-revision", proposal.startingEvaluation.repositoryScope.revision)
        assertEquals(1, proposal.startingEvaluation.repositoryScope.summary.appliedRuleCount)
        assertEquals(1, proposal.startingEvaluation.repositoryScope.summary.excludedArchitectureUnitCount)
        assertEquals(3, proposal.startingEvaluation.repositoryScope.summary.excludedReferenceCount)
    }

    @Test
    fun `uses source folder identities for TypeScript without broadening the selectors`() {
        val proposal = assertIs<CycleDebtBaselineResult.Proposed>(
            planner.propose(CycleGovernanceDocument(), listOf(cycle(backend = GovernanceBackend.TYPESCRIPT_ARTIFACT))),
        ).proposal

        assertEquals(1, proposal.selectedEdgeCount)
        assertEquals(1, proposal.addedRecords.size)
        proposal.addedRecords.values.forEach { record ->
            assertEquals(GovernanceBackend.TYPESCRIPT_ARTIFACT, record.analysisSource.backend)
            assertEquals(GovernanceLanguage.TYPESCRIPT, record.analysisSource.language)
            assertEquals(GovernanceScope.REFERENCE, record.scope)
            assertTrue(record.source.sourceFile?.endsWith(".ts") == true)
        }
    }

    @Test
    fun `new references are not covered and a genuinely new cycle still fails`() {
        val input = cycle()
        val proposal = assertIs<CycleDebtBaselineResult.Proposed>(
            planner.propose(CycleGovernanceDocument(), listOf(input)),
        ).proposal
        val existingEdgeReference = input.evidence.references.first().copy(id = "ref-a-b-new")
        val newCycleReferences = listOf(
            input.evidence.references.first().copy(
                id = "ref-c-d",
                source = GovernanceIdentity("c", type = "c.C", sourceFile = "src/main/java/c/C.java"),
                target = GovernanceIdentity("d", type = "d.D", sourceFile = "src/main/java/d/D.java"),
            ),
            input.evidence.references.first().copy(
                id = "ref-d-c",
                source = GovernanceIdentity("d", type = "d.D", sourceFile = "src/main/java/d/D.java"),
                target = GovernanceIdentity("c", type = "c.C", sourceFile = "src/main/java/c/C.java"),
            ),
        )
        val expandedReferences = input.evidence.references + existingEdgeReference + newCycleReferences
        val expanded = input.copy(
            evidence = input.evidence.copy(
                nodes = expandedReferences.flatMap { reference ->
                    listOf(
                        GovernanceEvidenceNode(SOURCE, reference.backend, reference.sourceLanguage, reference.source),
                        GovernanceEvidenceNode(SOURCE, reference.backend, reference.targetLanguage, reference.target),
                    )
                }.distinct(),
                references = expandedReferences,
            ),
        )

        val evaluation = CycleGovernanceEvaluator().evaluate(
            proposal.proposedDocument,
            listOf(expanded),
            "test",
        )

        assertTrue(evaluation.records.none { "ref-a-b-new" in it.matchedReferenceIds })
        assertEquals(GovernanceEvaluationVerdict.PROBLEMS, evaluation.verdict)
        assertEquals(setOf("c", "d"), evaluation.problemGroups.single().members.mapTo(sortedSetOf()) { it.architectureUnit })
    }

    @Test
    fun `removing one old dependency resolves only its exact baseline record`() {
        val input = cycle()
        val proposal = assertIs<CycleDebtBaselineResult.Proposed>(
            planner.propose(CycleGovernanceDocument(), listOf(input)),
        ).proposal
        val removedReferenceId = proposal.addedRecords.values.single().referenceIds.single()
        val remainingReferences = input.evidence.references.filterNot { it.id == removedReferenceId }
        val reduced = input.copy(
            evidence = input.evidence.copy(
                nodes = remainingReferences.flatMap { reference ->
                    listOf(
                        GovernanceEvidenceNode(SOURCE, reference.backend, reference.sourceLanguage, reference.source),
                        GovernanceEvidenceNode(SOURCE, reference.backend, reference.targetLanguage, reference.target),
                    )
                }.distinct(),
                references = remainingReferences,
            ),
        )

        val evaluation = CycleGovernanceEvaluator().evaluate(
            proposal.proposedDocument,
            listOf(reduced),
            "test",
        )
        val removedRecordId = proposal.addedRecords.entries.single { removedReferenceId in it.value.referenceIds }.key

        assertEquals(GovernanceRecordStatus.RESOLVED, evaluation.records.single { it.recordId == removedRecordId }.status)
        assertTrue(evaluation.records.filterNot { it.recordId == removedRecordId }.all { it.status == GovernanceRecordStatus.ACTIVE })
        assertEquals(GovernanceEvaluationVerdict.CLEAN, evaluation.verdict)
    }

    @Test
    fun `refuses stale partial invalid and unattributed evidence without proposing a document`() {
        val stale = cycle().let { input ->
            input.copy(evidence = input.evidence.copy(sources = input.evidence.sources.map { it.copy(fresh = false) }))
        }
        val partial = cycle().let { input ->
            input.copy(evidence = input.evidence.copy(sources = input.evidence.sources.map { it.copy(repositoryComplete = false) }))
        }
        val unattributed = cycle().copy(
            issues = listOf(
                GovernanceEvaluationIssue(
                    code = "jvm-module-attribution",
                    message = "Module ownership is ambiguous.",
                    severity = GovernanceIssueSeverity.ERROR,
                    analysisSourceId = SOURCE,
                ),
            ),
        )
        val invalidDocument = CycleGovernanceDocument(
            records = mapOf("x" to packageRecord("a", "missing")),
        )

        listOf(
            planner.propose(CycleGovernanceDocument(), listOf(stale)),
            planner.propose(CycleGovernanceDocument(), listOf(partial)),
            planner.propose(CycleGovernanceDocument(), listOf(unattributed)),
            planner.propose(invalidDocument, listOf(cycle())),
        ).forEach { result ->
            assertIs<CycleDebtBaselineResult.Refused>(result)
            assertTrue(result.diagnostics.isNotEmpty())
        }
    }

    @Test
    fun `refuses capacity overflow instead of compacting into broad selectors`() {
        val result = CycleDebtBaselinePlanner(maxRecords = 0)
            .propose(CycleGovernanceDocument(), listOf(cycle()))

        val refused = assertIs<CycleDebtBaselineResult.Refused>(result)
        assertEquals("baseline-record-limit-exceeded", refused.diagnostics.single().code)
    }

    private fun cycle(
        module: String? = null,
        sourceLanguage: GovernanceLanguage = GovernanceLanguage.JAVA,
        backend: GovernanceBackend = GovernanceBackend.JVM_BYTECODE,
    ): GovernanceEvaluationInput {
        val language = if (backend == GovernanceBackend.TYPESCRIPT_ARTIFACT) GovernanceLanguage.TYPESCRIPT else sourceLanguage
        fun identity(unit: String, type: String): GovernanceIdentity = if (backend == GovernanceBackend.JVM_BYTECODE) {
            GovernanceIdentity(unit, type = "$unit.$type", sourceFile = "src/main/${language.name.lowercase()}/$unit/$type.${if (language == GovernanceLanguage.KOTLIN) "kt" else "java"}", module = module)
        } else {
            GovernanceIdentity("src/$unit", sourceFile = "src/$unit/$type.ts")
        }
        fun reference(id: String, from: String, to: String, sourceType: String, targetType: String) =
            GovernanceEvidenceReference(
                id = id,
                analysisSourceId = SOURCE,
                backend = backend,
                sourceLanguage = language,
                targetLanguage = language,
                source = identity(from, sourceType),
                target = identity(to, targetType),
                dependencyKind = if (backend == GovernanceBackend.JVM_BYTECODE) {
                    GovernanceDependencyKind.METHOD_CALL
                } else {
                    GovernanceDependencyKind.RUNTIME_IMPORT
                },
            )
        val references = listOf(
            reference("ref-a-b-one", "a", "b", "A", "B"),
            reference("ref-a-b-two", "a", "b", "A2", "B"),
            reference("ref-b-a", "b", "a", "B", "A"),
        )
        return GovernanceEvaluationInput(
            GovernanceEvidenceSnapshot(
                sources = listOf(
                    GovernanceEvidenceSource(
                        id = SOURCE,
                        backend = backend,
                        languages = setOf(language),
                        supportedScopes = setOf(GovernanceScope.REFERENCE),
                        repositoryComplete = true,
                    ),
                ),
                nodes = references.flatMap { reference ->
                    listOf(
                        GovernanceEvidenceNode(SOURCE, backend, language, reference.source),
                        GovernanceEvidenceNode(SOURCE, backend, language, reference.target),
                    )
                }.distinct(),
                references = references,
            ),
        )
    }

    private fun packageRecord(from: String, to: String) = CycleGovernanceRecord(
        analysisSource = GovernanceAnalysisSource(SOURCE, GovernanceBackend.JVM_BYTECODE, GovernanceLanguage.JAVA),
        scope = GovernanceScope.PACKAGE,
        ownerSide = GovernanceOwnerSide.SOURCE,
        source = GovernanceIdentity(from),
        target = GovernanceIdentity(to),
        kind = CycleGovernanceKind.INTENTIONAL,
        reason = "Reviewed decision.",
    )

    private companion object {
        const val SOURCE = "jvm:whole-project"
    }
}
