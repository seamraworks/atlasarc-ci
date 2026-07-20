package io.atlasarc.governance

data class GovernanceMergeConflict(
    val recordId: String,
    val message: String,
)

sealed interface GovernanceMergeResult {
    data class Merged(val document: CycleGovernanceDocument) : GovernanceMergeResult
    data class Conflicts(val conflicts: List<GovernanceMergeConflict>) : GovernanceMergeResult
}

class CycleGovernanceMerger(
    private val codec: CycleGovernanceCodec = CycleGovernanceCodec(),
) {
    fun merge(
        base: CycleGovernanceDocument,
        ours: CycleGovernanceDocument,
        theirs: CycleGovernanceDocument,
    ): GovernanceMergeResult {
        if (setOf(base.schemaVersion, ours.schemaVersion, theirs.schemaVersion).size != 1) {
            return GovernanceMergeResult.Conflicts(
                listOf(GovernanceMergeConflict("<document>", "Schema versions differ.")),
            )
        }
        val merged = linkedMapOf<String, CycleGovernanceRecord>()
        val conflicts = mutableListOf<GovernanceMergeConflict>()
        (base.records.keys + ours.records.keys + theirs.records.keys).toSortedSet().forEach { id ->
            val baseValue = base.records[id]
            val ourValue = ours.records[id]
            val theirValue = theirs.records[id]
            val selected = when {
                ourValue == theirValue -> ourValue
                ourValue == baseValue -> theirValue
                theirValue == baseValue -> ourValue
                else -> {
                    conflicts += GovernanceMergeConflict(id, "The same governance record changed differently on both branches.")
                    null
                }
            }
            if (selected != null) merged[id] = selected
        }
        if (conflicts.isNotEmpty()) return GovernanceMergeResult.Conflicts(conflicts)
        return GovernanceMergeResult.Merged(codec.canonical(base.copy(records = merged)))
    }
}
