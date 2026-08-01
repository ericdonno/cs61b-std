# Phase 2 Spec：确定性事件桥接与双速执行骨架

## 0. 元数据

- **Phase**：2
- **状态**：Draft — 根据新 AI Tick 架构重写，等待 Builder 审批
- **作者**：Codex（持续 Advisor）
- **创建日期**：2026-07-22
- **重写日期**：2026-07-23
- **基线分支**：`ai-enemis`
- **基线 commit**：`4c1bc98`
- **前一阶段 Completion**：`PHASE_1_COMPLETION.md`
- **非阻塞增强**：`PHASE_1DOT5_COMPLETION.md`（FOV debug overlay，不是 Phase 2 gate）
- **上位文档**：
  - `PROJECT_INTENT_zh-CN.md`
  - `DEVELOPMENT_ROADMAP.md`
  - `AI_TICK_ARCHITECTURE.md`
- **替代内容**：完整替代 2026-07-22 版 `PythonBridgeBrain + AgentMailbox + request/response` 方案

本 Spec 尚未获批。审批前不得开始通信、Enemy 主循环或 ActionQueue 重构。

---

## 1. 必读输入与审计范围

### 1.1 已完整读取的基准文档

- `PROJECT_INTENT_zh-CN.md`
  - §3：每个 Enemy 是独立 Agent，不共享意识或隐藏知识。
  - §6：Agent MVP 必须包含结构化输出、反馈、异步运行和 tracing。
  - §7：LLM 负责高层意图，确定性规划器/技能和 ActionQueue 负责执行。
- `DEVELOPMENT_ROADMAP.md`
  - §3：INV-01 至 INV-09。
  - 阶段 2：双速执行、AgentSession、有界队列、单 in-flight、deadline、Lease/Arbiter、commit barrier。
  - 阶段 3–5：Phase 2 必须留下但不能提前实现的 intent/skill/plan/通信扩展 seam。
- `AI_TICK_ARCHITECTURE.md`
  - §2：模型延迟、双速大脑、P0–P4 仲裁和请求生命周期。
  - §3–6：双向 NDJSON、AgentSession、消息信封和最小 schema。
  - §12：Phase 2 必须闭合的状态转换、背压、Session 生命周期和语义过时问题。
- `PHASE_1_COMPLETION.md`
- `PHASE_1DOT5_COMPLETION.md`
- `PHASE_SPEC_TEMPLATE.md`
- 被本文替代的旧版 `PHASE_2_SPEC.md`

### 1.2 已审计的当前代码

| 文件 | 审计重点 |
|------|----------|
| `byog/Core/Game.java` | 游戏主循环、楼层切换、Enemy 创建、存读档 |
| `byog/Entity/Enemy.java` | 同步 think/plan/act、动作冷却、MAX_RETRY、身份和生产感知路径 |
| `byog/Entity/EntityManager.java` | 空间索引、frameOccupied、flush/remove 提交边界 |
| `byog/Perception/PerceptionSystem.java` | 私有 FOV 与实体摘要生成 |
| `byog/Perception/ObservationEnvelope.java` | 不可变 observation、身份字段和 snapshot walkability |
| `byog/Perception/VisibleEntity.java` | 可见实体数据边界 |
| `byog/Perception/HeardEvent.java` | Phase 1 仅定义、尚未产生的听觉结构 |
| `byog/AI/EnemyBrain.java` | 同步 Brain 接口 |
| `byog/AI/RuleBasedBrain.java` | 私有 observation 下的本地 fallback |
| `byog/AI/StrategicIntent.java` | 当前扁平 goal/strategy/target 模型 |
| `byog/AI/ClassicalPlanner.java` | intent → 完整 BFS path → actions |
| `byog/Action/Action.java` | 原子动作和 ActionResult |
| `byog/Action/ActionQueue.java` | FIFO、固定 low-water=2 |
| `byog/Trace/AgentTrace.java` | Phase 0/1 schema 隔离与 canonical serializer |
| `byog/IO/GameConfig.java` | properties 加载与默认生成 |
| `byog/IO/GameSaveData.java` | agentId 存档字段；不持久化运行时连接状态 |

### 1.3 已审计的测试与运行入口

- `byog/Test/EncounterHarness.java`
- `byog/Test/Phase0EncounterHarness.java`（历史兼容 adapter）
- `byog/Test/Phase0EncounterTest.java`
- `byog/Test/Phase1EncounterHarness.java`（历史兼容 adapter）
- `byog/Test/Phase1EncounterTest.java`
- `byog/Test/PerceptionSystemTest.java`
- `byog/Test/EnemyCollisionTest.java`
- `byog/Test/Phase2TestSuite.java`
- Phase 0/1 historical baseline artifacts

2026-07-26 的测试架构整理已用单一 `Phase2TestSuite` 验证 58 个不重复的 deterministic tests。
Phase 0/1 JSON baseline 继续作为历史证据保存，但不再是阻塞生产调度重构的 byte-for-byte gate。

### 1.4 当前工具链事实

- 当前 JDK/Javac：19.0.2。
- 当前 Python：3.14.6。
- Java classpath 只有 CS61B/JUnit 相关 jar；仓库中没有 Gson、Jackson、`org.json` 或其他 JSON parser。
- 仓库中没有 Python 源文件；fake runtime 为 greenfield。
- Phase 2 不新增第三方 Java/Python 依赖，因此没有易变化依赖需要在本阶段锁版。

### 1.5 规范性引用与有意不重复的内容

本 Spec 负责锁定 Phase 2 的可实施契约，不重复抄写 `AI_TICK_ARCHITECTURE.md` 的全部架构论证和
后续阶段设计。以下内容仍是规范性边界：

| 架构内容 | 规范性来源 | Phase 2 的处理 |
|----------|------------|----------------|
| Java 是世界权威和能力提供者；Agent 拥有自己的推理循环 | `AI_TICK_ARCHITECTURE.md` §0–1 | D2-01 锁定职责边界；fake Agent 的立即响应只是测试夹具，不代表 Java 拥有真实 Agent 的思考循环 |
| 双速大脑、等待模型、deadline、陈旧 intent 和反射覆盖 | `AI_TICK_ARCHITECTURE.md` §2.2–2.10 | §6–8 给出 Phase 2 最小实现；完整动机、覆盖时序和后续事件类型直接引用架构文档 |
| 完整双向事件流、Tool Calling 循环和 Agent 状态机 | `AI_TICK_ARCHITECTURE.md` §3.2–3.3、§5.3、§6 | Phase 2 只实现 §8 明列的消息；其余消息与状态保留扩展 seam，不进入本阶段 DoD |
| Enemy 作为身体、AgentSession 作为会话边界 | `AI_TICK_ARCHITECTURE.md` §4–5 | 本 Spec 的 §7–9 将其细化为接口、所有权和逐文件计划 |
| 复杂 skill、计划、全局预算、协作、规模和玩家可反制性缺口 | `AI_TICK_ARCHITECTURE.md` §12；`DEVELOPMENT_ROADMAP.md` 阶段 3–7 | 作为后续设计债务保留，不要求 Phase 2 提前实现 |

若架构文档给出概念性或面向未来的 schema 示例，Phase 2 的精确 wire contract、容量、状态转换和
验收条件以本 Spec §7–15 为准；这只是阶段细化，不得改变 Java 权威、私有知识边界或 Agent 独立性。

---

## 2. 阶段目标与成功定义

Phase 2 的目标不是“让 Python 决定 Enemy 下一帧做什么”，而是建立一条确定性、可验证的
**事件会话 + 双速执行**链路：

```text
已提交世界
  → Java 私有感知与快脑
  → ActionQueue 每个 action tick 至多执行一个原子动作
  → ActionOutcome
  → 有界 outbound queue
  → Python deterministic fake Agent
  → submit_intent
  → 有界 inbound queue
  → Java 校验、Lease、仲裁
  → 后续动作
```

Python fake Agent 不使用 LLM、不保存长期状态、不调用工具；它只证明以下基础设施成立：

1. 游戏线程从不等待 socket、Future、Python 或墙钟 deadline。
2. 等待 Python 时，Enemy 继续安全旧计划；无计划时由本地 Brain 接管。
3. 每个 Enemy 只有一个有效 in-flight 请求，新 observation 会合并而不是制造请求风暴。
4. 迟到、旧 Session、旧楼层、旧 generation 或语义过时的 intent 不会生效。
5. 玩家在模型等待期间进入视野，Java 快脑可在下一个 action tick 追击或攻击。
6. 每个 Action 都能关联回 decision、结果、反射覆盖和 fallback。
7. Python 停止读取、断线、返回坏消息或恢复时，游戏仍持续运行且行为可追踪。

Phase 2 完成后，Phase 3 可以在不重写 Game loop、AgentSession 或时效协议的前提下接入真实
Agent runtime、Tool Calling、可扩展 skill 和全局推理预算。

---

## 3. 起始事实

### 3.1 已确认事实

