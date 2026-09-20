package app

/** Node kinds emitted by the proof-event stream. */
enum class NodeKind { AXIOM, DEFINITION, LEMMA }

/** A single symbol in one normalized library snapshot. */
data class Sym(
    val library: String,
    val libraryVersion: String,
    val fqn: String,
    val kind: NodeKind,
    val ord: Int,
    val contentFingerprint: String,
    val docFingerprint: String,
    val directDeps: List<String>,
    val parallelGoals: List<String>,
    val checkStatus: String,
    val proofRef: String?,
    val external: Boolean
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "library" to library,
        "libraryVersion" to libraryVersion,
        "fqn" to fqn,
        "kind" to kind.name,
        "ord" to ord,
        "contentFingerprint" to contentFingerprint,
        "docFingerprint" to docFingerprint,
        "directDeps" to directDeps,
        "parallelGoals" to parallelGoals,
        "checkStatus" to checkStatus,
        "proofRef" to proofRef,
        "external" to external
    )

    companion object {
        fun parse(library: String, libraryVersion: String, ord: Int, node: Map<String, Any?>): Sym {
            val kind = when (val k = J.str(node, "kind")) {
                "AXIOM" -> NodeKind.AXIOM
                "DEFINITION" -> NodeKind.DEFINITION
                "LEMMA" -> NodeKind.LEMMA
                else -> throw BadRequest("Unknown node kind '$k'")
            }
            val fqn = J.str(node, "fqn")
            val deps = J.strList(node, "directDeps")
            if (deps.size != deps.distinct().size) throw BadRequest("Duplicate directDeps on '$fqn'")
            val goals = J.strList(node, "parallelGoals")
            if (goals.size != goals.distinct().size) throw BadRequest("Duplicate parallelGoals on '$fqn'")
            return Sym(
                library = library,
                libraryVersion = libraryVersion,
                fqn = fqn,
                kind = kind,
                ord = J.intOpt(node, "ord") ?: ord,
                contentFingerprint = J.str(node, "contentFingerprint"),
                docFingerprint = J.strOpt(node, "docFingerprint") ?: "",
                directDeps = deps,
                parallelGoals = goals,
                checkStatus = J.strOpt(node, "checkStatus") ?: "UNKNOWN",
                proofRef = J.strOpt(node, "proofRef"),
                external = J.bool(node, "external")
            )
        }
    }
}

/** A normalized snapshot as imported (or as stored after normalization). */
data class SnapshotInput(
    val library: String,
    val libraryVersion: String,
    val symbols: List<Sym>
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "library" to library,
        "libraryVersion" to libraryVersion,
        "snapshotFingerprint" to snapshotFingerprint(),
        "symbols" to symbols.map { it.toJson() }
    )

    /**
     * Canonical fingerprint of the proof-relevant snapshot content.
     * Documentation fingerprints and presentation order are intentionally excluded:
     * a doc-only edit yields the same snapshot fingerprint.
     */
    fun snapshotFingerprint(): String = Hashing.sha256Hex(
        buildString {
            append(library).append('@').append(libraryVersion).append('\n')
            symbols.sortedBy { it.fqn }.forEach { s ->
                append(s.fqn).append('|')
                append(s.kind).append('|')
                append(s.contentFingerprint).append('|')
                append(s.external).append('|')
                append(s.proofRef ?: "").append('|')
                append(s.checkStatus).append('|')
                append(s.directDeps.sorted().joinToString(",")).append('|')
                append(s.parallelGoals.sorted().joinToString(",")).append('\n')
            }
        }
    )

    companion object {
        fun parse(body: Map<String, Any?>): SnapshotInput {
            val library = J.str(body, "library")
            val version = J.str(body, "libraryVersion")
            val nodes = J.objList(body, "symbols")
            val syms = nodes.mapIndexed { i, n -> Sym.parse(library, version, i, n) }
            val fqns = syms.map { it.fqn }
            if (fqns.size != fqns.distinct().size) throw BadRequest("Duplicate symbol fqn in snapshot")
            syms.forEach { s ->
                s.directDeps.forEach { d ->
                    if (d !in fqns) throw BadRequest("Symbol '${s.fqn}' depends on unknown '$d'")
                }
                s.parallelGoals.forEach { g ->
                    if (g !in fqns) throw BadRequest("Symbol '${s.fqn}' lists unknown parallel goal '$g'")
                    if (g == s.fqn) throw BadRequest("Symbol '${s.fqn}' lists itself as parallel goal")
                }
            }
            return SnapshotInput(library, version, syms)
        }
    }
}

