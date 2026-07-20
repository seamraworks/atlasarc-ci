package io.atlasarc.evaluator

import io.atlasarc.evaluation.GovernanceEvaluationInput
import io.atlasarc.governance.GovernanceBackend
import io.atlasarc.governance.GovernanceDependencyKind
import io.atlasarc.governance.GovernanceEvidenceNode
import io.atlasarc.governance.GovernanceEvidenceReference
import io.atlasarc.governance.GovernanceEvidenceSnapshot
import io.atlasarc.governance.GovernanceEvidenceSource
import io.atlasarc.governance.GovernanceIdentity
import io.atlasarc.governance.GovernanceIds
import io.atlasarc.governance.GovernanceLanguage
import io.atlasarc.governance.GovernancePaths
import io.atlasarc.governance.GovernanceScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/** Converts dependency-cruiser JSON into the portable AtlasArc evidence contract. */
class TypeScriptGovernanceEvidence {
    fun build(
        analysisSourceId: String,
        repositoryRoot: Path,
        sourceRoot: Path,
        dependencyCruiserJson: Path,
    ): GovernanceEvaluationInput {
        require(analysisSourceId.isNotBlank()) { "The analysis source ID must not be blank." }
        if (!dependencyCruiserJson.isRegularFile()) {
            throw EvaluatorAcquisitionException(
                analysisSourceId,
                "The configured dependency-cruiser JSON is missing: ${repositoryPath(dependencyCruiserJson, repositoryRoot) ?: dependencyCruiserJson.fileName}",
            )
        }
        val graph = readGraph(analysisSourceId, dependencyCruiserJson)
        val projectRoot = graph.baseDir?.let { raw ->
            val path = Path.of(raw)
            (if (path.isAbsolute) path else repositoryRoot.resolve(path)).toAbsolutePath().normalize()
        } ?: repositoryRoot.toAbsolutePath().normalize()
        val modules = graph.modules.mapNotNull { module ->
            resolveArtifactPath(module.source, projectRoot, sourceRoot)
                .takeIf { isInRepository(it, repositoryRoot) }
                ?.takeUnless(::isNodeModule)
                ?.let { ModuleFile(module, it, requireNotNull(repositoryPath(it, repositoryRoot))) }
        }.distinctBy(ModuleFile::repositoryPath).sortedBy(ModuleFile::repositoryPath)
        if (modules.isEmpty()) {
            throw EvaluatorAcquisitionException(
                analysisSourceId,
                "The dependency-cruiser JSON contains no in-repository TypeScript modules.",
            )
        }
        val byPath = modules.associateBy(ModuleFile::repositoryPath)
        val references = modules.flatMap { (module, sourcePath, sourceRepositoryPath) ->
            module.dependencies.flatMap dependency@{ dependency ->
                val resolved = dependency.resolved?.takeIf(String::isNotBlank) ?: return@dependency emptyList()
                val targetPath = resolveArtifactPath(resolved, projectRoot, sourceRoot)
                val targetRepositoryPath = repositoryPath(targetPath, repositoryRoot) ?: return@dependency emptyList()
                if (isNodeModule(targetPath) || targetRepositoryPath !in byPath) return@dependency emptyList()
                val sourceIdentity = identity(sourceRepositoryPath)
                val targetIdentity = identity(targetRepositoryPath)
                dependencyKinds(dependency).map { kind ->
                    GovernanceEvidenceReference(
                        id = GovernanceIds.referenceId(
                            analysisSourceId,
                            GovernanceBackend.TYPESCRIPT_ARTIFACT,
                            GovernanceLanguage.TYPESCRIPT,
                            GovernanceLanguage.TYPESCRIPT,
                            sourceIdentity,
                            targetIdentity,
                            kind,
                        ),
                        analysisSourceId = analysisSourceId,
                        backend = GovernanceBackend.TYPESCRIPT_ARTIFACT,
                        sourceLanguage = GovernanceLanguage.TYPESCRIPT,
                        targetLanguage = GovernanceLanguage.TYPESCRIPT,
                        source = sourceIdentity,
                        target = targetIdentity,
                        dependencyKind = kind,
                    )
                }
            }
        }.distinctBy(GovernanceEvidenceReference::id).sortedBy(GovernanceEvidenceReference::id)
        val nodes = (modules.map { file ->
            GovernanceEvidenceNode(
                analysisSourceId,
                GovernanceBackend.TYPESCRIPT_ARTIFACT,
                GovernanceLanguage.TYPESCRIPT,
                identity(file.repositoryPath),
            )
        } + references.flatMap { reference ->
            listOf(
                GovernanceEvidenceNode(analysisSourceId, GovernanceBackend.TYPESCRIPT_ARTIFACT, GovernanceLanguage.TYPESCRIPT, reference.source),
                GovernanceEvidenceNode(analysisSourceId, GovernanceBackend.TYPESCRIPT_ARTIFACT, GovernanceLanguage.TYPESCRIPT, reference.target),
            )
        }).distinct().sortedBy { "${it.identity.architectureUnit}|${it.identity.sourceFile.orEmpty()}" }
        val newestSource = modules.mapNotNull { file -> modified(file.absolutePath) }.maxOrNull()
        val graphModified = modified(dependencyCruiserJson)
        val stale = newestSource != null && graphModified != null && newestSource > graphModified
        return GovernanceEvaluationInput(
            GovernanceEvidenceSnapshot(
                sources = listOf(
                    GovernanceEvidenceSource(
                        id = analysisSourceId,
                        backend = GovernanceBackend.TYPESCRIPT_ARTIFACT,
                        languages = setOf(GovernanceLanguage.TYPESCRIPT),
                        supportedScopes = setOf(
                            GovernanceScope.SOURCE_FOLDER,
                            GovernanceScope.SOURCE_FILE,
                            GovernanceScope.REFERENCE,
                        ),
                        fresh = !stale,
                        freshnessDiagnostic = if (stale) {
                            "The dependency-cruiser JSON is older than current TypeScript source; regenerate it before evaluation."
                        } else {
                            null
                        },
                        repositoryComplete = true,
                    ),
                ),
                nodes = nodes,
                references = references,
                caseSensitive = !System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
                evaluationComplete = true,
            ),
        )
    }

