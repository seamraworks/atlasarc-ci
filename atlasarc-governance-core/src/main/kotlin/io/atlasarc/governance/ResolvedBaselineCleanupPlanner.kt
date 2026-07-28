package io.atlasarc.governance

import io.atlasarc.evaluation.CycleGovernanceEvaluator
import io.atlasarc.evaluation.GovernanceEvaluationInput
import io.atlasarc.evaluation.GovernanceEvaluationResult
import io.atlasarc.scope.RepositoryScopeEvaluationContext
import io.atlasarc.scope.RepositoryScopeMatcher
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class ResolvedBaselineRetentionReason {
    ACTIVE,
    OUTSIDE_ANALYSIS_SOURCE,
    OUTSIDE_REPOSITORY_SCOPE,
    NON_BASELINE,
    NOT_EXACT_SINGLE_REFERENCE_DEBT,
    INSUFFICIENT_EVIDENCE,
    INVALID_OR_AMBIGUOUS,
}

data class ResolvedBaselineCleanupProposal(
    val proposedDocument: CycleGovernanceDocument,
    val eligibleRecordIds: List<String>,
    val retainedRecordIds: Map<ResolvedBaselineRetentionReason, List<String>>,
    val evaluation: GovernanceEvaluationResult,
    val expectedGovernanceRevision: GovernanceRevision,
    val evidenceFingerprint: String,
    val analysisSourceIds: List<String>,
    val repositoryScopeRevision: String,
    val proposedDocumentRevision: GovernanceRevision,
    val proposalToken: String,
)

data class ResolvedBaselineCleanupDiagnostic(
    val code: String,
    val message: String,
    val recordId: String? = null,
)

sealed interface ResolvedBaselineCleanupResult {
    data class Proposed(val proposal: ResolvedBaselineCleanupProposal) : ResolvedBaselineCleanupResult
    data class Refused(val diagnostics: List<ResolvedBaselineCleanupDiagnostic>) : ResolvedBaselineCleanupResult
}

