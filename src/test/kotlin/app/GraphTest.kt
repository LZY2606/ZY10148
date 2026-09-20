package app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GraphTest {
    private fun sym(id: String, kind: NodeKind, deps: List<String>, recursive: Boolean = false) =
        Symbol(
            id = id,
            kind = kind,
            library = "lib",
            version = "v",
            fingerprint = "fp-$id",
            content = "content-$id",
            dependencies = deps,
            recursive = recursive,
        )

    @Test
    fun `mutually recursive definitions are legal cycles`() {
        val symbols = listOf(
            sym("even", NodeKind.DEFINITION, listOf("odd"), recursive = true),
            sym("odd", NodeKind.DEFINITION, listOf("even"), recursive = true),
            sym("nat", NodeKind.DEFINITION, emptyList()),
        )
        assertEquals(emptyList<CycleViolation>(), analyzeCycles(symbols))
    }

    @Test
    fun `mutual cycle among lemmas is rejected`() {
        val symbols = listOf(
            sym("a", NodeKind.LEMMA, listOf("b")),
            sym("b", NodeKind.LEMMA, listOf("a")),
        )
        val violations = analyzeCycles(symbols)
        assertEquals(1, violations.size)
        assertEquals(listOf("a", "b"), violations.single().scc)
    }

    @Test
    fun `mixed definition-lemma cycle is rejected even when definitions are recursive`() {
        val symbols = listOf(
            sym("d", NodeKind.DEFINITION, listOf("t"), recursive = true),
            sym("t", NodeKind.LEMMA, listOf("d")),
        )
        val violations = analyzeCycles(symbols)
        assertEquals(1, violations.size)
        assertTrue(violations.single().reason.contains("t"))
    }

    @Test
    fun `axiom in a cycle is rejected`() {
        val symbols = listOf(
            sym("ax", NodeKind.AXIOM, listOf("d")),
            sym("d", NodeKind.DEFINITION, listOf("ax"), recursive = true),
        )
        assertEquals(1, analyzeCycles(symbols).size)
    }

    @Test
    fun `self loop of recursive definition is legal but non-recursive self dependency is not`() {
        val legal = listOf(sym("d", NodeKind.DEFINITION, listOf("d"), recursive = true))
        assertEquals(0, analyzeCycles(legal).size)
        val illegal = listOf(sym("d", NodeKind.DEFINITION, listOf("d"), recursive = false))
        assertEquals(1, analyzeCycles(illegal).size)
    }

    @Test
    fun `topological layers are ordered and same layer sorted by id`() {
        val deps = mapOf(
            "base" to emptyList(),
            "mid1" to listOf("base"),
            "mid2" to listOf("base"),
            "top" to listOf("mid1", "mid2"),
        )
        val layers = topologicalLayers(deps.keys, deps)
        assertEquals(listOf(listOf("base"), listOf("mid1", "mid2"), listOf("top")), layers)
    }

    @Test
    fun `acyclic graph reports no cyclic scc`() {
        val symbols = listOf(
            sym("a", NodeKind.AXIOM, emptyList()),
            sym("b", NodeKind.LEMMA, listOf("a")),
        )
        assertTrue(analyzeCycles(symbols).isEmpty())
        assertFalse(tarjan(symbols).second.any { it.size > 1 })
    }
}
