package io.atlasarc.archunit.internal

/**
 * Canonical acquisition tuple for one dependency relationship between two classes. It retains
 * source location and attribution confidence while the portable governance projection deliberately
 * derives a line-independent semantic identity.
 */
internal data class RichDependencyRecord(
    val originClass: String,
    val originPackage: String,
    /** Null for class-level structural dependencies. */
    val originMember: MemberRef?,
    /** Source file name as reported by bytecode (e.g. "OrderService.kt"). */
    val sourceFileName: String?,
    /** Absolute source path resolved via [SourceIndex]; empty string if unresolved. */
    val sourceFilePath: String,
    /** Named, legitimately module-less, or failed/ambiguous ownership of the origin class. */
    val originModuleRef: JvmModuleRef = JvmModuleRef.Unattributed,
    val line: Int?,
    val targetClass: String,
    val targetPackage: String,
    /** Null for structural dependencies or when target member is not captured. */
    val targetMember: MemberRef?,
    val kind: DependencyKind,
    /** Target source path resolved from bytecode/source index when available. */
    val targetSourceFilePath: String? = null,
    /** Named, legitimately module-less, or failed/ambiguous ownership of the target class. */
    val targetModuleRef: JvmModuleRef = JvmModuleRef.Unattributed,
    /** Whether acquisition observed this tuple directly or inferred it from surrounding bytecode. */
    val attributionConfidence: DependencyAttributionConfidence = DependencyAttributionConfidence.OBSERVED,
) {
    val originModule: String? get() = originModuleRef.stableName
    val targetModule: String? get() = targetModuleRef.stableName
}

internal data class MemberRef(
    val ownerClass: String,
    val name: String,
    /** JVM descriptor string, e.g. "(Ljava/lang/String;)V". Null for fields. */
    val descriptor: String?,
)

internal enum class DependencyKind {
    METHOD_CALL,
    CONSTRUCTOR_CALL,
    FIELD_ACCESS,
    /** Inheritance, field types, generics, annotations, throws, instanceof, class literals. */
    STRUCTURAL,
}

internal enum class DependencyAttributionConfidence {
    OBSERVED,
    INFERRED,
}
