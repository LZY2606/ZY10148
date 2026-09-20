package app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

object EventTypes {
    const val SNAPSHOT_IMPORTED = "SNAPSHOT_IMPORTED"
    const val PLAN_CREATED = "PLAN_CREATED"
    const val PLAN_FROZEN = "PLAN_FROZEN"
    const val SLICE_NARROWED = "SLICE_NARROWED"
    const val BATCH_SUBMITTED = "BATCH_SUBMITTED"
    const val AXIOM_AUDITED = "AXIOM_AUDITED"
}

/**
 * 内存读模型：从不可变事件日志重放得到。所有变更都先 append 事件再 apply。
 */
class AppState {
    /** snapshotFingerprint -> record，按库分组。 */
    val snapshots = LinkedHashMap<String, SnapshotRecord>()
    val plans = LinkedHashMap<String, PlanRecord>()
    val batches = ArrayList<BatchRecord>()
    val audits = ArrayList<AuditRecord>()
    val events = ArrayList<StoredEvent>()

    fun plan(planId: String): PlanRecord =
        plans[planId] ?: throw ApiException(404, "unknown plan: $planId")

    fun version(planId: String, version: Int? = null): PlanVersion {
        val plan = plan(planId)
        val v = version ?: plan.currentVersion
        return plan.versions.firstOrNull { it.version == v }
            ?: throw ApiException(404, "plan $planId has no version $v")
    }
}

