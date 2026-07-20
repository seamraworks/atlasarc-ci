package io.atlasarc.evaluation

import io.atlasarc.governance.CycleGovernanceKind
import io.atlasarc.governance.GovernanceBackend
import io.atlasarc.governance.GovernanceIssueSeverity
import io.atlasarc.governance.GovernanceLanguage
import io.atlasarc.governance.GovernanceRecordStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val GOVERNANCE_EVALUATION_RESULT_VERSION: Int = 2

@Serializable
enum class GovernanceEvaluationVerdict {
    @SerialName("clean") CLEAN,
    @SerialName("problems") PROBLEMS,
    @SerialName("invalid") INVALID,
}

@Serializable
data class GovernanceEvaluationProducer(
    val name: String = "AtlasArc Governance Evaluator",
    val version: String,
)

@Serializable
data class GovernanceEvaluationSource(
    val id: String,
    val backend: GovernanceBackend,
    val languages: List<GovernanceLanguage>,
    val fresh: Boolean,
)

@Serializable
data class GovernanceRecordEvaluation(
    val recordId: String,
    val analysisSourceId: String,
    val status: GovernanceRecordStatus,
    val kind: CycleGovernanceKind,
    val matchedReferenceIds: List<String>,
    val diagnostics: List<String>,
)

@Serializable
data class GovernanceArchitectureUnit(
    val nodeKey: String,
    val architectureUnit: String,
    val module: String? = null,
)

@Serializable
data class GovernanceProblemEdge(
    val analysisSourceId: String,
    val cycleGroupId: Int,
    val source: GovernanceArchitectureUnit,
    val target: GovernanceArchitectureUnit,
    val uncoveredReferenceIds: List<String>,
    val governedReferenceIds: List<String>,
)

@Serializable
data class GovernanceProblemGroup(
    val analysisSourceId: String,
    val id: Int,
    val type: String,
    val members: List<GovernanceArchitectureUnit>,
    val edgeCount: Int,
)

@Serializable
data class GovernanceEvaluationIssue(
    val code: String,
    val message: String,
    val severity: GovernanceIssueSeverity,
    val analysisSourceId: String? = null,
    val recordId: String? = null,
)

@Serializable
data class GovernanceEvaluationSummary(
    val sourceCount: Int,
    val recordCount: Int,
    val activeRecordCount: Int,
    val debtRecordCount: Int,
    val resolvedRecordCount: Int,
    val invalidRecordCount: Int,
    val problemGroupCount: Int,
    val problemEdgeCount: Int,
)

@Serializable
data class GovernanceEvaluationResult(
    val resultVersion: Int = GOVERNANCE_EVALUATION_RESULT_VERSION,
    val producer: GovernanceEvaluationProducer,
    val governanceSchemaVersion: Int,
    val verdict: GovernanceEvaluationVerdict,
    val sources: List<GovernanceEvaluationSource>,
    val records: List<GovernanceRecordEvaluation>,
    val problemGroups: List<GovernanceProblemGroup>,
    val problemEdges: List<GovernanceProblemEdge>,
    val issues: List<GovernanceEvaluationIssue>,
    val summary: GovernanceEvaluationSummary,
)
