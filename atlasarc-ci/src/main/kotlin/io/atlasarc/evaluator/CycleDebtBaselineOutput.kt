@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.atlasarc.evaluator

import io.atlasarc.governance.CycleDebtBaselineDiagnostic
import io.atlasarc.governance.CycleDebtBaselineProposal
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CycleDebtBaselineCommandResult(
    val resultVersion: Int = 1,
    val producer: String = "AtlasArc.io CI",
    val producerVersion: String,
    val safe: Boolean,
    val writeRequested: Boolean,
    val written: Boolean,
    val noChange: Boolean,
    val documentPath: String,
    val startingVerdict: String,
    val resultingVerdict: String,
    val summary: CycleDebtBaselineCommandSummary,
    val records: List<CycleDebtBaselineRecordPreview>,
    val diagnostics: List<CycleDebtBaselineCommandDiagnostic>,
)

@Serializable
data class CycleDebtBaselineCommandSummary(
    val problemGroups: Int = 0,
    val ungovernedCycleReferences: Int = 0,
    val alreadyGovernedReferences: Int = 0,
    val existingRecordsUntouched: Int = 0,
    val recordsToAdd: Int = 0,
)

@Serializable
data class CycleDebtBaselineRecordPreview(
    val recordId: String,
    val analysisSourceId: String,
    val referenceId: String,
    val source: String,
    val sourceModule: String = "",
    val target: String,
    val targetModule: String = "",
)

@Serializable
data class CycleDebtBaselineCommandDiagnostic(
    val code: String,
    val message: String,
    val analysisSourceId: String = "",
    val recordId: String = "",
)

object CycleDebtBaselineOutput {
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = false
    }

    fun success(
        proposal: CycleDebtBaselineProposal,
        writeRequested: Boolean,
        written: Boolean,
        noChange: Boolean,
    ): CycleDebtBaselineCommandResult = CycleDebtBaselineCommandResult(
        producerVersion = ATLASARC_EVALUATOR_VERSION,
        safe = true,
        writeRequested = writeRequested,
        written = written,
        noChange = noChange,
        documentPath = ".atlasarc/governance/cycles.json",
        startingVerdict = proposal.startingEvaluation.verdict.name.lowercase(),
        resultingVerdict = proposal.resultingEvaluation.verdict.name.lowercase(),
        summary = CycleDebtBaselineCommandSummary(
            problemGroups = proposal.problemGroupCount,
            ungovernedCycleReferences = proposal.problemReferenceCount,
            alreadyGovernedReferences = proposal.alreadyGovernedReferenceCount,
            existingRecordsUntouched = proposal.untouchedRecordCount,
            recordsToAdd = proposal.addedRecords.size,
        ),
        records = proposal.addedRecords.map { (recordId, record) ->
            CycleDebtBaselineRecordPreview(
                recordId = recordId,
                analysisSourceId = record.analysisSource.id,
                referenceId = record.referenceIds.single(),
                source = record.source.architectureUnit,
                sourceModule = record.source.module.orEmpty(),
                target = record.target.architectureUnit,
                targetModule = record.target.module.orEmpty(),
            )
        },
        diagnostics = emptyList(),
    )

    fun failure(
        diagnostics: List<CycleDebtBaselineDiagnostic>,
        writeRequested: Boolean,
    ): CycleDebtBaselineCommandResult = CycleDebtBaselineCommandResult(
        producerVersion = ATLASARC_EVALUATOR_VERSION,
        safe = false,
        writeRequested = writeRequested,
        written = false,
        noChange = false,
        documentPath = ".atlasarc/governance/cycles.json",
        startingVerdict = "invalid",
        resultingVerdict = "invalid",
        summary = CycleDebtBaselineCommandSummary(),
        records = emptyList(),
        diagnostics = diagnostics.map { diagnostic ->
            CycleDebtBaselineCommandDiagnostic(
                code = diagnostic.code,
                message = diagnostic.message,
                analysisSourceId = diagnostic.analysisSourceId.orEmpty(),
                recordId = diagnostic.recordId.orEmpty(),
            )
        },
    )

    fun render(result: CycleDebtBaselineCommandResult, format: EvaluatorOutputFormat): String = when (format) {
        EvaluatorOutputFormat.JSON -> json.encodeToString(result).replace("\r\n", "\n").trimEnd() + "\n"
        EvaluatorOutputFormat.HUMAN -> renderHuman(result)
        EvaluatorOutputFormat.SARIF -> error("SARIF is not a baseline output format.")
    }

    private fun renderHuman(result: CycleDebtBaselineCommandResult): String = buildString {
        appendLine("AtlasArc.io cycle-debt baseline")
        appendLine()
        if (!result.safe) {
            appendLine("Baseline refused. No governance file was changed.")
            result.diagnostics.forEach { diagnostic -> appendLine("- ${diagnostic.code}: ${diagnostic.message}") }
            return@buildString
        }
        appendLine("Current problem cycle groups: ${result.summary.problemGroups}")
        appendLine("Ungoverned cycle references: ${result.summary.ungovernedCycleReferences}")
        appendLine("Already governed references in those groups: ${result.summary.alreadyGovernedReferences}")
        appendLine("Existing governance records left unchanged: ${result.summary.existingRecordsUntouched}")
        appendLine("Exact DEBT records to add: ${result.summary.recordsToAdd}")
        appendLine("Result after baseline: ${result.resultingVerdict}")
        appendLine()
        when {
            result.written -> appendLine("Wrote ${result.documentPath}. Review and commit this file with the repository.")
            result.noChange -> appendLine("No baseline changes were needed; ${result.documentPath} was left byte-for-byte unchanged.")
            else -> appendLine("Preview only. No file was changed; rerun with --write to create this baseline.")
        }
    }
}
