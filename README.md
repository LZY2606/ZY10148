# Proof Replay · 证明库快照比较与最小重放

形式化方法团队从外部证明器导出**精简事件流**（定义 / 公理 / 引理、直接依赖、并列
目标与检查结果），每个符号携带**库版本**与**内容指纹**。本服务在**不运行外部
证明器**的前提下比较两份库快照，计算公理 / 定义 / 依赖改变后**必须重放的最小定理
集**，并把重放使用的**确切证据**以不可覆盖事件保存到 SQLite。

- Kotlin + JDK 内置 HTTP Server + SQLite（`sqlite-jdbc`），零前端构建步骤
- 事件溯源：SQLite 只追加 `events` 表，所有读模型在启动时重放
- 网页展示：依赖图、强连通组件（SCC）、受影响边界、拓扑重放批次
- 重命名**只**通过两份快照间的显式 mapping 传递；同文本/编辑距离不是证据
- 重放结果只能提交到它所声明的计划版本；批次中途失败则整体未发布

## 构建与演示

```bash
mvn -q -DskipTests package
mvn -q test && mvn -q exec:java -Dexec.mainClass=app.MainKt -Dexec.args='--port 5348'
```

打开 <http://127.0.0.1:5348>。可选参数：`--db <sqlite 路径>`（默认
`proof-replay.db`）。启动时自动导入 `fixtures/` 下的两个合法快照；非法环 fixture
只用于演示拒绝，不会入库。

## Fixture

固定的小型证明库 `tiny-arith`：

| 文件 | 内容 |
| --- | --- |
| `fixtures/arith-v1.json` | 基线：nat/add/even/odd、peano 归纳、外部经典公理、5 个引理 |
| `fixtures/arith-v2.json` | `add` 定义变更、`peano_induction` 公理加强、`add_comm` 证明变更、新增 `add_assoc`、`add_zero→add_zero_rw` 改名、`succ` 仅文档改写 |
| `fixtures/bad-lemma-cycle.json` | 两个引理互相引用形成未声明环，导入必须被拒绝（422） |

v1→v2 的最小重放集（显式提供 `{"add_zero":"add_zero_rw"}` mapping 时）：

- `add_assoc` — **MISSING_PROOF**（v1 无此定理）
- `add_comm` — **CONTENT**（定理指纹 `3333…331 → 3333…332`）
- `even_or_odd` — **DEPENDENCY**（自身指纹未变，但它依赖的 `peano_induction` 变了）
- `add_succ`、`double_even`、`add_zero_rw` — **DEPENDENCY**（传递依赖到变更的 `add` 定义）
- `succ` 仅文档变化 → 不传播；`add_zero_rw` 改名本身不触发重放

## 节点类型与环合法性

| 类型 | 含义 | 是否进入重放 |
| --- | --- | --- |
| `AXIOM` | 公理；`external=true` 表示外部公理，需要人工审核 | 否（作为变化种子传播） |
| `DEFINITION` | 定义；`recursive=true` 声明递归 | 否（作为变化种子传播） |
| `LEMMA` | 带证明的定理 | 是（只有引理会被重放） |

依赖边方向为 `符号 → 它直接依赖的符号`。用 Tarjan 算法求强连通组件：

1. **合法环**：SCC 内**全部**是 `recursive=true` 的 `DEFINITION`（如 fixture 中
   `even ↔ odd` 互递归）——递归定义允许成环。
2. **非法环（拒绝导入，HTTP 422）**：SCC 中出现任何 `LEMMA`/`AXIOM`，或出现
   未声明 `recursive` 的 `DEFINITION`（包括自环）。定理证明之间的未声明环
   不能因为“图里有环”而与递归定义混为一谈。

`GET /api/snapshots/{fp}/graph` 返回节点、边和每个 SCC 的 `legal/reason`，
网页对合法（绿色 ✔）与非法（红色 ✘）分量分别标注。

## 影响传播

匹配两份快照时严格遵守：

- 显式 `renameMapping`（oldId → newId）优先；否则按相同 id 匹配；
- **绝不**用相同文本或编辑距离猜测改名（fixture 中改名后引理内容相同，
  不给 mapping 时会被当作新增 + 删除）；
- 内容指纹变化（`fingerprint`，未提供时对 `kind|content|依赖集` 取 SHA-256）
  或依赖集合变化 → 成为**变化种子**；