| ID | 当前事实 | 代码证据 | Phase 2 影响 |
|----|----------|----------|--------------|
| F2-01 | `Game.playWithKeyboard()` 每帧同步调用所有 `Enemy.updateAI()`，随后统一 flush/remove | `Game.java:108-123` | 必须改成五阶段 loop；网络不得进入该调用栈 |
| F2-02 | `Enemy.updateAI()` 在同一方法中完成感知、Brain、Planner 和最多四次 Action 尝试 | `Enemy.java:25-26, 78-201` | 不能只插入异步 Brain；必须拆分调度、执行与反馈 |
| F2-03 | 动作频率由 `tickCounter >= moveInterval` 控制 | `Enemy.java:33, 78-82` | 新 loop 必须保留等价 cooldown，不能变成每渲染帧移动 |
| F2-04 | 当前生产新 Enemy 已设置 `perceptionEnabled=true` | `Enemy.java:292-295` | 新游戏可依赖私有感知；旧 Completion 的“生产未启用”已过时 |
| F2-05 | 当前读档重建 Enemy 后没有重新启用 perception | `Game.java:702-768` | Phase 2 若保留读档入口，必须创建新的本地/bridge runtime；旧存档迁移不是 gate |
| F2-06 | 生产 `updateAI()` 构造 observation 时仍使用 `runId="unknown"`、`floorId=1`、无 trace 时 turn=0 | `Enemy.java:87-100` | Phase 2 必须让 Game 提供真实 run/floor/logicalTick |
| F2-07 | `ObservationEnvelope` 已不可变，持有可见/可行走 snapshot，不再保留 live world 引用 | `ObservationEnvelope.java:15-43, 120-172` | 旧 Spec 的“序列化 world 引用”描述错误；必须新增显式 `VisibleTile` snapshot |
| F2-08 | `PerceptionSystem` 能生成私有实体摘要，但未保存可见 tile 类型列表 | `PerceptionSystem.java:73-142` | bridge 不能重新读取隐藏 world；tile 类型必须在感知创建时固化 |
| F2-09 | `HeardEvent` 类型存在，但生产列表始终为空 | `PerceptionSystem.java:90, 137-142` | schema 保留数组；Phase 2 不实现听觉传播 |
| F2-10 | `StrategicIntent` 仍是 goal/strategy/target/confidence/targetRoom | `StrategicIntent.java:8-55` | 仅作为 Java 内部 v1 执行对象；跨进程 schema 使用类型化 skill proposal |
| F2-11 | `ClassicalPlanner.translate()` 返回完整 BFS path；不可达时生成随机占位移动 | `ClassicalPlanner.java:31-77` | ActionQueue 只接收有界前缀；远程目标先做可达性校验 |
| F2-12 | `ActionQueue.needRefill()` 固定为 size ≤ 2 | `ActionQueue.java:35-55` | low/high water 必须可配置，且队列只跨 tick 缓冲 |
| F2-13 | `EntityManager` 已有 frameOccupied 和统一 `flushPendingChanges()` | `EntityManager.java:16-97` | 可直接作为 world commit barrier，但 observation 必须在 barrier 后生成 |
| F2-14 | `AgentTrace` 已隔离 Phase 0/1 schema | `AgentTrace.java:28-47` | 新增 Phase 2 schema 时保持旧 schema 语义；不要求旧动作轨迹逐字不变 |
| F2-15 | 当前没有 bridge package、TCP、NDJSON codec 或 Python 文件 | 仓库搜索 | 所有通信代码均为新建；不得暗示已存在 |
| F2-16 | Java classpath 没有 JSON 库 | `../library-sp18/javalib` 审计 | 必须实现受限严格 codec 或另行审批依赖；禁止假设 `org.json` 可用 |
| F2-17 | 旧 Phase 0/1 Suite 嵌套会重复执行，两个 encounter harness 镜像实现同一 parser/loop | 2026-07-26 测试审计 | 使用一个 leaf-only `Phase2TestSuite`、共享 `EncounterHarness` 和生产 `AiTickLoop` |

### 3.2 从事实得出的设计推断

- `PythonBridgeBrain implements EnemyBrain` 会继续把远程 Agent 降格为同步函数，并导致每次思考时
  立即覆盖为 PATROL；该方案与 Roadmap 和 AI Tick 架构冲突，必须删除。
- `AgentMailbox` 只表示 request/response，无法承载 feedback、event、cancel、heartbeat 和 Phase 3
  tool call；应以持久 `AgentSession` 代替。
- 仅检查 `observationSeq` 不足以处理旧楼层、重连和 hard-timeout 结果；必须同时校验完整身份元组。
- 只限制单 Enemy in-flight 足以满足 Phase 2；整个遭遇的全局模型预算属于 Phase 3。

### 3.3 本阶段采纳的建议

- 先在 `bridgeEnabled=false` 下完成本地双速 loop，再接入 TCP。
- deadline 和重连测试使用注入式单调时钟，不用真实 sleep。
- Python runtime 由开发者或测试 harness 启动；Phase 2 不在 `Main` 中增加跨平台 ProcessBuilder。
- Java JSON 使用仓库内受限递归下降 codec，支持 object/array/string/number/boolean/null，拒绝重复键、
  超深嵌套、超大帧和非有限数字。

---

## 4. 需求追踪

| Requirement | Phase 2 如何满足 | 验收证据 |
|-------------|------------------|----------|
| INV-01 独立身份 | 每 Enemy 独立 AgentSession、sessionEpoch、messageSeq、requestGeneration 和队列 | P2-S15、P2-I02 |
| INV-02 有限知识 | observation 只序列化不可变私有 snapshot；target 必须来自合法可见/已知输入 | P2-P04、P2-A07 |
| INV-03 世界内通信 | 本阶段只定义 world_event 信封，不实现盟友信息传播 | Out of Scope 审查 |
| INV-04 Java 权威 | Python 只提交 skill proposal；Java Validator、Arbiter、Planner 和 Action 仍是唯一执行入口 | P2-A01、P2-A08 |
| INV-05 分层控制 | 远程慢脑与 Java ReflexController/RuleBasedBrain 并存；快脑每 action tick 运行 | P2-A03 至 P2-A06 |
| INV-06 严格契约 | NDJSON schema、完整身份元组、skill/参数/TTL/policy 校验、最大帧限制 | P2-P01 至 P2-P06、P2-A02 |
| INV-07 异步时效 | 独立 IO loop、有界队列、单 in-flight、软/硬 deadline、cancel/rebuild、迟到丢弃 | P2-S01 至 P2-S14、P2-I03 至 P2-I05 |
| INV-08 可追踪评估 | Phase 2 canonical 事件关联 request、decision、override、action、fallback；毫秒值仅诊断 | P2-T01、P2-T02 |
| INV-09 玩法价值 | 不做最终玩法验收；只验证等待模型时行为连续、可见追杀且可受 policy 限制 | P2-A03 至 P2-A06 |

---

## 5. 范围与非目标

### 5.1 In Scope

- 版本化 NDJSON 双向消息信封和严格 Java/Python codec。
- `VisibleTile` snapshot，使 bridge 不需要读取 live world。
- 每 Enemy 持久 `AgentSession`、独立 IO loop、连接/请求状态和生命周期。
- 有界 inbound/outbound/pendingEvents、消息优先级、合并和拒绝语义。
- 每 Enemy 单 in-flight、latest observation 合并、soft/hard deadline、cancel_request/cancel_ack。
- `DecisionValidator`、`IntentLease`、`IntentArbiter`、`InterruptPolicy`。
- `ReflexObservation` 和 `ReflexController` 的 Phase 2 最小 P0–P4 行为。
- `RuleBasedBrain` 作为冷启动、无计划、hard-timeout 和断线 fallback。
- `ActionQueue` 跨 action tick 缓冲，每个 action tick 至多执行一个 Action。
- 五阶段 Game loop 和 world commit barrier。
- 每个 Action 的 `ActionOutcome` 生成及 `action_feedback` 入队。
- deterministic Python fake Agent，多连接、可配置延迟/坏消息/断线/停止读取模式。
- Phase 2 trace schema、headless harness、协议/Session/AI tick/集成测试。
- 单一无重复 deterministic Agent gate；共享 fixture/parser/生产 tick coordinator。
- 真实生产 `runId / floorId / logicalTick` 和可注入 deterministic test identity。
- bridge feature flag 与配置默认值。

### 5.2 Out of Scope

- 真实 LLM、LangGraph/LangChain、Tool Calling、checkpoint、token 计量。
- 全局模型并发和成本调度器（Phase 3）。
- 完整 Skill Registry/Tactical Executor 或复杂战术技能库（Phase 3+）。
- 多步骤 TacticalPlan、planId/stepId 和条件分支（Phase 4）。
- Agent 实际消费 ActionOutcome 后重规划（Phase 4）；Phase 2 只完成传输闭环。
- 完整 world_event 触发体系、听觉传播、盟友通信和 RoleIntent（Phase 4–5）。
- 共享上下文、自动队伍黑板、全知 SquadCoordinator。
- 跨楼层长期记忆或保存 socket/request/lease/ActionQueue。
- Java 自动启动/终止 Python 进程。
- 旧存档格式迁移保证；Builder 已明确不把旧存档作为本阶段 gate。
- 重写 `Game.playWithInputString()` 或围绕该 legacy API 构建 Agent runtime。

---

## 6. 已锁定决定、假设与待决定项

### 6.1 已锁定决定

#### D2-01：远程 Agent 使用 AgentSession，不实现 EnemyBrain

`EnemyBrain` 只保留本地同步 Brain（Phase 2 为 `RuleBasedBrain`）。远程 Python Agent 通过
`byog.Bridge.AgentSession` 与 Enemy 交换事件，不新增 `PythonBridgeBrain`，也不在
`thinkFromObservation()` 中发送网络请求。

Java 决定何时发布受限 observation/event、如何限流和何时拒绝非法动作，但不拥有或模拟远程
Agent 内部的推理深度、工具循环和工作记忆。Phase 2 fake Agent 的确定性立即响应仅用于验证桥接，
不得成为 Phase 3 真实 Agent 必须“被 Java 调用后立刻回答”的接口先例。完整角色定义见
`AI_TICK_ARCHITECTURE.md` §0–1。

#### D2-02：每 Enemy 一条持久 localhost TCP + NDJSON 会话

- Java 是 TCP client；Python fake runtime 是 server。
- 每个 Enemy/agentId 一条持久连接。
- 每行一个 UTF-8 JSON object，以 `\n` 结束。
- 最大单帧 65,536 bytes；超限是 protocol failure。
- TCP 只负责传输，不代表共享上下文。

#### D2-03：游戏线程绝不执行阻塞 IO

只有 AgentSession 自己的 IO thread 可以调用 socket `connect/read/write`。游戏线程只能：

- 非阻塞 drain 已解析 inbound queue；
- 向有界 outbound queue 执行立即返回的 enqueue；
- 查询状态；
- 发出 close 请求。

禁止游戏线程等待 Future、线程 join、cancel ack 或网络重连。最终 shutdown 的有界 join 发生在退出
清理路径，不发生在 PLAYING tick 中。

#### D2-04：三组正交状态

