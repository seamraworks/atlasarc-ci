package io.atlasarc.parity

import io.atlasarc.evaluation.GovernanceEvaluationInput
import io.atlasarc.evaluation.GovernanceEvaluationResult
import io.atlasarc.evaluation.GovernanceEvaluationSummary
import io.atlasarc.evaluation.GovernanceEvaluationVerdict
import io.atlasarc.governance.CycleGovernanceKind
import io.atlasarc.governance.GovernanceBackend
import io.atlasarc.governance.GovernanceDependencyKind
import io.atlasarc.governance.GovernanceIdentity
import io.atlasarc.governance.GovernanceIssueSeverity
import io.atlasarc.governance.GovernanceLanguage
import io.atlasarc.governance.GovernanceRecordStatus
import io.atlasarc.governance.GovernanceScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

const val ACQUISITION_PARITY_SCHEMA_VERSION: Int = 1

/**
 * Canonical intersection of independently acquired evidence.
 *
 * The contract deliberately excludes orchestrator-owned completeness/case policy, diagnostic prose,
 * producer versions, repository revisions, opaque node keys, and standalone type-inventory nodes.
 * It retains every semantic reference tuple, member descriptor, eligibility flag,
 * architecture-unit/module identity, freshness fact, problem edge, problem group, and record status
 * shared by both acquirers.
 */
@Serializable
data class AcquisitionParitySnapshot(
    val schemaVersion: Int = ACQUISITION_PARITY_SCHEMA_VERSION,
    val sources: List<ParityEvidenceSource>,
    val architectureUnits: List<ParityArchitectureUnit>,
    val references: List<ParityReference>,
    val evidenceIssues: List<ParityIssue>,
    val evaluation: ParityEvaluation? = null,
)

@Serializable
data class ParityEvidenceSource(
    val id: String,
    val backend: GovernanceBackend,
    val languages: List<GovernanceLanguage>,
    val supportedScopes: List<GovernanceScope>,
    val fresh: Boolean,
    val includedJvmModules: List<String>,
)

@Serializable
data class ParityArchitectureUnit(
    val analysisSourceId: String,
    val backend: GovernanceBackend,
    val language: GovernanceLanguage,
    val architectureUnit: String,
    val module: String? = null,
)

@Serializable
data class ParityIdentity(
    val architectureUnit: String,
    val type: String? = null,
    val sourceFile: String? = null,
    val memberName: String? = null,
    val memberDescriptor: String? = null,
    val module: String? = null,
)

@Serializable
data class ParityReference(
    val id: String,
    val analysisSourceId: String,
    val backend: GovernanceBackend,
    val sourceLanguage: GovernanceLanguage,
    val targetLanguage: GovernanceLanguage,
    val source: ParityIdentity,
    val target: ParityIdentity,
    val dependencyKind: GovernanceDependencyKind? = null,
    val sourceMemberGovernanceEligible: Boolean,
    val targetMemberGovernanceEligible: Boolean,
)

@Serializable
data class ParityIssue(
    val code: String,
    val severity: GovernanceIssueSeverity,
    val analysisSourceId: String? = null,
    val recordId: String? = null,
    val scopeRuleId: String? = null,
)

@Serializable
data class ParityEvaluation(
    val verdict: GovernanceEvaluationVerdict,
    val sources: List<ParityEvaluationSource>,
    val records: List<ParityRecord>,
    val problemGroups: List<ParityProblemGroup>,
    val problemEdges: List<ParityProblemEdge>,
    val issues: List<ParityIssue>,
    val summary: ParitySummary,
)

@Serializable
data class ParityEvaluationSource(
    val id: String,
    val backend: GovernanceBackend,
    val languages: List<GovernanceLanguage>,
    val fresh: Boolean,
)

@Serializable
data class ParityRecord(
    val recordId: String,
    val analysisSourceId: String,
    val status: GovernanceRecordStatus,
    val kind: CycleGovernanceKind,
    val matchedReferenceIds: List<String>,
)

@Serializable
data class ParitySemanticUnit(
    val architectureUnit: String,
    val module: String? = null,
)

@Serializable
data class ParityProblemGroup(
    val analysisSourceId: String,
    val id: Int,
    val type: String,
    val members: List<ParitySemanticUnit>,
    val edgeCount: Int,
)

@Serializable
data class ParityProblemEdge(
    val analysisSourceId: String,
    val cycleGroupId: Int,
    val source: ParitySemanticUnit,
    val target: ParitySemanticUnit,
    val uncoveredReferenceIds: List<String>,
    val governedReferenceIds: List<String>,
)

@Serializable
data class ParitySummary(
    val sourceCount: Int,
    val recordCount: Int,
    val activeRecordCount: Int,
    val debtRecordCount: Int,
    val resolvedRecordCount: Int,
    val invalidRecordCount: Int,
    val problemGroupCount: Int,
    val problemEdgeCount: Int,
)

