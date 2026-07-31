# DungeonMind Game Loop 与 AI 控制架构

> 状态：当前实现说明
>
> 更新时间：2026-07-31
>
> 范围：交互式游戏入口、Game Loop、Phase 2.2、Phase 2.3，以及已实现但尚未生产接线的 AgentSession 核心
>
> 不包含：`playWithInputString()` 的旧测试运行时、Tool Calling、多 Agent 协作和复杂计划系统

---

## 0. 先读这一页

当前可玩的交互式游戏已经启用新的 AI Tick 和本地双速控制链，但还没有接入网络 Agent。

| 能力 | 状态 | 当前行为 |
|------|------|----------|
| 交互式 Game Loop | **已实现** | `Main → Game.playWithKeyboard()` 持续处理输入、AI、绘制和生命周期 |
| AI Tick 调度 | **已实现** | 所有 Enemy 按 `poll → execute → commit → collect → close dead` 执行 |
| 每次冷却至多一个 Action | **已实现** | 不再在一次 Enemy 更新中连续尝试多个动作 |
| 私有观察与提交后反馈 | **已实现** | execute 使用旧观察，collect 从已提交世界生成新观察和 outcome |
| Reflex / Lease / Arbiter / Validator | **已实现** | 纯 Java 控制链已经参与真实游戏运行 |
| 本地 RuleBasedBrain fallback | **已实现** | 无可用 Lease 时仍能行动 |
| 远程 proposal 校验入口 | **接口已预留** | `tryAdoptRemoteIntent()` 可用，但当前只有测试调用 |
| AgentSession / 有界队列 | **核心已实现** | 已有确定性测试；`pollAgentMessages()` 尚未接线 |
| AgentTransport / TCP worker | **已实现** | `SocketTransport` 独占 Socket，负责 NDJSON、退避重连和有界关闭 |
| Python Agent runtime | **未实现** | 不参与当前游戏行为 |

读图时使用以下约定：

- **实线**：当前代码真实执行的调用。
- **虚线**：接口或模块规划，当前没有生产调用。
- **commit barrier**：`EntityManager` 完成待处理变更和死亡清理后，世界状态才可用于观察与反馈。

---

## 1. 完整 Game Loop

### 1.1 游戏生命周期

```mermaid
flowchart TD
    MAIN["Main.main(args)"] --> MODE{"是否无命令行参数？"}
    MODE -->|否| LEGACY["playWithInputString()<br/>旧接口，不属于当前运行时"]
    MODE -->|是| START["Game.playWithKeyboard()"]

    START --> INIT["初始化 Renderer、EntityManager、键位和 GameState"]
    INIT --> MENU["MENU"]

    MENU -->|New Game| DIFF["DIFFICULTY_SELECT"]
    DIFF --> SEED["SEED_INPUT"]
    SEED -->|"确认 seed"| BUILD["生成 world、Player、Enemy、stairs"]
    BUILD --> RUNID["beginAgentRun()<br/>新 runId + logicalTick=0 + 初始观察"]
    RUNID --> PLAYING["PLAYING"]

    MENU -->|Load| LOAD["loadGameState()"]
    LOAD -->|成功| RUNID
    LOAD -->|失败| MENU

    PLAYING --> FRAME["处理一帧：输入 → AI Tick → 绘制"]
    FRAME -->|继续| PLAYING
    FRAME -->|Pause / 玩家死亡| PAUSED["PAUSED"]
    PAUSED -->|Resume| PLAYING

    FRAME -->|"Player 到达 stairs"| FLOOR["nextFloor()"]
    FLOOR --> CLOSEOLD["关闭旧 Enemy runtime"]
    CLOSEOLD --> REBUILD["重建 world、Player、Enemy、stairs"]
    REBUILD --> PRIME["生成新楼层初始观察"]
    PRIME --> PLAYING

    PLAYING -->|:q| SAVE["保存游戏"]
    PAUSED -->|:q| SAVE
    SAVE --> QUIT["QUIT"]
    MENU -->|Quit| QUIT
    QUIT --> FINALLY["finally: closeAllEnemyRuntimes()"]
```

