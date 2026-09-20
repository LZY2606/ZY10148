# Prover Replay — 证明库双快照影响分析与最小重放

形式化方法团队从证明器导出的精简事件流（定义、公理、引理、直接依赖、并列目标、检查结果，
每个符号带库版本与内容指纹）。本服务在**不运行任何外部证明器**的前提下：

- 导入并校验两份库快照（按 `库 + 快照内容指纹` 幂等）；
- 构建依赖图并用 Tarjan 求强连通分量（SCC），区分**合法递归定义环**与**非法证明环**；
- 比较两份快照，沿逆向依赖传播影响，给出**必须重放的最小定理集**；
- 以拓扑有序、同层稳定排序的批次组织重放，绑定计划版本，保存每次重放的确切证据；
- 冻结计划、外部公理审核、失败引理依赖切片等决策全部写入**追加式、哈希链、不可覆盖**事件表；
- 提供依赖图 / SCC / 影响边界 / 批次页面。

## 安装与演示

```bash
mvn -q -DskipTests package
mvn -q test && mvn -q exec:java -Dexec.mainClass=app.MainKt -Dexec.args='--port 5348'
```

打开 <http://127.0.0.1:5348>。首次启动会自动导入 classpath 上的固定 fixture
（`mini-arith` v1/v2）并生成计划；页面左上角也可以重新触发或手工导入 JSON。

其他参数：`--db <path>`（默认 `data/prover.db`，SQLite）、`--no-bootstrap`。

## 快照事件模型

每个符号包含：

| 字段 | 含义 |
| --- | --- |
| `fqn` | 全限定名，快照内唯一 |
| `kind` | `AXIOM` / `DEFINITION` / `LEMMA` |
| `contentFingerprint` | 证明相关内容指纹（指纹相同即内容相同） |
| `docFingerprint` | 文档指纹，**不影响**快照指纹与影响集 |
| `directDeps` | 直接依赖（图的边 `symbol -> dep`） |
| `parallelGoals` | 并列目标，可同层处理（图中紫虚线，不产生先后约束） |
| `checkStatus` | 证明器给出的检查结果 |
| `proofRef` | 旧证明证据引用；为 `null` 表示该版本没有证明证据 |
| `external` | 是否外部公理（外部公理才有审核状态） |

`ord` 可缺省，取数组下标，用于同层稳定排序。依赖指向不存在的符号会被拒绝（400）。

**快照指纹**只对规范化后的证明相关字段（fqn、kind、contentFingerprint、proofRef、
checkStatus、external、directDeps、parallelGoals）求 SHA-256；`docFingerprint` 与
顺序不参与。因此只改文档时快照指纹不变，重复导入幂等命中，也不会扩大影响集。

## 节点类型与环的合法性

- `DEFINITION` 之间允许环：递归定义（如 fixture 中 `def_even <-> def_odd`）是合法 SCC。
- 任何 SCC 只要包含 `AXIOM`/`LEMMA` 且形成环，就判定为**非法证明环**，导入被拒绝
  （HTTP 422），同时保留一条 `SNAPSHOT_REJECTED` 事件（事件在独立事务中提交，
  不会随导入回滚而消失）。
- 判定不做“图里有环就拒绝”的一刀切：先算 SCC，再看环上节点的种类。
  `illegal-cycle.json` fixture 给出 `lemma_a -> lemma_b -> lemma_a` 的拒绝样例。

## 快照比较与身份

- 默认身份是 fqn 相等。
- **改名只有显式 `mapping`（old -> new）才传递**；mapping 必须同时指向两份快照里
  存在的符号，否则 400。同文本、相同指纹、编辑距离都不构成改名证据：没有 mapping 时，
  新名字一律视为新增符号（进入原因 `MISSING_OLD_PROOF`）。
- mapping 下的依赖比较先把旧依赖重映射到新 fqn 再判定边是否改变。

## 影响传播（种子 → 最小重放集）

1. **变化种子**：新增 / 删除 / 修改的符号。修改涵盖内容指纹、kind、external、
   proofRef、checkStatus、直接依赖边、并列目标；`docFingerprint` 变化单独记录为
   `docOnly`，不是种子。
2. **传播**：在新快照依赖图上从种子做逆向可达（BFS），取所有可达的 `LEMMA`。
   公理/定义本身不重放，但可以是触发源。
3. **进入原因**（互斥，优先级如下）：
   - `MISSING_OLD_PROOF`：旧快照中无对应符号或对应符号 `proofRef == null`
     （旧证明缺少，必须重新取得证据）；
   - `CONTENT_CHANGED`：引理自身内容指纹改变；
   - `DEPENDENCY_CHANGED`：内容指纹未变，但位于变化种子的逆向可达锥内
     （公理改了，定理即使没动也必须重放）。
4. 每个条目带 `triggeredBy`（直接变化输入 / 边变化 / 最近变化祖先）作为传播证据。

### 最小性

返回集合 S 恰好是“变化种子 ∪ 从种子逆向可达的引理”：

- **可靠性**：S 中每个引理要么自身变化/缺少旧证明，要么有一条通向某变化种子的依赖
  路径，旧证明的前提已失效，不重放就不能接受其旧证据。
- **必要性（无冗余）**：对任何被包含的引理去掉它，若它自身是种子则漏掉真实变化；
  否则它与种子间存在依赖路径，旧证明依赖的输入指纹可能已变，漏掉即风险。
