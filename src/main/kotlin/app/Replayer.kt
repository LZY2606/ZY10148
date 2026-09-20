package app

/**
 * Deterministic stand-in for an external proof replay.
 * No proof process is launched: each lemma's result is derived purely from the
 * declared plan evidence, dependency proofs in earlier batches, and an explicit
 * failure-injection set (used by the fixed fixtures and acceptance tests).
 */
object Replayer {
    fun checkBatch(plan: PlanRow, batch: Int, newSnapshot: Snapshot, forcedFailures: Set<String>): List<Map<String, Any?>> {
        val members = plan.layers.getOrElse(batch) { emptyList() }
        val syms = newSnapshot.byFqn()
        return members.map { fqn ->
            val item = plan.items.first { it.fqn == fqn }
            val sym = syms.getValue(fqn)
            val upstream = sym.directDeps.filter { dep ->
                plan.items.any { it.fqn == dep && it.batch < batch }
            }
            val evidenceToken = Hashing.sha256Hex(
                buildString {
                    append(plan.planFingerprint).append('|')
                    append(fqn).append('|')
                    append(sym.contentFingerprint).append('|')
                    append(sym.directDeps.joinToString(",")).append('|')
                    append(sym.proofRef ?: "").append('|')
                    append(item.reason.name)
                }
            )
            val verdict = if (fqn in forcedFailures) {
                "FAILED" to "forced replay failure for fixture/acceptance"
            } else if (sym.proofRef == null) {
                "FAILED" to "no proof evidence attached to lemma"
            } else if (sym.directDeps.any { dep ->
                    val d = syms[dep]
                    d != null && d.kind == NodeKind.LEMMA && forcedFailures.contains(dep)
                }) {
                "FAILED" to "upstream forced failure"
            } else {
                "PROVED" to "deterministic evidence check passed"
            }
            linkedMapOf(
                "fqn" to fqn,
                "status" to verdict.first,
                "detail" to verdict.second,
                "reason" to item.reason.name,
                "upstreamInPlan" to upstream,
                "parallelGoals" to sym.parallelGoals,
                "proofRef" to sym.proofRef,
                "contentFingerprint" to sym.contentFingerprint,
                "evidenceToken" to evidenceToken
            )
        }
    }
}
