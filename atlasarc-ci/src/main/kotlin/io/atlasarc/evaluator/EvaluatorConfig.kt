@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.atlasarc.evaluator

import io.atlasarc.governance.GovernanceBackend
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

const val EVALUATOR_CONFIG_VERSION: Int = 1
const val EVALUATOR_CONFIG_SCHEMA_URI: String = "https://atlasarc.io/schemas/evaluator-config-v1.schema.json"

@Serializable
data class EvaluatorPathSpec(
    val path: String,
    val module: String? = null,
)

@Serializable
data class EvaluatorSourceConfig(
    val id: String,
    val backend: GovernanceBackend,
    val root: String = ".",
    val classDirectories: List<EvaluatorPathSpec> = emptyList(),
    val sourceRoots: List<EvaluatorPathSpec> = emptyList(),
    val dependencyCruiserJson: String? = null,
)

@Serializable
data class EvaluatorConfig(
    @Required
    @SerialName("\$schema")
    val schema: String = EVALUATOR_CONFIG_SCHEMA_URI,
    val configVersion: Int = EVALUATOR_CONFIG_VERSION,
    val repositoryRoot: String = ".",
    val sources: List<EvaluatorSourceConfig>,
)

class EvaluatorConfigurationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

object EvaluatorConfigCodec {
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
        allowTrailingComma = false
    }

    fun read(path: Path): EvaluatorConfig {
        val text = try {
            Files.readString(path)
        } catch (exception: Exception) {
            throw EvaluatorConfigurationException("Could not read evaluator configuration.", exception)
        }
        return try {
            json.decodeFromString<EvaluatorConfig>(text).also(::validate)
        } catch (exception: SerializationException) {
            throw EvaluatorConfigurationException("Evaluator configuration does not match version 1.", exception)
        } catch (exception: IllegalArgumentException) {
            throw EvaluatorConfigurationException(exception.message ?: "Evaluator configuration is invalid.", exception)
        }
    }

    fun encode(config: EvaluatorConfig): String =
        json.encodeToString(config).replace("\r\n", "\n").trimEnd() + "\n"

    fun validate(config: EvaluatorConfig) {
        if (config.schema != EVALUATOR_CONFIG_SCHEMA_URI) {
            throw EvaluatorConfigurationException("Unsupported evaluator schema '${config.schema}'.")
        }
        if (config.configVersion != EVALUATOR_CONFIG_VERSION) {
            throw EvaluatorConfigurationException(
                "Unsupported evaluator config version ${config.configVersion}; expected $EVALUATOR_CONFIG_VERSION.",
            )
        }
        if (config.sources.isEmpty()) {
            throw EvaluatorConfigurationException("Evaluator configuration must declare at least one analysis source.")
        }
        val duplicateIds = config.sources.groupBy { it.id }.filterValues { it.size > 1 }.keys.sorted()
        if (duplicateIds.isNotEmpty()) {
            throw EvaluatorConfigurationException("Analysis source IDs must be unique: ${duplicateIds.joinToString()}.")
        }
        config.sources.forEach { source ->
            if (source.id.isBlank()) throw EvaluatorConfigurationException("Analysis source ID must not be blank.")
            when (source.backend) {
                GovernanceBackend.JVM_BYTECODE -> {
                    if (source.classDirectories.isEmpty()) {
                        throw EvaluatorConfigurationException(
                            "JVM source '${source.id}' must declare at least one class directory.",
                        )
                    }
                    if (source.dependencyCruiserJson != null) {
                        throw EvaluatorConfigurationException(
                            "JVM source '${source.id}' cannot declare TypeScript artifacts.",
                        )
                    }
                    val jvmRoots = source.classDirectories + source.sourceRoots
                    val hasNamedRoots = jvmRoots.any { !it.module.isNullOrBlank() }
                    val hasModulelessRoots = jvmRoots.any { it.module.isNullOrBlank() }
                    if (hasNamedRoots && hasModulelessRoots) {
                        throw EvaluatorConfigurationException(
                            "JVM source '${source.id}' mixes named and module-less roots. " +
                                "Either assign a stable module to every class and source root, or omit all module labels " +
                                "to describe one genuinely module-less JVM universe.",
                        )
                    }
                }
                GovernanceBackend.TYPESCRIPT_ARTIFACT -> {
                    if (source.dependencyCruiserJson.isNullOrBlank()) {
                        throw EvaluatorConfigurationException(
                            "TypeScript source '${source.id}' must declare dependencyCruiserJson.",
                        )
                    }
                    if (source.classDirectories.isNotEmpty()) {
                        throw EvaluatorConfigurationException(
                            "TypeScript source '${source.id}' cannot declare JVM class directories.",
                        )
                    }
                }
            }
        }
    }
}