```text
ConnectionState = DISABLED | CONNECTING | CONNECTED | DISCONNECTED
RequestState    = NO_REQUEST | AWAITING_INTENT | SOFT_TIMED_OUT | CANCEL_PENDING
ExecutionState  = NO_PLAN | EXECUTING | BLOCKED | EXHAUSTED
```

`closed` 是 AgentSession 的终止 flag，不与 ConnectionState 混用。
`CONNECTED + AWAITING_INTENT + EXECUTING` 是正常状态。

#### D2-05：每 Enemy 最多一个有效 in-flight

- 新 observation 在请求期间只替换 `latestObservation`。
- pendingEvents 按事件类型和关联实体合并。
- 普通 observation 不取消当前推理。
- 楼层切换、Enemy 死亡、显式取消或关键前提彻底失效才 supersede。
- supersede 先发送 `cancel_request`；未在 cancel grace 内收到 ack，则关闭并重建 Session。
- 新请求必须等旧 runtime 已确认取消或旧 Session 已被物理关闭，不能仅增加 observationSeq 后并发发送。

#### D2-06：soft/hard deadline 使用单调时钟

- `softDeadlineMs=1500`：进入 `SOFT_TIMED_OUT`，记录 `AGENT_SLOW`，不取消请求。
- `hardDeadlineMs=10000`：requestGeneration 递增，旧响应失效，进入 `CANCEL_PENDING`。
- deadline 使用注入的 `MonotonicClock.nanoTime()`；wall-clock 和毫秒耗时不进入 canonical 正确性。
- hard timeout 不自动销毁仍有效的旧 IntentLease；只有没有可执行计划时才本地接管。

#### D2-07：fallback 顺序固定

```text
安全且仍有效的旧计划
  → 当前私有感知上的 ReflexController
  → RuleBasedBrain 本地短期 intent
  → WAIT/PATROL 最终降级
```

不得因请求尚未返回而每个 action tick 清空当前 ActionQueue 或写入 PATROL。

#### D2-08：控制权按 P0–P4 仲裁

| 级别 | Phase 2 最小实现 |
|------|------------------|
| P0 | 死亡、越界、碰撞、非法 Action 永远由 Java 拒绝 |
| P1 | 当前私有感知中玩家相邻时，允许立即攻击/防御；不能被远程 policy 禁止 |
| P2 | 当前 FOV 看到玩家，且有效 lease 未明确禁止主动接战时，进入短期 REFLEX_ENGAGE |
| P3 | 执行有效 IntentLease 对应的有界 ActionQueue |
| P4 | 无有效 lease/计划时使用 RuleBasedBrain |

Phase 2 不实现受击、致命伤害等全部 P1 事件，但接口和 trace reason enum 必须可扩展。
Phase 2 的最小覆盖生命周期遵守 `AI_TICK_ARCHITECTURE.md` §2.10：开始覆盖时显式记录并暂挂或
标记旧 lease，覆盖动作只能读取当前私有感知且仍产生 ActionOutcome；当前依据消失、覆盖到期或新
lease 安全接管时显式结束，旧 lease 只有在前提仍成立时才能恢复。完整 reason 集合与更多触发事件
不在本阶段展开。

#### D2-09：ActionQueue 是跨 tick 缓冲

- 每个 action tick 最多 poll/execute 一个 Action。
- blocked Action 不在同 tick 内循环重试。
- 默认 low-water=2、high-water=5；Planner 返回更多动作时只装入前 5 个。
- 新 lease 安全接管时替换旧队列；反射短期覆盖不偷偷改写远程 intent。
- cooldown 与 observation/request 调度相互独立。

#### D2-10：Phase 2 跨进程 intent 是受限 skill proposal

远程 schema 不直接镜像 Java enum，也不允许原子动作。Phase 2 白名单：

```text
PATROL | CHASE | ATTACK | GUARD
```

proposal 包含 `intentVersion`、`skill`、受限 parameters、`confidence`、`validForTicks` 和
`interruptPolicy`。Validator 通过后再映射为当前 Java `StrategicIntent`，并包装为 IntentLease。

`AMBUSH / INTERCEPT / RETREAT` 等现有 Java 枚举不自动成为 Phase 2 远程能力；它们要等 Phase 3
Skill Registry 正式定义参数和失败语义。

#### D2-11：Java JSON codec 不新增依赖

新建受限严格 codec：

- 支持 JSON object/array/string/number/boolean/null；
- 最大嵌套 16；
- 拒绝重复 key、非法转义、非有限数字、尾随垃圾和超大帧；
- schema decoder 再执行字段白名单、必填、类型和范围校验；
- 不使用 regex 解析 JSON，不使用 JavaScript engine。

若实现者希望引入第三方 JSON 库，必须先回到 Decision，更新 classpath、许可证、锁版和所有命令。

#### D2-12：Python runtime 手动或由测试 harness 启动

Phase 2 的 `Main` 不负责 ProcessBuilder。生产 `bridgeEnabled=true` 时只连接配置地址；runtime
不可达时 AgentSession 保持 DISCONNECTED 并后台重连，本地 Brain 接管。

#### D2-13：feature flag 只控制远程会话

`agent.bridge.enabled=false` 时不创建 socket/IO thread，但正式 Game loop 仍使用新的双速本地执行路径。
Phase 0/1 contract tests 可继续调用 legacy `Enemy.updateAI()`；它们只保护碰撞、有限感知、确定性和 trace
schema 等不变量，不再锁死完整动作轨迹。正式 `Game` 不再调用该方法。

#### D2-14：旧存档不是兼容 gate

AgentSession、request、lease、queue 和 runtime state 均不持久化。读档视为新运行会话，重新生成 runId、
sessionEpoch 和 requestGeneration。已有 agentId 若可读则沿用；旧格式兼容失败不阻塞 Phase 2 验收。

### 6.2 暂时假设

| ID | 假设 | 验证方式 |
|----|------|----------|
| A2-01 | 当前完整 observation NDJSON 小于 64 KiB | serializer size test；超限即停止并评估压缩/分块 |
| A2-02 | 一 Enemy 一 IO thread 对当前“少量敌人”规模可接受 | Phase 2 记录线程/连接数；Phase 3/6 做遭遇规模测试 |
| A2-03 | Python 3.11+ 标准库足以实现 fake runtime | 在 Python 3.14.6 环境运行协议 smoke test |
| A2-04 | 当前 `EntityManager.flushPendingChanges()` 可作为 commit barrier | P2-A10 验证 feedback/observation 读取提交后位置 |
| A2-05 | 当前 Planner + high-water 截断可维持 Phase 1 动作节奏 | bridge-disabled 行为与 Phase 1 固定场景回归 |

假设失败时先记录事实并回到 Specification，不得静默更换传输、序列化或动作语义。

### 6.3 需要 Builder 决定

当前没有阻塞本 Spec 审批的新产品决定。以下变化若被提出，必须重新审批：

- Phase 2 自动启动 Python 子进程；
- Phase 2 引入第三方 JSON 依赖；
- Phase 2 提前实现真实模型、复杂 skill、全局推理预算或多 Agent 协作；
- 放弃每 Enemy 独立会话或允许共享上下文。

---

## 7. 目标架构与数据流

### 7.1 数据所有权

| 数据 | 创建者 | 可修改者 | 生命周期 |
|------|--------|----------|----------|
| 权威 world/entity state | Java Game/EntityManager | Java Action + commit barrier | 当前楼层 |
| ObservationEnvelope/VisibleTile | Java PerceptionSystem | 无；不可变 | 单 observation |
| latestObservation | Enemy | Enemy 在 commit 后替换 | 当前楼层/Enemy |
| outbound/inbound queue | AgentSession | 游戏线程生产 outbound；IO thread 消费；方向相反 | Session |
| request identity/context | AgentSession | AgentSession game-thread lifecycle 方法 | 单 request |
| IntentProposal | Python fake Agent | 无；Java 只解析/校验 | 单 submit_intent |
| IntentLease | Java IntentArbiter | Java | 到期、失效、取消或替换 |
| ActionQueue | Java Enemy/ReflexController | Java | 当前 lease/override |
| ActionOutcome | Java executeOneAction | 无；排队后发送 | 单 Action |

### 7.2 正式 Game tick 顺序

Game 在每个 `PLAYING` logical tick 先创建稳定 Enemy snapshot，避免遍历 HashMap view 时生命周期变化：

```text
0. 玩家输入、charge/hit timer
1. enemies[].pollAgentMessages()
   - drain 已解析 inbound
   - advance deadline/request state
   - Validator + Arbiter 在安全动作边界采纳新 lease
2. enemies[].executeOneAction()
   - cooldown 未到：不执行
   - cooldown 到：刷新 ReflexObservation，P0–P4 选择并执行最多一个 Action
   - 缓存 ActionOutcome；不生成给 Agent 的新 observation
3. EntityManager.flushPendingChanges()
   EntityManager.removeDeadEntities()
   - 这是 world commit barrier
4. 存活 enemies[].collectAgentUpdates()
   - 从提交后的世界刷新 latestObservation
   - 生成/合并 feedback 和最小事件
   - 根据首次、低水位、耗尽、blocked、关键反射或 heartbeat 决定是否请求
5. send* 只完成有界 queue enqueue；IO thread 独立传输
6. 关闭本 tick 死亡 Enemy 的 Session
```

`logicalTick` 只在 PLAYING tick 递增，暂停和渲染不会改变它。`runId` 每次新游戏或读档会话生成一次；
`floorId` 使用真实 `floorLevel`。测试 harness 注入固定 runId 和逻辑时钟。

### 7.3 等待模型时的行为

```text
AWAITING_INTENT / SOFT_TIMED_OUT
  ├─ current lease + queue 仍有效 → 继续执行
  ├─ 当前 FOV 产生 P1/P2 → 短期 reflex override
  ├─ 无有效远程计划 → RuleBasedBrain 创建 LOCAL_FALLBACK lease
  └─ RuleBasedBrain 也无合法动作 → WAIT
```

远程响应只能在步骤 1 采纳。它不能在 IO thread 回调中直接清队列、移动 Entity 或修改 Strategy。

### 7.4 请求生命周期转换表

