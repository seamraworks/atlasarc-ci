package io.atlasarc.governance

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@JvmInline
value class GovernanceRevision(val value: String) {
    companion object {
        val MISSING = GovernanceRevision("missing")
    }
}

data class LoadedGovernanceDocument(
    val repositoryRoot: Path,
    val path: Path,
    val document: CycleGovernanceDocument,
    val revision: GovernanceRevision,
    val exists: Boolean,
)

sealed interface GovernanceReadResult {
    data class Loaded(val value: LoadedGovernanceDocument) : GovernanceReadResult
    data class Invalid(
        val repositoryRoot: Path,
        val path: Path,
        val revision: GovernanceRevision,
        val issues: List<GovernanceValidationIssue>,
        /** Available when the document decoded but failed semantic validation. */
        val document: CycleGovernanceDocument? = null,
    ) : GovernanceReadResult

    data class MissingVcsRoot(val start: Path) : GovernanceReadResult
    data class IoError(val path: Path, val message: String) : GovernanceReadResult
}

sealed interface GovernanceWriteResult {
    data class Written(val path: Path, val revision: GovernanceRevision) : GovernanceWriteResult
    data class NoChange(val path: Path, val revision: GovernanceRevision) : GovernanceWriteResult
    data class MissingVcsRoot(val start: Path) : GovernanceWriteResult
    data class IgnoredPath(val path: Path, val rule: GovernanceIgnoreRule? = null) : GovernanceWriteResult
    data class IgnoreCheckUnavailable(val path: Path) : GovernanceWriteResult
    data class ReadOnly(val path: Path) : GovernanceWriteResult
    data class ConcurrentEdit(
        val path: Path,
        val expected: GovernanceRevision,
        val actual: GovernanceRevision,
    ) : GovernanceWriteResult

    data class InvalidDocument(val issues: List<GovernanceValidationIssue>) : GovernanceWriteResult
    data class IoError(val path: Path, val message: String) : GovernanceWriteResult
}

enum class GovernanceIgnoreStatus {
    IGNORED,
    NOT_IGNORED,
    UNAVAILABLE,
}

data class GovernanceIgnoreRule(
    val source: String,
    val line: Int,
    val pattern: String,
)

data class GovernanceIgnoreCheck(
    val status: GovernanceIgnoreStatus,
    val rule: GovernanceIgnoreRule? = null,
)

fun interface GovernanceIgnoreProbe {
    fun check(repositoryRoot: Path, relativePath: String): GovernanceIgnoreCheck
}

class GitGovernanceIgnoreProbe : GovernanceIgnoreProbe {
    override fun check(repositoryRoot: Path, relativePath: String): GovernanceIgnoreCheck {
        return try {
            val process = ProcessBuilder(
                "git",
                "-C",
                repositoryRoot.toString(),
                "check-ignore",
                "--verbose",
                "--no-index",
                relativePath,
            ).redirectErrorStream(true).start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return GovernanceIgnoreCheck(GovernanceIgnoreStatus.UNAVAILABLE)
            }
            val output = process.inputStream.bufferedReader().readText().trim()
            when (process.exitValue()) {
                0 -> {
                    val rule = parseRule(output)
                    if (rule?.pattern?.startsWith("!") == true) {
                        GovernanceIgnoreCheck(GovernanceIgnoreStatus.NOT_IGNORED, rule)
                    } else {
                        GovernanceIgnoreCheck(GovernanceIgnoreStatus.IGNORED, rule)
                    }
                }
                1 -> GovernanceIgnoreCheck(GovernanceIgnoreStatus.NOT_IGNORED)
                else -> GovernanceIgnoreCheck(GovernanceIgnoreStatus.UNAVAILABLE)
            }
        } catch (_: IOException) {
            GovernanceIgnoreCheck(GovernanceIgnoreStatus.UNAVAILABLE)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            GovernanceIgnoreCheck(GovernanceIgnoreStatus.UNAVAILABLE)
        }
    }

    private fun parseRule(output: String): GovernanceIgnoreRule? {
        val firstLine = output.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val match = RULE_OUTPUT.matchEntire(firstLine) ?: return null
        return GovernanceIgnoreRule(
            source = match.groupValues[1],
            line = match.groupValues[2].toIntOrNull() ?: return null,
            pattern = match.groupValues[3],
        )
    }

    private companion object {
        val RULE_OUTPUT = Regex("""^(.*):(\d+):(.*)\t.*$""")
    }
}

class VcsRootLocator {
    fun nearest(start: Path): Path? {
        var current = start.toAbsolutePath().normalize()
        if (!Files.isDirectory(current)) current = current.parent ?: return null
        while (true) {
            if (Files.exists(current.resolve(".git"))) return current
            current = current.parent ?: return null
        }
    }
}

