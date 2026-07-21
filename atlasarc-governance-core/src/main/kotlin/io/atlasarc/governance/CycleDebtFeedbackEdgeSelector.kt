package io.atlasarc.governance

import io.atlasarc.evaluation.GovernanceProblemEdge
import java.util.ArrayDeque
import java.util.TreeSet

/**
 * Selects a deterministic feedback-edge set from current problem-cycle evidence.
 *
 * Small strongly connected components are solved exactly: first minimize the number of governed
 * unit edges, then the number of exact references on those edges. Larger components use a bounded
 * deterministic ordering heuristic and remove redundant cuts before returning. Callers must still
 * prove the resulting governance document through the evaluator.
 */
internal class CycleDebtFeedbackEdgeSelector(
    private val exactNodeLimit: Int = DEFAULT_EXACT_NODE_LIMIT,
) {
    init {
        require(exactNodeLimit in 2..MAX_EXACT_NODE_LIMIT) {
            "exactNodeLimit must be between 2 and $MAX_EXACT_NODE_LIMIT"
        }
    }

    fun select(problemEdges: List<GovernanceProblemEdge>): List<GovernanceProblemEdge> {
        val candidates = problemEdges
            .asSequence()
            .filter { it.uncoveredReferenceIds.isNotEmpty() }
            .distinctBy(::scopedEdgeKey)
            .map(::CandidateEdge)
            .toList()
        if (candidates.isEmpty()) return emptyList()

        val selected = linkedSetOf<CandidateEdge>()
        candidates.groupBy { it.groupKey }
            .toSortedMap()
            .forEach { (_, groupEdges) ->
                selected += groupEdges.filter { it.source == it.target }
                stronglyConnectedComponents(groupEdges)
                    .filter { it.size > 1 }
                    .forEach { members ->
                        val memberEdges = groupEdges.filter {
                            it.source != it.target && it.source in members && it.target in members
                        }
                        selected += if (members.size <= exactNodeLimit) {
                            exactFeedbackEdges(members.sorted(), memberEdges)
                        } else {
                            boundedFeedbackEdges(members.sorted(), memberEdges)
                        }
                    }
            }

        return selected.sortedWith(candidateComparator).map(CandidateEdge::original)
    }

    private fun exactFeedbackEdges(
        nodes: List<String>,
        edges: List<CandidateEdge>,
    ): Set<CandidateEdge> {
        val nodeIndex = nodes.withIndex().associate { it.value to it.index }
        val edgesByPair = edges.associateBy { nodeIndex.getValue(it.source) to nodeIndex.getValue(it.target) }
        val stateCount = 1 shl nodes.size
        val cutEdges = IntArray(stateCount) { INFINITE_EDGE_COUNT }
        val cutReferences = LongArray(stateCount) { Long.MAX_VALUE }
        val lastNode = IntArray(stateCount) { -1 }
        cutEdges[0] = 0
        cutReferences[0] = 0

        for (mask in 1 until stateCount) {
            var remaining = mask
            while (remaining != 0) {
                val last = Integer.numberOfTrailingZeros(remaining)
                val previousMask = mask xor (1 shl last)
                var addedEdges = 0
                var addedReferences = 0L
                var previousNodes = previousMask
                while (previousNodes != 0) {
                    val previous = Integer.numberOfTrailingZeros(previousNodes)
                    edgesByPair[last to previous]?.let { edge ->
                        addedEdges++
                        addedReferences += edge.referenceCount
                    }
                    previousNodes = previousNodes and (previousNodes - 1)
                }
                val candidateEdges = cutEdges[previousMask] + addedEdges
                val candidateReferences = cutReferences[previousMask] + addedReferences
                if (
                    candidateEdges < cutEdges[mask] ||
                    candidateEdges == cutEdges[mask] && candidateReferences < cutReferences[mask] ||
                    candidateEdges == cutEdges[mask] && candidateReferences == cutReferences[mask] &&
                    (lastNode[mask] == -1 || last < lastNode[mask])
                ) {
                    cutEdges[mask] = candidateEdges
                    cutReferences[mask] = candidateReferences
                    lastNode[mask] = last
                }
                remaining = remaining and (remaining - 1)
            }
        }

        var mask = stateCount - 1
        val reverseOrder = ArrayList<Int>(nodes.size)
        while (mask != 0) {
            val last = lastNode[mask]
            check(last >= 0) { "Exact feedback-edge ordering could not be reconstructed." }
            reverseOrder += last
            mask = mask xor (1 shl last)
        }
        val order = reverseOrder.asReversed()
        val position = IntArray(nodes.size)
        order.forEachIndexed { index, node -> position[node] = index }
        return edges.filterTo(linkedSetOf()) { edge ->
            position[nodeIndex.getValue(edge.source)] > position[nodeIndex.getValue(edge.target)]
        }
    }

    private fun boundedFeedbackEdges(
        nodes: List<String>,
        edges: List<CandidateEdge>,
    ): Set<CandidateEdge> {
        val orderings = listOf(
            weightedOrdering(nodes, edges, reverseStableTie = false),
            weightedOrdering(nodes, edges, reverseStableTie = true),
            nodes,
            nodes.asReversed(),
        ).distinct()
        val candidates = orderings.flatMap { ordering ->
            val initial = cutsForOrdering(ordering, edges)
            listOf(
                pruneCuts(edges, initial, initial.sortedWith(expensiveFirstComparator)),
                pruneCuts(edges, initial, initial.sortedWith(candidateComparator)),
                pruneCuts(edges, initial, initial.sortedWith(candidateComparator.reversed())),
            )
        }
        return candidates.minWithOrNull(::compareCuts) ?: emptySet()
    }

    private fun weightedOrdering(
        nodes: List<String>,
        edges: List<CandidateEdge>,
        reverseStableTie: Boolean,
    ): List<String> {
        val remaining = nodes.toMutableSet()
        val left = mutableListOf<String>()
        val right = ArrayDeque<String>()
        val outgoing = edges.groupBy(CandidateEdge::source)
        val incoming = edges.groupBy(CandidateEdge::target)
        val outDegree = nodes.associateWith { outgoing[it].orEmpty().size }.toMutableMap()
        val inDegree = nodes.associateWith { incoming[it].orEmpty().size }.toMutableMap()
        val outReferences = nodes.associateWith { outgoing[it].orEmpty().sumOf(CandidateEdge::referenceCount) }.toMutableMap()
        val inReferences = nodes.associateWith { incoming[it].orEmpty().sumOf(CandidateEdge::referenceCount) }.toMutableMap()
        val stableComparator = if (reverseStableTie) compareByDescending<String> { it } else naturalOrder()
        val sources = TreeSet(stableComparator)
        val sinks = TreeSet(stableComparator)

        fun refreshBoundary(node: String) {
            if (node !in remaining) return
            if (inDegree.getValue(node) == 0) sources += node
            if (outDegree.getValue(node) == 0) sinks += node
        }

        fun removeNode(node: String) {
            check(remaining.remove(node)) { "Heuristic node '$node' was already removed." }
            sources.remove(node)
            sinks.remove(node)
            outgoing[node].orEmpty().forEach { edge ->
                if (edge.target in remaining) {
                    inDegree[edge.target] = inDegree.getValue(edge.target) - 1
                    inReferences[edge.target] = inReferences.getValue(edge.target) - edge.referenceCount
                    refreshBoundary(edge.target)
                }
            }
            incoming[node].orEmpty().forEach { edge ->
                if (edge.source in remaining) {
                    outDegree[edge.source] = outDegree.getValue(edge.source) - 1
                    outReferences[edge.source] = outReferences.getValue(edge.source) - edge.referenceCount
                    refreshBoundary(edge.source)
                }
            }
        }

        nodes.forEach(::refreshBoundary)
        while (remaining.isNotEmpty()) {
            when {
                sinks.isNotEmpty() -> {
                    val sink = sinks.first()
                    removeNode(sink)
                    right.addFirst(sink)
                }
                sources.isNotEmpty() -> {
                    val source = sources.first()
                    removeNode(source)
                    left += source
                }
                else -> {
                    var selected: String? = null
                    var selectedScore: NodeScore? = null
                    remaining.forEach { node ->
                        val score = NodeScore(
                            edgeDelta = outDegree.getValue(node) - inDegree.getValue(node),
                            referenceDelta = outReferences.getValue(node) - inReferences.getValue(node),
                        )
                        val betterScore = selectedScore == null || score > selectedScore!!
                        val betterTie = score == selectedScore && selected != null &&
                            if (reverseStableTie) node > selected!! else node < selected!!
                        if (betterScore || betterTie) {
                            selected = node
                            selectedScore = score
                        }
                    }
                    val node = requireNotNull(selected)
                    removeNode(node)
                    left += node
                }
            }
        }
        return left + right
    }

    private fun cutsForOrdering(order: List<String>, edges: List<CandidateEdge>): Set<CandidateEdge> {
        val position = order.withIndex().associate { it.value to it.index }
        return edges.filterTo(linkedSetOf()) {
            position.getValue(it.source) > position.getValue(it.target)
        }
    }

    private fun pruneCuts(
        allEdges: List<CandidateEdge>,
        initialCuts: Set<CandidateEdge>,
        reconsiderationOrder: List<CandidateEdge>,
    ): Set<CandidateEdge> {
        val cuts = initialCuts.toMutableSet()
        val retained = allEdges.filterTo(mutableSetOf()) { it !in cuts }
        reconsiderationOrder.forEach { edge ->
            if (!pathExists(edge.target, edge.source, retained)) {
                cuts.remove(edge)
                retained += edge
            }
        }
        return cuts
    }

    private fun pathExists(from: String, to: String, edges: Set<CandidateEdge>): Boolean {
        if (from == to) return true
        val adjacency = edges.groupBy({ it.source }, { it.target })
        val pending = ArrayDeque<String>()
        val visited = hashSetOf<String>()
        pending += from
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (!visited.add(node)) continue
            adjacency[node].orEmpty().forEach { target ->
                if (target == to) return true
                if (target !in visited) pending += target
            }
        }
        return false
    }

    private fun stronglyConnectedComponents(edges: List<CandidateEdge>): List<Set<String>> {
        val nodes = edges.flatMapTo(sortedSetOf()) { listOf(it.source, it.target) }
        val adjacency = edges.groupBy({ it.source }, { it.target }).mapValues { (_, targets) -> targets.sorted() }
        val reverseAdjacency = edges.groupBy({ it.target }, { it.source }).mapValues { (_, sources) -> sources.sorted() }
        val visited = hashSetOf<String>()
        val finishOrder = mutableListOf<String>()
        nodes.forEach { start ->
            if (start in visited) return@forEach
            val pending = ArrayDeque<TraversalFrame>()
            pending.addLast(TraversalFrame(start, expanded = false))
            while (pending.isNotEmpty()) {
                val frame = pending.removeLast()
                if (frame.expanded) {
                    finishOrder += frame.node
                } else if (visited.add(frame.node)) {
                    pending.addLast(frame.copy(expanded = true))
                    adjacency[frame.node].orEmpty().asReversed().forEach { target ->
                        if (target !in visited) pending.addLast(TraversalFrame(target, expanded = false))
                    }
                }
            }
        }

        val assigned = hashSetOf<String>()
        val components = mutableListOf<Set<String>>()
        finishOrder.asReversed().forEach { start ->
            if (!assigned.add(start)) return@forEach
            val component = sortedSetOf<String>()
            val pending = ArrayDeque<String>()
            pending += start
            while (pending.isNotEmpty()) {
                val node = pending.removeLast()
                component += node
                reverseAdjacency[node].orEmpty().asReversed().forEach { source ->
                    if (assigned.add(source)) pending += source
                }
            }
            components += component
        }
        return components.sortedBy { it.first() }
    }

    private fun compareCuts(left: Set<CandidateEdge>, right: Set<CandidateEdge>): Int {
        val edgeComparison = left.size.compareTo(right.size)
        if (edgeComparison != 0) return edgeComparison
        val referenceComparison = left.sumOf(CandidateEdge::referenceCount)
            .compareTo(right.sumOf(CandidateEdge::referenceCount))
        if (referenceComparison != 0) return referenceComparison
        val leftKeys = left.sortedWith(candidateComparator).map(CandidateEdge::stableKey)
        val rightKeys = right.sortedWith(candidateComparator).map(CandidateEdge::stableKey)
        return compareLexicographically(leftKeys, rightKeys)
    }

    private fun compareLexicographically(left: List<String>, right: List<String>): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val comparison = left[index].compareTo(right[index])
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    private data class GroupKey(val analysisSourceId: String, val cycleGroupId: Int) : Comparable<GroupKey> {
        override fun compareTo(other: GroupKey) =
            compareValuesBy(this, other, GroupKey::analysisSourceId, GroupKey::cycleGroupId)
    }

    private data class CandidateEdge(val original: GovernanceProblemEdge) {
        val groupKey = GroupKey(original.analysisSourceId, original.cycleGroupId)
        val source: String = original.source.nodeKey
        val target: String = original.target.nodeKey
        val referenceCount: Int = original.uncoveredReferenceIds.size
        val stableKey: String = "${groupKey.analysisSourceId}:${groupKey.cycleGroupId}:$source->$target"
    }

    private data class NodeScore(val edgeDelta: Int, val referenceDelta: Int) : Comparable<NodeScore> {
        override fun compareTo(other: NodeScore) =
            compareValuesBy(this, other, NodeScore::edgeDelta, NodeScore::referenceDelta)
    }

    private data class TraversalFrame(val node: String, val expanded: Boolean)

    private companion object {
        const val DEFAULT_EXACT_NODE_LIMIT = 18
        const val MAX_EXACT_NODE_LIMIT = 20
        const val INFINITE_EDGE_COUNT = Int.MAX_VALUE / 4

        val candidateComparator = compareBy<CandidateEdge>(CandidateEdge::stableKey)
        val expensiveFirstComparator = compareByDescending<CandidateEdge>(CandidateEdge::referenceCount)
            .then(candidateComparator)

        fun scopedEdgeKey(edge: GovernanceProblemEdge) =
            "${edge.analysisSourceId}:${edge.cycleGroupId}:${edge.source.nodeKey}->${edge.target.nodeKey}"
    }
}