| 当前 RequestState | 事件 | 条件 | 新状态 | 副作用 |
|--------------------|------|------|--------|--------|
| NO_REQUEST | REQUEST_TRIGGER | CONNECTED 且有 latestObservation | AWAITING_INTENT | 生成 decisionId；发送 observation；保存 ValidationContext |
| NO_REQUEST | REQUEST_TRIGGER | 非 CONNECTED | NO_REQUEST | 保留 latestObservation/pendingEvents；本地执行 |
| AWAITING_INTENT | SOFT_DEADLINE | request 仍相同 | SOFT_TIMED_OUT | trace `AGENT_SLOW`；不取消 |
| AWAITING_INTENT / SOFT_TIMED_OUT | VALID_SUBMIT_INTENT | 身份与语义通过 | NO_REQUEST | Arbiter 采纳 lease；清除已发送 pendingEvents |
| AWAITING_INTENT / SOFT_TIMED_OUT | INVALID/STale SUBMIT | 当前 request 已完成或身份不匹配 | 原状态或 NO_REQUEST | 记录原因；不得修改 lease/queue/cooldown |
| AWAITING_INTENT / SOFT_TIMED_OUT | HARD_DEADLINE | request 仍相同 | CANCEL_PENDING | requestGeneration++；发送 cancel_request；保留安全旧计划 |
| AWAITING_INTENT / SOFT_TIMED_OUT | SUPERSEDE | 关键前提彻底失效 | CANCEL_PENDING | 同上；latestObservation 保留 |
| CANCEL_PENDING | CANCEL_ACK | decisionId/generation 匹配 | NO_REQUEST | 旧请求释放；如有 pending trigger 可发下一请求 |
| CANCEL_PENDING | CANCEL_GRACE_EXPIRED | 未确认 | NO_REQUEST | 关闭旧物理 Session；sessionEpoch++ 后重连；旧响应永久失效 |
| 任意非 NO_REQUEST | CONNECTION_LOST | — | NO_REQUEST | requestGeneration++；保留 latestObservation；本地执行 |
| 任意 | ENEMY_DIED/FLOOR_EXIT/CLOSE | — | NO_REQUEST | closed=true；清队列；关闭 socket；永不重连 |

### 7.5 ConnectionState 转换表

| 当前状态 | 事件 | 新状态 | 行为 |
|----------|------|--------|------|
| DISABLED | feature flag 开启且未 closed | CONNECTING | IO thread 尝试连接 |
| CONNECTING | connect 成功 | CONNECTED | sessionEpoch++；messageSeq 清零；允许请求 |
| CONNECTING | connect 失败 | DISCONNECTED | 按退避计划下一次连接 |
| CONNECTED | EOF/IO/protocol fatal | DISCONNECTED | socket 关闭；in-flight 失效；requestGeneration++ |
| DISCONNECTED | backoff 到期 | CONNECTING | IO thread 重试 |
| 任意 | close | 保持枚举值，closed=true | 关闭 socket、停止重连、清理队列、有界 join |

重连退避：250ms、500ms、1000ms、2000ms、最多 4000ms；成功连接后重置。毫秒值仅用于 IO 诊断。

### 7.6 ExecutionState 转换表

| 当前状态 | 事件 | 新状态 | 行为 |
|----------|------|--------|------|
| NO_PLAN/EXHAUSTED/BLOCKED | 有效 remote/local lease 产生 actions | EXECUTING | 装入最多 high-water 个 actions |
| EXECUTING | Action SUCCESS 且 queue 非空 | EXECUTING | 下个 cooldown 继续 |
| EXECUTING | queue 为空 | EXHAUSTED | 触发请求或本地补位 |
| EXECUTING | Action BLOCKED/INTERRUPTED | BLOCKED | 产生 feedback；允许一次受限局部重规划 |
| BLOCKED | 局部重规划成功 | EXECUTING | 不改变 decisionId，actionIndex 继续递增 |
| BLOCKED | 局部重规划失败 | EXHAUSTED | lease 标记 STALE/SUSPENDED；请求重规划 |
| 任意 | P1/P2 override | 保留原状态 | 单独执行 reflex action；记录 override 起止 |
| 任意 | lease 到期/语义失效且无 override | NO_PLAN | 清除对应队列；本地 fallback |

---

## 8. 接口与数据契约

### 8.1 包与核心类型

```text
byog/
  Bridge/
    AgentSession.java
    AgentSessionConfig.java
    AgentHandler.java
    AgentProtocol.java
    AgentProtocolCodec.java
    MonotonicClock.java
  AI/
    AiTickContext.java
    ReflexObservation.java
    ReflexController.java
    InterruptPolicy.java
    IntentLease.java
    IntentArbiter.java
    DecisionValidator.java
  Action/
    ActionOutcome.java
  Perception/
    VisibleTile.java
```

不得把这些类型新增到 `byog.Core`；`Core` 继续只承载 Game/Main。

### 8.2 AgentSession API

```java
package byog.Bridge;

public final class AgentSession implements AutoCloseable {
    /** 只读请求快照；保存完整身份、deadline 基点和源 observation 校验上下文。 */
    public static final class RequestContext { /* immutable */ }

    public AgentSession(AgentSessionConfig config,
                        AgentProtocol.Identity identity,
                        MonotonicClock clock);

    public ConnectionState getConnectionState();
    public RequestState getRequestState();
    public long getSessionEpoch();
    public long getRequestGeneration();
    public boolean isClosed();

    /** 游戏线程调用；只 drain inbound queue，不读 socket。 */
    public void pollInbound(AgentHandler handler, long logicalTick);

    /** 游戏线程调用；开始请求或仅更新 latest snapshot，永不阻塞。 */
    public RequestStartResult requestIntent(
            ObservationEnvelope observation,
            List<AgentProtocol.WorldEventData> pendingEvents,
            long logicalTick);

    /** 游戏线程调用；有界入队。 */
    public EnqueueResult sendActionFeedback(ActionOutcome outcome, long logicalTick);

    /** Phase 2 只提供协议 seam；有界入队。 */
    public EnqueueResult sendWorldEvent(
            AgentProtocol.WorldEventData event, long logicalTick);

    /** 游戏线程推进 deadline；使用注入的 monotonic clock。 */
    public void advanceRequestLifecycle(long logicalTick);

    /** 关键失效时请求取消；不等待 ack。 */
    public void supersedeCurrentRequest(SupersedeReason reason, long logicalTick);

    @Override
    public void close();
}
```

硬约束：

- 只有 IO thread 持有/修改 Socket、Reader、Writer。
- IO thread 只解析并 enqueue，不调用 Enemy。
- `pollInbound()` 是唯一触发 `AgentHandler` 的位置，因此 handler 总在游戏线程执行。
- `close()` 幂等；关闭 socket 解除 read 阻塞，join 最多 `shutdownJoinMs`。
- close 后所有 send/request 返回 `CLOSED`，不得重新连接。

### 8.3 AgentHandler

```java
package byog.Bridge;

public interface AgentHandler {
    void onIntentSubmitted(AgentProtocol.SubmitIntentData data,
                           AgentProtocol.Envelope envelope,
                           AgentSession.RequestContext requestContext);

    void onCancelAcknowledged(AgentProtocol.CancelAckData data,
                              AgentProtocol.Envelope envelope,
                              AgentSession.RequestContext cancelledRequest);

    void onProtocolRejected(ProtocolFailure failure);
}
```

`AgentSession.pollInbound()` 可以先做 envelope/session identity 校验；Enemy 的 `DecisionValidator` 再做
request、intent、世界前提和权限校验。

### 8.4 Enemy 正式运行 API

```java
// byog.Entity.Enemy

public void attachAgentSession(AgentSession session);
public void detachAgentSession();

/** Game 阶段 1。 */
public void pollAgentMessages(AiTickContext context);

/** Game 阶段 2；cooldown 到期时至多执行一个 Action。 */
public void executeOneAction(AiTickContext context,
        TETile[][] world, EntityManager entityMgr);

/** Game 阶段 4；必须在 EntityManager commit 后调用。 */
public void collectAgentUpdates(AiTickContext context,
        TETile[][] world, EntityManager entityMgr, Player player);

/** Enemy 死亡、离层或游戏退出时调用。 */
public void closeAgentRuntime();
```

`Enemy.updateAI()` 在 Phase 2 保留为 Phase 0/1 legacy harness seam，但正式 `Game` 不再调用它。新增生产
逻辑不得复制在 legacy 方法中。

### 8.5 本地控制类型

```java
public final class IntentLease {
    public StrategicIntent getIntent();
    public String getDecisionId();
    public long getBasedOnObservationSeq();
    public long getAdoptedAtTick();
    public long getValidUntilTick();
    public InterruptPolicy getInterruptPolicy();
    public DecisionSource getDecisionSource(); // REMOTE_AGENT | LOCAL_FALLBACK
    public LeaseState getState();               // ACTIVE | SUSPENDED | STALE | EXPIRED
}
```

```java
public final class DecisionValidator {
    public ValidationResult validate(
            AgentProtocol.SubmitIntentData proposal,
            AgentProtocol.Envelope envelope,
            AgentSession.RequestContext request,
            ReflexObservation currentObservation,
            TETile[][] committedWorld);
}
```

ValidationResult 必须是类型化结果，不返回自由文本作为唯一语义：

```text
ACCEPTED
SCHEMA_MISMATCH
IDENTITY_MISMATCH
SESSION_EPOCH_MISMATCH
REQUEST_GENERATION_MISMATCH
DECISION_ID_MISMATCH
OBSERVATION_SEQ_MISMATCH
UNKNOWN_SKILL
INVALID_PARAMETERS
TARGET_OUT_OF_BOUNDS
TARGET_NOT_KNOWN
TARGET_UNREACHABLE
INVALID_TTL
INVALID_INTERRUPT_POLICY
STALE_PRECONDITION
```

失败不得清 ActionQueue、重置 cooldown、改变 current lease 或采用 proposal 中任何字段。

### 8.6 完整身份与版本不变量

`submit_intent` 只有同时匹配以下字段才进入语义校验：

```text
schemaVersion
+ runId
+ floorId
+ agentId
+ sessionEpoch
+ observationSeq
+ decisionId
+ requestGeneration
```