关键入口：

1. [`Main.main`](byog/Core/Main.java) 无参数时调用 `Game.playWithKeyboard()`。
2. [`Game.playWithKeyboard`](byog/Core/Game.java) 持有状态机和渲染循环。
3. 新游戏或读档成功后，`beginAgentRun()` 创建新的 `runId`、清零 `logicalTick`，并调用
   `primeEnemyObservations()`。
4. 暂停和菜单仍会绘制，但不会调用 `runPlayingTick()`，因此不会推进 `logicalTick`。
5. 换层会关闭旧 Enemy runtime，重建实体并生成新楼层的初始观察。
6. 游戏退出使用 `finally` 关闭所有 Enemy runtime，避免未来接入 Session 后泄漏资源。

---

## 2. Game Loop 与 AI 架构

### 2.1 架构概览

下图保留原设计图，不做修改。它同时包含当前 Java 实时闭环和尚未实现的异步 Agent 链路；
具体实现状态以 §0 和 §5 为准。

```mermaid
graph TD
    W["已提交的世界状态"] --> P["PerceptionSystem<br/>敌人私有感知"]

    P --> E["ObservationEnvelope<br/>更新 latest / 按需发送慢脑"]
    E --> O["outbound queue<br/>合并 observation 与事件"]

    P --> D{"action cooldown 到期？"}
    D -->|否| T["结束本 tick<br/>等待下一 logical tick"]
    T --> W
    D -->|是| RO["ReflexObservation<br/>Java 本地最新快照"]
    RO --> A["IntentArbiter<br/>规则、反射、战略优先级"]
    A --> R["ReflexController + ClassicalPlanner<br/>Java 快脑"]
    R --> Q["ActionQueue<br/>原子动作缓冲"]
    Q --> X["执行最多一个 Action"]
    X --> C["EntityManager commit barrier<br/>flush / remove dead"]
    C --> W

    O --> S["AgentSession IO loop<br/>非阻塞连接与期限管理"]
    S --> M["Python Agent / LLM<br/>慢速战略推理"]
    M --> I["StrategicIntent<br/>目标、计划、中断策略"]
    I --> N["inbound queue"]
    N --> V["DecisionValidator<br/>身份、版本、时效、世界前提"]
    V --> L["IntentLease<br/>有效期与决策版本"]
    L --> A

    F["RuleBasedBrain<br/>冷启动、断线、硬超时 fallback"] --> A
    C --> B["ActionOutcome + WorldEvent"]
    B --> O
```

### 2.2 当前闭环的边界

当前真正闭合的是 Java 本地循环：

```text
已提交世界
  → latest ObservationEnvelope
  → ReflexObservation / RuleBasedBrain
  → IntentArbiter
  → StrategicIntent
  → ClassicalPlanner
  → ActionQueue
  → 一个 Action
  → EntityManager commit
  → 新 ObservationEnvelope + ActionOutcome
```

尚未闭合的是网络循环：

```text
ObservationEnvelope / ActionOutcome
  ⇢ outbound queue
  ⇢ AgentSession / TCP
  ⇢ Python Agent
  ⇢ submit_intent
  ⇢ inbound queue
  ⇢ DecisionValidator
```

因此当前游戏能够完整运行本地 AI，但不会向 Python 发送 observation，也不会在生产路径收到远程 intent。

---

## 3. Phase 2.2：代码执行图

Phase 2.2 把一个 `PLAYING` tick 固定为 `poll → execute → commit → collect`。
每个 Enemy 使用上一次提交后的 Observation 做决定，达到冷却时最多执行一个 Action；
所有 Enemy 执行完后统一提交位置和死亡状态，再生成下一轮 Observation 与 ActionOutcome。
因此动作、空间索引、反馈和下一轮感知都对应同一个明确的世界版本。

### 3.1 Production AI Tick

