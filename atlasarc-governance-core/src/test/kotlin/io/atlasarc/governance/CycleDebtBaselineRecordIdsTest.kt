package io.atlasarc.governance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CycleDebtBaselineRecordIdsTest {
    private val validator = CycleGovernanceValidator()

    @Test
    fun `reserved baseline ids require their generated exact debt shape`() {
        val record = exactDebt("ref-a-b")
        val managedId = CycleDebtBaselineRecordIds.forReferenceId(record.referenceIds.single())

        assertTrue(CycleDebtBaselineRecordIds.isManaged(managedId, record))
        assertTrue(validator.validateRecord(managedId, record).isEmpty())

        listOf(
            managedId to record.copy(kind = CycleGovernanceKind.INTENTIONAL),
            managedId to record.copy(referenceIds = setOf("ref-other")),
            "${CycleDebtBaselineRecordIds.PREFIX}not-the-digest" to record,
        ).forEach { (recordId, malformed) ->
            assertEquals(
                "invalid-baseline-record-id",
                validator.validateRecord(recordId, malformed).first { it.code == "invalid-baseline-record-id" }.code,
            )
        }
    }

    @Test
    fun `independently authored exact debt remains valid outside the reserved namespace`() {
        assertTrue(validator.validateRecord("manual-exact-debt", exactDebt("ref-a-b")).isEmpty())
    }

    private fun exactDebt(referenceId: String) = CycleGovernanceRecord(
        analysisSource = GovernanceAnalysisSource(
            "jvm:whole-project",
            GovernanceBackend.JVM_BYTECODE,
            GovernanceLanguage.JAVA,
        ),
        scope = GovernanceScope.REFERENCE,
        ownerSide = GovernanceOwnerSide.SOURCE,
        source = GovernanceIdentity("a", type = "a.A"),
        target = GovernanceIdentity("b", type = "b.B"),
        referenceIds = setOf(referenceId),
        kind = CycleGovernanceKind.DEBT,
        reason = "Existing debt.",
    )
}