- `runId`：每次新游戏/读档会话创建；测试可注入固定值。
- `floorId`：真实 floorLevel。
- `agentId`：来自 Enemy，不能来自 Entity.id。
- `sessionEpoch`：每次成功建立新的物理会话递增。
- `observationSeq`：每 Enemy/每楼层单调递增。
- `decisionId`：每请求唯一；生产 UUID，测试使用 deterministic IdGenerator。
- `requestGeneration`：hard timeout、supersede、断线导致旧请求失效时递增。
- `messageSeq`：每 sessionEpoch 从 0 单调递增，用于诊断乱序/重复。

### 8.7 通用 NDJSON 信封

```json
{
  "schemaVersion": "phase2.session.v1",
  "messageId": "uuid-or-deterministic-test-id",
  "messageSeq": 18,
  "runId": "run-uuid",
  "floorId": 1,
  "agentId": "guard-a",
  "sessionEpoch": 3,
  "logicalTick": 42,
  "type": "observation",
  "data": {}
}
```

Phase 2 消息类型：

| 方向 | type | Phase 2 行为 |
|------|------|--------------|
| Java → Python | `observation` | 发起一次 intent request |
| Java → Python | `action_feedback` | fake runtime 解析并记录，不重规划 |
| Java → Python | `world_event` | schema/seam 存在；仅发送最小 lifecycle/reflex 事件 |
| Java → Python | `heartbeat` | 低优先级，可合并/丢弃 |
| Java → Python | `cancel_request` | 取消正在推理的 decision |
| Python → Java | `submit_intent` | 唯一可采纳的决策消息 |
| Python → Java | `cancel_ack` | 释放 CANCEL_PENDING |
| 双向 | `protocol_error` | 诊断协议失败，不携带可执行 intent |

`cancel_request` 与 Phase 4 的 `cancel_intent` 不同：前者取消尚未完成的推理请求，后者取消已经采纳的计划。
通用信封版本不替代 payload 版本：observation、intent 和 outcome 分别携带
`private-observation.v1`、`strategic-intent.v1` 和 `action-outcome.v1`，任一不兼容版本都必须被拒绝。

### 8.8 observation data

```json
{
  "observationVersion": "private-observation.v1",
  "decisionId": "decision-17",
  "observationSeq": 5,
  "requestGeneration": 2,
  "observedAtTurn": 42,
  "self": {
    "position": {"x": 9, "y": 2},
    "hp": 20
  },
  "visibleTiles": [
    {"x": 9, "y": 2, "type": "FLOOR", "walkable": true},
    {"x": 10, "y": 2, "type": "WALL", "walkable": false}
  ],
  "visibleEntities": [
    {
      "type": "PLAYER",
      "position": {"x": 3, "y": 2},
      "visibleHp": 100,
      "agentId": null
    }
  ],
  "heardEvents": [],
  "pendingEvents": [],
  "capabilities": {
    "supportedSkills": ["PATROL", "CHASE", "ATTACK", "GUARD"],
    "sightRange": 7,
    "attackDamage": 10,
    "moveInterval": 5
  }
}
```

`VisibleTile` 必须在 PerceptionSystem 创建 observation 时从 world 固化；serializer 只能读取
ObservationEnvelope，不能额外接收 live world。不可见坐标不得以 `"UNKNOWN"` tile 形式枚举，因为世界尺寸、
边界形状和不可见 tile 数本身也可能形成额外信息；只发送可见 tile。

### 8.9 submit_intent data

```json
{
  "decisionId": "decision-17",
  "observationSeq": 5,
  "requestGeneration": 2,
  "intent": {
    "intentVersion": "strategic-intent.v1",
    "skill": "CHASE",
    "parameters": {
      "targetPosition": {"x": 3, "y": 2}
    },
    "confidence": 0.8,
    "validForTicks": 20,
    "interruptPolicy": {
      "engageVisiblePlayer": true,
      "respondToAdjacentThreat": true,
      "allowLocalReroute": true
    }
  }
}
```

Phase 2 规则：

| 字段 | 规则 |
|------|------|
| intentVersion | 必须等于 `strategic-intent.v1` |
| skill | 必须为 PATROL/CHASE/ATTACK/GUARD |
| parameters | 只允许 skill schema 声明的 key；未知 key 拒绝 |
| targetPosition | CHASE/ATTACK/GUARD 必填；必须在界内且来自合法 observation/可达事实 |
| confidence | 必须有限且在 0.0–1.0；仅诊断，不决定 Java 权限 |
| validForTicks | 1–60；Java 以 adoptedAtTick 计算 validUntilTick |
| interruptPolicy | 缺失时用 skill 安全默认值；不能禁用 P0/P1 |

fake Agent 的确定性逻辑：

- observation 中有相邻 PLAYER → ATTACK；
- 否则有 PLAYER → CHASE；
- 否则 → PATROL，target 使用 observation 内 deterministic 可见 walkable tile；
- fake runtime 不读取隐藏地图，不使用随机数。

### 8.10 action_feedback、cancel 与最小 world_event

```json
{
  "decisionId": "decision-17",
  "actionIndex": 1,
  "actionType": "MoveAction",
  "result": "SUCCESS",
  "beforePosition": {"x": 9, "y": 2},
  "afterPosition": {"x": 10, "y": 2},
  "selfHp": 20,
  "decisionSource": "REMOTE_AGENT",
  "overrideReason": null
}
```

```json
{
  "decisionId": "decision-17",
  "requestGeneration": 2,
  "reason": "HARD_TIMEOUT"
}
```

Phase 2 最小 world_event 白名单：

```text
PLAN_BLOCKED | PLAN_EXHAUSTED | PLAYER_SPOTTED
| REFLEX_OVERRIDE_STARTED | REFLEX_OVERRIDE_ENDED
```

fake runtime 只需合法解析和记录 action_feedback/world_event；Phase 4 才根据它们持续重规划。

hard timeout/supersede 时，Session 先把旧的 RequestContext 移入只读 `cancelledRequest`，再递增当前
requestGeneration。`cancel_request` 携带旧 context 的 generation；`cancel_ack` 必须匹配该旧 context，
而任何随后到达的旧 `submit_intent` 都因当前 generation 已变化而被丢弃。

### 8.11 队列、背压与容量

默认值：

| 资源 | 容量 |
|------|------|
| outbound queue | 32 messages |
| inbound queue | 16 messages |
| pendingEvents | 16 merged events |
| NDJSON frame | 65,536 UTF-8 bytes |
| 每次 pollInbound 最大 drain | 8 messages |

`EnqueueResult`：

```text
ACCEPTED | COALESCED | DROPPED_LOW_PRIORITY
| REJECTED_CRITICAL | CLOSED
```

规则：

1. heartbeat 最低优先级；队列满时先丢旧 heartbeat。
2. 未发送 observation 按 agent/session 只保留最新一个；替换返回 COALESCED。
3. world_event 按 eventType + related entity 合并，保留最新 tick，最多 16 个。
4. action_feedback、cancel_request 不得静默覆盖；满时返回 REJECTED_CRITICAL，记录 trace，并使 Session
   进入 degraded/disconnect-rebuild 路径，游戏线程仍立即返回。
5. inbound 满时先丢身份已明显过期或重复 messageSeq；仍无法容纳有效关键消息时视为 protocol fatal，
   关闭连接、requestGeneration++、记录 trace。

### 8.12 配置

`GameConfig` 和默认 `config/game.properties` 新增：

```properties
agent.bridge.enabled=false
agent.bridge.host=127.0.0.1
agent.bridge.port=9876
agent.bridge.softDeadlineMs=1500
agent.bridge.hardDeadlineMs=10000
agent.bridge.cancelGraceMs=500
agent.bridge.outboundCapacity=32
agent.bridge.inboundCapacity=16
agent.bridge.pendingEventCapacity=16
agent.bridge.maxFrameBytes=65536
agent.bridge.reconnectInitialMs=250
agent.bridge.reconnectMaxMs=4000
agent.bridge.shutdownJoinMs=1000
agent.bridge.heartbeatTicks=120
agent.actionQueue.lowWater=2
agent.actionQueue.highWater=5
```

配置必须做范围校验：

- soft > 0，hard > soft；
- cancelGrace > 0；
- queue capacity ≥ 4；
- highWater > lowWater ≥ 0；
- maxFrameBytes 在 1024–1,048,576；
- reconnectMax ≥ reconnectInitial > 0。

非法配置记录错误并使用默认值，不得在 PLAYING tick 抛异常。

---

## 9. 逐文件变更计划

