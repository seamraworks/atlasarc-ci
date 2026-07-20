package io.atlasarc.governance

import kotlinx.serialization.SerializationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

sealed interface GovernanceDecodeResult {
    data class Success(val document: CycleGovernanceDocument) : GovernanceDecodeResult
    data class Invalid(
        val issues: List<GovernanceValidationIssue>,
        /** Present when JSON decoding succeeded and only semantic validation failed. */
        val document: CycleGovernanceDocument? = null,
    ) : GovernanceDecodeResult
}

sealed interface GovernanceEncodeResult {
    data class Success(val text: String) : GovernanceEncodeResult
    data class Invalid(val issues: List<GovernanceValidationIssue>) : GovernanceEncodeResult
}

@OptIn(ExperimentalSerializationApi::class)
class CycleGovernanceCodec(
    private val validator: CycleGovernanceValidator = CycleGovernanceValidator(),
) {
    fun decode(text: String): GovernanceDecodeResult {
        JsonDuplicateKeyDetector.find(text).firstOrNull()?.let { duplicate ->
            return invalid("duplicate-json-key", "JSON object contains the duplicate key '$duplicate'.")
        }
        val element = try {
            json.parseToJsonElement(text)
        } catch (exception: SerializationException) {
            return invalid("malformed-json", exception.message ?: "Malformed JSON.")
        } catch (exception: IllegalArgumentException) {
            return invalid("malformed-json", exception.message ?: "Malformed JSON.")
        }

        val root = element as? JsonObject
            ?: return invalid("invalid-root", "The governance document root must be a JSON object.")
        val version = root["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: return invalid("missing-schema-version", "schemaVersion must be an integer.")
        if (version != CYCLE_GOVERNANCE_SCHEMA_VERSION) {
            return invalid(
                "unsupported-schema-version",
                "Schema version $version is not supported; expected $CYCLE_GOVERNANCE_SCHEMA_VERSION.",
                "schemaVersion",
            )
        }

        val document = try {
            json.decodeFromJsonElement(CycleGovernanceDocument.serializer(), root)
        } catch (exception: SerializationException) {
            return invalid("schema-decode-failed", exception.message ?: "The document does not match the v1 schema.")
        } catch (exception: IllegalArgumentException) {
            return invalid("schema-decode-failed", exception.message ?: "The document does not match the v1 schema.")
        }
        val issues = validator.validate(document)
        return if (issues.any { it.severity == GovernanceIssueSeverity.ERROR }) {
            GovernanceDecodeResult.Invalid(issues, canonical(document))
        } else {
            GovernanceDecodeResult.Success(canonical(document))
        }
    }

    fun encode(document: CycleGovernanceDocument): GovernanceEncodeResult {
        val issues = validator.validate(document)
        if (issues.any { it.severity == GovernanceIssueSeverity.ERROR }) {
            return GovernanceEncodeResult.Invalid(issues)
        }
        val encoded = json.encodeToString(canonical(document))
            .replace("\r\n", "\n")
            .trimEnd() + "\n"
        return GovernanceEncodeResult.Success(encoded)
    }

    fun canonical(document: CycleGovernanceDocument): CycleGovernanceDocument =
        document.copy(
            records = document.records.toSortedMap().mapValuesTo(linkedMapOf()) { (_, record) ->
                record.copy(referenceIds = record.referenceIds.toSortedSet())
            },
        )

    private fun invalid(code: String, message: String, field: String? = null) =
        GovernanceDecodeResult.Invalid(
            listOf(GovernanceValidationIssue(code = code, message = message, field = field)),
        )

    private companion object {
        val json = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = false
            isLenient = false
            allowTrailingComma = false
        }
    }
}

private object JsonDuplicateKeyDetector {
    private data class ObjectContext(
        val keys: MutableSet<String> = mutableSetOf(),
        var expectingKey: Boolean = true,
    )

    fun find(text: String): List<String> {
        val objects = ArrayDeque<ObjectContext?>()
        val duplicates = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            when (val char = text[index]) {
                ' ', '\t', '\r', '\n', ':' -> index++
                '{' -> {
                    objects.addLast(ObjectContext())
                    index++
                }
                '[' -> {
                    objects.addLast(null)
                    index++
                }
                '}', ']' -> {
                    if (objects.isNotEmpty()) objects.removeLast()
                    index++
                }
                ',' -> {
                    objects.lastOrNull()?.expectingKey = true
                    index++
                }
                '"' -> {
                    val (value, next) = readString(text, index)
                    val context = objects.lastOrNull()
                    if (context != null && context.expectingKey) {
                        if (!context.keys.add(value)) duplicates += value
                        context.expectingKey = false
                    }
                    index = next
                }
                else -> {
                    index++
                    while (index < text.length && text[index] !in charArrayOf(',', '}', ']', ' ', '\t', '\r', '\n')) {
                        index++
                    }
                }
            }
        }
        return duplicates
    }

    private fun readString(text: String, start: Int): Pair<String, Int> {
        val literal = StringBuilder().append('"')
        var index = start + 1
        var escaped = false
        while (index < text.length) {
            val char = text[index++]
            literal.append(char)
            if (!escaped && char == '"') break
            escaped = !escaped && char == '\\'
        }
        val decoded = runCatching { keyJson.decodeFromString<String>(literal.toString()) }
            .getOrDefault(literal.toString())
        return decoded to index
    }

    private val keyJson = Json
}
