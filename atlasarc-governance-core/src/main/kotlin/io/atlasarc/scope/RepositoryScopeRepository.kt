package io.atlasarc.scope

import io.atlasarc.governance.GitGovernanceIgnoreProbe
import io.atlasarc.governance.GovernanceIgnoreProbe
import io.atlasarc.governance.GovernanceIgnoreRule
import io.atlasarc.governance.GovernanceIgnoreStatus
import io.atlasarc.governance.GovernancePaths
import io.atlasarc.governance.GovernanceRevision
import io.atlasarc.governance.VcsRootLocator
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

data class LoadedRepositoryScope(
    val repositoryRoot: Path,
    val path: Path,
    val document: RepositoryScopeDocument,
    val revision: GovernanceRevision,
    val exists: Boolean,
)

sealed interface RepositoryScopeReadResult {
    data class Loaded(val value: LoadedRepositoryScope) : RepositoryScopeReadResult
    data class Invalid(
        val repositoryRoot: Path,
        val path: Path,
        val revision: GovernanceRevision,
        val issues: List<RepositoryScopeIssue>,
        val document: RepositoryScopeDocument? = null,
    ) : RepositoryScopeReadResult
    data class MissingVcsRoot(val start: Path) : RepositoryScopeReadResult
    data class IoError(val path: Path, val message: String) : RepositoryScopeReadResult
}

sealed interface RepositoryScopeWriteCheckResult {
    data class Ready(val path: Path) : RepositoryScopeWriteCheckResult
    data class MissingVcsRoot(val start: Path) : RepositoryScopeWriteCheckResult
    data class IgnoredPath(val path: Path, val rule: GovernanceIgnoreRule? = null) : RepositoryScopeWriteCheckResult
    data class IgnoreCheckUnavailable(val path: Path) : RepositoryScopeWriteCheckResult
    data class ReadOnly(val path: Path) : RepositoryScopeWriteCheckResult
}

sealed interface RepositoryScopeWriteResult {
    data class Written(val path: Path, val revision: GovernanceRevision) : RepositoryScopeWriteResult
    data class NoChange(val path: Path, val revision: GovernanceRevision) : RepositoryScopeWriteResult
    data class MissingVcsRoot(val start: Path) : RepositoryScopeWriteResult
    data class IgnoredPath(val path: Path, val rule: GovernanceIgnoreRule? = null) : RepositoryScopeWriteResult
    data class IgnoreCheckUnavailable(val path: Path) : RepositoryScopeWriteResult
    data class ReadOnly(val path: Path) : RepositoryScopeWriteResult
    data class ConcurrentEdit(val path: Path, val expected: GovernanceRevision, val actual: GovernanceRevision) :
        RepositoryScopeWriteResult
    data class InvalidDocument(val issues: List<RepositoryScopeIssue>) : RepositoryScopeWriteResult
    data class IoError(val path: Path, val message: String) : RepositoryScopeWriteResult
}