```mermaid
sequenceDiagram
    autonumber
    participant G as Game
    participant L as AiTickLoop
    participant M as EntityManager
    participant E as Enemy snapshot
    participant D as Arbiter / Brain / Planner
    participant V as PerceptionSystem

    G->>G: runPlayingTick()
    G->>G: player.updateCharge() / updateHitTimer()
    G->>G: ensureRunIdentity()
    G->>G: new AiTickContext(runId, floorLevel, logicalTick)
    G->>L: run(context, world, entityMgr, player)

    L->>M: snapshot(): getAllEntities()
    M-->>L: Enemy 引用列表（包含随后可能死亡的 Enemy）

    loop 每个快照 Enemy
        L->>E: pollAgentMessages(context)
        Note right of E: 当前仅检查 closed / null context
    end

    loop 每个快照 Enemy
        L->>E: executeOneAction(context, world, entityMgr)
        E->>E: closed/alive/pending/cooldown/observation 检查
        opt 到达行动冷却
            E->>D: decide → intent → plan
            D-->>E: 至多一个 Action
            opt Action != null
                E->>E: Action.execute()
                E->>E: 保存 PendingAction
            end
        end
    end

    L->>M: flushPendingChanges()
    M->>M: 用最新 position 重建索引<br/>加入 pendingAdd<br/>清空 frameOccupied
    L->>M: removeDeadEntities()

    loop 每个仍存活的快照 Enemy
        L->>E: collectAgentUpdates(...)
        E->>M: findEntityAt(self.position)
        M-->>E: 已提交索引中的 self
        E->>V: computeObservation(已提交世界)
        V-->>E: ObservationEnvelope
        E->>E: 更新 latestObservation<br/>latestReflexObservation<br/>cachedVisibleMask
        opt PendingAction != null
            E->>E: 创建 ActionOutcome
            E->>E: pendingAction = null
        end
    end

    loop 每个已经死亡的快照 Enemy
        L->>E: closeAgentRuntime()
        E->>E: 清空 ActionQueue / queuedDecisionId / pendingAction
    end

    L-->>G: return
    G->>G: logicalTick++
```

### 3.2 `executeOneAction()` 调用链与分支

Arbiter 只决定本次由谁控制；具体 Action 仍由 Java Planner 生成。
Reflex 分支直接规划单步，Lease 和 Local Fallback 分支则通过跨 tick 的 ActionQueue 延续计划。

```mermaid
sequenceDiagram
    autonumber
    participant E as Enemy
    participant A as IntentArbiter
    participant C as ReflexController / RuleBasedBrain
    participant P as ClassicalPlanner
    participant Q as ActionQueue
    participant X as Action

    E->>E: executeOneAction(context, world, entityMgr)
    E->>E: 检查 runtime、存活状态、PendingAction、冷却和 Observation

    alt 本 tick 不可行动
        E-->>E: return
    else 到达 action tick
        E->>A: decide(latestReflexObservation, logicalTick)
        A-->>E: ArbiterDecision

        alt P1_REFLEX / Safety Reflex：玩家相邻
            E->>C: createAdjacentAttackIntent(reflex)
            C-->>E: ATTACK intent
            E->>E: planSingleAction(intent, world, entityMgr)
            E->>P: translateBounded(intent, maxActions=1)
            P-->>E: Action 或 null
            E->>A: getCurrentLease() / getOverrideReason()
            A-->>E: Lease 或 null / P1 reason
            E->>E: 有效 Lease 则继承 decision/source<br/>否则沿用或创建本地 decision
            Note over E,Q: 不消费、不替换被暂挂 Lease 的 ActionQueue

        else P2_REFLEX / Visible Threat Reflex：玩家可见且允许接战
            E->>C: createEngageIntent(reflex)
            C-->>E: CHASE intent
            E->>E: planSingleAction(intent, world, entityMgr)
            E->>P: translateBounded(intent, maxActions=1)
            P-->>E: Action 或 null
            E->>A: getCurrentLease() / getOverrideReason()
            A-->>E: Lease 或 null / P2 reason
            E->>E: 有效 Lease 则继承 decision/source<br/>否则沿用或创建本地 decision
            Note over E,Q: 不消费、不替换被暂挂 Lease 的 ActionQueue

        else P3_LEASE / Active Intent Lease：存在可用 Lease
            E->>A: getCurrentLease()
            A-->>E: IntentLease

            alt lease.decisionId != queuedDecisionId
                E->>E: currentStrategy = lease.intent.strategy
                E->>P: translateBounded(lease.intent, highWater)
                P-->>E: 有界 Action 前缀
                E->>Q: replaceWithBoundedPrefix(actions)
                E->>E: queuedDecisionId = lease.decisionId
            else 同一 decision
                E->>Q: needRefill()
                Q-->>E: true / false
                opt queue.size <= lowWater
                    E->>P: translateBounded(lease.intent, remainingCapacity)
                    P-->>E: 补充 Action
                    E->>Q: appendBounded(actions)
                end
            end

            E->>Q: poll()
            Q-->>E: Action 或 null
            E->>E: decision/source 取自 Lease<br/>selectDecision(decisionId, source)

        else P4_LOCAL_FALLBACK / Local Fallback：没有可用 Lease
            E->>C: thinkFromObservation(latestObservation)
            C-->>E: 本地 StrategicIntent
            E->>E: startLocalDecision(context)
            E->>A: adoptLocalFallbackLease(intent, decisionId,<br/>observationSeq, tick, TTL=30)
            A-->>E: Local IntentLease
            E->>P: translateBounded(intent, highWater)
            P-->>E: 有界 Action 前缀
            E->>Q: replaceWithBoundedPrefix(actions)
            E->>E: queuedDecisionId = currentDecisionId
            E->>Q: poll()
            Q-->>E: Action 或 null
        end

        alt action == null
            E-->>E: return
        else 得到一个 Action
            E->>X: execute(world, enemy)
            X-->>E: ActionResult
            E->>E: actionsExecutedForDecision++
            E->>E: 保存 PendingAction，等待 commit 后 collect
        end
    end
```

