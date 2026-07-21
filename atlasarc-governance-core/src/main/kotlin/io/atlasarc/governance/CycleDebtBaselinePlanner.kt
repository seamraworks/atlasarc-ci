package io.atlasarc.governance

import io.atlasarc.evaluation.CycleGovernanceEvaluator
import io.atlasarc.evaluation.GovernanceEvaluationInput
import io.atlasarc.evaluation.GovernanceEvaluationResult
import io.atlasarc.evaluation.GovernanceEvaluationVerdict
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import io.atlasarc.scope.RepositoryScopeEvaluationContext

const val DEFAULT_CYCLE_DEBT_BASELINE_REASON: String =
    "Established as existing cycle debt by the AtlasArc CI baseline."

data class CycleDebtBaselineOptions(
    val reason: String = DEFAULT_CYCLE_DEBT_BASELINE_REASON,
    val ticket: String? = null,
)

data class CycleDebtBaselineDiagnostic(
    val code: String,
    val message: String,
    val analysisSourceId: String? = null,
    val recordId: String? = null,
)

data class CycleDebtBaselineProposal(
    val proposedDocument: CycleGovernanceDocument,
    val addedRecords: Map<String, CycleGovernanceRecord>,
    val startingEvaluation: GovernanceEvaluationResult,
    val resultingEvaluation: GovernanceEvaluationResult,
    val problemGroupCount: Int,
    val problemReferenceCount: Int,
    val selectedEdgeCount: Int,
    val alreadyGovernedReferenceCount: Int,
    val untouchedRecordCount: Int,
)

sealed interface CycleDebtBaselineResult {
    data class Proposed(val proposal: CycleDebtBaselineProposal) : CycleDebtBaselineResult
    data class Refused(val diagnostics: List<CycleDebtBaselineDiagnostic>) : CycleDebtBaselineResult
}

/**
 * Produces a deterministic, reviewable debt-baseline mutation from complete portable evidence.
 *
 * The planner is pure with respect to the repository. Callers remain responsible for presenting
 * intent and performing a revision-checked write through [CycleGovernanceRepository].
 */