data class AcquisitionParityDifference(
    val path: String,
    val expected: String,
    val actual: String,
) {
    override fun toString(): String = "$path: expected $expected, actual $actual"
}

class AcquisitionParityMismatchException(
    val difference: AcquisitionParityDifference,
) : IllegalStateException("Acquisition parity mismatch at $difference")

object AcquisitionParityContract {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun snapshot(
        inputs: List<GovernanceEvaluationInput>,
        evaluation: GovernanceEvaluationResult? = null,
    ): AcquisitionParitySnapshot {
        val evidence = inputs.map(GovernanceEvaluationInput::evidence)
        return AcquisitionParitySnapshot(
            sources = evidence.flatMap { it.sources }
                .distinctBy { it.id to it.backend }
                .sortedWith(compareBy({ it.id }, { it.backend.name }))
                .map { source ->
                    ParityEvidenceSource(
                        id = source.id,
                        backend = source.backend,
                        languages = source.languages.sortedBy(Enum<*>::name),
                        supportedScopes = source.supportedScopes.sortedBy(Enum<*>::name),
                        fresh = source.fresh,
                        includedJvmModules = source.includedJvmModules.sorted(),
                    )
                },
            architectureUnits = evidence.flatMap { it.nodes }
                .map { node ->
                    ParityArchitectureUnit(
                        analysisSourceId = node.analysisSourceId,
                        backend = node.backend,
                        language = node.language,
                        architectureUnit = node.identity.architectureUnit,
                        module = node.identity.module,
                    )
                }
                .distinct()
                .sortedWith(compareBy(
                    { it.analysisSourceId },
                    { it.backend.name },
                    { it.language.name },
                    { it.module.orEmpty() },
                    { it.architectureUnit },
                )),
            references = evidence.flatMap { it.references }
                .distinctBy { it.id }
                .sortedBy { it.id }
                .map { reference ->
                    ParityReference(
                        id = reference.id,
                        analysisSourceId = reference.analysisSourceId,
                        backend = reference.backend,
                        sourceLanguage = reference.sourceLanguage,
                        targetLanguage = reference.targetLanguage,
                        source = reference.source.toParityIdentity(),
                        target = reference.target.toParityIdentity(),
                        dependencyKind = reference.dependencyKind,
                        sourceMemberGovernanceEligible = reference.sourceMemberGovernanceEligible,
                        targetMemberGovernanceEligible = reference.targetMemberGovernanceEligible,
                    )
                },
            evidenceIssues = inputs.flatMap { it.issues }
                .map { issue ->
                    ParityIssue(
                        code = issue.code,
                        severity = issue.severity,
                        analysisSourceId = issue.analysisSourceId,
                        recordId = issue.recordId,
                        scopeRuleId = issue.scopeRuleId,
                    )
                }
                .distinct()
                .sortedWith(issueComparator),
            evaluation = evaluation?.toParityEvaluation(),
        )
    }

    fun encode(snapshot: AcquisitionParitySnapshot): String =
        json.encodeToString(snapshot).replace("\r\n", "\n").trimEnd() + "\n"

    fun decode(text: String): AcquisitionParitySnapshot = json.decodeFromString(text)

    fun firstDifference(
        expected: AcquisitionParitySnapshot,
        actual: AcquisitionParitySnapshot,
    ): AcquisitionParityDifference? = firstDifference(
        json.encodeToJsonElement(AcquisitionParitySnapshot.serializer(), expected),
        json.encodeToJsonElement(AcquisitionParitySnapshot.serializer(), actual),
        "$",
    )

    fun requireEquivalent(
        expected: AcquisitionParitySnapshot,
        actual: AcquisitionParitySnapshot,
    ) {
        firstDifference(expected, actual)?.let { throw AcquisitionParityMismatchException(it) }
    }

    fun requireEquivalent(expectedText: String, actual: AcquisitionParitySnapshot) =
        requireEquivalent(decode(expectedText), actual)

    fun verifyCorpusManifest(moduleRoot: Path) {
        val root = moduleRoot.toAbsolutePath().normalize()
        val manifestPath = root.resolve("src/test/resources/acquisition-parity/corpus-manifest.json")
        val manifest = json.parseToJsonElement(Files.readString(manifestPath)).jsonObject
        require(manifest.getValue("corpusVersion").jsonPrimitive.content == "1") {
            "Unsupported acquisition parity corpus version in $manifestPath"
        }
        manifest.getValue("files").jsonObject.toSortedMap().forEach { (relativePath, value) ->
            val path = root.resolve(relativePath).normalize()
            require(path.startsWith(root) && Files.isRegularFile(path)) {
                "Acquisition parity manifest entry is missing or escapes the module: $relativePath"
            }
            val portableBytes = Files.readString(path)
                .replace("\r\n", "\n")
                .toByteArray(StandardCharsets.UTF_8)
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(portableBytes)
                .joinToString("") { byte -> "%02x".format(byte) }
            val expected = value.jsonPrimitive.content.lowercase()
            require(actual == expected) {
                "Acquisition parity corpus drift at $relativePath: expected $expected, actual $actual"
            }
        }
    }

