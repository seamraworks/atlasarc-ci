package io.atlasarc.governance

import io.atlasarc.evaluation.GovernanceArchitectureUnit
import io.atlasarc.evaluation.GovernanceProblemEdge
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeout
import java.time.Duration
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CycleDebtFeedbackEdgeSelectorTest {
    private val selector = CycleDebtFeedbackEdgeSelector()

    @Test
    fun `chooses the direction with fewer concrete references in a two-node cycle`() {
        val edges = listOf(
            edge("a", "b", referenceCount = 2),
            edge("b", "a", referenceCount = 7),
        )

        val selected = selector.select(edges)

        assertEquals(listOf("a->b"), selected.map(::edgeKey))
        assertAcyclic(edges, selected)
    }

    @Test
    fun `minimizes selected edges before reference count when cycles share an edge`() {
        val edges = listOf(
            edge("a", "b", referenceCount = 5),
            edge("b", "c"),
            edge("c", "a"),
            edge("b", "d"),
            edge("d", "a"),
        )

        val selected = selector.select(edges)

        assertEquals(listOf("a->b"), selected.map(::edgeKey))
        assertEquals(5, selected.sumOf { it.uncoveredReferenceIds.size })
        assertAcyclic(edges, selected)
    }

    @Test
    fun `handles self loops ordinary cycles and already governed edges together`() {
        val edges = listOf(
            edge("self", "self", referenceCount = 2),
            edge("b", "c", referenceCount = 3),
            edge("c", "b", referenceCount = 1),
            edge("governed", "elsewhere", referenceCount = 0),
        )

        val selected = selector.select(edges)

        assertEquals(setOf("self->self", "c->b"), selected.mapTo(sortedSetOf(), ::edgeKey))
        assertFalse(selected.any { it.uncoveredReferenceIds.isEmpty() })
        assertAcyclic(edges, selected)
    }

    @Test
    fun `selects independently across many problem groups`() {
        val edges = (0 until 75).flatMap { group ->
            listOf(
                edge("a$group", "b$group", referenceCount = 2, group = group),
                edge("b$group", "a$group", referenceCount = 1, group = group),
            )
        }

        val selected = selector.select(edges)

        assertEquals(75, selected.size)
        assertTrue(selected.all { it.source.architectureUnit.startsWith("b") })
        assertAcyclic(edges, selected)
    }

    @Test
    fun `recomputes and selects many independent SCCs even when evidence shares one group`() {
        val edges = (0 until 60).flatMap { component ->
            listOf(
                edge("left$component", "right$component", referenceCount = 3),
                edge("right$component", "left$component", referenceCount = 1),
            )
        }

        val selected = selector.select(edges)

        assertEquals(60, selected.size)
        assertTrue(selected.all { it.source.architectureUnit.startsWith("right") })
        assertAcyclic(edges, selected)
    }

    @Test
    fun `large ring uses the bounded path and still chooses one cheapest stable cut`() {
        val nodeCount = 96
        val edges = (0 until nodeCount).map { index ->
            val target = (index + 1) % nodeCount
            edge(
                source = "n${index.toString().padStart(3, '0')}",
                target = "n${target.toString().padStart(3, '0')}",
                referenceCount = if (index == 57) 1 else 4,
            )
        }
        val shuffled = edges.shuffled(Random(42))
        val boundedSelector = CycleDebtFeedbackEdgeSelector(exactNodeLimit = 12)

        val selected = assertTimeout(Duration.ofSeconds(3)) { boundedSelector.select(shuffled) }

        assertEquals(listOf("n057->n058"), selected.map(::edgeKey))
        assertEquals(selected, boundedSelector.select(edges.reversed()))
        assertAcyclic(edges, selected)
    }

    @Test
    fun `large dense SCC completes without exponential search and produces an acyclic cut`() {
        val nodeCount = 40
        val edges = buildList {
            for (left in 0 until nodeCount) {
                for (right in left + 1 until nodeCount) {
                    add(edge("n$left", "n$right"))
                    add(edge("n$right", "n$left"))
                }
            }
        }
        val boundedSelector = CycleDebtFeedbackEdgeSelector(exactNodeLimit = 12)

        val selected = assertTimeout(Duration.ofSeconds(5)) { boundedSelector.select(edges) }

        assertEquals(nodeCount * (nodeCount - 1) / 2, selected.size)
        assertTrue(selected.size < edges.size)
        assertAcyclic(edges, selected)
    }

    @Test
    fun `very large sparse SCC does not depend on the call stack`() {
        val nodeCount = 2_000
        val edges = (0 until nodeCount).map { index ->
            edge("n$index", "n${(index + 1) % nodeCount}")
        }
        val boundedSelector = CycleDebtFeedbackEdgeSelector(exactNodeLimit = 12)

        val selected = assertTimeout(Duration.ofSeconds(5)) { boundedSelector.select(edges) }

        assertEquals(1, selected.size)
        assertAcyclic(edges, selected)
    }

    @Test
    fun `exact path matches brute force optimum across seeded small graphs`() {
        val random = Random(8675309)

        repeat(120) { sample ->
            val nodeCount = random.nextInt(2, 7)
            val possibleEdges = buildList {
                for (source in 0 until nodeCount) {
                    for (target in 0 until nodeCount) {
                        if (source != target) add(source to target)
                    }
                }
            }.shuffled(random)
            val edgeCount = random.nextInt(1, minOf(12, possibleEdges.size) + 1)
            val edges = possibleEdges.take(edgeCount).map { (source, target) ->
                edge("n$source", "n$target", referenceCount = random.nextInt(1, 6))
            }

            val selected = selector.select(edges)
            val expectedCost = bruteForceCost(edges)

            assertEquals(
                expectedCost,
                selected.size to selected.sumOf { it.uncoveredReferenceIds.size },
                "Unexpected feedback-edge cost for seeded sample $sample: ${edges.map(::edgeKey)}",
            )
            assertAcyclic(edges, selected)
        }
    }

    @Test
    fun `module-qualified node keys keep same-named package SCCs independent`() {
        fun moduleEdge(sourceModule: String, targetModule: String, source: String, target: String) =
            GovernanceProblemEdge(
                analysisSourceId = SOURCE,
                cycleGroupId = 0,
                source = GovernanceArchitectureUnit("$sourceModule:$source", source, sourceModule),
                target = GovernanceArchitectureUnit("$targetModule:$target", target, targetModule),
                uncoveredReferenceIds = listOf("$sourceModule:$source->$targetModule:$target"),
                governedReferenceIds = emptyList(),
            )
        val edges = listOf(
            moduleEdge("billing", "billing", "shared", "right"),
            moduleEdge("billing", "billing", "right", "shared"),
            moduleEdge("orders", "orders", "shared", "right"),
            moduleEdge("orders", "orders", "right", "shared"),
        )

        val selected = selector.select(edges)

        assertEquals(2, selected.size)
        assertEquals(setOf("billing", "orders"), selected.mapTo(sortedSetOf()) { it.source.module!! })
        assertAcyclic(edges, selected)
    }

    @Test
    fun `acyclic evidence does not produce debt cuts`() {
        val edges = listOf(edge("a", "b"), edge("a", "c"), edge("b", "d"), edge("c", "d"))

        assertTrue(selector.select(edges).isEmpty())
    }

    private fun edge(
        source: String,
        target: String,
        referenceCount: Int = 1,
        group: Int = 0,
    ) = GovernanceProblemEdge(
        analysisSourceId = SOURCE,
        cycleGroupId = group,
        source = GovernanceArchitectureUnit(source, source),
        target = GovernanceArchitectureUnit(target, target),
        uncoveredReferenceIds = (1..referenceCount).map { "$group:$source->$target:$it" },
        governedReferenceIds = emptyList(),
    )

    private fun assertAcyclic(
        edges: List<GovernanceProblemEdge>,
        selected: List<GovernanceProblemEdge>,
    ) {
        assertTrue(isAcyclic(edges, selected))
    }

    private fun isAcyclic(
        edges: List<GovernanceProblemEdge>,
        selected: List<GovernanceProblemEdge>,
    ): Boolean {
        val selectedKeys = selected.mapTo(hashSetOf()) { scopedEdgeKey(it) }
        val remaining = edges.filter { it.uncoveredReferenceIds.isNotEmpty() && scopedEdgeKey(it) !in selectedKeys }
        val adjacency = remaining.groupBy({ it.source.nodeKey }, { it.target.nodeKey })
        val visiting = hashSetOf<String>()
        val visited = hashSetOf<String>()

        fun visitsCycle(node: String): Boolean {
            if (!visiting.add(node)) return true
            if (!visited.add(node)) {
                visiting.remove(node)
                return false
            }
            val cyclic = adjacency[node].orEmpty().any(::visitsCycle)
            visiting.remove(node)
            return cyclic
        }

        return !remaining.flatMap { listOf(it.source.nodeKey, it.target.nodeKey) }.any(::visitsCycle)
    }

    private fun bruteForceCost(edges: List<GovernanceProblemEdge>): Pair<Int, Int> {
        var best = Int.MAX_VALUE to Int.MAX_VALUE
        for (mask in 0 until (1 shl edges.size)) {
            val selected = edges.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            val cost = selected.size to selected.sumOf { it.uncoveredReferenceIds.size }
            if ((cost.first < best.first || cost.first == best.first && cost.second < best.second) &&
                isAcyclic(edges, selected)
            ) {
                best = cost
            }
        }
        return best
    }

    private fun edgeKey(edge: GovernanceProblemEdge) =
        "${edge.source.architectureUnit}->${edge.target.architectureUnit}"

    private fun scopedEdgeKey(edge: GovernanceProblemEdge) =
        "${edge.analysisSourceId}:${edge.cycleGroupId}:${edge.source.nodeKey}->${edge.target.nodeKey}"

    private companion object {
        const val SOURCE = "jvm:whole-project"
    }
}
