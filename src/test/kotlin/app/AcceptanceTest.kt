package app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AcceptanceTest {
    private fun fixture(name: String) =
        SnapshotInput.parse(Json.parseObject(AcceptanceTest::class.java.getResourceAsStream("/fixtures/$name")!!.bufferedReader().readText()))

    private fun bootStore(): Pair<Store, PlanRow> {
        val st = Store(":memory:")
        val v1 = st.importSnapshot(fixture("mini-arith-v1.json")).first
        val v2 = st.importSnapshot(fixture("mini-arith-v2.json")).first
        val plan = st.createPlan(v1.id, v2.id, mapOf("lemma_add_comm" to "lemma_sum_comm"))
        return st to plan
    }

    @Test
    fun `import is idempotent by library and content fingerprint`() {
        val st = Store(":memory:")
        val v1 = fixture("mini-arith-v1.json")
        val (a, created1) = st.importSnapshot(v1)
        val (b, created2) = st.importSnapshot(v1)
        assertTrue(created1); assertFalse(created2); assertEquals(a.id, b.id)
        // doc-only change keeps snapshot fingerprint -> same import identity
        val docEdited = v1.copy(symbols = v1.symbols.map {
            if (it.fqn == "def_odd") it.copy(docFingerprint = "totally-new-doc") else it
        })
        assertFalse(st.importSnapshot(docEdited).second)
        st.close()
    }

    @Test
    fun `recursive definition SCC is legal but proof cycle is rejected`() {
        val st = Store(":memory:")
        val (v1, _) = st.importSnapshot(fixture("mini-arith-v1.json"))
        val evenOdd = Graph(v1.symbols).cyclicSccs().single()
        assertTrue(evenOdd.legalRecursion)
        assertEquals(setOf("def_even", "def_odd"), evenOdd.members.toSet())

        val ex = assertThrows(HttpError::class.java) { st.importSnapshot(fixture("illegal-cycle.json")) }
        assertEquals(422, ex.status)
        assertTrue(ex.message!!.contains("illegal proof cycle"))
        assertEquals("SNAPSHOT_REJECTED", st.listEvents().last().eventType)
        st.close()
    }

    @Test
    fun `minimal replay plan distinguishes all three entry reasons and excludes doc-only and unaffected lemmas`() {
        val (st, plan) = bootStore()
        val byFqn = plan.items.associateBy { it.fqn }
        assertEquals(EnterReason.CONTENT_CHANGED, byFqn.getValue("lemma_zero_nat").reason)
        // fingerprint unchanged but the axiom it depends on changed -> must replay
        assertEquals(EnterReason.DEPENDENCY_CHANGED, byFqn.getValue("lemma_even_zero").reason)
        assertTrue("lemma_even_zero" in plan.affectedLemmas)
        assertEquals(EnterReason.MISSING_OLD_PROOF, byFqn.getValue("lemma_new_dawn").reason)
        // renamed lemma tracked only because of the explicit mapping
        assertEquals(EnterReason.DEPENDENCY_CHANGED, byFqn.getValue("lemma_sum_comm").reason)
        assertNull(byFqn["lemma_doc_only"])
        assertNull(byFqn["lemma_stable"])
        assertTrue("lemma_doc_only" in plan.docOnly)
        st.close()
    }

    @Test
    fun `rename without explicit mapping is not propagated`() {
        val st = Store(":memory:")
        val v1 = st.importSnapshot(fixture("mini-arith-v1.json")).first
        val v2 = st.importSnapshot(fixture("mini-arith-v2.json")).first
        val plan = st.createPlan(v1.id, v2.id, emptyMap())
        val item = plan.items.first { it.fqn == "lemma_sum_comm" }
        assertEquals(EnterReason.MISSING_OLD_PROOF, item.reason)
        assertTrue(plan.items.none { it.fqn == "lemma_add_comm" })
        st.close()
    }

    @Test
    fun `plan is topologically ordered with stable same-layer order`() {
        val (st, plan) = bootStore()
        val v2 = st.getSnapshot(plan.newSnapshotId)
        val ord = v2.byFqn().mapValues { it.value.ord }
        plan.items.windowed(2).forEach { (a, b) ->
            assertTrue(a.layer <= b.layer)
            if (a.layer == b.layer) assertTrue(a.fqn < b.fqn || ord.getValue(a.fqn) < ord.getValue(b.fqn))
        }
        plan.items.forEach { item ->
            v2.byFqn().getValue(item.fqn).directDeps.forEach { dep ->
                val depItem = plan.items.firstOrNull { it.fqn == dep }
                if (depItem != null) assertTrue(depItem.layer < item.layer)
            }
        }
        plan.layers.forEachIndexed { i, members ->
            members.forEach { fqn -> assertEquals(i, plan.items.first { it.fqn == fqn }.batch) }
        }
        st.close()
    }

    @Test
    fun `replay binds to declared plan version`() {
        val (st, plan) = bootStore()
        val err = assertThrows(HttpError::class.java) {
            st.submitReplay(plan.planId, plan.planVersion + 9, 0, emptySet(), "test")
        }
        assertEquals(409, err.status)
        st.close()
    }

    @Test
    fun `failed batch stays unpublished and published batches persist exact evidence`() {
        val (st, plan) = bootStore()
        val victim = plan.layers[0].first()
        val failed = st.submitReplay(plan.planId, plan.planVersion, 0, setOf(victim), "test")
        assertEquals("FAILED", failed["status"])
        assertEquals(false, failed["published"])
        assertTrue(st.listBatches(plan.planId).none { it["batch"] == 0 })
        assertTrue(st.listEvents().any { it.eventType == "REPLAY_FAILED" })

        val ok = st.submitReplay(plan.planId, plan.planVersion, 0, emptySet(), "test")
        assertEquals("PUBLISHED", ok["status"])
        val batches = st.listBatches(plan.planId)
        assertEquals(1, batches.size.toInt())
        @Suppress("UNCHECKED_CAST")
        val evidence = batches[0]["evidence"] as Map<String, Any?>
        assertEquals(plan.planFingerprint, evidence["planFingerprint"])
        assertEquals(plan.planVersion, (evidence["planVersion"] as Number).toInt())

        val again = st.submitReplay(plan.planId, plan.planVersion, 0, setOf(victim), "test")
        assertEquals(true, again["idempotent"])
        st.close()
    }

    @Test
    fun `batches must be submitted in topological order`() {
        val (st, plan) = bootStore()
        if (plan.layers.size > 1) {
            val err = assertThrows(HttpError::class.java) {
                st.submitReplay(plan.planId, plan.planVersion, 1, emptySet(), "test")
            }
            assertEquals(409, err.status)
        }
        st.close()
    }

    @Test
    fun `frozen plan rejects further replay`() {
        val (st, plan) = bootStore()
        st.freezePlan(plan.planId, plan.planVersion, "locked")
        val err = assertThrows(HttpError::class.java) {
            st.submitReplay(plan.planId, plan.planVersion, 0, emptySet(), "test")
        }
        assertEquals(409, err.status)
        assertTrue(st.listEvents().any { it.eventType == "PLAN_FROZEN" })
        st.close()
    }

    @Test
    fun `external axiom review is an append-only decision`() {
        val (st, plan) = bootStore()
        val r1 = st.reviewAxiom(plan.planId, "ax_omit_ext", "CLAIMED", "alice", "looking")
        val r2 = st.reviewAxiom(plan.planId, "ax_omit_ext", "APPROVED", "bob", "verified source")
        val reviews = st.listReviews(plan.planId)
        assertEquals(2, reviews.size)
        assertEquals("CLAIMED", reviews[0].status)
        assertEquals("APPROVED", reviews[1].status)
        assertEquals(r1.eventSeq + 1, r2.eventSeq)
        val err = assertThrows(HttpError::class.java) {
            st.reviewAxiom(plan.planId, "ax_zero", "APPROVED", "alice", "internal")
        }
        assertEquals(400, err.status)
        st.close()
    }

    @Test
    fun `slice can only be narrowed on a failed lemma`() {
        val (st, plan) = bootStore()
        val victim = plan.layers[0].first()
        st.submitReplay(plan.planId, plan.planVersion, 0, setOf(victim), "test")
        val unrelatedNonFailed = plan.layers.flatten().first { it != victim }
        assertEquals(409, assertThrows(HttpError::class.java) { st.narrowSlice(plan.planId, unrelatedNonFailed, "x") }.status)
        val slice = st.narrowSlice(plan.planId, victim, "narrow it")
        assertTrue(victim in slice.sliceFqns)
        val v2 = st.getSnapshot(plan.newSnapshotId)
        val cone = LinkedHashSet<String>()
        val q = ArrayDeque<String>(); q.addLast(victim); cone.add(victim)
        while (q.isNotEmpty()) v2.byFqn()[q.removeFirst()]?.directDeps?.forEach { if (cone.add(it)) q.addLast(it) }
        slice.sliceFqns.forEach { assertTrue(it in cone) }
        assertTrue(st.listEvents().any { it.eventType == "PROOF_SLICE_NARROWED" })
        st.close()
    }

    @Test
    fun `event log is an append-only hash chain`() {
        val (st, _) = bootStore()
        assertTrue(st.verifyChain())
        st.close()
    }
}
