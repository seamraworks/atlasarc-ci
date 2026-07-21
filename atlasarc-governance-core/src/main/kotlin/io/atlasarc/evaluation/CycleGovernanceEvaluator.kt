package io.atlasarc.evaluation

import io.atlasarc.governance.CycleGovernanceDocument
import io.atlasarc.governance.CycleGovernanceKind
import io.atlasarc.governance.CycleGovernanceMatcher
import io.atlasarc.governance.GovernanceBackend
import io.atlasarc.governance.GovernanceEvidenceNode
import io.atlasarc.governance.GovernanceEvidenceReference
import io.atlasarc.governance.GovernanceEvidenceSnapshot
import io.atlasarc.governance.GovernanceEvidenceSource
import io.atlasarc.governance.GovernanceIdentity
import io.atlasarc.governance.GovernanceIssueSeverity
import io.atlasarc.governance.GovernanceRecordStatus
import io.atlasarc.governance.GovernanceRecordMatch
import io.atlasarc.scope.RepositoryScopeEvaluationContext
import io.atlasarc.scope.RepositoryScopeMatcher
import java.nio.charset.StandardCharsets

/**
 * A portable evidence contribution to one repository evaluation.
 *
 * Acquisition adapters own bytecode or artifact parsing. The core receives only semantic evidence
 * and deterministic diagnostics; it has no dependency on IntelliJ, ArchUnit, Node, or a build tool.
 */
data class GovernanceEvaluationInput(
    val evidence: GovernanceEvidenceSnapshot,
    val issues: List<GovernanceEvaluationIssue> = emptyList(),
)