    private fun firstDifference(
        expected: JsonElement,
        actual: JsonElement,
        path: String,
    ): AcquisitionParityDifference? {
        if (expected::class != actual::class) {
            return AcquisitionParityDifference(path, expected.toString(), actual.toString())
        }
        return when (expected) {
            is JsonObject -> {
                val actualObject = actual as JsonObject
                (expected.keys + actualObject.keys).toSortedSet().firstNotNullOfOrNull { key ->
                    val expectedValue = expected[key]
                    val actualValue = actualObject[key]
                    when {
                        expectedValue == null -> AcquisitionParityDifference("$path.$key", "<missing>", actualValue.toString())
                        actualValue == null -> AcquisitionParityDifference("$path.$key", expectedValue.toString(), "<missing>")
                        else -> firstDifference(expectedValue, actualValue, "$path.$key")
                    }
                }
            }
            is JsonArray -> {
                val actualArray = actual as JsonArray
                if (expected.size != actualArray.size) {
                    AcquisitionParityDifference("$path.length", expected.size.toString(), actualArray.size.toString())
                } else {
                    expected.indices.firstNotNullOfOrNull { index ->
                        firstDifference(expected[index], actualArray[index], "$path[$index]")
                    }
                }
            }
            else -> if (expected == actual) null else AcquisitionParityDifference(path, expected.toString(), actual.toString())
        }
    }

    private fun GovernanceIdentity.toParityIdentity() = ParityIdentity(
        architectureUnit = architectureUnit,
        type = type,
        sourceFile = sourceFile,
        memberName = member?.name,
        memberDescriptor = member?.descriptor,
        module = module,
    )

    private fun GovernanceEvaluationResult.toParityEvaluation() = ParityEvaluation(
        verdict = verdict,
        sources = sources.sortedWith(compareBy({ it.id }, { it.backend.name })).map { source ->
            ParityEvaluationSource(
                id = source.id,
                backend = source.backend,
                languages = source.languages.sortedBy(Enum<*>::name),
                fresh = source.fresh,
            )
        },
        records = records.sortedBy { it.recordId }.map { record ->
            ParityRecord(
                recordId = record.recordId,
                analysisSourceId = record.analysisSourceId,
                status = record.status,
                kind = record.kind,
                matchedReferenceIds = record.matchedReferenceIds.sorted(),
            )
        },
        problemGroups = problemGroups.sortedWith(compareBy({ it.analysisSourceId }, { it.id })).map { group ->
            ParityProblemGroup(
                analysisSourceId = group.analysisSourceId,
                id = group.id,
                type = group.type,
                members = group.members.map { ParitySemanticUnit(it.architectureUnit, it.module) }
                    .sortedWith(semanticUnitComparator),
                edgeCount = group.edgeCount,
            )
        },
        problemEdges = problemEdges.sortedWith(compareBy(
            { it.analysisSourceId },
            { it.cycleGroupId },
            { it.source.module.orEmpty() },
            { it.source.architectureUnit },
            { it.target.module.orEmpty() },
            { it.target.architectureUnit },
        )).map { edge ->
            ParityProblemEdge(
                analysisSourceId = edge.analysisSourceId,
                cycleGroupId = edge.cycleGroupId,
                source = ParitySemanticUnit(edge.source.architectureUnit, edge.source.module),
                target = ParitySemanticUnit(edge.target.architectureUnit, edge.target.module),
                uncoveredReferenceIds = edge.uncoveredReferenceIds.sorted(),
                governedReferenceIds = edge.governedReferenceIds.sorted(),
            )
        },
        issues = issues.map { issue ->
            ParityIssue(
                code = issue.code,
                severity = issue.severity,
                analysisSourceId = issue.analysisSourceId,
                recordId = issue.recordId,
                scopeRuleId = issue.scopeRuleId,
            )
        }.distinct().sortedWith(issueComparator),
        summary = summary.toParitySummary(),
    )

    private fun GovernanceEvaluationSummary.toParitySummary() = ParitySummary(
        sourceCount = sourceCount,
        recordCount = recordCount,
        activeRecordCount = activeRecordCount,
        debtRecordCount = debtRecordCount,
        resolvedRecordCount = resolvedRecordCount,
        invalidRecordCount = invalidRecordCount,
        problemGroupCount = problemGroupCount,
        problemEdgeCount = problemEdgeCount,
    )

    private val issueComparator = compareBy<ParityIssue>(
        { it.code },
        { it.severity.name },
        { it.analysisSourceId.orEmpty() },
        { it.recordId.orEmpty() },
        { it.scopeRuleId.orEmpty() },
    )
    private val semanticUnitComparator = compareBy<ParitySemanticUnit>(
        { it.module.orEmpty() },
        { it.architectureUnit },
    )
}
