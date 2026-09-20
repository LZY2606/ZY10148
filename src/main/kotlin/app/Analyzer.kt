package app

/** Why a lemma enters the replay set. */
enum class EnterReason { CONTENT_CHANGED, DEPENDENCY_CHANGED, MISSING_OLD_PROOF }

data class PlanItem(
    val fqn: String,
    val layer: Int,
    val batch: Int,
    val reason: EnterReason,
    val triggeredBy: List<String>,
    val oldContentFingerprint: String?,
    val newContentFingerprint: String
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "fqn" to fqn,
        "layer" to layer,
        "batch" to batch,
        "reason" to reason.name,
        "triggeredBy" to triggeredBy,
        "oldContentFingerprint" to oldContentFingerprint,
        "newContentFingerprint" to newContentFingerprint
    )
}

data class ChangedSymbol(
    val fqn: String,
    val kind: NodeKind,
    val change: String,
    val detail: String,
    val external: Boolean
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "fqn" to fqn, "kind" to kind.name, "change" to change, "detail" to detail, "external" to external
    )
}

data class ReplayPlan(
    val planId: String,
    val planVersion: Int,
    val oldSnapshotId: Long,
    val newSnapshotId: Long,
    val frozen: Boolean,
    val items: List<PlanItem>,
    val changedSeeds: List<ChangedSymbol>,
    val docOnly: List<String>,
    val affectedLemmas: List<String>,
    val boundary: List<String>,
    val mapping: Map<String, String>,
    val layers: List<List<String>>,
    val planFingerprint: String
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "planId" to planId,
        "planVersion" to planVersion,
        "oldSnapshotId" to oldSnapshotId,
        "newSnapshotId" to newSnapshotId,
        "frozen" to frozen,
        "planFingerprint" to planFingerprint,
        "changedSeeds" to changedSeeds.map { it.toJson() },
        "docOnly" to docOnly,
        "affectedLemmas" to affectedLemmas,
        "boundary" to boundary,
        "mapping" to mapping,
        "layers" to layers,
        "items" to items.map { it.toJson() }
    )
}

object Analyzer {
    /**
     * Build the minimal replay plan between two snapshots.
     *
     * Identity: a symbol is the "same" across snapshots only when its fqn is equal
     * or an explicit rename mapping pairs old -> new. Text similarity is never used.
     */
    fun plan(old: Snapshot, new: Snapshot, renameMapping: Map<String, String>): PlanBuild {
        renameMapping.forEach { (o, n) ->
            if (o !in old.byFqn()) throw BadRequest("mapping references unknown old symbol '$o'")
            if (n !in new.byFqn()) throw BadRequest("mapping references unknown new symbol '$n'")
        }
        val newByOld: Map<String, String> = renameMapping
        val oldByNew: Map<String, String> = renameMapping.entries.associate { it.value to it.key }

        val seeds = mutableListOf<ChangedSymbol>()
        val docOnly = mutableListOf<String>()

        for (ns in new.symbols.sortedWith(compareBy({ it.ord }, { it.fqn }))) {
            val oldFqn = if (ns.fqn in old.byFqn()) ns.fqn else oldByNew[ns.fqn]
            val os = oldFqn?.let { old.byFqn()[it] }
            if (os == null) {
                seeds += ChangedSymbol(ns.fqn, ns.kind, "ADDED", "new symbol with no matching fqn or rename mapping", ns.external)
                continue
            }
            val contentChanged = os.contentFingerprint != ns.contentFingerprint ||
                os.kind != ns.kind ||
                os.external != ns.external ||
                os.proofRef != ns.proofRef ||
                os.checkStatus != ns.checkStatus ||
                os.directDepsMapped(newByOld) != ns.directDeps ||
                os.parallelGoalsMapped(newByOld) != ns.parallelGoals
            val docChanged = os.docFingerprint != ns.docFingerprint
            if (contentChanged) {
                val parts = mutableListOf<String>()
                if (os.contentFingerprint != ns.contentFingerprint) parts += "contentFingerprint ${os.contentFingerprint.take(12)} -> ${ns.contentFingerprint.take(12)}"
                if (os.kind != ns.kind) parts += "kind ${os.kind} -> ${ns.kind}"
                if (os.external != ns.external) parts += "external ${os.external} -> ${ns.external}"
                if (os.proofRef != ns.proofRef) parts += "proofRef ${os.proofRef ?: "<none>"} -> ${ns.proofRef ?: "<none>"}"
                if (os.checkStatus != ns.checkStatus) parts += "checkStatus ${os.checkStatus} -> ${ns.checkStatus}"
                if (os.directDepsMapped(newByOld) != ns.directDeps) parts += "directDeps changed"
                if (os.parallelGoalsMapped(newByOld) != ns.parallelGoals) parts += "parallelGoals changed"
                seeds += ChangedSymbol(ns.fqn, ns.kind, "MODIFIED", parts.joinToString("; "), ns.external)
            } else if (docChanged) {
                docOnly += ns.fqn
            }
        }
        for (os in old.symbols.sortedWith(compareBy({ it.ord }, { it.fqn }))) {
            val newFqn = if (os.fqn in new.byFqn()) os.fqn else newByOld[os.fqn]
            if (newFqn == null) {
                seeds += ChangedSymbol(os.fqn, os.kind, "REMOVED", "symbol absent from new snapshot and not covered by rename mapping", os.external)
            }
        }

        val newGraph = Graph(new.symbols)
        val seedFqns: Set<String> = seeds.map { it.fqn }.toSet()
        // Removed symbols are not nodes of the new graph; propagation starts at new-graph symbols
        // whose dependency set changed or that were a dependency of a removed symbol.
        val removed = seeds.filter { it.change == "REMOVED" }.map { it.fqn }.toSet()

        // Reverse reachability on the new graph from changed seeds that exist there.
        val reached = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        seedFqns.filter { it in newGraph.byFqn }.forEach { if (reached.add(it)) queue.addLast(it) }
        // Any symbol that used to depend on a removed symbol is itself a seed (its edge set changed),
        // which is already captured as MODIFIED directDeps for survivors; be defensive anyway.
        new.symbols.filter { removed.isNotEmpty() && oldByNew[it.fqn]?.let { of -> old.byFqn().getValue(of).directDeps.any { d -> d in removed } } == true }
            .forEach { if (reached.add(it.fqn)) queue.addLast(it.fqn) }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (parent in newGraph.reverse[cur].orEmpty()) {
                if (reached.add(parent)) queue.addLast(parent)
            }
        }