| Arbiter 返回值 | 实际调用链 | `ActionQueue` | 每次冷却执行量 |
|---|---|---|---|
| `P1_REFLEX` | `createAdjacentAttackIntent` → `translateBounded(..., 1)` | 不读、不写 | 0 或 1 |
| `P2_REFLEX` | `createEngageIntent` → `translateBounded(..., 1)` | 不读、不写 | 0 或 1 |
| `P3_LEASE` | Lease intent → 必要时规划/补充 → `poll()` | 跨 tick 保留 | 0 或 1 |
| `P4_LOCAL_FALLBACK` | `thinkFromObservation` → 本地 Lease → 重新规划 → `poll()` | 替换旧队列 | 0 或 1 |

### 3.3 `execute` 与 `collect` 的补充说明

- `executeOneAction()` 先检查 runtime、存活状态、`PendingAction` 和冷却；满足条件后按 P1–P4 选择控制来源，经 Java Planner 得到并执行至多一个 Action。
- P1/P2 直接规划单步，不修改被暂挂 Lease 的 `ActionQueue`；P3/P4 从有界队列中消费动作。
- 动作执行后只保存 `PendingAction`。`collectAgentUpdates()` 在 commit 后校验空间索引和 tick context，生成新 Observation，并把 `PendingAction` 补全为 `ActionOutcome`。

### 3.4 一个 Action 前后的字段

| 字段 | execute 前 | `Action.execute()` 后 | commit + collect 后 |
|---|---|---|---|
| `Entity.position` | 上次已提交位置 | Move 成功时已改变 | 空间索引与其一致 |
| `positionIndex` | 上次提交结果 | 仍是旧索引 | `flushPendingChanges()` 重建 |
| `frameOccupied` | 本 tick 已 claim 位置 | Move 成功时加入新位置 | flush 时清空 |
| `pendingAction` | 必须为 `null` | 保存原始 result 和 beforePosition | 转成 `ActionOutcome` 后清空 |
| `latestObservation` | 上次 collect 的快照 | 不变 | 替换为已提交世界的新快照 |
| `latestReflexObservation` | 上次 collect 的切片 | 不变 | 从新 Observation 重建 |
| `lastActionOutcome` | 上一个完成结果 | 不变 | 有动作时被本次结果替换 |

### 3.5 代码阅读捷径

