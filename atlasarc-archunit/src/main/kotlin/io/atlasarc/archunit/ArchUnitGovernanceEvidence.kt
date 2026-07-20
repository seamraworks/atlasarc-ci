package io.atlasarc.archunit

import com.tngtech.archunit.core.domain.JavaClasses
import io.atlasarc.archunit.internal.CompiledClassOwnershipIndex
import io.atlasarc.archunit.internal.ExampleIdentity
import io.atlasarc.archunit.internal.JvmModuleRef
import io.atlasarc.archunit.internal.ModuleClassRoot
import io.atlasarc.archunit.internal.RichDependencyExtractor
import io.atlasarc.archunit.internal.RichDependencyRecord
import io.atlasarc.archunit.internal.SourceIndex
import io.atlasarc.archunit.internal.SourceRoot
import io.atlasarc.evaluation.GovernanceEvaluationInput
import io.atlasarc.evaluation.GovernanceEvaluationIssue
import io.atlasarc.governance.GovernanceBackend
import io.atlasarc.governance.GovernanceDependencyKind
import io.atlasarc.governance.GovernanceEvidenceNode
import io.atlasarc.governance.GovernanceEvidenceReference
import io.atlasarc.governance.GovernanceEvidenceSnapshot
import io.atlasarc.governance.GovernanceEvidenceSource
import io.atlasarc.governance.GovernanceIdentity
import io.atlasarc.governance.GovernanceIds
import io.atlasarc.governance.GovernanceIssueSeverity
import io.atlasarc.governance.GovernanceLanguage
import io.atlasarc.governance.GovernanceMemberIdentity
import io.atlasarc.governance.GovernancePaths
import io.atlasarc.governance.GovernanceScope
import java.nio.file.Path

data class JvmEvidenceRoot(val path: Path, val module: String? = null)

