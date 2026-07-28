package io.atlasarc.governance

import io.atlasarc.evaluation.GovernanceEvaluationInput
import io.atlasarc.scope.RepositoryScopeDocument
import io.atlasarc.scope.RepositoryScopeEvaluationContext
import io.atlasarc.scope.RepositoryScopeExclusion
import io.atlasarc.scope.RepositoryScopeSelector
import io.atlasarc.scope.RepositoryScopeSelectorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ResolvedBaselineCleanupPlannerTest {
    private val planner = ResolvedBaselineCleanupPlanner()

    @Test
    fun `removes only resolved baseline-managed exact debt`() {
        val removedReference = reference("ref-removed", "removed", "gone")
        val activeReference = reference("ref-active", "active", "target")
        val manualReference = reference("ref-manual", "manual", "retired")
        val removed = baselineRecord(removedReference)
        val active = baselineRecord(activeReference)
        val manual = baselineRecord(manualReference).copy(reason = "Independently reviewed debt.")
        val broad = removed.copy(
            scope = GovernanceScope.PACKAGE,
            referenceIds = emptySet(),
            kind = CycleGovernanceKind.INTENTIONAL,
        )
        val document = CycleGovernanceDocument(
            records = mapOf(
                baselineId(removedReference) to removed,
                baselineId(activeReference) to active,
                "manual-exact-debt" to manual,
                "broad-intentional" to broad,
            ),
        )
        val proposal = proposed(
            document,
            completeInput(listOf(activeReference)),
        )

        assertEquals(listOf(baselineId(removedReference)), proposal.eligibleRecordIds)
        assertFalse(baselineId(removedReference) in proposal.proposedDocument.records)
        assertEquals(
            listOf(baselineId(activeReference)),
            proposal.retainedRecordIds.getValue(ResolvedBaselineRetentionReason.ACTIVE),
        )
        assertEquals(
            listOf("manual-exact-debt"),
            proposal.retainedRecordIds.getValue(ResolvedBaselineRetentionReason.NON_BASELINE),
        )
        assertEquals(
            listOf("broad-intentional"),
            proposal.retainedRecordIds.getValue(ResolvedBaselineRetentionReason.NOT_EXACT_SINGLE_REFERENCE_DEBT),
        )
    }

    @Test
    fun `retains baseline records outside source scope or trustworthy evidence`() {
        val ordersReference = reference("ref-orders", "orders.left", "orders.right", module = "orders")
        val recordId = baselineId(ordersReference)
        val document = CycleGovernanceDocument(records = mapOf(recordId to baselineRecord(ordersReference)))
        val partialSource = GovernanceEvidenceSource(
            id = "jvm:module:billing",
            backend = GovernanceBackend.JVM_BYTECODE,
            languages = setOf(GovernanceLanguage.JAVA),
            supportedScopes = setOf(GovernanceScope.REFERENCE),
            repositoryComplete = false,
            includedJvmModules = setOf("billing"),
        )
        val outside = GovernanceEvaluationInput(
            GovernanceEvidenceSnapshot(
                sources = listOf(partialSource),
                nodes = emptyList(),
                references = emptyList(),
                evaluationComplete = false,
            ),
        )
        val stale = completeInput(emptyList()).let { input ->
            input.copy(evidence = input.evidence.copy(sources = input.evidence.sources.map { it.copy(fresh = false) }))
        }

        val outsideProposal = proposed(document, outside)
        val staleProposal = proposed(document, stale)

        assertTrue(outsideProposal.eligibleRecordIds.isEmpty())
        assertEquals(
            listOf(recordId),
            outsideProposal.retainedRecordIds.getValue(ResolvedBaselineRetentionReason.OUTSIDE_ANALYSIS_SOURCE),
        )
        assertTrue(staleProposal.eligibleRecordIds.isEmpty())
        assertEquals(
            listOf(recordId),
            staleProposal.retainedRecordIds.getValue(ResolvedBaselineRetentionReason.INSUFFICIENT_EVIDENCE),
        )
    }

    @Test
    fun `retains a resolved record excluded by repository scope`() {
        val reference = reference("ref-scoped", "inside", "excluded")
        val recordId = baselineId(reference)
        val scope = RepositoryScopeEvaluationContext(
            document = RepositoryScopeDocument(
                exclusions = mapOf(
                    "exclude-target" to RepositoryScopeExclusion(
                        selector = RepositoryScopeSelector(
                            kind = RepositoryScopeSelectorKind.JVM_PACKAGE_PATTERN,
                            pattern = "excluded",
                        ),
                        reason = "Outside governed architecture.",
                    ),
                ),
            ),
            exists = true,
            revision = "scope-1",
        )
        val result = planner.propose(
            CycleGovernanceDocument(records = mapOf(recordId to baselineRecord(reference))),
            listOf(completeInput(emptyList())),
            GovernanceRevision("governance-1"),
            repositoryScope = scope,
        )
        val proposal = assertIs<ResolvedBaselineCleanupResult.Proposed>(result).proposal

        assertTrue(proposal.eligibleRecordIds.isEmpty())
        assertEquals("scope-1", proposal.repositoryScopeRevision)
        assertEquals(
            listOf(recordId),
            proposal.retainedRecordIds.getValue(ResolvedBaselineRetentionReason.OUTSIDE_REPOSITORY_SCOPE),
        )
    }

    @Test
    fun `proposal tokens are deterministic and change with evidence or revision`() {
        val removed = reference("ref-removed", "a", "b")
        val document = CycleGovernanceDocument(records = mapOf(baselineId(removed) to baselineRecord(removed)))
        val first = proposed(document, completeInput(emptyList()), GovernanceRevision("revision-1"))
        val repeated = proposed(document, completeInput(emptyList()), GovernanceRevision("revision-1"))
        val changedRevision = proposed(document, completeInput(emptyList()), GovernanceRevision("revision-2"))
        val changedEvidence = proposed(
            document,
            completeInput(listOf(removed)),
            GovernanceRevision("revision-1"),
        )

        assertEquals(first.proposalToken, repeated.proposalToken)
        assertEquals(first.evidenceFingerprint, repeated.evidenceFingerprint)
        assertNotEquals(first.proposalToken, changedRevision.proposalToken)
        assertNotEquals(first.proposalToken, changedEvidence.proposalToken)
        assertNotEquals(first.proposedDocumentRevision, changedEvidence.proposedDocumentRevision)
    }

    private fun proposed(
        document: CycleGovernanceDocument,
        input: GovernanceEvaluationInput,
        revision: GovernanceRevision = GovernanceRevision("governance-1"),
    ): ResolvedBaselineCleanupProposal = assertIs<ResolvedBaselineCleanupResult.Proposed>(
        planner.propose(document, listOf(input), revision, evaluatorVersion = "test"),
    ).proposal

    private fun completeInput(references: List<GovernanceEvidenceReference>): GovernanceEvaluationInput =
        GovernanceEvaluationInput(
            GovernanceEvidenceSnapshot(
                sources = listOf(
                    GovernanceEvidenceSource(
                        id = SOURCE,
                        backend = GovernanceBackend.JVM_BYTECODE,
                        languages = setOf(GovernanceLanguage.JAVA),
                        supportedScopes = setOf(GovernanceScope.REFERENCE),
                        repositoryComplete = true,
                    ),
                ),
                nodes = references.flatMap { reference ->
                    listOf(
                        GovernanceEvidenceNode(SOURCE, reference.backend, reference.sourceLanguage, reference.source),
                        GovernanceEvidenceNode(SOURCE, reference.backend, reference.targetLanguage, reference.target),
                    )
                }.distinct(),
                references = references,
            ),
        )

    private fun reference(
        id: String,
        sourceUnit: String,
        targetUnit: String,
        module: String? = null,
    ) = GovernanceEvidenceReference(
        id = id,
        analysisSourceId = SOURCE,
        backend = GovernanceBackend.JVM_BYTECODE,
        sourceLanguage = GovernanceLanguage.JAVA,
        targetLanguage = GovernanceLanguage.JAVA,
        source = GovernanceIdentity(sourceUnit, type = "$sourceUnit.Owner", module = module),
        target = GovernanceIdentity(targetUnit, type = "$targetUnit.Target", module = module),
        dependencyKind = GovernanceDependencyKind.METHOD_CALL,
    )

    private fun baselineRecord(reference: GovernanceEvidenceReference) = CycleGovernanceRecord(
        analysisSource = GovernanceAnalysisSource(
            SOURCE,
            reference.backend,
            reference.sourceLanguage,
        ),
        scope = GovernanceScope.REFERENCE,
        ownerSide = GovernanceOwnerSide.SOURCE,
        source = reference.source,
        target = reference.target,
        dependencyKind = reference.dependencyKind,
        referenceIds = setOf(reference.id),
        kind = CycleGovernanceKind.DEBT,
        reason = DEFAULT_CYCLE_DEBT_BASELINE_REASON,
    )

    private fun baselineId(reference: GovernanceEvidenceReference): String =
        CycleDebtBaselineRecordIds.forReferenceId(reference.id)

    private companion object {
        const val SOURCE = "jvm:whole-project"
    }
}