/** Stored snapshot row. */
data class Snapshot(
    val id: Long,
    val library: String,
    val libraryVersion: String,
    val snapshotFingerprint: String,
    val importedAt: Long,
    val symbols: List<Sym>
) {
    fun byFqn(): Map<String, Sym> = symbols.associateBy { it.fqn }
}

data class Scc(
    val id: Int,
    val members: List<String>,
    val legalRecursion: Boolean,
    val reason: String
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "members" to members,
        "legalRecursion" to legalRecursion,
        "reason" to reason
    )
}

/** Dependency graph over one snapshot, edge symbol -> direct dependency. */
class Graph(val syms: List<Sym>) {
    val byFqn: Map<String, Sym> = syms.associateBy { it.fqn }
    val outgoing: Map<String, List<String>> = syms.associate { it.fqn to it.directDeps }

    /** Reverse adjacency: symbol -> symbols that depend on it. */
    val reverse: Map<String, List<String>> = buildMap {
        syms.forEach { put(it.fqn, mutableListOf()) }
        syms.forEach { s -> s.directDeps.forEach { d -> (getValue(d) as MutableList).add(s.fqn) } }
    }

    /**
     * Tarjan SCC. Returns components in Tarjan (reverse-topological) discovery order;
     * members are kept in deterministic (snapshot ord, fqn) order.
     */
    fun sccs(): List<Scc> {
        val indexMap = HashMap<String, Int>()
        val low = HashMap<String, Int>()
        val onStack = HashSet<String>()
        val stack = ArrayDeque<String>()
        var counter = 0
        val raw = mutableListOf<List<String>>()

        fun strongConnect(v: String) {
            val work = ArrayDeque<Pair<String, Int>>()
            indexMap[v] = counter; low[v] = counter; counter++
            stack.addLast(v); onStack.add(v)
            work.addLast(v to 0)
            while (work.isNotEmpty()) {
                val (node, edgeIdx) = work.last()
                val edges = outgoing[node].orEmpty()
                if (edgeIdx < edges.size) {
                    work[work.size - 1] = node to (edgeIdx + 1)
                    val w = edges[edgeIdx]
                    if (w !in indexMap) {
                        indexMap[w] = counter; low[w] = counter; counter++
                        stack.addLast(w); onStack.add(w)
                        work.addLast(w to 0)
                    } else if (w in onStack) {
                        low[node] = minOf(low.getValue(node), indexMap.getValue(w))
                    }
                } else {
                    if (low.getValue(node) == indexMap.getValue(node)) {
                        val comp = mutableListOf<String>()
                        while (true) {
                            val w = stack.removeLast(); onStack.remove(w); comp.add(w)
                            if (w == node) break
                        }
                        raw.add(comp)
                    }
                    work.removeLast()
                    if (work.isNotEmpty()) {
                        val parent = work.last().first
                        low[parent] = minOf(low.getValue(parent), low.getValue(node))
                    }
                }
            }
        }
        syms.sortedWith(compareBy({ it.ord }, { it.fqn })).forEach { if (it.fqn !in indexMap) strongConnect(it.fqn) }

        return raw.mapIndexed { idx, members ->
            val ordered = members.map { byFqn.getValue(it) }.sortedWith(compareBy({ it.ord }, { it.fqn })).map { it.fqn }
            val selfLoop = ordered.size == 1 && outgoing[ordered[0]].orEmpty().contains(ordered[0])
            val cyclic = ordered.size > 1 || selfLoop
            val allDefs = ordered.all { byFqn.getValue(it).kind == NodeKind.DEFINITION }
            val legal = !cyclic || allDefs
            val reason = when {
                !cyclic -> "single node, no cycle"
                allDefs -> "recursive definition cycle (DEFINITION only) is legal"
                else -> "cycle reaches ${ordered.count { byFqn.getValue(it).kind != NodeKind.DEFINITION }} non-DEFINITION node(s); proofs must be acyclic"
            }
            Scc(idx, ordered, legal, reason)
        }
    }

    /** All SCCs that contain a cycle. */
    fun cyclicSccs(): List<Scc> = sccs().filter { it.members.size > 1 || outgoing[it.members[0]].orEmpty().contains(it.members[0]) }

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "nodes" to syms.sortedWith(compareBy({ it.ord }, { it.fqn })).map {
            linkedMapOf(
                "fqn" to it.fqn, "kind" to it.kind.name, "ord" to it.ord,
                "contentFingerprint" to it.contentFingerprint,
                "docFingerprint" to it.docFingerprint,
                "checkStatus" to it.checkStatus,
                "external" to it.external,
                "proofRef" to it.proofRef,
                "parallelGoals" to it.parallelGoals
            )
        },
        "edges" to syms.flatMap { s -> s.directDeps.map { d -> linkedMapOf("from" to s.fqn, "to" to d) } }
    )
}
