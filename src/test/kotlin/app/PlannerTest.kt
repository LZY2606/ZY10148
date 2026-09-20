package app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlannerTest {
    private fun snapshot(
        version: String,
        symbols: List<Symbol>,
        library: String = "lib",
    ) = Snapshot(library = library, version = version, symbols = symbols)

    private fun axiom(id: String, fp: String = "ax-$id", deps: List<String> = emptyList(), external: Boolean = false) =
        Symbol(id, NodeKind.AXIOM, "lib", "v", fp, "content-$id", "doc-$id", deps, external)

    private fun def(
        id: String,
        fp: String = "df-$id",
        deps: List<String> = emptyList(),
        recursive: Boolean = false,
        doc: String = "doc-$id",
    ) = Symbol(id, NodeKind.DEFINITION, "lib", "v", fp, "content-$id", doc, deps, false, recursive)

    private fun lemma(id: String, deps: List<String>, fp: String = "lm-$id", doc: String = "doc-$id", content: String = "content-$id") =
        Symbol(id, NodeKind.LEMMA, "lib", "v", fp, content, doc, deps)

    private fun records(old: Snapshot, new: Snapshot): Pair<SnapshotRecord, SnapshotRecord> {
        val svc = TestHarness.newService()
        val r1 = svc.importSnapshot(old).first
        val r2 = svc.importSnapshot(new).first
        return r1 to r2
    }

    @Test
    fun `changed axiom replays dependent lemma even when lemma fingerprint is unchanged`() {
        val v1 = snapshot("v1", listOf(
            axiom("ax", fp = "ax-old"),
            lemma("thm", listOf("ax"), fp = "thm-fp"),
        ))
        val v2 = snapshot("v2", listOf(
            axiom("ax", fp = "ax-new"),
            lemma("thm", listOf("ax"), fp = "thm-fp"),
        ))
        val (r1, r2) = records(v1, v2)
        val diff = diffSnapshots(r1, r2, emptyMap())
        val build = buildPlan(r1, r2, diff)
        assertEquals(setOf("thm"), build.entries.map { it.symbolId }.toSet())
        assertEquals(EntryReason.DEPENDENCY, build.entries.single().reason)
    }

    @Test
    fun `doc only change does not enlarge impact set`() {
        val v1 = snapshot("v1", listOf(axiom("ax", fp = "ax-fp")))
        val v2 = snapshot("v2", listOf(axiom("ax", fp = "ax-fp", deps = emptyList()).copy(doc = "rewritten docs")))
        val (r1, r2) = records(v1, v2)
        val diff = diffSnapshots(r1, r2, emptyMap())
        assertTrue(diff.changed.isEmpty())
        assertEquals(setOf("ax"), diff.docOnly)
        assertTrue(buildPlan(r1, r2, diff).entries.isEmpty())
    }

    @Test
    fun `rename transfers only with explicit mapping`() {
        val v1 = snapshot("v1", listOf(
            axiom("ax", fp = "ax-fp"),
            lemma("old_name", listOf("ax"), fp = "thm-fp"),
        ))
        val v2 = snapshot("v2", listOf(
            axiom("ax", fp = "ax-fp"),
            lemma("new_name", listOf("ax"), fp = "thm-fp"),
        ))
        val (r1, r2) = records(v1, v2)

        val withoutMapping = diffSnapshots(r1, r2, emptyMap())
        assertEquals(setOf("new_name"), withoutMapping.added)
        assertEquals(setOf("old_name"), withoutMapping.deleted)
        assertNull(withoutMapping.matched["new_name"])

        val withMapping = diffSnapshots(r1, r2, mapOf("old_name" to "new_name"))
        assertEquals("old_name", withMapping.matched["new_name"])
        assertTrue(withMapping.added.isEmpty())
        assertTrue(buildPlan(r1, r2, withMapping).entries.isEmpty())
    }

    @Test
    fun `same text without mapping is not treated as rename`() {
        val v1 = snapshot("v1", listOf(
            axiom("ax", fp = "ax-fp"),
            lemma("name_a", listOf("ax"), fp = "same-fp", content = "Lemma shared body."),
        ))
        val v2 = snapshot("v2", listOf(
            axiom("ax", fp = "ax-fp"),
            lemma("name_b", listOf("ax"), fp = "same-fp", content = "Lemma shared body."),
        ))
        val (r1, r2) = records(v1, v2)
        val diff = diffSnapshots(r1, r2, emptyMap())
        assertEquals(setOf("name_b"), diff.added)
        val build = buildPlan(r1, r2, diff)
        assertEquals(EntryReason.MISSING_PROOF, build.entries.single().reason)
    }

    @Test
    fun `new lemma enters with MISSING_PROOF`() {
        val v1 = snapshot("v1", listOf(axiom("ax", fp = "ax-fp")))
        val v2 = snapshot("v2", listOf(
            axiom("ax", fp = "ax-fp"),
            lemma("new_thm", listOf("ax")),
        ))
        val (r1, r2) = records(v1, v2)
        val build = buildPlan(r1, r2, diffSnapshots(r1, r2, emptyMap()))
        assertEquals(EntryReason.MISSING_PROOF, build.entries.single().reason)
    }

    @Test
    fun `content change is CONTENT and unaffected lemma is excluded`() {
        val v1 = snapshot("v1", listOf(
            axiom("ax", fp = "ax-fp"),
            lemma("changed", listOf("ax"), fp = "c-1"),
            lemma("untouched", listOf("ax"), fp = "u-1"),
        ))
        val v2 = snapshot("v2", listOf(
            axiom("ax", fp = "ax-fp"),
            lemma("changed", listOf("ax"), fp = "c-2"),
            lemma("untouched", listOf("ax"), fp = "u-1"),
        ))
        val (r1, r2) = records(v1, v2)
        val build = buildPlan(r1, r2, diffSnapshots(r1, r2, emptyMap()))
        val byId = build.entries.associateBy { it.symbolId }
        assertEquals(setOf("changed"), byId.keys)
        assertEquals(EntryReason.CONTENT, byId.getValue("changed").reason)
    }

    @Test
    fun `plan is topological and stable sorted within layers`() {
        val v1 = snapshot("v1", listOf(
            axiom("ax", fp = "ax-old"),
            lemma("z_low", listOf("ax"), fp = "z1"),
            lemma("a_low", listOf("ax"), fp = "a1"),
            lemma("top", listOf("z_low", "a_low"), fp = "t1"),
        ))
        val v2 = snapshot("v2", listOf(
            axiom("ax", fp = "ax-new"),
            lemma("z_low", listOf("ax"), fp = "z1"),
            lemma("a_low", listOf("ax"), fp = "a1"),
            lemma("top", listOf("z_low", "a_low"), fp = "t1"),
        ))
        val (r1, r2) = records(v1, v2)
        val build = buildPlan(r1, r2, diffSnapshots(r1, r2, emptyMap()))
        val layer0 = build.entries.filter { it.layer == 0 }.map { it.symbolId }
        val layer1 = build.entries.filter { it.layer == 1 }.map { it.symbolId }
        assertEquals(listOf("a_low", "z_low"), layer0)
        assertEquals(listOf("top"), layer1)
    }

    @Test
    fun `transitive impact reaches dependent of dependent`() {
        val v1 = snapshot("v1", listOf(
            axiom("ax", fp = "ax-old"),
            lemma("mid", listOf("ax"), fp = "m1"),
            lemma("far", listOf("mid"), fp = "f1"),
        ))
        val v2 = snapshot("v2", listOf(
            axiom("ax", fp = "ax-new"),
            lemma("mid", listOf("ax"), fp = "m1"),
            lemma("far", listOf("mid"), fp = "f1"),
        ))
        val (r1, r2) = records(v1, v2)
        val build = buildPlan(r1, r2, diffSnapshots(r1, r2, emptyMap()))
        assertEquals(setOf("mid", "far"), build.affected.intersect(setOf("mid", "far")))
    }

    @Test
    fun `illegal lemma cycle rejects snapshot import`() {
        val bad = snapshot("bad", listOf(
            lemma("a", listOf("b")),
            lemma("b", listOf("a")),
        ))
        assertThrows(SnapshotValidationException::class.java) { validateSnapshot(bad) }
    }

    @Test
    fun `unknown dependency is rejected`() {
        val bad = snapshot("bad", listOf(lemma("a", listOf("missing"))))
        val ex = assertThrows(SnapshotValidationException::class.java) { validateSnapshot(bad) }
        assertEquals(listOf("missing"), ex.unknownDependencies)
    }
}
