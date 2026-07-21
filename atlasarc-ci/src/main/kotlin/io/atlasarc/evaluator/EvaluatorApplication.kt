package io.atlasarc.evaluator

import io.atlasarc.evaluation.GOVERNANCE_EVALUATION_RESULT_VERSION
import io.atlasarc.evaluation.CycleGovernanceEvaluator
import io.atlasarc.evaluation.GovernanceEvaluationIssue
import io.atlasarc.evaluation.GovernanceEvaluationProducer
import io.atlasarc.evaluation.GovernanceEvaluationResult
import io.atlasarc.evaluation.GovernanceEvaluationSummary
import io.atlasarc.evaluation.GovernanceEvaluationVerdict
import io.atlasarc.governance.CYCLE_GOVERNANCE_SCHEMA_VERSION
import io.atlasarc.governance.CycleDebtBaselineDiagnostic
import io.atlasarc.governance.CycleDebtBaselineOptions
import io.atlasarc.governance.CycleDebtBaselinePlanner
import io.atlasarc.governance.CycleDebtBaselineResult
import io.atlasarc.governance.CycleGovernanceRepository
import io.atlasarc.governance.GovernanceIssueSeverity
import io.atlasarc.governance.GovernanceReadResult
import io.atlasarc.governance.GovernanceValidationIssue
import io.atlasarc.governance.VcsRootLocator
import io.atlasarc.governance.GovernanceWriteCheckResult
import io.atlasarc.governance.GovernanceWriteResult
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

val ATLASARC_EVALUATOR_VERSION: String =
    EvaluatorApplication::class.java.`package`.implementationVersion ?: "development"

object EvaluatorExitCode {
    const val CLEAN = 0
    const val PROBLEMS = 1
    const val INVALID = 2
    const val INTERNAL_ERROR = 3
}