- 仅 `doc` 不同（文档元数据）→ 不传播、不进入影响集。

在**新快照**的图上从所有变化种子沿反向边（“谁依赖了它”）做闭包，得到
受影响集合。公理和定义不是可重放证明，只负责点燃传播；边界（frontier）
即变化种子本身，网页以粗框高亮，其余受影响节点提亮，传播边高亮。

## 最小性

最小重放集 = 受影响集合 ∩ 引理。进入原因三选一：

- **MISSING_PROOF**：新引理在旧快照中无对应符号（无旧证明可复用）；
- **CONTENT**：引理自身内容指纹改变；
- **DEPENDENCY**：指纹未变，但依赖集变化或（传递）依赖的公理/定义变化。

不在集合中的引理同时满足“指纹未变”且“全部传递依赖未变”，因此其旧证明
仍然成立——这就是最小性依据。重放计划在引理诱导子图上做 Kahn 拓扑分层
（无依赖的引理在第 0 层），**同层按符号 id 稳定升序**，批次按层提交。

## 重放、切片缩小与冻结

- `POST /api/plans/{id}/batches` 一次提交**整个拓扑层**；校验覆盖集合、
  已发布冲突、证据完整性，以及 `usedDependencies` 必须与计划声明的
  `sliceDependencies` **精确一致**。任何一项失败都不写事件，整批保持未发布。
- 重放结果只能提交到它**声明的 `planVersion`**；对不存在版本提交返回 404，
  重复发布同一层返回 409。
- 在失败定理上可 `POST /api/plans/{id}/narrow` 缩小证明依赖切片
  （必须是当前切片子集），生成新的不可变计划版本（父版本保留）。
- `POST /api/plans/{id}/freeze` 冻结某个版本；冻结后不可再对其缩小切片，
  重复冻结返回 409。
- 外部公理审核 `POST /api/audits`（仅 `external=true` 的公理可审核），
  APPROVED / REJECTED 都作为事件留档。

所有决策（导入、建计划、冻结、切片缩小、批次、审核）都是 `events` 表中的
**只追加**事件，seq 单调、永不更新或删除；重启服务后状态从日志完整重建。

## HTTP API 摘要

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/snapshots` | 导入快照 JSON（按库 + 快照内容指纹幂等，已存在返回 200） |
| `POST /api/snapshots/load-fixture` | 导入内置 fixture（`arith-v1.json` 等） |
| `GET  /api/snapshots[?library=]` | 列出快照 |
| `GET  /api/snapshots/{fp}` | 快照详情 |
| `GET  /api/snapshots/{fp}/graph` | 依赖图、分层、SCC 与环合法性 |
| `GET  /api/snapshots/{fp}/external-axioms` | 外部公理及最新审核状态 |
| `POST /api/plans` | 创建计划：`{library,fromSnapshot,toSnapshot,renameMapping}` |
| `GET  /api/plans` / `GET /api/plans/{id}?version=n` | 计划列表 / 版本状态（含每条定理状态） |
| `POST /api/plans/{id}/freeze` | 冻结版本 |
| `POST /api/plans/{id}/narrow` | 失败定理上缩小证明依赖切片 → 新版本 |
| `POST /api/plans/{id}/batches` | 按层提交重放结果与证据（原子发布） |
| `GET  /api/plans/{id}/batches` | 已发布批次与证据 |
| `POST /api/audits` / `GET /api/audits` | 外部公理审核（不可覆盖） |
| `GET  /api/events` | 全部事件日志（按 seq） |

## 测试

```bash
mvn -q test
```

- `GraphTest`（7）：Tarjan SCC、互递归定义合法、引理环/混合环/公理环拒绝、自环规则、拓扑层稳定排序
- `PlannerTest`（10）：公理变化触发 DEPENDENCY、仅文档不传播、改名 mapping 语义、同文本非改名、MISSING_PROOF / CONTENT 分类、传递传播、未知依赖与非法环拒绝
- `ApiAcceptanceTest`（1 个端到端工作流）：幂等导入、422 拒绝非法环、mapping 比较、
  批次原子性（错误版本 / 覆盖不全 / 错误切片 / 重复发布）、失败后切片生成新版本、
  冻结语义、外部公理审核边界、事件只追加与重启重建
