# Phase 4 Spec：执行反馈与事件驱动重规划

## 0. 元数据

- Phase：4
- 状态：Accepted；自动化实现完成，人工 `PLAY-01` 待补
- 文档类别：Design Spec
- 创建日期：2026-08-13
- 基线分支：`result`
- 基线 commit：`7e0665ac360466c114a6b6bc8fdaf812388241ea`
- 前一阶段 Completion：[`PHASE_3_COMPLETION.md`](documents/phases/phase-3/PHASE_3_COMPLETION.md)
- 配套 Build Guide：[`PHASE_4_BUILD_GUIDE.md`](PHASE_4_BUILD_GUIDE.md)
- 适用 Intent：[`PROJECT_INTENT_zh-CN.md`](PROJECT_INTENT_zh-CN.md)
- 适用 Roadmap：[`DEVELOPMENT_ROADMAP.md`](DEVELOPMENT_ROADMAP.md)
- 跨阶段架构：[`AI_TICK_ARCHITECTURE.md`](AI_TICK_ARCHITECTURE.md)、
  [`session.md`](session.md)、[`agentarchitecture.md`](agentarchitecture.md)
- API 决定：继续保持 provider-neutral；Builder 后续自行选择并配置非 OpenAI 或其他 provider

### 0.1 批准与开工条件

本 Spec 固定 Phase 4 的目标行为、跨语言契约、失败语义和验收边界。开始实现前必须满足：

1. Builder 接受第 6 节的锁定决定；
2. 实现者先修复 Step 4.1 记录的交互式新世界身份初始化顺序；
3. 不把真实 provider、API key、模型 ID 或 `.env` 当作默认自动化测试前置；
4. 若需要改变 Intent、Roadmap 或 Phase 5 的通信边界，先回到 Specification/Decision；
5. 不围绕已作废的 `Game.playWithInputString(String)` 新建 Agent runtime 能力。

## 1. 必读输入与审计范围

### 1.1 已完整读取的权威输入

- [`PROJECT_INTENT_zh-CN.md`](PROJECT_INTENT_zh-CN.md)
- [`DEVELOPMENT_ROADMAP.md`](DEVELOPMENT_ROADMAP.md)
- [`PHASE_3_COMPLETION.md`](documents/phases/phase-3/PHASE_3_COMPLETION.md)
- [`PHASE_SPEC_TEMPLATE.md`](PHASE_SPEC_TEMPLATE.md)
- [`AI_TICK_ARCHITECTURE.md`](AI_TICK_ARCHITECTURE.md)
- [`session.md`](session.md) 中 Session、pending event、背压和存档边界
- [`agentarchitecture.md`](agentarchitecture.md) 中协议、消息方向、provider seam 和安全边界
- [`agent/contract/README.md`](agent/contract/README.md)

### 1.2 已审计的生产代码

Java：

- [`byog/Action/Action.java`](byog/Action/Action.java)
- [`byog/Action/ActionOutcome.java`](byog/Action/ActionOutcome.java)
- [`byog/AI/IntentLease.java`](byog/AI/IntentLease.java)
- [`byog/AI/IntentArbiter.java`](byog/AI/IntentArbiter.java)
- [`byog/AI/TacticalSkill.java`](byog/AI/TacticalSkill.java)
- [`byog/AI/TacticalSkillRegistry.java`](byog/AI/TacticalSkillRegistry.java)
- [`byog/AI/BuiltinTacticalSkills.java`](byog/AI/BuiltinTacticalSkills.java)
- [`byog/Bridge/AgentProtocol.java`](byog/Bridge/AgentProtocol.java)
- [`byog/Bridge/AgentProtocolCodec.java`](byog/Bridge/AgentProtocolCodec.java)
- [`byog/Bridge/AgentSession.java`](byog/Bridge/AgentSession.java)
- [`byog/Entity/Enemy.java`](byog/Entity/Enemy.java)
- [`byog/Core/Game.java`](byog/Core/Game.java)
- [`byog/Trace/AgentTrace.java`](byog/Trace/AgentTrace.java)
- [`byog/Perception/HeardEvent.java`](byog/Perception/HeardEvent.java)
- [`byog/Perception/PerceptionSystem.java`](byog/Perception/PerceptionSystem.java)

Python：

- [`agent/python/dungeonmind_agent/brain/graph_agent.py`](agent/python/dungeonmind_agent/brain/graph_agent.py)
- [`agent/python/dungeonmind_agent/graph/state.py`](agent/python/dungeonmind_agent/graph/state.py)
- [`agent/python/dungeonmind_agent/graph/tools.py`](agent/python/dungeonmind_agent/graph/tools.py)
- [`agent/python/dungeonmind_agent/graph/workflow.py`](agent/python/dungeonmind_agent/graph/workflow.py)
- [`agent/python/dungeonmind_agent/model/adapter.py`](agent/python/dungeonmind_agent/model/adapter.py)
- [`agent/python/dungeonmind_agent/config.py`](agent/python/dungeonmind_agent/config.py)
- [`agent/python/dungeonmind_agent/protocol.py`](agent/python/dungeonmind_agent/protocol.py)
- [`agent/python/dungeonmind_agent/observability.py`](agent/python/dungeonmind_agent/observability.py)

### 1.3 已审计的测试与运行入口

- [`agent/python/tests/test_graph_runtime.py`](agent/python/tests/test_graph_runtime.py)
- [`agent/python/tests/test_protocol.py`](agent/python/tests/test_protocol.py)
- [`agent/python/tests/test_contract_fixtures.py`](agent/python/tests/test_contract_fixtures.py)
- [`agent/python/tests/test_deterministic_brain.py`](agent/python/tests/test_deterministic_brain.py)
- [`byog/Test/AgentRuntimeTestSuite.java`](byog/Test/AgentRuntimeTestSuite.java)
- [`byog/Test/AgentRuntimeIntegrationTest.java`](byog/Test/AgentRuntimeIntegrationTest.java)
- [`byog/Test/AgentSessionTest.java`](byog/Test/AgentSessionTest.java)
- [`byog/Test/AgentProtocolContractTest.java`](byog/Test/AgentProtocolContractTest.java)
- [`byog/Test/AgentTraceContractTest.java`](byog/Test/AgentTraceContractTest.java)
- [`byog/Test/CoreGameplayRegressionSuite.java`](byog/Test/CoreGameplayRegressionSuite.java)

### 1.4 分支、工作树与工具链事实

审计时：

- 当前分支是 `result`；
- HEAD 是 Phase 3 完成提交 `7e0665a`；
- `out/production/proj2/` 下存在 IDE/编译生成的修改和未跟踪副本；它们不是权威源码，
  本阶段不得修改、提交或删除这些用户生成物；
- Python 依赖继续由 `pyproject.toml` 和 `pylock.toml` 固定：LangGraph 1.2.10、
  LangChain 1.3.14、Pydantic 2.13.4、SQLite checkpointer 3.1.0；
- 本阶段不新增依赖，也不依赖某个 provider SDK，因此无需为 Spec 选择供应商文档或 API 版本。

