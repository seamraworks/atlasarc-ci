package io.atlasarc.evaluator

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.atlasarc.archunit.ArchUnitGovernanceEvidence
import io.atlasarc.archunit.JvmEvidenceRoot
import io.atlasarc.evaluation.GovernanceEvaluationInput
import io.atlasarc.governance.GovernanceBackend
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

class EvaluatorAcquisitionException(
    val analysisSourceId: String,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

fun interface EvidenceAcquirer {
    fun acquire(config: EvaluatorSourceConfig, repositoryRoot: Path): GovernanceEvaluationInput
}

class HeadlessEvidenceAcquirer : EvidenceAcquirer {
    override fun acquire(config: EvaluatorSourceConfig, repositoryRoot: Path): GovernanceEvaluationInput =
        when (config.backend) {
            GovernanceBackend.JVM_BYTECODE -> acquireJvm(config, repositoryRoot)
            GovernanceBackend.TYPESCRIPT_ARTIFACT -> acquireTypeScript(config, repositoryRoot)
        }

    private fun acquireJvm(config: EvaluatorSourceConfig, repositoryRoot: Path): GovernanceEvaluationInput {
        val classRoots = config.classDirectories.map { JvmEvidenceRoot(resolve(repositoryRoot, it.path), it.module) }
        classRoots.forEach { root ->
            if (!root.path.isDirectory()) {
                throw EvaluatorAcquisitionException(config.id, "A configured JVM class directory is missing: ${portable(repositoryRoot, root.path)}")
            }
        }
        val latestClass = latestModified(classRoots.map(JvmEvidenceRoot::path), setOf("class"))
            ?: throw EvaluatorAcquisitionException(
                config.id,
                "No .class files were found in the configured JVM class directories.",
            )
        val sourceRoots = config.sourceRoots.map { JvmEvidenceRoot(resolve(repositoryRoot, it.path), it.module) }
        sourceRoots.forEach { root ->
            if (!root.path.isDirectory()) {
                throw EvaluatorAcquisitionException(config.id, "A configured JVM source root is missing: ${portable(repositoryRoot, root.path)}")
            }
        }
        val classes = try {
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPaths(classRoots.map(JvmEvidenceRoot::path))
        } catch (exception: Exception) {
            throw EvaluatorAcquisitionException(config.id, "Could not import configured JVM class output.", exception)
        }
        if (classes.toList().isEmpty()) {
            throw EvaluatorAcquisitionException(config.id, "Configured JVM class output contains no importable classes.")
        }
        val latestSource = latestModified(sourceRoots.map(JvmEvidenceRoot::path), setOf("java", "kt"))
        val stale = latestSource != null && latestSource > latestClass
        return try {
            ArchUnitGovernanceEvidence().build(
                classes = classes,
                sourceRoots = sourceRoots,
                analysisSourceId = config.id,
                repositoryRoot = repositoryRoot,
                classRoots = classRoots,
                fresh = !stale,
                freshnessDiagnostic = if (stale) {
                    "Compiled JVM output is older than current Java/Kotlin source; rebuild it before evaluation."
                } else {
                    null
                },
            )
        } catch (exception: Exception) {
            throw EvaluatorAcquisitionException(config.id, "Could not extract JVM dependency evidence.", exception)
        }
    }

    private fun acquireTypeScript(config: EvaluatorSourceConfig, repositoryRoot: Path): GovernanceEvaluationInput {
        val sourceRoot = resolve(repositoryRoot, config.root)
        val dependencyCruiser = resolve(repositoryRoot, requireNotNull(config.dependencyCruiserJson))
        return try {
            TypeScriptGovernanceEvidence().build(
                analysisSourceId = config.id,
                repositoryRoot = repositoryRoot,
                sourceRoot = sourceRoot,
                dependencyCruiserJson = dependencyCruiser,
            )
        } catch (exception: EvaluatorAcquisitionException) {
            throw exception
        } catch (exception: Exception) {
            throw EvaluatorAcquisitionException(config.id, "Could not acquire TypeScript dependency evidence.", exception)
        }
    }

    private fun resolve(root: Path, path: String): Path {
        val candidate = Path.of(path)
        return (if (candidate.isAbsolute) candidate else root.resolve(candidate)).toAbsolutePath().normalize()
    }

    private fun portable(root: Path, path: Path): String = runCatching {
        root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/')
    }.getOrDefault(path.fileName?.toString() ?: "configured path")

    private fun latestModified(roots: List<Path>, extensions: Set<String>): Long? = roots.asSequence()
        .filter(Path::isDirectory)
        .flatMap { root ->
            Files.walk(root).use { stream ->
                stream.filter { it.isRegularFile() && it.extension.lowercase() in extensions }
                    .map { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrNull() }
                    .filter { it != null }
                    .map { it!! }
                    .toList()
                    .asSequence()
            }
        }
        .maxOrNull()
}
