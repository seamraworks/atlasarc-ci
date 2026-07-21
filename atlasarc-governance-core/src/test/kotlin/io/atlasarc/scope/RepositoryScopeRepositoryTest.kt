package io.atlasarc.scope

import io.atlasarc.governance.GovernanceIgnoreCheck
import io.atlasarc.governance.GovernanceIgnoreStatus
import io.atlasarc.governance.GovernanceRevision
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RepositoryScopeRepositoryTest {
    @Test
    fun `missing policy loads as empty at the owning VCS root`() {
        val root = Files.createTempDirectory("atlasarc-scope-repository")
        root.resolve(".git").createDirectories()
        val nested = root.resolve("module/src").createDirectories()

        val loaded = assertIs<RepositoryScopeReadResult.Loaded>(repository().read(nested)).value

        assertEquals(root, loaded.repositoryRoot)
        assertEquals(root.resolve(REPOSITORY_SCOPE_RELATIVE_PATH), loaded.path)
        assertEquals(RepositoryScopeDocument(), loaded.document)
        assertEquals(GovernanceRevision.MISSING, loaded.revision)
        assertTrue(!loaded.exists)
    }

    @Test
    fun `write is deterministic revision guarded and round trips`() {
        val root = Files.createTempDirectory("atlasarc-scope-write")
        root.resolve(".git").createDirectories()
        val repository = repository()
        val document = RepositoryScopeDocument(
            exclusions = mapOf(
                "generated" to RepositoryScopeExclusion(
                    RepositoryScopeSelector(
                        RepositoryScopeSelectorKind.JVM_PACKAGE_PATTERN,
                        "com.acme.generated.**",
                        "*",
                    ),
                    "Generated code is outside the governed architecture.",
                ),
            ),
        )

        val written = assertIs<RepositoryScopeWriteResult.Written>(
            repository.write(root, document, GovernanceRevision.MISSING),
        )
        val loaded = assertIs<RepositoryScopeReadResult.Loaded>(repository.read(root)).value
        assertEquals(document, loaded.document)
        assertEquals(written.revision, loaded.revision)
        assertIs<RepositoryScopeWriteResult.NoChange>(repository.write(root, document, loaded.revision))
        assertIs<RepositoryScopeWriteResult.ConcurrentEdit>(
            repository.write(root, document.copy(exclusions = emptyMap()), GovernanceRevision.MISSING),
        )
    }

    @Test
    fun `invalid and ignored policy fail closed`() {
        val root = Files.createTempDirectory("atlasarc-scope-invalid")
        root.resolve(".git").createDirectories()
        val path = root.resolve(REPOSITORY_SCOPE_RELATIVE_PATH)
        path.parent.createDirectories()
        path.createFile().writeText("{}")

        assertIs<RepositoryScopeReadResult.Invalid>(repository().read(root))
        path.writeText(
            assertIs<RepositoryScopeEncodeResult.Success>(
                RepositoryScopeCodec().encode(RepositoryScopeDocument()),
            ).text,
        )
        val ignored = RepositoryScopeRepository(ignoreProbe = { _, _ ->
            GovernanceIgnoreCheck(GovernanceIgnoreStatus.IGNORED)
        })
        val ignoredRead = assertIs<RepositoryScopeReadResult.Invalid>(ignored.read(root))
        assertEquals("ignored-scope-policy", ignoredRead.issues.single().code)
        assertIs<RepositoryScopeWriteCheckResult.IgnoredPath>(ignored.checkWrite(root))
    }

    private fun repository() = RepositoryScopeRepository(ignoreProbe = { _, _ ->
        GovernanceIgnoreCheck(GovernanceIgnoreStatus.NOT_IGNORED)
    })
}