class AppService(private val store: EventStore) {
    val state = AppState()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        store.allEvents().forEach { replay(it) }
    }

    // ---------- 快照导入 ----------

    fun importSnapshot(snapshot: Snapshot): Pair<SnapshotRecord, Boolean> {
        validateSnapshot(snapshot)
        val fingerprint = snapshot.snapshotFingerprint ?: snapshotFingerprint(snapshot)
        val existing = state.snapshots[fingerprint]
        if (existing != null) {
            return existing to false // 幂等：同库 + 同内容指纹直接返回已存在快照
        }
        val record = SnapshotRecord(
            library = snapshot.library,
            version = snapshot.version,
            snapshotFingerprint = fingerprint,
            importedAt = Instant.now().toString(),
            symbols = snapshot.symbols,
        )
        append(EventTypes.SNAPSHOT_IMPORTED, encode(record, SnapshotRecord.serializer()))
        return record to true
    }

    fun snapshots(library: String?): List<SnapshotRecord> =
        state.snapshots.values
            .filter { library == null || it.library == library }
            .sortedWith(compareBy({ it.library }, { it.importedAt }, { it.snapshotFingerprint }))

    fun snapshot(fingerprint: String): SnapshotRecord =
        state.snapshots[fingerprint] ?: throw ApiException(404, "unknown snapshot: $fingerprint")

    // ---------- 计划 ----------

    fun createPlan(request: PlanRequest): PlanVersion {
        val from = snapshot(request.fromSnapshot)
        val to = snapshot(request.toSnapshot)
        if (from.library != request.library || to.library != request.library) {
            throw ApiException(400, "snapshots must belong to library ${request.library}")
        }
        if (from.snapshotFingerprint == to.snapshotFingerprint) {
            throw ApiException(400, "from and to snapshots are identical")
        }
        val diff = diffSnapshots(from, to, request.renameMapping)
        val build = buildPlan(from, to, diff)
        val planId = "plan-${state.plans.size + 1}"
        val version = PlanVersion(
            planId = planId,
            version = 1,
            library = request.library,
            fromSnapshotFingerprint = from.snapshotFingerprint,
            toSnapshotFingerprint = to.snapshotFingerprint,
            renameMapping = request.renameMapping.toSortedMap(),
            entries = build.entries,
            changedSymbols = build.changedSymbols,
            frontier = build.frontier,
            deletedSymbols = build.deletedSymbols,
            createdAt = Instant.now().toString(),
        )
        append(
            EventTypes.PLAN_CREATED,
            buildJsonObject {
                put("planId", planId)
                put("version", json.encodeToJsonElement(PlanVersion.serializer(), version))
            },
        )
        return state.version(planId, 1)
    }

    fun freezePlan(planId: String, request: FreezeRequest): PlanVersion {
        val current = state.version(planId, request.planVersion)
        if (current.frozen) throw ApiException(409, "plan version ${current.version} already frozen")
        append(
            EventTypes.PLAN_FROZEN,
            buildJsonObject {
                put("planId", planId)
                put("planVersion", current.version)
                put("note", request.note)
            },
        )
        return state.version(planId, current.version)
    }

    /**
     * 在失败定理上缩小证明依赖切片：产生该计划的新版本。
     * 新切片必须是当前切片的子集，且仍是新快照中存在的依赖；
     * 其它条目原样保留，层号不变。
     */
    fun narrowSlice(planId: String, request: NarrowRequest): PlanVersion {
        val base = state.version(planId, request.planVersion)
        if (base.frozen) throw ApiException(409, "plan version ${base.version} is frozen")
        val toSnapshot = snapshot(base.toSnapshotFingerprint)
        val symbol = toSnapshot.symbols.firstOrNull { it.id == request.symbolId }
            ?: throw ApiException(404, "unknown symbol in target snapshot: ${request.symbolId}")
        if (symbol.kind != NodeKind.LEMMA) {
            throw ApiException(400, "${request.symbolId} is not a theorem")
        }
        val entry = base.entries.firstOrNull { it.symbolId == request.symbolId }
            ?: throw ApiException(400, "${request.symbolId} is not in replay plan version ${request.planVersion}")
        val wanted = request.sliceDependencies.distinct().sorted()
        val legalDeps = symbol.dependencies.toSet()
        val illegal = wanted.filter { it !in legalDeps }
        if (illegal.isNotEmpty()) {
            throw ApiException(400, "slice dependencies not declared by symbol: ${illegal.joinToString(", ")}")
        }
        if (!entry.sliceDependencies.containsAll(wanted)) {
            throw ApiException(
                400,
                "narrowed slice must be a subset of the current slice " +
                    entry.sliceDependencies.joinToString(", "),
            )
        }
        if (wanted == entry.sliceDependencies) {
            throw ApiException(400, "slice is unchanged")
        }
        val nextVersion = state.plan(planId).currentVersion + 1
        val newEntries = base.entries.map {
            if (it.symbolId == request.symbolId) it.copy(sliceDependencies = wanted, narrowed = true) else it
        }
        val created = PlanVersion(
            planId = planId,
            version = nextVersion,
            parentVersion = base.version,
            library = base.library,
            fromSnapshotFingerprint = base.fromSnapshotFingerprint,
            toSnapshotFingerprint = base.toSnapshotFingerprint,
            renameMapping = base.renameMapping,
            entries = newEntries,
            changedSymbols = base.changedSymbols,
            frontier = base.frontier,
            deletedSymbols = base.deletedSymbols,
            createdAt = Instant.now().toString(),
        )
        append(
            EventTypes.SLICE_NARROWED,
            buildJsonObject {
                put("planId", planId)
                put("version", json.encodeToJsonElement(PlanVersion.serializer(), created))
            },
        )
        return state.version(planId, nextVersion)
    }

    // ---------- 重放批次 ----------

    /**
     * 提交某拓扑层的重放结果。中途任何校验失败 => 整体未发布（不写事件）。
     * 结果只能提交到它所声明的计划版本。
     */
    fun submitBatch(planId: String, request: BatchSubmitRequest): BatchRecord {
        val planVersion = state.version(planId, request.planVersion)
        val layerEntries = planVersion.entries.filter { it.layer == request.layer }
        if (layerEntries.isEmpty()) {
            throw ApiException(404, "plan version ${request.planVersion} has no layer ${request.layer}")
        }
        val expected = layerEntries.map { it.symbolId }.toSet()
        val given = request.results.map { it.symbolId }.toSet()
        if (given.size != request.results.size) throw ApiException(400, "duplicate theorem results")
        if (given != expected) {
            val missing = expected - given
            val extra = given - expected
            throw ApiException(
                400,
                "batch must cover exactly layer ${request.layer} (${expected.sorted().joinToString(", ")}): " +
                    "missing=[${missing.sorted().joinToString(", ")}] extra=[${extra.sorted().joinToString(", ")}]",
            )
        }
        val already = state.batches
            .filter { it.planId == planId && it.planVersion == request.planVersion && it.published }
            .flatMap { it.symbolIds }
            .toSet()
        val overlap = expected.intersect(already)
        if (overlap.isNotEmpty()) {
            throw ApiException(409, "theorems already published in this plan version: ${overlap.sorted().joinToString(", ")}")
        }

        for (result in request.results) {
            val entry = layerEntries.first { it.symbolId == result.symbolId }
            val evidenceDetail = result.evidence
            if (evidenceDetail.checksum.isBlank() || evidenceDetail.certificate.isBlank()
                || evidenceDetail.prover.isBlank()
            ) {
                throw ApiException(400, "incomplete evidence for ${result.symbolId}")
            }
            val used = result.usedDependencies.distinct().sorted()
            if (used != entry.sliceDependencies) {
                throw ApiException(
                    400,
                    "evidence for ${result.symbolId} must use exactly the declared slice " +
                        "[${entry.sliceDependencies.joinToString(", ")}], got [${used.joinToString(", ")}]",
                )
            }
        }

        val record = BatchRecord(
            batchId = "batch-${state.batches.size + 1}",
            planId = planId,
            planVersion = request.planVersion,
            layer = request.layer,
            symbolIds = expected.sorted(),
            published = true,
            results = request.results.sortedBy { it.symbolId },
            submittedAt = Instant.now().toString(),
        )
        append(
            EventTypes.BATCH_SUBMITTED,
            json.encodeToJsonElement(BatchRecord.serializer(), record) as JsonObject,
        )
        return state.batches.last { it.batchId == record.batchId }
    }

    fun batches(planId: String?): List<BatchRecord> =
        state.batches.filter { planId == null || it.planId == planId }

    // ---------- 外部公理审核 ----------

    fun auditAxiom(request: AuditRequest): AuditRecord {
        val snapshotRecord = snapshot(request.snapshotFingerprint)
        val symbol = snapshotRecord.symbols.firstOrNull { it.id == request.symbolId }
            ?: throw ApiException(404, "unknown symbol ${request.symbolId} in snapshot")
        if (symbol.kind != NodeKind.AXIOM) {
            throw ApiException(400, "${request.symbolId} is not an axiom")
        }
        if (!symbol.external) {
            throw ApiException(400, "${request.symbolId} is not an external axiom; audit is only for external axioms")
        }
        val record = AuditRecord(
            snapshotFingerprint = request.snapshotFingerprint,
            symbolId = request.symbolId,
            status = request.status,
            reviewer = request.reviewer,
            note = request.note,
            at = Instant.now().toString(),
        )
        append(
            EventTypes.AXIOM_AUDITED,
            json.encodeToJsonElement(AuditRecord.serializer(), record) as JsonObject,
        )
        return state.audits.last {
            it.snapshotFingerprint == record.snapshotFingerprint &&
                it.symbolId == record.symbolId && it.at == record.at
        }
    }

    fun audits(snapshotFingerprint: String?): List<AuditRecord> =
        state.audits.filter { snapshotFingerprint == null || it.snapshotFingerprint == snapshotFingerprint }

    // ---------- 图视图 ----------

    fun graph(fingerprint: String): GraphView {
        val record = snapshot(fingerprint)
        val (sccOf, components) = tarjan(record.symbols)
        val byId = record.symbols.associateBy { it.id }
        val violations = analyzeCycles(record.symbols).associate { it.scc.first() to it.reason }
        val sccInfos = components.mapIndexed { index, ids ->
            val first = ids.first()
            val violation = components[index].let { component ->
                val cyclic = component.size > 1 ||
                    byId.getValue(component.single()).dependencies.contains(component.single())
                cyclic && component.any {
                    val s = byId.getValue(it)
                    s.kind != NodeKind.DEFINITION || !s.recursive
                }
            }
            SccInfo(
                index = index,
                ids = ids,
                legal = !violation,
                reason = if (violation) violations[first] ?: "undeclared cycle" else "legal dependency component",
            )
        }
        val depths = sccDepths(components, sccOf, record.symbols)
        val nodes = record.symbols
            .sortedBy { it.id }
            .map { s ->
                GraphNode(
                    id = s.id,
                    kind = s.kind,
                    external = s.external,
                    recursive = s.recursive,
                    scc = sccOf.getValue(s.id),
                    depth = depths.getValue(s.id),
                )
            }
        val edges = record.symbols
            .sortedBy { it.id }
            .flatMap { s -> s.dependencies.sorted().map { GraphEdge(s.id, it) } }
        return GraphView(
            library = record.library,
            version = record.version,
            snapshotFingerprint = record.snapshotFingerprint,
            nodes = nodes,
            edges = edges,
            sccs = sccInfos,
        )
    }

    private fun sccDepths(
        components: List<List<String>>,
        sccOf: Map<String, Int>,
        symbols: List<Symbol>,
    ): Map<String, Int> {
        val componentEdges = HashMap<Int, HashSet<Int>>()
        for (s in symbols) {
            for (dep in s.dependencies) {
                val a = sccOf.getValue(s.id)
                val b = sccOf.getValue(dep)
                if (a != b) componentEdges.getOrPut(a) { HashSet() }.add(b)
            }
        }
        val memo = HashMap<Int, Int>()
        fun depth(component: Int): Int = memo.getOrPut(component) {
            val next = componentEdges[component].orEmpty()
            if (next.isEmpty()) 0 else 1 + next.maxOf { depth(it) }
        }
        return symbols.associate { it.id to depth(sccOf.getValue(it.id)) }
    }

    // ---------- 事件追加与重放 ----------

    private fun append(type: String, payload: JsonObject) {
        val event = store.append(type, payload)
        replay(event)
    }

    private fun <T> encode(value: T, serializer: kotlinx.serialization.KSerializer<T>): JsonObject =
        json.encodeToJsonElement(serializer, value) as JsonObject

    private fun replay(event: StoredEvent) {
        state.events.add(event)
        when (event.type) {
            EventTypes.SNAPSHOT_IMPORTED -> {
                val record = json.decodeFromJsonElement(SnapshotRecord.serializer(), event.payload)
                state.snapshots[record.snapshotFingerprint] = record
            }
            EventTypes.PLAN_CREATED -> {
                val version = json.decodeFromJsonElement(
                    PlanVersion.serializer(),
                    event.payload.jsonObject.getValue("version").jsonObject,
                )
                val record = PlanRecord(
                    planId = version.planId,
                    library = version.library,
                    currentVersion = 1,
                    versions = listOf(version.copy(createdByEventSeq = event.seq)),
                )
                state.plans[version.planId] = record
            }
            EventTypes.PLAN_FROZEN -> {
                val planId = event.payload.getValue("planId").jsonPrimitive.content
                val v = event.payload.getValue("planVersion").jsonPrimitive.int
                val plan = state.plan(planId)
                val versions = plan.versions.map { if (it.version == v) it.copy(frozen = true) else it }
                state.plans[planId] = plan.copy(versions = versions)
            }
            EventTypes.SLICE_NARROWED -> {
                val version = json.decodeFromJsonElement(
                    PlanVersion.serializer(),
                    event.payload.jsonObject.getValue("version").jsonObject,
                ).copy(createdByEventSeq = event.seq)
                val plan = state.plan(version.planId)
                state.plans[version.planId] = plan.copy(
                    currentVersion = version.version,
                    versions = plan.versions + version,
                )
            }
            EventTypes.BATCH_SUBMITTED -> {
                val record = json.decodeFromJsonElement(BatchRecord.serializer(), event.payload)
                    .copy(eventSeq = event.seq)
                state.batches.add(record)
            }
            EventTypes.AXIOM_AUDITED -> {
                val record = json.decodeFromJsonElement(AuditRecord.serializer(), event.payload)
                    .copy(eventSeq = event.seq)
                state.audits.add(record)
            }
            else -> throw IllegalStateException("unknown event type: ${event.type}")
        }
    }

    fun planStatus(planId: String, version: Int? = null): JsonObject {
        val v = state.version(planId, version)
        val published = state.batches
            .filter { it.planId == planId && it.planVersion == v.version && it.published }
            .flatMap { batch -> batch.results.map { it.symbolId to it.status } }
            .toMap()
        val entries = v.entries.map { entry ->
            buildJsonObject {
                put("symbolId", entry.symbolId)
                put("layer", entry.layer)
                put("reason", entry.reason.name)
                put("narrowed", entry.narrowed)
                put("sliceDependencies", JsonArray(entry.sliceDependencies.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                put("status", published[entry.symbolId]?.name ?: "PENDING")
            }
        }
        return buildJsonObject {
            put("planId", planId)
            put("version", v.version)
            put("parentVersion", v.parentVersion)
            put("frozen", v.frozen)
            put("toSnapshotFingerprint", v.toSnapshotFingerprint)
            put("fromSnapshotFingerprint", v.fromSnapshotFingerprint)
            put("changedSymbols", JsonArray(v.changedSymbols.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("frontier", JsonArray(v.frontier.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("deletedSymbols", JsonArray(v.deletedSymbols.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("entries", JsonArray(entries))
        }
    }

    fun externalAxiomStatus(fingerprint: String): List<JsonObject> {
        val record = snapshot(fingerprint)
        val audits = state.audits.filter { it.snapshotFingerprint == fingerprint }
        return record.symbols
            .filter { it.kind == NodeKind.AXIOM && it.external }
            .map { s ->
                val latest = audits.lastOrNull { it.symbolId == s.id }
                buildJsonObject {
                    put("symbolId", s.id)
                    put("status", latest?.status?.name ?: AuditStatus.PENDING.name)
                    put("reviewer", latest?.reviewer ?: "")
                    put("note", latest?.note ?: "")
                    put("at", latest?.at ?: "")
                }
            }
    }

    val jsonPublic: Json get() = json
}
