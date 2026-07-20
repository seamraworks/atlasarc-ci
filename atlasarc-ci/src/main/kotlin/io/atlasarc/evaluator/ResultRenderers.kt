@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.atlasarc.evaluator

import io.atlasarc.evaluation.GovernanceEvaluationIssue
import io.atlasarc.evaluation.GovernanceEvaluationResult
import io.atlasarc.evaluation.GovernanceEvaluationVerdict
import io.atlasarc.evaluation.GovernanceProblemEdge
import io.atlasarc.governance.CYCLE_GOVERNANCE_RELATIVE_PATH
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object GovernanceEvaluationJson {
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(result: GovernanceEvaluationResult): String =
        json.encodeToString(result).replace("\r\n", "\n").trimEnd() + "\n"
}

object GovernanceEvaluationHumanRenderer {
    fun render(result: GovernanceEvaluationResult): String = buildString {
        appendLine("AtlasArc.io governance evaluation: ${result.verdict.name.lowercase()}")
        appendLine("Evaluator ${result.producer.version}; governance schema ${result.governanceSchemaVersion}")
        appendLine(
            "Sources ${result.summary.sourceCount}; records ${result.summary.recordCount} " +
                "(${result.summary.activeRecordCount} active, ${result.summary.debtRecordCount} debt, " +
                "${result.summary.resolvedRecordCount} resolved, ${result.summary.invalidRecordCount} invalid)",
        )
        appendLine(
            "Problem groups ${result.summary.problemGroupCount}; problem edges ${result.summary.problemEdgeCount}",
        )
        if (result.issues.isNotEmpty()) {
            appendLine()
            appendLine("Validation issues:")
            result.issues.forEach { issue ->
                val subject = listOfNotNull(issue.analysisSourceId, issue.recordId).joinToString("/")
                append("- ${issue.code}")
                if (subject.isNotBlank()) append(" [$subject]")
                appendLine(": ${issue.message}")
            }
        }
        if (result.problemGroups.isNotEmpty()) {
            appendLine()
            appendLine("Ungoverned cycle problems:")
            result.problemGroups.forEach { group ->
                appendLine(
                    "- ${group.analysisSourceId} group ${group.id} (${group.type}): " +
                        group.members.joinToString(" -> ") { it.displayLabel() },
                )
                result.problemEdges.filter {
                    it.analysisSourceId == group.analysisSourceId && it.cycleGroupId == group.id
                }.forEach { edge ->
                    appendLine(
                        "  ${edge.source.displayLabel()} -> ${edge.target.displayLabel()} " +
                            "(${edge.uncoveredReferenceIds.size} uncovered references)",
                    )
                }
            }
        }
    }.replace("\r\n", "\n")
}

object GovernanceEvaluationSarifRenderer {
    private const val SARIF_SCHEMA =
        "https://json.schemastore.org/sarif-2.1.0.json"
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun render(result: GovernanceEvaluationResult): String {
        val results = buildList {
            result.issues.forEach { add(issueResult(it)) }
            result.problemEdges.forEach { add(problemResult(it)) }
        }
        val ruleIds = results.mapNotNull { it["ruleId"]?.let { value -> (value as JsonPrimitive).content } }
            .distinct()
            .sorted()
        val root = buildJsonObject {
            put("version", "2.1.0")
            put("\$schema", SARIF_SCHEMA)
            putJsonArray("runs") {
                add(buildJsonObject {
                    putJsonObject("tool") {
                        putJsonObject("driver") {
                            put("name", result.producer.name)
                            put("version", result.producer.version)
                            put("informationUri", "https://atlasarc.io/")
                            putJsonArray("rules") {
                                ruleIds.forEach { ruleId -> add(rule(ruleId)) }
                            }
                        }
                    }
                    put("results", JsonArray(results))
                    putJsonObject("properties") {
                        put("governanceEvaluationResultVersion", result.resultVersion)
                        put("governanceSchemaVersion", result.governanceSchemaVersion)
                        put("verdict", result.verdict.name.lowercase())
                    }
                })
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
            .replace("\r\n", "\n")
            .trimEnd() + "\n"
    }

    private fun issueResult(issue: GovernanceEvaluationIssue): JsonObject = buildJsonObject {
        val ruleId = "ATLASARC_${issue.code.uppercase().replace('-', '_')}"
        put("ruleId", ruleId)
        put("level", if (issue.severity.name == "ERROR") "error" else "warning")
        putJsonObject("message") { put("text", issue.message) }
        if (issue.recordId != null) {
            putJsonArray("locations") {
                add(buildJsonObject {
                    putJsonObject("physicalLocation") {
                        putJsonObject("artifactLocation") {
                            put("uri", CYCLE_GOVERNANCE_RELATIVE_PATH)
                        }
                    }
                })
            }
        }
        putJsonObject("properties") {
            issue.analysisSourceId?.let { put("analysisSourceId", it) }
            issue.recordId?.let { put("recordId", it) }
        }
    }

    private fun problemResult(edge: GovernanceProblemEdge): JsonObject = buildJsonObject {
        put("ruleId", "ATLASARC_UNGOVERNED_CYCLE")
        put("level", "error")
        putJsonObject("message") {
            put("text", "Ungoverned cycle dependency ${edge.source.displayLabel()} -> ${edge.target.displayLabel()}.")
        }
        putJsonArray("logicalLocations") {
            add(buildJsonObject {
                put("name", "${edge.source.displayLabel()} -> ${edge.target.displayLabel()}")
                put("kind", "dependency")
            })
        }
        putJsonObject("properties") {
            put("analysisSourceId", edge.analysisSourceId)
            put("cycleGroupId", edge.cycleGroupId)
            put("uncoveredReferenceIds", JsonArray(edge.uncoveredReferenceIds.map(::JsonPrimitive)))
            put("governedReferenceIds", JsonArray(edge.governedReferenceIds.map(::JsonPrimitive)))
        }
    }

    private fun rule(ruleId: String): JsonObject = buildJsonObject {
        put("id", ruleId)
        put("name", ruleId.removePrefix("ATLASARC_").lowercase().replace('_', '-'))
        putJsonObject("shortDescription") {
            put(
                "text",
                if (ruleId == "ATLASARC_UNGOVERNED_CYCLE") {
                    "An architecture cycle contains ungoverned dependency evidence."
                } else {
                    "AtlasArc.io governance or evidence validation failed."
                },
            )
        }
    }
}

private fun io.atlasarc.evaluation.GovernanceArchitectureUnit.displayLabel(): String =
    module?.let { "$it:$architectureUnit" } ?: architectureUnit