        data class Inclusion(val reason: EnterReason, val triggers: List<String>)

        val inclusion = LinkedHashMap<String, Inclusion>()
        for (fqn in reached.sortedWith(compareBy({ newGraph.byFqn.getValue(it).ord }, { it }))) {
            val sym = newGraph.byFqn.getValue(fqn)
            if (sym.kind != NodeKind.LEMMA) continue
            val oldFqn = if (fqn in old.byFqn()) fqn else oldByNew[fqn]
            val osym = oldFqn?.let { old.byFqn()[it] }
            val isSeed = fqn in seedFqns
            val oldProofMissing = osym == null || osym.proofRef == null
            val reason = when {
                oldProofMissing -> EnterReason.MISSING_OLD_PROOF
                isSeed && sym.contentFingerprint != osym.contentFingerprint -> EnterReason.CONTENT_CHANGED
                else -> EnterReason.DEPENDENCY_CHANGED
            }
            val triggers = when (reason) {
                EnterReason.MISSING_OLD_PROOF -> listOf("${oldFqn}:no-proof-evidence-in-old-snapshot")
                EnterReason.CONTENT_CHANGED -> listOf("$fqn:content")
                EnterReason.DEPENDENCY_CHANGED -> directTriggers(fqn, newGraph, seedFqns, reached, old, new, oldByNew)
            }
            inclusion[fqn] = Inclusion(reason, triggers)
        }

        val itemFqns = inclusion.keys
        val layers = topoLayers(newGraph, itemFqns)
        val layerOf = HashMap<String, Int>()
        layers.forEachIndexed { idx, members -> members.forEach { layerOf[it] = idx } }

        val items = itemFqns.map { fqn ->
            val inc = inclusion.getValue(fqn)
            val oldFqn = if (fqn in old.byFqn()) fqn else oldByNew[fqn]
            PlanItem(
                fqn = fqn,
                layer = layerOf.getValue(fqn),
                batch = layerOf.getValue(fqn),
                reason = inc.reason,
                triggeredBy = inc.triggers,
                oldContentFingerprint = oldFqn?.let { old.byFqn().getValue(it).contentFingerprint },
                newContentFingerprint = newGraph.byFqn.getValue(fqn).contentFingerprint
            )
        }.sortedWith(compareBy({ it.layer }, { newGraph.byFqn.getValue(it.fqn).ord }, { it.fqn }))

        val affectedLemmas = itemFqns.sortedWith(compareBy({ newGraph.byFqn.getValue(it).ord }, { it }))
        // Boundary: affected lemmas that have a lemma/theorem dependent which is NOT affected,
        // plus affected lemmas on the outer rim when no unaffected dependent exists.
        val boundary = affectedLemmas.filter { fqn ->
            val parents = newGraph.reverse[fqn].orEmpty().filter { newGraph.byFqn.getValue(it).kind == NodeKind.LEMMA }
            parents.isNotEmpty() && parents.any { it !in itemFqns }
        }.let { rim -> if (rim.isNotEmpty()) rim else affectedLemmas.filter { fqn ->
            newGraph.reverse[fqn].orEmpty().none { newGraph.byFqn.getValue(it).kind == NodeKind.LEMMA }
        } }

