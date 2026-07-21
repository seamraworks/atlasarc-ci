@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.atlasarc.scope

import io.atlasarc.governance.GovernanceIssueSeverity
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val REPOSITORY_SCOPE_SCHEMA_VERSION: Int = 1
const val REPOSITORY_SCOPE_SCHEMA_URI: String =
    "https://atlasarc.io/schemas/repository-scope-v1.schema.json"
const val REPOSITORY_SCOPE_RELATIVE_PATH: String = ".atlasarc/governance/scope.json"

@Serializable
data class RepositoryScopeDocument(
    @Required
    @SerialName("\$schema")
    val schema: String = REPOSITORY_SCOPE_SCHEMA_URI,
    @Required
    val schemaVersion: Int = REPOSITORY_SCOPE_SCHEMA_VERSION,
    @Required
    val exclusions: Map<String, RepositoryScopeExclusion> = emptyMap(),
)

@Serializable
data class RepositoryScopeExclusion(
    val selector: RepositoryScopeSelector,
    val reason: String,
)

@Serializable
data class RepositoryScopeSelector(
    val kind: RepositoryScopeSelectorKind,
    val pattern: String,
    /**
     * JVM ownership selector. Omitted means legitimately module-less, `*` means every ownership,
     * and any other value is one stable named module. It is forbidden for TypeScript selectors.
     */
    val module: String? = null,
)

@Serializable
enum class RepositoryScopeSelectorKind {
    @SerialName("jvm-package-pattern")
    JVM_PACKAGE_PATTERN,

    @SerialName("typescript-source-folder-pattern")
    TYPESCRIPT_SOURCE_FOLDER_PATTERN,
}

data class RepositoryScopeIssue(
    val code: String,
    val message: String,
    val severity: GovernanceIssueSeverity = GovernanceIssueSeverity.ERROR,
    val ruleId: String? = null,
    val field: String? = null,
)

@Serializable
data class RepositoryScopeRuleEvaluation(
    val ruleId: String,
    val matchedArchitectureUnitCount: Int,
)

@Serializable
data class RepositoryScopeSummary(
    val ruleCount: Int,
    val appliedRuleCount: Int,
    val staleRuleCount: Int,
    val architectureUnitCountBefore: Int,
    val architectureUnitCountAfter: Int,
    val referenceCountBefore: Int,
    val referenceCountAfter: Int,
) {
    val excludedArchitectureUnitCount: Int
        get() = architectureUnitCountBefore - architectureUnitCountAfter

    val excludedReferenceCount: Int
        get() = referenceCountBefore - referenceCountAfter
}

data class RepositoryScopeEvaluationContext(
    val document: RepositoryScopeDocument = RepositoryScopeDocument(),
    val exists: Boolean = false,
    val revision: String = "missing",
)