/** Pure, deterministic planner for the explicit cleanup of resolved baseline-managed records. */
class ResolvedBaselineCleanupPlanner(
    private val evaluator: CycleGovernanceEvaluator = CycleGovernanceEvaluator(),
    private val codec: CycleGovernanceCodec = CycleGovernanceCodec(),
    private val scopeMatcher: RepositoryScopeMatcher = RepositoryScopeMatcher(),
) {
    fun propose(
        document: CycleGovernanceDocument,
        inputs: List<GovernanceEvaluationInput>,
        expectedGovernanceRevision: GovernanceRevision,
        evaluatorVersion: String = "development",
        repositoryScope: RepositoryScopeEvaluationContext = RepositoryScopeEvaluationContext(),
    ): ResolvedBaselineCleanupResult {
        val encodedCurrent = when (val encoded = codec.encode(document)) {
            is GovernanceEncodeResult.Success -> encoded.text
            is GovernanceEncodeResult.Invalid -> return ResolvedBaselineCleanupResult.Refused(
                encoded.issues.map { issue ->
                    ResolvedBaselineCleanupDiagnostic(issue.code, issue.message, issue.recordId)
                },
            )
        }
        val evaluation = evaluator.evaluate(document, inputs, evaluatorVersion, repositoryScope)
        val evaluationsById = evaluation.records.associateBy { it.recordId }
        val globallyBlocked = inputs.any { input ->
            input.issues.any { it.severity == GovernanceIssueSeverity.ERROR && it.analysisSourceId == null }
        }
        val blockedSourceIds = inputs.asSequence()
            .flatMap { it.issues.asSequence() }
            .filter { it.severity == GovernanceIssueSeverity.ERROR }
            .mapNotNull { it.analysisSourceId }
            .toSet()
        val eligible = sortedSetOf<String>()
        val retained = ResolvedBaselineRetentionReason.entries
            .associateWithTo(linkedMapOf()) { sortedSetOf<String>() }

        document.records.toSortedMap().forEach { (recordId, record) ->
            val exactDebt = record.scope == GovernanceScope.REFERENCE &&
                record.ownerSide == GovernanceOwnerSide.SOURCE &&
                record.kind == CycleGovernanceKind.DEBT &&
                record.referenceIds.size == 1
            if (!exactDebt) {
                retained.getValue(ResolvedBaselineRetentionReason.NOT_EXACT_SINGLE_REFERENCE_DEBT) += recordId
                return@forEach
            }
            if (!CycleDebtBaselineRecordIds.isManaged(recordId, record)) {
                retained.getValue(ResolvedBaselineRetentionReason.NON_BASELINE) += recordId
                return@forEach
            }

            val recordEvaluation = evaluationsById.getValue(recordId)
            val reason = when (recordEvaluation.status) {
                GovernanceRecordStatus.ACTIVE -> ResolvedBaselineRetentionReason.ACTIVE
                GovernanceRecordStatus.RESOLVED -> if (
                    globallyBlocked || record.analysisSource.id in blockedSourceIds
                ) {
                    ResolvedBaselineRetentionReason.INSUFFICIENT_EVIDENCE
                } else {
                    null
                }
                GovernanceRecordStatus.NOT_IN_ANALYSIS -> if (
                    scopeMatcher.matchesAny(
                        repositoryScope.document,
                        record.analysisSource.backend,
                        record.source,
                        inputs.firstOrNull()?.evidence?.caseSensitive ?: true,
                    ) || scopeMatcher.matchesAny(
                        repositoryScope.document,
                        record.analysisSource.backend,
                        record.target,
                        inputs.firstOrNull()?.evidence?.caseSensitive ?: true,
                    )
                ) {
                    ResolvedBaselineRetentionReason.OUTSIDE_REPOSITORY_SCOPE
                } else {
                    ResolvedBaselineRetentionReason.OUTSIDE_ANALYSIS_SOURCE
                }
                GovernanceRecordStatus.PARTIAL,
                GovernanceRecordStatus.UNSUPPORTED,
                -> ResolvedBaselineRetentionReason.INSUFFICIENT_EVIDENCE
                GovernanceRecordStatus.MISSING_SOURCE,
                GovernanceRecordStatus.MISSING_TARGET,
                GovernanceRecordStatus.AMBIGUOUS,
                GovernanceRecordStatus.INVALID,
                -> ResolvedBaselineRetentionReason.INVALID_OR_AMBIGUOUS
            }
            if (reason == null) eligible += recordId else retained.getValue(reason) += recordId
        }

        val proposedDocument = document.copy(records = document.records - eligible)
        val encodedProposed = when (val encoded = codec.encode(proposedDocument)) {
            is GovernanceEncodeResult.Success -> encoded.text
            is GovernanceEncodeResult.Invalid -> return ResolvedBaselineCleanupResult.Refused(
                encoded.issues.map { issue ->
                    ResolvedBaselineCleanupDiagnostic(issue.code, issue.message, issue.recordId)
                },
            )
        }
        val evidenceFingerprint = evidenceFingerprint(inputs)
        val proposedRevision = GovernanceRevision(sha256(encodedProposed))
        val retainedResult = retained.mapValuesTo(linkedMapOf()) { (_, ids) -> ids.toList() }
        val analysisSourceIds = evaluation.sources.map { it.id }.distinct().sorted()
        val proposalToken = sha256(
            buildString {
                append(expectedGovernanceRevision.value).append('\n')
                append(sha256(encodedCurrent)).append('\n')
                append(evidenceFingerprint).append('\n')
                append(repositoryScope.revision).append('\n')
                append(proposedRevision.value).append('\n')
                append(eligible.joinToString("\u001f")).append('\n')
                retainedResult.forEach { (reason, ids) ->
                    append(reason.name).append('=').append(ids.joinToString("\u001f")).append('\n')
                }
            },
        )
        return ResolvedBaselineCleanupResult.Proposed(
            ResolvedBaselineCleanupProposal(
                proposedDocument = proposedDocument,
                eligibleRecordIds = eligible.toList(),
                retainedRecordIds = retainedResult,
                evaluation = evaluation,
                expectedGovernanceRevision = expectedGovernanceRevision,
                evidenceFingerprint = evidenceFingerprint,
                analysisSourceIds = analysisSourceIds,
                repositoryScopeRevision = repositoryScope.revision,
                proposedDocumentRevision = proposedRevision,
                proposalToken = proposalToken,
            ),
        )
    }

    private fun evidenceFingerprint(inputs: List<GovernanceEvaluationInput>): String {
        val lines = buildList {
            inputs.forEach { input ->
                val evidence = input.evidence
                add("snapshot|${evidence.caseSensitive}|${evidence.evaluationComplete}")
                evidence.sources.forEach { source ->
                    add(
                        listOf(
                            "source",
                            source.id,
                            source.backend.name,
                            source.languages.map { it.name }.sorted().joinToString(","),
                            source.supportedScopes.map { it.name }.sorted().joinToString(","),
                            source.fresh.toString(),
                            source.freshnessDiagnostic.orEmpty(),
                            source.repositoryComplete.toString(),
                            source.includedJvmModules.sorted().joinToString(","),
                        ).joinToString("|"),
                    )
                }
                evidence.nodes.forEach { node ->
                    add(
                        listOf("node", node.analysisSourceId, node.backend.name, node.language.name, identityKey(node.identity))
                            .joinToString("|"),
                    )
                }
                evidence.references.forEach { reference ->
                    add(
                        listOf(
                            "reference",
                            reference.id,
                            reference.analysisSourceId,
                            reference.backend.name,
                            reference.sourceLanguage.name,
                            reference.targetLanguage.name,
                            identityKey(reference.source),
                            identityKey(reference.target),
                            reference.dependencyKind?.name.orEmpty(),
                            reference.sourceMemberGovernanceEligible.toString(),
                            reference.targetMemberGovernanceEligible.toString(),
                        ).joinToString("|"),
                    )
                }
                input.issues.forEach { issue ->
                    add(
                        listOf(
                            "issue",
                            issue.code,
                            issue.message,
                            issue.severity.name,
                            issue.analysisSourceId.orEmpty(),
                            issue.recordId.orEmpty(),
                            issue.scopeRuleId.orEmpty(),
                        ).joinToString("|"),
                    )
                }
            }
        }
        return sha256(lines.sorted().joinToString("\n"))
    }

    private fun identityKey(identity: GovernanceIdentity): String = listOf(
        identity.architectureUnit,
        identity.type.orEmpty(),
        identity.sourceFile.orEmpty(),
        identity.member?.name.orEmpty(),
        identity.member?.descriptor.orEmpty(),
        identity.module.orEmpty(),
    ).joinToString("\u001e")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