/** Converts ArchUnit-imported project classes into AtlasArc.io's portable evidence contract. */
class ArchUnitGovernanceEvidence {
    fun build(
        classes: JavaClasses,
        sourceRoots: List<JvmEvidenceRoot>,
        analysisSourceId: String,
        repositoryRoot: Path,
        classRoots: List<JvmEvidenceRoot> = emptyList(),
        fresh: Boolean = true,
        freshnessDiagnostic: String? = null,
    ): GovernanceEvaluationInput {
        require(analysisSourceId.isNotBlank()) { "The analysis source ID must not be blank." }
        require(classes.toList().isNotEmpty()) { "The imported ArchUnit class set must not be empty." }
        validateRootModes(sourceRoots + classRoots)

        val sourceIndex = SourceIndex.build(sourceRoots.map { SourceRoot(it.path, it.module.orEmpty()) })
        val ownership = if (classRoots.isEmpty()) {
            CompiledClassOwnershipIndex.EMPTY
        } else {
            CompiledClassOwnershipIndex.build(classRoots.map { ModuleClassRoot(it.path, it.module.orEmpty()) })
        }
        val records = RichDependencyExtractor(sourceIndex, ownership).extract(classes)
        val attributionIssues = records.asSequence()
            .flatMap { sequenceOf(it.originPackage to it.originModuleRef, it.targetPackage to it.targetModuleRef) }
            .filter { it.second == JvmModuleRef.Unattributed }
            .map { it.first }
            .distinct()
            .sorted()
            .map { packageName ->
                GovernanceEvaluationIssue(
                    code = "jvm-module-attribution",
                    message = "AtlasArc.io could not attribute JVM package '$packageName' to one stable module.",
                    severity = GovernanceIssueSeverity.ERROR,
                    analysisSourceId = analysisSourceId,
                )
            }
            .toList()
        val usable = records.filter {
            it.originModuleRef != JvmModuleRef.Unattributed && it.targetModuleRef != JvmModuleRef.Unattributed
        }
        val references = toReferences(usable, sourceIndex, analysisSourceId, repositoryRoot)
        val nodes = mutableListOf<GovernanceEvidenceNode>()
        references.forEach { reference ->
            nodes += GovernanceEvidenceNode(analysisSourceId, GovernanceBackend.JVM_BYTECODE, reference.sourceLanguage, reference.source)
            nodes += GovernanceEvidenceNode(analysisSourceId, GovernanceBackend.JVM_BYTECODE, reference.targetLanguage, reference.target)
        }
        sourceIndex.allFiles.forEach { file ->
            val identity = GovernanceIdentity(file.packageName, module = file.module.takeIf(String::isNotBlank))
            nodes += GovernanceEvidenceNode(analysisSourceId, GovernanceBackend.JVM_BYTECODE, languageOf(file.fileName), identity)
        }
        classes.forEach { javaClass ->
            val sourceFile = javaClass.sourceCodeLocation.sourceFileName
            val moduleRef = ownership.moduleRefFor(javaClass.name)
                ?: sourceIndex.moduleRefFor(javaClass.packageName, sourceFile)
                ?: sourceIndex.uniqueModuleRefForPackage(javaClass.packageName)
                ?: JvmModuleRef.Unattributed
            if (moduleRef != JvmModuleRef.Unattributed) {
                nodes += GovernanceEvidenceNode(
                    analysisSourceId,
                    GovernanceBackend.JVM_BYTECODE,
                    languageOf(sourceFile),
                    GovernanceIdentity(javaClass.packageName, type = javaClass.name, module = moduleRef.stableName),
                )
            }
        }

        val includedModules = (sourceRoots + classRoots).mapNotNullTo(sortedSetOf()) { it.module }
        return GovernanceEvaluationInput(
            evidence = GovernanceEvidenceSnapshot(
                sources = listOf(
                    GovernanceEvidenceSource(
                        id = analysisSourceId,
                        backend = GovernanceBackend.JVM_BYTECODE,
                        languages = setOf(GovernanceLanguage.JAVA, GovernanceLanguage.KOTLIN),
                        supportedScopes = setOf(
                            GovernanceScope.PACKAGE,
                            GovernanceScope.TYPE,
                            GovernanceScope.MEMBER,
                            GovernanceScope.REFERENCE,
                        ),
                        fresh = fresh,
                        freshnessDiagnostic = freshnessDiagnostic,
                        repositoryComplete = true,
                        includedJvmModules = includedModules,
                    ),
                ),
                nodes = nodes.distinct().sortedBy(::nodeKey),
                references = references,
                caseSensitive = !System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
                evaluationComplete = attributionIssues.isEmpty(),
            ),
            issues = attributionIssues,
        )
    }

