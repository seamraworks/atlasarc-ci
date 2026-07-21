package io.atlasarc.scope

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

sealed interface RepositoryScopeDecodeResult {
    data class Success(val document: RepositoryScopeDocument) : RepositoryScopeDecodeResult
    data class Invalid(
        val issues: List<RepositoryScopeIssue>,
        val document: RepositoryScopeDocument? = null,
    ) : RepositoryScopeDecodeResult
}

sealed interface RepositoryScopeEncodeResult {
    data class Success(val text: String) : RepositoryScopeEncodeResult
    data class Invalid(val issues: List<RepositoryScopeIssue>) : RepositoryScopeEncodeResult
}

@OptIn(ExperimentalSerializationApi::class)
class RepositoryScopeCodec(
    private val validator: RepositoryScopeValidator = RepositoryScopeValidator(),
) {
    fun decode(text: String): RepositoryScopeDecodeResult {
        duplicateKeys(text).firstOrNull()?.let { key ->
            return invalid("duplicate-json-key", "JSON object contains the duplicate key '$key'.")
        }
        val element = try {
            json.parseToJsonElement(text)
        } catch (exception: SerializationException) {
            return invalid("malformed-json", exception.message ?: "Malformed JSON.")
        } catch (exception: IllegalArgumentException) {
            return invalid("malformed-json", exception.message ?: "Malformed JSON.")
        }
        val root = element as? JsonObject
            ?: return invalid("invalid-root", "The repository scope document root must be a JSON object.")
        val version = root["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: return invalid("missing-schema-version", "schemaVersion must be an integer.")
        if (version != REPOSITORY_SCOPE_SCHEMA_VERSION) {
            return invalid(
                "unsupported-schema-version",
                "Schema version $version is not supported; expected $REPOSITORY_SCOPE_SCHEMA_VERSION.",
                "schemaVersion",
            )
        }
        val document = try {
            json.decodeFromJsonElement(RepositoryScopeDocument.serializer(), root)
        } catch (exception: SerializationException) {
            return invalid("schema-decode-failed", exception.message ?: "The document does not match the v1 schema.")
        } catch (exception: IllegalArgumentException) {
            return invalid("schema-decode-failed", exception.message ?: "The document does not match the v1 schema.")
        }
        val canonical = canonical(document)
        val issues = validator.validate(canonical)
        return if (issues.isEmpty()) RepositoryScopeDecodeResult.Success(canonical)
        else RepositoryScopeDecodeResult.Invalid(issues, canonical)
    }

    fun encode(document: RepositoryScopeDocument): RepositoryScopeEncodeResult {
        val canonical = canonical(document)
        val issues = validator.validate(canonical)
        if (issues.isNotEmpty()) return RepositoryScopeEncodeResult.Invalid(issues)
        return RepositoryScopeEncodeResult.Success(
            json.encodeToString(canonical).replace("\r\n", "\n").trimEnd() + "\n",
        )
    }

    fun canonical(document: RepositoryScopeDocument): RepositoryScopeDocument =
        document.copy(exclusions = document.exclusions.toSortedMap().toMap(linkedMapOf()))

    private fun invalid(code: String, message: String, field: String? = null) =
        RepositoryScopeDecodeResult.Invalid(listOf(RepositoryScopeIssue(code, message, field = field)))

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

private fun duplicateKeys(text: String): List<String> {
    data class Context(val keys: MutableSet<String> = mutableSetOf(), var expectingKey: Boolean = true)
    val contexts = ArrayDeque<Context?>()
    val duplicates = mutableListOf<String>()
    var index = 0
    while (index < text.length) {
        when (text[index]) {
            ' ', '\t', '\r', '\n', ':' -> index++
            '{' -> { contexts.addLast(Context()); index++ }
            '[' -> { contexts.addLast(null); index++ }
            '}', ']' -> { if (contexts.isNotEmpty()) contexts.removeLast(); index++ }
            ',' -> { contexts.lastOrNull()?.expectingKey = true; index++ }
            '"' -> {
                val literal = StringBuilder().append('"')
                var cursor = index + 1
                var escaped = false
                while (cursor < text.length) {
                    val char = text[cursor++]
                    literal.append(char)
                    if (!escaped && char == '"') break
                    escaped = !escaped && char == '\\'
                }
                val value = runCatching { Json.decodeFromString<String>(literal.toString()) }
                    .getOrDefault(literal.toString())
                contexts.lastOrNull()?.let { context ->
                    if (context.expectingKey) {
                        if (!context.keys.add(value)) duplicates += value
                        context.expectingKey = false
                    }
                }
                index = cursor
            }
            else -> index++
        }
    }
    return duplicates
}