/** Evaluates repository cycle governance over one or more acquired evidence sources. */
class CycleGovernanceEvaluator(
    private val matcher: CycleGovernanceMatcher = CycleGovernanceMatcher(),
    private val scopeMatcher: RepositoryScopeMatcher = RepositoryScopeMatcher(),
) {
    fun evaluate(
        document: CycleGovernanceDocument,
        inputs: List<GovernanceEvaluationInput>,
        evaluatorVersion: String,
        repositoryScope: RepositoryScopeEvaluationContext = RepositoryScopeEvaluationContext(),
    ): GovernanceEvaluationResult {
        val issues = inputs.flatMapTo(mutableListOf()) { it.issues }
        if (inputs.isEmpty()) {
            issues += GovernanceEvaluationIssue(
                code = "no-analysis-sources",
                message = "No analysis source was configured.",
                severity = GovernanceIssueSeverity.ERROR,
            )
        }

        val snapshots = inputs.map(GovernanceEvaluationInput::evidence)
        val sources = snapshots.flatMap(GovernanceEvidenceSnapshot::sources)
        sources.groupBy { it.id to it.backend }
            .filterValues { it.size > 1 }
            .keys
            .sortedBy { "${it.first}|${it.second.name}" }
            .forEach { (id, _) ->
                issues += GovernanceEvaluationIssue(
                    code = "duplicate-analysis-source",
                    message = "Analysis source '$id' is configured more than once.",
                    severity = GovernanceIssueSeverity.ERROR,
                    analysisSourceId = id,
                )
            }
        sources.filterNot(GovernanceEvidenceSource::fresh).forEach { source ->
            issues += GovernanceEvaluationIssue(
                code = "stale-analysis-evidence",
                message = source.freshnessDiagnostic
                    ?: "Analysis evidence is stale; regenerate it before evaluating governance.",
                severity = GovernanceIssueSeverity.ERROR,
                analysisSourceId = source.id,
            )
        }
        val casePolicies = snapshots.map(GovernanceEvidenceSnapshot::caseSensitive).distinct()
        if (casePolicies.size > 1) {
            issues += GovernanceEvaluationIssue(
                code = "mixed-case-policy",
                message = "All analysis sources in one evaluation must use the same path case policy.",
                severity = GovernanceIssueSeverity.ERROR,
            )
        }

        val combined = GovernanceEvidenceSnapshot(
            sources = sources.distinctBy { it.id to it.backend }
                .sortedWith(compareBy({ it.id }, { it.backend.name })),
            nodes = snapshots.flatMap(GovernanceEvidenceSnapshot::nodes).distinct().sortedBy(::nodeSortKey),
            references = snapshots.flatMap(GovernanceEvidenceSnapshot::references)
                .distinctBy(GovernanceEvidenceReference::id)
                .sortedBy(GovernanceEvidenceReference::id),
            caseSensitive = casePolicies.firstOrNull() ?: true,
            evaluationComplete = snapshots.all(GovernanceEvidenceSnapshot::evaluationComplete),
        )
        val scopeApplication = scopeMatcher.apply(repositoryScope.document, combined)
        scopeApplication.issues.forEach { issue ->
            issues += GovernanceEvaluationIssue(
                code = issue.code,
                message = issue.message,
                severity = issue.severity,
                scopeRuleId = issue.ruleId,
            )
        }
        val scopedEvidence = scopeApplication.evidence
        val rawMatchResult = matcher.match(document, scopedEvidence)
        val scopedOutRecordIds = document.records.filterValues { record ->
            scopeMatcher.matchesAny(
                repositoryScope.document,
                record.analysisSource.backend,
                record.source,
                combined.caseSensitive,
            ) || scopeMatcher.matchesAny(
                repositoryScope.document,
                record.analysisSource.backend,
                record.target,
                combined.caseSensitive,
            )
        }.keys
        val matchResult = rawMatchResult.copy(
            records = rawMatchResult.records.mapValues { (recordId, match) ->
                if (recordId !in scopedOutRecordIds) match else GovernanceRecordMatch(
                    recordId = recordId,
                    status = GovernanceRecordStatus.NOT_IN_ANALYSIS,
                    diagnostics = listOf("This governance record is outside the repository analysis-scope policy."),
                )
            },
        )
        val records = matchResult.records.toSortedMap().map { (recordId, match) ->
            val record = document.records.getValue(recordId)
            GovernanceRecordEvaluation(
                recordId = recordId,
                analysisSourceId = record.analysisSource.id,
                status = match.status,
                kind = record.kind,
                matchedReferenceIds = match.matchedReferenceIds.sorted(),
                diagnostics = match.diagnostics.sorted(),
            )
        }

        val invalidStatuses = setOf(
            GovernanceRecordStatus.MISSING_SOURCE,
            GovernanceRecordStatus.MISSING_TARGET,
            GovernanceRecordStatus.PARTIAL,
            GovernanceRecordStatus.AMBIGUOUS,
            GovernanceRecordStatus.UNSUPPORTED,
            GovernanceRecordStatus.INVALID,
        )
        records.filter { it.status in invalidStatuses }.forEach { record ->
            issues += GovernanceEvaluationIssue(
                code = "governance-record-${record.status.name.lowercase().replace('_', '-')}",
                message = record.diagnostics.firstOrNull()
                    ?: "Governance record '${record.recordId}' is ${record.status.name.lowercase()}.",
                severity = GovernanceIssueSeverity.ERROR,
                analysisSourceId = record.analysisSourceId,
                recordId = record.recordId,
            )
        }

        val groups = mutableListOf<GovernanceProblemGroup>()
        val edges = mutableListOf<GovernanceProblemEdge>()
        combined.sources.sortedWith(compareBy({ it.id }, { it.backend.name })).forEach { source ->
            val sourceReferences = scopedEvidence.references.filter {
                it.analysisSourceId == source.id && it.backend == source.backend
            }
            val structuralEdges = sourceReferences.groupBy { reference ->
                UnitEdge(
                    UnitKey(source.backend, reference.source),
                    UnitKey(source.backend, reference.target),
                )
            }
            val problemEdges = structuralEdges.filterValues { references ->
                references.any { it.id !in matchResult.coverage }
            }
            val nodes = problemEdges.keys.flatMapTo(sortedSetOf()) { listOf(it.source, it.target) }
            val sccs = findStronglyConnectedComponents(nodes, problemEdges.keys)
                .sortedBy { members -> members.minOf(UnitKey::nodeKey) }

            sccs.forEachIndexed { groupId, members ->
                val memberEdges = structuralEdges.filterKeys { it.source in members && it.target in members }
                groups += GovernanceProblemGroup(
                    analysisSourceId = source.id,
                    id = groupId,
                    type = cycleType(members),
                    members = members.sorted().map(UnitKey::toEvaluationUnit),
                    edgeCount = memberEdges.size,
                )
                memberEdges.toSortedMap().forEach { (edge, references) ->
                    val referenceIds = references.mapTo(sortedSetOf(), GovernanceEvidenceReference::id)
                    val governed = referenceIds.filterTo(sortedSetOf()) { it in matchResult.coverage }
                    edges += GovernanceProblemEdge(
                        analysisSourceId = source.id,
                        cycleGroupId = groupId,
                        source = edge.source.toEvaluationUnit(),
                        target = edge.target.toEvaluationUnit(),
                        uncoveredReferenceIds = (referenceIds - governed).sorted(),
                        governedReferenceIds = governed.toList(),
                    )
                }
            }
        }

        val orderedSources = combined.sources.map { source ->
            GovernanceEvaluationSource(
                id = source.id,
                backend = source.backend,
                languages = source.languages.sortedBy { it.name },
                fresh = source.fresh,
            )
        }
        val orderedIssues = issues.distinct().sortedWith(
            compareBy(
                { it.analysisSourceId.orEmpty() },
                { it.recordId.orEmpty() },
                { it.scopeRuleId.orEmpty() },
                GovernanceEvaluationIssue::code,
                GovernanceEvaluationIssue::message,
            ),
        )
        val orderedGroups = groups.sortedWith(compareBy({ it.analysisSourceId }, { it.id }))
        val orderedEdges = edges.sortedWith(
            compareBy({ it.analysisSourceId }, { it.cycleGroupId }, { it.source.nodeKey }, { it.target.nodeKey }),
        )
        val verdict = when {
            orderedIssues.any { it.severity == GovernanceIssueSeverity.ERROR } -> GovernanceEvaluationVerdict.INVALID
            orderedGroups.isNotEmpty() -> GovernanceEvaluationVerdict.PROBLEMS
            else -> GovernanceEvaluationVerdict.CLEAN
        }
        return GovernanceEvaluationResult(
            producer = GovernanceEvaluationProducer(version = evaluatorVersion),
            governanceSchemaVersion = document.schemaVersion,
            verdict = verdict,
            sources = orderedSources,
            records = records,
            problemGroups = orderedGroups,
            problemEdges = orderedEdges,
            issues = orderedIssues,
            summary = GovernanceEvaluationSummary(
                sourceCount = orderedSources.size,
                recordCount = records.size,
                activeRecordCount = records.count { it.status == GovernanceRecordStatus.ACTIVE },
                debtRecordCount = records.count {
                    it.status == GovernanceRecordStatus.ACTIVE && it.kind == CycleGovernanceKind.DEBT
                },
                resolvedRecordCount = records.count { it.status == GovernanceRecordStatus.RESOLVED },
                invalidRecordCount = records.count { it.status in invalidStatuses },
                problemGroupCount = orderedGroups.size,
                problemEdgeCount = orderedEdges.size,
            ),
            repositoryScope = RepositoryScopeEvaluation(
                schemaVersion = repositoryScope.document.schemaVersion,
                exists = repositoryScope.exists,
                revision = repositoryScope.revision,
                rules = scopeApplication.rules,
                summary = scopeApplication.summary,
            ),
        )
    }
}

