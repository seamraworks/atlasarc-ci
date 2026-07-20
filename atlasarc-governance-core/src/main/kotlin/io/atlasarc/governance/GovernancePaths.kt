package io.atlasarc.governance

import java.nio.file.Path
import java.text.Normalizer

object GovernancePaths {
    private val windowsAbsolute = Regex("^[A-Za-z]:[/\\\\].*")

    fun normalizeRepositoryRelative(value: String): String? {
        if (value.isBlank()) return null
        val slash = Normalizer.normalize(value.trim(), Normalizer.Form.NFC).replace('\\', '/')
        if (slash.startsWith('/') || slash.startsWith("//") || windowsAbsolute.matches(slash)) return null
        if (slash == ".") return "."

        val parts = slash.split('/')
        if (parts.any { it.isBlank() || it == ".." }) return null
        val normalized = parts.filterNot { it == "." }.joinToString("/")
        return normalized.takeIf { it.isNotBlank() }
    }

    fun isCanonicalRepositoryRelative(value: String): Boolean =
        normalizeRepositoryRelative(value) == value

    fun documentPath(repositoryRoot: Path): Path =
        repositoryRoot.resolve(CYCLE_GOVERNANCE_RELATIVE_PATH.replace('/', repositoryRoot.fileSystem.separator.single()))
            .normalize()
}
