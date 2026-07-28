package io.atlasarc.governance

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Reserved identity contract for records created and managed by the cycle-debt baseline workflow. */
object CycleDebtBaselineRecordIds {
    const val PREFIX: String = "cycle-baseline-"

    fun forReferenceId(referenceId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(referenceId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$PREFIX${digest.take(32)}"
    }

    fun isReserved(recordId: String): Boolean = recordId.startsWith(PREFIX)

    fun isManaged(recordId: String, record: CycleGovernanceRecord): Boolean {
        val referenceId = record.referenceIds.singleOrNull() ?: return false
        return recordId == forReferenceId(referenceId) &&
            record.scope == GovernanceScope.REFERENCE &&
            record.ownerSide == GovernanceOwnerSide.SOURCE &&
            record.kind == CycleGovernanceKind.DEBT
    }
}