private data class UnitKey(
    val backend: GovernanceBackend,
    val architectureUnit: String,
    val module: String?,
) : Comparable<UnitKey> {
    constructor(backend: GovernanceBackend, identity: GovernanceIdentity) :
        this(backend, identity.architectureUnit, identity.module)

    val nodeKey: String = when (backend) {
        GovernanceBackend.JVM_BYTECODE -> module?.let {
            "jvm:module/${encode(it)}/package/${encode(architectureUnit)}"
        } ?: "jvm:moduleless/package/${encode(architectureUnit)}"
        GovernanceBackend.TYPESCRIPT_ARTIFACT -> architectureUnit
    }

    override fun compareTo(other: UnitKey): Int = nodeKey.compareTo(other.nodeKey)

    fun toEvaluationUnit(): GovernanceArchitectureUnit = GovernanceArchitectureUnit(
        nodeKey = nodeKey,
        architectureUnit = architectureUnit,
        module = module,
    )
}

private data class UnitEdge(val source: UnitKey, val target: UnitKey) : Comparable<UnitEdge> {
    override fun compareTo(other: UnitEdge): Int =
        compareValuesBy(this, other, { it.source.nodeKey }, { it.target.nodeKey })
}

private fun nodeSortKey(node: GovernanceEvidenceNode): String = listOf(
    node.analysisSourceId,
    node.backend.name,
    node.language.name,
    node.identity.architectureUnit,
    node.identity.type.orEmpty(),
    node.identity.sourceFile.orEmpty(),
    node.identity.member?.name.orEmpty(),
    node.identity.member?.descriptor.orEmpty(),
    node.identity.module.orEmpty(),
).joinToString("|")

