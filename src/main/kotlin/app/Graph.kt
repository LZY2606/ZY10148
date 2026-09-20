package app

import java.security.MessageDigest

/** 对符号内容计算指纹（fixture 未显式提供 fingerprint 时使用）。 */
fun symbolFingerprint(symbol: Symbol): String =
    symbol.fingerprint
        ?: sha256Hex(
            buildString {
                append(symbol.kind).append('|')
                append(symbol.content).append('|')
                append(symbol.dependencies.sorted().joinToString(","))
            }
        )

/** 快照指纹：按库版本与所有符号（含其规范化指纹）的规范序列计算。 */
fun snapshotFingerprint(snapshot: Snapshot): String {
    val canonical = snapshot.symbols
        .sortedBy { it.id }
        .joinToString(separator = "\n") { s ->
            listOf(
                s.id,
                s.kind.name,
                symbolFingerprint(s),
                s.external.toString(),
                s.recursive.toString(),
                s.dependencies.sorted().joinToString(","),
                s.version,
            ).joinToString("\u0001")
        }
    return sha256Hex("${snapshot.library}\u0001${snapshot.version}\n$canonical")
}

fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return buildString { digest.forEach { b -> append("%02x".format(b)) } }
}

data class AnalysisResult(
    val ids: List<String>,
    val dependencies: Map<String, List<String>>,
    val symbols: Map<String, Symbol>,
    /** SCC index per symbol。 */
    val sccOf: Map<String, Int>,
    val sccs: List<List<String>>,
)

/** 建立依赖索引：dependencies[id] 为该符号直接依赖的符号（边 id -> dep）。 */
fun indexSymbols(symbols: List<Symbol>): Map<String, List<String>> =
    symbols.associate { it.id to it.dependencies.distinct().sorted() }

/** Tarjan 强连通分量；自环或大小>1 的 SCC 才是环。返回分量按首个节点 id 稳定排序。 */
fun tarjan(symbols: List<Symbol>): Pair<Map<String, Int>, List<List<String>>> {
    val adj = indexSymbols(symbols)
    val ids = symbols.map { it.id }.sorted()
    var nextIndex = 0
    val indices = HashMap<String, Int>()
    val low = HashMap<String, Int>()
    val onStack = HashSet<String>()
    val stack = ArrayDeque<String>()
    val components = ArrayList<List<String>>()

    fun strongConnect(v: String) {
        indices[v] = nextIndex
        low[v] = nextIndex
        nextIndex++
        stack.addLast(v)
        onStack.add(v)
        for (w in adj[v].orEmpty()) {
            if (w !in indices) {
                strongConnect(w)
                low[v] = minOf(low[v]!!, low[w]!!)
            } else if (w in onStack) {
                low[v] = minOf(low[v]!!, indices[w]!!)
            }
        }
        if (low[v] == indices[v]) {
            val component = ArrayList<String>()
            while (true) {
                val w = stack.removeLast()
                onStack.remove(w)
                component.add(w)
                if (w == v) break
            }
            components.add(component.sorted())
        }
    }
    ids.forEach { if (it !in indices) strongConnect(it) }
    components.sortBy { it.first() }
    val sccOf = HashMap<String, Int>()
    components.forEachIndexed { index, component ->
        component.forEach { sccOf[it] = index }
    }
    return sccOf to components
}

/**
 * 环合法性：
 *  - 仅由 recursive=true 的 DEFINITION 构成的 SCC 是合法递归；
 *  - 任何包含 AXIOM/LEMMA 或未声明 recursive 的 DEFINITION 的环都必须拒绝
 *    （定理证明之间的未声明环）。
 */
fun analyzeCycles(symbols: List<Symbol>): List<CycleViolation> {
    val byId = symbols.associateBy { it.id }
    val (_, components) = tarjan(symbols)
    val violations = ArrayList<CycleViolation>()
    for (component in components) {
        val cyclic = component.size > 1 ||
            component.singleOrNull()?.let { single ->
                byId[single]?.dependencies?.contains(single) == true
            } == true
        if (!cyclic) continue
        val offenders = component.filter {
            val s = byId.getValue(it)
            s.kind != NodeKind.DEFINITION || !s.recursive
        }
        if (offenders.isNotEmpty()) {
            val kinds = offenders.joinToString(",") {
                val s = byId.getValue(it)
                "${it}(${if (s.kind == NodeKind.DEFINITION) "non-recursive definition" else s.kind.name.lowercase()})"
            }
            violations.add(
                CycleViolation(
                    component,
                    "undeclared cycle in theorem/proof SCC: $kinds",
                )
            )
        }
    }
    return violations
}

fun analyze(symbols: List<Symbol>): AnalysisResult {
    val (sccOf, components) = tarjan(symbols)
    return AnalysisResult(
        ids = symbols.map { it.id }.sorted(),
        dependencies = indexSymbols(symbols),
        symbols = symbols.associateBy { it.id },
        sccOf = sccOf,
        sccs = components,
    )
}

/**
 * 计算拓扑层（Kahn 算法），边方向为符号 -> 它依赖的符号：
 * 无依赖者处于第 0 层，同层按 id 稳定排序。只在给定节点集合上的诱导子图计算。
 */
fun topologicalLayers(ids: Collection<String>, dependencies: Map<String, List<String>>): List<List<String>> {
    val set = ids.toSet()
    val deps = set.associateWith { id -> dependencies[id].orEmpty().filter { it in set } }
    val indegree = HashMap<String, Int>()
    val dependents = HashMap<String, MutableSet<String>>()
    for (id in set) {
        indegree.putIfAbsent(id, 0)
        for (dep in deps[id].orEmpty()) {
            dependents.getOrPut(dep) { HashSet() }.add(id)
        }
    }
    for (id in set) indegree[id] = deps[id]!!.size
    val ready = sortedSetOf<String>().apply { addAll(set.filter { indegree[it] == 0 }) }
    val layers = ArrayList<List<String>>()
    var done = 0
    while (ready.isNotEmpty()) {
        val layer = ready.toList()
        layers.add(layer)
        ready.clear()
        done += layer.size
        for (node in layer) {
            for (dependent in dependents[node].orEmpty()) {
                val next = indegree.getValue(dependent) - 1
                indegree[dependent] = next
                if (next == 0) ready.add(dependent)
            }
        }
    }
    check(done == set.size) { "dependency cycle among replay entries" }
    return layers
}
