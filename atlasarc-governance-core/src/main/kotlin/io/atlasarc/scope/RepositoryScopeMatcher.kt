package io.atlasarc.scope

import io.atlasarc.governance.GovernanceBackend
import io.atlasarc.governance.GovernanceEvidenceNode
import io.atlasarc.governance.GovernanceEvidenceReference
import io.atlasarc.governance.GovernanceEvidenceSnapshot
import io.atlasarc.governance.GovernanceIdentity
import io.atlasarc.governance.GovernanceIssueSeverity

data class RepositoryScopeApplication(
    val evidence: GovernanceEvidenceSnapshot,
    val rules: List<RepositoryScopeRuleEvaluation>,
    val issues: List<RepositoryScopeIssue>,
    val summary: RepositoryScopeSummary,
)

/** Applies one repository policy to portable architecture-unit evidence before any verdict. */
class RepositoryScopeMatcher {
    fun apply(
        document: RepositoryScopeDocument,
        evidence: GovernanceEvidenceSnapshot,
    ): RepositoryScopeApplication {
        val units = buildSet {
            evidence.nodes.forEach { add(ScopeUnit(it.backend, it.identity.architectureUnit, it.identity.module)) }
            evidence.references.forEach {
                add(ScopeUnit(it.backend, it.source.architectureUnit, it.source.module))
                add(ScopeUnit(it.backend, it.target.architectureUnit, it.target.module))
            }
        }
        val matchesByRule = document.exclusions.toSortedMap().mapValues { (_, exclusion) ->
            units.filterTo(sortedSetOf()) { unit -> matches(exclusion.selector, unit, evidence.caseSensitive) }
        }
        val excluded = matchesByRule.values.flatten().toSet()
        val retainedNodes = evidence.nodes.filterNot { it.scopeUnit() in excluded }
        val retainedReferences = evidence.references.filterNot {
            it.sourceScopeUnit() in excluded || it.targetScopeUnit() in excluded
        }
        val rules = matchesByRule.map { (ruleId, matches) ->
            RepositoryScopeRuleEvaluation(ruleId, matches.size)
        }
        val issues = rules.filter { it.matchedArchitectureUnitCount == 0 }.map { rule ->
            RepositoryScopeIssue(
                code = "stale-scope-rule",
                message = "Repository scope rule '${rule.ruleId}' does not match current architecture evidence.",
                severity = GovernanceIssueSeverity.WARNING,
                ruleId = rule.ruleId,
            )
        }
        return RepositoryScopeApplication(
            evidence = evidence.copy(nodes = retainedNodes, references = retainedReferences),
            rules = rules,
            issues = issues,
            summary = RepositoryScopeSummary(
                ruleCount = rules.size,
                appliedRuleCount = rules.count { it.matchedArchitectureUnitCount > 0 },
                staleRuleCount = issues.size,
                architectureUnitCountBefore = units.size,
                architectureUnitCountAfter = units.count { it !in excluded },
                referenceCountBefore = evidence.references.size,
                referenceCountAfter = retainedReferences.size,
            ),
        )
    }

    fun matches(
        selector: RepositoryScopeSelector,
        backend: GovernanceBackend,
        identity: GovernanceIdentity,
        caseSensitive: Boolean = true,
    ): Boolean = matches(
        selector,
        ScopeUnit(backend, identity.architectureUnit, identity.module),
        caseSensitive,
    )

    fun matchesAny(
        document: RepositoryScopeDocument,
        backend: GovernanceBackend,
        identity: GovernanceIdentity,
        caseSensitive: Boolean = true,
    ): Boolean = document.exclusions.values.any { exclusion ->
        matches(exclusion.selector, backend, identity, caseSensitive)
    }

    private fun matches(selector: RepositoryScopeSelector, unit: ScopeUnit, caseSensitive: Boolean): Boolean {
        val expectedBackend = when (selector.kind) {
            RepositoryScopeSelectorKind.JVM_PACKAGE_PATTERN -> GovernanceBackend.JVM_BYTECODE
            RepositoryScopeSelectorKind.TYPESCRIPT_SOURCE_FOLDER_PATTERN -> GovernanceBackend.TYPESCRIPT_ARTIFACT
        }
        if (unit.backend != expectedBackend) return false
        if (selector.kind == RepositoryScopeSelectorKind.JVM_PACKAGE_PATTERN) {
            val moduleMatches = when (selector.module) {
                null -> unit.module == null
                "*" -> true
                else -> selector.module == unit.module
            }
            if (!moduleMatches) return false
        }
        val separator = if (unit.backend == GovernanceBackend.JVM_BYTECODE) '.' else '/'
        val pattern = selector.pattern.split(separator)
        val value = normalize(unit.architectureUnit, unit.backend).split(separator)
        return globMatches(pattern, value, caseSensitive)
    }

    private fun normalize(value: String, backend: GovernanceBackend): String = when (backend) {
        GovernanceBackend.JVM_BYTECODE -> value
        GovernanceBackend.TYPESCRIPT_ARTIFACT -> value.replace('\\', '/').removePrefix("./").trim('/')
    }

    private fun globMatches(pattern: List<String>, value: List<String>, caseSensitive: Boolean): Boolean {
        val memo = mutableMapOf<Pair<Int, Int>, Boolean>()
        fun matchesAt(patternIndex: Int, valueIndex: Int): Boolean = memo.getOrPut(patternIndex to valueIndex) {
            when {
                patternIndex == pattern.size -> valueIndex == value.size
                pattern[patternIndex] == "**" ->
                    matchesAt(patternIndex + 1, valueIndex) ||
                        (valueIndex < value.size && matchesAt(patternIndex, valueIndex + 1))
                valueIndex == value.size -> false
                pattern[patternIndex] == "*" -> matchesAt(patternIndex + 1, valueIndex + 1)
                equals(pattern[patternIndex], value[valueIndex], caseSensitive) ->
                    matchesAt(patternIndex + 1, valueIndex + 1)
                else -> false
            }
        }
        return matchesAt(0, 0)
    }

    private fun equals(left: String, right: String, caseSensitive: Boolean): Boolean =
        if (caseSensitive) left == right else left.equals(right, ignoreCase = true)
}

private data class ScopeUnit(
    val backend: GovernanceBackend,
    val architectureUnit: String,
    val module: String?,
) : Comparable<ScopeUnit> {
    override fun compareTo(other: ScopeUnit): Int =
        compareValuesBy(this, other, { it.backend.name }, { it.module.orEmpty() }, { it.architectureUnit })
}

private fun GovernanceEvidenceNode.scopeUnit() = ScopeUnit(backend, identity.architectureUnit, identity.module)
private fun GovernanceEvidenceReference.sourceScopeUnit() = ScopeUnit(backend, source.architectureUnit, source.module)
private fun GovernanceEvidenceReference.targetScopeUnit() = ScopeUnit(backend, target.architectureUnit, target.module)