| 文件 | 新建/修改 | 责任与关键变更 | 不应包含 |
|------|-----------|----------------|----------|
| `byog/Bridge/AgentProtocol.java` | 新建 | 信封、消息 data、identity、enum 和受限 DTO | 游戏逻辑、socket |
| `byog/Bridge/AgentProtocolCodec.java` | 新建 | 严格 JSON parser/writer、schema decode、frame limit | regex JSON、第三方未审批依赖 |
| `byog/Bridge/AgentSessionConfig.java` | 新建 | 配置快照和范围校验 | 读取 world |
| `byog/Bridge/MonotonicClock.java` | 新建 | `nanoTime()` seam；System 实现 | wall-clock canonical identity |
| `byog/Bridge/AgentHandler.java` | 新建 | 游戏线程上的 inbound handler | IO thread 修改 Enemy |
| `byog/Bridge/AgentSession.java` | 新建 | 持久 TCP、独立 IO loop、有界队列、状态转换、deadline、cancel/reconnect/close | LLM、Brain 接口 |
| `byog/Perception/VisibleTile.java` | 新建 | 可见 tile 类型、坐标、walkable 的不可变 snapshot | live TETile 引用 |
| `byog/Perception/ObservationEnvelope.java` | 修改 | 保存不可变 visibleTiles；维持现有防御性复制 | 恢复 live world |
| `byog/Perception/PerceptionSystem.java` | 修改 | 创建 visibleTiles；不改变 FOV 规则 | 听觉传播 |
| `byog/AI/AiTickContext.java` | 新建 | run/floor/logicalTick/trace identity | socket |
| `byog/AI/AiTickLoop.java` | 新建 | Game 与 headless tests 共用的 poll/execute/commit/collect coordinator | 测试专用规则、反射 |
| `byog/AI/ReflexObservation.java` | 新建 | 从最新私有 observation 提取快脑所需切片 | player/global world 引用 |
| `byog/AI/InterruptPolicy.java` | 新建 | 三个受限 interrupt flag 与安全默认值 | 任意表达式 |
| `byog/AI/IntentLease.java` | 新建 | TTL、来源、decision identity、状态 | 直接执行 Action |
| `byog/AI/DecisionValidator.java` | 新建 | 完整身份、skill、参数、知识、可达性和语义时效校验 | 修改 Enemy 状态 |
| `byog/AI/IntentArbiter.java` | 新建 | P0–P4、safe adoption、override 生命周期 | 隐藏信息读取 |
| `byog/AI/ReflexController.java` | 新建 | 当前私有感知上的相邻攻击、可见追杀、queue/local 选择 | 长期战略、跨 Agent 通信 |
| `byog/Action/ActionOutcome.java` | 新建 | decision/action/result/before/after/source/override 关联 | Agent reasoning |
| `byog/Action/ActionQueue.java` | 修改 | 配置 low/high water、有界 replace/refill、跨 tick 缓冲 | 同 tick 批量执行 |
| `byog/AI/ClassicalPlanner.java` | 修改 | 提供有界 action prefix；保持 Java 权威 | 远程代码/任意 skill |
| `byog/Entity/Enemy.java` | 修改 | 新 runtime 字段、三阶段方法、单 Action、outcome、close；legacy updateAI 仅保留测试 | `PythonBridgeBrain`、socket |
| `byog/Core/Game.java` | 修改 | runId/logicalTick、五阶段 loop、spawn/load/floor/quit Session 生命周期 | `playWithInputString` Agent 集成 |
| `byog/Trace/AgentTrace.java` | 修改 | phase2 schema、事件和关联字段；保持 Phase0/1 schema 语义隔离 | 把完整 gameplay 轨迹当兼容 API |
| `byog/IO/GameConfig.java` | 修改 | bridge/action queue 配置和默认生成 | 模型配置 |
| `config/game.properties` | 修改 | 增加 Phase 2 默认项 | API key |
| `agent/python/dungeonmind_agent/protocol.py` | 新建 | 与 Java schema 对称的严格标准库 codec/validation | LLM |
| `agent/python/dungeonmind_agent/brain/deterministic.py` | 新建 | 每连接 deterministic brain | LangGraph/状态记忆 |
| `agent/python/dungeonmind_agent/server.py`、`agent/python/run.py` | 新建 | 多连接 server 与 CLI host/port/mode/delay 入口 | 自动被 Main 启动 |
| `byog/Test/Phase2ProtocolTest.java` | 新建 | codec、有限知识、坏消息 | 网络 sleep |
| `byog/Test/Phase2SessionTest.java` | 新建 | fake clock/transport 下的状态、队列、deadline、close | GUI |
| `byog/Test/Phase2AiTickTest.java` | 新建 | 双速、P0–P4、cooldown、commit、feedback | 真实 LLM |
| `byog/Test/Phase2IntegrationTest.java` | 新建 | Java ↔ Python smoke、断线/恢复 | 性能统计 |
| `byog/Test/EncounterHarness.java` | 新建 | 所有 two-guard 模式共用 ASCII parser、身份、时钟和 legacy/private 调度 | 镜像 harness |
| `byog/Test/Phase2TestSuite.java` | 新建 | 唯一 deterministic Agent gate；直接列 leaf classes 且每项一次 | Suite 嵌套、integration |
| `PHASE_2_COMPLETION.md` | 验收时新建 | 命令、结果、偏差、Phase 3 artifacts | 未验证的完成声明 |

明确不新建：

- `byog/AI/PythonBridgeBrain.java`
- `byog/Core/AgentMailbox.java`
- `byog/Core/AgentRequest.java`
- `byog/Core/AgentDecision.java`

---

## 10. 实施顺序

### Step 2.1：固化 observation snapshot 与协议 codec

**输入**：Phase 1 ObservationEnvelope、§8 schema。

**改动**：

1. 新增 VisibleTile 并由 PerceptionSystem 在 observation 创建时填充。
2. 新增 AgentProtocol/Codec 和 Java 协议单元测试。
3. 实现 deterministic IdGenerator/test identity seam。

**验证命令**：

```powershell
javac -encoding UTF-8 -cp "..\library-sp18\javalib\*" -d out `
  (Get-ChildItem byog -Recurse -Filter *.java | ForEach-Object FullName)
java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" `
  org.junit.runner.JUnitCore byog.Test.Phase2ProtocolTest
