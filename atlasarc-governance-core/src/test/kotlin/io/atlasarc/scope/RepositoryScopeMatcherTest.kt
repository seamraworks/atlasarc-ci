package io.atlasarc.scope

import io.atlasarc.governance.GovernanceBackend
import io.atlasarc.governance.GovernanceEvidenceNode
import io.atlasarc.governance.GovernanceEvidenceReference
import io.atlasarc.governance.GovernanceEvidenceSnapshot
import io.atlasarc.governance.GovernanceIdentity
import io.atlasarc.governance.GovernanceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryScopeMatcherTest {
    @Test
    fun `module-qualified JVM exclusion cannot hide an equal package in another module`() {
        val policy = policy(
            "generated" to selector(RepositoryScopeSelectorKind.JVM_PACKAGE_PATTERN, "com.acme.generated.**", "billing"),
        )
        val evidence = snapshot(
            nodes = listOf(
                node(GovernanceBackend.JVM_BYTECODE, "com.acme.generated", "billing"),
                node(GovernanceBackend.JVM_BYTECODE, "com.acme.generated", "orders"),
                node(GovernanceBackend.JVM_BYTECODE, "com.acme.core", "billing"),
            ),
            references = listOf(
                ref("billing-generated", GovernanceBackend.JVM_BYTECODE, "com.acme.generated", "com.acme.core", "billing", "billing"),
                ref("orders-generated", GovernanceBackend.JVM_BYTECODE, "com.acme.generated", "com.acme.core", "orders", "billing"),
            ),
        )

        val result = RepositoryScopeMatcher().apply(policy, evidence)

        assertEquals(setOf("orders-generated"), result.evidence.references.mapTo(linkedSetOf()) { it.id })
        assertTrue(result.evidence.nodes.any { it.identity.module == "orders" && it.identity.architectureUnit == "com.acme.generated" })
        assertFalse(result.evidence.nodes.any { it.identity.module == "billing" && it.identity.architectureUnit == "com.acme.generated" })
        assertEquals(1, result.summary.excludedArchitectureUnitCount)
        assertEquals(1, result.summary.excludedReferenceCount)
    }

    @Test
    fun `omitted JVM module means module-less while star intentionally spans ownership`() {
        val matcher = RepositoryScopeMatcher()
        val moduleless = GovernanceIdentity("com.acme.generated")
        val named = GovernanceIdentity("com.acme.generated", module = "app")
        val omitted = selector(RepositoryScopeSelectorKind.JVM_PACKAGE_PATTERN, "com.acme.generated", null)
        val any = selector(RepositoryScopeSelectorKind.JVM_PACKAGE_PATTERN, "com.acme.generated", "*")

        assertTrue(matcher.matches(omitted, GovernanceBackend.JVM_BYTECODE, moduleless))
        assertFalse(matcher.matches(omitted, GovernanceBackend.JVM_BYTECODE, named))
        assertTrue(matcher.matches(any, GovernanceBackend.JVM_BYTECODE, moduleless))
        assertTrue(matcher.matches(any, GovernanceBackend.JVM_BYTECODE, named))
    }

    @Test
    fun `segment wildcards handle Java subtrees and TypeScript folder patterns`() {
        val matcher = RepositoryScopeMatcher()
        val jvm = selector(RepositoryScopeSelectorKind.JVM_PACKAGE_PATTERN, "com.*.generated.**", "*")
        val typescript = selector(RepositoryScopeSelectorKind.TYPESCRIPT_SOURCE_FOLDER_PATTERN, "packages/**/generated/*")

        assertTrue(matcher.matches(jvm, GovernanceBackend.JVM_BYTECODE, GovernanceIdentity("com.acme.generated")))
        assertTrue(matcher.matches(jvm, GovernanceBackend.JVM_BYTECODE, GovernanceIdentity("com.acme.generated.adapters")))
        assertFalse(matcher.matches(jvm, GovernanceBackend.JVM_BYTECODE, GovernanceIdentity("com.acme.handwritten")))
        assertTrue(matcher.matches(typescript, GovernanceBackend.TYPESCRIPT_ARTIFACT, GovernanceIdentity("packages/app/generated/api")))
        assertTrue(matcher.matches(typescript, GovernanceBackend.TYPESCRIPT_ARTIFACT, GovernanceIdentity("packages/team/app/generated/api")))
        assertFalse(matcher.matches(typescript, GovernanceBackend.TYPESCRIPT_ARTIFACT, GovernanceIdentity("packages/app/generated")))
    }

    @Test
    fun `incident dependencies disappear and unmatched rules remain visible as stale warnings`() {
        val policy = policy(
            "vendor" to selector(RepositoryScopeSelectorKind.TYPESCRIPT_SOURCE_FOLDER_PATTERN, "src/vendor/**"),
            "renamed" to selector(RepositoryScopeSelectorKind.TYPESCRIPT_SOURCE_FOLDER_PATTERN, "src/old-name/**"),
        )
        val evidence = snapshot(
            nodes = listOf(
                node(GovernanceBackend.TYPESCRIPT_ARTIFACT, "src/app"),
                node(GovernanceBackend.TYPESCRIPT_ARTIFACT, "src/vendor"),
            ),
            references = listOf(
                ref("app-vendor", GovernanceBackend.TYPESCRIPT_ARTIFACT, "src/app", "src/vendor"),
                ref("vendor-app", GovernanceBackend.TYPESCRIPT_ARTIFACT, "src/vendor", "src/app"),
            ),
        )

        val result = RepositoryScopeMatcher().apply(policy, evidence)

        assertTrue(result.evidence.references.isEmpty())
        assertEquals(listOf("renamed"), result.issues.map { it.ruleId })
        assertEquals(1, result.summary.appliedRuleCount)
        assertEquals(1, result.summary.staleRuleCount)
    }

    @Test
    fun `evidence case policy controls architecture-unit comparison`() {
        val selector = selector(RepositoryScopeSelectorKind.TYPESCRIPT_SOURCE_FOLDER_PATTERN, "SRC/GENERATED/**")
        val matcher = RepositoryScopeMatcher()
        val identity = GovernanceIdentity("src/generated/api")
        assertFalse(matcher.matches(selector, GovernanceBackend.TYPESCRIPT_ARTIFACT, identity, caseSensitive = true))
        assertTrue(matcher.matches(selector, GovernanceBackend.TYPESCRIPT_ARTIFACT, identity, caseSensitive = false))
    }

    private fun policy(vararg entries: Pair<String, RepositoryScopeSelector>) = RepositoryScopeDocument(
        exclusions = entries.associateTo(linkedMapOf()) { (id, selector) ->
            id to RepositoryScopeExclusion(selector, "Outside the governed architecture.")
        },
    )

    private fun selector(kind: RepositoryScopeSelectorKind, pattern: String, module: String? = null) =
        RepositoryScopeSelector(kind, pattern, module)

    private fun snapshot(
        nodes: List<GovernanceEvidenceNode>,
        references: List<GovernanceEvidenceReference>,
    ) = GovernanceEvidenceSnapshot(emptyList(), nodes, references)

    private fun node(backend: GovernanceBackend, unit: String, module: String? = null) = GovernanceEvidenceNode(
        "source",
        backend,
        if (backend == GovernanceBackend.JVM_BYTECODE) GovernanceLanguage.JAVA else GovernanceLanguage.TYPESCRIPT,
        GovernanceIdentity(unit, module = module),
    )

    private fun ref(
        id: String,
        backend: GovernanceBackend,
        source: String,
        target: String,
        sourceModule: String? = null,
        targetModule: String? = null,
    ) = GovernanceEvidenceReference(
        id,
        "source",
        backend,
        if (backend == GovernanceBackend.JVM_BYTECODE) GovernanceLanguage.JAVA else GovernanceLanguage.TYPESCRIPT,
        if (backend == GovernanceBackend.JVM_BYTECODE) GovernanceLanguage.JAVA else GovernanceLanguage.TYPESCRIPT,
        GovernanceIdentity(source, module = sourceModule),
        GovernanceIdentity(target, module = targetModule),
    )
}