class RepositoryScopeRepository(
    private val codec: RepositoryScopeCodec = RepositoryScopeCodec(),
    private val rootLocator: VcsRootLocator = VcsRootLocator(),
    private val ignoreProbe: GovernanceIgnoreProbe = GitGovernanceIgnoreProbe(),
) {
    fun revision(path: Path): GovernanceRevision? = currentRevision(path)

    fun read(start: Path): RepositoryScopeReadResult {
        val root = rootLocator.nearest(start) ?: return RepositoryScopeReadResult.MissingVcsRoot(start)
        val path = GovernancePaths.scopeDocumentPath(root)
        if (!Files.exists(path)) {
            return RepositoryScopeReadResult.Loaded(
                LoadedRepositoryScope(root, path, RepositoryScopeDocument(), GovernanceRevision.MISSING, exists = false),
            )
        }
        return try {
            val bytes = Files.readAllBytes(path)
            val revision = revision(bytes)
            val ignore = ignoreProbe.check(root, REPOSITORY_SCOPE_RELATIVE_PATH)
            if (ignore.status == GovernanceIgnoreStatus.IGNORED) {
                return RepositoryScopeReadResult.Invalid(
                    root,
                    path,
                    revision,
                    listOf(
                        RepositoryScopeIssue(
                            "ignored-scope-policy",
                            "The repository scope policy is ignored by Git and cannot define shared architecture scope.",
                        ),
                    ),
                )
            }
            val text = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (exception: Exception) {
                return RepositoryScopeReadResult.Invalid(
                    root,
                    path,
                    revision,
                    listOf(RepositoryScopeIssue("invalid-utf8", exception.message ?: "Document is not valid UTF-8.")),
                )
            }
            when (val decoded = codec.decode(text)) {
                is RepositoryScopeDecodeResult.Success -> RepositoryScopeReadResult.Loaded(
                    LoadedRepositoryScope(root, path, decoded.document, revision, exists = true),
                )
                is RepositoryScopeDecodeResult.Invalid -> RepositoryScopeReadResult.Invalid(
                    root,
                    path,
                    revision,
                    decoded.issues,
                    decoded.document,
                )
            }
        } catch (exception: IOException) {
            RepositoryScopeReadResult.IoError(path, exception.message ?: exception.javaClass.simpleName)
        }
    }

    fun checkWrite(start: Path): RepositoryScopeWriteCheckResult {
        val root = rootLocator.nearest(start) ?: return RepositoryScopeWriteCheckResult.MissingVcsRoot(start)
        val path = GovernancePaths.scopeDocumentPath(root)
        val ignore = ignoreProbe.check(root, REPOSITORY_SCOPE_RELATIVE_PATH)
        when (ignore.status) {
            GovernanceIgnoreStatus.IGNORED -> return RepositoryScopeWriteCheckResult.IgnoredPath(path, ignore.rule)
            GovernanceIgnoreStatus.UNAVAILABLE -> return RepositoryScopeWriteCheckResult.IgnoreCheckUnavailable(path)
            GovernanceIgnoreStatus.NOT_IGNORED -> Unit
        }
        if (isReadOnly(path) || nearestExistingParent(path.parent)?.let(::isReadOnly) == true) {
            return RepositoryScopeWriteCheckResult.ReadOnly(path)
        }
        return RepositoryScopeWriteCheckResult.Ready(path)
    }

    fun write(
        start: Path,
        document: RepositoryScopeDocument,
        expectedRevision: GovernanceRevision,
    ): RepositoryScopeWriteResult {
        val encoded = when (val result = codec.encode(document)) {
            is RepositoryScopeEncodeResult.Success -> result.text.toByteArray(StandardCharsets.UTF_8)
            is RepositoryScopeEncodeResult.Invalid -> return RepositoryScopeWriteResult.InvalidDocument(result.issues)
        }
        val path = when (val check = checkWrite(start)) {
            is RepositoryScopeWriteCheckResult.Ready -> check.path
            is RepositoryScopeWriteCheckResult.MissingVcsRoot -> return RepositoryScopeWriteResult.MissingVcsRoot(check.start)
            is RepositoryScopeWriteCheckResult.IgnoredPath -> return RepositoryScopeWriteResult.IgnoredPath(check.path, check.rule)
            is RepositoryScopeWriteCheckResult.IgnoreCheckUnavailable -> return RepositoryScopeWriteResult.IgnoreCheckUnavailable(check.path)
            is RepositoryScopeWriteCheckResult.ReadOnly -> return RepositoryScopeWriteResult.ReadOnly(check.path)
        }
        val actualRevision = currentRevision(path)
            ?: return RepositoryScopeWriteResult.IoError(path, "Could not read the current repository scope revision.")
        if (actualRevision != expectedRevision) {
            return RepositoryScopeWriteResult.ConcurrentEdit(path, expectedRevision, actualRevision)
        }
        if (Files.exists(path)) {
            val existing = try {
                Files.readAllBytes(path)
            } catch (exception: IOException) {
                return RepositoryScopeWriteResult.IoError(path, exception.message ?: exception.javaClass.simpleName)
            }
            if (existing.contentEquals(encoded)) return RepositoryScopeWriteResult.NoChange(path, actualRevision)
        }

        var temporary: Path? = null
        return try {
            Files.createDirectories(path.parent)
            temporary = Files.createTempFile(path.parent, ".scope.", ".tmp")
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(encoded)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            val revisionBeforeMove = currentRevision(path)
                ?: return RepositoryScopeWriteResult.IoError(path, "Could not recheck the repository scope revision.")
            if (revisionBeforeMove != expectedRevision) {
                return RepositoryScopeWriteResult.ConcurrentEdit(path, expectedRevision, revisionBeforeMove)
            }
            atomicReplace(temporary, path)
            temporary = null
            RepositoryScopeWriteResult.Written(path, revision(encoded))
        } catch (exception: IOException) {
            RepositoryScopeWriteResult.IoError(path, exception.message ?: exception.javaClass.simpleName)
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun currentRevision(path: Path): GovernanceRevision? = if (!Files.exists(path)) {
        GovernanceRevision.MISSING
    } else {
        runCatching { revision(Files.readAllBytes(path)) }.getOrNull()
    }

    private fun atomicReplace(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isReadOnly(path: Path): Boolean {
        if (!Files.exists(path)) return false
        val dosReadOnly = runCatching { Files.getAttribute(path, "dos:readonly") as? Boolean }.getOrNull() == true
        return dosReadOnly || !Files.isWritable(path)
    }

    private fun nearestExistingParent(path: Path): Path? {
        var current: Path? = path
        while (current != null && !Files.exists(current)) current = current.parent
        return current
    }

    private fun revision(bytes: ByteArray): GovernanceRevision = GovernanceRevision(
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
    )
}
