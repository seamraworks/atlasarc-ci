package io.atlasarc.archunit.internal

/**
 * Internal model for a single dependency relationship between two classes,
 * richer than the final portable evidence reference to support stable member-level identities and precise
 * deduplication before projection to the raw domain model.
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

/**
 * Deduplication key for [RichDependencyRecord]s.
 *
 * Two records with the same identity are collapsed into one.
 */
internal data class RichDependencyIdentity(
    val originClass: String,
    val originModuleRef: JvmModuleRef,
    val originMember: MemberRef?,
    val targetClass: String,
    val targetModuleRef: JvmModuleRef,
    val targetMember: MemberRef?,
    val kind: DependencyKind,
    val line: Int?,
)

/**
 * Projection key used to collapse equivalent [RichDependencyRecord] values before portable mapping.
 *
 * Intentionally **member-blind**: an access record (e.g. a method call, member=`foo`) and the
 * structural "shadow" records the structural pass emits for the same reference (member=null) share
 * one identity and collapse into a single example — otherwise a single call would be counted
 * multiple times. The distinct member *names* that collapse into an example are preserved on
 * the portable evidence so the evaluator can still apply per-reference repository governance with
 * red-wins across them.
 */
internal data class ExampleIdentity(
    val sourceFile: String,
    val sourceClass: String?,
    val sourceModuleRef: JvmModuleRef,
    val targetClass: String,
    val targetModuleRef: JvmModuleRef,
    val line: Int?,
)