    private fun readGraph(sourceId: String, path: Path): DepGraph {
        val root = try {
            json.parseToJsonElement(Files.readString(path)).jsonObject
        } catch (exception: Exception) {
            throw EvaluatorAcquisitionException(sourceId, "The dependency-cruiser JSON is unreadable or invalid.", exception)
        }
        val modules = root["modules"] as? JsonArray
            ?: throw EvaluatorAcquisitionException(sourceId, "Expected dependency-cruiser JSON with a top-level modules array.")
        return DepGraph(
            modules = modules.mapNotNull { element ->
                val value = element as? JsonObject ?: return@mapNotNull null
                DepModule(
                    source = value.string("source") ?: return@mapNotNull null,
                    dependencies = (value["dependencies"] as? JsonArray)?.mapNotNull(::dependency) ?: emptyList(),
                )
            },
            baseDir = (root["summary"] as? JsonObject)
                ?.get("optionsUsed")
                ?.let { it as? JsonObject }
                ?.string("baseDir"),
        )
    }

    private fun dependency(element: JsonElement): DepDependency? {
        val value = element as? JsonObject ?: return null
        return DepDependency(
            resolved = value.string("resolved"),
            dependencyTypes = value.stringArray("dependencyTypes"),
            dynamic = value.boolean("dynamic"),
        )
    }

    private fun identity(repositoryPath: String): GovernanceIdentity = GovernanceIdentity(
        architectureUnit = repositoryPath.substringBeforeLast('/', missingDelimiterValue = ".").ifBlank { "." },
        sourceFile = repositoryPath,
    )

    private fun dependencyKinds(dependency: DepDependency): List<GovernanceDependencyKind> {
        val raw = dependency.dependencyTypes.map(String::lowercase)
        val kinds = sortedSetOf<GovernanceDependencyKind>()
        val typeOnly = "pre-compilation-only" in raw
        if (dependency.dynamic || raw.any { "dynamic" in it }) kinds += GovernanceDependencyKind.DYNAMIC_IMPORT
        if (typeOnly || raw.any { "type" in it }) kinds += GovernanceDependencyKind.TYPE_ONLY_IMPORT
        if (raw.any { it == "export" || "re-export" in it || "reexport" in it }) kinds += GovernanceDependencyKind.RE_EXPORT
        if (!typeOnly && raw.any { it in RUNTIME_DEPENDENCY_TYPES }) kinds += GovernanceDependencyKind.RUNTIME_IMPORT
        if (kinds.isEmpty()) kinds += GovernanceDependencyKind.UNKNOWN
        return kinds.toList()
    }

    private fun resolveArtifactPath(raw: String, projectRoot: Path, sourceRoot: Path): Path {
        val path = Path.of(raw.replace('\\', '/'))
        if (path.isAbsolute) return path.normalize()
        val projectCandidate = projectRoot.resolve(path).normalize()
        val sourceCandidate = sourceRoot.resolve(path).normalize()
        return when {
            projectCandidate.exists() -> projectCandidate
            sourceCandidate.exists() -> sourceCandidate
            else -> projectCandidate
        }
    }

    private fun repositoryPath(path: Path, repositoryRoot: Path): String? {
        val absolute = path.toAbsolutePath().normalize()
        val root = repositoryRoot.toAbsolutePath().normalize()
        if (!absolute.startsWith(root)) return null
        return GovernancePaths.normalizeRepositoryRelative(root.relativize(absolute).toString())
    }

    private fun isInRepository(path: Path, repositoryRoot: Path): Boolean =
        path.toAbsolutePath().normalize().startsWith(repositoryRoot.toAbsolutePath().normalize())

    private fun isNodeModule(path: Path): Boolean =
        path.toString().replace('\\', '/').split('/').any { it == "node_modules" }

    private fun modified(path: Path): Long? =
        if (!path.isRegularFile()) null else runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrNull()

    private data class DepGraph(val modules: List<DepModule>, val baseDir: String?)
    private data class DepModule(val source: String, val dependencies: List<DepDependency>)
    private data class DepDependency(val resolved: String?, val dependencyTypes: List<String>, val dynamic: Boolean)
    private data class ModuleFile(val module: DepModule, val absolutePath: Path, val repositoryPath: String)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val RUNTIME_DEPENDENCY_TYPES = setOf(
            "import", "require", "import-equals", "amd-define", "amd-require", "amd-exotic-require",
            "exotic-require", "triple-slash-directive", "triple-slash-file-reference", "triple-slash-amd-dependency",
        )
    }
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
private fun JsonObject.boolean(name: String): Boolean = this[name]?.jsonPrimitive?.booleanOrNull ?: false
private fun JsonObject.stringArray(name: String): List<String> =
    (this[name] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