| 看到的代码 | 当前真实行为 |
|---|---|
| `pollAgentMessages()` | 没有 Session、队列或 Socket；当前是生命周期空操作 |
| `tickCounter++` | 先计数，达到 `moveInterval` 才进入仲裁；随后清零 |
| `latestObservation == null` | 直接跳过动作；初始值由 `primeEnemyObservations()` 提前生成 |
| `translateBounded()` | 相邻 `ATTACK` 直接生成 `AttackAction`；其他情况走 BFS，只取有界前缀 |
| `ActionQueue(2, 5)` | 长度 `<= 2` 时补充，最多保存 5 个动作 |
| `flushPendingChanges()` | 重建索引前，刚离开的旧位置仍会阻挡同 tick 后行动 Enemy |
| `removeDeadEntities()` | 死亡实体在所有 execute 结束后统一移出索引 |
| `lastActionOutcome` | 单值字段，不是反馈队列；可被下一次结果覆盖 |

---

## 4. Phase 2.3：代码决策图

Phase 2.3 负责决定当前动作的控制权。代码先处理 Safety Reflex 和
Visible Threat Reflex，再尝试继续 Active Intent Lease；没有可用 Lease 时，
由 Local Fallback 生成一份短期本地 Lease。Reflex Override 暂时覆盖原计划，
但不会直接销毁其 ActionQueue。

### 4.1 `IntentArbiter.decide()` 实际分支

```mermaid
flowchart TD
    START["decide(reflex, currentTick)"] --> TTL["currentLease?.refreshExpiry(currentTick)"]
    TTL --> P1{"shouldTriggerP1?<br/>可见玩家相邻"}

    P1 -->|是| O1["beginOrUpdateReflexOverride<br/>首次进入时 suspend 当前 Lease"]
    O1 --> R1["return P1_REFLEX<br/>Safety Reflex"]

    P1 -->|否| POLICY["有效 Lease 的 InterruptPolicy<br/>否则 safeDefault()"]
    POLICY --> P2{"shouldTriggerP2?<br/>玩家可见、非相邻、允许接战"}

    P2 -->|是| O2["beginOrUpdateReflexOverride<br/>首次进入时 suspend 当前 Lease"]
    O2 --> R2["return P2_REFLEX<br/>Visible Threat Reflex"]

    P2 -->|否| OVERRIDE{"overrideActive?"}
    OVERRIDE -->|是| END["endReflexOverride()"]
    END --> RESUME{"TTL 和 intent 前提<br/>仍满足?"}
    RESUME -->|是| ACTIVE["resumeAt(currentTick)"]
    RESUME -->|否| STALE["markStale()"]
    ACTIVE --> P3
    STALE --> P3
    OVERRIDE -->|否| P3{"currentLease.isUsableAt(currentTick)?"}

    P3 -->|是| R3["return P3_LEASE<br/>Active Intent Lease"]
    P3 -->|否| R4["return P4_LOCAL_FALLBACK<br/>Local Fallback"]
```

### 4.2 四个返回值落到 `Enemy` 后做什么

| Level | intent 来源 | decision 归属 | 队列变化 | Action 来源 |
|---|---|---|---|---|
| `P1_REFLEX` | `ReflexController.createAdjacentAttackIntent()` | 有效 Lease；否则本地 decision | 保留不动 | Planner 直接返回至多一个 |
| `P2_REFLEX` | `ReflexController.createEngageIntent()` | 有效 Lease；否则本地 decision | 保留不动 | Planner 直接返回至多一个 |
| `P3_LEASE` | `currentLease.getIntent()` | Lease 的 ID 和 Source | 新 decision 时 replace；低水位时 append | `actionQueue.poll()` |
| `P4_LOCAL_FALLBACK` | `RuleBasedBrain.thinkFromObservation()` | 新建 `local-{agentId}-tick-{tick}` | replace，并安装 30 tick 本地 Lease | `actionQueue.poll()` |

### 4.3 Reflex 与 Policy 的实际使用