class CycleGovernanceRepository(
    private val codec: CycleGovernanceCodec = CycleGovernanceCodec(),
    private val rootLocator: VcsRootLocator = VcsRootLocator(),
    private val ignoreProbe: GovernanceIgnoreProbe = GitGovernanceIgnoreProbe(),
) {
    fun read(start: Path): GovernanceReadResult {
        val root = rootLocator.nearest(start) ?: return GovernanceReadResult.MissingVcsRoot(start)
        val path = GovernancePaths.documentPath(root)
        if (!Files.exists(path)) {
            return GovernanceReadResult.Loaded(
                LoadedGovernanceDocument(
                    repositoryRoot = root,
                    path = path,
                    document = CycleGovernanceDocument(),
                    revision = GovernanceRevision.MISSING,
                    exists = false,
                ),
            )
        }
        return try {
            val bytes = Files.readAllBytes(path)
            val revision = revision(bytes)
            val text = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (exception: Exception) {
                return GovernanceReadResult.Invalid(
                    root,
                    path,
                    revision,
                    listOf(GovernanceValidationIssue("invalid-utf8", exception.message ?: "Document is not valid UTF-8.")),
                )
            }
            when (val decoded = codec.decode(text)) {
                is GovernanceDecodeResult.Success -> GovernanceReadResult.Loaded(
                    LoadedGovernanceDocument(root, path, decoded.document, revision, exists = true),
                )
                is GovernanceDecodeResult.Invalid -> GovernanceReadResult.Invalid(
                    root,
                    path,
                    revision,
                    decoded.issues,
                    decoded.document,
                )
            }
        } catch (exception: IOException) {
            GovernanceReadResult.IoError(path, exception.message ?: exception.javaClass.simpleName)
        }
    }

    fun write(
        start: Path,
        document: CycleGovernanceDocument,
        expectedRevision: GovernanceRevision,
    ): GovernanceWriteResult {
        val encoded = when (val result = codec.encode(document)) {
            is GovernanceEncodeResult.Success -> result.text.toByteArray(StandardCharsets.UTF_8)
            is GovernanceEncodeResult.Invalid -> return GovernanceWriteResult.InvalidDocument(result.issues)
        }
        val root = rootLocator.nearest(start) ?: return GovernanceWriteResult.MissingVcsRoot(start)
        val path = GovernancePaths.documentPath(root)
        val ignore = ignoreProbe.check(root, CYCLE_GOVERNANCE_RELATIVE_PATH)
        when (ignore.status) {
            GovernanceIgnoreStatus.IGNORED -> return GovernanceWriteResult.IgnoredPath(path, ignore.rule)
            GovernanceIgnoreStatus.UNAVAILABLE -> return GovernanceWriteResult.IgnoreCheckUnavailable(path)
            GovernanceIgnoreStatus.NOT_IGNORED -> Unit
        }

        val actualRevision = currentRevision(path)
            ?: return GovernanceWriteResult.IoError(path, "Could not read the current governance revision.")
        if (actualRevision != expectedRevision) {
            return GovernanceWriteResult.ConcurrentEdit(path, expectedRevision, actualRevision)
        }
        if (Files.exists(path)) {
            val existing = try {
                Files.readAllBytes(path)
            } catch (exception: IOException) {
                return GovernanceWriteResult.IoError(path, exception.message ?: exception.javaClass.simpleName)
            }
            if (existing.contentEquals(encoded)) return GovernanceWriteResult.NoChange(path, actualRevision)
        }

        if (isReadOnly(path)) return GovernanceWriteResult.ReadOnly(path)
        val parent = path.parent
        if (nearestExistingParent(parent)?.let(::isReadOnly) == true) return GovernanceWriteResult.ReadOnly(path)

        var temporary: Path? = null
        return try {
            Files.createDirectories(parent)
            temporary = Files.createTempFile(parent, ".cycles.", ".tmp")
            FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                var buffer = ByteBuffer.wrap(encoded)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }

            val revisionBeforeMove = currentRevision(path)
                ?: return GovernanceWriteResult.IoError(path, "Could not recheck the governance revision.")
            if (revisionBeforeMove != expectedRevision) {
                return GovernanceWriteResult.ConcurrentEdit(path, expectedRevision, revisionBeforeMove)
            }
            atomicReplace(temporary, path)
            temporary = null
            GovernanceWriteResult.Written(path, revision(encoded))
        } catch (exception: IOException) {
            GovernanceWriteResult.IoError(path, exception.message ?: exception.javaClass.simpleName)
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    fun revision(path: Path): GovernanceRevision? = currentRevision(path)

    private fun currentRevision(path: Path): GovernanceRevision? {
        if (!Files.exists(path)) return GovernanceRevision.MISSING
        return try {
            revision(Files.readAllBytes(path))
        } catch (_: IOException) {
            null
        }
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

class GovernanceFileChangeDetector {
    fun changed(snapshot: LoadedGovernanceDocument): Boolean {
        val current = if (!Files.exists(snapshot.path)) {
            GovernanceRevision.MISSING
        } else {
            val bytes = runCatching { Files.readAllBytes(snapshot.path) }.getOrNull() ?: return true
            GovernanceRevision(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        }
        return current != snapshot.revision
    }
}

object AtlasArcIgnoreFile {
    const val CONTENT: String = """# Generated AtlasArc analysis artifacts (regenerate locally)
/depgraph.json
/eslint.sarif
/lcov.info

# sources.json and governance/ are repository configuration and remain committable.
"""

    const val ROOT_TRACKED_CONFIGURATION: String = """# Keep AtlasArc repository configuration visible to Git
!/.atlasarc/
/.atlasarc/*
!/.atlasarc/sources.json
!/.atlasarc/governance/
!/.atlasarc/governance/**
"""
}
