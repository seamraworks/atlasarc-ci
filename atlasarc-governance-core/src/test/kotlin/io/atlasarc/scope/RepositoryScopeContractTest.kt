package io.atlasarc.scope

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RepositoryScopeContractTest {
    @Test
    fun `canonical encoding sorts stable rule ids`() {
        val document = RepositoryScopeDocument(
            exclusions = linkedMapOf(
                "z-generated" to exclusion("com.acme.generated.**"),
                "a-vendor" to exclusion("com.acme.vendor.**"),
            ),
        )

        val encoded = assertIs<RepositoryScopeEncodeResult.Success>(RepositoryScopeCodec().encode(document)).text
        assertTrue(encoded.indexOf("a-vendor") < encoded.indexOf("z-generated"))
        assertEquals(document.copy(exclusions = document.exclusions.toSortedMap()), assertIs<RepositoryScopeDecodeResult.Success>(RepositoryScopeCodec().decode(encoded)).document)
    }

    @Test
    fun `strict decoding rejects unknown fields and duplicate keys`() {
        val unknown = RepositoryScopeCodec().decode(
            """{"${'$'}schema":"$REPOSITORY_SCOPE_SCHEMA_URI","schemaVersion":1,"exclusions":{},"surprise":true}""",
        )
        assertEquals("schema-decode-failed", assertIs<RepositoryScopeDecodeResult.Invalid>(unknown).issues.single().code)

        val duplicate = RepositoryScopeCodec().decode(
            """{"${'$'}schema":"$REPOSITORY_SCOPE_SCHEMA_URI","schemaVersion":1,"schemaVersion":1,"exclusions":{}}""",
        )
        assertEquals("duplicate-json-key", assertIs<RepositoryScopeDecodeResult.Invalid>(duplicate).issues.single().code)
    }

    @Test
    fun `contract rejects ambiguous wildcards duplicate selectors and TypeScript modules`() {
        val invalid = RepositoryScopeDocument(
            exclusions = linkedMapOf(
                "first" to exclusion("com.acme.gen*"),
                "second" to exclusion("com.acme.gen*"),
                "typescript" to RepositoryScopeExclusion(
                    RepositoryScopeSelector(
                        RepositoryScopeSelectorKind.TYPESCRIPT_SOURCE_FOLDER_PATTERN,
                        "src/generated/**",
                        module = "web",
                    ),
                    "Generated sources.",
                ),
            ),
        )

        val issues = assertIs<RepositoryScopeEncodeResult.Invalid>(RepositoryScopeCodec().encode(invalid)).issues
        assertTrue(issues.any { it.code == "invalid-pattern-wildcard" })
        assertTrue(issues.any { it.code == "duplicate-selector" })
        assertTrue(issues.any { it.code == "typescript-module-selector" })
    }

    @Test
    fun `TypeScript selectors allow literal dot-prefixed repository folders`() {
        val document = RepositoryScopeDocument(
            exclusions = mapOf(
                "storybook" to RepositoryScopeExclusion(
                    RepositoryScopeSelector(
                        RepositoryScopeSelectorKind.TYPESCRIPT_SOURCE_FOLDER_PATTERN,
                        ".storybook/generated/**",
                    ),
                    "Generated Storybook evidence.",
                ),
            ),
        )

        assertIs<RepositoryScopeEncodeResult.Success>(RepositoryScopeCodec().encode(document))
    }

    private fun exclusion(pattern: String) = RepositoryScopeExclusion(
        RepositoryScopeSelector(RepositoryScopeSelectorKind.JVM_PACKAGE_PATTERN, pattern, module = "*"),
        "Outside the governed architecture.",
    )
}