private fun cycleType(members: Set<UnitKey>): String {
    val ordered = members.sorted()
    for (left in ordered.indices) {
        for (right in left + 1 until ordered.size) {
            if (!ordered[left].isAncestorOf(ordered[right]) && !ordered[right].isAncestorOf(ordered[left])) {
                return "architectural"
            }
        }
    }
    return "nested"
}

private fun UnitKey.isAncestorOf(other: UnitKey): Boolean {
    if (backend != other.backend || module != other.module) return false
    val separator = if (backend == GovernanceBackend.TYPESCRIPT_ARTIFACT) '/' else '.'
    return other.architectureUnit.startsWith("$architectureUnit$separator")
}

private fun findStronglyConnectedComponents(
    nodes: Set<UnitKey>,
    edges: Set<UnitEdge>,
): List<Set<UnitKey>> {
    val adjacency = nodes.associateWithTo(linkedMapOf()) { mutableListOf<UnitKey>() }
    edges.sorted().forEach { edge ->
        if (edge.source in nodes && edge.target in nodes) adjacency.getValue(edge.source) += edge.target
    }
    adjacency.values.forEach(MutableList<UnitKey>::sort)

    val index = mutableMapOf<UnitKey, Int>()
    val lowLink = mutableMapOf<UnitKey, Int>()
    val stack = ArrayDeque<UnitKey>()
    val onStack = mutableSetOf<UnitKey>()
    val result = mutableListOf<Set<UnitKey>>()
    var nextIndex = 0

    fun connect(node: UnitKey) {
        index[node] = nextIndex
        lowLink[node] = nextIndex
        nextIndex++
        stack.addLast(node)
        onStack += node

        adjacency.getValue(node).forEach { target ->
            if (target !in index) {
                connect(target)
                lowLink[node] = minOf(lowLink.getValue(node), lowLink.getValue(target))
            } else if (target in onStack) {
                lowLink[node] = minOf(lowLink.getValue(node), index.getValue(target))
            }
        }
        if (lowLink[node] == index[node]) {
            val component = sortedSetOf<UnitKey>()
            do {
                val member = stack.removeLast()
                onStack -= member
                component += member
            } while (member != node)
            if (component.size >= 2) result += component
        }
    }
    nodes.sorted().forEach { if (it !in index) connect(it) }
    return result
}

private fun encode(value: String): String = buildString {
    value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        val safe = unsigned in 'a'.code..'z'.code ||
            unsigned in 'A'.code..'Z'.code ||
            unsigned in '0'.code..'9'.code ||
            unsigned == '-'.code || unsigned == '_'.code || unsigned == '.'.code || unsigned == '~'.code
        if (safe) append(unsigned.toChar()) else append('%').append(unsigned.toString(16).uppercase().padStart(2, '0'))
    }
}
