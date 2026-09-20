package app

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class HttpError(val status: Int, message: String) : RuntimeException(message)

data class EventRow(
    val seq: Long,
    val eventType: String,
    val payload: String,
    val prevHash: String,
    val hash: String,
    val createdAt: Long
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "seq" to seq,
        "eventType" to eventType,
        "payload" to Json.parse(payload),
        "prevHash" to prevHash,
        "hash" to hash,
        "createdAt" to createdAt
    )
}

data class PlanRow(
    val id: Long,
    val planId: String,
    val planVersion: Int,
    val oldSnapshotId: Long,
    val newSnapshotId: Long,
    val mappingJson: String,
    val planFingerprint: String,
    val frozen: Boolean,
    val items: List<PlanItem>,
    val layers: List<List<String>>,
    val changedSeeds: List<ChangedSymbol>,
    val docOnly: List<String>,
    val affectedLemmas: List<String>,
    val boundary: List<String>,
    val createdAt: Long
) {
    fun toJson(snapshotInfo: Pair<Snapshot, Snapshot>? = null): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "planId" to planId,
        "planVersion" to planVersion,
        "oldSnapshotId" to oldSnapshotId,
        "newSnapshotId" to newSnapshotId,
        "frozen" to frozen,
        "planFingerprint" to planFingerprint,
        "mapping" to Json.parse(mappingJson),
        "changedSeeds" to changedSeeds.map { it.toJson() },
        "docOnly" to docOnly,
        "affectedLemmas" to affectedLemmas,
        "boundary" to boundary,
        "layers" to layers,
        "items" to items.map { it.toJson() },
        "createdAt" to createdAt
    )
}

data class BatchResultRow(
    val id: Long,
    val planRowId: Long,
    val planVersion: Int,
    val batch: Int,
    val status: String,
    val evidenceJson: String,
    val createdAt: Long
)

data class ReviewRow(
    val id: Long,
    val planId: String,
    val axiomFqn: String,
    val status: String,
    val reviewer: String,
    val note: String,
    val eventSeq: Long,
    val createdAt: Long
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "id" to id, "planId" to planId, "axiomFqn" to axiomFqn,
        "status" to status, "reviewer" to reviewer, "note" to note,
        "eventSeq" to eventSeq, "createdAt" to createdAt
    )
}

data class SliceRow(
    val id: Long,
    val planId: String,
    val lemmaFqn: String,
    val sliceFqns: List<String>,
    val frontier: List<String>,
    val evidenceJson: String,
    val eventSeq: Long,
    val createdAt: Long
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "id" to id, "planId" to planId, "lemmaFqn" to lemmaFqn,
        "sliceFqns" to sliceFqns, "frontier" to frontier,
        "evidence" to Json.parse(evidenceJson),
        "eventSeq" to eventSeq, "createdAt" to createdAt
    )
}

class Store(dbPath: String) : AutoCloseable {
    private val conn: Connection

    init {
        if (dbPath != ":memory:") {
            val p = Path.of(dbPath)
            p.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        }
        Class.forName("org.sqlite.JDBC")
        val url = if (dbPath == ":memory:") "jdbc:sqlite::memory:" else "jdbc:sqlite:$dbPath"
        conn = DriverManager.getConnection(url)
        conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        conn.autoCommit = false
        migrate()
        conn.commit()
    }

    override fun close() = conn.close()