class EvaluatorApplication(
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
    private val acquirer: EvidenceAcquirer = HeadlessEvidenceAcquirer(),
    private val evaluator: CycleGovernanceEvaluator = CycleGovernanceEvaluator(),
    private val repository: CycleGovernanceRepository = CycleGovernanceRepository(),
    private val baselinePlanner: CycleDebtBaselinePlanner = CycleDebtBaselinePlanner(),
) {
    fun run(arguments: Array<String>, currentDirectory: Path = Path.of(".")): Int {
        val command = try {
            EvaluatorCli.parse(arguments, currentDirectory.toAbsolutePath().normalize())
        } catch (exception: EvaluatorConfigurationException) {
            err.println("AtlasArc.io evaluator configuration error: ${exception.message}")
            return EvaluatorExitCode.INVALID
        }
        return when (command) {
            EvaluatorCommand.Help -> {
                out.print(helpText())
                EvaluatorExitCode.CLEAN
            }
            EvaluatorCommand.Version -> {
                out.println(ATLASARC_EVALUATOR_VERSION)
                EvaluatorExitCode.CLEAN
            }
            is EvaluatorCommand.Evaluate -> evaluate(command.invocation)
            is EvaluatorCommand.Baseline -> baseline(command.invocation)
        }
    }

    private fun evaluate(invocation: EvaluatorInvocation): Int {
        val config = try {
            invocation.directConfig ?: EvaluatorConfigCodec.read(invocation.configPath!!)
        } catch (exception: EvaluatorConfigurationException) {
            return emitFailure(
                invocation,
                "invalid-evaluator-config",
                exception.message ?: "Evaluator configuration is invalid.",
            )
        }
        val start = resolve(invocation.configBase, config.repositoryRoot)
        val repositoryRoot = VcsRootLocator().nearest(start)
            ?: return emitFailure(
                invocation,
                "missing-vcs-root",
                "No owning Git repository was found for the configured repository root.",
            )

        val governanceRead = repository.read(repositoryRoot)
        val documentIssues: List<GovernanceValidationIssue>
        val document = when (governanceRead) {
            is GovernanceReadResult.Loaded -> {
                documentIssues = emptyList()
                governanceRead.value.document
            }
            is GovernanceReadResult.Invalid -> {
                val decoded = governanceRead.document
                    ?: return emitFailure(
                        invocation,
                        "invalid-governance-document",
                        governanceRead.issues.joinToString("; ") { "${it.code}: ${it.message}" },
                    )
                documentIssues = governanceRead.issues
                decoded
            }
            is GovernanceReadResult.MissingVcsRoot -> return emitFailure(
                invocation,
                "missing-vcs-root",
                "No owning Git repository was found for the governance document.",
            )
            is GovernanceReadResult.IoError -> return emitFailure(
                invocation,
                "governance-read-failed",
                "The governance document could not be read.",
            )
        }

        val inputSources = try {
            config.sources.sortedBy { it.id }.map { source -> acquirer.acquire(source, repositoryRoot) }
        } catch (exception: EvaluatorAcquisitionException) {
            return emitFailure(
                invocation,
                "analysis-acquisition-failed",
                exception.message ?: "Analysis evidence acquisition failed.",
                exception.analysisSourceId,
            )
        } catch (exception: Exception) {
            err.println("AtlasArc.io evaluator internal error while acquiring evidence.")
            return EvaluatorExitCode.INTERNAL_ERROR
        }

        val result = try {
            evaluator.evaluate(document, inputSources, ATLASARC_EVALUATOR_VERSION)
        } catch (exception: Exception) {
            err.println("AtlasArc.io evaluator internal error while evaluating governance.")
            return EvaluatorExitCode.INTERNAL_ERROR
        }
        val finalResult = if (documentIssues.isEmpty()) {
            result
        } else {
            result.copy(
                verdict = GovernanceEvaluationVerdict.INVALID,
                issues = (result.issues + documentIssues.map { issue ->
                    GovernanceEvaluationIssue(
                        code = issue.code,
                        message = issue.message,
                        severity = issue.severity,
                        recordId = issue.recordId,
                    )
                }).distinct().sortedWith(compareBy({ it.recordId.orEmpty() }, { it.code }, { it.message })),
            )
        }
        return try {
            emit(invocation, finalResult)
            when (finalResult.verdict) {
                GovernanceEvaluationVerdict.CLEAN -> EvaluatorExitCode.CLEAN
                GovernanceEvaluationVerdict.PROBLEMS -> EvaluatorExitCode.PROBLEMS
                GovernanceEvaluationVerdict.INVALID -> EvaluatorExitCode.INVALID
            }
        } catch (exception: Exception) {
            err.println("AtlasArc.io evaluator could not write its requested output.")
            EvaluatorExitCode.INVALID
        }
    }

    private fun baseline(invocation: BaselineInvocation): Int {
        val evaluatorInvocation = invocation.evaluator
        val config = try {
            EvaluatorConfigCodec.read(evaluatorInvocation.configPath!!)
        } catch (exception: EvaluatorConfigurationException) {
            return emitBaselineFailure(
                evaluatorInvocation,
                invocation.write,
                "invalid-evaluator-config",
                exception.message ?: "Evaluator configuration is invalid.",
            )
        }
        val start = resolve(evaluatorInvocation.configBase, config.repositoryRoot)
        val repositoryRoot = VcsRootLocator().nearest(start)
            ?: return emitBaselineFailure(
                evaluatorInvocation,
                invocation.write,
                "missing-vcs-root",
                "No owning Git repository was found for the configured repository root.",
            )
        val loaded = when (val read = repository.read(repositoryRoot)) {
            is GovernanceReadResult.Loaded -> read.value
            is GovernanceReadResult.Invalid -> return emitBaselineFailure(
                evaluatorInvocation,
                invocation.write,
                "invalid-governance-document",
                read.issues.joinToString("; ") { "${it.code}: ${it.message}" },
            )
            is GovernanceReadResult.MissingVcsRoot -> return emitBaselineFailure(
                evaluatorInvocation,
                invocation.write,
                "missing-vcs-root",
                "No owning Git repository was found for the governance document.",
            )
            is GovernanceReadResult.IoError -> return emitBaselineFailure(
                evaluatorInvocation,
                invocation.write,
                "governance-read-failed",
                "The governance document could not be read.",
            )
        }
        when (val check = repository.checkWrite(repositoryRoot)) {
            is GovernanceWriteCheckResult.Ready -> Unit
            is GovernanceWriteCheckResult.MissingVcsRoot -> return emitBaselineFailure(
                evaluatorInvocation, invocation.write, "missing-vcs-root", "No owning Git repository was found.",
            )
            is GovernanceWriteCheckResult.IgnoredPath -> return emitBaselineFailure(
                evaluatorInvocation,
                invocation.write,
                "ignored-governance-path",
                buildString {
                    append("Git ignore rules exclude .atlasarc/governance/cycles.json")
                    check.rule?.let { append(" (${it.source}:${it.line}: ${it.pattern})") }
                    append(". Make the governance path committable before creating a baseline.")
                },
            )
            is GovernanceWriteCheckResult.IgnoreCheckUnavailable -> return emitBaselineFailure(
                evaluatorInvocation,
                invocation.write,
                "ignore-check-unavailable",
                "AtlasArc.io could not prove that .atlasarc/governance/cycles.json is visible to Git.",
            )
            is GovernanceWriteCheckResult.ReadOnly -> return emitBaselineFailure(
                evaluatorInvocation,
                invocation.write,
                "read-only-governance-path",
                "The governance file or its nearest existing parent is read-only.",
            )
        }

        val inputs = try {
            config.sources.sortedBy { it.id }.map { source -> acquirer.acquire(source, repositoryRoot) }
        } catch (exception: EvaluatorAcquisitionException) {
            return emitBaselineFailure(
                evaluatorInvocation,
                invocation.write,
                "analysis-acquisition-failed",
                exception.message ?: "Analysis evidence acquisition failed.",
                exception.analysisSourceId,
            )
        } catch (exception: Exception) {
            err.println("AtlasArc.io evaluator internal error while acquiring baseline evidence.")
            return EvaluatorExitCode.INTERNAL_ERROR
        }
        val planned = try {
            baselinePlanner.propose(
                loaded.document,
                inputs,
                CycleDebtBaselineOptions(
                    reason = invocation.reason ?: io.atlasarc.governance.DEFAULT_CYCLE_DEBT_BASELINE_REASON,
                    ticket = invocation.ticket,
                ),
                ATLASARC_EVALUATOR_VERSION,
            )
        } catch (exception: Exception) {
            err.println("AtlasArc.io evaluator internal error while planning the cycle-debt baseline.")
            return EvaluatorExitCode.INTERNAL_ERROR
        }
        if (planned is CycleDebtBaselineResult.Refused) {
            return emitBaseline(evaluatorInvocation, CycleDebtBaselineOutput.failure(planned.diagnostics, invocation.write))
        }
        val proposal = (planned as CycleDebtBaselineResult.Proposed).proposal
        var written = false
        var noChange = proposal.addedRecords.isEmpty()
        if (invocation.write && proposal.addedRecords.isNotEmpty()) {
            when (val result = repository.write(repositoryRoot, proposal.proposedDocument, loaded.revision)) {
                is GovernanceWriteResult.Written -> written = true
                is GovernanceWriteResult.NoChange -> noChange = true
                is GovernanceWriteResult.ConcurrentEdit -> return emitBaselineFailure(
                    evaluatorInvocation,
                    true,
                    "concurrent-edit",
                    "The governance file changed after the baseline preview; reload evidence and retry.",
                )
                is GovernanceWriteResult.IgnoredPath -> return emitBaselineFailure(
                    evaluatorInvocation, true, "ignored-governance-path", "Git ignore rules changed and now hide the governance file.",
                )
                is GovernanceWriteResult.IgnoreCheckUnavailable -> return emitBaselineFailure(
                    evaluatorInvocation, true, "ignore-check-unavailable", "AtlasArc.io could not recheck Git ignore rules.",
                )
                is GovernanceWriteResult.InvalidDocument -> return emitBaselineFailure(
                    evaluatorInvocation,
                    true,
                    "invalid-governance-document",
                    result.issues.joinToString("; ") { it.message },
                )
                is GovernanceWriteResult.MissingVcsRoot -> return emitBaselineFailure(
                    evaluatorInvocation, true, "missing-vcs-root", "The owning Git repository is no longer available.",
                )
                is GovernanceWriteResult.ReadOnly -> return emitBaselineFailure(
                    evaluatorInvocation, true, "read-only-governance-path", "The governance path became read-only.",
                )
                is GovernanceWriteResult.IoError -> return emitBaselineFailure(
                    evaluatorInvocation, true, "governance-write-failed", result.message,
                )
            }
        }
        return emitBaseline(
            evaluatorInvocation,
            CycleDebtBaselineOutput.success(proposal, invocation.write, written, noChange),
        )
    }

    private fun emitBaselineFailure(
        invocation: EvaluatorInvocation,
        writeRequested: Boolean,
        code: String,
        message: String,
        analysisSourceId: String? = null,
    ): Int = emitBaseline(
        invocation,
        CycleDebtBaselineOutput.failure(
            listOf(CycleDebtBaselineDiagnostic(code, message, analysisSourceId)),
            writeRequested,
        ),
    )

    private fun emitBaseline(invocation: EvaluatorInvocation, result: CycleDebtBaselineCommandResult): Int = try {
        val text = CycleDebtBaselineOutput.render(result, invocation.format)
        val output = invocation.output
        if (output == null) {
            out.print(text)
        } else {
            output.parent?.let(Files::createDirectories)
            Files.writeString(output, text)
        }
        if (result.safe) EvaluatorExitCode.CLEAN else EvaluatorExitCode.INVALID
    } catch (exception: Exception) {
        err.println("AtlasArc.io evaluator could not write its requested baseline output.")
        EvaluatorExitCode.INVALID
    }

    private fun emitFailure(
        invocation: EvaluatorInvocation,
        code: String,
        message: String,
        analysisSourceId: String? = null,
    ): Int {
        val result = GovernanceEvaluationResult(
            resultVersion = GOVERNANCE_EVALUATION_RESULT_VERSION,
            producer = GovernanceEvaluationProducer(version = ATLASARC_EVALUATOR_VERSION),
            governanceSchemaVersion = CYCLE_GOVERNANCE_SCHEMA_VERSION,
            verdict = GovernanceEvaluationVerdict.INVALID,
            sources = emptyList(),
            records = emptyList(),
            problemGroups = emptyList(),
            problemEdges = emptyList(),
            issues = listOf(
                GovernanceEvaluationIssue(
                    code = code,
                    message = message,
                    severity = GovernanceIssueSeverity.ERROR,
                    analysisSourceId = analysisSourceId,
                ),
            ),
            summary = GovernanceEvaluationSummary(0, 0, 0, 0, 0, 0, 0, 0),
        )
        return try {
            emit(invocation, result)
            EvaluatorExitCode.INVALID
        } catch (exception: Exception) {
            err.println("AtlasArc.io evaluator could not write its requested output.")
            EvaluatorExitCode.INVALID
        }
    }

    private fun emit(invocation: EvaluatorInvocation, result: GovernanceEvaluationResult) {
        val text = when (invocation.format) {
            EvaluatorOutputFormat.HUMAN -> GovernanceEvaluationHumanRenderer.render(result)
            EvaluatorOutputFormat.JSON -> GovernanceEvaluationJson.encode(result)
            EvaluatorOutputFormat.SARIF -> GovernanceEvaluationSarifRenderer.render(result)
        }
        val output = invocation.output
        if (output == null) {
            out.print(text)
        } else {
            output.parent?.let(Files::createDirectories)
            Files.writeString(output, text)
        }
    }

    private fun resolve(base: Path, value: String): Path {
        val path = Path.of(value)
        return (if (path.isAbsolute) path else base.resolve(path)).toAbsolutePath().normalize()
    }

    private fun helpText(): String = """
        AtlasArc.io Governance Evaluator $ATLASARC_EVALUATOR_VERSION

        Usage:
          java -jar atlasarc-ci.jar evaluate --config <file> [--format human|json|sarif] [--output <file>]

          java -jar atlasarc-ci.jar evaluate --backend jvm-bytecode --source-id <id>
            --classes [module=]<dir> [--classes [module=]<dir> ...]
            [--source-root [module=]<dir> ...] [--repository-root <dir>]
            [--format human|json|sarif] [--output <file>]

          java -jar atlasarc-ci.jar evaluate --backend typescript-artifact --source-id <id>
            --root <source-root> --dependency-cruiser <depgraph.json>
            [--repository-root <dir>] [--format human|json|sarif] [--output <file>]

          java -jar atlasarc-ci.jar baseline --config <file> [--format human|json] [--output <file>]
            [--reason <text>] [--ticket <id>] [--write]

        The evaluator reads .atlasarc/governance/cycles.json from the nearest owning Git root.
        It never invokes build tools or Node tooling. Machine output never includes governance
        reasons/tickets or absolute workstation paths.

        Baseline preview is read-only. --write selects cycle-breaking edges and adds one exact DEBT
        record per current reference on those edges; it never accepts every SCC edge or broadly
        covers future dependencies.

        Exit codes: 0 clean; 1 ungoverned cycles; 2 invalid/stale/configuration failure; 3 internal error.
    """.trimIndent() + "\n"
}