```

**产出**：`phase2.session.v1` Java codec、不可变 visibleTiles 和协议测试。

### Step 2.2：先完成 bridge-disabled 双速本地 loop

**输入**：当前 RuleBasedBrain、Planner、ActionQueue、EntityManager。

**改动**：

1. 新增 AiTickContext、ReflexObservation、ActionOutcome。
2. ActionQueue 变为 low/high-water 跨 tick 缓冲。
3. Enemy 新增 poll/executeOne/collect 三阶段方法；一次最多一个 Action。
4. Game 改为五阶段 loop 和真实 run/floor/logicalTick。
5. bridge=false 下只用 RuleBasedBrain，但走正式新 loop。

**验证**：P2-A09/P2-A10/P2-R01/P2-R02。

**产出**：不依赖 Python 的新动作节拍和 commit/feedback seam。

### Step 2.3：实现 Lease、Validator、Arbiter 与 ReflexController

**输入**：Step 2.2。

**改动**：

1. 新增 InterruptPolicy、IntentLease、DecisionValidator、IntentArbiter、ReflexController。
2. 实现 Phase 2 的 P0–P4 最小行为和 semantic stale rules。
3. 反射覆盖保留/恢复有效旧 lease，产生显式 trace/outcome。

**验证**：P2-A01 至 P2-A08。

**产出**：纯 Java 可确定性测试的双速控制链。

### Step 2.4：实现 AgentSession 的无网络核心

**输入**：协议 codec 和状态表。

**改动**：

1. 先用 injectable transport/clock 实现状态、队列、single in-flight、deadline、cancel 和 close。
2. 固定 queue capacity、coalescing、critical rejection 和 protocol fatal。
3. 所有状态转换产生类型化 trace。

**验证**：P2-S01 至 P2-S14；禁止真实 sleep。

**产出**：确定性的 Session lifecycle 核心。

### Step 2.5：接入 TCP IO loop

**输入**：Step 2.4。

**改动**：

1. 只有 IO thread 操作 Socket。
2. 实现 persistent NDJSON read/write、frame limit、EOF/error、reconnect/backoff。
3. close 通过 socket close 解除阻塞并有界 join。

**验证**：Java test fake endpoint + P2-S11/P2-S12/P2-S14。

**产出**：真实非阻塞 AgentSession。

### Step 2.6：实现 Python deterministic fake Agent

**输入**：锁定 schema。

**改动**：

1. Python protocol codec。
2. fake Agent 多连接 server。
3. CLI 模式：`normal`、`delay`、`malformed`、`disconnect`、`no-read`。
4. cancel_request/cancel_ack 和 feedback/event 接收。

**验证**：

```powershell
python agent/python/run.py --host 127.0.0.1 --port 9876 --mode normal
```

另开终端运行 protocol smoke script；不调用模型、不访问外部网络：

```powershell
python agent/python/smoke_test.py
```

**产出**：可独立启动的 fake runtime。

### Step 2.7：连接 Enemy、Game 与 AgentSession

**输入**：Steps 2.2–2.6。

**改动**：

1. Game 在 new/load/floor transition 时创建/关闭每 Enemy Session。
2. Enemy 在 collect 阶段请求/合并 observation 和 feedback。
3. poll 阶段校验/采纳 intent；execute 阶段只运行快脑。
4. runtime 不可达时保持 local fallback；恢复后安全接管。

**验证**：P2-I01 至 P2-I05、P2-A03。

**产出**：完整 Java ↔ Python ↔ Java ↔ ActionOutcome 闭环。

### Step 2.8：扩展 trace 与 Phase 2 harness

**输入**：完整链路。

**改动**：

1. 增加 Phase 2 trace event/fields。
2. 保持 Phase0/Phase1 schema 语义隔离；旧完整轨迹 baseline 仅作历史 evidence。
3. 扩展共享 EncounterHarness 的固定 clock/ID/failure injection，并复用正式 AiTickLoop。
4. 扩展 leaf-only Phase2TestSuite；integration 保持独立入口。

**验证**：P2-T01/P2-T02/P2-R01/P2-R02。

**产出**：可关联、可重复的 Phase 2 evidence。

### Step 2.9：配置、人工故障演练与关闭阶段

**输入**：所有实现。

**改动**：

1. 更新 GameConfig/properties。
2. 手动演练 delay/no-read/disconnect/restart。
3. 记录命令、结果、偏差和性能诊断。
4. 生成 PHASE_2_COMPLETION.md。

**产出**：可审批的阶段证据与 Phase 3 交接包。

---

## 11. 测试与验收矩阵

### 11.1 Protocol 与知识边界

| Test ID | 场景 | 核心断言 | 方式 | 需求 |
|---------|------|----------|------|------|
| P2-P01 | `protocol_round_trip` | 所有 Phase 2 消息 Java encode/decode 字段不丢失 | 自动 | INV-06 |
| P2-P02 | `malformed_or_duplicate_key_rejected` | 坏 JSON、重复 key、错误类型、尾随垃圾被拒绝 | 自动 | INV-06 |
| P2-P03 | `oversize_and_deep_frame_rejected` | 超 64KiB 或嵌套 >16 不进入 inbound | 自动 | INV-06/07 |
| P2-P04 | `observation_serializes_only_visible_tiles` | 墙后 tile/实体坐标和类型不在 NDJSON | 自动 | INV-02 |
| P2-P05 | `unknown_message_type_nonfatal` | 未知兼容消息记录 protocol error，不修改 Enemy | 自动 | INV-06 |
| P2-P06 | `unknown_skill_or_parameter_rejected` | FLY/未知 parameter 不生成 lease/action | 自动 | INV-04/06 |

### 11.2 AgentSession、状态和背压

| Test ID | 场景 | 核心断言 | 方式 | 需求 |
|---------|------|----------|------|------|
| P2-S01 | `single_inflight_coalesces_observations` | 连续 observation 只保留一个 request，latest snapshot 更新 | 自动 fake clock | INV-07 |
| P2-S02 | `soft_timeout_keeps_request` | 进入 SOFT_TIMED_OUT，不 cancel、不清旧计划 | 自动 fake clock | INV-07 |
| P2-S03 | `hard_timeout_invalidates_generation` | generation++、cancel pending、迟到结果失效 | 自动 fake clock | INV-07 |
| P2-S04 | `cancel_ack_allows_next_request` | ack 后才用 latest observation 发下一请求 | 自动 | INV-07 |
| P2-S05 | `cancel_grace_rebuilds_session` | 无 ack 时物理关闭，epoch++ 后重连，无并发旧请求 | 自动 fake clock/transport | INV-07 |
| P2-S06 | `heartbeat_dropped_first` | queue 满时 heartbeat 可丢，game-thread enqueue 立即返回 | 自动 | INV-07 |
| P2-S07 | `observation_coalesces_when_full` | 未发送 observation 被最新值替换 | 自动 | INV-07 |
| P2-S08 | `critical_outbound_rejection_is_traced` | feedback/cancel 满时不静默丢失，触发 degraded/rebuild | 自动 | INV-08 |
| P2-S09 | `inbound_overflow_is_protocol_fatal` | 有效关键消息无法入队时关闭连接并失效 request | 自动 | INV-07 |
| P2-S10 | `complete_identity_tuple_required` | 任一 run/floor/agent/epoch/seq/id/generation 不匹配均丢弃 | 自动 | INV-01/06/07 |
| P2-S11 | `io_thread_is_only_socket_owner` | 游戏线程路径不调用 transport read/write/connect | 自动 test transport | INV-07 |
| P2-S12 | `disconnect_reconnects_with_backoff` | 状态按表转换，新 epoch 恢复 | 自动 fake clock | INV-07 |
| P2-S13 | `close_is_idempotent_and_terminal` | close 两次安全；不再重连/入队 | 自动 | INV-07 |
| P2-S14 | `close_unblocks_reader_with_bounded_join` | 阻塞 read 被 socket close 解除，退出清理有上限 | 自动 | INV-07 |
| P2-S15 | `two_enemy_sessions_do_not_cross` | A/B identity、queue、generation、response 完全隔离 | 自动 | INV-01 |

### 11.3 双速 AI、仲裁与执行

| Test ID | 场景 | 核心断言 | 方式 | 需求 |
|---------|------|----------|------|------|
| P2-A01 | `remote_intent_goes_through_java_planner` | submit CHASE 只经 Validator/Lease/Planner 生成 Action | 自动 | INV-04/05 |
| P2-A02 | `invalid_intent_has_no_side_effects` | 拒绝后 lease/queue/cooldown 完全不变 | 自动 | INV-04/06 |
| P2-A03 | `old_plan_continues_while_agent_slow` | delay 数秒时按原 cadence 继续旧计划 | 自动 fake clock | INV-05/07 |
| P2-A04 | `no_plan_uses_local_brain` | 首次/断线/耗尽时 Enemy 不发呆 | 自动 | INV-05/07 |
| P2-A05 | `visible_player_triggers_next_action_tick` | 模型等待时玩家进入 FOV，下个 action tick 追击/攻击 | 自动 | INV-05/09 |
| P2-A06 | `fresh_guard_policy_limits_p2_not_p1` | GUARD 禁止主动追击，但玩家相邻仍触发 P1 | 自动 | INV-04/05 |
| P2-A07 | `stale_patrol_cannot_override_reflex_engage` | 基于旧 observation 的 PATROL 不压过当前可见追杀 | 自动 | INV-02/07 |
| P2-A08 | `hidden_player_position_not_used` | 玩家离开 FOV 后 override 显式结束；快脑不读取真实隐藏位置，旧 lease 仅在前提仍成立时恢复 | 自动 | INV-02/05 |
| P2-A09 | `one_action_per_cooldown` | 每 action tick 至多一个 Action；blocked 不同 tick 重试 | 自动 | INV-05 |
| P2-A10 | `feedback_uses_committed_position` | ActionOutcome/新 observation 在 commit 后读取位置 | 自动 | INV-04/08 |
| P2-A11 | `action_feedback_keeps_decision_link` | decisionId/actionIndex/source/overrideReason 可关联 | 自动 | INV-08 |
| P2-A12 | `remote_resume_only_at_safe_boundary` | Python 恢复后不在 Action 执行中途替换 lease/queue | 自动 | INV-05/07 |

### 11.4 端到端与回归

| Test ID | 场景 | 核心断言 | 方式 | 需求 |
|---------|------|----------|------|------|
| P2-I01 | `java_python_fake_round_trip` | observation → submit_intent → action → feedback 完整关联 | 自动集成 | INV-04/06/08 |
| P2-I02 | `two_fake_agent_connections_independent` | 两连接持久、决策不串线 | 自动集成 | INV-01 |
| P2-I03 | `slow_python_does_not_stop_logical_ticks` | delay 模式下 headless logicalTick 持续推进 | 自动集成 | INV-07 |
| P2-I04 | `no_read_does_not_block_game_thread` | no-read + outbound 饱和时 tick 仍推进 | 自动集成 | INV-07 |
| P2-I05 | `python_restart_remote_resumes` | disconnect 后本地接管，restart 后新 intent 安全接管 | 自动集成 | INV-07 |
| P2-R01 | `legacy_contracts_preserved` | bridge=false 下碰撞、有限感知、确定性和旧 trace schema 语义仍成立；不比较完整动作轨迹 | 自动回归 | INV-04/08 |
| P2-R02 | `phase1_action_cadence_preserved` | 正式新 loop 的移动间隔与 Phase 1 参数一致 | 自动 | INV-05 |

### 11.5 验证命令

```powershell
# 编译
$javaSources = Get-ChildItem byog -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
javac -encoding UTF-8 -cp "..\library-sp18\javalib\*" -d out $javaSources

# 全部 Phase 0/1/2 deterministic contracts（leaf tests 各一次）
java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.Phase2TestSuite