    private fun migrate() {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS snapshot (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  library TEXT NOT NULL,
                  library_version TEXT NOT NULL,
                  snapshot_fingerprint TEXT NOT NULL,
                  payload_json TEXT NOT NULL,
                  imported_at INTEGER NOT NULL,
                  UNIQUE(library, snapshot_fingerprint)
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS symbol (
                  snapshot_id INTEGER NOT NULL REFERENCES snapshot(id) ON DELETE CASCADE,
                  fqn TEXT NOT NULL,
                  ord INTEGER NOT NULL,
                  kind TEXT NOT NULL,
                  content_fingerprint TEXT NOT NULL,
                  doc_fingerprint TEXT NOT NULL,
                  proof_ref TEXT,
                  check_status TEXT NOT NULL,
                  external INTEGER NOT NULL,
                  PRIMARY KEY (snapshot_id, fqn)
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS dep_edge (
                  snapshot_id INTEGER NOT NULL REFERENCES snapshot(id) ON DELETE CASCADE,
                  src_fqn TEXT NOT NULL,
                  dst_fqn TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  PRIMARY KEY (snapshot_id, kind, src_fqn, dst_fqn)
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS event (
                  seq INTEGER PRIMARY KEY AUTOINCREMENT,
                  event_type TEXT NOT NULL,
                  payload_json TEXT NOT NULL,
                  prev_hash TEXT NOT NULL,
                  hash TEXT NOT NULL,
                  created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS plan (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  plan_id TEXT NOT NULL UNIQUE,
                  plan_version INTEGER NOT NULL,
                  old_snapshot_id INTEGER NOT NULL REFERENCES snapshot(id),
                  new_snapshot_id INTEGER NOT NULL REFERENCES snapshot(id),
                  mapping_json TEXT NOT NULL,
                  plan_fingerprint TEXT NOT NULL,
                  frozen INTEGER NOT NULL DEFAULT 0,
                  detail_json TEXT NOT NULL,
                  created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS batch_result (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  plan_row_id INTEGER NOT NULL REFERENCES plan(id) ON DELETE CASCADE,
                  plan_version INTEGER NOT NULL,
                  batch INTEGER NOT NULL,
                  status TEXT NOT NULL,
                  evidence_json TEXT NOT NULL,
                  created_at INTEGER NOT NULL,
                  UNIQUE(plan_row_id, batch)
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS axiom_review (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  plan_id TEXT NOT NULL,
                  axiom_fqn TEXT NOT NULL,
                  status TEXT NOT NULL,
                  reviewer TEXT NOT NULL,
                  note TEXT NOT NULL,
                  event_seq INTEGER NOT NULL,
                  created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS proof_slice (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  plan_id TEXT NOT NULL,
                  lemma_fqn TEXT NOT NULL,
                  slice_fqns_json TEXT NOT NULL,
                  frontier_json TEXT NOT NULL,
                  evidence_json TEXT NOT NULL,
                  event_seq INTEGER NOT NULL,
                  created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    // ---------- helpers ----------

    private fun <T> tx(body: () -> T): T {
        try {
            val r = body()
            conn.commit()
            return r
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        }
    }

    /**
     * Decision events must survive business-transaction rollback (e.g. a rejected
     * import or an unpublished failed batch). We commit the pending unit of work first,
     * insert the event in its own transaction, then the caller throws so that the rest
     * of the outer operation is rolled back while this event stays.
     */
    private fun appendEventDurable(type: String, payload: Any?): EventRow {
        conn.commit()
        val evt = appendEvent(type, payload)
        conn.commit()
        return evt
    }

    private fun appendEvent(type: String, payload: Any?): EventRow {
        val payloadJson = Json.write(payload)
        val prev = conn.prepareStatement("SELECT hash FROM event ORDER BY seq DESC LIMIT 1").executeQuery()
            .use { if (it.next()) it.getString(1) else "GENESIS" }
        val now = System.currentTimeMillis()
        val hash = Hashing.sha256Hex("$prev|$type|$payloadJson|$now")
        conn.prepareStatement(
            "INSERT INTO event(event_type, payload_json, prev_hash, hash, created_at) VALUES (?,?,?,?,?)"
        ).use {
            it.setString(1, type); it.setString(2, payloadJson); it.setString(3, prev)
            it.setString(4, hash); it.setLong(5, now)
            it.executeUpdate()
        }
        val seq = conn.createStatement().executeQuery("SELECT last_insert_rowid()").use {
            it.next(); it.getLong(1)
        }
        return EventRow(seq, type, payloadJson, prev, hash, now)
    }

    fun listEvents(): List<EventRow> = tx {
        conn.createStatement().executeQuery("SELECT seq, event_type, payload_json, prev_hash, hash, created_at FROM event ORDER BY seq")
            .use { rs -> generateSequence { if (rs.next()) rs else null }.map { it.eventRow() }.toList() }
    }

    private fun ResultSet.eventRow() = EventRow(
        getLong("seq"), getString("event_type"), getString("payload_json"),
        getString("prev_hash"), getString("hash"), getLong("created_at")
    )

    /** Verifies the append-only hash chain; throws if any event has been altered. */
    fun verifyChain(): Boolean {
        var prev = "GENESIS"
        for (e in listEvents()) {
            if (e.prevHash != prev) throw HttpError(409, "event chain broken at seq ${e.seq} (prevHash)")
            val expect = Hashing.sha256Hex("${e.prevHash}|${e.eventType}|${e.payload}|${e.createdAt}")
            if (expect != e.hash) throw HttpError(409, "event chain broken at seq ${e.seq} (hash)")
            prev = e.hash
        }
        return true
    }

    // ---------- snapshots ----------

    fun importSnapshot(input: SnapshotInput): Pair<Snapshot, Boolean> = tx {
        val fp = input.snapshotFingerprint()
        val existing = findSnapshotRow(input.library, fp)
        if (existing != null) return@tx (existing as Snapshot) to false
        val graph = Graph(input.symbols)
        val illegal = graph.cyclicSccs().filter { !it.legalRecursion }
        if (illegal.isNotEmpty()) {
            val detail = illegal.joinToString("; ") { "${it.members} :: ${it.reason}" }
            appendEventDurable(
                "SNAPSHOT_REJECTED",
                linkedMapOf(
                    "library" to input.library,
                    "libraryVersion" to input.libraryVersion,
                    "snapshotFingerprint" to fp,
                    "illegalSccs" to illegal.map { it.toJson() }
                )
            )
            throw HttpError(422, "illegal proof cycle: $detail")
        }
        val now = System.currentTimeMillis()
        conn.prepareStatement(
            "INSERT INTO snapshot(library, library_version, snapshot_fingerprint, payload_json, imported_at) VALUES (?,?,?,?,?)",
        ).use {
            it.setString(1, input.library); it.setString(2, input.libraryVersion)
            it.setString(3, fp); it.setString(4, Json.write(input.toJson())); it.setLong(5, now)
            it.executeUpdate()
        }
        val id = conn.createStatement().executeQuery("SELECT last_insert_rowid()").use {
            it.next(); it.getLong(1)
        }
        input.symbols.forEach { s ->
            conn.prepareStatement(
                "INSERT INTO symbol(snapshot_id, fqn, ord, kind, content_fingerprint, doc_fingerprint, proof_ref, check_status, external) VALUES (?,?,?,?,?,?,?,?,?)"
            ).use {
                it.setLong(1, id); it.setString(2, s.fqn); it.setInt(3, s.ord); it.setString(4, s.kind.name)
                it.setString(5, s.contentFingerprint); it.setString(6, s.docFingerprint)
                it.setString(7, s.proofRef); it.setString(8, s.checkStatus); it.setInt(9, if (s.external) 1 else 0)
                it.executeUpdate()
            }
            s.directDeps.forEach { d ->
                conn.prepareStatement("INSERT INTO dep_edge(snapshot_id, src_fqn, dst_fqn, kind) VALUES (?,?,?,'DEP')").use {
                    it.setLong(1, id); it.setString(2, s.fqn); it.setString(3, d); it.executeUpdate()
                }
            }
            s.parallelGoals.forEach { g ->
                conn.prepareStatement("INSERT INTO dep_edge(snapshot_id, src_fqn, dst_fqn, kind) VALUES (?,?,?,'PARALLEL')").use {
                    it.setLong(1, id); it.setString(2, s.fqn); it.setString(3, g); it.executeUpdate()
                }
            }
        }
        val sccs = graph.sccs()
        appendEvent(
            "SNAPSHOT_IMPORTED",
            linkedMapOf(
                "snapshotId" to id,
                "library" to input.library,
                "libraryVersion" to input.libraryVersion,
                "snapshotFingerprint" to fp,
                "symbolCount" to input.symbols.size,
                "recursiveSccs" to sccs.filter { it.legalRecursion && (it.members.size > 1 || graph.outgoing[it.members[0]].orEmpty().contains(it.members[0])) }.map { it.toJson() }
            )
        )
        (loadSnapshot(id) ?: error("snapshot vanished after insert")) to true
    }

    private fun findSnapshotRow(library: String, fingerprint: String): Snapshot? =
        conn.prepareStatement("SELECT id FROM snapshot WHERE library = ? AND snapshot_fingerprint = ?").use {
            it.setString(1, library); it.setString(2, fingerprint)
            it.executeQuery().use { rs -> if (rs.next()) loadSnapshot(rs.getLong(1)) else null }
        }

    fun getSnapshot(id: Long): Snapshot =
        loadSnapshot(id) ?: throw HttpError(404, "snapshot $id not found")

    fun findSnapshot(library: String, version: String): Snapshot? =
        conn.prepareStatement("SELECT id FROM snapshot WHERE library = ? AND library_version = ? ORDER BY id DESC").use {
            it.setString(1, library); it.setString(2, version)
            it.executeQuery().use { rs -> if (rs.next()) loadSnapshot(rs.getLong(1)) else null }
        }

    fun listSnapshots(): List<Map<String, Any?>> = tx {
        conn.createStatement().executeQuery(
            "SELECT id, library, library_version, snapshot_fingerprint, imported_at FROM snapshot ORDER BY id"
        ).use { rs ->
            generateSequence { if (rs.next()) rs else null }.map {
                linkedMapOf(
                    "id" to it.getLong(1), "library" to it.getString(2),
                    "libraryVersion" to it.getString(3),
                    "snapshotFingerprint" to it.getString(4),
                    "importedAt" to it.getLong(5)
                )
            }.toList()
        }
    }

    private fun loadSnapshot(id: Long): Snapshot? {
        val meta = conn.prepareStatement(
            "SELECT library, library_version, snapshot_fingerprint, imported_at FROM snapshot WHERE id = ?"
        ).use {
            it.setLong(1, id); it.executeQuery().use { rs ->
                if (!rs.next()) return null
                listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4))
            }
        }
        val syms = conn.prepareStatement(
            "SELECT fqn, ord, kind, content_fingerprint, doc_fingerprint, proof_ref, check_status, external FROM symbol WHERE snapshot_id = ? ORDER BY ord, fqn"
        ).use {
            it.setLong(1, id)
            it.executeQuery().use { rs ->
                generateSequence { if (rs.next()) rs else null }.map { r ->
                    val fqn = r.getString(1)
                    val deps = edgeTargets(id, fqn, "DEP")
                    val parallel = edgeTargets(id, fqn, "PARALLEL")
                    Sym(
                        library = meta[0] as String,
                        libraryVersion = meta[1] as String,
                        fqn = fqn,
                        kind = NodeKind.valueOf(r.getString(3)),
                        ord = r.getInt(2),
                        contentFingerprint = r.getString(4),
                        docFingerprint = r.getString(5),
                        directDeps = deps,
                        parallelGoals = parallel,
                        checkStatus = r.getString(7),
                        proofRef = r.getString(6),
                        external = r.getInt(8) == 1
                    )
                }.toList()
            }
        }
        return Snapshot(id, meta[0] as String, meta[1] as String, meta[2] as String, meta[3] as Long, syms)
    }

    private fun edgeTargets(snapshotId: Long, fqn: String, kind: String): List<String> =
        conn.prepareStatement("SELECT dst_fqn FROM dep_edge WHERE snapshot_id = ? AND src_fqn = ? AND kind = ? ORDER BY dst_fqn").use {
            it.setLong(1, snapshotId); it.setString(2, fqn); it.setString(3, kind)
            it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }

    // ---------- plans ----------

    /**
     * Creating a plan for the same (old, new, mapping) comparison is idempotent:
     * the deterministic planId is reused and gets a fresh monotonic version only
     * when its inputs change.
     */
    fun createPlan(oldId: Long, newId: Long, mapping: Map<String, String>): PlanRow = tx {
        val old = getSnapshot(oldId); val new = getSnapshot(newId)
        if (old.library != new.library) throw HttpError(400, "snapshots belong to different libraries")
        val built = Analyzer.plan(old, new, mapping)
        val existing = findPlanByPlanId(built.planId)
        if (existing != null) return@tx existing
        val version = (conn.createStatement().executeQuery("SELECT COALESCE(MAX(plan_version),0) + 1 FROM plan").use {
            it.next(); it.getInt(1)
        })
        val detail = linkedMapOf(
            "layers" to built.layers,
            "changedSeeds" to built.changedSeeds.map { it.toJson() },
            "docOnly" to built.docOnly,
            "affectedLemmas" to built.affectedLemmas,
            "boundary" to built.boundary,
            "items" to built.items.map { it.toJson() }
        )
        val now = System.currentTimeMillis()
        conn.prepareStatement(
            "INSERT INTO plan(plan_id, plan_version, old_snapshot_id, new_snapshot_id, mapping_json, plan_fingerprint, frozen, detail_json, created_at) VALUES (?,?,?,?,?,0,?,?,?)",
        ).use {
            it.setString(1, built.planId); it.setInt(2, version)
            it.setLong(3, oldId); it.setLong(4, newId)
            it.setString(5, Json.write(mapping.toSortedMap())); it.setString(6, built.planFingerprint)
            it.setString(7, Json.write(detail)); it.setLong(8, now)
            it.executeUpdate()
        }
        val rowId = conn.createStatement().executeQuery("SELECT last_insert_rowid()").use {
            it.next(); it.getLong(1)
        }
        appendEvent(
            "PLAN_CREATED",
            linkedMapOf(
                "planRowId" to rowId, "planId" to built.planId, "planVersion" to version,
                "oldSnapshotId" to oldId, "newSnapshotId" to newId,
                "mapping" to mapping.toSortedMap(),
                "planFingerprint" to built.planFingerprint,
                "itemCount" to built.items.size,
                "layers" to built.layers,
                "reasons" to built.items.groupingBy { it.reason.name }.eachCount()
            )
        )
        getPlan(rowId)
    }

    fun listPlans(): List<PlanRow> = tx {
        conn.createStatement().executeQuery("SELECT id FROM plan ORDER BY id").use { rs ->
            generateSequence { if (rs.next()) rs.getLong(1) else null }.map { getPlan(it) }.toList()
        }
    }

    fun getPlanByPlanId(planId: String): PlanRow =
        findPlanByPlanId(planId) ?: throw HttpError(404, "plan '$planId' not found")

    private fun findPlanByPlanId(planId: String): PlanRow? =
        conn.prepareStatement("SELECT id FROM plan WHERE plan_id = ?").use {
            it.setString(1, planId)
            it.executeQuery().use { rs -> if (rs.next()) getPlan(rs.getLong(1)) else null }
        }

    fun getPlan(rowId: Long): PlanRow {
        val base = conn.prepareStatement("SELECT * FROM plan WHERE id = ?").use {
            it.setLong(1, rowId)
            it.executeQuery().use { rs ->
                if (!rs.next()) throw HttpError(404, "plan $rowId not found")
                listOf(
                    rs.getLong("id"), rs.getString("plan_id"), rs.getInt("plan_version"),
                    rs.getLong("old_snapshot_id"), rs.getLong("new_snapshot_id"),
                    rs.getString("mapping_json"), rs.getString("plan_fingerprint"),
                    rs.getInt("frozen") == 1, rs.getString("detail_json"), rs.getLong("created_at")
                )
            }
        }
        @Suppress("UNCHECKED_CAST")
        val detail = Json.parse(base[8] as String) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val layers = (detail["layers"] as List<Any?>).map { l -> (l as List<Any?>).map { it as String } }
        @Suppress("UNCHECKED_CAST")
        val items = (detail["items"] as List<Any?>).map { parseItem(it as Map<String, Any?>) }
        @Suppress("UNCHECKED_CAST")
        val seeds = (detail["changedSeeds"] as List<Any?>).map { parseChanged(it as Map<String, Any?>) }
        @Suppress("UNCHECKED_CAST")
        val docOnly = (detail["docOnly"] as List<Any?>).map { it as String }
        @Suppress("UNCHECKED_CAST")
        val affected = (detail["affectedLemmas"] as List<Any?>).map { it as String }
        @Suppress("UNCHECKED_CAST")
        val boundary = (detail["boundary"] as List<Any?>).map { it as String }
        return PlanRow(
            id = base[0] as Long, planId = base[1] as String, planVersion = base[2] as Int,
            oldSnapshotId = base[3] as Long, newSnapshotId = base[4] as Long,
            mappingJson = base[5] as String, planFingerprint = base[6] as String,
            frozen = base[7] as Boolean, items = items, layers = layers,
            changedSeeds = seeds, docOnly = docOnly, affectedLemmas = affected,
            boundary = boundary, createdAt = base[9] as Long
        )
    }

    private fun parseItem(m: Map<String, Any?>): PlanItem = PlanItem(
        fqn = m.getValue("fqn") as String,
        layer = (m.getValue("layer") as Number).toInt(),
        batch = (m.getValue("batch") as Number).toInt(),
        reason = EnterReason.valueOf(m.getValue("reason") as String),
        triggeredBy = (m.getValue("triggeredBy") as List<Any?>).map { it as String },
        oldContentFingerprint = m["oldContentFingerprint"] as String?,
        newContentFingerprint = m.getValue("newContentFingerprint") as String
    )

    private fun parseChanged(m: Map<String, Any?>): ChangedSymbol = ChangedSymbol(
        fqn = m.getValue("fqn") as String,
        kind = NodeKind.valueOf(m.getValue("kind") as String),
        change = m.getValue("change") as String,
        detail = m.getValue("detail") as String,
        external = m.getValue("external") as Boolean
    )

    fun freezePlan(planId: String, expectedVersion: Int?, note: String): PlanRow = tx {
        val plan = getPlanByPlanId(planId)
        if (expectedVersion != null && expectedVersion != plan.planVersion)
            throw HttpError(409, "version mismatch: plan is v${plan.planVersion}, request declared v$expectedVersion")
        if (!plan.frozen) {
            conn.prepareStatement("UPDATE plan SET frozen = 1 WHERE id = ?").use { it.setLong(1, plan.id); it.executeUpdate() }
            appendEvent(
                "PLAN_FROZEN",
                linkedMapOf("planId" to planId, "planVersion" to plan.planVersion, "note" to note)
            )
        } else {
            appendEvent(
                "PLAN_FROZEN_REDUNDANT_REJECTED",
                linkedMapOf("planId" to planId, "planVersion" to plan.planVersion, "note" to note)
            )
        }
        getPlan(plan.id)
    }

    private fun requireMutable(plan: PlanRow) {
        if (plan.frozen) throw HttpError(409, "plan ${plan.planId} v${plan.planVersion} is frozen; decisions are immutable")
    }

    // ---------- replay batches ----------

    /**
     * Submit a replay result. Results bind to the exact declared plan version and
     * batch. On any failure nothing is published for the batch: evidence is recorded
     * only as an append-only REPLAY_FAILED event.
     */
    fun submitReplay(
        planId: String,
        declaredVersion: Int,
        batch: Int,
        forcedFailures: Set<String>,
        recordedBy: String
    ): Map<String, Any?> = tx {
        val plan = getPlanByPlanId(planId)
        if (plan.frozen) throw HttpError(409, "plan is frozen; replay publication is locked")
        if (declaredVersion != plan.planVersion)
            throw HttpError(409, "replay declares plan v$declaredVersion but current is v${plan.planVersion}")
        if (batch < 0 || batch >= plan.layers.size) throw HttpError(404, "batch $batch does not exist in plan")

        existingBatch(plan.id, batch)?.let { existing ->
            return@tx linkedMapOf(
                "planId" to planId, "planVersion" to plan.planVersion, "batch" to batch,
                "status" to existing.status, "evidence" to Json.parse(existing.evidenceJson),
                "idempotent" to true
            )
        }
        if (batch > 0 && existingBatch(plan.id, batch - 1)?.let { it.status != "PUBLISHED" } != false)
            throw HttpError(409, "batch ${batch - 1} must be published before batch $batch")

        val newSnap = getSnapshot(plan.newSnapshotId)
        val checks = Replayer.checkBatch(plan, batch, newSnap, forcedFailures)
        val evidence = linkedMapOf(
            "planId" to planId,
            "planVersion" to plan.planVersion,
            "planFingerprint" to plan.planFingerprint,
            "batch" to batch,
            "recordedBy" to recordedBy,
            "checkedAt" to System.currentTimeMillis(),
            "results" to checks
        )
        if (checks.any { it["status"] == "FAILED" }) {
            val failed = checks.filter { it["status"] == "FAILED" }.map { it["fqn"] }
            appendEventDurable(
                "REPLAY_FAILED",
                linkedMapOf(
                    "planId" to planId, "planVersion" to plan.planVersion, "batch" to batch,
                    "failed" to failed, "evidence" to evidence
                )
            )
            linkedMapOf(
                "planId" to planId, "planVersion" to plan.planVersion, "batch" to batch,
                "status" to "FAILED", "published" to false, "results" to checks,
                "event" to "REPLAY_FAILED"
            )
        } else {
            val now = System.currentTimeMillis()
            val ev = Json.write(evidence)
            conn.prepareStatement(
                "INSERT INTO batch_result(plan_row_id, plan_version, batch, status, evidence_json, created_at) VALUES (?,?,?,'PUBLISHED',?,?)"
            ).use {
                it.setLong(1, plan.id); it.setInt(2, plan.planVersion); it.setInt(3, batch)
                it.setString(4, ev); it.setLong(5, now); it.executeUpdate()
            }
            val evt = appendEvent(
                "REPLAY_PUBLISHED",
                linkedMapOf(
                    "planId" to planId, "planVersion" to plan.planVersion, "batch" to batch,
                    "batchSize" to checks.size, "evidence" to evidence
                )
            )
            linkedMapOf(
                "planId" to planId, "planVersion" to plan.planVersion, "batch" to batch,
                "status" to "PUBLISHED", "published" to true, "results" to checks,
                "eventSeq" to evt.seq
            )
        }
    }

    private fun existingBatch(planRowId: Long, batch: Int): BatchResultRow? =
        conn.prepareStatement("SELECT id, plan_row_id, plan_version, batch, status, evidence_json, created_at FROM batch_result WHERE plan_row_id = ? AND batch = ?").use {
            it.setLong(1, planRowId); it.setInt(2, batch)
            it.executeQuery().use { rs ->
                if (!rs.next()) null else BatchResultRow(
                    rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getInt(4),
                    rs.getString(5), rs.getString(6), rs.getLong(7)
                )
            }
        }

    fun listBatches(planId: String): List<Map<String, Any?>> {
        val plan = getPlanByPlanId(planId)
        return tx {
            conn.prepareStatement("SELECT batch, status, evidence_json, created_at FROM batch_result WHERE plan_row_id = ? ORDER BY batch").use {
                it.setLong(1, plan.id)
                it.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) rs else null }.map { r ->
                        linkedMapOf(
                            "batch" to r.getInt(1), "status" to r.getString(2),
                            "evidence" to Json.parse(r.getString(3)), "createdAt" to r.getLong(4)
                        )
                    }.toList()
                }
            }
        }
    }

    // ---------- external axiom review ----------

    fun reviewAxiom(planId: String, axiomFqn: String, status: String, reviewer: String, note: String): ReviewRow = tx {
        val plan = getPlanByPlanId(planId)
        val allowed = setOf("UNREVIEWED", "CLAIMED", "APPROVED", "REJECTED")
        if (status !in allowed) throw HttpError(400, "status must be one of $allowed")
        val newSnap = getSnapshot(plan.newSnapshotId)
        val sym = newSnap.byFqn()[axiomFqn] ?: throw HttpError(404, "axiom '$axiomFqn' not found in new snapshot")
        if (sym.kind != NodeKind.AXIOM) throw HttpError(400, "'$axiomFqn' is ${sym.kind}, not an AXIOM")
        if (!sym.external) throw HttpError(400, "'$axiomFqn' is not an external axiom; review records are for external axioms only")
        val evt = appendEvent(
            "EXTERNAL_AXIOM_REVIEWED",
            linkedMapOf(
                "planId" to planId, "planVersion" to plan.planVersion, "axiomFqn" to axiomFqn,
                "status" to status, "reviewer" to reviewer, "note" to note,
                "contentFingerprint" to sym.contentFingerprint
            )
        )
        val now = System.currentTimeMillis()
        conn.prepareStatement(
            "INSERT INTO axiom_review(plan_id, axiom_fqn, status, reviewer, note, event_seq, created_at) VALUES (?,?,?,?,?,?,?)"
        ).use {
            it.setString(1, planId); it.setString(2, axiomFqn); it.setString(3, status)
            it.setString(4, reviewer); it.setString(5, note); it.setLong(6, evt.seq); it.setLong(7, now)
            it.executeUpdate()
        }
        ReviewRow(conn.createStatement().executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) },
            planId, axiomFqn, status, reviewer, note, evt.seq, now)
    }

    fun listReviews(planId: String): List<ReviewRow> {
        getPlanByPlanId(planId)
        return tx {
            conn.prepareStatement("SELECT id, plan_id, axiom_fqn, status, reviewer, note, event_seq, created_at FROM axiom_review WHERE plan_id = ? ORDER BY id").use {
                it.setString(1, planId)
                it.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) rs else null }.map {
                        ReviewRow(it.getLong(1), it.getString(2), it.getString(3), it.getString(4),
                            it.getString(5), it.getString(6), it.getLong(7), it.getLong(8))
                    }.toList()
                }
            }
        }
    }

    // ---------- proof-dependency slices ----------

    /**
     * Narrow the dependency slice of a failed lemma: the full backward dependency cone
     * intersected with replay items. Stored as an immutable decision event.
     */
    fun narrowSlice(planId: String, lemmaFqn: String, note: String): SliceRow = tx {
        val plan = getPlanByPlanId(planId)
        if (lemmaFqn !in plan.items.map { it.fqn }.toSet())
            throw HttpError(404, "lemma '$lemmaFqn' is not part of plan '$planId'")
        val failures = failedLemmas(plan.id)
        if (lemmaFqn !in failures)
            throw HttpError(409, "slice narrowing is only allowed on failed lemmas; failed in plan: ${failures.ifEmpty { listOf("<none>") }}")
        val newSnap = getSnapshot(plan.newSnapshotId)
        val graph = Graph(newSnap.symbols)
        val cone = LinkedHashSet<String>()
        val q = ArrayDeque<String>()
        q.addLast(lemmaFqn); cone.add(lemmaFqn)
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            graph.byFqn[cur]?.directDeps?.forEach { d -> if (cone.add(d)) q.addLast(d) }
        }
        val inPlan = cone.filter { fqn -> plan.items.any { it.fqn == fqn } }
            .sortedWith(compareBy({ graph.byFqn.getValue(it).ord }, { it }))
        val frontier = inPlan.filter { fqn ->
            graph.byFqn.getValue(fqn).directDeps.none { it in inPlan && it != fqn } ||
                graph.byFqn.getValue(fqn).kind != NodeKind.LEMMA
        }.distinct().sorted()
        val evidence = linkedMapOf(
            "planId" to planId, "planVersion" to plan.planVersion,
            "lemmaFqn" to lemmaFqn, "note" to note,
            "fullCone" to cone.sortedWith(compareBy({ graph.byFqn[it]?.ord ?: Int.MAX_VALUE }, { it })),
            "sliceInPlan" to inPlan, "frontier" to frontier,
            "sliceFingerprint" to Hashing.sha256Hex(inPlan.joinToString(","))
        )
        val evt = appendEvent("PROOF_SLICE_NARROWED", evidence)
        val now = System.currentTimeMillis()
        conn.prepareStatement(
            "INSERT INTO proof_slice(plan_id, lemma_fqn, slice_fqns_json, frontier_json, evidence_json, event_seq, created_at) VALUES (?,?,?,?,?,?,?)"
        ).use {
            it.setString(1, planId); it.setString(2, lemmaFqn)
            it.setString(3, Json.write(inPlan)); it.setString(4, Json.write(frontier))
            it.setString(5, Json.write(evidence)); it.setLong(6, evt.seq); it.setLong(7, now)
            it.executeUpdate()
        }
        SliceRow(conn.createStatement().executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) },
            planId, lemmaFqn, inPlan, frontier, Json.write(evidence), evt.seq, now)
    }

    fun listSlices(planId: String): List<SliceRow> {
        getPlanByPlanId(planId)
        return tx {
            conn.prepareStatement("SELECT id, plan_id, lemma_fqn, slice_fqns_json, frontier_json, evidence_json, event_seq, created_at FROM proof_slice WHERE plan_id = ? ORDER BY id").use {
                it.setString(1, planId)
                it.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) rs else null }.map {
                        @Suppress("UNCHECKED_CAST")
                        SliceRow(it.getLong(1), it.getString(2), it.getString(3),
                            (Json.parse(it.getString(4)) as List<Any?>).map { x -> x as String },
                            (Json.parse(it.getString(5)) as List<Any?>).map { x -> x as String },
                            it.getString(6), it.getLong(7), it.getLong(8))
                    }.toList()
                }
            }
        }
    }

    private fun failedLemmas(planRowId: Long): Set<String> {
        val plan = getPlan(planRowId)
        val out = HashSet<String>()
        conn.prepareStatement("SELECT evidence_json FROM batch_result WHERE plan_row_id = ?").use { st ->
            st.setLong(1, planRowId)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    @Suppress("UNCHECKED_CAST")
                    val ev = Json.parse(rs.getString(1)) as Map<String, Any?>
                    collectFailures(ev, out)
                }
            }
        }
        // Failed (unpublished) batches persist only as immutable REPLAY_FAILED events.
        conn.createStatement().executeQuery(
            "SELECT payload_json FROM event WHERE event_type IN ('REPLAY_FAILED','REPLAY_PUBLISHED') ORDER BY seq"
        ).use { rs ->
            while (rs.next()) {
                @Suppress("UNCHECKED_CAST")
                val payload = Json.parse(rs.getString(1)) as Map<String, Any?>
                if (payload["planId"] == plan.planId) {
                    @Suppress("UNCHECKED_CAST")
                    val ev = (payload["evidence"] as? Map<String, Any?>)
                    if (ev != null) collectFailures(ev, out)
                }
            }
        }
        return out
    }

    private fun collectFailures(evidence: Map<String, Any?>, out: MutableSet<String>) {
        @Suppress("UNCHECKED_CAST")
        (evidence["results"] as? List<Any?>)?.forEach { r ->
            @Suppress("UNCHECKED_CAST")
            val m = r as Map<String, Any?>
            if (m["status"] == "FAILED") out += m["fqn"] as String
        }
    }
}
