package io.atlasarc.archunit.internal

import com.tngtech.archunit.core.domain.Dependency
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaCodeUnit
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaConstructorCall
import com.tngtech.archunit.core.domain.JavaConstructorReference
import com.tngtech.archunit.core.domain.JavaFieldAccess
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.domain.JavaMethodReference

/**
 * Extracts [RichDependencyRecord]s from ArchUnit's imported [JavaClasses].
 *
 * Two passes per class:
 * 1. **Access pass** — executable accesses (method calls, constructor calls, field accesses)
 *    where member-level governance identities are semantically meaningful.
 * 2. **Structural pass** — broad dependency objects (inheritance, field types, generics,
 *    annotations, throws declarations, instanceof, class literals).
 *
 * Results are filtered to project-internal dependencies and deduplicated before being returned.
 * Same-package, different-class records are retained so the converter can expose them as
 * Graph-only package-internals evidence without turning them into package-level edges.
 */
/** Repository governance is applied after acquisition; this extractor emits structural evidence. */
internal class RichDependencyExtractor(
    private val sourceIndex: SourceIndex,
    private val classOwnership: CompiledClassOwnershipIndex = CompiledClassOwnershipIndex.EMPTY,
    private val bytecodeFilter: KotlinBytecodeFilter = KotlinBytecodeFilter(),
) {

    fun extract(classes: JavaClasses): List<RichDependencyRecord> {
        // Derive project-internal packages directly from the imported bytecode.
        // Using the source index here is unreliable: source root discovery can miss
        // generated code, Kotlin multi-file facades, or unresolved VFS roots — any
        // of which silently empties the set and drops all cross-package edges.
        // The imported JavaClasses are always consistent with the compiler output roots.
        val projectPackages = classes
            .map { it.packageName }
            .filter { it.isNotBlank() }
            .toSet()

        val records = mutableListOf<RichDependencyRecord>()

        for (origin in classes) {
            if (bytecodeFilter.ignoreClass(origin)) continue

            records += extractAccesses(origin, projectPackages)
            records += extractStructuralDependencies(origin, projectPackages)
        }

        val filtered = records.filterNot { bytecodeFilter.ignoreDependency(it) }
        return deduplicate(filtered)
    }

    // ── Pass 1: executable accesses ───────────────────────────────────────

    private fun extractAccesses(
        origin: JavaClass,
        projectPackages: Set<String>,
    ): List<RichDependencyRecord> {
        val records = mutableListOf<RichDependencyRecord>()

        for (access in origin.accessesFromSelf) {
            val targetOwner = access.targetOwner       // JavaClass owning the accessed member
            if (!isProjectInternal(origin, targetOwner, projectPackages)) continue

            val kind = dependencyKind(access) ?: continue

            val location     = access.sourceCodeLocation
            val sourceFile   = location.sourceFileName
            val line         = location.lineNumber.takeIf { it > 0 }
            val originModuleRef = moduleRefFor(origin, sourceFile)
            val sourcePath   = sourceIndex.resolveSourceFile(origin.packageName, sourceFile, originModuleRef.stableName)
            val targetSourceFile = targetOwner.sourceCodeLocation.sourceFileName
            val targetModuleRef = moduleRefFor(targetOwner, targetSourceFile)

            val record = RichDependencyRecord(
                originClass    = origin.name,
                originPackage  = origin.packageName,
                originMember   = access.origin.toMemberRef(),
                sourceFileName = sourceFile,
                sourceFilePath = sourcePath,
                originModuleRef = originModuleRef,
                line           = line,
                targetClass    = targetOwner.name,
                targetPackage  = targetOwner.packageName,
                targetMember   = access.target.toMemberRefOrNull(),
                kind           = kind,
                targetSourceFilePath = sourceIndex.resolveSourceFile(
                    targetOwner.packageName,
                    targetSourceFile,
                    targetModuleRef.stableName,
                ).ifBlank { null },
                targetModuleRef = targetModuleRef,
            )

            records += record
            records += extractTargetSignatureDependencies(origin, access, projectPackages)
        }

        return records
    }

    private fun extractTargetSignatureDependencies(
        origin: JavaClass,
        access: com.tngtech.archunit.core.domain.JavaAccess<*>,
        projectPackages: Set<String>,
    ): List<RichDependencyRecord> {
        val location = access.sourceCodeLocation
        val sourceFile = location.sourceFileName
        if (!sourceFile.hasSupportedSignatureDependencyOrigin()) return emptyList()
        if (access.targetOwner.name.contains("\$DefaultImpls")) return emptyList()

        val targetCodeUnit = access.target.resolveMember().orElse(null) as? JavaCodeUnit ?: return emptyList()
        val signatureTypes = (targetCodeUnit.rawParameterTypes + targetCodeUnit.rawReturnType)
            .filter { isProjectInternal(origin, it, projectPackages) }
            .filter { it.name != access.targetOwner.name }
            .distinctBy { it.name }

        if (signatureTypes.isEmpty()) return emptyList()

        val line = location.lineNumber.takeIf { it > 0 }
        val originModuleRef = moduleRefFor(origin, sourceFile)
        val sourcePath = sourceIndex.resolveSourceFile(origin.packageName, sourceFile, originModuleRef.stableName)

        return signatureTypes.map { targetType ->
            val targetSourceFile = targetType.sourceCodeLocation.sourceFileName
            val targetModuleRef = moduleRefFor(targetType, targetSourceFile)
            RichDependencyRecord(
                originClass = origin.name,
                originPackage = origin.packageName,
                originMember = access.origin.toMemberRef(),
                sourceFileName = sourceFile,
                sourceFilePath = sourcePath,
                originModuleRef = originModuleRef,
                line = line,
                targetClass = targetType.name,
                targetPackage = targetType.packageName,
                targetMember = null,
                kind = DependencyKind.STRUCTURAL,
                targetSourceFilePath = sourceIndex.resolveSourceFile(
                    targetType.packageName,
                    targetSourceFile,
                    targetModuleRef.stableName,
                ).ifBlank { null },
                targetModuleRef = targetModuleRef,
                attributionConfidence = DependencyAttributionConfidence.INFERRED,
            )
        }
    }

    // ── Pass 2: structural dependencies ──────────────────────────────────

    private fun extractStructuralDependencies(
        origin: JavaClass,
        projectPackages: Set<String>,
    ): List<RichDependencyRecord> {
        val concreteAccessDependencies = origin.accessesFromSelf
            .filter { dependencyKind(it) != null }
            .flatMap { access -> access.convertTo(Dependency::class.java) }
            .toSet()
        return origin.directDependenciesFromSelf
            .filterNot(concreteAccessDependencies::contains)
            .mapNotNull { dep ->
                val target = dep.targetClass
                if (!isProjectInternal(origin, target, projectPackages)) return@mapNotNull null

                val location     = dep.sourceCodeLocation
                val sourceFile   = location.sourceFileName
                val line         = location.lineNumber.takeIf { it > 0 }
                val originModuleRef = moduleRefFor(origin, sourceFile)
                val sourcePath   = sourceIndex.resolveSourceFile(origin.packageName, sourceFile, originModuleRef.stableName)
                val targetSourceFile = target.sourceCodeLocation.sourceFileName
                val targetModuleRef = moduleRefFor(target, targetSourceFile)

                RichDependencyRecord(
                    originClass    = origin.name,
                    originPackage  = origin.packageName,
                    originMember   = null,
                    sourceFileName = sourceFile,
                    sourceFilePath = sourcePath,
                    originModuleRef = originModuleRef,
                    line           = line,
                    targetClass    = target.name,
                    targetPackage  = target.packageName,
                    targetMember   = null,
                    kind           = DependencyKind.STRUCTURAL,
                    targetSourceFilePath = sourceIndex.resolveSourceFile(
                        target.packageName,
                        targetSourceFile,
                        targetModuleRef.stableName,
                    ).ifBlank { null },
                    targetModuleRef = targetModuleRef,
                )
            }
    }

    // ── Deduplication ─────────────────────────────────────────────────────

    /** Data-class equality covers the full observed/inferred tuple, including location and confidence. */
    private fun deduplicate(records: List<RichDependencyRecord>): List<RichDependencyRecord> =
        records.distinct()

    private fun moduleRefFor(javaClass: JavaClass, sourceFileName: String?): JvmModuleRef =
        classOwnership.moduleRefFor(javaClass.name)
            ?: sourceIndex.moduleRefFor(javaClass.packageName, sourceFileName)
            ?: sourceIndex.uniqueModuleRefForPackage(javaClass.packageName)
            ?: JvmModuleRef.Unattributed

    // ── Filter helper ─────────────────────────────────────────────────────

    private fun isProjectInternal(
        origin: JavaClass,
        target: JavaClass,
        projectPackages: Set<String>,
    ): Boolean {
        if (origin.name == target.name) return false
        return target.packageName in projectPackages
    }

    private fun dependencyKind(
        access: com.tngtech.archunit.core.domain.JavaAccess<*>,
    ): DependencyKind? = when (access) {
        is JavaMethodCall -> DependencyKind.METHOD_CALL
        is JavaMethodReference -> DependencyKind.METHOD_CALL
        is JavaConstructorCall -> DependencyKind.CONSTRUCTOR_CALL
        is JavaConstructorReference -> DependencyKind.CONSTRUCTOR_CALL
        is JavaFieldAccess -> DependencyKind.FIELD_ACCESS
        else -> null
    }
}

// ── Extension helpers ─────────────────────────────────────────────────────────

/**
 * Converts a [com.tngtech.archunit.core.domain.JavaMember] to a [MemberRef].
 * Call on `access.origin` (always a resolved member).
 */
private fun com.tngtech.archunit.core.domain.JavaMember.toMemberRef(): MemberRef =
    MemberRef(
        ownerClass  = owner.name,
        name        = name,
        descriptor  = runCatching { descriptor }.getOrNull(),
    )

/**
 * Converts an [com.tngtech.archunit.core.domain.AccessTarget] to a [MemberRef],
 * or null if target resolution failed (unresolved class).
 */
private fun com.tngtech.archunit.core.domain.AccessTarget.toMemberRefOrNull(): MemberRef? =
    runCatching {
        MemberRef(
            ownerClass  = owner.name,
            name        = name,
            descriptor  = null,  // AccessTarget does not expose descriptor in ArchUnit 1.4.2
        )
    }.getOrNull()

private fun String.hasSupportedSignatureDependencyOrigin(): Boolean =
    endsWith(".java", ignoreCase = true) || endsWith(".kt", ignoreCase = true)