| 代码项 | 实际是否影响运行 | 用途 |
|---|---|---|
| `ReflexObservation.canSeePlayer()` | 是 | P2、CHASE/ATTACK Lease 恢复 |
| `ReflexObservation.isPlayerAdjacent()` | 是 | P1 |
| `InterruptPolicy.engageVisiblePlayer()` | 是 | 允许或禁止 P2 |
| `InterruptPolicy.respondToAdjacentThreat()` | P1 不读取 | Java 始终允许 Safety Reflex |
| `InterruptPolicy.allowLocalReroute()` | 当前生产动作链未读取 | 已保存但尚未接入 reroute |
| `Enemy.reflexController` | 是 | 创建 P1/P2 intent |
| `IntentArbiter.reflexController` | 是 | 判断 P1/P2 是否触发 |

### 4.4 `IntentLease` 状态变化

```mermaid
stateDiagram-v2
    [*] --> ACTIVE: new IntentLease

    ACTIVE --> SUSPENDED: 首次 Reflex Override
    SUSPENDED --> ACTIVE: canResumeLease && resumeAt

    ACTIVE --> STALE: 被新 Lease 替换
    SUSPENDED --> STALE: 恢复前提失败

    ACTIVE --> EXPIRED: currentTick >= validUntilTick
    SUSPENDED --> EXPIRED: currentTick >= validUntilTick

    note right of ACTIVE : isUsableAt = ACTIVE 且 tick < validUntilTick
```

### 4.5 Lease 恢复条件

| Strategy | `canResumeLease()` 返回 true 的条件 |
|---|---|
| `CHASE` / `ATTACK` | 玩家仍可见，且 intent 目标等于当前可见玩家位置 |
| `PATROL` | 玩家仍不可见 |
| `GUARD` | 始终满足策略前提 |
| 任意 Strategy | Lease 必须仍在 TTL 内 |

### 4.6 远程 intent 代码路径

远程 proposal 进入 Java 后必须先通过身份、知识边界和当前世界状态校验，
校验成功只会安装 Lease，不会绕过 Planner 或直接操作实体。
当前生产游戏尚未把 `pollAgentMessages()` 接到该入口。

```mermaid
flowchart LR
    POLL["pollAgentMessages()"] -.->|当前未连接| ENTRY["Enemy.tryAdoptRemoteIntent()"]
    ENTRY --> CLOSED{"runtime closed?"}
    CLOSED -->|是| IDERR["IDENTITY_MISMATCH"]
    CLOSED -->|否| VALIDATE["DecisionValidator.validate()"]

    VALIDATE --> ID["schema / identity / epoch<br/>generation / decision / observation"]
    ID --> DATA["skill / confidence / TTL / policy<br/>target 类型"]
    DATA --> WORLD["已知目标 / 边界 / tile / BFS"]
    WORLD --> NOW["当前 Reflex 前提"]
    NOW --> RESULT{"ACCEPTED?"}

    RESULT -->|否| KEEP["不修改 Lease / Queue / cooldown"]
    RESULT -->|是| CONVERT["toStrategicIntent()<br/>fromData()<br/>计算 validUntilTick"]
    CONVERT --> NEW["旧 Lease → STALE<br/>currentLease → REMOTE_AGENT Lease"]
    NEW --> NEXT["下次 P3 发现 decisionId 变化<br/>replace ActionQueue"]
```

### 4.7 当前游戏实际覆盖

| 路径 | 游戏运行时 |
|---|---|
| Safety Reflex (`P1`) | 已启用 |
| Visible Threat Reflex (`P2`) | 已启用 |
| Active Intent Lease (`P3`) | 已启用；当前主要执行本地 Lease |
| Local Fallback (`P4`) | 已启用 |
| Remote Agent Lease | 校验和采纳代码已实现，但没有生产消息来源调用 |

---

## 5. AgentSession 核心与尚未接线模块

### 5.1 模块连接图

实线表示已经实现的 Session/transport 关系；虚线表示生产接线或尚未实现的 Python 模块。

