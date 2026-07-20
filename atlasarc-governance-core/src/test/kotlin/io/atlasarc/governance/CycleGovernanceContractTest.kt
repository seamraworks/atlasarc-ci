package io.atlasarc.governance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CycleGovernanceContractTest {
    private val codec = CycleGovernanceCodec()

    @Test
    fun `empty and populated contract fixtures round-trip byte-stably`() {
        listOf("empty.json", "populated.json").forEach { name ->
            val text = fixture(name)
            val decoded = assertInstanceOf(GovernanceDecodeResult.Success::class.java, codec.decode(text))
            val encoded = assertInstanceOf(GovernanceEncodeResult.Success::class.java, codec.encode(decoded.document))
            assertEquals(text, encoded.text, name)
        }
    }

    @Test
    fun `serialization is deterministic regardless of record and reference discovery order`() {
        val populated = (codec.decode(fixture("populated.json")) as GovernanceDecodeResult.Success).document
        val reversed = populated.copy(
            records = populated.records.entries.reversed().associate { (id, record) ->
                id to record.copy(referenceIds = record.referenceIds.reversed().toSet())
            },
        )

        assertEquals(codec.encode(populated), codec.encode(reversed))
    }

    @Test
    fun `older newer unknown duplicate and noncanonical path fixtures fail closed`() {
        val expectedCodes = mapOf(
            "older-version.json" to "unsupported-schema-version",
            "newer-version.json" to "unsupported-schema-version",
            "unknown-field.json" to "schema-decode-failed",
            "duplicate-selector.json" to "duplicate-selector",
            "invalid-windows-path.json" to "non-canonical-path",
        )

        expectedCodes.forEach { (name, code) ->
            val result = assertInstanceOf(GovernanceDecodeResult.Invalid::class.java, codec.decode(fixture(name)))
            assertTrue(result.issues.any { it.code == code }, "$name should contain $code but was ${result.issues}")
        }
    }

    @Test
    fun `missing required properties and duplicate JSON keys are rejected before rewrite`() {
        val missingRecords = """{"${'$'}schema":"$CYCLE_GOVERNANCE_SCHEMA_URI","schemaVersion":1}"""
        val duplicateVersion = """{"${'$'}schema":"$CYCLE_GOVERNANCE_SCHEMA_URI","schemaVersion":1,"schemaVersion":1,"records":{}}"""

        val missing = assertInstanceOf(GovernanceDecodeResult.Invalid::class.java, codec.decode(missingRecords))
        val duplicate = assertInstanceOf(GovernanceDecodeResult.Invalid::class.java, codec.decode(duplicateVersion))

        assertTrue(missing.issues.any { it.code == "schema-decode-failed" })
        assertTrue(duplicate.issues.any { it.code == "duplicate-json-key" })
    }

    @Test
    fun `individual dependency scope has no configurable owner`() {
        val record = CycleGovernanceRecord(
            analysisSource = GovernanceAnalysisSource(
                "jvm:whole-project",
                GovernanceBackend.JVM_BYTECODE,
                GovernanceLanguage.JAVA,
            ),
            scope = GovernanceScope.REFERENCE,
            ownerSide = GovernanceOwnerSide.TARGET,
            source = GovernanceIdentity("billing"),
            target = GovernanceIdentity("orders"),
            referenceIds = setOf("ref-selected"),
            kind = CycleGovernanceKind.INTENTIONAL,
            reason = "Selected dependency is intentional.",
        )

        val issues = CycleGovernanceValidator().validateRecord("reference-record", record)

        assertTrue(issues.any { it.code == "noncanonical-reference-owner" })
    }

    @Test
    fun `path normalization is portable conservative and unicode preserving`() {
        assertEquals("apps/Admin Portal/src/Résumé.ts", GovernancePaths.normalizeRepositoryRelative("apps\\Admin Portal\\src\\Résumé.ts"))
        assertEquals("a/b", GovernancePaths.normalizeRepositoryRelative("a/./b"))
        assertEquals(null, GovernancePaths.normalizeRepositoryRelative("../secret"))
        assertEquals(null, GovernancePaths.normalizeRepositoryRelative("C:\\work\\secret"))
        assertEquals(null, GovernancePaths.normalizeRepositoryRelative("/var/secret"))
        assertEquals(null, GovernancePaths.normalizeRepositoryRelative("//server/share"))
    }

    @Test
    fun `bundled schema is present and identifies the exact contract`() {
        val schema = javaClass.classLoader
            .getResourceAsStream("io/atlasarc/governance/cycle-governance-v1.schema.json")
            ?.bufferedReader()
            ?.use { it.readText() }
        requireNotNull(schema)
        assertTrue(schema.contains("\"\$id\": \"$CYCLE_GOVERNANCE_SCHEMA_URI\""))
        assertTrue(schema.contains("\"additionalProperties\": false"))
    }

    @Test
    fun `record IDs are generated once and semantic reference IDs are portable and deterministic`() {
        val source = GovernanceIdentity("com.example.a", "com.example.a.Owner", "src/main/java/com/example/a/Owner.java")
        val target = GovernanceIdentity("com.example.b", "com.example.b.Target")
        val first = GovernanceIds.referenceId(
            "jvm:whole-project",
            GovernanceBackend.JVM_BYTECODE,
            GovernanceLanguage.JAVA,
            GovernanceLanguage.JAVA,
            source,
            target,
            GovernanceDependencyKind.METHOD_CALL,
        )
        val second = GovernanceIds.referenceId(
            "jvm:whole-project",
            GovernanceBackend.JVM_BYTECODE,
            GovernanceLanguage.JAVA,
            GovernanceLanguage.JAVA,
            source.copy(sourceFile = GovernancePaths.normalizeRepositoryRelative("src\\main\\java\\com\\example\\a\\Owner.java")),
            target,
            GovernanceDependencyKind.METHOD_CALL,
        )

        assertTrue(GovernanceIds.newRecordId().matches(Regex("cycle-[0-9a-f-]{36}")))
        assertEquals(first, GovernanceIds.referenceId(
            "jvm:whole-project",
            GovernanceBackend.JVM_BYTECODE,
            GovernanceLanguage.JAVA,
            GovernanceLanguage.JAVA,
            source,
            target,
            GovernanceDependencyKind.METHOD_CALL,
        ))
        assertTrue(first.startsWith("ref-") && first.length == 36)
        assertEquals(first, second)
    }

    @Test
    fun `module identity round-trips and disambiguates stable reference IDs`() {
        val orders = GovernanceIdentity("shared.left", module = "orders")
        val billing = orders.copy(module = "billing")
        val target = GovernanceIdentity("shared.right", module = "orders")
        val ordersId = GovernanceIds.referenceId(
            "jvm:whole-project",
            GovernanceBackend.JVM_BYTECODE,
            GovernanceLanguage.JAVA,
            GovernanceLanguage.JAVA,
            orders,
            target,
            GovernanceDependencyKind.METHOD_CALL,
        )
        val billingId = GovernanceIds.referenceId(
            "jvm:whole-project",
            GovernanceBackend.JVM_BYTECODE,
            GovernanceLanguage.JAVA,
            GovernanceLanguage.JAVA,
            billing,
            target.copy(module = "billing"),
            GovernanceDependencyKind.METHOD_CALL,
        )
        val document = CycleGovernanceDocument(
            records = mapOf(
                "orders-record" to CycleGovernanceRecord(
                    GovernanceAnalysisSource("jvm:whole-project", GovernanceBackend.JVM_BYTECODE, GovernanceLanguage.JAVA),
                    GovernanceScope.PACKAGE,
                    GovernanceOwnerSide.SOURCE,
                    orders,
                    target,
                    kind = CycleGovernanceKind.INTENTIONAL,
                    reason = "Orders owns this split-package direction.",
                ),
            ),
        )

        val encoded = assertInstanceOf(GovernanceEncodeResult.Success::class.java, codec.encode(document))
        val decoded = assertInstanceOf(GovernanceDecodeResult.Success::class.java, codec.decode(encoded.text))

        assertTrue(encoded.text.contains("\"module\": \"orders\""))
        assertEquals(document, decoded.document)
        assertTrue(ordersId != billingId)
    }

    private fun fixture(name: String): String = javaClass.classLoader
        .getResourceAsStream("contracts/$name")
        ?.bufferedReader(Charsets.UTF_8)
        ?.use { it.readText() }
        ?: error("Missing fixture $name")
}
