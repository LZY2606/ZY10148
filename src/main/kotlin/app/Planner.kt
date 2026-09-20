package app

/**
 * 两份快照之间的变化。
 *
 * 匹配规则（严格）：
 *  - 优先使用显式 renameMapping（oldId -> newId）；
 *  - 否则按相同符号 id 匹配；
 *  - 绝不按相同文本或编辑距离猜测改名。
 */
data class SnapshotDiff(
    val matched: Map<String, String>,
    val added: Set<String>,
    val deleted: Set<String>,
    /** 内容指纹或依赖集合发生变化的匹配对（newId -> oldId）。 */
    val changed: Map<String, String>,
    /** 仅文档元数据（doc/version 展示信息）不同：不传播。 */
    val docOnly: Set<String>,
)

class SnapshotValidationException(
    val unknownDependencies: List<String> = emptyList(),
    val cycleViolations: List<CycleViolation> = emptyList(),
) : RuntimeException(buildMessage(unknownDependencies, cycleViolations)) {
    companion object {
        private fun buildMessage(unknown: List<String>, cycles: List<CycleViolation>): String =
            buildList {
                if (unknown.isNotEmpty()) add("unknown dependencies: ${unknown.joinToString(", ")}")
                cycles.forEach { add(it.reason + " => " + it.scc.joinToString(" -> ")) }
            }.joinToString("; ")
    }
}

fun validateSnapshot(snapshot: Snapshot) {
    val ids = snapshot.symbols.map { it.id }.toSet()
    val unknown = snapshot.symbols
        .flatMap { it.dependencies }
        .filter { it !in ids }
        .distinct()
        .sorted()
    if (unknown.isNotEmpty()) {
        throw SnapshotValidationException(unknown, emptyList())
    }
    val cycles = analyzeCycles(snapshot.symbols)
    if (cycles.isNotEmpty()) {
        throw SnapshotValidationException(emptyList(), cycles)
    }
}

fun diffSnapshots(
    from: SnapshotRecord,
    to: SnapshotRecord,
    renameMapping: Map<String, String>,
): SnapshotDiff {
    val oldById = from.symbols.associateBy { it.id }
    val newById = to.symbols.associateBy { it.id }

    for ((oldId, newId) in renameMapping) {
        if (oldId !in oldById) throw ApiException(400, "rename mapping references unknown old symbol: $oldId")
        if (newId !in newById) throw ApiException(400, "rename mapping references unknown new symbol: $newId")
    }

    val matched = LinkedHashMap<String, String>()
    val oldTaken = HashSet<String>()
    for (newId in newById.keys.sorted()) {
        val mappedOld = renameMapping.entries.firstOrNull { it.value == newId }?.key
        val oldId = mappedOld ?: newId.takeIf { it in oldById }
        if (oldId != null) {
            if (oldId in oldTaken) throw ApiException(400, "duplicate mapping target for old symbol $oldId")
            matched[newId] = oldId
            oldTaken.add(oldId)
        }
    }

    val added = newById.keys - matched.keys
    val deleted = oldById.keys - oldTaken

    val changed = LinkedHashMap<String, String>()
    val docOnly = HashSet<String>()
    for ((newId, oldId) in matched.toSortedMap()) {
        val oldSym = oldById.getValue(oldId)
        val newSym = newById.getValue(newId)
        val contentChanged = symbolFingerprint(oldSym) != symbolFingerprint(newSym)
        val depsChanged = normalizeDeps(oldSym.dependencies, renameMapping) !=
            newSym.dependencies.distinct().sorted()
        if (contentChanged || depsChanged) {
            changed[newId] = oldId
        } else if (oldSym.doc != newSym.doc) {
            docOnly.add(newId)
        }
    }
    return SnapshotDiff(matched, added, deleted, changed, docOnly)
}

private fun normalizeDeps(deps: List<String>, renameMapping: Map<String, String>): List<String> =
    deps.map { renameMapping[it] ?: it }.distinct().sorted()

data class PlanBuild(
    val changedSymbols: List<String>,
    val affected: Set<String>,
    val entries: List<PlanEntry>,
    val frontier: List<String>,
    val deletedSymbols: List<String>,
)

/**
 * 影响传播与最小重放集。
 *
 * 1. changed seeds = diff 中内容/依赖改变或新增的符号；
 * 2. 在新快照图上反向传播：任何（传递）依赖种子的符号均受影响；
 * 3. 最小重放集 = 受影响集合中的引理（公理/定义不是可重放证明）；
 * 4. 原因分类：
 *    - 新引理（旧快照无证明）            -> MISSING_PROOF
 *    - 自身指纹变化                      -> CONTENT
 *    - 其余（指纹未变但公理/依赖改变）   -> DEPENDENCY
 * 5. 在重放诱导子图上做拓扑分层，同层按 id 稳定排序。
 *
 * 最小性：不在集合中的引理指纹未变，且其全部（传递）依赖均未改变，
 * 因而旧证明仍然成立，无需重放。
 */
fun buildPlan(
    from: SnapshotRecord,
    to: SnapshotRecord,
    diff: SnapshotDiff,
): PlanBuild {
    val analysis = analyze(to.symbols)
    val oldById = from.symbols.associateBy { it.id }

    val changedSeeds = (diff.changed.keys + diff.added).sorted()

    // dependents[x] = 直接依赖 x 的符号集合。
    val dependents = HashMap<String, MutableList<String>>()
    for ((id, deps) in analysis.dependencies) {
        for (dep in deps) dependents.getOrPut(dep) { mutableListOf() }.add(id)
    }

    val affected = HashSet<String>()
    val queue = ArrayDeque(changedSeeds)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!affected.add(current)) continue
        dependents[current].orEmpty().sorted().forEach { queue.addLast(it) }
    }

    val replayIds = affected
        .filter { analysis.symbols.getValue(it).kind == NodeKind.LEMMA }
        .sorted()

    val layers = topologicalLayers(replayIds, analysis.dependencies)
    val layerOf = HashMap<String, Int>()
    layers.forEachIndexed { index, ids -> ids.forEach { layerOf[it] = index } }

    val entries = replayIds.map { newId ->
        val newSym = analysis.symbols.getValue(newId)
        val oldId = diff.matched[newId]
        val oldSym = oldId?.let { oldById[it] }
        val reason = when {
            oldSym == null -> EntryReason.MISSING_PROOF
            symbolFingerprint(oldSym) != symbolFingerprint(newSym) -> EntryReason.CONTENT
            else -> EntryReason.DEPENDENCY
        }
        PlanEntry(
            symbolId = newId,
            layer = layerOf.getValue(newId),
            reason = reason,
            oldFingerprint = oldSym?.let { symbolFingerprint(it) },
            newFingerprint = symbolFingerprint(newSym),
            sliceDependencies = newSym.dependencies.sorted(),
        )
    }

    return PlanBuild(
        changedSymbols = changedSeeds,
        affected = affected,
        entries = entries,
        frontier = changedSeeds,
        deletedSymbols = diff.deleted.sorted(),
    )
}