```mermaid
flowchart LR
    POLL["Enemy.pollAgentMessages()"] -.-> SESSION["AgentSession<br/>生命周期与请求状态"]
    COLLECT["Enemy.collectAgentUpdates()"] -.-> SESSION
    CLOSE["Enemy.closeAgentRuntime()"] -.-> SESSION

    SESSION --> INQ["bounded inbound queue"]
    SESSION --> OUTQ["bounded outbound queue"]
    SESSION --> CLOCK["MonotonicClock"]
    SESSION --> HANDLER["AgentHandler"]
    SESSION --> TRANSPORT["AgentTransport<br/>生命周期控制面"]

    INQ --> HANDLER
    HANDLER -.-> VALIDATOR["DecisionValidator<br/>已实现"]
    VALIDATOR --> ARBITER["IntentArbiter<br/>已实现"]

    OUTQ --> IO["SocketTransport<br/>TCP IO worker"]
    TRANSPORT --> IO
    IO --> TCP["persistent NDJSON TCP"]
    TCP -.-> PY["Python Agent runtime"]
    PY -.-> TCP
    IO --> INQ

    COLLECT -.->|"ObservationEnvelope<br/>ActionOutcome"| OUTQ
    INQ -.->|"submit_intent<br/>cancel_ack"| POLL
```

### 5.2 模块状态

| 模块 | 状态 | 责任 |
|------|------|------|
| `AgentProtocol` / `AgentProtocolCodec` | **已实现** | 类型化消息和 NDJSON 编解码 |
| `DecisionValidator` | **已实现** | proposal 身份、权限、知识和世界前提校验 |
| `IntentArbiter` / `IntentLease` | **已实现** | 采纳后的控制权与有效期 |
| `AgentSession` | **核心已实现** | single in-flight、deadline、cancel、队列和生命周期 |
| `AgentSessionConfig` | **已实现** | 地址、容量、deadline 和重连配置快照 |
| `AgentHandler` | **已实现** | 在游戏线程接收消息，并显式返回 intent 接受/拒绝结果 |
| `MonotonicClock` | **已实现** | 为 deadline 提供可测试的单调时间 |
| `AgentTransport` | **已实现** | `SocketTransport` 绑定 endpoint、请求物理重建、永久关闭 transport |
| inbound/outbound queues | **已实现** | 隔离游戏线程与 IO，提供有界背压 |
| TCP IO worker | **已实现** | 唯一操作 connect/read/write 的线程；短读超时保证双向推进 |
| Python Agent runtime | **未实现** | 消费 observation/feedback，返回受限 intent |

更完整的 Session 状态机、失败语义和测试说明见 [`session.md`](session.md)。

### 5.3 当前 Session API

以下代码块概括当前源码的主要接口：

```java
public final class AgentSession implements AutoCloseable {
    public void pollInbound(AgentHandler handler, long logicalTick);

    public RequestStartResult requestIntent(
            ObservationEnvelope observation,
            List<AgentProtocol.WorldEventData> pendingEvents,
            long logicalTick);

    public EnqueueResult sendActionFeedback(
            ActionOutcome outcome, long logicalTick);

    public void advanceRequestLifecycle(long logicalTick);

    public void supersedeCurrentRequest(
            SupersedeReason reason, long logicalTick);

    @Override
    public void close();
}
```

```java
public interface AgentHandler {
    IntentHandlingResult onIntentSubmitted(
            AgentProtocol.SubmitIntentData data,
            AgentProtocol.Envelope envelope,
            AgentSession.RequestContext requestContext);

    void onCancelAcknowledged(
            AgentProtocol.CancelAckData data,
            AgentProtocol.Envelope envelope,
            AgentSession.RequestContext cancelledRequest);

    void onProtocolRejected(ProtocolFailure failure);
}
```

```java
public interface AgentTransport extends AutoCloseable {
    void start(AgentSession.TransportEndpoint endpoint);
    void requestRebuild();
    void close();
}
```

```java
@FunctionalInterface
public interface MonotonicClock {
    long nanoTime();
}
```

### 5.4 接入当前 Game Loop 的位置

未来实现不能另建一套 Game Loop，只能填入当前 seam：