- **无多余**：锥外引理的完整（传递）依赖闭包中没有任何变化种子，其旧证明输入全部
  未变，重放不产生新信息（fixture 中的 `lemma_stable`、`lemma_doc_only` 即被排除）。

`docOnly` 里的符号只改文档指纹，既不是种子也不传播。

## 重放计划与批次

- 计划由 `(oldSnapshot, newSnapshot, mapping)` 确定，`planId` 为确定性哈希，重复请求
  复用同一计划；输入变化时产生新的单调递增 `planVersion`。
- 层 = 新图中仅保留重放子图后的最长依赖路径深度（Kahn 式分层）；**层即批次**，
  层内无依赖、可并列（并列目标优先同层展示）。
- 同层按 `(ord, fqn)` 稳定排序，保证计划与证据逐字节可复现。
- `boundary` 给出影响边界（仍有锥外引理依赖它的最外层受影响引理）。

重放是**确定性证据校验**，不调用证明器：每个引理的判定由计划指纹、引理内容指纹、
依赖集、proofRef、进入原因哈希出 `evidenceToken`；`forceFailures` 用于 fixture/验收
注入失败。规则：

- 重放结果**只能提交到它声明的 `planVersion`**，版本不符 409；
- 必须按批顺序提交（前一批未发布不能提交后一批，409）；
- **批次原子**：批内有任何失败，整批不写入 `batch_result`（保持未发布），只追加
  `REPLAY_FAILED` 事件及完整证据；可修正后重试；
- 成功批次在同一事务写入 `batch_result` 并追加 `REPLAY_PUBLISHED`，证据包含
  planId/planVersion/planFingerprint/批次/每条结果/时间；
- 已发布批次重放提交为幂等返回，不会覆盖既有证据；
- 计划冻结后拒绝一切重放发布（409）。

## 不可覆盖决策（追加事件）

所有决策写入 `event(seq, type, payload, prev_hash, hash, created_at)`，
`hash = SHA256(prevHash|type|payload|createdAt)`，`/api/events/verify` 校验整条链。
代码层没有 update/delete 决策的路径：

- `PLAN_FROZEN`：冻结计划（重复冻结记 `PLAN_FROZEN_REDUNDANT_REJECTED`）；
- `EXTERNAL_AXIOM_REVIEWED`：外部公理审核（`UNREVIEWED/CLAIMED/APPROVED/REJECTED`），
  每次审核追加一行，历史状态全部保留；仅 `external=true` 的 `AXIOM` 可审核；
- `PROOF_SLICE_NARROWED`：只能对**已失败引理**缩小证明依赖切片（后向依赖锥 ∩ 计划内
  条目），切片指纹与前沿一并存证。

## HTTP API

| 方法 路径 | 说明 |
| --- | --- |
| `GET /` | 依赖图/SCC/边界/批次页面 |
| `POST /api/snapshots/import` | 导入快照（幂等；非法环 422） |
| `GET /api/snapshots[/{id}]` | 快照列表 / 图 + SCC 详情 |
| `POST /api/plans` | `{oldSnapshotId,newSnapshotId,mapping}` 生成或复用计划 |
| `GET /api/plans[/{planId}]` | 计划列表 / 明细（layers、items、boundary…） |
| `POST /api/plans/{id}/freeze` | 冻结，body 带 `planVersion` 校验 |
| `POST /api/plans/{id}/replay` | 提交 `{planVersion,batch,forceFailures,recordedBy}` |
| `GET /api/plans/{id}/batches` | 已发布批次与证据 |
| `POST/GET /api/plans/{id}/reviews` | 外部公理审核记录 / 列表 |
| `POST/GET /api/plans/{id}/slices` | 失败引理切片 / 列表 |
| `GET /api/events` / `POST /api/events/verify` | 事件日志 / 哈希链校验 |
| `POST /api/demo/bootstrap` | 空库时导入 fixture 并建计划 |

## 固定 fixture（`src/main/resources/fixtures/`）

- `mini-arith-v1.json`：3 公理（含 1 外部公理）+ 3 定义（`even/odd` 合法递归环）
  + 多个引理；
- `mini-arith-v2.json`：同一库新版本——`ax_induct` 内容变化、`lemma_zero_nat`
  内容变化、`lemma_odd_one` 变化、`lemma_add_comm` 改名为 `lemma_sum_comm`（指纹相同，
  需 mapping）、新增无旧证明的 `lemma_new_dawn`、仅文档变化的 `def_odd`/`lemma_doc_only`；
- `illegal-cycle.json`：引理互依环，必须被拒绝。

## 自动化验收

`mvn -q test` 运行 15 个 JUnit 5 用例（存储层 + HTTP 端到端），覆盖：

- 导入幂等（含仅文档改动仍幂等）；合法递归 SCC 放行、非法证明环 422 且事件留存；
- 三种进入原因的区分；指纹未变但公理改变仍重放；doc-only/锥外引理被排除；
- 无 mapping 不传递改名；拓扑序与同层稳定排序；
- 版本绑定、批次顺序、失败整批不发布、成功证据含计划指纹与版本、重复提交幂等；
- 冻结锁定；外部公理审核追加且不覆盖；切片仅限失败引理且在依赖锥内；哈希链完整。

## 实现说明

- Kotlin + JDK 内置 `com.sun.net.httpserver.HttpServer`，SQLite 经 `sqlite-jdbc`；
  无外部证明器、无 Web 框架、无 JSON 库（内置最小 JSON 解析/写入）。
- 单写线程执行器 + 每方法事务；拒绝/失败事件在独立事务提交，保证“失败也留证”。