### 1.5 已发现的文档与实现差异

1. [`AI_TICK_ARCHITECTURE.md`](AI_TICK_ARCHITECTURE.md) 的部分段落仍把真实 Tool Calling/model
   写成未来能力，且有一处把 `pollAgentMessages()` 描述为生命周期空操作；Phase 3 代码与
   Completion 已证明 Graph、Tool Calling、Session poll 和远程 Lease 实际存在。本 Spec 以代码和
   Completion 为当前事实，不沿用这些过时句子。
2. [`agentarchitecture.md`](agentarchitecture.md) 的 deterministic runtime 章节仍把 feedback/event
   描述为只记录不重规划；这与当前代码一致，正是 Phase 4 要改变的起点。
3. [`byog/Core/Game.java#L364`](byog/Core/Game.java#L364) 至
   [`byog/Core/Game.java#L379`](byog/Core/Game.java#L379) 在新世界路径中先调用
   `beginAgentRun()`，随后才生成 `worldId`；而 Session identity 构造要求非空 `worldId`。
   默认 Bridge 关闭掩盖了该问题。它必须作为 Step 4.1 的生产接线缺陷修复，不能在真实联调时绕过。

## 2. 阶段目标与成功定义

Phase 4 完成后，一个敌人不再把模型输出当作一次性意图。模型或 scripted adapter 可以提交一个
有界多步骤计划；Python runtime 把当前 step 作为既有 `strategic-intent.v2` 提交给 Java；Java 在
commit barrier 后用结构化反馈报告动作、step 和计划状态；同一 Agent 的 runtime 消费反馈和事件，
在无需深度推理时直接推进下一 step，在前提变化或失败时才触发重新规划。

成功不是“feedback 已收到”，而是可以用一条确定性 trace 证明：

```text
私有 Observation + 新事件
  → 有界计划
  → 当前 step 的 StrategicIntent
  → Java validator / skill / Action
  → commit 后 ActionOutcome
  → step 成功、失败、暂停或恢复
  → 本地推进下一 step 或触发一次有原因的 replan
```

同时必须保持：游戏线程不等待模型、Java 是世界唯一权威、反射即时生效、敌人状态不串线、
没有重要事件时不会靠固定频率调用模型。

## 3. 起始事实

### 3.1 已确认事实

1. `ActionOutcome` 已在 commit 后创建，包含 raw action result、前后位置、HP、decision source 和
   override reason，但没有 plan/step 字段、稳定原因码或 step 状态
   （[`ActionOutcome.java#L11`](byog/Action/ActionOutcome.java#L11)）。
2. wire `ActionFeedbackData` 只镜像当前单动作字段
   （[`AgentProtocol.java#L239`](byog/Bridge/AgentProtocol.java#L239)）。
3. `PlanMetadataData(planId, stepId, revision)` 已进入 intent，但源码明确说明它当前只用于关联，
   不是多步骤 executor（[`AgentProtocol.java#L215`](byog/Bridge/AgentProtocol.java#L215)）。
4. `TacticalSkillRegistry` 已统一负责验证、翻译、规划和恢复判断，但 `TacticalSkill` 还没有
   step progress 评估 seam（[`TacticalSkill.java#L7`](byog/AI/TacticalSkill.java#L7)）。
5. `IntentLease` 只有 `ACTIVE / SUSPENDED / STALE / EXPIRED`，已经能在反射覆盖时暂停和恢复，
   但不会产生完整计划状态反馈（[`IntentLease.java#L13`](byog/AI/IntentLease.java#L13)）。
6. `Enemy.publishAgentUpdates()` 已在 blocked、ActionQueue 低水位、反射变化和 heartbeat 时请求新意图；
   heartbeat 当前会触发深度决策，这与事件驱动目标不符
   （[`Enemy.java#L769`](byog/Entity/Enemy.java#L769)）。
7. 当前 `WorldEventType` 只有五种，`Enemy` 实际只产生 `PLAN_BLOCKED` 和反射开始/结束；
   `PLAYER_SPOTTED` 和 `PLAN_EXHAUSTED` 尚未形成完整生产路径
   （[`AgentProtocol.java#L51`](byog/Bridge/AgentProtocol.java#L51)、
   [`Enemy.java#L835`](byog/Entity/Enemy.java#L835)）。
8. `AgentSession` 对 action feedback 使用关键消息语义，对 world event 使用可合并低优先级语义；
   pending event 合并键是 `eventType + relatedEntityId`
   （[`AgentSession.java#L580`](byog/Bridge/AgentSession.java#L580)、
   [`AgentSession.java#L1282`](byog/Bridge/AgentSession.java#L1282)）。
9. Python `AgentGraphState` 只有单个 `recent_feedback`，没有 active plan、step cursor、事件消费账本或
   replan reason（[`state.py#L68`](agent/python/dungeonmind_agent/graph/state.py#L68)）。
10. `GraphAgentBrain` 接收 observation、cancel 和 action feedback，但忽略 standalone `world_event`
    （[`graph_agent.py#L25`](agent/python/dungeonmind_agent/brain/graph_agent.py#L25)）。
11. `AgentWorkflow.record_feedback()` 只覆盖最近一条 feedback；`decide()` 每次仍从
    `prepare_context → model → tools` 开始，没有反馈 reducer 或 plan advance 分支
    （[`workflow.py#L33`](agent/python/dungeonmind_agent/graph/workflow.py#L33)、
    [`workflow.py#L82`](agent/python/dungeonmind_agent/graph/workflow.py#L82)）。
12. `submit_strategic_intent` 总是生成单个 `intent-0`，scripted adapter 也只提交一个 skill
    （[`tools.py#L75`](agent/python/dungeonmind_agent/graph/tools.py#L75)）。
13. `PerceptionSystem` 当前创建空的 `heardEvents` 列表；`HeardEvent` 只是已定义的数据结构。
    因此本阶段可以完成“收到声音时触发”的消费契约，但不能宣称已经有完整声音传播玩法。
14. `config/game.properties` 默认 `agent.bridge.enabled=false`。Phase 4 的 Agent 行为不会改变默认
    本地游玩，除非 Builder 显式启用 Bridge 并启动 runtime。
15. `RuntimeConfig(brain="model")` 在 provider 未配置时会在 bind/ready 前失败；这是当前正确防护，
    本阶段必须保留（[`config.py#L23`](agent/python/dungeonmind_agent/config.py#L23)）。

### 3.2 从事实得出的设计推断

- 多步骤计划不应作为未来 Action 列表跨进程发送，否则会绕过 Java 的每-step validator 和当前世界前提。
- `strategic-intent.v2` 已有 runtime-owned plan metadata，足以承载“当前 step”关联；本阶段不需要
  `strategic-intent.v3`。
- 反馈和事件必须有显式 payload version 与稳定 occurrence ID，才能在 Session 重发、pending event
  合并和 checkpoint 恢复时区分“新输入”与“已消费输入”。
- step 是否成功必须由 Java skill 语义和 commit 后世界判断；模型不能提交自由文本条件表达式。
- heartbeat 应只检查连接活性，不能继续作为模型决策触发器。
- active plan 属于外部 Agent runtime 的短期执行状态；Java 只持有当前 Lease 和权威进度判断，
  两者通过 plan/step/decision 关联，而不是共享可变对象。
- 读档会复用 `worldId/floorId/agentId` checkpoint key，但新的 `runId` 必须让 active plan、未消费反馈和
  当前 step 冷启动，避免旧运行的执行状态进入新运行。

### 3.3 本阶段建议

Phase 4 采用“Python 持有有界计划，Java 评估当前 step”的最小闭环：

- 模型工具提交最多 6 个高层 step；
- runtime 只把当前 step 编码为 v2 intent；
- Java registry 负责每个 skill 的进度、失败与恢复语义；
- step 成功时 Python 无模型调用地推进下一 step；
- step 失败、目标丢失、计划完成或有重要新事件时才进入模型 replan；
- 单次 blocked 允许一次确定性局部重新寻路，重复失败才升级为 replan。

## 4. 需求追踪

| Requirement | 本阶段如何满足 | 验收证据 |
|---|---|---|
| INV-01 独立身份 | plan、inbox、cursor 继续按 `worldId/floorId/agentId` 隔离 | 两敌人交错反馈测试 |
| INV-02 有限知识 | plan tool 与 replan context 只读取私有 observation、feedback、event | hidden target/no-cheat 测试 |
| INV-03 世界内通信 | 只预留 `MESSAGE_RECEIVED` trigger；不创建共享消息或自动黑板 | scope/codec 测试 |
| INV-04 Java 权威 | Java validator、skill progress 和 commit 后 outcome 决定执行事实 | progress/authority 测试 |
| INV-05 分层控制 | 模型规划，Java 寻路、Action、反射和一次局部修正 | reflex/reroute 场景 |
| INV-06 严格契约 | hard-cut feedback v2、event v1、observation v3 和共享 fixtures | 双语言 codec gate |
| INV-07 异步时效 | Session single in-flight、generation、run reset 和 non-blocking 不变 | timeout/load/floor tests |
| INV-08 可追踪评估 | feedback/event/plan/step/replan reason 贯穿两类 trace | correlation trace test |
| INV-09 玩法价值 | blocked 后可恢复、反射后可续计划、无事件不滥用模型 | 固定遭遇与人工试玩 |

## 5. 范围与非目标

### 5.1 In Scope

- 修复交互式新世界在 `worldId` 生成前建立 Agent Session 的顺序问题。
- 将 action feedback 硬切为 `action-feedback.v2`，增加 plan/step、稳定状态和原因字段。
- 将 world event 固定为 `agent-event.v1`，增加 `eventId`、关联字段和严格 enum。
- 因 pending event 结构变化，将 observation 硬切为 `private-observation.v3`。
- Java 在 execute 时冻结 decision/plan/step 身份，在 commit 后补全位置、HP 和权威进度。
- 在 `TacticalSkill` seam 增加 skill-specific step progress 评估。
- Python 增加有界 plan、step cursor、feedback/event inbox、去重账本和 run boundary reset。
- 新增 `submit_plan` 工具；保留单-step 工具并归一化为单-step plan。
- event-driven graph route：继续 step、推进 step、暂停/恢复或重新规划。
- 实现一次有界局部 reroute 与重复失败升级规则。
- 把 heartbeat 从“触发模型”改为“只保活”。
- 升级 Java runtime trace 与 Python model trace，记录稳定 replan 证据。
- deterministic contract、headless scenario、真实 Python/TCP scripted integration 和回归。
- 在文档和 Completion 中保留 provider 配置提醒。

### 5.2 Out of Scope

- 具体 provider adapter、SDK、endpoint、模型 ID、API key 或真实付费调用。
- 多敌人共享上下文、自动黑板、队长 Agent 或共享计划。
- Phase 5 的世界内消息生产、传播距离、延迟、阻断和接收玩法。
- 完整声音传播系统；本阶段只消费已进入私有 observation/event 的声音。
- 新增 `AMBUSH / FLANK / GUARD_EXIT` 等复杂战术技能。
- 模型生成任意路径、Action、Java 类名或自由表达式前置/终止条件。
- 跨楼层长期记忆、玩家画像、向量数据库。
- GUI Agent overlay、成本仪表盘或平衡调整。
- 把 Session、active plan 或 inbox 写入 Java 世界存档。
- 维护或扩展 `playWithInputString(String)`。

## 6. 已锁定决定、假设与待决定项

### 6.1 已锁定决定

#### D4-01：保留现有五段 AI Tick、Session 和 single in-flight

Phase 4 只在既有 `poll → execute → commit → collect → close dead` 中增加 progress/feedback；
不创建第二套 Game Loop、Socket 或请求状态机。

#### D4-02：`strategic-intent.v2` 保持不变

wire 每次仍只发送一个当前 step 的 skill、参数、TTL、interrupt policy 和 plan metadata。
完整 future steps 永不进入 Java wire。

#### D4-03：反馈和事件一次性硬切新 payload

- feedback：`action-feedback.v2`
- event：`agent-event.v1`
- observation：`private-observation.v3`
- envelope：继续 `agent-session.v1`
- intent：继续 `strategic-intent.v2`

Java、Python、fixtures 和 tests 同次切换；不提供旧 feedback/event/observation 双读。

#### D4-04：模型只提交受限 plan draft

一个 plan 至多 6 个 step。每个 step 只含：skill、受限参数、TTL、interrupt policy。
`planId / stepId / revision` 由 runtime 生成，模型不能指定。

#### D4-05：Java 决定 step progress

每个 `TacticalSkill` 定义固定的成功、继续、失败和恢复语义。模型不能提交自由文本表达式，
Python 不能把动作成功自行解释成世界事实。

#### D4-06：默认 progress policy 有界

施工默认值：

| 约束 | 默认值 |
|---|---:|
| plan 最大 step 数 | 6 |
| Agent state 中 feedback 窗口 | 32 |
| Agent state 中 event 窗口 | 32 |
| 已消费 ID 窗口 | 各 64 |
| 同一 step 最大连续 blocked | 2 |
| 局部 reroute | 每 step 最多 1 次 |
| plan revision 最大值 | 32 |

超过限制时明确 `REPLAN_REQUIRED` 或拒绝，不截断成看似成功的计划。

#### D4-07：step 成功优先本地推进，不调用模型

若 plan 还有下一 step，runtime 校验其结构并直接生成新的当前-step intent。只有计划失败、关键前提改变、
重要事件或整个计划结束才允许进入模型节点。

#### D4-08：heartbeat 不触发模型

heartbeat 只作为低优先级连接保活消息。没有事件、step 边界或首次冷启动时，不得固定频率调用模型。

#### D4-09：一次 blocked 先局部修正，重复 blocked 再 replan

仅当当前 policy 允许 `allowLocalReroute` 时，Java 清除当前 step 的旧 ActionQueue 前缀并重新规划一次。
第二次 blocked、前提失效或 registry 无法恢复时，step 失败并请求 replan。

#### D4-10：反射覆盖暂停计划，不默认销毁计划

P1/P2 反射继续立即生效。覆盖开始产生 `PAUSED` 反馈；结束时 Java 根据 skill `canResume` 决定
`RESUMED` 或 `REPLAN_REQUIRED`。反射动作沿用原 decision/plan/step 关联，但注明 override reason。

#### D4-11：反馈和事件按稳定 ID 幂等消费

`feedbackId` 和 `eventId` 由 Java 创建且在同一 occurrence 的 standalone/pending 副本间保持相同。
Python 只在结果实际写回或明确完成无模型推进后提交 consumption cursor；取消、超时和异常不吞掉输入。

#### D4-12：新 `runId` 清除易失执行状态

checkpoint key 不变时，如果输入 `runId` 与 state 中不同：

- 清除 active plan、step cursor、pending inbox、已消费 cursor 和当前 decision；
- 保留仅用于审计的有界历史计数；
- 不恢复旧 Lease 或旧 step；
- 新楼层因 key 改变自然冷启动。

#### D4-13：重要反馈不能静默丢失

action feedback 和 step terminal 状态继续使用关键消息语义。关键队列饱和进入 degraded/reconnect，
Java fallback 继续运行；不得把失败伪装成已交付。

#### D4-14：声音与消息只建立 trigger 消费契约

`SOUND_HEARD` 在收到私有声音事实时触发；`MESSAGE_RECEIVED` 作为 Phase 5 producer 的稳定入口。
本阶段不凭空生成声音或消息，也不提前实现传播规则。

#### D4-15：provider-neutral 自动化可以关闭本阶段

scripted adapter 必须走真实 graph、plan tool、反馈 reducer、step advance 和 replan 分支。
真实 provider smoke 在 Builder 配置自己的 adapter/API 后补做；未配置时 `--brain model` 继续 fail-fast。

#### D4-16：trace 不记录敏感模型内容

不记录 API key、authorization、raw prompt、provider raw response 或自由 reasoning。验收只依赖结构化字段。

### 6.2 暂时假设

1. 四个 built-in skill 足以构造一个多步骤固定场景；验证时至少使用两种 skill。
2. 当前 `ActionResult` 可保留为 raw result，稳定 `reasonCode` 由 skill progress evaluator 和上下文产生，
   暂不改 `Action.execute()` 返回类型。
3. 32 条 feedback/event 与 64 条消费 ID 足以覆盖一个遭遇的短期执行窗口；压力测试若证明不足，
   只能调整有界配置，不得改成无限列表。
4. `private-observation.v3` 只因 event payload 变化升级，不扩大 Agent 的可见世界。

### 6.3 需要 Builder 决定

当前没有阻塞 Phase 4 文档或 provider-neutral 实现的产品决定。具体 provider 选择仍由 Builder 后续决定；
它不允许实现者预先假设 OpenAI、Anthropic、兼容 OpenAI 的 endpoint 或任意特定 SDK。

## 7. 目标架构与数据流

### 7.1 数据所有权

| 数据 | 创建者 | 权威修改者 | 生命周期 |
|---|---|---|---|
| `PlanDraft / ActivePlan` | Python runtime | Python reducer | 同 Agent、同 run、同 floor |
| 当前 `StrategicIntent` | Python runtime | immutable；Java 只校验/转换 | 一个 decision/step Lease |
| `IntentLease` | Java Arbiter | Java Arbiter | TTL、反射、替换或 run 结束 |
| `StepProgress` | Java skill evaluator | Java | commit 后一次反馈 |
| `ActionOutcome` | Java Enemy collect | immutable | commit 后发送/trace |
| feedback/event inbox | Python runtime | Python inbox | 有界、幂等、run reset |
| world state | Java | Java | 游戏运行/存档 |

### 7.2 正常多步骤计划

```mermaid
sequenceDiagram
    autonumber
    participant J as Java Enemy/Session
    participant B as Python Graph Brain
    participant M as ModelAdapter
    participant R as Java Registry/Planner

    J->>B: private-observation.v3 + agent-event.v1[]
    B->>B: ingest + dedupe + route REPLAN
    B->>M: bounded private context
    M-->>B: submit_plan tool call
    B->>B: validate plan; assign planId/stepId
    B-->>J: strategic-intent.v2 for current step
    J->>R: validate and adopt Lease
    loop action ticks
        R-->>J: at most one Action
        J->>J: world commit
        J-->>B: action-feedback.v2
    end
    B->>B: reducer sees STEP_SUCCEEDED
    alt next step exists
        B-->>J: next v2 step, no model call
    else plan complete
        B->>M: one event-driven replan call
    end
```

### 7.3 失败和重新规划

```text
ACTION_BLOCKED once + allowLocalReroute
  → Java clears stale queue prefix
  → same step replans locally once
  → feedback reason = LOCAL_REROUTE

ACTION_BLOCKED again / TARGET_LOST / PRECONDITION_CHANGED
  → Java marks step FAILED, plan REPLAN_REQUIRED
  → critical feedback + latest private observation
  → Python consumes once and calls model once
  → new revision or new plan
```

多个同 tick trigger 必须合并为一个 replan request；模型输入包含有界、有序 trigger batch，
不能为每条事件启动独立推理。

### 7.4 反射暂停与恢复

```text
ACTIVE step
  → P1/P2 override starts
  → Lease SUSPENDED + plan PAUSED feedback
  → Java reflex keeps acting; model is not called
  → override ends
      → skill canResume = true: Lease ACTIVE + plan RESUMED
      → skill canResume = false: Lease STALE + REPLAN_REQUIRED
```

### 7.5 读档、换层和关闭

- 读档：Java 创建新 `runId` 和 Session；Python 看到相同 checkpoint key、不同 run 时清除易失执行状态。
- 换层：旧 Session 关闭，新 `floorId` 形成新 checkpoint key；不得带入旧 feedback/event。
- Enemy 死亡：Session close，正在运行的模型结果被取消或抑制；active plan 不再产生新 intent。
- provider 断线：Java 继续有效 Lease、反射或本地 fallback；恢复后只发送当前 run 的最新输入。

## 8. 接口与数据契约

### 8.1 `action-feedback.v2`

wire `action_feedback.data` exact fields：

```json
{
  "feedbackVersion": "action-feedback.v2",
  "feedbackId": "feedback-guard-a-42-1",
  "decisionId": "decision-7",
  "planId": "plan-3",
  "stepId": "step-1",
  "planRevision": 0,
  "actionIndex": 2,
  "actionType": "MoveAction",
  "result": "BLOCKED",
  "reasonCode": "OCCUPIED_OR_TERRAIN_BLOCKED",
  "stepStatus": "ACTIVE",
  "planStatus": "ACTIVE",
  "beforePosition": {"x": 4, "y": 3},
  "afterPosition": {"x": 4, "y": 3},
  "selfHp": 20,
  "decisionSource": "REMOTE_AGENT",
  "overrideReason": null
}
```

规则：

- `feedbackId` 在当前 `runId/floorId/agentId` 内唯一且非空；
- remote feedback 的 `planId/stepId/planRevision` 必填；local fallback 时三者为 `null`；
- `result` 保留 `ActionResult.name()`；
- `reasonCode` 是稳定 enum，不是异常消息；
- `stepStatus ∈ {UNTRACKED, ACTIVE, SUCCEEDED, FAILED, PAUSED, CANCELLED}`；
- `planStatus ∈ {UNTRACKED, ACTIVE, PAUSED, COMPLETED, REPLAN_REQUIRED, CANCELLED}`；
- 所有位置和 HP 来自 commit 后世界；
- Python 以 envelope identity + `feedbackId` 去重。

### 8.2 `agent-event.v1`

standalone `world_event.data` 与 observation `pendingEvents[]` 使用同一 exact schema：

```json
{
  "eventVersion": "agent-event.v1",
  "eventId": "event-guard-a-43-0",
  "eventType": "PLAYER_SPOTTED",
  "logicalTick": 43,
  "relatedPosition": {"x": 7, "y": 3},
  "relatedEntityId": "player",
  "decisionId": "decision-7",
  "planId": "plan-3",
  "stepId": "step-1",
  "reasonCode": "ENTERED_PRIVATE_FOV"
}
```

事件白名单：

```text
PLAYER_SPOTTED
SOUND_HEARD
MESSAGE_RECEIVED
STEP_SUCCEEDED
STEP_FAILED
PLAN_COMPLETED
PLAN_CANCELLED
REFLEX_OVERRIDE_STARTED
REFLEX_OVERRIDE_ENDED
```

`MESSAGE_RECEIVED` 在 Phase 4 只允许 codec、inbox 和 trigger reducer 消费；生产者属于 Phase 5。

### 8.3 `private-observation.v3`

顶层字段保持 Phase 3 的 observation 结构，仅：

- `observationVersion` 改为 `private-observation.v3`；
- `pendingEvents` 元素硬切 `agent-event.v1`；
- 不增加全局地图、隐藏实体、玩家未观察 HP 或远方 Agent 状态。

旧 v2 observation 在 Phase 4 runtime 中返回 `UNKNOWN_PAYLOAD_VERSION`。

### 8.4 Java `ActionOutcome` 与 pending capture

目标接口草图：

```java
// 接口草图；名称表达稳定领域职责
public final class ActionOutcome {
    String getFeedbackId();
    String getDecisionId();
    PlanMetadata getPlanMetadata(); // local fallback 可为 null
    ActionResult getResult();
    OutcomeReason getReasonCode();
    StepStatus getStepStatus();
    PlanStatus getPlanStatus();
    // positions, HP, source, override...
}
```

`PendingAction` 必须在 execute 时冻结 `skillId/planId/stepId/revision`，collect 不得通过“当前 Lease”
反查，因为该 Lease 可能在 commit 前后被替换或失效。

### 8.5 Java skill progress seam

```java
// 接口草图
public interface TacticalSkill {
    // existing methods...
    StepProgress evaluateProgress(
            StrategicIntent intent,
            SkillProgressContext context,
            ActionOutcome committedOutcome);
}
```

`SkillProgressContext` 只能包含当前 Agent 的 committed observation、动作计数、连续 blocked 数、
局部 reroute 是否已用和 ActionQueue 状态；不能提供隐藏全局玩家事实。

默认 skill 语义：

| Skill | 成功 | 继续 | 失败 |
|---|---|---|---|
| PATROL | 到达目标 | 有可行路径且未超限 | 重复 blocked/目标不可恢复 |
| CHASE | 玩家仍可见且已相邻 | 玩家位置匹配且可继续接近 | 目标丢失/位置前提改变/重复 blocked |
| ATTACK | commit 后 `DAMAGE` | 无 | 未命中、目标丢失或非相邻 |
| GUARD | 完成一个确定性 guard commitment | commitment 尚未结束 | 前提失效或取消 |

### 8.6 Python plan state

```python
# 接口草图
class PlanStep(BaseModel):
    step_id: str
    skill: str
    parameters: dict[str, Any]
    valid_for_ticks: int
    interrupt_policy: InterruptPolicyModel
    status: StepStatus

class ActivePlan(BaseModel):
    plan_id: str
    revision: int
    steps: tuple[PlanStep, ...]  # 1..6
    current_step_index: int
    status: PlanStatus
    replan_reason: str | None
```

Graph state 新增：

- `active_plan`
- `feedback_window` / `event_window`
- `consumed_feedback_ids` / `consumed_event_ids`
- `last_run_id`
- `replan_reason`
- `last_model_trigger_tick`

所有集合使用有界 reducer，不允许默认列表无限追加。

### 8.7 `submit_plan` 工具

模型调用参数只含 steps；tool 负责：

1. exact-field 与 1..6 长度校验；
2. 每个 skill 必须出现在当前 observation capabilities；
3. 参数继续走 Pydantic 结构上限；
4. runtime 分配 plan/step ID；
5. 生成 active plan，并只输出当前 step 的 `strategic-intent.v2`。

`submit_strategic_intent` 保留并归一化为单-step plan，避免无必要破坏 provider-neutral adapter seam。

### 8.8 event-driven route

```text
ingest_inputs
  → reset_on_run_change
  → reduce_feedback_and_events
  → route_execution
      CONTINUE_CURRENT_STEP → emit_current_step
      ADVANCE_STEP          → emit_next_step
      PAUSE_OR_RESUME       → update_state_without_model
      REPLAN                → invoke_model → tools → finalize_plan
```

`REPLAN` 的稳定触发原因至少包括：

```text
COLD_START
TARGET_SPOTTED
SOUND_HEARD
MESSAGE_RECEIVED
STEP_FAILED
PLAN_COMPLETED
PLAN_CANCELLED
PRECONDITION_CHANGED
```

同一 observation 中多个原因按固定优先级合并为一次模型调用，并把全部原因作为有界结构化输入。

### 8.9 runtime 配置

新增配置只用于有界执行，不包含 provider secret：

```text
max_plan_steps = 6
max_feedback_window = 32
max_event_window = 32
max_consumed_ids = 64
max_plan_revisions = 32
max_local_reroutes_per_step = 1
```

CLI 参数、环境变量或 config 名称不得含开发阶段编号。

## 9. 逐文件变更计划

| 文件 | 新建/修改 | 责任 | 关键变更 | 不应包含 |
|---|---|---|---|---|
| `byog/Core/Game.java` | 修改 | 运行身份 | worldId 先于 Session/observation | provider 启动 |
| `byog/Action/ActionOutcome.java` | 修改 | commit 后反馈 | plan/step/status/reason/feedbackId | 模型判断 |
| `byog/AI/TacticalSkill.java` | 修改 | skill seam | progress evaluator | future Action list |
| `byog/AI/BuiltinTacticalSkills.java` | 修改 | built-in progress | 四个固定 progress policy | 隐藏世界读取 |
| `byog/AI/SkillProgressContext.java` | 新建 | 有限 progress 输入 | committed private facts + counters | Player 引用 |
| `byog/AI/StepProgress.java` | 新建 | progress 结果 | status/reason/reroute | 开发阶段命名 |
| `byog/AI/IntentLease.java` | 修改 | pause/resume/terminal | 可观察 transition | Python state |
| `byog/AI/IntentArbiter.java` | 修改 | 控制权 | transition result 与 replan disposition | 网络等待 |
| `byog/Entity/Enemy.java` | 修改 | 协调执行 | capture metadata、progress、trigger、heartbeat 分离 | 第二 loop |
| `byog/Bridge/AgentProtocol.java` | 修改 | Java DTO | feedback v2/event v1/observation v3 | provider 字段 |
| `byog/Bridge/AgentProtocolCodec.java` | 修改 | strict codec | exact fields、版本、enum | 宽松双读 |
| `byog/Bridge/AgentSession.java` | 修改 | 传输 | 新 DTO、event ID/合并、critical 语义 | Agent 规划 |
| `byog/Trace/AgentTrace.java` | 修改 | gameplay trace | v4 plan/progress/replan 字段 | raw prompt |
| `agent/python/dungeonmind_agent/protocol.py` | 修改 | Python codec | 同步三种 payload | 旧版 fallback |
| `agent/python/dungeonmind_agent/graph/state.py` | 修改 | typed state | plan/inbox/cursors | 无限历史 |
| `agent/python/dungeonmind_agent/graph/tools.py` | 修改 | plan tool | submit/normalize plan | 任意 Action |
| `agent/python/dungeonmind_agent/graph/workflow.py` | 修改 | graph route | reducer/advance/replan | 每次都 model |
| `agent/python/dungeonmind_agent/brain/graph_agent.py` | 修改 | async inbox | world_event、commit consumption | Socket IO |
| `agent/python/dungeonmind_agent/model/adapter.py` | 修改 | provider-neutral input | bounded execution context | SDK/secret |
| `agent/python/dungeonmind_agent/observability.py` | 修改 | model trace | v2 trigger/plan fields | sensitive text |
| `agent/contract/README.md` | 修改 | wire 入口 | 版本/字段/迁移 | 实现细节 |
| `agent/contract/fixtures/*` | 修改/新建 | 共享样例 | valid/invalid v3/v2/v1 | 运行 trace |
| `byog/Test/ExecutionFeedbackContractTest.java` | 新建 | Java feedback | correlation/status/reason | GUI |
| `byog/Test/PlanProgressTest.java` | 新建 | progress policy | skill terminal/reroute | 网络 |
| `byog/Test/EventDrivenAgentIntegrationTest.java` | 新建 | 端到端 | multi-step/replan/non-blocking | 真实 provider |
| `agent/python/tests/test_execution_loop.py` | 新建 | Python reducer | dedupe/advance/reset | 真实网络 |
| `PHASE_4_COMPLETION.md` | 最后新建 | 关闭证据 | 实际命令/结果/偏差 | 计划冒充证据 |

## 10. 实施顺序

### Step 4.1：固定基线并修复交互式运行身份

- 输入：Phase 3 Completion、当前 Game new/load/floor 路径。
- 改动：先生成/恢复 `worldId`，再创建 run/Session/初始 observation；增加 enabled Bridge 的 headless
  composition 测试。
- 验证：Java 全量编译、Game identity/配置测试、现有核心回归。
- artifact：真实交互式路径可以安全建立 Session 的前置基线。

### Step 4.2：硬切反馈、事件与 observation contract

- 输入：现有 DTO、codec、fixtures。
- 改动：Java/Python 同次切 feedback v2、event v1、observation v3；旧版严格拒绝。
- 验证：双语言 codec、正反 fixtures、frame/enum/identity tests。
- artifact：后续 progress 和 inbox 可依赖的稳定 wire contract。

### Step 4.3：建立 Java 权威 progress 与关联冻结

- 输入：`ActionOutcome`、`TacticalSkillRegistry`、commit barrier。
- 改动：execute 冻结 plan metadata；skill progress evaluator 在 collect 后给出状态和 reason；一次 reroute
  使用有界 counter。
- 验证：四个 skill progress、目标丢失、blocked、local fallback null plan、commit 后位置。
- artifact：Java 可产生完整结构化 feedback。

### Step 4.4：建立 Python 有界 inbox、去重和 run reset

- 输入：新 feedback/event codec、checkpoint key。
- 改动：Graph brain 接收 feedback/event；线程安全有界 inbox；两阶段消费；新 run 清易失状态。
- 验证：重复 standalone/pending event、取消不吞输入、两 Agent 隔离、读档/换层冷启动。
- artifact：可靠的同 Agent execution input。

### Step 4.5：建立有界 plan contract 与当前-step emitter

- 输入：v2 intent、plan metadata、registry skill list。
- 改动：Pydantic plan/step、`submit_plan`、单-step 归一化、runtime-owned ID、step advance。
- 验证：1..6 边界、未知 skill、非法参数、ID 稳定、future steps 不上 wire。
- artifact：可跨多个 action tick 的 active plan。

### Step 4.6：改造 graph 为事件驱动执行/重规划

- 输入：active plan、inbox snapshot、scheduler。
- 改动：feedback reducer、route、无模型 advance、trigger coalescing、heartbeat 与推理解耦。
- 验证：step success 不调用模型；失败只调用一次；无事件不调用；预算和 cancel 不变。
- artifact：持续感知—行动—反馈闭环。

### Step 4.7：固定反射暂停、恢复、取消与局部修正

- 输入：Lease state、interrupt policy、progress result。
- 改动：反射 transition 产生结构化反馈；可恢复继续原 step；不可恢复 replan；reroute 一次上限。
- 验证：P1/P2 覆盖、queue 保留/清理、重复 blocked、目标丢失、decision 关联。
- artifact：快脑和慢脑职责边界可验证。

### Step 4.8：升级 trace 并建立固定场景证据

- 输入：完整闭环与现有 trace sink。
- 改动：`agent-runtime.trace.v4`、`agent-model.trace.v2`、固定多-step/blocked/reflex 场景。
- 验证：correlation、字段白名单、无 raw prompt、canonical determinism、无 sleep。
- artifact：可复查的 Phase 4 行为证据。

### Step 4.9：生产接线、回归、provider 提醒与交接

- 输入：全部局部闸门。
- 改动：真实 Python/TCP scripted integration、默认配置回归、文档同步、Completion。
- 验证：Python 全测、Java 全量编译、Agent suite、integration、Socket、Core regression；provider smoke
  仅在 Builder 配置自己的 adapter/API 后执行。
- artifact：`PHASE_4_COMPLETION.md` 与 Phase 5 稳定输入。

## 11. 测试与验收矩阵

### 11.1 测试架构摘要

| 项目 | 本阶段决定 |
|---|---|
| 单一 deterministic Java 入口 | `AgentRuntimeTestSuite` 直接列 leaf tests，不嵌套 Suite |
| Python 入口 | `unittest discover -s agent/python/tests -v` |
| integration 入口 | `EventDrivenAgentIntegrationTest`，真实 Python/TCP + scripted adapter |
| shared harness | 复用 `EncounterHarness`、fake monotonic clock、真实 `AiTickLoop` |
| production seam | `Enemy.poll/execute/collect`、真实 Session/codec/registry |
| golden | 只用于 wire fixtures；游戏行为断言不变量与关联 |
| 排除项 | GUI、默认存档、真实 provider、墙钟 sleep、付费网络 |

### 11.2 验收矩阵

| Test ID | 场景 | 断言 | 自动/人工 | 需求 |
|---|---|---|---|---|
| RUN-01 | Bridge enabled 新世界 | worldId 非空后才建 Session | 自动 | INV-07 |
| FB-01 | remote Move commit | feedback 带同一 plan/step 和 commit 后位置 | 自动 | INV-04/08 |
| FB-02 | local fallback | plan 字段为 null、状态 UNTRACKED | 自动 | INV-06 |
| FB-03 | 旧 feedback | 双语言 `UNKNOWN_PAYLOAD_VERSION` | 自动 | INV-06 |
| EVT-01 | 同一 event 两种交付 | eventId 去重，只消费一次 | 自动 | INV-08 |
| EVT-02 | 新 tick 同类事件 | 新 eventId 被视为新输入 | 自动 | INV-08 |
| EVT-03 | injected SOUND_HEARD | 触发一次 replan，不泄露隐藏状态 | 自动 | INV-02 |
| EVT-04 | injected MESSAGE_RECEIVED | reducer 接受；没有生产传播副作用 | 自动 | INV-03 |
| PLAN-01 | 两-step scripted plan | 跨多个 action tick，第二 step 无模型推进 | 自动 | INV-05/09 |
| PLAN-02 | plan 长度 0/7 | tool 明确拒绝 | 自动 | INV-06 |
| PLAN-03 | future step | Java wire 只看到当前 step | 自动 | INV-04 |
| PLAN-04 | 首次 blocked | 只局部 reroute，不调用模型 | 自动 | INV-05 |
| PLAN-05 | 重复 blocked | step FAILED，合并为一次 replan | 自动 | INV-07/09 |
| PLAN-06 | attack DAMAGE/BLOCKED | 成功/失败进入下一决策输入 | 自动 | INV-04/08 |
| PLAN-07 | target lost | 旧 step 不继续，reason 可追踪 | 自动 | INV-02/07 |
| REFLEX-01 | P2 开始/结束可恢复 | plan PAUSED→ACTIVE，原关联保留 | 自动 | INV-05/08 |
| REFLEX-02 | 覆盖后前提失效 | plan REPLAN_REQUIRED，不恢复旧 queue | 自动 | INV-04/07 |
| ID-01 | 两 Agent 交错 feedback | plan/inbox/cursor 不串线 | 自动 | INV-01 |
| ID-02 | 同楼层读档新 run | 旧 active plan/feedback 不复用 | 自动 | INV-07 |
| ID-03 | 换层 | 新 key 冷启动 | 自动 | INV-01/07 |
| BUDGET-01 | 无 event、plan active | heartbeat 不产生模型调用 | 自动 | INV-07/09 |
| BUDGET-02 | 多 trigger 同 observation | scheduler 只接收一次模型调用 | 自动 | INV-07 |
| FAIL-01 | runtime 慢/断线 | game tick、reflex、fallback 继续 | 自动 | INV-07 |
| TRACE-01 | 完整闭环 | event→plan→step→action→feedback→replan 可关联 | 自动 | INV-08 |
| TRACE-02 | model trace | 无 raw prompt/key/response/reasoning | 自动 | INV-02/08 |
| PLAY-01 | 固定 blocked 遭遇 | 玩家可观察敌人停止撞墙并采取新计划 | 人工 | INV-09 |
| PROVIDER-01 | Builder 的真实 adapter | tool round、plan、feedback/replan 跑通 | 延后 | INV-08 |

### 11.3 阶段闸门原则

- deterministic tests 不访问网络、GUI、默认存档或真实 provider；
- integration 使用有界进程等待和清理，禁止 `Thread.sleep()` 猜时序；
- 一个 Test ID 可由紧凑测试覆盖，但失败消息必须可检索；
- 不用完整逐步轨迹锁死寻路或随机伤害；
- 真实 provider smoke 未执行时必须在 Completion 明确列为未验证，不能写“兼容所有 provider”。

### 11.4 计划验证命令

这些是实现阶段要执行的目标命令，不是本 Spec 的完成证据：

```powershell
$env:PYTHONPATH = (Resolve-Path agent/python).Path
& agent/python/.venv/Scripts/python.exe -m unittest discover `
    -s agent/python/tests -v

$javaSources = Get-ChildItem byog -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
javac -encoding UTF-8 -cp "..\library-sp18\javalib\*" -d out $javaSources

java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.AgentRuntimeTestSuite

java "-Dfile.encoding=UTF-8" `
    "-Ddungeonmind.python=$((Resolve-Path agent/python/.venv/Scripts/python.exe).Path)" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.EventDrivenAgentIntegrationTest

java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Bridge.SocketTransportTest

java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.CoreGameplayRegressionSuite
```

## 12. Observability 与运行证据

### 12.1 Java `agent-runtime.trace.v4`

新增稳定字段：

```text
feedbackId
eventId
reasonCode
stepStatus
planStatus
replanTrigger
localRerouteCount
```

新增事件至少包括：

```text
STEP_PROGRESS_EVALUATED
PLAN_PAUSED
PLAN_RESUMED
PLAN_REPLAN_REQUIRED
EVENT_ENQUEUED
EVENT_CONSUMED
```

trace 仍使用 logical tick，不把 wall-clock、线程名或 Socket 细节纳入 canonical 行为。

### 12.2 Python `agent-model.trace.v2`

白名单新增：

```text
feedbackId
eventId
planRevision
stepStatus
planStatus
replanTrigger
consumedInputCount
```

模型文本、prompt、response、API endpoint、key 和自由 reasoning 不得进入 trace。

### 12.3 Completion 必须记录

- 分支、起止 commit、环境版本；
- 实际执行的命令、测试数、结果和耗时；
- 至少一条 multi-step、blocked replan、reflex resume 的关联证据；
- 默认 Bridge disabled 的回归结果；
- 是否配置/执行真实 provider smoke；
- 未实现的声音 producer 和 Phase 5 message producer；
- 任何偏离本 Spec 的决定及原因。

## 13. 失败处理、兼容与迁移

### 13.1 降级路径

| 失败 | 行为 |
|---|---|
| provider 未配置 | runtime model 模式 ready 前失败；Java 本地 AI 不受影响 |
| model timeout/cancel | 不消费 input；Java 继续 Lease/reflex/fallback |
| plan tool 非法 | graph 明确 reject，不输出半计划 |
| Java 拒绝当前 step | 不安装 Lease；保留原 Lease/fallback，trace rejection |
| feedback 关键入队失败 | Session degraded/reconnect；不能静默丢失 |
| event 低优先级拥塞 | pending snapshot 保留最新可合并事件；记录 drop |
| checkpoint 不可用 | 同 run 内存冷启动；不能恢复旧执行状态 |
| repeated blocked | 局部修正用尽后 REPLAN_REQUIRED |

### 13.2 Wire 硬切

本阶段不兼容：

- `private-observation.v2`
- 无 `feedbackVersion` 的旧 action feedback
- 无 `eventVersion/eventId` 的旧 world event

Java/Python 任一端未同次升级时应 fail-fast，不做字段猜测或默认补齐。

### 13.3 存档与 checkpoint

- Java 存档格式不因 Phase 4 增加 Session/plan 字段；
- SQLite checkpoint 可以保留有界图状态，但 active execution 受 `runId` reset；
- 缺失 checkpoint 合法冷启动；
- 不删除用户已有 checkpoint DB，不提交本地 DB。

### 13.4 默认行为

- `agent.bridge.enabled=false` 保持；
- 默认交互式游戏继续使用本地双速 AI；
- 启用 Bridge 但 runtime 不可用时游戏仍继续；
- 不自动启动用户的 Python server 或 provider 进程。

### 13.5 半完成状态防护

以下任一出现时不得写 Phase 4 Accepted：

- wire 一端已切版本、另一端仍宽松读旧版；
- plan 能创建但 feedback 不会推进或失败；
- heartbeat 仍固定触发模型；
- reflex 覆盖丢失原 plan/step 关联；
- runId 改变后恢复旧 active step；
- 只在 deterministic brain 记录列表，没有真实 Graph reducer；
- 用 provider 未配置掩盖生产 identity 或 Session 接线失败。

## 14. 风险与停止条件

### 14.1 主要风险

| 风险 | 后果 | 缓解 |
|---|---|---|
| Java/Python 双方各自判断 step 成败 | split brain | Java status 权威，Python 只 reduce |
| collect 时反查当前 Lease | plan/step 错配 | execute 时冻结 metadata |
| event 与 pending duplicate | 重复付费调用 | stable eventId + bounded dedupe |
| plan future steps 上 wire | 绕过当前前提 | 当前 step only |
| heartbeat 仍触发模型 | 调用滥用 | transport heartbeat 与 decision trigger 分离 |
| checkpoint 恢复 active step | 旧运行污染 | runId reset |
| 声音/message 被写成已实现玩法 | 虚假完成 | producer 明确 out of scope |
| provider 细节渗入 core graph | 后续难替换 | ModelAdapter boundary |

### 14.2 停止条件

发现以下情况时停止扩大实现并请求 Builder 决定：

- 必须新增全局共享 Agent context 才能完成某场景；
- 必须让 Python 直接读取 Java world 或返回 Action；
- 需要把 Session/plan 写入玩家存档；
- 需要提前实现 Phase 5 消息传播或 Phase 7 复杂技能；
- 所选 provider 要求修改稳定 wire contract，而不是只实现 adapter；
- 交互式 identity 缺陷无法在不改变世界存档语义的情况下修复。

## 15. Definition of Done

- [ ] Step 4.1 的 worldId/Session 顺序缺陷已修复并有回归。
- [ ] feedback v2、event v1、observation v3 在 Java/Python/fixtures 同次硬切。
- [ ] 旧 payload 版本被稳定拒绝。
- [ ] `ActionOutcome` 在 execute 时冻结 plan/step，在 commit 后补全权威结果。
- [ ] 四个 built-in skill 都有有界 progress policy。
- [ ] plan 至多 6 step，future steps 不进入 Java wire。
- [ ] scripted adapter 通过真实 Graph 创建 multi-step plan。
- [ ] step 成功能无模型推进下一 step。
- [ ] blocked/target lost 能产生一次有原因的 replan。
- [ ] heartbeat 不触发模型。
- [ ] feedback/event 可以区分新输入和已消费输入。
- [ ] 反射暂停/恢复/取消保留原 plan/step/decision 关联。
- [ ] 新 run、换层和死亡不会复用旧执行状态。
- [ ] 游戏线程在慢、断线和队列饱和时不等待 Agent。
- [ ] 单一 deterministic Suite 没有嵌套或重复计数。
- [ ] tests 不用 GUI、默认存档、真实付费 provider 或 `Thread.sleep()`。
- [ ] Java 与 Python trace 可关联 event→plan→step→action→feedback→replan。
- [ ] trace 和日志没有 key、raw prompt、raw response 或自由 reasoning。
- [ ] 默认 Bridge disabled 的核心游戏回归通过。
- [ ] 固定多-step/blocked/reflex 场景通过。
- [ ] 实际命令、结果、耗时、偏差写入 `PHASE_4_COMPLETION.md`。
- [ ] 真实 provider smoke 若未执行，Completion 明确标为等待 Builder 配置。

## 16. 下一阶段交接

### 16.1 Phase 5 可以依赖

- `agent-event.v1` 的 stable eventId、dedupe 和 `MESSAGE_RECEIVED` trigger 消费入口；
- `action-feedback.v2` 的 plan/step/status/reason 关联；
- 有界 active plan、step advance 和 replan reducer；
- 反射 pause/resume/cancel 与局部 reroute 规则；
- 无固定频率模型调用的 event-driven scheduler 路径；
- `agent-runtime.trace.v4` 与 `agent-model.trace.v2` 的关联字段；
- deterministic/integration harness 和 Completion 证据。

### 16.2 Phase 5 不得假设

- `MESSAGE_RECEIVED` 已有世界内 producer、传播距离或阻断规则；
- 声音系统已经完整产生 `SOUND_HEARD`；
- 多个 Agent 可以共享 plan、checkpoint、messages 或 memory；
- 真实 provider 已配置或已证明兼容；
- plan 可以跨 floor/run 恢复；
- 模型可以绕过 Java validator 或 progress evaluator。

### 16.3 Phase 5 首要入口

在不改变 Agent 独立状态和 Java 权威的前提下，把一个可阻断的世界内通信动作转成
`MESSAGE_RECEIVED` event，验证只有实际听见/收到的敌人才把消息写入自己的 inbox 并触发本地 replan。

## 附录 A：Spec 自检

- [x] 已完整读取 Intent、Roadmap、跨阶段架构和 Phase 3 Completion。
- [x] 已审计分支、HEAD、工作树、配置、Java/Python 代码和测试入口。
- [x] 已区分当前事实、推断、建议和拟议目标行为。
- [x] 已记录 AI 架构文档过时段落与 new-world identity 缺陷。
- [x] 已给出数据所有权、版本、字段、迁移、失败和逐文件计划。
- [x] Spec Step 4.1–4.9 与 Build Guide 4.1–4.9 一一映射。
- [x] 已避免在拟议源码标识符、测试名和配置键中使用开发阶段编号。
- [x] 已把 provider 配置和真实 smoke 延后，并保留明确提醒。
- [x] 已把声音/message producer 的未实现事实写清楚。
- [x] 已定义 leaf-only gate、独立 integration 和 canonical 边界。