    private fun toReferences(
        records: List<RichDependencyRecord>,
        sourceIndex: SourceIndex,
        analysisSourceId: String,
        repositoryRoot: Path,
    ): List<GovernanceEvidenceReference> = records
        .filter { it.originPackage.isNotBlank() && it.targetPackage.isNotBlank() }
        .groupBy { record ->
            ExampleIdentity(
                sourceFile = record.sourceFilePath.ifBlank { record.sourceFileName.orEmpty() },
                sourceClass = record.originClass,
                sourceModuleRef = record.originModuleRef,
                targetClass = record.targetClass,
                targetModuleRef = record.targetModuleRef,
                line = record.line,
            )
        }
        .values
        .flatMap { collapsed ->
            val first = collapsed.first()
            val sourceLanguage = languageOf(first.sourceFilePath.ifBlank { first.sourceFileName.orEmpty() })
            val targetLanguage = languageOf(first.targetSourceFilePath.orEmpty())
            val sourceFile = repositoryPath(first.sourceFilePath.ifBlank { first.sourceFileName.orEmpty() }, repositoryRoot)
            val targetFile = first.targetSourceFilePath?.let { repositoryPath(it, repositoryRoot) }
            val origins = collapsed.mapNotNullTo(linkedSetOf()) { it.originMember?.let { member ->
                GovernanceMemberIdentity(member.name, member.descriptor)
            } }.takeIf(Set<GovernanceMemberIdentity>::isNotEmpty)?.toList() ?: listOf(null)
            val targets = collapsed.mapNotNullTo(linkedSetOf()) { it.targetMember?.let { member ->
                GovernanceMemberIdentity(member.name, member.descriptor)
            } }.takeIf(Set<GovernanceMemberIdentity>::isNotEmpty)?.toList() ?: listOf(null)
            val kinds = collapsed.mapTo(linkedSetOf()) { it.kind.toGovernanceKind() }.toList()
            origins.flatMap { originMember ->
                targets.flatMap { targetMember ->
                    kinds.map { kind ->
                        val source = GovernanceIdentity(
                            architectureUnit = first.originPackage,
                            type = first.originClass,
                            sourceFile = sourceFile,
                            member = originMember,
                            module = first.originModuleRef.stableName,
                        )
                        val target = GovernanceIdentity(
                            architectureUnit = first.targetPackage,
                            type = first.targetClass,
                            sourceFile = targetFile,
                            member = targetMember,
                            module = first.targetModuleRef.stableName,
                        )
                        GovernanceEvidenceReference(
                            id = GovernanceIds.referenceId(
                                analysisSourceId,
                                GovernanceBackend.JVM_BYTECODE,
                                sourceLanguage,
                                targetLanguage,
                                source,
                                target,
                                kind,
                            ),
                            analysisSourceId = analysisSourceId,
                            backend = GovernanceBackend.JVM_BYTECODE,
                            sourceLanguage = sourceLanguage,
                            targetLanguage = targetLanguage,
                            source = source,
                            target = target,
                            dependencyKind = kind,
                        )
                    }
                }
            }
        }
        .distinctBy(GovernanceEvidenceReference::id)
        .sortedBy(GovernanceEvidenceReference::id)

    private fun repositoryPath(pathText: String, repositoryRoot: Path): String? {
        if (pathText.isBlank()) return null
        val path = runCatching { Path.of(pathText) }.getOrNull() ?: return null
        val relative = if (path.isAbsolute) {
            val absolute = path.toAbsolutePath().normalize()
            val root = repositoryRoot.toAbsolutePath().normalize()
            if (!absolute.startsWith(root)) return null
            root.relativize(absolute).toString()
        } else {
            pathText
        }
        return GovernancePaths.normalizeRepositoryRelative(relative)
    }

    private fun validateRootModes(roots: List<JvmEvidenceRoot>) {
        val hasNamed = roots.any { !it.module.isNullOrBlank() }
        val hasModuleless = roots.any { it.module.isNullOrBlank() }
        require(!(hasNamed && hasModuleless)) {
            "Do not mix named and module-less JVM roots. Assign every root a stable module, or omit every module."
        }
    }

    private fun languageOf(path: String): GovernanceLanguage =
        if (path.endsWith(".kt", ignoreCase = true)) GovernanceLanguage.KOTLIN else GovernanceLanguage.JAVA

    private fun io.atlasarc.archunit.internal.DependencyKind.toGovernanceKind(): GovernanceDependencyKind = when (this) {
        io.atlasarc.archunit.internal.DependencyKind.METHOD_CALL -> GovernanceDependencyKind.METHOD_CALL
        io.atlasarc.archunit.internal.DependencyKind.CONSTRUCTOR_CALL -> GovernanceDependencyKind.CONSTRUCTOR_CALL
        io.atlasarc.archunit.internal.DependencyKind.FIELD_ACCESS -> GovernanceDependencyKind.FIELD_ACCESS
        io.atlasarc.archunit.internal.DependencyKind.STRUCTURAL -> GovernanceDependencyKind.STRUCTURAL
    }

    private fun nodeKey(node: GovernanceEvidenceNode): String = listOf(
        node.analysisSourceId,
        node.language.name,
        node.identity.architectureUnit,
        node.identity.type.orEmpty(),
        node.identity.sourceFile.orEmpty(),
        node.identity.member?.name.orEmpty(),
        node.identity.member?.descriptor.orEmpty(),
        node.identity.module.orEmpty(),
    ).joinToString("|")
}