# 分层定位；integration 独立运行，不放进快速 deterministic Suite
java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.Phase2ProtocolTest
java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.Phase2SessionTest
java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.Phase2AiTickTest
java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.Phase2IntegrationTest
```

deterministic tests 禁止用 `Thread.sleep()` 推进 deadline。TCP integration 可使用有界等待，但失败信息必须
指出超时的具体状态，不允许无限等待。

---

## 12. Observability 与运行证据

### 12.1 Phase 2 canonical 事件

新增 `AgentTrace.PHASE2_SCHEMA_VERSION = "phase2.trace.v1"`，至少包含：

```text
AGENT_SESSION_STATE_CHANGED
AGENT_REQUEST_SENT
AGENT_SLOW
AGENT_HARD_TIMEOUT
AGENT_CANCEL_SENT
AGENT_CANCEL_ACKED
STALE_RESPONSE_DROPPED
INTENT_ADOPTED
LOCAL_BRAIN_TAKEOVER
REMOTE_AGENT_RESUMED
REFLEX_OVERRIDE_STARTED
REFLEX_OVERRIDE_ENDED
ACTION_FEEDBACK_ENQUEUED
OUTBOUND_MESSAGE_COALESCED
OUTBOUND_MESSAGE_DROPPED
PROTOCOL_ERROR
```

canonical 关联字段：

```text
runId, floorId, agentId, logicalTick,
sessionEpoch, observationSeq, decisionId, requestGeneration,
messageType, connectionState, requestState, executionState,
decisionSource, validationResult, overrideReason,
actionIndex, actionType, rawActionResult,
beforePosition, afterPosition
```

### 12.2 非 canonical diagnostics

以下字段可以记录，但不得进入 deterministic canonical evidence：

```text
wallClockTimestamp
queueDelayMs
connectMs
transportMs
fakeInferenceMs
totalLatencyMs
threadName
socketAddress
```

Phase 0/1 使用各自 schema 时，Phase 2 新字段不得混入旧 schema。历史 baseline 不由普通测试读取、生成或
覆盖；gameplay 回归使用类型化不变量断言，只有稳定 wire/schema fixture 才允许 byte-for-byte golden。

### 12.3 必须保留的运行证据

- Phase2TestSuite 命令、测试数、结果和耗时。
- Python 版本、Java 版本、fake runtime 启动命令。
- normal/delay/no-read/disconnect/restart 五种模式结果。
- 一个可关联 observation → intent → action → feedback 的 canonical trace。
- 一个 stale response 被丢弃且当前行为不变的 trace。
- 一个 reflex override 在模型等待期间开始/结束的 trace。
- 队列饱和情况下 logical tick 继续推进的证据。

---

## 13. 失败处理、兼容与迁移

### 13.1 失败行为

| 失败 | 必须行为 |
|------|----------|
| runtime 未启动 | DISCONNECTED + 后台重连；本地 Brain；游戏继续 |
| connect/read/write EOF | 关闭 socket、requestGeneration++、退避重连 |
| JSON/schema 错误 | protocol trace；消息无副作用；严重错误重建 Session |
| soft timeout | 保留请求和安全旧计划 |
| hard timeout | generation 失效、cancel/rebuild；不清安全旧计划 |
| stale submit_intent | 只记录并丢弃，不改变 lease/queue/cooldown |
| outbound 低优先消息满 | 合并或丢弃并 trace |
| outbound 关键消息满 | 立即返回 rejected、trace、degrade/rebuild；不阻塞 |
| Enemy 死亡/离层 | close Session，丢弃所有未来消息，禁止重连 |
| Python 恢复 | 新 epoch/request 的有效 intent 只在安全边界接管 |

### 13.2 bridge-disabled 兼容

- 默认 `agent.bridge.enabled=false`。
- 正式 Game 仍使用新的三阶段 Enemy API 和单 Action cadence。
- 本地 RuleBasedBrain 只读私有 observation。
- Phase 0/1 compatibility adapter 可调用 legacy `updateAI()` 保护关键契约；不得要求保留其完整动作轨迹，
  也不得成为新生产 runtime 的依赖。

### 13.3 存档策略

- 不保存 AgentSession、socket、sessionEpoch、request、lease、ActionQueue、pendingEvents。
- 读档创建新 runId 和新 Session，AI runtime 从本地 observation 冷启动。
- 沿用可读取的 Enemy.agentId；无法读取的旧存档不属于 Phase 2 兼容 gate。
- 存档时不等待 outbound drain 或 Agent 回复。

### 13.4 半完成状态防护

- 在 Step 2.2 完成前，Game 不切换到新三阶段 API。
- AgentSession 未完成时，bridge flag 必须保持 false。
- Python schema 与 Java codec 不一致时，禁止“宽松解析后尽量执行”。
- 任何失败路径最终都必须回到可运行的本地控制，而不是 null Brain/null Session 分支。

---

## 14. 风险与停止条件

### 14.1 主要风险

1. **范围膨胀**：Phase 2 同时改 loop、通信、时效和反射。通过先完成 bridge-disabled 双速 loop、
   再接 AgentSession 降低耦合。
2. **线程泄漏/旧回调**：楼层切换、Enemy 死亡和退出必须覆盖 close/rebuild/epoch 测试。
3. **手写 JSON codec 缺陷**：限制语法、深度、帧大小并做坏输入测试；不允许 regex 快捷实现。
4. **动作速度回归**：从 tickCounter/MAX_RETRY 迁移后可能变快或变慢；P2-A09/P2-R02 是强 gate。
5. **语义过时判断过度或不足**：Phase 2 只做 TTL、当前 FOV、目标可见/可达和关键前提冲突；
   不实现任意条件语言。
6. **Queue 规则自相矛盾**：关键消息无法保证永不丢且永不阻塞；本 Spec 选择“不可静默丢，拒绝后
   trace + rebuild”，实现不得宣称无界可靠。
7. **一 Agent 一线程规模风险**：Phase 2 只针对少量敌人；记录指标，Phase 3/6 决定是否复用传输。
8. **Planner 仍然扁平**：Phase 2 只支持四个受限 skill；不得把复杂 plan 偷塞进 parameters。

### 14.2 停止条件

出现以下任一情况，停止 Build 并回到 Specification/Decision：

- 需要游戏线程阻塞等待网络或 Future 才能保证正确性。
- 无法证明同一 Enemy 最多一个有效 Python 推理。
- 需要向 Python 发送不可见 tile、真实隐藏玩家坐标或完整 Entity/world 引用。
- 需要让 Python 返回原子动作、任意脚本或未经 validator 的条件表达式。
- 需要引入第三方 JSON/网络/并发依赖。
- bridge=false 时无法保持 Phase 1 动作节奏、碰撞/有限感知不变量或 trace schema 隔离。
- 需要提前实现真实 LLM、全局预算、复杂技能、多步骤计划或多 Agent 协作。
- AgentSession 无法在楼层切换/Enemy 死亡后有界关闭。
- 64KiB observation 假设失败且需要协议分块或压缩。

---

## 15. Definition of Done

- [ ] §5.1 的全部 In Scope 交付物存在。
- [ ] P2-P01 至 P2-P06 全部通过。
- [ ] P2-S01 至 P2-S15 全部通过。
- [ ] P2-A01 至 P2-A12 全部通过。
- [ ] P2-I01 至 P2-I05 全部通过。
- [ ] P2-R01、P2-R02 全部通过。
- [ ] Phase2TestSuite 可由单一命令 headless 运行，只列 leaf test class、无嵌套和重复计数，不访问默认存档或 GUI。
- [ ] Python fake runtime 使用标准库，可在 normal/delay/malformed/disconnect/no-read 模式运行。
- [ ] 游戏线程代码路径不调用 socket connect/read/write 或等待 Future。
- [ ] 每 Enemy 最多一个有效 in-flight；hard timeout 后旧结果永不生效。
- [ ] outbound/inbound/pendingEvents 全部有界，队列满时游戏线程仍立即返回。
- [ ] 模型等待期间有计划 Enemy 继续行动，无计划 Enemy 使用本地 Brain。
- [ ] 玩家进入 FOV 时下一个 action tick 触发合法 P1/P2 行为。
- [ ] 陈旧 PATROL 不会覆盖当前 REFLEX_ENGAGE。
- [ ] 每个 action tick 至多执行一个 Action，moveInterval 等价 cadence 保持。
- [ ] observation 和 ActionOutcome 使用 world commit 后的位置。
- [ ] runId/floorId/agentId/sessionEpoch/seq/id/generation 全部参与响应校验。
- [ ] Phase 2 canonical trace 可关联 observation、request、intent、override、action、feedback、fallback。
- [ ] Phase 0/1 碰撞、有限感知、确定性和旧 trace schema 隔离契约通过；历史完整轨迹 baseline 不参与 gate。
- [ ] 默认 bridge=false 时不创建网络线程，游戏使用私有感知本地 Brain。
- [ ] 没有引入 LLM、LangGraph/LangChain、Tool Calling、共享上下文或复杂战术库。
- [ ] 所有运行命令、结果、偏差和已知失败写入 `PHASE_2_COMPLETION.md`。
- [ ] Phase 3 artifacts 和禁止假设项已明确交付。

---

## 16. 下一阶段交接

### 16.1 Phase 3 可以依赖

- `phase2.session.v1` 通用信封和 NDJSON codec。
- 每 Enemy 持久 AgentSession、独立身份、队列、重连和 close 生命周期。
- 单 in-flight、latest observation 合并、soft/hard deadline、cancel/rebuild。
- `submit_intent` 的完整身份和语义校验 seam。
- `IntentLease`、`InterruptPolicy`、`IntentArbiter`、`ReflexController`。
- 五阶段 Game loop、单 Action cadence 和 world commit barrier。
- `ActionOutcome` 与 `action_feedback` 的稳定 v1 关联字段。
- deterministic fake runtime 及故障模式。
- Phase 2 trace、harness、测试 clock/transport 和回归 gate。

### 16.2 Phase 3 不得假设

- Python 已有 LLM、LangGraph、Tool Calling、state 或 checkpoint。
- Phase 2 四个 skill 就是最终战术能力。
- `StrategicIntent` 可以永远保持扁平 proposal。
- 已有 Skill Registry/Tactical Executor。
- 已有全局模型并发/token/cost 预算。
- fake runtime 会利用 ActionOutcome 重规划。
- world_event 已覆盖完整玩法事件。
- 多 Enemy 可以共享 context、memory 或消息。
- 一 Enemy 一 TCP/IO thread 已通过大规模遭遇验证。
- Agent runtime state 可以跨读档或跨楼层恢复。

### 16.3 Phase 3 首要入口

1. 在不改变 AgentSession 和 Game loop 的前提下接入真正的 Agent graph。
2. 新增 Tool Calling 消息并保持 Java 权威。
3. 将 `strategic-intent.v1` 扩展为版本化 skill/parameter/plan metadata，而不是增加任意字段。
4. 建立 Skill Registry/Tactical Executor seam。
5. 增加整个遭遇的全局推理调度和预算，不合并 Agent 私有上下文。

---

## 附录 A：Spec 自检

- [x] 完整读取 Intent、Roadmap、AI Tick 架构、Phase 1 Completion 和模板。
- [x] 审计当前 Game、Enemy、Perception、AI、Action、Trace、Config、Save 和测试代码。
- [x] 记录实际分支 `ai-enemis` 与基线 commit `4c1bc98`。
- [x] 明确本次重写未重新运行测试，没有把静态审计写成运行证据。
- [x] 删除旧 `PythonBridgeBrain + AgentMailbox + 每次 PATROL fallback` 核心假设。
- [x] 区分代码事实、设计推断、锁定决定、假设和停止条件。
- [x] 完整定义状态转换、queue/backpressure、Session close、身份和迟到规则。
- [x] 未提前实现 Phase 3 LLM/全局预算、Phase 4 多步骤 plan 或 Phase 5 协作。
- [x] 逐文件计划使用当前模块化包结构，没有把新类型塞回 `byog.Core`。
- [x] 每个 DoD 条目都有测试、trace 或运行证据来源。
- [x] 旧存档按 Builder 已确认范围处理；PerceptionSystemTest 已纳入无重复 deterministic gate。
- [x] 测试架构使用共享 EncounterHarness、生产 AiTickLoop 和 leaf-only Phase2TestSuite；完整 gameplay golden 已降为历史 evidence。

## 附录 B：旧 Spec → 新 Spec 迁移对照

| 旧方案 | 新方案 |
|--------|--------|
| `PythonBridgeBrain implements EnemyBrain` | `AgentSession` 独立于本地 EnemyBrain |
| `AgentMailbox` request/response | 持久双向 event session |
| 每次 think 立即返回 PATROL | 安全旧计划 → reflex → local fallback → WAIT |
| 新 observation 覆盖旧请求 | 单 in-flight + latest observation/pending event 合并 |
| 500ms 单 timeout | soft/hard deadline + cancel/rebuild |
| 只校验 decisionId/agentId/seq | 完整 run/floor/agent/epoch/seq/id/generation |
| 两阶段 Game loop | 五阶段 loop + world commit barrier |
| 每 AI tick 最多重试 4 个 Action | 每 action tick 至多 1 个 Action |
| Python 响应直接清 ActionQueue | Validator → Lease → Arbiter → safe adoption |
| Python 慢时 Enemy 反复 PATROL | 旧计划和 Java 快脑持续运行 |
| 单 request/response JSON | 通用信封 + typed NDJSON messages |
| 假设 `org.json` 已存在 | 仓库内严格受限 codec；不新增依赖 |
| Java ProcessBuilder 自动启动 Python | Phase 2 runtime 手动/测试 harness 启动 |
| ActionOutcome 推迟到 Phase 4 | Phase 2 传输并关联；Phase 4 才消费并持续重规划 |