| 当前位置 | 接入行为 |
|----------|----------|
| `Enemy.pollAgentMessages()` | drain 已解析 inbound；调用 Handler；合法 proposal 进入 Validator/Arbiter |
| `Enemy.collectAgentUpdates()` | 将最新 observation 和已完成 outcome 有界入队 |
| `Enemy.closeAgentRuntime()` | 取消请求、关闭 Session、解除阻塞 IO，并有界等待线程退出 |
| `Game.beginAgentRun()` | 为新游戏/读档创建新的 Session identity |
| `Game.nextFloor()` | 关闭旧楼层 Session；新 Enemy 使用新 floor identity |
| `Game.playWithKeyboard()` 的 `finally` | 无论正常退出还是异常都关闭 Session |

游戏线程必须遵守：

```text
允许：queue.offer / drain 已解析消息 / 读取 Session 状态
禁止：Socket.connect / read / write / Future.get / 等待 Python
```

### 5.5 队列与失败语义

最小接口必须显式区分：

```text
ACCEPTED
COALESCED
DROPPED_LOW_PRIORITY
REJECTED_CRITICAL
CLOSED
```

- observation 可以合并为最新值；
- heartbeat 可以低优先级丢弃；
- action feedback 和 cancel 不能静默覆盖；
- 队列满也不能阻塞游戏线程；
- 关键消息无法入队时必须进入 degraded/reconnect 路径并记录原因。

---

## 6. 架构约束

### 6.1 Java 是世界权威

Agent 只能提交受限 `StrategicIntent`，不能：

- 直接移动实体；
- 直接提交任意原子 Action；
- 绕过碰撞、边界或知识来源校验；
- 关闭 Java 强制执行的 Safety Reflex；
- 读取 Enemy 私有观察之外的信息。

### 6.2 游戏线程永不等待外部 Agent

Python 慢、断线或未启动时：

```text
可用旧 Lease → 继续
没有可用 Lease → Local Fallback
当前出现紧急威胁 → Safety / Visible Threat Reflex
```

模型延迟只能影响战略新鲜度，不能暂停输入、AI Tick 或渲染。

### 6.3 时间和身份必须分开

| 数据 | 用途 |
|------|------|
| `logicalTick` | 游戏因果顺序、Lease TTL、proposal 时效 |
| monotonic time | 网络 soft/hard deadline |
| `runId + floorId + agentId` | 世界身份 |
| `sessionEpoch + requestGeneration` | Session 和请求身份 |
| `observationSeq + decisionId` | 观察与决策关联 |

wall-clock 不能参与 canonical 正确性判断。

### 6.4 当前实现必须长期保持的五条不变量

1. 一个 Enemy 每次冷却最多执行一个 Action。
2. 所有 Enemy execute 完成后，世界只统一 commit 一次。
3. collect 只能读取 commit 后的世界。
4. Reflex 只能使用私有 observation。
5. 任何远程 proposal 必须先通过 Validator，再成为 Lease。

---

## 7. 最短代码阅读顺序

想理解当前运行时，只需按以下顺序：

1. [`Main.main`](byog/Core/Main.java)
2. [`Game.playWithKeyboard` 与 `runPlayingTick`](byog/Core/Game.java)
3. [`AiTickLoop.run`](byog/AI/AiTickLoop.java)
4. [`Enemy.pollAgentMessages / executeOneAction / collectAgentUpdates`](byog/Entity/Enemy.java)
5. [`IntentArbiter.decide`](byog/AI/IntentArbiter.java)
6. [`ReflexController`](byog/AI/ReflexController.java)
7. [`IntentLease`](byog/AI/IntentLease.java)
8. [`DecisionValidator`](byog/AI/DecisionValidator.java)
9. [`ActionQueue`](byog/Action/ActionQueue.java) 与
   [`ClassicalPlanner`](byog/AI/ClassicalPlanner.java)
10. [`AgentSession`](byog/Bridge/AgentSession.java) 与
    [`session.md`](session.md)
11. [`EntityManager.flushPendingChanges`](byog/Entity/EntityManager.java) 与
    [`PerceptionSystem.computeObservation`](byog/Perception/PerceptionSystem.java)

读完第 4 项可以理解 Game Loop；读完第 8 项可以理解当前双速控制链；第 10 项说明已经实现但尚未接入 Game/Enemy 的 Session 核心。
