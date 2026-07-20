package io.atlasarc.evaluation

import io.atlasarc.governance.CycleGovernanceDocument
import io.atlasarc.governance.CycleGovernanceKind
import io.atlasarc.governance.CycleGovernanceRecord
import io.atlasarc.governance.GovernanceAnalysisSource
import io.atlasarc.governance.GovernanceBackend
import io.atlasarc.governance.GovernanceEvidenceNode
import io.atlasarc.governance.GovernanceEvidenceReference
import io.atlasarc.governance.GovernanceEvidenceSnapshot
import io.atlasarc.governance.GovernanceEvidenceSource
import io.atlasarc.governance.GovernanceIdentity
import io.atlasarc.governance.GovernanceIssueSeverity
import io.atlasarc.governance.GovernanceLanguage
import io.atlasarc.governance.GovernanceOwnerSide
import io.atlasarc.governance.GovernanceScope
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CycleGovernanceEvaluatorTest {
    @Test
    fun `an uncovered strongly connected component fails evaluation`() {
        val result = evaluate(document = CycleGovernanceDocument(), input = cycle())

        assertEquals(GovernanceEvaluationVerdict.PROBLEMS, result.verdict)
        assertEquals(listOf("a", "b"), result.problemGroups.single().members.map { it.architectureUnit })
        assertEquals(2, result.problemEdges.size)
    }

    @Test
    fun `accepting one direction breaks the problem cycle`() {
        val result = evaluate(
            document = CycleGovernanceDocument(
                records = mapOf("accept-a-to-b" to packageRecord("a", "b")),
            ),
            input = cycle(),
        )

        assertEquals(GovernanceEvaluationVerdict.CLEAN, result.verdict)
        assertEquals(1, result.summary.activeRecordCount)
        assertTrue(result.problemGroups.isEmpty())
    }

    @Test
    fun `one uncovered concrete reference keeps a structural edge red`() {
        val base = cycle()
        val extra = reference("a-b-structural", "a", "b")
        val result = evaluate(
            document = CycleGovernanceDocument(
                records = mapOf(
                    "one-reference" to packageRecord("a", "b").copy(
                        scope = GovernanceScope.REFERENCE,
                        referenceIds = setOf("a-b"),
                    ),
                ),
            ),
            input = base.copy(
                evidence = base.evidence.copy(references = base.evidence.references + extra),
            ),
        )

        assertEquals(GovernanceEvaluationVerdict.PROBLEMS, result.verdict)
        assertEquals(listOf("a-b-structural"), result.problemEdges.single { it.source.architectureUnit == "a" }.uncoveredReferenceIds)
    }

    @Test
    fun `stale acquisition fails closed even when every cycle is accepted`() {
        val input = cycle().copy(
            evidence = cycle().evidence.copy(
                sources = listOf(source(fresh = false)),
            ),
        )
        val result = evaluate(
            CycleGovernanceDocument(records = mapOf("accept-a-to-b" to packageRecord("a", "b"))),
            input,
        )

        assertEquals(GovernanceEvaluationVerdict.INVALID, result.verdict)
        assertTrue(result.issues.any { it.code == "stale-analysis-evidence" && it.severity == GovernanceIssueSeverity.ERROR })
    }

    @Test
    fun `split packages remain separate architecture units by module`() {
        val billing = cycle("billing")
        val orders = cycle("orders")
        val result = evaluate(
            CycleGovernanceDocument(
                records = mapOf(
                    "orders-only" to packageRecord("a", "b", "orders"),
                ),
            ),
            GovernanceEvaluationInput(
                evidence = GovernanceEvidenceSnapshot(
                    sources = listOf(source()),
                    nodes = billing.evidence.nodes + orders.evidence.nodes,
                    references = billing.evidence.references + orders.evidence.references,
                ),
            ),
        )

        assertEquals(GovernanceEvaluationVerdict.PROBLEMS, result.verdict)
        assertEquals(setOf("billing"), result.problemGroups.flatMap { it.members }.mapNotNullTo(sortedSetOf()) { it.module })
    }

    private fun evaluate(document: CycleGovernanceDocument, input: GovernanceEvaluationInput) =
        CycleGovernanceEvaluator().evaluate(document, listOf(input), "test")

    private fun cycle(module: String? = null): GovernanceEvaluationInput {
        val references = listOf(reference("a-b${module.orEmpty()}", "a", "b", module), reference("b-a${module.orEmpty()}", "b", "a", module))
        return GovernanceEvaluationInput(
            GovernanceEvidenceSnapshot(
                sources = listOf(source()),
                nodes = references.flatMap { reference ->
                    listOf(
                        GovernanceEvidenceNode(SOURCE_ID, GovernanceBackend.JVM_BYTECODE, GovernanceLanguage.JAVA, reference.source),
                        GovernanceEvidenceNode(SOURCE_ID, GovernanceBackend.JVM_BYTECODE, GovernanceLanguage.JAVA, reference.target),
                    )
                }.distinct(),
                references = references,
            ),
        )
    }

    private fun source(fresh: Boolean = true) = GovernanceEvidenceSource(
        id = SOURCE_ID,
        backend = GovernanceBackend.JVM_BYTECODE,
        languages = setOf(GovernanceLanguage.JAVA, GovernanceLanguage.KOTLIN),
        supportedScopes = setOf(GovernanceScope.PACKAGE, GovernanceScope.REFERENCE),
        fresh = fresh,
        freshnessDiagnostic = if (fresh) null else "Rebuild compiled output.",
    )

    private fun reference(id: String, from: String, to: String, module: String? = null) = GovernanceEvidenceReference(
        id = id,
        analysisSourceId = SOURCE_ID,
        backend = GovernanceBackend.JVM_BYTECODE,
        sourceLanguage = GovernanceLanguage.JAVA,
        targetLanguage = GovernanceLanguage.JAVA,
        source = GovernanceIdentity(from, module = module),
        target = GovernanceIdentity(to, module = module),
    )

    private fun packageRecord(from: String, to: String, module: String? = null) = CycleGovernanceRecord(
        analysisSource = GovernanceAnalysisSource(SOURCE_ID, GovernanceBackend.JVM_BYTECODE, GovernanceLanguage.JAVA),
        scope = GovernanceScope.PACKAGE,
        ownerSide = GovernanceOwnerSide.SOURCE,
        source = GovernanceIdentity(from, module = module),
        target = GovernanceIdentity(to, module = module),
        kind = CycleGovernanceKind.INTENTIONAL,
        reason = "Reviewed boundary.",
    )

    private companion object {
        const val SOURCE_ID = "jvm:whole-project"
    }
}
