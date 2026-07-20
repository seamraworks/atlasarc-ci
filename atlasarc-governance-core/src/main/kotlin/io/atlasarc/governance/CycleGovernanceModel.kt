@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.atlasarc.governance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Required

const val CYCLE_GOVERNANCE_SCHEMA_VERSION: Int = 1
const val CYCLE_GOVERNANCE_SCHEMA_URI: String =
    "https://atlasarc.io/schemas/cycle-governance-v1.schema.json"
const val CYCLE_GOVERNANCE_RELATIVE_PATH: String = ".atlasarc/governance/cycles.json"

@Serializable
data class CycleGovernanceDocument(
    @Required
    @SerialName("\$schema")
    val schema: String = CYCLE_GOVERNANCE_SCHEMA_URI,
    @Required
    val schemaVersion: Int = CYCLE_GOVERNANCE_SCHEMA_VERSION,
    @Required
    val records: Map<String, CycleGovernanceRecord> = emptyMap(),
)

@Serializable
data class CycleGovernanceRecord(
    val analysisSource: GovernanceAnalysisSource,
    val scope: GovernanceScope,
    val ownerSide: GovernanceOwnerSide,
    val source: GovernanceIdentity,
    val target: GovernanceIdentity,
    val dependencyKind: GovernanceDependencyKind? = null,
    @Required val referenceIds: Set<String> = emptySet(),
    val kind: CycleGovernanceKind,
    val reason: String,
    val ticket: String? = null,
    val display: GovernanceDisplay? = null,
)

@Serializable
data class GovernanceAnalysisSource(
    val id: String,
    val backend: GovernanceBackend,
    val language: GovernanceLanguage,
)

@Serializable
data class GovernanceIdentity(
    val architectureUnit: String,
    val type: String? = null,
    val sourceFile: String? = null,
    val member: GovernanceMemberIdentity? = null,
    /** Stable backend module identity; required to disambiguate split JVM packages when available. */
    val module: String? = null,
)

@Serializable
data class GovernanceMemberIdentity(
    val name: String,
    val descriptor: String? = null,
)

@Serializable
data class GovernanceDisplay(
    val source: String? = null,
    val target: String? = null,
    val sourcePath: String? = null,
    val targetPath: String? = null,
)

@Serializable
enum class GovernanceBackend {
    @SerialName("jvm-bytecode")
    JVM_BYTECODE,

    @SerialName("typescript-artifact")
    TYPESCRIPT_ARTIFACT,
}

@Serializable
enum class GovernanceLanguage {
    @SerialName("java")
    JAVA,

    @SerialName("kotlin")
    KOTLIN,

    @SerialName("typescript")
    TYPESCRIPT,
}

@Serializable
enum class GovernanceScope {
    @SerialName("package")
    PACKAGE,

    @SerialName("type")
    TYPE,

    @SerialName("member")
    MEMBER,

    @SerialName("source-folder")
    SOURCE_FOLDER,

    @SerialName("source-file")
    SOURCE_FILE,

    @SerialName("reference")
    REFERENCE,
}

@Serializable
enum class GovernanceOwnerSide {
    @SerialName("source")
    SOURCE,

    @SerialName("target")
    TARGET,
}

@Serializable
enum class CycleGovernanceKind {
    @SerialName("INTENTIONAL")
    INTENTIONAL,

    @SerialName("DEBT")
    DEBT,
}

@Serializable
enum class GovernanceDependencyKind {
    @SerialName("method-call")
    METHOD_CALL,

    @SerialName("constructor-call")
    CONSTRUCTOR_CALL,

    @SerialName("field-access")
    FIELD_ACCESS,

    @SerialName("structural")
    STRUCTURAL,

    @SerialName("runtime-import")
    RUNTIME_IMPORT,

    @SerialName("type-only-import")
    TYPE_ONLY_IMPORT,

    @SerialName("dynamic-import")
    DYNAMIC_IMPORT,

    @SerialName("re-export")
    RE_EXPORT,

    @SerialName("unknown")
    UNKNOWN,
}

@Serializable
enum class GovernanceRecordStatus {
    @SerialName("active") ACTIVE,
    @SerialName("resolved") RESOLVED,
    @SerialName("not-in-analysis") NOT_IN_ANALYSIS,
    @SerialName("missing-source") MISSING_SOURCE,
    @SerialName("missing-target") MISSING_TARGET,
    @SerialName("partial") PARTIAL,
    @SerialName("ambiguous") AMBIGUOUS,
    @SerialName("unsupported") UNSUPPORTED,
    @SerialName("invalid") INVALID,
}

@Serializable
enum class GovernanceIssueSeverity {
    @SerialName("error") ERROR,
    @SerialName("warning") WARNING,
}

data class GovernanceValidationIssue(
    val code: String,
    val message: String,
    val severity: GovernanceIssueSeverity = GovernanceIssueSeverity.ERROR,
    val recordId: String? = null,
    val field: String? = null,
)
