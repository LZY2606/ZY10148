package app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 依赖图中的节点类型。公理与定义无需重放；只有引理会进入重放计划。 */
enum class NodeKind { AXIOM, DEFINITION, LEMMA }

/** 证明器导出的单个符号：定义 / 公理 / 引理，携带库版本与内容指纹。 */
@Serializable
data class Symbol(
    val id: String,
    val kind: NodeKind,
    val library: String,
    val version: String,
    val fingerprint: String? = null,
    val content: String,
    val doc: String = "",
    val dependencies: List<String> = emptyList(),
    val external: Boolean = false,
    val recursive: Boolean = false,
)

/** 一份精简事件流快照。snapshotFingerprint 可省略，由服务按内容重算。 */
@Serializable
data class Snapshot(
    val library: String,
    val version: String,
    val snapshotFingerprint: String? = null,
    val symbols: List<Symbol>,
)

/** 已入库快照（指纹规范化后）。 */
@Serializable
data class SnapshotRecord(
    val library: String,
    val version: String,
    val snapshotFingerprint: String,
    val importedAt: String,
    val symbols: List<Symbol>,
)

/** 进入最小重放计划的原因。 */
enum class EntryReason {
    /** 定理本身的内容指纹发生变化。 */
    CONTENT,

    /** 指纹未变，但依赖集合或所依赖公理/定义变化。 */
    DEPENDENCY,

    /** 新快照中的定理在旧快照中没有对应证明（新增或仅能由 mapping 匹配）。 */
    MISSING_PROOF,
}

/** 重放计划中的一个定理条目。 */
@Serializable
data class PlanEntry(
    val symbolId: String,
    val layer: Int,
    val reason: EntryReason,
    val oldFingerprint: String? = null,
    val newFingerprint: String,
    /** 该定理本次重放所依据的证明依赖切片；默认是完整直接依赖。 */
    val sliceDependencies: List<String>,
    val narrowed: Boolean = false,
)

/** 计划的一个不可变版本（创建或切片缩小都会产生新版本）。 */
@Serializable
data class PlanVersion(
    val planId: String,
    val version: Int,
    val parentVersion: Int? = null,
    val library: String,
    val fromSnapshotFingerprint: String,
    val toSnapshotFingerprint: String,
    val renameMapping: Map<String, String>,
    val entries: List<PlanEntry>,
    /** 传播种子：内容/依赖改变或新增的符号。 */
    val changedSymbols: List<String>,
    /** 受影响边界（changedSymbols 中直接点燃传播的部分，用于界面标注）。 */
    val frontier: List<String>,
    /** 旧快照中消失且无 mapping 的符号（不进入重放，仅留档）。 */
    val deletedSymbols: List<String>,
    val frozen: Boolean = false,
    val createdAt: String,
    val createdByEventSeq: Long = 0,
)

/** 计划聚合，包含全部历史版本。 */
@Serializable
data class PlanRecord(
    val planId: String,
    val library: String,
    val currentVersion: Int,
    val versions: List<PlanVersion>,
)

/** 重放时保存的确切证据。 */
@Serializable
data class Evidence(
    val prover: String,
    val certificate: String,
    val checksum: String,
    val checkedAt: String,
    val detail: String = "",
)

@Serializable
data class TheoremResult(
    val symbolId: String,
    val status: TheoremStatus,
    val evidence: Evidence,
    /** 本次重放实际使用的证明依赖，必须与计划声明的切片精确一致。 */
    val usedDependencies: List<String>,
)

enum class TheoremStatus { PROVED, FAILED }

/** 一个重放批次（按拓扑层提交）。published=false 表示校验失败、整体未发布。 */
@Serializable
data class BatchRecord(
    val batchId: String,
    val planId: String,
    val planVersion: Int,
    val layer: Int,
    val symbolIds: List<String>,
    val published: Boolean,
    val results: List<TheoremResult>,
    val submittedAt: String,
    val eventSeq: Long = 0,
    /** published=false 时的拒绝原因，仅用于失败响应，不会写入事件日志。 */
    val rejectionReason: String? = null,
)

/** 外部公理审核状态。 */
enum class AuditStatus { PENDING, APPROVED, REJECTED }

@Serializable
data class AuditRecord(
    val snapshotFingerprint: String,
    val symbolId: String,
    val status: AuditStatus,
    val reviewer: String,
    val note: String = "",
    val at: String,
    val eventSeq: Long = 0,
)

/** 不可覆盖的事件记录。payload 按 type 解析为上述 DTO。 */
@Serializable
data class StoredEvent(
    val seq: Long,
    val type: String,
    val at: String,
    val payload: JsonObject,
)

// ---- 请求体 ----

@Serializable
data class LoadFixtureRequest(val resource: String)

@Serializable
data class PlanRequest(
    val library: String,
    val fromSnapshot: String,
    val toSnapshot: String,
    /** 显式改名映射 oldId -> newId；同文本/编辑距离永远不作为依据。 */
    val renameMapping: Map<String, String> = emptyMap(),
)

@Serializable
data class FreezeRequest(val planVersion: Int? = null, val note: String = "")

@Serializable
data class NarrowRequest(
    val planVersion: Int,
    val symbolId: String,
    val sliceDependencies: List<String>,
)

@Serializable
data class BatchSubmitRequest(
    val planVersion: Int,
    val layer: Int,
    val results: List<TheoremResult>,
)

@Serializable
data class AuditRequest(
    val snapshotFingerprint: String,
    val symbolId: String,
    val status: AuditStatus,
    val reviewer: String,
    val note: String = "",
)

// ---- 图视图 ----

@Serializable
data class GraphNode(
    val id: String,
    val kind: NodeKind,
    val external: Boolean,
    val recursive: Boolean,
    val scc: Int,
    val depth: Int,
)

@Serializable
data class GraphEdge(val from: String, val to: String)

@Serializable
data class SccInfo(
    val index: Int,
    val ids: List<String>,
    val legal: Boolean,
    val reason: String,
)

@Serializable
data class GraphView(
    val library: String,
    val version: String,
    val snapshotFingerprint: String,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val sccs: List<SccInfo>,
)

/** 快照校验失败 / 非法环等结构化错误。 */
class ApiException(val status: Int, message: String) : RuntimeException(message)

data class CycleViolation(val scc: List<String>, val reason: String)
