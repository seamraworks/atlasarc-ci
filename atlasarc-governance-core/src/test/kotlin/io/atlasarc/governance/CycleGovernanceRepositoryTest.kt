package io.atlasarc.governance

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CycleGovernanceRepositoryTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `read never creates a missing document and write uses the nearest VCS root`() {
        val outer = vcsRoot(temp.resolve("outer"))
        val inner = vcsRoot(outer.resolve("modules/inner"))
        val start = Files.createDirectories(inner.resolve("src/main"))
        val repository = repository()

        val loaded = assertInstanceOf(GovernanceReadResult.Loaded::class.java, repository.read(start)).value
        assertEquals(inner, loaded.repositoryRoot)
        assertFalse(loaded.exists)
        assertFalse(Files.exists(loaded.path))

        val written = assertInstanceOf(
            GovernanceWriteResult.Written::class.java,
            repository.write(start, document(), loaded.revision),
        )
        assertEquals(inner.resolve(".atlasarc/governance/cycles.json"), written.path)
        assertFalse(Files.exists(outer.resolve(".atlasarc/governance/cycles.json")))
    }

    @Test
    fun `semantic no-op produces no filesystem write`() {
        val root = vcsRoot(temp.resolve("repo"))
        val repository = repository()
        val missing = (repository.read(root) as GovernanceReadResult.Loaded).value
        val written = repository.write(root, document(), missing.revision) as GovernanceWriteResult.Written
        val bytes = Files.readAllBytes(written.path)
        val modified = Files.getLastModifiedTime(written.path)

        val noChange = repository.write(root, document(), written.revision)

        assertInstanceOf(GovernanceWriteResult.NoChange::class.java, noChange)
        assertArrayEquals(bytes, Files.readAllBytes(written.path))
        assertEquals(modified, Files.getLastModifiedTime(written.path))
    }

    @Test
    fun `an external edit is detected and never overwritten`() {
        val root = vcsRoot(temp.resolve("repo"))
        val repository = repository()
        val loaded = (repository.read(root) as GovernanceReadResult.Loaded).value
        Files.createDirectories(loaded.path.parent)
        Files.writeString(loaded.path, "external branch edit\n")

        val result = repository.write(root, document(), loaded.revision)

        assertInstanceOf(GovernanceWriteResult.ConcurrentEdit::class.java, result)
        assertEquals("external branch edit\n", Files.readString(loaded.path))
    }

    @Test
    fun `ignored governance path returns a typed failure`() {
        val root = vcsRoot(temp.resolve("repo"))
        val repository = CycleGovernanceRepository(
            ignoreProbe = GovernanceIgnoreProbe { _, _ ->
                GovernanceIgnoreCheck(
                    GovernanceIgnoreStatus.IGNORED,
                    GovernanceIgnoreRule(".gitignore", 1, ".atlasarc/"),
                )
            },
        )

        val result = repository.write(root, document(), GovernanceRevision.MISSING)

        val ignored = assertInstanceOf(GovernanceWriteResult.IgnoredPath::class.java, result)
        assertEquals(".gitignore", ignored.rule?.source)
        assertFalse(Files.exists(root.resolve(".atlasarc/governance/cycles.json")))
    }

    @Test
    fun `unavailable Git ignore check blocks the first durable write`() {
        val root = vcsRoot(temp.resolve("repo"))
        val repository = CycleGovernanceRepository(
            ignoreProbe = GovernanceIgnoreProbe { _, _ ->
                GovernanceIgnoreCheck(GovernanceIgnoreStatus.UNAVAILABLE)
            },
        )

        val result = repository.write(root, document(), GovernanceRevision.MISSING)

        assertInstanceOf(GovernanceWriteResult.IgnoreCheckUnavailable::class.java, result)
        assertFalse(Files.exists(root.resolve(".atlasarc/governance/cycles.json")))
    }

    @Test
    fun `missing VCS root invalid document and malformed file are typed`() {
        val repository = repository()
        assertInstanceOf(GovernanceReadResult.MissingVcsRoot::class.java, repository.read(temp))
        assertInstanceOf(
            GovernanceWriteResult.MissingVcsRoot::class.java,
            repository.write(temp, document(), GovernanceRevision.MISSING),
        )

        val root = vcsRoot(temp.resolve("repo"))
        val invalid = document().copy(schemaVersion = 2)
        assertInstanceOf(
            GovernanceWriteResult.InvalidDocument::class.java,
            repository.write(root, invalid, GovernanceRevision.MISSING),
        )

        val path = GovernancePaths.documentPath(root)
        Files.createDirectories(path.parent)
        Files.writeString(path, "not json")
        assertInstanceOf(GovernanceReadResult.Invalid::class.java, repository.read(root))
    }

    @Test
    fun `malformed UTF-8 is rejected rather than replacement-decoded`() {
        val root = vcsRoot(temp.resolve("repo"))
        val path = GovernancePaths.documentPath(root)
        Files.createDirectories(path.parent)
        Files.write(path, byteArrayOf(0x7b, 0x22, 0x78, 0x22, 0x3a, 0xc3.toByte(), 0x28, 0x7d))

        val result = assertInstanceOf(GovernanceReadResult.Invalid::class.java, repository().read(root))

        assertTrue(result.issues.any { it.code == "invalid-utf8" })
    }

    @Test
    fun `real git ignore rules block hidden governance but default AtlasArc rules do not`() {
        val root = Files.createDirectories(temp.resolve("repo"))
        val init = ProcessBuilder("git", "init", "--quiet", root.toString()).start()
        assertEquals(0, init.waitFor())
        Files.writeString(root.resolve(".gitignore"), ".atlasarc/\n")
        val repository = CycleGovernanceRepository()

        val ignored = assertInstanceOf(
            GovernanceWriteResult.IgnoredPath::class.java,
            repository.write(root, document(), GovernanceRevision.MISSING),
        )
        assertEquals(".gitignore", ignored.rule?.source)
        assertEquals(1, ignored.rule?.line)
        assertEquals(".atlasarc/", ignored.rule?.pattern)

        Files.writeString(root.resolve(".gitignore"), "")
        val atlasArc = Files.createDirectories(root.resolve(".atlasarc"))
        Files.writeString(atlasArc.resolve(".gitignore"), AtlasArcIgnoreFile.CONTENT)
        assertInstanceOf(
            GovernanceWriteResult.Written::class.java,
            repository.write(root, document(), GovernanceRevision.MISSING),
        )
    }

    @Test
    fun `suggested root ignore layout keeps governance committable after a whole-folder rule`() {
        val root = Files.createDirectories(temp.resolve("repo"))
        val init = ProcessBuilder("git", "init", "--quiet", root.toString()).start()
        assertEquals(0, init.waitFor())
        Files.writeString(
            root.resolve(".gitignore"),
            ".atlasarc/\n\n${AtlasArcIgnoreFile.ROOT_TRACKED_CONFIGURATION}",
        )
        val ignore = GitGovernanceIgnoreProbe().check(root, CYCLE_GOVERNANCE_RELATIVE_PATH)
        assertEquals(GovernanceIgnoreStatus.NOT_IGNORED, ignore.status, ignore.toString())

        val result = CycleGovernanceRepository().write(root, document(), GovernanceRevision.MISSING)

        assertInstanceOf(GovernanceWriteResult.Written::class.java, result)
    }

    @Test
    fun `read-only governance file is not replaced`() {
        val root = vcsRoot(temp.resolve("repo"))
        val repository = repository()
        val first = (repository.read(root) as GovernanceReadResult.Loaded).value
        val written = repository.write(root, document(), first.revision) as GovernanceWriteResult.Written
        val original = Files.readAllBytes(written.path)

        Files.setAttribute(written.path, "dos:readonly", true)
        try {
            val changed = document().copy(records = document().records.mapValues { (_, value) -> value.copy(reason = "changed") })
            assertInstanceOf(
                GovernanceWriteResult.ReadOnly::class.java,
                repository.write(root, changed, written.revision),
            )
            assertArrayEquals(original, Files.readAllBytes(written.path))
        } finally {
            Files.setAttribute(written.path, "dos:readonly", false)
        }
    }

    @Test
    fun `external file change can trigger reload without graph acquisition`() {
        val root = vcsRoot(temp.resolve("repo"))
        val repository = repository()
        val first = (repository.read(root) as GovernanceReadResult.Loaded).value
        val written = repository.write(root, document(), first.revision) as GovernanceWriteResult.Written
        val snapshot = (repository.read(root) as GovernanceReadResult.Loaded).value
        val detector = GovernanceFileChangeDetector()
        assertFalse(detector.changed(snapshot))

        Files.writeString(written.path, Files.readString(written.path) + " ")

        assertTrue(detector.changed(snapshot))
    }

    @Test
    fun `default AtlasArc ignore file leaves committed configuration visible`() {
        assertTrue(AtlasArcIgnoreFile.CONTENT.contains("/depgraph.json"))
        assertFalse(AtlasArcIgnoreFile.CONTENT.lines().any { it.trim() == "/governance/" })
        assertTrue(AtlasArcIgnoreFile.CONTENT.endsWith("\n"))
        assertTrue(AtlasArcIgnoreFile.ROOT_TRACKED_CONFIGURATION.contains("!/.atlasarc/evaluator.json"))
        assertTrue(AtlasArcIgnoreFile.ROOT_TRACKED_CONFIGURATION.contains("!/.atlasarc/governance/**"))
    }

    private fun vcsRoot(path: Path): Path = Files.createDirectories(path).also {
        Files.createDirectories(it.resolve(".git"))
    }

    private fun repository() = CycleGovernanceRepository(
        ignoreProbe = GovernanceIgnoreProbe { _, _ ->
            GovernanceIgnoreCheck(GovernanceIgnoreStatus.NOT_IGNORED)
        },
    )

    private fun document() = CycleGovernanceDocument(
        records = mapOf(
            "cycle-one" to CycleGovernanceRecord(
                analysisSource = GovernanceAnalysisSource("jvm:whole-project", GovernanceBackend.JVM_BYTECODE, GovernanceLanguage.JAVA),
                scope = GovernanceScope.PACKAGE,
                ownerSide = GovernanceOwnerSide.SOURCE,
                source = GovernanceIdentity("a"),
                target = GovernanceIdentity("b"),
                kind = CycleGovernanceKind.INTENTIONAL,
                reason = "Reviewed architecture boundary.",
            ),
        ),
    )
}
