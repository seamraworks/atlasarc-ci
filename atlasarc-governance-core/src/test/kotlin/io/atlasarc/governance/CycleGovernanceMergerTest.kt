package io.atlasarc.governance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class CycleGovernanceMergerTest {
    private val merger = CycleGovernanceMerger()

    @Test
    fun `independent branch additions merge without ordering churn`() {
        val base = CycleGovernanceDocument()
        val ours = base.copy(records = mapOf("record-z" to record("z", "a")))
        val theirs = base.copy(records = mapOf("record-a" to record("a", "z")))

        val merged = assertInstanceOf(GovernanceMergeResult.Merged::class.java, merger.merge(base, ours, theirs))

        assertEquals(listOf("record-a", "record-z"), merged.document.records.keys.toList())
    }

    @Test
    fun `same-record edits surface a semantic conflict`() {
        val base = CycleGovernanceDocument(records = mapOf("record-one" to record("a", "b")))
        val ours = base.copy(records = mapOf("record-one" to record("a", "b").copy(reason = "ours")))
        val theirs = base.copy(records = mapOf("record-one" to record("a", "b").copy(reason = "theirs")))

        val result = assertInstanceOf(GovernanceMergeResult.Conflicts::class.java, merger.merge(base, ours, theirs))

        assertEquals(listOf("record-one"), result.conflicts.map { it.recordId })
    }

    private fun record(source: String, target: String) = CycleGovernanceRecord(
        analysisSource = GovernanceAnalysisSource("jvm:whole-project", GovernanceBackend.JVM_BYTECODE, GovernanceLanguage.JAVA),
        scope = GovernanceScope.PACKAGE,
        ownerSide = GovernanceOwnerSide.SOURCE,
        source = GovernanceIdentity(source),
        target = GovernanceIdentity(target),
        kind = CycleGovernanceKind.INTENTIONAL,
        reason = "Reviewed architecture boundary.",
    )
}
