# Phase 2 Spec：确定性端到端桥接

## 0. 元数据

- **Phase**：2
- **状态**：Draft — ready for Builder review
- **作者**：Codex（持续 Advisor）
- **创建日期**：2026-07-22
- **基线分支**：`ai-enemis`
- **基线 commit**：Phase 1.5 完成后最新 commit
- **前一阶段 Completion**：`PHASE_1_COMPLETION.md`、`PHASE_1DOT5_COMPLETION.md`
- **上位文档**：`PROJECT_INTENT_zh-CN.md`、`DEVELOPMENT_ROADMAP.md`

## 1. 必读输入与审计范围

本 Spec 基于以下实际输入：

- `PROJECT_INTENT_zh-CN.md`（§3 Agent 身份与边界、§6 Agent MVP 完成定义、§9 异步事件驱动）
- `DEVELOPMENT_ROADMAP.md`（阶段 2 定义、INV-04/05/06/07、阶段间交付链）
- `PHASE_1_COMPLETION.md`（Phase 1 交付物、已知偏差、Phase 2 可依赖项与不得假设项）
- `PHASE_1DOT5_COMPLETION.md`（FOV 可视化基础设施、`cachedVisibleMask`）
- `PHASE_0_COMPLETION.md`（trace seam 设计、baseline 体系）
- `PHASE_SPEC_TEMPLATE.md`
- `byog/Core/Game.java`——主游戏循环，当前同步调用 `enemy.updateAI()`（[Game.java:116-120](byog/Core/Game.java#L116-L120)）
- `byog/Entity/Enemy.java`——`updateAI()` 双路径结构（perception → brain.thinkFromObservation → planner → action execution）（[Enemy.java:75-200](byog/Entity/Enemy.java#L75-L200)）
- `byog/Perception/ObservationEnvelope.java`——私有感知结果 DTO，包含 `visibleMask`、`visibleEntities`、`heardEvents`、`world` 引用（[ObservationEnvelope.java](byog/Perception/ObservationEnvelope.java)）
- `byog/Perception/VisibleEntity.java`——`EntityType` 枚举、`position`、`visibleHp`、`agentId`
- `byog/Perception/HeardEvent.java`——`SoundType` 枚举、`sourcePosition`、`turn`
- `byog/AI/EnemyBrain.java`——`think(GameStateSnapshot)` + `thinkFromObservation(ObservationEnvelope)` default 方法
- `byog/AI/StrategicIntent.java`——`Goal` 枚举（7 值）、`Strategy` 枚举（6 值）、`targetPosition`、`confidence`、`targetRoom`
- `byog/AI/ClassicalPlanner.java`——`translate(StrategicIntent, ...)` → `List<Action>`（支持 CHASE/ATTACK/AMBUSH/GUARD/PATROL）
- `byog/Action/Action.java`——`Action` 接口 + `ActionResult` 枚举（SUCCESS/BLOCKED/INTERRUPTED/COMPLETED/DAMAGE）
- `byog/Action/ActionQueue.java`——FIFO 队列，`needRefill()` 阈值 2
- `byog/Trace/AgentTrace.java`——`phase1.trace.v1` schema，`EventType` 5 种，`Context/Sink/InMemorySink`
- `byog/IO/GameConfig.java`——配置系统，`getBoolean()` 优雅降级模式
- `byog/IO/GameSaveData.java`——存档结构，含 `extraData: Map<String, Serializable>`（可扩展）
- 仓库内**无 Python 文件**（`**/*.py` 搜索结果为空）——Python 侧全部为 greenfield

## 2. 阶段目标与成功定义

Phase 1 让每个敌人拥有了私有感知——Guard A 能看到玩家，Guard B 被墙挡住看不到。但敌人仍然用硬编码的 `RuleBasedBrain` 做决策——看到就追，看不到就巡逻，毫无策略可言。

Phase 2 的目标是：**在接入真正的 LLM 之前，先打通 Java → Python → Java 的完整通信链路，用确定性假 runtime 验证异步、超时、过期丢弃等基础设施是正确的。**

可以把这个阶段想象成"先铺铁轨，再跑火车"：Python 侧不接任何模型，只回复固定的硬编码决策（类似一个 echo server），但这趟从 Java 发出 observation、经过 Python 处理、返回结构化 intent、最终驱动敌人行动的完整往返，必须能在游戏不卡顿、Python 崩溃不崩游戏、旧决策不污染当前状态的前提下跑通。

成功后，Phase 3 只需要把 Python 侧的"固定回复"替换成"LLM 推理"，通信基础设施无需再改。

## 3. 起始事实

### 3.1 Game loop 当前结构（同步阻塞点）

主循环在 [Game.java:108-129](byog/Core/Game.java#L108-L129)：

```java
if (currentState == GameState.PLAYING) {
    player.updateCharge();
    player.updateHitTimer();
    for (Entity e : entityMgr.getAllEntities()) {
        if (e instanceof Enemy enemy && e.isAlive()) {
            enemy.updateAI(world, entityMgr, player);  // ← 同步调用
        }
    }
    entityMgr.flushPendingChanges();
    entityMgr.removeDeadEntities();
}
```

当前每个 enemy 的 `updateAI()` 是帧内同步完成的——perception → brain → planner → action 全部在一个调用栈里跑完。如果 brain 需要等 Python 回复，这个循环就会被阻塞，游戏画面冻结。

Phase 2 必须打破这个同步依赖：`updateAI()` 仍然返回（带着 fallback 行为），Python 回复在未来的某一帧被消费。

### 3.2 Enemy.updateAI() 的分层结构

当前结构（[Enemy.java:75-200](byog/Entity/Enemy.java#L75-L200)）已经是良好分层：

```
1. 感知层: PerceptionSystem.computeObservation() → ObservationEnvelope
2. 决策层: brain.thinkFromObservation(observation) → StrategicIntent
3. 规划层: ClassicalPlanner.translate(intent, ...) → List<Action>
4. 执行层: actionQueue.poll() → action.execute() → ActionResult
```

Phase 2 只需要在"决策层"插入一个异步 Brain 实现——感知层不变，规划层不变，执行层不变。

### 3.3 EnemyBrain 接口当前状态

[EnemyBrain.java](byog/AI/EnemyBrain.java) 只有两个方法：

```java
StrategicIntent think(GameStateSnapshot state);  // legacy

default StrategicIntent thinkFromObservation(ObservationEnvelope obs) {
    throw new UnsupportedOperationException(...);
}
```

`thinkFromObservation` 的返回值类型是 `StrategicIntent`——同步返回。Phase 2 的新 Brain 实现需要打破这个契约：它**立即**返回一个 fallback intent（PATROL），真正的决策**异步**送达后通过另一条路径注入。

### 3.4 ObservationEnvelope 的序列化挑战

`ObservationEnvelope` 的字段（[ObservationEnvelope.java](byog/Perception/ObservationEnvelope.java)）：

| 字段 | 类型 | 序列化难度 |
|------|------|-----------|
| `runId` | String | 简单 |
| `floorId` | int | 简单 |
| `agentId` | String | 简单 |
| `observationSeq` | long | 简单 |
| `observedAtTurn` | long | 简单 |
| `selfPosition` | Position | 简单 |
| `selfHp` | int | 简单 |
| `visibleMask` | boolean[][] | 完整 world 尺寸（如 80×30 = 2400 元素），需压缩 |
| `visibleEntities` | List\<VisibleEntity\> | 每个实体有 type/position/visibleHp/agentId |
| `heardEvents` | List\<HeardEvent\> | SoundType/sourcePosition/turn |
| `world` | TETile[][] | **不能**直接序列化（TETile 引用）；需要提取可见 tile 的类型信息 |

Python 侧需要一个能理解"地图上有墙、地板、楼梯"的世界模型。最简单的方式：**对 `visibleMask` 中标记为可见的 tile，附加其 tile 类型（字符表示）**。这样 Python 知道的不会比 Java 允许的更多。

### 3.5 StrategicIntent 的（反）序列化

Python 需要返回一个等价物。当前 `StrategicIntent` 结构（[StrategicIntent.java](byog/AI/StrategicIntent.java)）：

```java
Goal goal;           // INTERCEPT_PLAYER, GUARD, PATROL, CHASE, AMBUSH, RETREAT, ATTACK_PLAYER
Strategy strategy;   // INTERCEPT, AMBUSH, PATROL, GUARD, CHASE, ATTACK
Position targetPosition;
double confidence;   // 0-1
int targetRoom;      // -1 表示未指定
```

但 Python 不应该返回裸的 Java 枚举字符串——需要定义**语言中立的 schema**，由 Java 侧解析和校验。

### 3.6 当前可复用的基础设施

- `AgentTrace.Context/Sink/InMemorySink`：可扩展新事件类型，记录桥接生命周期。
- `GameConfig.getBoolean()` 模式：优雅降级，配置缺失不崩溃。
- `EntityManager.flushPendingChanges()`：已有延迟更新机制，对异步注入动作有参考价值。
- `Enemy.cachedVisibleMask`：Phase 1.5 引入，证明"缓存跨帧数据"的模式可行。
- `ActionQueue` 的 `needRefill()` 阈值 2：即使 Python 决策延迟几帧，队列中的动作仍能执行。

### 3.7 Phase 1 遗留问题与本阶段的关系

| Phase 1 遗留问题 | Phase 2 处理方式 |
|-----------------|-----------------|
| `perceptionEnabled` 在生产路径未启用 | **解决**：Phase 2 让生产路径使用 bridge brain（内部启用私有感知） |
| 存档 agentId 持久化未写自动化测试 | **保留**：Phase 2 测试可顺便覆盖，但不是核心目标 |
| PerceptionSystemTest 未纳入 Suite | **解决**：统一加入 Phase2TestSuite |
| `GameStateSnapshot` 未移除 | **保留**：仍为 RuleBasedBrain 兼容保留，Phase 3+ 移除 |
| HeardEvent 列表始终为空 | **保留**：Phase 5 实现听觉传播 |

### 3.8 Phase 1.5 与本阶段的关系

Phase 1.5 的 FOV 可视化（`FLOOR_FOV` tile、`debugShowEnemyFov` 配置、`cachedVisibleMask`）是独立 debug 基础设施，Phase 2 不依赖也不修改它。

### 3.9 构建与测试基线

- 编译命令：`$javaSources = Get-ChildItem byog -Recurse -Filter *.java | ForEach-Object { $_.FullName }; javac -encoding UTF-8 -cp "..\library-sp18\javalib\*" -d out $javaSources`
- 测试命令：`java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" org.junit.runner.JUnitCore byog.Test.Phase1TestSuite`（27 tests，ALL PASS）
- Phase 2 不要求 Phase 1 的全部 27 个测试保持绿色——Phase 2 会修改 `Enemy`，这些测试会因构造签名变化而需要适配。Phase 2 的 `Phase2TestSuite` 聚合 Phase 2 测试 + 适配后的 Phase 1 测试。

## 4. 需求追踪

| Requirement | Phase 2 的处理 | 验收证据 |
|-------------|----------------|----------|
| INV-01 独立身份 | Bridge 使用 `agentId` 关联请求/响应，每敌人独立 mailbox | P2-T01：不同敌人的请求不串线 |
| INV-02 有限知识 | Bridge 只发送 `ObservationEnvelope` 内容（可见 tile 类型 + 可见实体），不发送全量 world | P2-T08：Python 收到的数据不含不可见 tile 的类型 |
| INV-03 世界内通信 | 不在本阶段实现 | Out of Scope 检查 |
| INV-04 Java 权威 | Python 返回的 `AgentDecision` 经 Java 校验（agentId、observationSeq、goal/strategy 合法性、target 可达性）后才生效 | P2-T04：非法 goal 被拒绝，fallback 到 PATROL |
| INV-05 分层控制 | `PythonBridgeBrain` 实现 `EnemyBrain`，Python 只产出 `StrategicIntent`，规划与执行仍在 Java | P2-T05：Python 决策 → ClassicalPlanner.translate() → Action 执行 |
| INV-06 严格契约 | 请求含 schema 版本 + decisionId + observationSeq；响应含 decisionId + observationSeq；Java 校验匹配后生效 | P2-T06：observationSeq 不匹配的响应被丢弃 |
| INV-07 异步时效 | 每敌人最多一个 in-flight 请求；超时后 fallback；延迟返回的旧决策被丢弃；游戏循环不被阻塞 | P2-T03：游戏仍以 ~60 FPS 运行 |
| INV-08 可追踪评估 | AgentTrace 新增 `BRIDGE_REQUEST_SENT`、`BRIDGE_RESPONSE_RECEIVED`、`BRIDGE_TIMEOUT`、`BRIDGE_FALLBACK` 事件 | P2-T07：trace 可关联请求/响应/fallback |
| INV-09 玩法价值 | 不在本阶段验证 | Phase 3+ |

## 5. 范围与非目标

### 5.1 In Scope

- **通信协议设计**：JSON schema 定义（`AgentRequest` / `AgentDecision`），含版本字段。
- **Java 侧 `PythonBridgeBrain`**：实现 `EnemyBrain` 接口，异步发送 observation，同步返回 fallback intent。
- **Java 侧 `AgentMailbox`**：每敌人一个 mailbox，管理 in-flight 请求、超时、响应注入。
- **Java 侧 `DecisionValidator`**：校验 Python 响应（agentId 匹配、observationSeq 匹配、goal/strategy 合法、target 可达）。
- **Python 侧 fake runtime**：本地 socket server，接收 JSON 请求，返回硬编码的 `AgentDecision`。
- **Python 侧健康检查**：Java 在启动时验证 Python 进程可达。
- **Game loop 集成**：每帧遍历 enemies → 消费已就绪的 Python 响应 → 注入意图 → 发起新请求。
- **Trace 扩展**：新增桥接生命周期事件类型。
- **Game.java 生产路径切换**：Enemy 使用 `PythonBridgeBrain`（内部启用私有感知），玩家可见 FOV 可视化。
- **Headless 测试**：Phase 2 harness 与 contract tests（P2-T01 ~ P2-T09）。
- **配置文件**：`enemy.bridgeTimeoutMs`、`enemy.bridgeEnabled` 等。

### 5.2 Out of Scope

- LLM 推理、LangGraph、Tool Calling（Phase 3）。
- 真实模型调用、token 计量（Phase 3）。
- checkpoint 持久化（Phase 3）。
- `ActionOutcome` 反馈写回 Agent 状态（Phase 4）。
- 事件驱动的重规划（Phase 4）。
- 听觉传播、记忆系统、通信（Phase 5+）。
- Python 侧的 checkpoint 或状态管理。
- 跨进程 schema 的向前兼容策略（Phase 3 再定义）。
- `GameStateSnapshot` 的移除（Phase 3+）。
- `INTERCEPT_PLAYER` 和 `RETREAT` 的 planner 实现（Phase 4+）。

## 6. 已锁定决定、假设与待决定项

### 6.1 已锁定决定

#### D2-01：传输方式使用 localhost TCP socket

Java 侧作为 TCP client，Python 侧作为 TCP server。选择理由：
- 启动顺序可控：Python server 先启动，Java 连接。
- 跨平台一致：不依赖 OS 特定的 pipe 机制。
- 开发友好：可以用 telnet/netcat 调试 Python 侧。
- 失败隔离：Python 崩溃时 TCP 连接断开，Java 可检测并 fallback。

端口号：通过配置文件指定，默认 `9876`。端口冲突时 Python 启动失败，Java 检测到连接失败 → fallback。

#### D2-02：请求/响应格式使用 JSON

与 AgentTrace 的 canonical JSON 一致，选择 JSON 的理由：
- 人类可读，调试友好。
- Java 侧已有 JSON 序列化经验（AgentTrace）。
- Python 侧 `json` 标准库即可处理，零依赖。

不使用 protobuf/flatbuffers/msgpack 等二进制格式——Phase 2 的吞吐量极低（每 enemy 每秒最多 2-3 个请求），JSON 开销可忽略。

#### D2-03：通信协议 Schema

**请求（Java → Python）**：

```json
{
  "schemaVersion": "phase2.request.v1",
  "decisionId": "uuid-string",
  "runId": "string",
  "floorId": 1,
  "agentId": "guard-a",
  "observationSeq": 5,
  "observedAtTurn": 42,
  "self": {
    "position": {"x": 9, "y": 2},
    "hp": 20
  },
  "visibleTiles": [
    {"x": 9, "y": 2, "type": "FLOOR"},
    {"x": 10, "y": 2, "type": "FLOOR"},
    {"x": 8, "y": 3, "type": "WALL"}
  ],
  "visibleEntities": [
    {"type": "PLAYER", "position": {"x": 3, "y": 2}, "visibleHp": 100}
  ],
  "heardEvents": [],
  "config": {
    "sightRange": 7,
    "attackDamage": 10
  }
}
```

字段说明：
- `decisionId`：UUID，关联请求和响应。
- `visibleTiles[].type`：`"FLOOR"` / `"WALL"` / `"STAIRS"` / `"NOTHING"`——仅包含 `visibleMask` 中标记为可见的 tile。
- `visibleEntities[].type`：`"PLAYER"` / `"ENEMY"` / `"OTHER"`。
- `visibleEntities[].visibleHp`：仅对 PLAYER 总是填充；对 ENEMY 可选（取决于设计，Phase 2 暂不填）。
- `heardEvents`：Phase 2 中始终为空列表。
- `config`：敌人自身的能力参数（帮助 Python 理解自身能力边界）。

**响应（Python → Java）**：

```json
{
  "schemaVersion": "phase2.response.v1",
  "decisionId": "uuid-string-matching-request",
  "agentId": "guard-a",
  "observationSeq": 5,
  "error": null,
  "intent": {
    "goal": "CHASE",
    "strategy": "CHASE",
    "targetPosition": {"x": 3, "y": 2},
    "confidence": 0.8,
    "targetRoom": -1
  }
}
```

字段说明：
- `decisionId`：必须与请求一致。
- `agentId`：必须与请求一致。
- `observationSeq`：必须与请求一致（防止基于旧 observation 的决策生效）。
- `error`：非 null 时表示 Python 侧异常（如内部错误），此时 `intent` 可为 null，Java 使用 fallback。
- `intent.goal`：`"CHASE"` / `"PATROL"` / `"ATTACK_PLAYER"` / `"GUARD"` / `"AMBUSH"` / `"INTERCEPT_PLAYER"` / `"RETREAT"`。
- `intent.strategy`：`"CHASE"` / `"PATROL"` / `"ATTACK"` / `"GUARD"` / `"AMBUSH"` / `"INTERCEPT"`。
- `intent.targetPosition`：可为 null（表示无具体目标，如 PATROL 时由 planner 自主选择）。
- `intent.confidence`：0.0-1.0。

#### D2-04：每敌人单个 in-flight 请求

每个 `PythonBridgeBrain` 最多同时持有一个未完成的请求。新请求发起前，旧请求自动失效（即使 Python 稍后返回，也会因 observationSeq 不匹配被丢弃）。

理由：
- 避免在 Python 慢响应时堆积大量请求。
- 保证"一个 observation 对应一个 decision"的语义清晰。
- 简化 mailbox 实现。

#### D2-05：Fallback 策略

以下情况使用 fallback：
1. Python 进程不可达（启动时检测到连接失败）。
2. 发送请求时 TCP write 失败。
3. 超时（默认 500ms，可配置）。
4. 收到响应但 validation 失败（agentId 不匹配、observationSeq 不匹配、goal 非法）。
5. 收到响应且 `error` 字段非 null。

Fallback 行为：**PATROL**（与 `RuleBasedBrain` 看不到玩家时行为一致）。Patrol 目标由 `ClassicalPlanner` 在 Java 侧从可见 FLOOR tile 中随机选择。

如果 Python 连续失败（如连续 3 次 fallback），log warning，但不改变行为——仍用 PATROL fallback。

#### D2-06：Python 侧 Fake Runtime

Python 侧实现一个最小 TCP server：
1. 接收连接。
2. 读取一行 JSON（以 `\n` 分隔）。
3. 解析请求。
4. 硬编码决策逻辑：如果 `visibleEntities` 中有 PLAYER → 返回 `CHASE`（target 为玩家位置）；否则返回 `PATROL`（target 为随机可见 FLOOR tile，或 null）。
5. 返回一行 JSON 响应（以 `\n` 分隔）。
6. 继续等待下一个请求。

这个 fake runtime 的行为几乎等同于 `RuleBasedBrain`——但这不重要。重要的是它证明了"Python 收到正确数据"和"Python 返回的决策能被正确执行"。

#### D2-07：Game loop 中 Python 响应的消费时机

在 [Game.java:116-120](byog/Core/Game.java#L116-L120) 当前 loop 中，插入两个阶段：

```java
// 阶段 A：消费已就绪的 Python 响应（每个 enemy 检查 mailbox）
for (Entity e : entityMgr.getAllEntities()) {
    if (e instanceof Enemy enemy && e.isAlive()) {
        enemy.consumeAsyncDecision();  // 如果 mailbox 有就绪响应，注入到 planner
    }
}

// 阶段 B：执行 AI tick（同步部分：perception → 发送请求 → fallback → planner → action）
for (Entity e : entityMgr.getAllEntities()) {
    if (e instanceof Enemy enemy && e.isAlive()) {
        enemy.updateAI(world, entityMgr, player);
    }
}
```

阶段 A 在玩家输入处理之后、AI tick 之前，确保 Python 返回的决策在当前帧就能驱动敌人行动（而非等下一帧）。

阶段 B 的 `updateAI()` 内部，如果使用 `PythonBridgeBrain`：
- brain.thinkFromObservation() 立即返回 fallback intent（PATROL）。
- 同时异步发送 observation 到 Python。
- 敌人用 fallback intent 继续走 planner → action 流程。

#### D2-08：DecisionValidator 校验规则

Java 侧收到 Python 响应后，依次校验：

1. `schemaVersion` 匹配 `"phase2.response.v1"`。
2. `decisionId` 与当前 in-flight 请求一致。
3. `agentId` 与 enemy 的 agentId 一致。
4. `observationSeq` 与发出请求时的 observationSeq 一致（**过期检测**：如果 enemy 已经发出了更新的 observation，旧决策必须丢弃）。
5. `error == null`。
6. `intent.goal` 是合法枚举值。
7. `intent.strategy` 是合法枚举值。
8. 如果 `intent.targetPosition` 非 null，`targetPosition` 必须在当前 `world` 范围内。

校验失败 → 丢弃响应，使用 PATROL fallback，log warning。

#### D2-09：Trace 新事件类型

新增 4 个 `EventType`：

| 事件类型 | 触发时机 | 关键字段 |
|----------|----------|----------|
| `BRIDGE_REQUEST_SENT` | observation 被序列化并发送到 Python 后 | `decisionId`、`observationSeq`、`agentId` |
| `BRIDGE_RESPONSE_RECEIVED` | Python 返回有效响应并通过 validation | `decisionId`、`observationSeq`、`agentId`、`goal`、`strategy`、`latencyMs` |
| `BRIDGE_TIMEOUT` | 响应超时 | `decisionId`、`observationSeq`、`agentId`、`timeoutMs` |
| `BRIDGE_FALLBACK` | 任何失败导致使用 PATROL fallback | `decisionId`、`agentId`、`reason`（CONNECTION_FAILED/TIMEOUT/VALIDATION_FAILED/PYTHON_ERROR） |

这些事件也加入 `AgentTrace.TraceEvent` 和 `InMemorySink.toCanonicalJson()`。

#### D2-10：GameConfig 新增配置项

| 配置键 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enemy.bridgeEnabled` | boolean | `false` | 是否启用 Python bridge。false 时回退到 RuleBasedBrain |
| `enemy.bridgeHost` | String | `localhost` | Python server 监听地址 |
| `enemy.bridgePort` | int | `9876` | Python server 监听端口 |
| `enemy.bridgeTimeoutMs` | int | `500` | 单个请求超时（毫秒） |
| `enemy.bridgeMaxRetries` | int | `2` | 连接失败最大重试次数（含首次） |

`GameConfig` 使用与 Phase 1.5 引入的 `debugShowEnemyFov` 相同的模式：`getBoolean()` / `getInt()` 优雅降级。

#### D2-11：Java 侧 JSON 处理

不使用第三方 JSON 库（如 Gson/Jackson）。使用 AgentTrace 已有的 `org.json`（来自 `javalib` 目录）或手写简单序列化。

理由：
- `org.json` 已经在 classpath 中（`javalib` 目录下），不需要新增依赖。
- 字段量少（~20 个字段），手写序列化代码量可控。
- 避免引入依赖管理复杂度。

### 6.2 暂时假设

- **假设 A1**：Python 3.x 已在系统 PATH 中，可通过 `python` 命令启动。如果 Builder 环境需要 `python3`，通过配置或启动脚本适配。
- **假设 A2**：localhost TCP 通信延迟在 LAN 环境下 < 1ms（非网络延迟），500ms 超时绰绰有余。如果后续 Phase 3 引入真实 LLM 调用（延迟可达 2-10s），超时值需要大幅调整，但那属于 Phase 3 范围。
- **假设 A3**：`visibleTiles` 序列化后的 JSON 大小在 50KB 以内（80×30 世界，FOV 约 150 tile），TCP 传输一次往返 < 5ms。如果后续世界尺寸增大到 200×200，需要评估压缩方案，但当前规模足够。
- **假设 A4**：Fake Python runtime 只处理单个 TCP 连接。多连接支持属于 Phase 3+（真实 LLM 可能需要并行请求）。
- **假设 A5**：Phase 2 不使用 trace 的 canonical JSON golden comparison（P2-T09 除外）——因为 Python 的假 runtime 行为是确定性的，但桥接引入的时间戳字段（`latencyMs`）是非确定性的。在 golden comparison 中排除这些字段。
- **假设 A6**：`Enemy` 构造签名变化（新增 bridge 相关字段）会影响 Phase 0/1 harness 和 Enemy.spawnEnemies()。波及面已知，编译时 IDE 会标红，逐一修复即可。

### 6.3 需要 Builder 决定

#### Q1：Python 进程的生命周期管理

Python server 应该：
- **(a)** 由 Java 进程作为子进程启动（`ProcessBuilder`），Java 退出时自动终止子进程。
- **(b)** 由开发者手动启动，Java 只负责连接。

Advisor 建议：**(a)**。用户体验更好——启动游戏后一切自动就绪。缺点是 Java 需要管理子进程，增加约 30 行代码。但这是值得的：后续 Phase 3 引入 LLM 后，Python server 的启动参数可能更复杂（如指定 model path），由 Java 统一管理避免手动操作遗漏。

#### Q2：`visibleTiles` 是否携带 tile 的 description（如 "stone wall"）

当前 tile 类型只有 `FLOOR` / `WALL` / `STAIRS` / `NOTHING` 四种。Phase 2 的 fake runtime 不需要自然语言描述。但 Phase 3 的 LLM 可能需要更丰富的上下文（如"你在一条狭窄的走廊里，北面是墙，南面是开阔房间"）。

选项：
- **(a)** Phase 2 只传 tile 类型枚举（`FLOOR`/`WALL`/`STAIRS`/`NOTHING`），Phase 3 再加描述文本。
- **(b)** 现在就附带一个 `description` 字段（如 `{"type": "WALL", "description": "stone wall", "x": 5, "y": 3}`），为 Phase 3 提前准备。

Advisor 建议：**(a)**。Fake runtime 不需要描述字段，Phase 3 加字段不会破坏 Phase 2 的测试。提前加字段看似"为未来考虑"，实则增加了 Phase 2 的序列化/反序列化复杂度，而这些字段在 Phase 2 中从不被读取。

#### Q3：Python fake runtime 的 patrol 目标选择

当 Python 决定 PATROL 时，target 应该：
- **(a)** 由 Python 从 `visibleTiles` 中随机选择 FLOOR tile 作为 target 返回。
- **(b)** 返回 `targetPosition: null`，由 Java 侧 `ClassicalPlanner` 自行选择 patrol 目标。

Advisor 建议：**(b)**。更接近真实的分层语义——Python 说"我想巡逻"，Java 决定"巡逻去哪里"。这也避免了 Python 侧需要随机数生成器（保持 fake runtime 的简单性）。`ClassicalPlanner` 已有 patrol 目标选择逻辑（在 `RuleBasedBrain` 中通过 `generatePatrolPosFromObservation` 实现），只需确认 `ClassicalPlanner.translate()` 在 target 为 null 时能自主选择。

## 7. 目标架构与数据流

```text
Phase 2 数据流（一帧内）：
═══════════════════════════════════════════════════════════

Game loop (每帧, ~60 FPS)
│
├─ [阶段 A] consumeAsyncDecision() × 每个 enemy
│     └─ mailbox.hasResponse()?
│         ├─ YES → DecisionValidator.validate()
│         │         ├─ PASS → 注入 StrategicIntent
│         │         │         → ClassicalPlanner.translate() → ActionQueue
│         │         │         → trace: BRIDGE_RESPONSE_RECEIVED
│         │         └─ FAIL → trace: BRIDGE_FALLBACK
│         │                   → 使用 PATROL fallback
│         └─ NO  → 什么都不做
│
├─ [阶段 B] updateAI() × 每个 enemy（每 moveInterval 帧）
│     │
│     ├─ PerceptionSystem.computeObservation() → ObservationEnvelope
│     ├─ trace: OBSERVATION_GENERATED
│     │
│     ├─ brain.thinkFromObservation(observation)
│     │     │
│     │     │  [PythonBridgeBrain]
│     │     ├── 取消旧的 in-flight 请求
│     │     ├── 序列化 observation → AgentRequest JSON
│     │     ├── mailbox.send(request) → TCP write to Python
│     │     ├── trace: BRIDGE_REQUEST_SENT
│     │     └── return PATROL (fallback intent)
│     │
│     ├─ trace: INTENT_SELECTED (fallback intent)
│     ├─ ClassicalPlanner.translate() → ActionQueue
│     └─ Action 执行 (与 Phase 1 完全相同)
│
├─ entityMgr.flushPendingChanges()
├─ entityMgr.removeDeadEntities()
└─ render

Python 侧（独立进程, TCP server loop）：
═══════════════════════════════════════════════════════════

while True:
    conn ← accept()
    line ← readline()
    request ← parse_json(line)
    if request.visibleEntities contains PLAYER:
        response = { goal: "CHASE", target: player.position }
    else:
        response = { goal: "PATROL", target: null }
    conn.writeline(json.dumps(response))
```

关键生命周期：
- **请求创建**：`PythonBridgeBrain.thinkFromObservation()` 中创建，通过 `mailbox.send()` 发送。
- **请求取消**：新的 `thinkFromObservation()` 调用时自动覆盖旧请求（不显式通知 Python，Python 返回旧决策会被 observationSeq 校验拦截）。
- **响应消费**：`consumeAsyncDecision()` 从 mailbox 取出，校验通过后注入当前帧的 planner。
- **超时**：mailbox 内部检查时间戳，超时后标记为过期。`consumeAsyncDecision()` 跳过过期响应并记录 `BRIDGE_TIMEOUT`。
- **连接断开**：TCP read/write 异常时，mailbox 进入 disconnected 状态。下一帧 `consumeAsyncDecision()` 检测到断开 → 尝试重连（`bridgeMaxRetries` 次）→ 仍失败则 fallback。

## 8. 接口与数据契约

### 8.1 `PythonBridgeBrain`（Java，新建）

```java
package byog.AI;

/**
 * 通过 TCP bridge 将 observation 发送到 Python runtime，
 * 异步消费 Python 返回的决策。立即返回 fallback intent 不阻塞游戏循环。
 */
public class PythonBridgeBrain implements EnemyBrain {

    public PythonBridgeBrain(AgentMailbox mailbox, String agentId);
    
    /** 同步返回 fallback intent，同时异步发送 observation 到 Python */
    @Override
    public StrategicIntent thinkFromObservation(ObservationEnvelope obs);

    /** Phase 0 legacy 不支持，抛出 UnsupportedOperationException */
    @Override
    public StrategicIntent think(GameStateSnapshot state);
}
```

`thinkFromObservation()` 内部流程：
1. 生成 `decisionId`（UUID）。
2. 序列化 `ObservationEnvelope` → `AgentRequest` JSON。
3. 调用 `mailbox.send(request)`。
4. 返回 `StrategicIntent.PATROL`（fallback intent）。

### 8.2 `AgentMailbox`（Java，新建）

```java
package byog.Core;

/**
 * 单个敌人的异步请求 mailbox。管理一个 in-flight 请求的生命周期。
 */
public class AgentMailbox implements AutoCloseable {

    public AgentMailbox(String host, int port, int timeoutMs, int maxRetries, String agentId);

    /** 发送请求到 Python。覆盖旧的 in-flight 请求。不阻塞。 */
    public void send(AgentRequest request);

    /** 检查是否有已就绪的有效响应。返回 null 表示无响应或响应无效。 */
    public AgentDecision pollResponse();

    /** 检查 bridge 是否可用（连接正常）。 */
    public boolean isConnected();

    /** 关闭连接 */
    @Override
    public void close();
}
```

内部实现要点：
- 使用单独的线程处理 TCP 通信（读取 Python 响应）。
- `pollResponse()` 从线程安全的队列中取出已就绪的响应。
- 响应按 `decisionId` 关联到请求。
- 超时检测：发送时记录时间戳，`pollResponse()` 中检查是否超时。

### 8.3 `AgentRequest` / `AgentDecision`（Java，新建）

纯数据类，对应 JSON schema（见 D2-03）。包含：
- Schema 版本字段。
- 身份关联字段（decisionId、agentId、observationSeq）。
- 数据负载（observation 或 intent）。

### 8.4 `DecisionValidator`（Java，新建）

```java
package byog.Core;

/**
 * 校验 Python 返回的 AgentDecision 是否合法。
 * 纯函数，不修改状态。
 */
public final class DecisionValidator {

    /** @return null 表示校验通过；否则返回失败原因字符串 */
    public static String validate(AgentDecision decision, AgentRequest request,
                                   TETile[][] world, int worldWidth, int worldHeight);
}
```

### 8.5 `Enemy` 修改

新增字段：
```java
private PythonBridgeBrain bridgeBrain;  // 非 null 时表示使用 bridge 模式
private AgentMailbox mailbox;           // bridge 通信 mailbox
```

新增方法：
```java
/** 消费已就绪的 Python 异步决策。每帧由 Game loop 调用。 */
public void consumeAsyncDecision(EntityManager entityMgr, TETile[][] world, Random random);

/** 设置 bridge brain 和 mailbox */
public void setBridgeBrain(PythonBridgeBrain brain, AgentMailbox mailbox);
```

`consumeAsyncDecision()` 内部流程：
1. 如果 `bridgeBrain == null || mailbox == null`，直接返回。
2. `mailbox.pollResponse()` 获取响应。
3. 如果响应为 null（无响应或过期），返回。
4. `DecisionValidator.validate(response, ...)`。
5. 如果校验失败，记录 trace `BRIDGE_FALLBACK`，返回。
6. 如果校验通过，`StrategicIntent intent = response.toStrategicIntent()`。
7. 清空 actionQueue，用新 intent 重新规划：`ClassicalPlanner.translate(intent, ...)` → `actionQueue.enqueueAll()`。
8. 更新 `currentStrategy`。
9. 记录 trace `BRIDGE_RESPONSE_RECEIVED`。

### 8.6 `updateAI()` 修改

在 `perceptionEnabled=true` 路径（[Enemy.java:85-116](byog/Entity/Enemy.java#L85-L116)）中：

```java
if (perceptionEnabled) {
    // ... computeObservation ...
    
    if (bridgeBrain != null) {
        intent = bridgeBrain.thinkFromObservation(observation);
        // bridge brain 内部已发送请求并记录 BRIDGE_REQUEST_SENT trace
    } else {
        intent = brain.thinkFromObservation(observation);
    }
    // ... trace INTENT_SELECTED ...
}
```

注意：当 `bridgeBrain != null` 时，`brain` 字段被绕过——这是一个刻意的设计：bridge 模式直接替代 Brain，而非作为 Brain 的 wrapper。

### 8.7 `Game.java` 修改

主循环 [Game.java:108-129](byog/Core/Game.java#L108-L129) 改为：

```java
if (currentState == GameState.PLAYING) {
    if (player != null) {
        player.updateCharge();
        player.updateHitTimer();
    }

    // 阶段 A：消费异步决策
    if (config.isBridgeEnabled()) {
        for (Entity e : entityMgr.getAllEntities()) {
            if (e instanceof Enemy enemy && e.isAlive()) {
                enemy.consumeAsyncDecision(entityMgr, world, random);
            }
        }
    }

    // 阶段 B：AI tick
    for (Entity e : entityMgr.getAllEntities()) {
        if (e instanceof Enemy enemy && e.isAlive()) {
            enemy.updateAI(world, entityMgr, player);
        }
    }
    entityMgr.flushPendingChanges();
    entityMgr.removeDeadEntities();

    if (player != null && !player.isAlive()) {
        currentState = GameState.PAUSED;
        Logger.info("Player died!");
    }
}
```

### 8.8 `AgentTrace` 扩展

Schema version 升级为 `"phase2.trace.v1"`。

`EventType` 新增：`BRIDGE_REQUEST_SENT`、`BRIDGE_RESPONSE_RECEIVED`、`BRIDGE_TIMEOUT`、`BRIDGE_FALLBACK`。

`TraceEvent` 新增字段（对 bridge 事件填充，其他事件为 null）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `decisionId` | nullable String | UUID，关联请求/响应 |
| `bridgeObservationSeq` | nullable Long | 发送请求时的 observation 序号 |
| `bridgeAgentId` | nullable String | （冗余，方便按 agent 过滤）|
| `bridgeGoal` | nullable String | Python 返回的 goal（仅 RESPONSE_RECEIVED） |
| `bridgeStrategy` | nullable String | Python 返回的 strategy（仅 RESPONSE_RECEIVED） |
| `bridgeLatencyMs` | nullable Long | 请求到响应的延迟（仅 RESPONSE_RECEIVED） |
| `bridgeTimeoutMs` | nullable Long | 配置的超时值（仅 TIMEOUT） |
| `bridgeFallbackReason` | nullable String | CONNECTION_FAILED/TIMEOUT/VALIDATION_FAILED/PYTHON_ERROR（仅 FALLBACK） |

### 8.9 Python 侧

**目录结构**：`bridge/`（项目根目录下）

**文件**：

| 文件 | 职责 |
|------|------|
| `bridge/server.py` | TCP server 主入口，接收连接、处理请求、返回硬编码决策 |
| `bridge/protocol.py` | `AgentRequest` / `AgentDecision` 数据类定义 + JSON 序列化/反序列化 |
| `bridge/run.py` | 启动脚本（供 Java `ProcessBuilder` 调用或手动启动） |

`server.py` 伪代码：

```python
import socket
import json
from protocol import AgentRequest, AgentDecision

HOST = '127.0.0.1'
PORT = 9876

def handle_request(data: dict) -> dict:
    req = AgentRequest.from_dict(data)
    # Fake decision logic
    player = next((e for e in req.visibleEntities if e.type == 'PLAYER'), None)
    if player is not None:
        intent = {'goal': 'CHASE', 'strategy': 'CHASE',
                  'targetPosition': player.position, 'confidence': 0.9, 'targetRoom': -1}
    else:
        intent = {'goal': 'PATROL', 'strategy': 'PATROL',
                  'targetPosition': None, 'confidence': 0.5, 'targetRoom': -1}
    
    resp = AgentDecision(
        schemaVersion='phase2.response.v1',
        decisionId=req.decisionId,
        agentId=req.agentId,
        observationSeq=req.observationSeq,
        error=None,
        intent=intent
    )
    return resp.to_dict()

def main():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind((HOST, PORT))
        s.listen(1)
        print(f'[Bridge] Listening on {HOST}:{PORT}')
        while True:
            conn, addr = s.accept()
            with conn:
                print(f'[Bridge] Connected from {addr}')
                f = conn.makefile('rw')
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    request = json.loads(line)
                    response = handle_request(request)
                    f.write(json.dumps(response) + '\n')
                    f.flush()

if __name__ == '__main__':
    main()
```

### 8.10 GameConfig 默认值更新

`game.properties` 默认模板新增：

```properties
# --- Python Bridge (Phase 2) ---
# 是否启用 Python bridge。false 时使用 RuleBasedBrain。
enemy.bridgeEnabled=false
# Python server 地址和端口
enemy.bridgeHost=localhost
enemy.bridgePort=9876
# 单个请求超时（毫秒）
enemy.bridgeTimeoutMs=500
# 连接失败最大重试次数
enemy.bridgeMaxRetries=2
```

## 9. 逐文件变更计划

| 文件 | 新建/修改 | 责任 | 关键变更 | 不应包含 |
|------|-----------|------|----------|----------|
| `byog/AI/PythonBridgeBrain.java` | **新建** | 异步 Brain 实现 | 实现 `EnemyBrain`、`thinkFromObservation()` 发送请求 + 返回 PATROL fallback | LLM 调用、Tool Calling |
| `byog/Core/AgentMailbox.java` | **新建** | 异步通信管理 | TCP client、发送/接收线程、超时检测、重连 | 多连接、消息批处理 |
| `byog/Core/AgentRequest.java` | **新建** | 请求 DTO | JSON schema 序列化/反序列化、decisionId 生成 | Python 侧逻辑 |
| `byog/Core/AgentDecision.java` | **新建** | 响应 DTO | JSON schema 序列化/反序列化、toStrategicIntent() | LLM token/logprobs |
| `byog/Core/DecisionValidator.java` | **新建** | 响应校验 | agentId/seq/target 合法性检查 | 可达性深度校验（Phase 4） |
| `byog/Entity/Enemy.java` | 修改 | 异步决策注入 | `bridgeBrain`/`mailbox` 字段、`consumeAsyncDecision()`、`updateAI()` 分支、`setBridgeBrain()` | 修改 ClassicalPlanner 或 ActionQueue |
| `byog/Core/Game.java` | 修改 | 游戏循环集成 | 双阶段 AI 处理（消费响应 → 发送请求）、Python 进程管理 | 修改 processInput/渲染 |
| `byog/Core/Main.java` | 修改 | 启动流程 | 启动 Python 子进程、创建 AgentMailbox、为 Enemy 设置 bridgeBrain | 修改种子输入/存档加载 |
| `byog/Trace/AgentTrace.java` | 修改 | 桥接事件 | 4 个新 EventType、新 TraceEvent 字段、v2 schema | 修改旧事件格式 |
| `byog/IO/GameConfig.java` | 修改 | 新配置项 | bridgeEnabled/bridgeHost/bridgePort/bridgeTimeoutMs/bridgeMaxRetries | 修改现有配置逻辑 |
| `config/game.properties` | 修改 | 配置模板 | 新增 5 个配置项 | — |
| `bridge/__init__.py` | **新建** | Python 包标记 | 空文件 | — |
| `bridge/protocol.py` | **新建** | Python schema | AgentRequest/AgentDecision 数据类、JSON 序列化 | LLM、LangChain |
| `bridge/server.py` | **新建** | Fake runtime | TCP server、硬编码决策逻辑 | 模型推理 |
| `bridge/run.py` | **新建** | 启动入口 | `main()` 入口，供 Python 直接执行 | — |
| `byog/Test/Phase2TestHarness.java` | **新建** | Phase 2 harness | headless runner、控制 fake Python 决策 | 渲染、键盘 |
| `byog/Test/Phase2Test.java` | **新建** | Phase 2 contract tests | P2-T01 ~ P2-T09 | 人工观察代替断言 |
| `byog/Test/Phase2TestSuite.java` | **新建** | 聚合入口 | 聚合 Phase 2 测试 + 适配后的 Phase 0/1 | 未适配的测试 |
| `PHASE_2_COMPLETION.md` | 验收时新建 | 关闭阶段 | commit、命令、结果、偏差、Phase 3 输入 | — |

**受影响的旧文件（需适配新构造签名）**：
- `Phase0EncounterHarness.java` — Enemy 构造签名变化。
- `Phase1EncounterHarness.java` — 同上。
- `EnemyCollisionTest.java` — 同上。
- `Phase1EncounterTest.java` — 同上。
- `Enemy.spawnEnemies()` — 同上。
- `Phase1BaselineMain.java` — 同上。

## 10. 实施顺序

### Step 2.1：定义 Python 侧数据结构与协议

**输入**：无（greenfield）

**具体改动**：
1. 创建 `bridge/` 目录结构和 `__init__.py`。
2. 创建 `bridge/protocol.py`——AgentRequest/AgentDecision 数据类。
3. 创建 `bridge/server.py`——TCP server，接收 JSON，硬编码决策，返回 JSON。
4. 创建 `bridge/run.py`——启动入口。

**验证**：手动启动 `python bridge/run.py`，用 `echo '{"schemaVersion":"phase2.request.v1",...}' | ncat localhost 9876` 验证返回格式正确。

**产出**：Python 通信层完成。

### Step 2.2：定义 Java 侧数据结构

**输入**：Step 2.1 的 Python schema。

**具体改动**：
1. 创建 `AgentRequest.java`——纯数据类，字段 + JSON 序列化/反序列化方法。
2. 创建 `AgentDecision.java`——纯数据类，字段 + JSON 序列化/反序列化方法 + `toStrategicIntent()`。
3. 创建 `DecisionValidator.java`——`validate()` 静态方法，校验规则见 D2-08。

**验证**：编译通过。写简易 main 测试 JSON 往返。

**产出**：Java DTO 层完成。

### Step 2.3：实现 AgentMailbox

**输入**：Step 2.2 的数据结构。

**具体改动**：
1. 创建 `AgentMailbox.java`：TCP client、独立接收线程、响应队列、超时检测、重连逻辑。
2. 内部使用 `java.net.Socket` + `BufferedReader/BufferedWriter`（或 `PrintWriter`，以 `\n` 分隔）。

**验证**：用 Python fake server（Step 2.1）测试连接-发送-接收-超时-重连。写简易 main 测试。

**产出**：通信管理层完成。

### Step 2.4：实现 PythonBridgeBrain

**输入**：Step 2.3 的 AgentMailbox。

**具体改动**：
1. 创建 `PythonBridgeBrain.java`：实现 `EnemyBrain`，`thinkFromObservation()` 内序列化 observation、生成 decisionId、调用 mailbox.send()、返回 PATROL。
2. `think(GameStateSnapshot)` 抛出 `UnsupportedOperationException`。

**验证**：编译通过。

**产出**：新 Brain 实现完成。

### Step 2.5：修改 Enemy 支持异步决策

**输入**：Step 2.4 的 PythonBridgeBrain。

**具体改动**：
1. `Enemy` 新增 `bridgeBrain`、`mailbox` 字段。
2. 新增 `setBridgeBrain(brain, mailbox)` 方法。
3. 新增 `consumeAsyncDecision(entityMgr, world, random)` 方法（见 §8.5）。
4. `updateAI()` 中 `perceptionEnabled=true` 路径增加 `bridgeBrain != null` 分支（见 §8.6）。
5. 适配 Enemy 构造签名（新增 bridge 相关参数的 optional setter，或新增构造方法重载）。

**验证**：Phase 0/1 harness 适配后编译 + 测试通过（bridge 未启用时行为不变）。

**产出**：Enemy 支持异步决策。

### Step 2.6：集成 Game loop

**输入**：Step 2.5 的 Enemy。

**具体改动**：
1. `Game.java` 主循环插入阶段 A（消费异步决策）。
2. `Main.java` / `Game.java` 启动时：如果 `bridgeEnabled=true`，用 `ProcessBuilder` 启动 Python 子进程，创建 AgentMailbox，为所有 Enemy 设置 bridgeBrain。
3. 退出时关闭 AgentMailbox 和 Python 子进程。
4. `GameConfig` 新增 5 个配置项（见 D2-10）。

**验证**：编译通过。手动启动游戏，观察日志：Python 进程启动成功，bridge 连接建立。

**产出**：Game loop 集成完成。

### Step 2.7：扩展 AgentTrace

**输入**：所有前序步骤。

**具体改动**：
1. `AgentTrace.TraceEvent` 新增 bridge 字段（见 §8.8）。
2. `AgentTrace.EventType` 新增 4 个类型。
3. 新增 factory 方法：`bridgeRequestSent()`、`bridgeResponseReceived()`、`bridgeTimeout()`、`bridgeFallback()`。
4. `InMemorySink.toCanonicalJson()` 输出新字段。
5. Schema version 升级为 `phase2.trace.v1`。

**验证**：编译通过。写简易 main 测试生成 trace JSON，确认新字段格式正确。

**产出**：Trace 扩展完成。

### Step 2.8：创建 Phase 2 测试

**输入**：所有前序步骤。

**具体改动**：
1. 创建 `Phase2TestHarness`：headless runner，支持控制 fake Python 决策（通过注入预先构造的 AgentDecision 或直接调用 `consumeAsyncDecision` 的测试路径）。
2. 创建 `Phase2Test`（P2-T01 ~ P2-T09）。
3. 创建 `Phase2TestSuite`：聚合 Phase 2 测试 + 适配后 Phase 0/1 测试。

**验证**：所有 Phase 2 测试通过 + 回归测试通过。

**产出**：Phase 2 测试套件完成。

### Step 2.9：生成配置文件默认值

**输入**：Step 2.6 的 GameConfig 更新。

**具体改动**：
1. 更新 `config/game.properties` 模板，添加 5 个 bridge 配置项。

**验证**：删除 `config/game.properties` 后启动游戏，确认自动生成的文件包含新配置项。

**产出**：配置模板更新。

## 11. 测试与验收矩阵

| Test ID | 场景 | 核心断言 | 自动/人工 | 对应需求 |
|---------|------|----------|-----------|----------|
| P2-T01 | `different_enemies_independent_mailboxes` | Guard A 和 B 的 mailbox 互不干扰，各自的 decisionId 不串线 | 自动 | INV-01 |
| P2-T02 | `python_decision_overrides_fallback` | Python 返回 CHASE 后，enemy 实际执行 CHASE（非 PATROL） | 自动 | INV-04/05 |
| P2-T03 | `game_loop_not_blocked_by_bridge` | 在 Python 慢响应（如 sleep 2s）场景下，游戏逻辑 tick 继续推进（frame counter 递增） | 自动 | INV-07 |
| P2-T04 | `invalid_goal_rejected_fallback` | Python 返回非法 goal（如 "FLY"），validator 拒绝，fallback 到 PATROL | 自动 | INV-04/06 |
| P2-T05 | `python_intent_goes_through_planner` | Python 返回 CHASE(target=player.pos)，enemy 的 ActionQueue 产生 MOVE 动作（非 PATROL 的随机移动） | 自动 | INV-05 |
| P2-T06 | `stale_observation_seq_rejected` | Python 返回 observationSeq=3，但 enemy 已发了 seq=5，旧响应被丢弃 | 自动 | INV-06/07 |
| P2-T07 | `trace_contains_bridge_lifecycle` | canonical trace 包含 BRIDGE_REQUEST_SENT/BRIDGE_RESPONSE_RECEIVED 事件，decisionId 可关联 | 自动 | INV-08 |
| P2-T08 | `python_receives_only_visible_info` | Python fake runtime 收到的 `visibleTiles` 只包含 FOV 内 tile；验证墙后 tile 不在列表中 | 自动 | INV-02 |
| P2-T09 | `bridge_fallback_on_python_closed` | Python 进程关闭后，enemy 正常 fallback 到 PATROL，游戏不崩溃 | 自动 | INV-07 |
| P2-T10 | `bridge_reconnect_on_python_restart` | Python 重新启动后，mailbox 重连成功，bridge 恢复正常 | 自动 | INV-07 |
| P2-R01 | `phase0_1_tests_still_pass_without_bridge` | Phase 0/1 测试（bridgeEnabled=false）全部通过 | 自动回归 | INV-04 |

测试命令：

```powershell
# Phase 2 全量测试
$javaSources = Get-ChildItem byog -Recurse -Filter *.java | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -cp "..\library-sp18\javalib\*" -d out $javaSources
java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" org.junit.runner.JUnitCore byog.Test.Phase2TestSuite

# 单独运行 Phase 2 测试
java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" org.junit.runner.JUnitCore byog.Test.Phase2Test
```

对于 P2-T08（验证 Python 收到数据），测试策略：
- 不真的启动 Python server。
- 在 Java 侧拦截序列化后的 JSON，验证 `visibleTiles` 列表中不包含墙后 tile 的坐标。
- 这是一个纯 Java 单元测试——验证 `AgentRequest` 序列化逻辑正确。

## 12. Observability 与运行证据

Phase 2 canonical evidence 包括（继承 Phase 1 全部 + 新增）：

- `schemaVersion`（升级为 `phase2.trace.v1`）
- Bridge 事件：`decisionId`、`bridgeObservationSeq`、`bridgeAgentId`、`bridgeGoal`、`bridgeStrategy`、`bridgeLatencyMs`、`bridgeTimeoutMs`、`bridgeFallbackReason`
- `inputKind`：仍为 `"private-perception-v1"`（observation 本身不变）

诊断日志（不参与 canonical comparison）：
- Python 进程启动/退出/重启日志
- TCP 连接状态变化日志
- 个别请求的序列化 JSON（DEBUG 级别，默认关闭）

人工验收：
- `bridgeEnabled=true` 时，游戏正常启动，FOV 可视化可见（Phase 1.5 特性），敌人行为与 RuleBasedBrain 视觉上相似（fake runtime 逻辑相同）。
- `bridgeEnabled=false` 时，游戏体验与 Phase 1 完全一致。
- Python 进程被 kill 后，敌人继续 PATROL，游戏不卡顿、不崩溃。

## 13. 失败处理、兼容与迁移

### 向后兼容

- `bridgeEnabled=false`（默认值）时，行为与 Phase 1 完全一致。生产路径仍使用 `RuleBasedBrain`。
- `Enemy` 构造签名变化（新增 bridge 相关 setter 或可选参数），所有旧 harness 需要适配。这是编译时错误，不会遗漏。
- Phase 0 和 Phase 1 测试需要适配新构造签名，但行为不变（bridge 默认不启用）。
- `GameStateSnapshot` 保留，不移除——`RuleBasedBrain` 仍需要它。

### 渐进迁移路径

- Phase 2 引入 `bridgeEnabled` 配置开关（默认 `false`）。
- 开发者在 `game.properties` 中设置 `bridgeEnabled=true` 来测试 bridge。
- Phase 3 将 `bridgeEnabled` 默认值改为 `true`（当 Python LLM runtime 可用时）。
- Phase 3+ 移除 `perceptionEnabled` 开关（只保留 bridge 路径和 RuleBasedBrain fallback）。

### 存档兼容

- `AgentMailbox` 不持久化——读档时重新连接 Python server。
- `bridgeBrain` 引用不持久化——由 `Game.java` / `Main.java` 在加载存档后重新创建并注入。
- 不需要新增存档格式字段。

### Feature flag

`enemy.bridgeEnabled` 配置项即为 feature flag。

### 半完成状态防护

- 如果 Python 进程未启动但 `bridgeEnabled=true`：连接失败 → fallback → 游戏正常运行，log warning 提示开发者检查 Python 进程。
- 如果 Python 返回格式不匹配：JSON 解析异常 → fallback → 不崩溃。

## 14. 风险与停止条件

### 主要风险

1. **Python 子进程管理复杂度**：`ProcessBuilder` 的跨平台行为差异（Windows vs Linux/Mac）。Windows 上 Python 可执行文件名可能是 `python` 或 `python3`，路径可能在 PATH 中也可能不在。对此风险的缓解：`GameConfig` 中新增 `enemy.bridgePythonCommand` 配置项（如默认 `python`），用户可自行调整。如果 Builder 环境特殊，先用手动启动模式（Q1 方案 b）绕过。

2. **多线程并发**：`AgentMailbox` 内部使用独立线程处理 TCP 读取，`consumeAsyncDecision()` 在游戏主线程调用。需要用 `synchronized` 或 `ConcurrentLinkedQueue` 保护共享队列。风险：漏加同步导致响应丢失或 double-consume。缓解：Phase 2 harness 的高频重复测试可暴露并发问题。

3. **JSON 解析性能**：每次序列化 `visibleTiles`（~150 个 tile）可能产生 5-10KB JSON。如果每帧多个 enemy 同时请求，有一定开销。缓解：`enemy.moveInterval` 已经限制 AI tick 频率（默认每 5 帧一次），所以 JSON 序列化每 5 帧才发生一次，开销可忽略。

4. **Phase 0/1 测试适配工作量**：Enemy 构造签名变化波及约 6 个文件。这是机械性的编译修复，IDE 能精确定位每一个问题，不需要逐行排查。
5. **`visibleTiles` 序列化中 TETile 类型判断**：当前 `ObservationEnvelope` 持有 `world` 引用但不暴露 tile 类型（只暴露 `isWalkable()` 和 `isVisible()`）。需要在序列化时通过 `visibleMask` 遍历并查询 `world[x][y]` 的 tile 类型。这需要访问 `world` 数组——可以在序列化代码中直接使用 `obs` 的 package-private 成员。
6. **Scope creep**：通信打通后容易顺手加入"简单的 LLM 调用"。**必须严格遵守 Out of Scope**——只允许硬编码决策。

### 停止条件

出现以下情况时停止实现并回到 Spec：
- AgentMailbox 的线程安全问题无法在合理时间内解决。
- JSON 序列化/反序列化导致性能可感知的卡顿（> 50ms 单帧）。
- 需要对 Intent 或 Roadmap 进行实质性修改（如在 Phase 2 中就引入 LLM）。
- Phase 0/1 回归测试在未启用 bridge 的情况下失败（行为被意外改变）。
- Python 进程管理在目标平台上不可行（需要切换传输方式，如 stdin/stdout pipe）。

## 15. Definition of Done

- [ ] P2-T01 ~ P2-T10 全部通过。
- [ ] P2-R01：Phase 0/1 回归测试（bridgeEnabled=false）全部通过。
- [ ] `Phase2TestSuite` 可通过单一命令运行，全程 headless。
- [ ] Python fake runtime 可独立启动并正确响应请求。
- [ ] `bridgeEnabled=true` 时，游戏循环不被 Python 阻塞。
- [ ] Python 进程关闭时，游戏正常 fallback，不崩溃。
- [ ] 延迟返回的旧决策（observationSeq 不匹配）被正确丢弃。
- [ ] 非法 goal 被 DecisionValidator 拒绝。
- [ ] canonical trace 包含 bridge 生命周期事件。
- [ ] 配置文件 `game.properties` 包含全部 5 个 bridge 配置项。
- [ ] `Main.java` 支持 `ProcessBuilder` 启动 Python 子进程。
- [ ] 没有引入 LLM、LangChain、Tool Calling 或真实模型调用。
- [ ] 已知偏差和遗留问题写入 Completion。
- [ ] `PHASE_2_COMPLETION.md` 已生成，并列出 Phase 3 所需 artifacts。

## 16. 下一阶段交接

Phase 3 可以依赖：

- 稳定的 `AgentRequest` / `AgentDecision` JSON schema（`phase2.request.v1` / `phase2.response.v1`）。
- `PythonBridgeBrain` → `AgentMailbox` → Python TCP server 的完整通信链路。
- 异步 mailbox 机制（每敌人单 in-flight 请求、超时、fallback）。
- `DecisionValidator` 校验规则。
- `AgentTrace` 的 bridge 事件类型（`phase2.trace.v1`）。
- `GameConfig` 的 bridge 配置项。
- Headless `Phase2TestHarness` 和测试套件。
- `Game.java` 的双阶段 AI loop（消费异步决策 + 发送请求）。

Phase 3 不得假设：

- Python 侧有状态管理或 checkpoint（Phase 2 fake runtime 完全无状态）。
- LLM 推理已接入（Phase 3 的任务）。
- Tool Calling 已实现（Phase 3 的任务）。
- `ActionOutcome` 反馈机制已存在（Phase 4）。
- 跨帧的状态持久化（如 Python 侧保存"上次看到玩家的位置"）。
- `visibleTiles` 携带自然语言描述。
- `GameStateSnapshot` 已被移除。
- 多 enemy 共享 context（Phase 5）。

Phase 3 的首要任务将是：在 Python 侧接入 LangGraph/LangChain，实现有状态的 Tool Calling loop，让 fake runtime 变成真正的 LLM Agent runtime。

## 附录 A：Spec 自检

- [x] 已先审计仓库（13 项代码审查），不是从 Roadmap 自由扩写。
- [x] 已阅读 Phase 1 Completion + Phase 1.5 Completion，提取了所有 Phase 2 输入。
- [x] 区分了代码事实、推断、已锁定决定和待 Builder 决定。
- [x] 引用了关键代码位置（Game loop、Enemy.updateAI()、EnemyBrain 接口）。
- [x] 逐文件变更计划包含新建/修改、责任和边界。
- [x] 实施顺序可单独编译验证。
- [x] 测试矩阵覆盖正常路径、失败路径、过期丢弃、非法输入和回归。
- [x] 没有提前实现 Phase 3 的 LLM、LangGraph、Tool Calling。
- [x] 没有提前实现 Phase 4 的 ActionOutcome 反馈。
- [x] 每项完成条件都有可执行验证方式。
- [x] 明确列出了 Phase 3 可以依赖与不得假设的内容。
- [x] D2-03 的 JSON schema 完整、可执行。
- [x] D2-08 的校验规则覆盖 agentId、seq、goal 合法性。