        val seedOrdered = seeds.sortedBy { it.fqn }
        val fingerprint = Hashing.sha256Hex(buildString {
            append(old.snapshotFingerprint).append('\n')
            append(new.snapshotFingerprint).append('\n')
            append(renameMapping.toSortedMap().entries.joinToString(",") { "${it.key}->${it.value}" }).append('\n')
            append(items.joinToString(";") { "${it.fqn}@${it.layer}:${it.reason}" })
        })
        val planId = Hashing.sha256Hex("${old.id}|${new.id}|${fingerprint}").take(16)

        return PlanBuild(
            planId = planId,
            items = items,
            changedSeeds = seedOrdered,
            docOnly = docOnly.sorted(),
            affectedLemmas = affectedLemmas,
            boundary = boundary.sortedWith(compareBy({ newGraph.byFqn.getValue(it).ord }, { it })),
            layers = layers,
            planFingerprint = fingerprint
        )
    }

    private fun Sym.directDepsMapped(newByOld: Map<String, String>): List<String> =
        directDeps.map { newByOld[it] ?: it }

    private fun Sym.parallelGoalsMapped(newByOld: Map<String, String>): List<String> =
        parallelGoals.map { newByOld[it] ?: it }

    /**
     * Triggering evidence: changed direct dependencies that can reach this lemma.
     * Prefers direct changed deps; falls back to nearest changed ancestors via reverse edges.
     */
    private fun directTriggers(
        fqn: String,
        graph: Graph,
        seeds: Set<String>,
        reached: Set<String>,
        old: Snapshot,
        new: Snapshot,
        oldByNew: Map<String, String>
    ): List<String> {
        val sym = graph.byFqn.getValue(fqn)
        val direct = sym.directDeps.filter { it in seeds }
        if (direct.isNotEmpty()) return direct.map { "$it:changed-input" }.distinct().sorted()
        // Dependency edge changed on this lemma itself.
        val oldFqn = oldByNew[fqn] ?: fqn
        val oldDeps = old.byFqn()[oldFqn]?.directDeps.orEmpty().toSet()
        if (oldDeps != sym.directDeps.toSet()) return listOf("$fqn:directDeps")
        // Nearest changed ancestor via BFS over reversed edges.
        val found = mutableListOf<String>()
        val visited = HashSet<String>()
        val q = ArrayDeque<String>()
        q.addLast(fqn)
        while (q.isNotEmpty() && found.size < 4) {
            val cur = q.removeFirst()
            for (dep in graph.byFqn.getValue(cur).directDeps) {
                if (dep in seeds) found += "$dep:changed-input"
                if (visited.add(dep) && graph.byFqn.getValue(dep).kind == NodeKind.LEMMA) q.addLast(dep)
            }
        }
        return found.distinct().sorted().ifEmpty { listOf("changed-upstream") }
    }

    /**
     * Longest-path layering restricted to the replay subset on the new graph.
     * Layer 0 = item with no item dependency. Within a layer, (ord, fqn) gives a stable order.
     */
    private fun topoLayers(graph: Graph, subset: Set<String>): List<List<String>> {
        if (subset.isEmpty()) return emptyList()
        val depsIn: Map<String, Set<String>> = subset.associateWith { fqn ->
            // all subset nodes reachable through one direct edge (deps transitively don't matter for level max)
            graph.byFqn.getValue(fqn).directDeps.filter { it in subset }.toSet()
        }
        val layer = HashMap<String, Int>()
        // Iterate until stable; graph restricted to proofs is acyclic by import validation.
        val remaining = subset.toMutableSet()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { depsIn.getValue(it).all { d -> d in layer } }
            if (ready.isEmpty()) throw IllegalStateException("Cycle inside replay subset: $remaining")
            for (fqn in ready) {
                val depLayers = depsIn.getValue(fqn).map { layer.getValue(it) }
                layer[fqn] = (depLayers.maxOrNull() ?: -1) + 1
            }
            remaining.removeAll(ready.toSet())
        }
        return layer.entries.groupBy({ it.value }, { it.key })
            .toSortedMap()
            .values
            .map { members -> members.sortedWith(compareBy({ graph.byFqn.getValue(it).ord }, { it })) }
    }
}

data class PlanBuild(
    val planId: String,
    val items: List<PlanItem>,
    val changedSeeds: List<ChangedSymbol>,
    val docOnly: List<String>,
    val affectedLemmas: List<String>,
    val boundary: List<String>,
    val layers: List<List<String>>,
    val planFingerprint: String
)