class CycleDebtBaselinePlanner private constructor(
    private val evaluator: CycleGovernanceEvaluator,
    private val codec: CycleGovernanceCodec,
    private val maxRecords: Int,
    private val feedbackEdgeSelector: CycleDebtFeedbackEdgeSelector,
) {
    constructor(
        evaluator: CycleGovernanceEvaluator = CycleGovernanceEvaluator(),
        codec: CycleGovernanceCodec = CycleGovernanceCodec(),
    ) : this(evaluator, codec, MAX_CYCLE_GOVERNANCE_RECORDS, CycleDebtFeedbackEdgeSelector())

    internal constructor(maxRecords: Int) : this(
        CycleGovernanceEvaluator(),
        CycleGovernanceCodec(),
        maxRecords,
        CycleDebtFeedbackEdgeSelector(),
    )

    fun propose(
        document: CycleGovernanceDocument,
        inputs: List<GovernanceEvaluationInput>,
        options: CycleDebtBaselineOptions = CycleDebtBaselineOptions(),
        evaluatorVersion: String = "development",
        repositoryScope: RepositoryScopeEvaluationContext = RepositoryScopeEvaluationContext(),
    ): CycleDebtBaselineResult {
        val inputDiagnostics = validateInputs(inputs, options)
        val documentDiagnostics = when (val encoded = codec.encode(document)) {
            is GovernanceEncodeResult.Success -> emptyList()
            is GovernanceEncodeResult.Invalid -> encoded.issues.map { issue ->
                CycleDebtBaselineDiagnostic(issue.code, issue.message, recordId = issue.recordId)
            }
        }
        val preflight = (documentDiagnostics + inputDiagnostics).distinct()
        if (preflight.isNotEmpty()) return CycleDebtBaselineResult.Refused(preflight)

        val starting = evaluator.evaluate(document, inputs, evaluatorVersion, repositoryScope)
        if (starting.verdict == GovernanceEvaluationVerdict.INVALID) {
            return CycleDebtBaselineResult.Refused(
                starting.issues.map { issue ->
                    CycleDebtBaselineDiagnostic(
                        code = issue.code,
                        message = issue.message,
                        analysisSourceId = issue.analysisSourceId,
                        recordId = issue.recordId,
                    )
                }.ifEmpty {
                    listOf(CycleDebtBaselineDiagnostic("invalid-evaluation", "Current governance evidence is invalid."))
                },
            )
        }

        val problemReferenceIds = starting.problemEdges
            .flatMapTo(sortedSetOf()) { it.uncoveredReferenceIds }
        val selectedEdges = feedbackEdgeSelector.select(starting.problemEdges)
        val selectedReferenceIds = selectedEdges
            .flatMapTo(sortedSetOf()) { it.uncoveredReferenceIds }
        val alreadyGovernedReferenceIds = starting.problemEdges
            .flatMapTo(sortedSetOf()) { it.governedReferenceIds }
        if (document.records.size + selectedReferenceIds.size > maxRecords) {
            return CycleDebtBaselineResult.Refused(
                listOf(
                    CycleDebtBaselineDiagnostic(
                        "baseline-record-limit-exceeded",
                        "The exact debt baseline would contain ${document.records.size + selectedReferenceIds.size} records, exceeding the governance limit of $maxRecords. No broad replacement selector was generated.",
                    ),
                ),
            )
        }
        val referencesById = inputs.asSequence()
            .flatMap { it.evidence.references.asSequence() }
            .groupBy(GovernanceEvidenceReference::id)
        val ambiguousReferenceIds = referencesById
            .filterValues { references -> references.distinct().size != 1 }
            .keys
            .sorted()
        if (ambiguousReferenceIds.isNotEmpty()) {
            return CycleDebtBaselineResult.Refused(
                ambiguousReferenceIds.map { id ->
                    CycleDebtBaselineDiagnostic(
                        "ambiguous-reference-id",
                        "Concrete reference '$id' maps to more than one evidence identity.",
                    )
                },
            )
        }
        val missingReferenceIds = problemReferenceIds.filter { it !in referencesById }
        if (missingReferenceIds.isNotEmpty()) {
            return CycleDebtBaselineResult.Refused(
                missingReferenceIds.map { id ->
                    CycleDebtBaselineDiagnostic(
                        "missing-problem-reference",
                        "Problem-cycle reference '$id' is absent from the evidence snapshot.",
                    )
                },
            )
        }

        val added = linkedMapOf<String, CycleGovernanceRecord>()
        for (referenceId in selectedReferenceIds) {
            val reference = referencesById.getValue(referenceId).single()
            val recordId = baselineRecordId(reference)
            val record = reference.toBaselineRecord(options)
            val existing = document.records[recordId]
            if (existing != null && existing != record) {
                return CycleDebtBaselineResult.Refused(
                    listOf(
                        CycleDebtBaselineDiagnostic(
                            "baseline-record-id-conflict",
                            "Generated record ID '$recordId' is already used by a different governance decision.",
                            analysisSourceId = reference.analysisSourceId,
                            recordId = recordId,
                        ),
                    ),
                )
            }
            if (existing == null) added[recordId] = record
        }

        val proposed = document.copy(records = (document.records + added).toSortedMap())
        val serializationDiagnostics = when (val encoded = codec.encode(proposed)) {
            is GovernanceEncodeResult.Success -> emptyList()
            is GovernanceEncodeResult.Invalid -> encoded.issues.map { issue ->
                CycleDebtBaselineDiagnostic(issue.code, issue.message, recordId = issue.recordId)
            }
        }
        if (serializationDiagnostics.isNotEmpty()) {
            return CycleDebtBaselineResult.Refused(serializationDiagnostics)
        }

        val resulting = evaluator.evaluate(proposed, inputs, evaluatorVersion, repositoryScope)
        if (resulting.verdict != GovernanceEvaluationVerdict.CLEAN) {
            val diagnostics = resulting.issues.map { issue ->
                CycleDebtBaselineDiagnostic(
                    issue.code,
                    issue.message,
                    issue.analysisSourceId,
                    issue.recordId,
                )
            }.ifEmpty {
                listOf(
                    CycleDebtBaselineDiagnostic(
                        "post-baseline-not-clean",
                        "The proposed debt records do not produce a clean cycle-governance verdict.",
                    ),
                )
            }
            return CycleDebtBaselineResult.Refused(diagnostics)
        }

        return CycleDebtBaselineResult.Proposed(
            CycleDebtBaselineProposal(
                proposedDocument = proposed,
                addedRecords = added,
                startingEvaluation = starting,
                resultingEvaluation = resulting,
                problemGroupCount = starting.problemGroups.size,
                problemReferenceCount = problemReferenceIds.size,
                selectedEdgeCount = selectedEdges.size,
                alreadyGovernedReferenceCount = alreadyGovernedReferenceIds.size,
                untouchedRecordCount = document.records.size,
            ),
        )
    }

    private fun validateInputs(
        inputs: List<GovernanceEvaluationInput>,
        options: CycleDebtBaselineOptions,
    ): List<CycleDebtBaselineDiagnostic> = buildList {
        if (inputs.isEmpty()) {
            add(CycleDebtBaselineDiagnostic("no-analysis-sources", "No complete analysis evidence was supplied."))
        }
        if (options.reason.isBlank()) {
            add(CycleDebtBaselineDiagnostic("missing-reason", "A baseline reason is required."))
        }
        inputs.forEach { input ->
            if (input.evidence.sources.isEmpty()) {
                add(CycleDebtBaselineDiagnostic("missing-analysis-source", "An evidence snapshot declares no analysis source."))
            }
            input.evidence.sources.forEach { source ->
                if (!source.repositoryComplete) {
                    add(
                        CycleDebtBaselineDiagnostic(
                            "partial-analysis-evidence",
                            "Analysis source '${source.id}' does not cover its complete repository scope.",
                            source.id,
                        ),
                    )
                }
                if (!source.fresh) {
                    add(
                        CycleDebtBaselineDiagnostic(
                            "stale-analysis-evidence",
                            source.freshnessDiagnostic
                                ?: "Analysis source '${source.id}' is stale; regenerate it before creating a baseline.",
                            source.id,
                        ),
                    )
                }
                if (GovernanceScope.REFERENCE !in source.supportedScopes) {
                    add(
                        CycleDebtBaselineDiagnostic(
                            "unsupported-reference-governance",
                            "Analysis source '${source.id}' cannot create exact-reference governance.",
                            source.id,
                        ),
                    )
                }
            }
            input.issues.filter { it.severity == GovernanceIssueSeverity.ERROR }.forEach { issue ->
                add(CycleDebtBaselineDiagnostic(issue.code, issue.message, issue.analysisSourceId, issue.recordId))
            }
        }
    }.distinct()

    private fun GovernanceEvidenceReference.toBaselineRecord(options: CycleDebtBaselineOptions) =
        CycleGovernanceRecord(
            analysisSource = GovernanceAnalysisSource(
                id = analysisSourceId,
                backend = backend,
                language = sourceLanguage,
            ),
            scope = GovernanceScope.REFERENCE,
            ownerSide = GovernanceOwnerSide.SOURCE,
            source = source,
            target = target,
            dependencyKind = dependencyKind,
            referenceIds = setOf(id),
            kind = CycleGovernanceKind.DEBT,
            reason = options.reason.trim(),
            ticket = options.ticket?.trim()?.takeIf(String::isNotEmpty),
        )

    private fun baselineRecordId(reference: GovernanceEvidenceReference): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(reference.id.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "cycle-baseline-${digest.take(32)}"
    }
}
