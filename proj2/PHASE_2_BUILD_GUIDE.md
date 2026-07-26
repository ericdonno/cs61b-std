# DungeonMind Phase 2 构建指南

> 面向第一次接触这类工程化改造的实现者：按可验证的小步，把 Phase 1 的同步敌人 AI 改造成“本地快脑持续行动、远程慢脑异步给出战略意图”的双速系统。

## 先读这里

Phase 2 的目标不是“让 Python 控制 Enemy 的下一帧”，而是建立一条不会拖住游戏线程、可以稳定测试的事件闭环：

```text
Java 提交后的世界
  → 私有 Observation
  → Python fake Agent 提交战略 Intent
  → Java 校验并签发 IntentLease
  → Planner 生成有界 ActionQueue
  → 每个 action tick 最多执行一个 Action
  → Java 生成 ActionOutcome 和 feedback
```

等待 Python、Python 断线或 Python 返回坏消息时，Enemy 仍要继续行动。Java 始终拥有世界状态、碰撞、能力、动作执行和最终裁决权。

这份指南回答“按什么顺序改、每一步在哪里停下来验证”。精确协议、状态转换和测试断言以 [PHASE_2_SPEC.md](PHASE_2_SPEC.md) 为准；系统动机和长期架构见 [AI_TICK_ARCHITECTURE.md](AI_TICK_ARCHITECTURE.md)。

阅读时不需要先掌握网络和并发。先读“从 Phase 1 到 Phase 2”，建立整体图景；施工时再从 2.1 开始顺序执行。每一节都会尽量回答四个问题：

1. 这个新概念是什么。
2. Phase 1 为什么没有它也能工作，Phase 2 为什么必须加入它。
3. 它应该放进哪个类、在哪个 tick 阶段生效。
4. 怎样用测试证明实现正确。

文中的代码多数是**接口草图或伪代码**，用于表达职责和顺序，不一定能原样复制编译；字段的最终名称和类型以 Spec 为准。

> [!IMPORTANT]
> 当前 `PHASE_2_SPEC.md` 的元数据仍标记为 Draft。若尚未得到 Builder 审批，先完成审批，不要开始通信层、Game loop 或 ActionQueue 重构。若本文与 Spec 冲突，以 Spec 为准并同步修正文档。

### 文档分工

| 文档 | 解决的问题 |
|------|------------|
| [DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md) | 为什么做 Phase 2，以及它与后续阶段的关系 |
| [PHASE_2_SPEC.md](PHASE_2_SPEC.md) | 必须满足的契约、状态表、跨进程字段规则和验收矩阵 |
| 本文 | 实现顺序、代码落点、阶段闸门和排错路径 |
| `PHASE_2_COMPLETION.md` | 完成后的命令、测试结果、故障演练和偏差证据 |

### 完成后的用户体验

- Python 正常：Enemy 接受远程 `PATROL / CHASE / ATTACK / GUARD` 战略意图。
- Python 很慢：Enemy 继续安全旧计划；没有旧计划时使用本地 Brain。
- 玩家突然贴近：Java 快脑不等 Python，在下一个 action tick 立即响应。
- Python 断线或停止读取：游戏 logical tick 继续推进，画面不卡住。
- Python 恢复：新意图只在安全动作边界接管，不在动作执行中途改写队列。
- 两个 Enemy：各自拥有独立 Session、身份、队列和请求生命周期，不共享隐藏信息。

### Phase 2 不做什么

- 不接真实 LLM，不引入 LangChain/LangGraph 或 Tool Calling。
- 不做长期记忆、多 Agent 协作、共享上下文或全局模型预算。
- 不让 Python 直接提交 `MoveAction`、坐标写入或其他原子动作。
- 不新增 `PythonBridgeBrain`；远程 Agent 不实现同步 `EnemyBrain`。
- 不围绕 legacy `Game.playWithInputString(String)` 构建 Agent runtime。
- 不引入未经审批的 JSON、网络或并发第三方依赖。

---

## 四条不可破坏的边界

### 1. 游戏线程不碰阻塞 IO

PLAYING tick 中禁止调用：

- `Socket.connect/read/write`
- `Future.get()` 或其他等待远程结果的操作
- 无界 `Thread.join()`
- 等待 `cancel_ack` 或等待重连

游戏线程只允许非阻塞地 drain inbound queue、向有界 outbound queue enqueue、查询状态，以及发起关闭。Socket 只能由 `AgentSession` 的 IO thread 操作。

### 2. Python 只提议，Java 才能执行

远程 `submit_intent` 必须依次经过：

```text
AgentProtocolCodec
  → 完整身份校验
  → DecisionValidator
  → IntentArbiter
  → IntentLease
  → ClassicalPlanner
  → ActionQueue
  → Action.execute()
```

任何一步拒绝，都不得悄悄修改 lease、队列、cooldown 或 Entity 状态。

### 3. 所有决策只读私有知识

发给 Python 的 observation 只能包含当前 Enemy 看得见的 tile 和 entity。快脑也只能读取最新私有 observation，不能为了“更聪明”直接看 `Player` 的真实隐藏坐标或完整 `world`。后文的 FOV 是 field of view，即 Enemy 当前的视野范围。

### 4. 时间和并发必须可测试

- deadline 使用可注入的 `MonotonicClock.nanoTime()`。
- deterministic test 用 fake clock 推进时间，禁止用 `Thread.sleep()`。
- 测试注入固定 runId、decisionId 和 transport。
- wall-clock（现实钟表时间）、线程名、socket 地址和耗时只能进入诊断日志，不能进入 canonical deterministic evidence（用于稳定比较的标准 trace）。

---

## 从 Phase 1 到 Phase 2：先理解为什么要这样改

Phase 1 的生产 AI 链路仍然是同步的：

```text
Enemy.updateAI()
  → 计算 ObservationEnvelope
  → RuleBasedBrain 立即返回 StrategicIntent
  → ClassicalPlanner 立即生成 Action
  → ActionQueue
  → 当前调用里执行 Action
```

这里的“同步”是指：上一步没有返回，下一步就不能开始。`RuleBasedBrain` 是普通 Java 代码，通常在几毫秒内就会返回，所以同步执行没有问题。

Phase 2 要接入的 Python Agent 不在 Java 进程里。Java 必须把 observation 通过 Socket 发给 Python，Python 处理后再把 intent 发回来。这个过程可能耗时几毫秒，也可能耗时几秒，甚至永远没有响应。

如果直接在 `Enemy.updateAI()` 里这样写：

```text
发送 observation
等待 Python 返回
收到 intent 后继续执行
```

那么等待期间整个游戏主线程都会停住：玩家不能移动，画面不能刷新，其他 Enemy 也不能行动。这就是 Phase 2 首先要解决的问题。

### 同步和异步到底差在哪里

可以把同步调用想象成面对面问话：

> Java：“你下一步想做什么？”
>
> Python：“等我想完再告诉你。”
>
> Java 站在原地，什么都不做。

异步调用更像用无线电发消息：

> Java 把 observation 放进发件箱，然后继续运行游戏。
>
> Python 稍后把 intent 放进收件箱。
>
> Java 在下一个安全时机检查收件箱。

因此 Phase 2 不再是一次完整的“调用并返回”，而是两个发生在不同时间的事件：

```text
时刻 A：Java 发出 observation request
时刻 B：Python 返回 submit_intent
```

时刻 A 和时刻 B 之间，Enemy 不能停在原地等。它要继续旧计划，或者使用本地 Brain 临时接管。

| 名词 | 初学者可以怎样理解 |
|------|--------------------|
| **同步（synchronous）** | 调用者一直等到被调用者返回结果 |
| **异步（asynchronous）** | 先发出任务，结果以后通过另一条路径返回；调用者期间继续工作 |
| **阻塞（blocking）** | 当前线程停在某个调用上，直到 IO 或其他条件完成 |
| **非阻塞（non-blocking）** | 调用立即返回“已入队/当前无消息”等结果，不等待远端 |
| **进程（process）** | 一个独立运行的程序；Java 游戏和 Python runtime 是两个进程 |
| **线程（thread）** | 同一进程中的执行路线；游戏线程和 IO thread 可以同时推进 |

### 为什么需要“双速大脑”

远程 Agent 擅长较慢的战略判断，本地 Java 擅长立即、确定地执行规则。Phase 2 把它们分成两种速度：

- **慢脑**：Python Agent，回答“接下来一段时间巡逻、追击、攻击还是守卫？”
- **快脑**：Java `ReflexController`，回答“玩家已经贴脸，这一刻要不要立即攻击？”

慢脑输出的不是下一帧按哪个方向键，而是一个高层 intent。Java 再把它翻译成具体动作：

```text
Python: CHASE target=(3,2), validForTicks=20
               ↓
Java Validator: 这个目标是否合法、是否仍然可见？
               ↓
Java IntentLease: 允许在一段时间内执行这份追击意图
               ↓
ClassicalPlanner: 计算 BFS 路径
               ↓
ActionQueue: [向左, 向左, 向下, ...]
               ↓
每个 action tick 执行一步
```

这样做的原因是：Python 可以建议“追击玩家”，但不能绕过 Java 的碰撞、地图边界、私有知识和伤害规则。

### 用“前线士兵和无线电”理解各组件

| 组件 | 比喻 | 真正职责 |
|------|------|----------|
| `Enemy` | 前线士兵的身体 | 保存 HP、位置、动作队列和当前控制状态 |
| `PerceptionSystem` | 士兵的眼睛 | 生成当前 Enemy 的私有 observation |
| Python Agent | 远端指挥官 | 根据收到的信息提出战略 intent |
| `AgentSession` | 每名士兵自己的无线电 | 管理连接、收发队列、请求和超时 |
| `DecisionValidator` | 军令审核员 | 拒绝身份错误、越权或已经过时的 intent |
| `IntentLease` | 有期限的已批准命令 | 保存 intent、有效期、来源和中断策略 |
| `IntentArbiter` | 当班指挥权裁判 | 决定远程命令、快脑或本地 fallback 谁控制当前动作 |
| `ReflexController` | 士兵的条件反射 | 根据当前私有感知处理紧急局面 |
| `ClassicalPlanner` | 路线规划员 | 把 CHASE 等意图翻译为 MoveAction/AttackAction |
| `ActionQueue` | 接下来几步的便签 | 跨 tick 保存一个短动作前缀 |
| `ActionOutcome` | 行动报告 | 记录动作是否成功、前后位置和决策来源 |

最重要的边界是：**无线电只传消息，不移动士兵；远端指挥官只提建议，不直接修改地图。**

### 一次完整闭环是怎样发生的

下面先忽略超时和断线，看一次正常请求：

```text
1. Java 在 commit 后生成 guard-a 的 observation #5
2. AgentSession 为它生成 decision-17
3. 游戏线程把 observation 放入 outbound queue，然后继续下一 tick
4. IO thread 从 outbound queue 取消息，通过 TCP 发给 Python
5. Python 根据 observation 返回 submit_intent: CHASE
6. IO thread 读取并解析消息，放入 inbound queue
7. 下一次 poll 阶段，游戏线程取出 submit_intent
8. Validator 检查身份、参数、知识边界和时效
9. Arbiter 采纳它，生成一个 IntentLease
10. Planner 生成最多 high-water 个动作
11. execute 阶段执行其中一个动作
12. commit 后生成 ActionOutcome，并把 feedback 放入 outbound queue
```

注意第 3 步和第 7 步之间可以隔很多个 tick。游戏不会等待这次闭环结束才继续。

### 四种时间不要混在一起

Phase 2 同时出现多种“时间”，它们解决的问题不同：

| 时间 | 谁推进 | 用途 |
|------|--------|------|
| `frameCounter` | GUI 主循环 | 攻击动画等已有显示逻辑 |
| `logicalTick` | 只在 `PLAYING` 时由 Game 推进 | AI 调度、trace、lease TTL |
| cooldown / action tick | 每个 Enemy 自己计数 | 决定这个 Enemy 什么时候可以执行下一动作 |
| monotonic time | `MonotonicClock.nanoTime()` | 计算网络请求经过了多少真实时间 |

例如 `moveInterval=5` 表示这个 Enemy 每经过 5 个 PLAYING logical tick 才获得一次动作机会；它不是“一次移动耗时 5 秒”。

soft deadline 的 1500ms 和 hard deadline 的 10000ms 是现实经过时间，不能用 `logicalTick * 16ms` 猜测。测试中也不能真的等 10 秒，而是注入 fake clock，直接把时间推进到指定位置。

### 每个 PLAYING tick 的顺序

这是 Phase 2 最重要的新逻辑。正式 `Game` 和无 GUI 的测试支架（headless harness）必须使用同一顺序：

```text
0. 处理玩家输入、charge 和 hit timer
1. poll
   - drain 已解析的 inbound
   - 推进 request deadline
   - 校验并在安全边界采纳新 lease
2. execute
   - cooldown 到期时，按 P0–P4 选择控制来源
   - 最多执行一个 Action
   - 暂存 outcome 所需信息
3. commit
   - EntityManager.flushPendingChanges()
   - EntityManager.removeDeadEntities()
4. collect
   - 存活 Enemy 从提交后的世界生成新 observation
   - 完成 ActionOutcome
   - 合并 feedback/event，必要时请求新 intent
5. send
   - 这里只是有界 queue enqueue；实际传输由 IO thread 完成
6. cleanup
   - 关闭本 tick 死亡 Enemy 的 Session
```

| 名词 | 本文含义 |
|------|----------|
| `poll` | 游戏线程检查内存中的 inbound queue；不是读取 Socket |
| `drain` | 每个 tick 从队列中有界取出并处理消息；默认最多处理 8 条，不保证排空整个队列 |
| `inbound` | IO thread 已经接收、解析并放入队列的 Python → Java 入站消息 |
| `request deadline` | 当前请求等待 Agent 响应的 soft/hard 超时期限 |
| `lease` | Java 校验并采纳 intent 后生成的、带有效期和中断策略的执行许可 |
| `cooldown` | 两次 Enemy 动作之间必须等待的 logical tick 间隔 |
| `outcome` | 包含执行结果、动作前后位置、决策来源等信息的结构化动作记录 |
| `commit` | 把本 tick 的 Entity 位置和生命周期变化提交到权威索引；不是 Git commit |
| `collect` | 从 commit 后的世界收集 observation 和 feedback；不是接收网络消息 |

为什么顺序不能交换：

- `poll` 必须先于 `execute`：新 lease 只能在动作开始前接管，不能执行到一半突然换计划。
- `execute` 必须先于 `commit`：Action 先提出位置/状态变化，EntityManager 再统一更新索引。
- `collect` 必须晚于 `commit`：否则 feedback 可能说 Enemy 已到 `(10,2)`，新 observation 却仍以旧位置 `(9,2)` 为中心。
- IO thread 不能直接改 Enemy：否则它可能在 `execute` 中途清空队列，产生难以复现的竞态。

这里的“安全边界”就是：**一个 Action 尚未开始，且世界没有处在半提交状态的时点。** Phase 2 把远程 intent 的采纳固定在 poll 阶段，让这个词变成可测试的规则，而不是模糊约定。

### 为什么要给同一请求这么多 ID

跨进程消息可能迟到、重复，甚至来自上一层地图。只看 `agentId` 不足以判断一条响应是否仍然有效。

| 字段 | 回答的问题 | 什么时候变化 |
|------|------------|--------------|
| `runId` | 是不是本次启动/读档会话？ | 新游戏或读档时 |
| `floorId` | 是不是当前楼层？ | 切换楼层时 |
| `agentId` | 是哪个 Enemy？ | Enemy 身份固定，存档可保留 |
| `sessionEpoch` | 是不是当前这条物理连接？ | 成功建立新连接时递增 |
| `observationSeq` | 回答的是这个 Enemy 的第几份观察？ | 每生成一份 observation 递增 |
| `decisionId` | 回答的是哪一次请求？ | 每次新 intent request 生成 |
| `requestGeneration` | 这代请求是否已经被整体作废？ | hard timeout、supersede 或断线时递增 |
| `messageSeq` | 连接内第几条消息？ | 每个 session epoch 内递增 |

举例：Enemy 在 Floor 1 发出请求，玩家立即进入 Floor 2。Python 两秒后才返回 Floor 1 的 CHASE。如果不检查 `floorId` 和 `requestGeneration`，新楼层的 Enemy 可能会采纳一个指向旧地图坐标的命令。

可以把完整身份元组理解为包裹上的多层地址：

```text
哪次游戏 / 哪层地图 / 哪个 Enemy / 哪条连接
  / 哪份观察 / 哪次请求 / 哪一代请求
```

任一层不匹配，都只记录并丢弃，不能“差不多就用”。

### 三组状态为什么不能合成一个枚举

一个 Session 同时有三个互不替代的问题：

1. 网络连上了吗？
2. 当前是否在等待 Python？
3. Enemy 手里还有动作可执行吗？

例如下面是完全正常的组合：

```text
CONNECTED + AWAITING_INTENT + EXECUTING
```

它表示“网络正常，新的远程请求还在计算，但 Enemy 正在继续旧计划”。如果只用一个 `WAITING_FOR_AGENT` 状态，就无法表达“等待 Agent 的同时仍在行动”。

因此状态分为三组：

```text
ConnectionState = DISABLED | CONNECTING | CONNECTED | DISCONNECTED
RequestState    = NO_REQUEST | AWAITING_INTENT | SOFT_TIMED_OUT | CANCEL_PENDING
ExecutionState  = NO_PLAN | EXECUTING | BLOCKED | EXHAUSTED
```

| 状态组 | 只描述什么 | 不应该顺便描述什么 |
|--------|------------|--------------------|
| `ConnectionState` | Socket/连接情况 | 是否有动作 |
| `RequestState` | 一次 intent 请求的生命周期 | Enemy 是否在移动 |
| `ExecutionState` | 当前计划和动作队列情况 | Python 是否连接 |

`closed` 是 Session 的永久终止标记，不是第四组业务状态。完整转换规则见 Spec §7.4–§7.6，具体实现会在 2.4 展开。

---

## 实施路线

不要同时修改协议、网络和 Game loop。推荐按下面的垂直切片推进，每一阶段都先过自己的闸门：

这里的**垂直切片（vertical slice）**是指：每次只完成一小段能够独立编译、测试和解释的功能。例如 2.1 会同时做少量数据类、codec 和对应测试，因为它们合起来才构成“Java 对象能严格往返 JSON”的可验证闭环。它不是“先把所有数据类一次写完，再把所有网络类一次写完”。

| 阶段 | 先解决什么 | 暂时不碰 | 阶段闸门 |
|------|------------|----------|----------|
| 2.1 | 不可变 observation + 严格 codec | Socket、Game loop | P2-P01～P2-P06 |
| 2.2 | bridge-disabled 新 tick 骨架 | Python、真实 Session | P2-A09/A10、P2-R01/R02 |
| 2.3 | Lease、Validator、P0–P4 | TCP | P2-A01～P2-A08 |
| 2.4 | Session 无网络核心 | 真实 Socket | P2-S01～P2-S10、S13 |
| 2.5 | 单独 IO thread + TCP | Python 决策逻辑 | P2-S11/S12/S14 |
| 2.6 | Python fake runtime | 真实模型 | protocol smoke |
| 2.7 | Java ↔ Python 完整闭环 | 新玩法 | P2-I01～P2-I05 |
| 2.8 | trace、harness、聚合测试 | 性能优化 | P2-T01/T02、回归 |
| 2.9 | 配置、故障演练、交接 | Phase 3 功能 | Completion 审批 |

每个阶段遵循同一节奏：

1. 只实现本阶段的最小闭环。
2. 编译。
3. 跑本阶段测试。
4. 跑 Phase 0/1 回归。
5. 审查 diff，确认没有提前实现下一阶段。

---

## 2.1 固化 observation 和协议

> **一句话目标**：在完全没有 Socket 的情况下，Java 已经能把私有 observation 编码为严格 JSON，再无损解码；任何隐藏信息、坏 JSON 或未知 skill 都不会越过协议边界。

### 2.1.1 创建 VisibleTile：把“能查询”变成“能发送”

**做什么**：创建 `byog/Perception/VisibleTile.java`，表示 Enemy 看见的一个地图格子。

**为什么 Phase 1 的 observation 还不够**：

Phase 1 的 `ObservationEnvelope` 可以在 Java 内调用：

```java
observation.isVisible(x, y);
observation.isWalkable(x, y);
```

Python 不能调用 Java 方法。要跨进程发送，必须把可见格子的坐标、类型和可通行性变成普通数据。

可以把两者理解为：

- `visibleMask/isWalkable()` 是一本只能在 Java 图书馆里查询的目录。
- `List<VisibleTile>` 是可以装进信封寄给 Python 的页面副本。

**数据结构**：

```text
VisibleTile
  x          格子横坐标
  y          格子纵坐标
  type       FLOOR/WALL/STAIRS/NOTHING/UNKNOWN
  walkable   Enemy 是否能站上去
```

类和字段都保持不可变：

```java
public final class VisibleTile {
    private final int x;
    private final int y;
    private final String type;
    private final boolean walkable;
}
```

**为什么不用 `TETile`**：`TETile` 是 Java 游戏对象，里面可能带颜色、描述和其他运行时信息。协议只应暴露 Agent 真正需要的稳定数据，不能把整个对象图交给 serializer。

**验证**：构造一个 `VisibleTile(9, 2, "FLOOR", true)`，确认四个 getter 返回正确值，且外部无法修改字段。

### 2.1.2 在生成 observation 时固化 visibleTiles

**做什么**：修改 `PerceptionSystem` 和 `ObservationEnvelope`，在 FOV 计算完成时同时保存 `visibleTiles`。

**这里的“固化 snapshot”是什么意思**：

snapshot 是某一时刻状态的照片。照片拍完以后，真实世界继续变化，照片内容不能跟着改变。

例如 tick 42 时 `(10,2)` 是墙，tick 43 门被打开。如果 tick 42 的 observation 在序列化时重新读取 live world，它会错误地把旧 observation 编码成“门已打开”。因此 tile 信息必须在 observation 创建时复制进去。

当前 `ObservationEnvelope` 已经：

- 防御性复制 `visibleMask`
- 在创建时固化 `walkableMask`

Phase 2 继续保持这个原则，不要重新加入 live `TETile[][]` 引用。

**怎么做**：

1. `PerceptionSystem.computeObservation()` 完成 `visibleMask` 后，按固定顺序遍历坐标。
2. 只有 `visibleMask[x][y] == true` 才创建 `VisibleTile`。
3. 将 tile 映射为稳定字符串：

   ```text
   Tileset.NOTHING → "NOTHING"
   Tileset.WALL    → "WALL"
   Tileset.FLOOR   → "FLOOR"
   Tileset.STAIRS  → "STAIRS"
   其他            → "UNKNOWN"
   ```

4. `walkable` 使用与 Java 权威移动规则一致的判断。
5. `ObservationEnvelope` 构造函数接收该列表，并用防御性复制包装为不可修改列表。
6. 新增 `getVisibleTiles()`。

**为什么要固定遍历顺序**：如果同一份地图有时按 `(x,y)`、有时按 HashSet 顺序输出，fake Agent 的 PATROL 选择和 canonical trace 都会不稳定。最简单的规则是沿用 `x` 外层、`y` 内层的确定顺序。确定性是为了让失败可重现，不代表必须把完整 gameplay 轨迹固化成 byte-for-byte golden。

**知识边界测试**：

- 可见墙本身可以出现在 `visibleTiles`。
- 墙后格子不能出现。
- 不要为不可见格子发送 `{"type":"UNKNOWN"}`；“有多少未知格子、地图边界在哪里”本身也是隐藏信息。
- serializer 只能接收 `ObservationEnvelope`，不能额外接收 `world`。

### 2.1.3 定义 AgentProtocol：先约定信件格式

**做什么**：创建 `byog/Bridge/AgentProtocol.java`，集中定义 Java 和 Python 之间允许出现的数据。

**什么是协议（protocol）**：

两个不同程序无法共享 Java 对象，只能发送字节。协议就是双方共同遵守的“信件格式”：

- 信封上必须写哪些地址？
- 信件有哪几种类型？
- 每种信件内部必须有哪些字段？
- 哪些值算非法？

如果 Java 写 `"agentID"`，Python 读取 `"agentId"`，消息就会失败。把字段集中定义在协议层可以减少这种分叉。

**什么是 DTO**：

DTO 是 Data Transfer Object，即“只负责搬运数据的对象”。它应当：

- 字段不可变
- 只有构造函数和 getter
- 不读取 world
- 不执行 Action
- 不包含网络代码

**信封和正文分开**：

```text
Envelope
  schemaVersion
  messageId/messageSeq
  runId/floorId/agentId/sessionEpoch/logicalTick
  type
  data  ← 不同消息类型的正文
```

所有消息共用 Envelope，`data` 根据 `type` 使用不同 DTO。精确字段以 Spec §8.6–§8.10 为准。

Phase 2 消息方向：

| 方向 | 消息 | 用途 |
|------|------|------|
| Java → Python | `observation` | 发起一次战略决策请求 |
| Java → Python | `action_feedback` | 告诉 Agent 上一步执行结果 |
| Java → Python | `world_event` | 发送 blocked、player spotted 等最小事件 |
| Java → Python | `heartbeat` | 长时间无业务消息时证明连接仍活着 |
| Java → Python | `cancel_request` | 告诉 Python 停止旧请求 |
| Python → Java | `submit_intent` | 提交受限战略意图 |
| Python → Java | `cancel_ack` | 确认旧请求已取消 |
| 双向 | `protocol_error` | 报告协议错误，不携带动作权限 |

**为什么 `submit_intent` 不直接使用 Java enum**：wire protocol 是跨语言契约，不应把 Java 内部枚举的所有值自动暴露成远程能力。Phase 2 只允许 `PATROL / CHASE / ATTACK / GUARD`，以后新增 Java Strategy 不会自动给 Python 新权限。

**ID 生成也要留下可替换的测试接缝（seam）**：

- 生产环境可用 UUID 生成 `messageId/decisionId`。
- 测试使用 `decision-1`、`decision-2` 这样的确定序列。

否则同一个测试每次输出不同 UUID，canonical trace 无法比较。可以引入小型 `IdGenerator` 接口或在 Session 构造时注入等价 seam；具体位置可由实现决定，但测试不能依赖随机 UUID。

### 2.1.4 实现 AgentProtocolCodec：对象和 JSON 之间的翻译器

**做什么**：创建 `AgentProtocolCodec.java`，负责：

```text
Java DTO --encode--> JSON 文本
JSON 文本 --decode--> Java DTO
```

codec 是 coder/decoder 的缩写，可以把它理解成翻译器。它只翻译和检查格式，不决定 Enemy 要做什么。

**为什么要分两层**：

```text
第一层：JSON 语法 parser
  "这是不是合法 JSON？"

第二层：schema decoder
  "这是合法 observation/submit_intent 吗？"
```

例如 `{"hp":"很多"}` 是合法 JSON，但 `hp` 应该是整数，所以不是合法协议消息。

Java classpath 没有 Gson/Jackson/`org.json`，因此本阶段实现一个受限递归下降 parser。递归下降的意思是：看到 `{` 就调用“解析对象”，看到 `[` 就调用“解析数组”，对象和数组内部再递归解析值。

最低支持：

```text
object / array / string / number / boolean / null
```

必须拒绝：

- 重复 key
- 非法字符串转义和非法 Unicode
- 尾随垃圾，例如 `{"a":1}garbage`
- `NaN`、`Infinity` 等非 JSON 数值
- 嵌套深度超过 16
- UTF-8 编码后超过 65,536 bytes
- 缺少必填字段
- 字段类型错误
- schema 未声明的未知字段

**为什么严格拒绝而不是尽量猜**：

协议边界上的“宽容”会把错误推迟到游戏逻辑中。例如把字符串 `"5"` 自动转成数字 5，看似方便，却会让 Java/Python schema 漂移而不自知。越早拒绝，错误越容易定位。

**不要这样做**：

- 不要用 regex 解析嵌套 JSON。
- 不要在 codec 里调用 `Enemy`、Planner 或 Socket。
- 不要 catch 所有异常后返回一个半空 DTO。
- 不要直接把任意 `Map<String,Object>` 交给游戏逻辑。

### 2.1.5 编写协议测试

创建 `Phase2ProtocolTest`，此时测试不需要 Python、端口、线程等待或 GUI。

| 测试 | 证明什么 |
|------|----------|
| P2-P01 | 所有消息 encode → decode 后字段不丢失 |
| P2-P02 | 坏 JSON、重复 key、错误类型、尾随垃圾被拒绝 |
| P2-P03 | 超 64KiB 和嵌套超过 16 的帧被拒绝 |
| P2-P04 | 墙后 tile/entity 没有进入 wire observation |
| P2-P05 | 未知消息只产生 protocol error，不修改 Enemy |
| P2-P06 | 未知 skill/parameter 不会生成 lease/action |

一个典型 round-trip 测试：

```text
构造 Java Envelope
  → codec.encode()
  → codec.decode()
  → 逐字段断言与原对象相同
```

知识边界测试不要只检查 `visibleTiles.size()`，还要直接搜索序列化后的 JSON，确认墙后坐标和实体类型根本没有出现。

### 2.1 阶段闸门

- [ ] `ObservationEnvelope` 仍是不可变快照。
- [ ] codec 测试完全不依赖网络和 sleep。
- [ ] wire JSON 中不存在任何墙后坐标或实体。
- [ ] parser 和 schema decoder 的错误能区分并给出可定位原因。
- [ ] Phase 0/1 的碰撞、私有感知、确定性和旧 trace schema 语义仍通过。

---

## 2.2 先让新 loop 在 bridge=false 下工作

> **一句话目标**：完全关闭 bridge、没有 Python 和网络线程时，正式 Game 已经改用 Phase 2 的新调度顺序，而且 Enemy 看起来仍和 Phase 1 一样正常行动。

### 为什么先做 bridge=false

`agent.bridge.enabled` 是 feature flag，即“功能开关”。

```text
false → 不创建 Socket/IO thread，但仍走 Phase 2 新 Game loop
true  → 在同一 Game loop 上附加远程 AgentSession
```

如果一开始就同时改 Game loop、ActionQueue、状态机和 Socket，Enemy 不动时很难判断是哪一层出了问题。先让纯 Java 路径工作，相当于先搭好公路，再让网络车辆上路。

这一步还有一个重要价值：以后 Python 没启动时，`bridge=false` 路径就是可玩的安全模式，而不是一次性测试代码。

### 2.2.1 创建三个阶段间的数据对象

**做什么**：创建 `AiTickContext`、`ReflexObservation` 和 `ActionOutcome`。

#### AiTickContext：本 tick 的公共标签

Game 在每个 PLAYING tick 创建一次 context，传给所有 Enemy：

```text
runId
floorId
logicalTick
traceContext
```

它的作用是避免每个方法各自猜“现在是哪次运行、哪一层、哪个 tick”。这类对象常被称为 context，即一组贯穿当前操作的公共信息。

#### ReflexObservation：快脑真正需要的小切片

完整 `ObservationEnvelope` 可能包含很多 visible tiles，但快脑通常只需要：

```text
自身位置/HP
当前可见实体
玩家是否可见
玩家是否相邻
相邻玩家摘要
```

`ReflexObservation.from(observation)` 从私有 observation 提取这些字段。

**为什么不直接把 `Player` 传进去**：一旦快脑持有真实 `Player` 引用，它就能在玩家离开 FOV 后继续读取精确位置，破坏 Phase 1 建立的知识边界。

#### ActionOutcome：动作执行后的结构化报告

`Action.ActionResult` 只回答 `SUCCESS/BLOCKED/...`。远程反馈和 trace 还要知道：

```text
这个动作属于哪个 decision？
是第几个动作？
动作前后坐标是什么？
由 REMOTE_AGENT、LOCAL_FALLBACK 还是 REFLEX_OVERRIDE 发起？
为什么发生覆盖？
```

这些字段组合成 `ActionOutcome`。它不是新的动作，也不改变游戏状态，只是已经发生之事的记录。

### 2.2.2 把 ActionQueue 改成跨 tick 的有界缓冲

Phase 1 的 `Enemy.updateAI()` 在一次调用里最多尝试 4 个 Action。Phase 2 改为一个 action tick 最多执行一个，因为：

- 每个动作都要产生独立 feedback。
- 两个动作之间可能收到新 intent。
- blocked 后应等到下一个 tick 再重规划，而不是同一 tick 连续试路。
- 一次执行很多步会让远程 lease 即使过时也停不下来。

`ActionQueue` 因此不再只是当前方法中的临时列表，而是跨多个 tick 保存的缓冲区。

**low-water 和 high-water** 可以想象成水箱刻度：

```text
highWater = 5  最多储存 5 个未来动作
lowWater  = 2  剩 2 个或更少时开始准备补充
```

为什么不保存完整 BFS 路径？假设一条路径有 40 步，而玩家已经改变位置。保留 40 步意味着 Enemy 还会沿旧路线走很久；只保留前 5 步，系统更容易在安全边界换计划。

建议提供：

```text
needRefill()
replaceWithBoundedPrefix(actions)
appendBounded(actions)
clear()
poll()
size()
```

- **replace**：新 lease 接管，旧路径目标已经不同，清空后装入新前缀。
- **append**：仍是同一 lease，只是队列快见底，继续补路。
- **reflex override**：通常不 replace 远程队列，而是临时绕开它执行一次反射动作。

**保留 cooldown 语义**：当前代码先 `tickCounter++`，再判断 `>= moveInterval`。重构时要用测试锁住第一次动作发生的 tick，避免 off-by-one。

### 2.2.3 把 Enemy.updateAI 拆成三个生产阶段

**做什么**：新增：

```java
pollAgentMessages(...)
executeOneAction(...)
collectAgentUpdates(...)
```

Phase 1 把“接收决策、执行动作、生成新观察”放在一个方法里。异步系统必须拆开，因为远程结果可能在任意现实时间到达，但只能在固定游戏阶段生效。

| 方法 | 做什么 | 为什么单独存在 |
|------|--------|----------------|
| `pollAgentMessages` | 处理已经解析的 inbound，推进请求状态 | 把远程结果的生效点固定在动作之前 |
| `executeOneAction` | cooldown 到期时执行至多一个动作 | 建立稳定的原子动作边界 |
| `collectAgentUpdates` | commit 后刷新 observation、完成 outcome | 保证发给 Agent 的状态已经提交 |

此时 Session 尚未实现：

- `pollAgentMessages` 可以在没有 Session 时立即返回。
- `executeOneAction` 先使用 `RuleBasedBrain + Planner` 保持本地行为。
- `collectAgentUpdates` 仍要生成 `latestObservation` 和 outcome seam，但不用发送网络消息。

不要为了等到 2.3 才实现 Arbiter，就让 2.2 的 Enemy 原地不动。2.2 的目标正是证明新 loop 在纯本地路径下能独立运行。

#### latestObservation 为什么要成为 Enemy 字段

Phase 1 每次 `updateAI()` 内部临时创建 observation，用完即丢。Phase 2 的 execute 阶段需要使用上一次 commit 后得到的 observation，而新 observation 要到本 tick collect 阶段才产生，因此 Enemy 必须保存：

```java
private ObservationEnvelope latestObservation;
```

时间关系是：

```text
tick 42 collect：生成 observation #5
tick 43 execute：使用 observation #5 做快脑判断
tick 43 collect：生成 observation #6
```

这是有意的一 tick 调度结构，不是忘了实时重算。所有 Enemy 都在相同 commit 边界观察世界，行为更确定。

#### ActionOutcome 为什么不能在 execute 中全部完成

execute 阶段可以缓存 `beforePosition`、action type 和原始结果，但权威空间索引要到 `flushPendingChanges()` 后才稳定。完整 `afterPosition` 和随后发送的 observation 在 collect 阶段读取。

这保证：

```text
feedback.afterPosition == 新 observation.self.position
```

### 2.2.4 改造 Game 的调度顺序

**做什么**：正式 `Game.playWithKeyboard()` 不再调用 `enemy.updateAI()`，而是按统一阶段调用所有 Enemy。

开始 tick 时先创建稳定快照：

```java
List<Enemy> enemiesThisTick = new ArrayList<>(...);
```

**为什么要 snapshot**：`entityMgr.getAllEntities()` 返回 Map 的视图。循环中如果 Enemy 死亡、切层或 EntityManager 重建索引，直接遍历视图可能出现 `ConcurrentModificationException`，也可能让某个 Enemy 在同 tick 被跳过或执行两次。

然后按以下结构组织：

```text
for all snapshot enemies: poll
for all snapshot enemies: execute at most one action
flush + remove dead
for all surviving snapshot enemies: collect
close sessions of enemies that died this tick
logicalTick++
```

这里不是“每个 Enemy 自己 poll→execute→collect 完再轮到下一个”。所有 Enemy 先完成同一阶段，再进入下一阶段，才能共享明确的 commit barrier。

Phase 0/1 harness 仍调用 legacy `Enemy.updateAI()`。在旧方法上标注：

```java
// Legacy Phase 0/1 harness seam.
// Production Game must not add new Phase 2 logic here.
```

不要把同一份生产逻辑同时维护在旧、新两条路径中。

### 2.2.5 接入真实 runId、floorId 和 logicalTick

Phase 1 固定场景允许使用占位身份；跨进程后不行。

**怎么做**：

- `runId`：新游戏或读档成功时生成一次。
- `floorId`：直接使用真实 `floorLevel`。
- `logicalTick`：只在 PLAYING loop 完整结束后递增。
- 暂停、菜单和单纯 draw 不递增。
- 测试 harness 注入 `"phase2-test"` 等固定值。

读档为什么生成新 runId？存档保存的是游戏世界，不是旧 Java/Python 进程之间的网络会话。读档后旧响应必须全部失效。

不要把新 runtime 接到 `playWithInputString()`；它是已废弃的 legacy seam，不是 Phase 2 生产入口。

### 2.2 阶段闸门

- [ ] bridge=false 时不会创建 Socket 或 IO thread。
- [ ] 正式 Game 使用 poll/execute/commit/collect 新路径。
- [ ] P2-A09：每个 cooldown 最多执行一个 Action。
- [ ] P2-A10：outcome 和新 observation 使用 commit 后状态。
- [ ] P2-R01：Phase 0/1 语义契约仍通过；历史 baseline 不作为 gameplay gate。
- [ ] P2-R02：新 loop 的第一次动作时点和移动 cadence 与 Phase 1 一致。
- [ ] GUI 在没有 Python 的情况下仍可正常游玩。

这一步不过，不要开始 AgentSession。否则网络问题会掩盖 tick 调度和 cooldown 问题。

---

## 2.3 加入 Lease、校验和快脑仲裁

> **一句话目标**：在不接 TCP 的纯 Java 测试中，证明“Python 只能提议，Java 决定是否采纳”，并让旧计划、快脑和本地 fallback 按明确优先级协作。

### 2.3.1 创建 InterruptPolicy：远程命令允许怎样被打断

**做什么**：创建 `InterruptPolicy`，保存三个受限布尔开关：

```text
engageVisiblePlayer
respondToAdjacentThreat
allowLocalReroute
```

**为什么 intent 需要中断策略**：

远程 Agent 可能发出不同性质的命令：

- `CHASE`：看到玩家时继续接战很合理。
- `GUARD`：任务可能是守住某个位置，不应看到远处玩家就离岗。

因此 lease 除了“做什么”，还要表达“什么情况下允许快脑暂时接管”。

但 interrupt policy 不是 Python 的最高权限。Java 的安全规则始终更高：

- `respondToAdjacentThreat` 在 Phase 2 不能真正关闭 P1。
- Python 不能允许越界、穿墙或攻击非法目标。
- `confidence=1.0` 也不会让非法动作变合法。

提供安全默认值，例如：

```text
safeDefault:  三项均允许
guardDefault: 不主动 engage 远处可见玩家，但仍响应相邻威胁
```

### 2.3.2 创建 IntentLease：为什么 intent 不能永久生效

**做什么**：创建 `IntentLease`，把已经通过校验的 `StrategicIntent` 包装成一份有期限的执行许可。

lease 原意是“租约”。租房合同不是永久拥有房屋，而是在规定时间和条件内拥有使用权。IntentLease 同理：

```text
Python proposal：我建议追击玩家
Java lease：这份追击建议从 tick 42 起，在规定期限和条件内可执行
```

至少保存：

```text
StrategicIntent intent
decisionId
basedOnObservationSeq
adoptedAtTick
validUntilTick
InterruptPolicy
DecisionSource
LeaseState
```

`DecisionSource` 区分：

```text
REMOTE_AGENT   来自 Python
LOCAL_FALLBACK 来自 RuleBasedBrain
```

`LeaseState` 区分：

| 状态 | 含义 |
|------|------|
| `ACTIVE` | 当前可执行 |
| `SUSPENDED` | 被短期 reflex 暂挂，之后可能恢复 |
| `STALE` | 前提已过时，不得恢复 |
| `EXPIRED` | TTL 已到，不得恢复 |

**TTL 是什么**：Time To Live，即还能存活多久。`validForTicks=20` 表示 Java 采纳后只允许它在有限 logical tick 内存在，不代表必须执行满 20 tick。

**为什么需要 SUSPENDED**：

假设远程命令是“守住门口”，玩家突然贴脸：

```text
GUARD lease ACTIVE
  → P1 相邻攻击触发
  → GUARD lease SUSPENDED
  → 执行一次反射攻击
  → 威胁消失且 lease 仍有效
  → GUARD lease 恢复 ACTIVE
```

如果反射一开始就把 lease 删除，Agent 每次遇到短暂威胁都要重新思考，既浪费请求也破坏连续行为。

### 2.3.3 创建 DecisionValidator：把“能解析”与“能执行”分开

codec 只证明消息格式正确；Validator 才判断这份 intent 能不能进入游戏。

例如：

```text
{"skill":"CHASE","targetPosition":{"x":999,"y":999}}
```

它可能是合法 JSON，也符合字段类型，但目标越界，所以必须被 Validator 拒绝。

**校验分两层，顺序不要反**：

第一层检查身份：

```text
schemaVersion
runId
floorId
agentId
sessionEpoch
observationSeq
decisionId
requestGeneration
```

第二层检查语义：

- skill 是否在 `PATROL / CHASE / ATTACK / GUARD` 白名单。
- parameters 是否只包含该 skill 允许的字段。
- target 是否在地图边界内。
- target 是否来自该请求允许知道的 observation/事实。
- target 是否满足当前 Java 可达性和能力规则。
- `confidence` 是否有限且在 0～1。
- `validForTicks` 是否在 1～60。
- interrupt policy 是否试图禁用 P0/P1。
- 请求产生时的前提现在是否已经过时。

**身份错误和语义错误的区别**：

- `floorId` 不匹配：这不是当前世界的消息，连内容都不应相信。
- target 越界：消息确实属于当前请求，但提议本身非法。
- `STALE_PRECONDITION`：当时可能合理，现在局势已经变化。

返回类型化 `ValidationResult`，例如 `UNKNOWN_SKILL`、`TARGET_OUT_OF_BOUNDS`、`STALE_PRECONDITION`。不要只返回自由文本 `"bad"`，否则测试和 trace 无法稳定判断失败类别。

**无副作用原则**：

Validator 只返回判断，不得：

- 清空 ActionQueue
- 重置 cooldown
- 修改 current lease
- 移动 Enemy
- “先采用 target 再继续检查”

这样拒绝测试才能断言整个运行状态完全没变。

### 2.3.4 创建 IntentArbiter 和 ReflexController

**Arbiter 是什么**：arbiter 是仲裁者。当远程 lease、当前危险和本地 Brain 都想控制 Enemy 时，它按固定优先级决定谁拥有当前动作机会。

**ReflexController 是什么**：它不是第二个长期 Brain，只处理当前私有 observation 中可以立即判断的情况，例如玩家相邻或刚进入 FOV。

优先级：

| 级别 | 当前阶段的行为 | 为什么 |
|------|----------------|--------|
| P0 | Java 拒绝死亡、越界、碰撞和非法 Action | 世界安全规则永远最高 |
| P1 | 当前 observation 中玩家相邻，立即攻击/防御 | 紧急局部反应，远程不能禁止 |
| P2 | 当前 FOV 看到玩家，允许短期 `REFLEX_ENGAGE` | 模型等待时也要及时响应 |
| P3 | 执行有效 IntentLease 的 ActionQueue | 正常战略执行 |
| P4 | 无有效计划时调用 `RuleBasedBrain` | 保证断线/首次启动不发呆 |

可以按下面的思路理解一次 action tick：

```text
if P0 判定当前动作非法:
    拒绝
else if 当前私有 observation 触发 P1:
    执行相邻攻击
else if 当前私有 observation 触发 P2 且 policy 允许:
    暂挂 lease，执行短期 engage
else if 有效 lease + queue:
    执行 queue 中一步
else:
    用 RuleBasedBrain 生成本地短期 lease
```

实际代码可以拆成多个方法，不要求写成一个巨大 `if/else`。关键是优先级和数据来源必须可见、可测试。

**ReflexController 禁止读取**：

- `Player` 的真实引用
- 完整 `world`
- 其他 Enemy 的隐藏 observation
- Python 未发送的内部状态

它只能使用 `ReflexObservation` 和 Java 权威能力检查。

### 2.3.5 分析一个“迟到 PATROL”案例

这是理解 semantic stale（语义过时）的关键例子：

```text
tick 40：guard-a 看不到玩家，发出 observation #5
tick 41：Python 正在计算 PATROL
tick 42：玩家进入 guard-a 的 FOV，P2 reflex engage 开始
tick 43：Python 才返回基于 observation #5 的 PATROL
```

这条响应的身份可能全部正确：run、floor、agent、epoch、decision 都匹配。但它的前提“没有玩家可见”已经失效。

因此不能只做 ID 校验后就用 PATROL 覆盖当前追击。Validator/Arbiter 应：

1. 识别它基于旧 observation。
2. 用当前私有 observation 检查关键前提。
3. 返回 `STALE_PRECONDITION`，或至少确保 P2 继续高于它。
4. 记录拒绝原因，不修改当前 override/queue/cooldown。

“身份正确”只说明信确实寄给了你，不说明信里的命令现在仍合理。

### 2.3.6 RuleBasedBrain 作为本地 fallback

本地 fallback 不是失败后的随便 PATROL，而是正式控制来源：

```text
没有 Session
连接断开
远程请求还没返回
lease 已耗尽/过期
远程 proposal 被拒绝
```

以上情况如果没有安全旧计划，就使用当前私有 observation 调用 `RuleBasedBrain`，并生成短 TTL 的 `LOCAL_FALLBACK` lease。

**为什么也包装成 lease**：这样远程和本地决策都经过相同的 Planner、ActionQueue、outcome 和 trace 链路，只是 `DecisionSource` 不同，不需要维护两套执行系统。

### 2.3 阶段闸门

通过 P2-A01～P2-A08，并人工确认：

- [ ] remote CHASE 只能经 Validator → Lease → Planner 产生动作。
- [ ] invalid intent 对 lease、queue、cooldown 没有副作用。
- [ ] 看不见玩家时，快脑代码路径拿不到玩家真实坐标。
- [ ] `GUARD` 只能限制 P2，不能压过相邻玩家的 P1。
- [ ] 模型等待期间继续安全旧计划；无旧计划时使用本地 fallback。
- [ ] 陈旧 PATROL 不会压过当前 `REFLEX_ENGAGE`。
- [ ] override 结束后只恢复仍有效、前提仍成立的 suspended lease。

---

## 2.4 先实现无网络 AgentSession

> **一句话目标**：先把 AgentSession 当成一个纯内存状态机来实现，用 fake clock 和 fake transport 测清所有请求、队列、超时和关闭规则；这一阶段不连接真实端口。

### 2.4.1 AgentSession 到底是什么

`AgentSession` 不是 Agent 的“大脑”，也不负责决定 CHASE 还是 PATROL。它是一个会话边界，负责：

```text
这条连接属于哪个 Enemy？
现在有没有未完成的请求？
哪条响应已经过时？
待发送/待处理消息放在哪里？
什么时候算慢、什么时候必须取消？
断线或关闭时怎样清理？
```

为什么每个 Enemy 一份 Session：

```text
guard-a → AgentSession A → TCP connection A
guard-b → AgentSession B → TCP connection B
```

如果多个 Enemy 共用一个 Session，就需要共享 request state、queue 和 identity，很容易把 A 的响应交给 B，也会形成项目明确禁止的共享上下文。

### 2.4.2 先建立可替换的时钟和传输 seam

**seam 是什么**：seam 是专门留下的替换接口。生产环境接真实实现，测试接可控制的假实现。

```text
MonotonicClock
  生产：System.nanoTime()
  测试：FakeMonotonicClock.advanceMs(...)

Transport
  生产：SocketTransport（2.5 再实现）
  测试：FakeTransport（内存队列）
```

为什么不直接在测试里启动 Socket：

- 端口可能被占用。
- 线程调度和网络延迟会让测试偶发失败。
- soft/hard timeout 需要真的等 1.5 秒和 10 秒。
- 很难精确构造“刚好在 cancel grace 前收到 ack”。

Fake clock 可以瞬间把时间推进 10 秒；Fake transport 可以精确注入下一条 inbound 消息。这样测的是 Session 逻辑，不是操作系统运气。

### 2.4.3 定义 AgentSession 的游戏线程 API

生产 API 以 Spec §8.2 为准，核心方法是：

```text
requestIntent(...)
sendActionFeedback(...)
sendWorldEvent(...)
pollInbound(...)
advanceRequestLifecycle(...)
supersedeCurrentRequest(...)
close()
```

逐个理解：

| 方法 | 调用时机 | 立即返回什么 |
|------|----------|--------------|
| `requestIntent` | collect 阶段需要新战略决策 | 开始了请求，或只合并了最新 observation |
| `sendActionFeedback` | commit 后产生 ActionOutcome | 消息是否成功进入本地队列 |
| `sendWorldEvent` | blocked/player spotted 等事件发生 | 接受、合并、丢低优先级或拒绝关键消息 |
| `pollInbound` | 每 tick 的 poll 阶段 | 无返回；有界处理内存中的消息 |
| `advanceRequestLifecycle` | 每 tick 的 poll 阶段 | 根据 clock 改变 request state |
| `supersedeCurrentRequest` | 关键前提彻底失效 | 发起取消，但不等待 ack |
| `close` | 死亡、离层或退出 | 永久终止 Session |

所有 send/request 方法都必须立即返回，不能等 IO thread。

`EnqueueResult`：

```text
ACCEPTED
COALESCED
DROPPED_LOW_PRIORITY
REJECTED_CRITICAL
CLOSED
```

这些结果描述的是本地队列，不是网络交付：

- `ACCEPTED`：进入 Java 内存队列。
- 不是“Python 已收到”。
- 更不是“Python 已执行”。

### 2.4.4 RequestContext：给一次请求拍身份证照片

当 Session 发出 observation request 时，要创建不可变 `RequestContext`，保存：

```text
完整 identity
decisionId
observationSeq
requestGeneration
请求开始的 monotonic time
soft/hard deadline 基点
源 observation 的校验上下文
```

为什么不可变：请求发出后，它代表“当时究竟发送了什么”。如果后来用 latest observation 覆盖其中字段，迟到响应可能被错误地拿去和新 observation 比较。

Session 可以同时保存：

- `currentRequest`：当前仍可能完成的请求。
- `cancelledRequest`：正在等待 cancel ack 的旧请求。
- `latestObservation`：期间收到的最新快照，留给下一次请求。

这三个对象含义不同，不要用一个可变字段反复覆盖。

### 2.4.5 三组正交状态如何落到代码

Connection、Request、Execution 三组状态已在前文解释。AgentSession 直接拥有前两组；ExecutionState 更接近 Enemy/ActionQueue，但 Session trace 需要能观察它。

常见组合：

| 组合 | 是否正常 | 含义 |
|------|----------|------|
| `CONNECTED + NO_REQUEST + EXECUTING` | 正常 | 没有远程请求，正在执行现有计划 |
| `CONNECTED + AWAITING_INTENT + EXECUTING` | 正常 | 等新 intent，同时继续旧计划 |
| `CONNECTED + SOFT_TIMED_OUT + EXECUTING` | 正常 | Agent 较慢，但游戏未停 |
| `DISCONNECTED + NO_REQUEST + EXECUTING` | 正常 | 网络断开，本地/旧计划仍在行动 |
| `CONNECTED + CANCEL_PENDING + EXECUTING` | 正常 | 正在取消旧请求，执行链不必停 |

不要写出“进入 SOFT_TIMED_OUT 就把 ExecutionState 设为 NO_PLAN”这样的跨状态副作用。请求慢和计划是否可执行是两件事。

### 2.4.6 单 in-flight：防止请求风暴

**in-flight** 指已经发出、但还没有完成或确认取消的请求。

为什么最多一个：

假设游戏每秒生成几十份 observation，而 Python 每次思考 2 秒。如果每份都发出请求：

```text
request #1 处理中
request #2 处理中
request #3 处理中
...
```

Python 会积压大量已经过时的工作；返回顺序也可能错乱。

正确行为：

```text
request #1 已发出
observation #6 到来 → 只更新 latestObservation
observation #7 到来 → 再替换 latestObservation
request #1 完成
如仍需要决策 → 用最新的 observation #7 发 request #2
```

这叫 **coalescing（合并）**：多个“只关心最新值”的更新折叠成一份最新数据。

普通 observation 更新不应自动取消当前请求。只有以下情况才 supersede：

- 楼层切换
- Enemy 死亡
- 显式取消
- 关键前提彻底失效
- hard deadline

### 2.4.7 supersede 和 cancel 为什么这么复杂

`supersede` 表示“旧请求即使以后返回，也绝不能生效”。

正确顺序：

1. 把旧 `currentRequest` 保存为只读 `cancelledRequest`。
2. 递增当前 `requestGeneration`，立即使旧 `submit_intent` 失效。
3. 发送 `cancel_request`，其中携带旧 RequestContext 的 decisionId/generation。
4. 进入 `CANCEL_PENDING`。
5. 收到匹配旧 context 的 `cancel_ack` 后释放旧请求。
6. 如果 cancel grace 内没 ack，物理关闭连接并重建。
7. 只有旧 runtime 确认停止或旧连接被关闭后，才能发下一请求。

为什么先 generation++：cancel 消息可能和旧 submit_intent 在网络中交错。只要 generation 已变化，旧 intent 无论先到还是后到都不能被采纳。

为什么 `cancel_request` 又要带旧 generation：Python 必须知道要取消的是哪一代工作，而不是刚刚递增后的新一代。

仅仅增加 `observationSeq` 不等于取消。旧 Python 任务仍在运行，也可能继续占用推理资源。

### 2.4.8 soft、hard 和 cancel grace 三道时间线

```text
请求发出
   |
   | 1500ms
   v
SOFT_TIMED_OUT：只是“慢”，不取消
   |
   | 到总计 10000ms
   v
CANCEL_PENDING：旧 generation 作废，发送 cancel
   |
   | 再等 500ms cancel grace
   v
仍无 ack：关闭物理连接并重建
```

| 时点 | 状态变化 | 保留什么 |
|------|----------|----------|
| soft deadline | `AWAITING_INTENT → SOFT_TIMED_OUT` | 请求、旧 lease、ActionQueue 全部保留 |
| hard deadline | generation++，进入 `CANCEL_PENDING` | 安全旧计划仍可保留；旧响应失效 |
| cancel grace | 未 ack 则重建 Session | latest observation 保留给新请求 |

soft timeout 的作用主要是 trace/诊断：“Agent 已经比较慢”。它不是失败，也不能导致 Enemy 每次 1.5 秒就重置计划。

deadline 使用 `MonotonicClock`。单调时钟只保证时间向前，不受用户修改系统时间影响；日期和时区不适合计算耗时。

### 2.4.9 有界队列和背压

**queue 是什么**：先进先出的消息缓冲区。游戏线程和 IO thread 速度不同，queue 让两边不必同时到场。

```text
游戏线程 --offer--> outbound queue --poll--> IO thread
游戏线程 <--poll--- inbound queue <--offer-- IO thread
```

**为什么必须有界**：如果 Python 永远不读，永久增长的 queue 最终会占满内存。有界 queue 达到容量后必须明确决定“合并、丢弃还是进入降级路径”，这套规则叫 **backpressure（背压）**。

默认容量：

| 资源 | 容量 |
|------|------|
| outbound | 32 |
| inbound | 16 |
| pending events | 16 |
| 每次 `pollInbound` 最大 drain | 8 |

不同消息价值不同：

| 消息 | 队列满时怎么办 | 原因 |
|------|----------------|------|
| heartbeat | 丢旧 heartbeat | 新旧心跳表达的信息几乎相同 |
| 未发送 observation | 用最新一份替换 | Agent 只需要最新世界快照 |
| world_event | 按类型和关联实体合并 | 避免同类事件刷屏 |
| action_feedback | 不得静默丢失 | 丢失会让 Agent 误以为动作成功 |
| cancel_request | 不得静默丢失 | 丢失会造成并发旧请求 |

关键消息无法入队时返回 `REJECTED_CRITICAL`，记录 trace，并进入 disconnect/rebuild 的降级路径；游戏线程仍立即返回。

为什么 `pollInbound` 每 tick 最多处理 8 条：即使 Python 一次发来很多消息，也不能让游戏线程整帧都花在处理网络积压上。这也是一种背压。

### 2.4.10 close 必须幂等且永久

**幂等（idempotent）** 的意思是：同一操作执行多次，最终效果与执行一次相同。

```java
session.close();
session.close(); // 不抛异常，不重新清理出错
```

关闭后：

- send/request 返回 `CLOSED`
- 不再重连
- 不再调用 handler
- queue/request 不会重新激活
- 2.5 加入 Socket 后，close 必须解除阻塞 read

为什么需要幂等：Enemy 可能因死亡被关闭一次，又在楼层 cleanup 或游戏退出时被遍历到。生命周期代码不应要求调用者精确记住“谁已经关过”。

### 2.4 阶段闸门

先通过 P2-S01～P2-S10 和 P2-S13：

- [ ] 连续 observation 只有一个 in-flight，latest snapshot 会更新。
- [ ] soft timeout 不 cancel、不清旧计划。
- [ ] hard timeout 使旧 generation 永久失效。
- [ ] cancel ack 匹配旧 RequestContext 后才能发下一请求。
- [ ] queue 满时各消息按优先级处理。
- [ ] identity 任一字段不匹配都不会触发 handler 采纳。
- [ ] close 两次安全，close 后永不重连。

所有测试使用 fake clock/transport，不访问真实端口，不用 `Thread.sleep()` 推进 deadline。

---

## 2.5 接入 TCP IO loop

> **一句话目标**：把 2.4 已经测好的 Session 状态机接到真实 localhost TCP，同时保证游戏线程 API、请求语义和测试 seam 都不改变。

### 2.5.1 先理解 TCP、Socket、client 和 server

Phase 2 使用：

```text
Java Game      = TCP client
Python runtime = TCP server
地址           = 127.0.0.1（localhost，本机）
端口           = 9876（默认）
```

**server** 先监听某个端口，等待连接。**client** 主动连接该地址。建立连接后，两边都可以持续读写字节。

**Socket** 可以理解为一条已经接通的双向电话线：

- 连接本身只传字节，不懂 observation 或 intent。
- `AgentProtocolCodec` 负责把对象变成 JSON 字节、再变回来。
- 每个 Enemy 建立自己的 Socket 连接。

**持久连接（persistent connection）** 表示一次连接建立后持续复用，不是每发一条 observation 就重新连接。这样既减少开销，也让 `sessionEpoch/messageSeq` 有清晰含义。

### 2.5.2 为什么 Socket 必须只有一个所有者

多个线程同时读同一 Socket，无法可靠判断哪一个线程会拿到下一条消息；多个线程同时写，也可能让字节交错。

Phase 2 规定只有 Session 的 IO thread 可以调用：

```text
connect
read
write
flush
socket.close（显式 close 可从清理线程调用以解除阻塞）
```

游戏线程和 IO thread 只通过线程安全的 inbound/outbound queue 交换消息：

```text
游戏线程                              IO thread
   |                                     |
   | offer Envelope                      | encode + socket.write
   +----------> outbound queue ----------+

   | handler + Validator                  | socket.read + decode
   +----------- inbound queue <----------+
```

这是一种 **producer-consumer（生产者—消费者）** 模式：

- 一边生产消息并放入 queue。
- 另一边按自己的速度消费。
- 双方不直接调用彼此的业务代码。

IO thread 可以：

- connect/reconnect
- 从 outbound queue 取消息并写出
- 读取、解析消息并放入 inbound queue
- 处理 EOF、IO error 和 protocol failure

IO thread 不可以：

- 调用 `Enemy`
- 运行 Validator/Arbiter
- 清 ActionQueue
- 移动 Entity
- 根据 world 决定 intent 是否合理

### 2.5.3 把 FakeTransport 替换为 SocketTransport

2.4 的 Session core 应依赖一个最小 transport seam，而不是到处直接 new `Socket`。

概念接口：

```text
connect()
writeFrame(bytes)
readFrame()
close()
```

生产实现 `SocketTransport` 包装 Java Socket；测试实现继续使用内存 FakeTransport。

**为什么不能删除 FakeTransport 测试**：真实 TCP 集成测试只能证明“整体能通”，无法稳定覆盖 queue 满、特定 messageSeq 重复、cancel ack 精确时序等边界。单元测试和集成测试解决不同问题，两者都保留。

### 2.5.4 NDJSON：怎样从连续字节中分出一条消息

TCP 传输的是连续字节流，没有“这一包正好是一条 JSON”的保证。一次 `read()` 可能拿到半条消息，也可能拿到两条消息。

NDJSON 约定：

```text
一行 = 一个完整 JSON object
每条消息以 \n 结束
```

示例：

```text
{"type":"observation",...}\n
{"type":"action_feedback",...}\n
```

这里的一行通常称为 **frame（帧）**，即协议层认定的一条完整消息。它不是 GUI frame。

写出流程：

```text
Envelope
  → codec.encode()
  → UTF-8 bytes
  → 检查长度
  → 写 bytes + '\n'
  → flush
```

读取流程：

```text
从字节流读到 '\n'
  → 检查字节数
  → 严格 UTF-8 解码
  → codec.decode()
  → inboundQueue.offer()
```

### 2.5.5 避免“阻塞读导致永远写不出去”

只有一个 IO thread 时，如果它一连接就永久停在阻塞 `readLine()`，游戏线程后来放入 outbound 的 observation 可能永远没有机会被写出。

需要明确的读写调度，例如：

1. 每轮先有界发送 outbound 消息。
2. 用短 `Socket.setSoTimeout(...)` 尝试读取。
3. 读超时只表示现在没有消息，不是连接失败。
4. 回到循环检查 outbound、reconnect 和 `closed`。

概念伪代码：

```text
while not closed:
    ensureConnectedOrBackoff()
    drainSomeOutboundAndWrite()
    try:
        frame = readOneFrameWithShortTimeout()
        decodeAndOfferInbound(frame)
    catch read-timeout:
        continue
    catch EOF/IO/protocol-fatal:
        disconnectAndInvalidateRequest()
```

也可以使用 Java 标准库 NIO，但不要因此再造第二套 Session 状态机。无论实现方式如何，游戏线程不能参与 Socket 读写。

### 2.5.6 真正限制 64KiB frame

下面的写法不够安全：

```java
String line = reader.readLine(); // 已经可能分配了巨大字符串
if (line.length() > limit) { ... }
```

检查发生得太晚。对方如果发送一条几百 MB、永远没有换行的输入，`readLine()` 会先不断积累内存。

实现 length-bounded byte frame reader：

1. 逐步从输入流读取字节。
2. 遇到 `\n` 结束当前 frame。
3. 超过 `maxFrameBytes` 立即 protocol failure。
4. 使用严格 UTF-8 decoder；非法字节序列拒绝。
5. codec 再检查 JSON 最大嵌套 16。

写出前也要按 UTF-8 byte length 检查，不要用 Java 字符数代替字节数。中文字符通常占多个 UTF-8 bytes。

### 2.5.7 断线、EOF 和 protocol failure

- **EOF**：对方正常或异常关闭连接，读取返回流结束。
- **IO error**：连接重置、网络栈错误等。
- **protocol failure**：连得上，但收到超大帧、坏 UTF-8 或无法解析的关键消息。

这些情况都不能让游戏线程崩溃。Session 应：

```text
ConnectionState → DISCONNECTED
关闭旧 Socket
requestGeneration++
保留 latestObservation
让 Enemy 继续本地执行
安排后台重连
```

坏消息是否每次都必须断线，以 Spec 的 fatal/non-fatal 分类为准。未知兼容消息可记录错误并忽略；破坏 frame 边界或有效关键消息无法入队属于 fatal。

### 2.5.8 指数退避重连

如果 Python 没启动，IO thread 不能每毫秒 connect 一次，否则会浪费 CPU 并刷满日志。

默认 backoff：

```text
250 → 500 → 1000 → 2000 → 4000 → 4000 ... ms
```

这叫 **exponential backoff（指数退避）**：每次失败后等待时间翻倍，直到上限。

连接成功时：

- `sessionEpoch++`
- `messageSeq` 在新 epoch 中重新从起点计数
- backoff 重置为 250ms
- 当前 generation 规则不回退
- 使用 latest observation 发起新的合法请求

为什么需要 `sessionEpoch`：旧 Socket 上已经在路上的消息即使迟到，也不能冒充新连接的消息。

### 2.5.9 close 怎样解除阻塞 read

IO thread 可能正阻塞在 read。仅设置：

```java
closed = true;
```

不会自动唤醒 Socket 读取。

`close()` 应先关闭 Socket，使阻塞 read 抛出 `SocketException` 或返回 EOF。IO loop 捕获后发现 `closed=true`，正常退出而不是重连。

最后可以在退出清理路径执行：

```text
ioThread.join(shutdownJoinMs)
```

join 是“等待另一个线程结束”。它只能在游戏退出/清理路径有界使用，不能出现在 PLAYING tick。

### 2.5 阶段闸门

- [ ] P2-S11：只有 IO thread 调用 transport read/write/connect。
- [ ] P2-S12：断线后按 backoff 重连，成功后进入新 epoch。
- [ ] P2-S14：close 能解除阻塞 read，并在上限内结束。
- [ ] 读写两侧都能持续前进，不会因永久阻塞 read 而饿死 outbound。
- [ ] 超限检查发生在完整大字符串分配之前。
- [ ] 所有 2.4 Session core 测试仍使用 fake transport 并保持绿色。

---

## 2.6 实现 Python deterministic fake Agent

> **一句话目标**：实现一个简单、确定、可故意制造故障的 Python server，用它证明 Java ↔ Python 基础设施成立；本阶段不调用任何模型。

### 2.6.1 为什么需要 fake Agent

如果直接接真实 LLM，测试失败可能来自：

- prompt 不稳定
- API/网络不可用
- 模型响应慢
- 输出格式变化
- Phase 2 桥接代码本身有 bug

这些原因混在一起很难排查。fake Agent 把“推理”简化成几条固定规则，让同一 observation 永远产生同一 intent。这样先证明运输管道和 Java 控制链正确，Phase 3 再替换真正 Agent。

**fake 不等于随便写**：它虽然简单，仍要模拟真实 runtime 的进程边界、并发连接、消息格式、取消和故障。

建议文件：

```text
bridge/
  protocol.py     Python 侧 codec 和 DTO/校验
  fake_agent.py   确定性决策和故障行为
  run.py          TCP server CLI 入口
  smoke_test.py   最小协议冒烟测试
```

### 2.6.2 run.py：启动一个多连接 TCP server

使用 Python 标准库 `socketserver.ThreadingTCPServer`：

```text
主 server 监听 127.0.0.1:9876
  ├─ guard-a 连接 → handler thread A → FakeAgent A
  └─ guard-b 连接 → handler thread B → FakeAgent B
```

每条 Java AgentSession 连接到同一个端口，但 server 为每条连接创建独立 handler 和 FakeAgent 实例。

**为什么不能全局只创建一个 FakeAgent**：全局实例会共享当前 decision、cancel 状态或日志上下文，测试可能出现 guard-a 的消息影响 guard-b，违反每 Enemy 独立 Agent。

CLI：

```powershell
python bridge/run.py `
    --host 127.0.0.1 `
    --port 9876 `
    --mode normal
```

生产 `Main` 只负责连接配置地址，不自动启动 Python。Integration harness 可以通过 `ProcessBuilder` 启动它，这是测试基础设施，不是生产生命周期。

### 2.6.3 protocol.py：Python 也要遵守同一 schema

Java 和 Python 不共享源代码，因此两边必须分别实现 `phase2.session.v1`，再用 round-trip/integration test 对齐。

Python 标准库有 `json`，不需要手写 parser，但默认行为并不够严格：

- `json.loads()` 默认接受重复 key，保留最后一个值。
- 默认可能接受 `NaN/Infinity`。
- 不会自动限制 schema 未知字段。
- 不会自动限制协议要求的 16 层嵌套。

至少增加：

```python
json.loads(
    text,
    object_pairs_hook=reject_duplicate_keys,
    parse_constant=reject_non_finite
)
```

并显式校验：

- 顶层必须是 object。
- Envelope 必填字段存在且类型正确。
- message type 合法。
- data 只包含该消息允许的字段。
- 嵌套不超过 16。
- UTF-8 frame 不超过配置大小。

编码使用：

```python
json.dumps(
    envelope,
    ensure_ascii=False,
    allow_nan=False
) + "\n"
```

`ensure_ascii=False` 保留正常 UTF-8；`allow_nan=False` 防止生成非标准 JSON 数值。

### 2.6.4 fake_agent.py：只根据当前 observation 决策

固定逻辑：

```text
可见 PLAYER 且曼哈顿距离为 1 → ATTACK
否则存在可见 PLAYER             → CHASE
否则                             → PATROL
```

**为什么用曼哈顿距离 1 判断相邻**：

```text
abs(player.x - self.x) + abs(player.y - self.y) == 1
```

这表示上下左右四邻格，与当前移动/攻击规则一致；不要误用“x/y 差都小于等于 1”，否则对角线也会被当成相邻。

PATROL 目标只能来自 `visibleTiles` 中 `walkable=true` 的格子。为了确定性：

1. 排除自身位置。
2. 按 `(x, y)` 排序。
3. 取第一个满足规则的格子。
4. 没有候选时返回安全的原地/WAIT 等约定结果。

不要使用随机数、`set` 的迭代顺序或 Python 对象 hash。

fake Agent 不做：

- 不读取 Java world。
- 不保存长期记忆。
- 不调用 LLM/API。
- 不基于 feedback 实现持续重规划。
- 不产生白名单外 skill。

它需要合法解析和记录 `action_feedback/world_event`，证明 Java 的后半段消息也能送到 Python。真正利用 feedback 的循环留给 Phase 4。

### 2.6.5 五种故障模式分别在测什么

| 模式 | fake runtime 行为 | Java 必须证明 |
|------|-------------------|---------------|
| `normal` | 立即返回合法 intent | 正常闭环和身份关联 |
| `delay` | 延迟约 2 秒再返回 | soft timeout 后 tick/旧计划继续；迟到但仍合法时可采纳 |
| `malformed` | 返回非法 JSON/frame | codec 拒绝，Enemy 状态不被污染 |
| `disconnect` | 收到请求后关闭连接 | 本地接管、generation 失效和重连 |
| `no-read` | 接受连接后不再读取客户端字节 | Socket/queue 反压不能阻塞游戏线程 |

**no-read 的关键**：

如果 server 仍然调用 `recv()`，只是“不解析”，操作系统接收缓冲仍在被消费，Java 写端可能一直不会阻塞。正确模式是接受连接后完全停止读取，让 OS buffer 最终填满。

**delay 模式与 deterministic unit test 的区别**：

- Python integration 的 delay 模式可以真实等待约 2 秒，因为它要验证真实进程和 Socket。
- Java 状态机单元测试仍必须使用 fake clock，不能靠 sleep。

### 2.6.6 smoke test：先测最短闭环

smoke test（冒烟测试）只回答“最基本功能能否跑通”，不覆盖所有边界：

```text
启动 Python normal server
Java 建立连接
Java 发送一条 observation
Python 返回 submit_intent
Java 收到并校验关键 identity
关闭双方
```

它失败时，先检查：

1. server 是否已监听正确 host/port。
2. 每条 NDJSON 是否以 `\n` 结尾。
3. Java/Python 字段名和版本是否一致。
4. UTF-8 和 frame size 是否一致。
5. 进程是否在失败后被可靠关闭。

### 2.6 阶段闸门

- [ ] Python protocol 单元测试通过。
- [ ] smoke test 完成 observation → submit_intent。
- [ ] normal 决策对相同 observation 完全确定。
- [ ] 两条并发连接不会串 agentId、decisionId 或内部状态。
- [ ] 五种模式均能独立启动和退出。
- [ ] runtime 可由测试 harness 启动，但生产 `Main` 不自动启动它。

---

## 2.7 连接 Enemy、Game 和 AgentSession

> **一句话目标**：把前面分别完成的 Game loop、快脑、Session 和 Python runtime 接起来，形成 `observation → intent → lease → action → feedback` 的完整闭环。

2.1～2.6 很像分别造好了发动机、方向盘、轮胎和刹车。2.7 才是第一次把它们装进同一辆车。这里最容易犯的错误不是“某个类写错了”，而是**把正确的方法放在错误的时机调用**。

### 2.7.1 先明确谁负责创建和关闭 Session

`AgentSession` 是一个有线程、队列和连接状态的运行时对象。它不只是普通数据，因此必须有明确的生命周期所有者。

Phase 2 的约定是：

- `Game` 负责游戏、楼层和全部 Enemy 的整体生命周期。
- 每个 `Enemy` 持有且只持有自己的 `AgentSession`。
- Session 不在构造器之外偷偷全局共享。

按事件实现下面的生命周期：

| 事件 | 必须做什么 | 为什么 |
|------|------------|--------|
| 新游戏 | 生成新 `runId`；`bridge=true` 时为每个 Enemy 建立独立 Session | 新的一局不能接受上一局迟到的消息 |
| 读档 | 建立新 `runId` 和新 Session；从本地 observation 冷启动 | Socket、旧请求和 deadline 不是存档内容 |
| 进入新楼层 | 先 supersede/close 旧楼 Session，再为新楼 Enemy 建立 Session | 旧楼坐标和新楼坐标没有可交换的语义 |
| Enemy 死亡 | 在本 tick 的 cleanup 阶段关闭它的 Session | 防止死去 Enemy 的线程重连或迟到回调 |
| 游戏退出 | 遍历并关闭全部 Session，执行有界清理 | JVM 不应被残留 IO thread 挂住 |

**什么叫冷启动**：只恢复游戏本身允许持久化的数据，然后根据当前世界重新生成 observation 和计划；不恢复运行中的网络会话。

存档策略：

```text
可以沿用：Enemy.agentId（前提是旧存档本来就能读取它）
不保存：runId、sessionEpoch、requestGeneration、request、
        socket、queue、lease、pendingEvents、ActionQueue
```

这里很容易误以为“保存更多状态就恢复得更完整”。实际上，旧 Session 的另一端可能早已不存在，旧 intent 也可能基于另一楼层的世界。恢复它们会制造无法验证的半旧半新状态。

### 2.7.2 用 attach/detach 把可选运行时接到 Enemy

`Enemy` 的生产 API 保持清晰：

```text
attachAgentSession(session)
detachAgentSession()
pollAgentMessages(context)
executeOneAction(context)
collectAgentUpdates(context)
closeAgentRuntime()
```

建议的职责：

- `attachAgentSession`：只接受属于当前 Enemy 的新 Session，建立 handler。
- `detachAgentSession`：解除引用，不再让 Enemy 使用它。
- `closeAgentRuntime`：幂等关闭；死亡、离层、退出都可以安全调用。
- bridge 关闭或 Session 不可用时，上述 tick 方法仍走本地路径，不到处写 `null` 特判。

**为什么要 attach，而不是在 Enemy 构造器里直接 new Session**：

1. bridge 默认关闭时根本不应创建网络线程。
2. 单元测试要注入 fake Session/transport。
3. 读档和换层时需要替换运行时。
4. Entity 的游戏数据不应绑定具体 host、port 和 Socket。

### 2.7.3 AgentHandler 是什么，为什么它必须“薄”

`AgentHandler` 是 Session 收到消息后调用的回调接口。**回调（callback）** 就是“现在先把一个方法交给别人，等事件发生时由对方再调用它”。

但要特别注意：Phase 2 不是让 IO thread 直接回调 Enemy。正确路径是：

```text
IO thread:
    bytes → decode → inbound queue

下一个游戏 tick 的 poll 阶段:
    inbound queue → AgentSession.pollInbound(handler, logicalTick)
                  → handler 把候选 intent 转交 Enemy
```

因此，handler 的方法只会在游戏线程调用 `pollInbound` 时执行。它可以做：

- 把已通过 Session 身份初筛的 `submit_intent` 交给 `DecisionValidator`。
- 把合法 proposal 交给 `IntentArbiter`。
- 记录拒绝、过时或采纳的 trace。

接口形状以 Spec §8.3 为准：

```java
public interface AgentHandler {
    void onIntentSubmitted(SubmitIntentData data,
                           Envelope envelope,
                           AgentSession.RequestContext requestContext);

    void onCancelAcknowledged(CancelAckData data,
                              Envelope envelope,
                              AgentSession.RequestContext cancelledRequest);

    void onProtocolRejected(ProtocolFailure failure);
}
```

`requestContext` 由 Session 提供，表示 Java 当初发出的那次请求；不要根据当前可变字段临时拼一个 context，否则迟到响应可能和错误的 observation 配对。

它不应该：

- 读写 Socket。
- 自己移动 Enemy。
- 绕过 Validator 直接生成 Action。
- 复制一整套 Session 状态机。

“薄”表示它只做对象之间的适配和转发。网络状态属于 Session，游戏行为属于 Enemy/Arbiter；handler 不应变成第三个全能控制器。

### 2.7.4 按阶段完成一次完整往返

下面用一组具体 ID 串起正常流程。假设：

```text
runId             = run-7
floorId           = floor-2
agentId           = guard-a
sessionEpoch      = 3
requestGeneration = 5
observationSeq    = 41
decisionId        = decision-9
```

#### A. collect：Java 发送 observation

本 tick 的 Action 已执行，`flushPendingChanges()` 也已完成。`collectAgentUpdates()`：

1. 从提交后的权威世界生成 `observationSeq=41`。
2. 把它写入 Session 的 `latestObservation`。
3. 如果符合请求条件，调用 `requestIntent(...)`。
4. Session 生成 `decisionId=decision-9`，建立 `RequestContext`。
5. Session 把携带完整请求身份的 observation envelope 放入 outbound queue。
6. 方法立即返回；游戏线程不等 Python。

#### B. IO loop：真正写到 Python

Session 的 IO thread 从 outbound queue 取出 envelope：

```text
Java object → codec → UTF-8 NDJSON → Socket
```

Python 解码后，只根据 observation 中的私有知识得出：

```text
skill = CHASE
```

它不会生成新的请求 ID，而是把 Java 请求中的 `decisionId=decision-9` 和其他完整身份字段原样放回 `submit_intent`。这样 Java 才能判断回答对应哪一次请求。

#### C. poll：Java 收到并采纳 intent

IO thread 只完成：

```text
Socket → frame → decode → inbound queue
```

到下一次 `pollAgentMessages()`，游戏线程才：

1. 检查 `runId/floorId/agentId/sessionEpoch`。
2. 检查 `observationSeq/requestGeneration` 是否仍对应当前请求。
3. 检查 `decisionId` 是否合法且未重复。
4. 用 `DecisionValidator` 检查 `CHASE` 及参数。
5. 用 `IntentArbiter` 检查当前 P0～P4 优先级。
6. 在安全边界建立新的 REMOTE `IntentLease`。
7. 由 Java `ClassicalPlanner` 把 lease 转成 ActionQueue。

如果任何一步失败，当前 lease、queue 和 cooldown 都保持不变。这就是“拒绝无副作用”。

#### D. execute：每个 action tick 只执行一个动作

`executeOneAction()` 取出至多一个 Action，并暂存：

```text
beforePosition
decisionId
actionIndex
decisionSource
overrideReason
rawActionResult
```

暂存而不立即构造最终 feedback，是因为 EntityManager 的增删移动可能还没 commit。

#### E. commit：世界统一提交变化

`Game` 调用：

```text
entityManager.flushPendingChanges()
```

到这里，移动、死亡和其他 pending change 才成为本 tick 的权威结果。

#### F. collect：构造 ActionOutcome 并反馈

`collectAgentUpdates()` 再读取：

- `afterPosition`
- 当前 self HP
- 动作是否 blocked/failed/succeeded

然后构造 `ActionOutcome`，其中继续携带：

```text
decision-9 → actionIndex → raw result → before/after position
```

再调用 `sendActionFeedback(...)` 入队。Python 即使暂时不使用 feedback，也必须能接收和记录它，因为这条关联链是后续阶段做持续计划的基础。

完整顺序可以压缩为：

```text
collect 产生 observation
  → IO 发给 Python
  → Python 返回 submit_intent
  → poll 校验并采纳 lease
  → execute 执行一个 Action
  → world commit
  → collect 产生 ActionOutcome/feedback
```

### 2.7.5 collect 阶段何时应该请求新 intent

不能每 tick 都发请求。否则 Python 还没回答第 41 号 observation，第 42、43、44 号已经堆满队列，而且大多数内容没有决策价值。

Phase 2 允许在以下情况请求或更新 `latestObservation`：

| 触发条件 | 含义 | 为什么需要新计划 |
|----------|------|------------------|
| first observation | 当前 Session 从未请求过 | 必须先获得第一份远程 intent |
| low-water | ActionQueue 降到低水位 | 趁旧动作尚未耗尽提前补计划 |
| exhausted | lease 或 ActionQueue 已耗尽 | 否则下一 action tick 只能 fallback |
| blocked | 当前动作被墙、实体或规则阻挡 | 原路线的前提可能不成立 |
| critical reflex | 开始/结束 P0/P1/P2 覆盖 | 远程计划需要知道重要局势变化 |
| heartbeat | 长时间没有其他消息 | 证明连接仍活跃并同步最新状态 |

**低水位（low-water）为什么设为 2**：还剩少量动作时就开始请求，给异步 Agent 留出思考时间；**高水位（high-water）为什么设为 5**：新计划一次只填有限动作，避免很久以后还在执行陈旧路线。

如果当前已经有 in-flight：

```text
不发送第二个请求
只把 latestObservation 替换为更新的一份
```

等旧请求完成、被取消或 Session 重建后，再用这份 latest observation 发下一请求。这就是 2.4 的 coalescing 在生产 loop 中的落点。

### 2.7.6 runtime 不可达时怎样继续玩

异步 Agent 是增强能力，不是维持 Enemy 存活的必要条件。

Session 为 `DISCONNECTED`、`SOFT_TIMED_OUT` 或正在 reconnect 时：

1. 当前私有感知若触发 P0/P1/P2，快脑立即接管。
2. 若仍有安全且有效的旧 lease/queue，按原 cadence 继续。
3. 若没有计划，`RuleBasedBrain` 生成 LOCAL_FALLBACK lease。
4. IO thread 在后台按 backoff 重连，游戏线程继续推进 logical tick。

恢复时也不能突然在动作执行一半替换计划：

```text
连接成功
  → sessionEpoch++，发送基于 latest observation 的新请求
  → 收到新 intent
  → 下一次 poll 安全边界校验并采纳
  → execute 才可能使用新 queue
```

这就是 **safe resume（安全恢复）**。恢复成功并不等于 Socket 一连上就马上改 Enemy；必须先有属于新 epoch/request 的合法 intent。

### 2.7.7 把集成失败定位到具体一段

完整闭环失败时，不要只看“Enemy 没动”。按链路逐段检查：

```text
是否生成 observation？
  ↓
是否成功 enqueue outbound？
  ↓
Python 是否收到同一个 observationSeq？
  ↓
submit_intent 是否回到 inbound？
  ↓
identity/semantic validation 是否通过？
  ↓
lease 是否在 poll 阶段采纳？
  ↓
Planner 是否生成 ActionQueue？
  ↓
execute 是否到了 action tick？
  ↓
commit 后是否生成 feedback？
```

每一段都应有 trace 或测试断言。这样集成问题会变成一个具体边界的问题，而不是在 Game、Enemy、Python 三边来回猜。

### 2.7 阶段闸门

通过 P2-I01～P2-I05，并同时复核 P2-A03、P2-A10～P2-A12：

- [ ] 单 Enemy 完成 observation → intent → action → feedback，所有关联 ID 一致。
- [ ] 双 Enemy 使用两条持久连接，identity、queue、request 和决策不串线。
- [ ] delay 时 logical tick 和旧计划 cadence 继续推进。
- [ ] no-read 导致 outbound 饱和时，游戏线程仍能持续 tick。
- [ ] disconnect 后由本地 Brain 接管。
- [ ] Python restart 后，新 intent 只在 poll 安全边界恢复远程控制。
- [ ] feedback 使用 commit 后位置，并保留 decisionId/actionIndex/source/overrideReason。
- [ ] Enemy 死亡、换层和退出后，旧 Session 不会重连或回调。

---

## 2.8 建立 trace、harness 和测试总入口

> **一句话目标**：让 Phase 2 不只“看起来能跑”，还可以解释每一次决定、稳定复现每一个边界，并证明旧功能没有被破坏。

### 2.8.1 observability 和 trace 分别是什么

**Observability（可观测性）** 是指：只看系统输出的证据，就能推断内部发生了什么。

普通日志可能只有：

```text
agent connected
enemy moved
```

这不足以回答：

- 是哪个 Enemy 连上了？
- 它的移动来自 REMOTE、LOCAL_FALLBACK 还是 REFLEX？
- intent 基于哪份 observation？
- 动作被覆盖时，原 lease 是暂停还是丢失？
- 一条迟到响应为什么被拒绝？

`AgentTrace` 是结构化事件记录。结构化表示每条记录有稳定事件类型和字段，可以被测试比较、过滤和关联，而不是只给人看一句自由文本。

### 2.8.2 canonical trace 与 diagnostic log 不要混在一起

Phase 2 新增：

```text
AgentTrace.PHASE2_SCHEMA_VERSION = "phase2.trace.v1"
```

至少覆盖：

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

Phase 2 把记录分成两类：

| 类型 | 作用 | 能否进入 deterministic evidence |
|------|------|---------------------------------|
| canonical trace | 描述游戏语义和状态转换 | 可以，必须可重复；优先用类型化不变量断言 |
| diagnostic log | 帮助分析真实运行性能和线程 | 不可以，通常每次都不同 |

canonical 字段包括：

```text
runId, floorId, agentId, logicalTick,
sessionEpoch, observationSeq, decisionId, requestGeneration,
messageType, connectionState, requestState, executionState,
decisionSource, validationResult, overrideReason,
actionIndex, actionType, rawActionResult,
beforePosition, afterPosition
```

diagnostic 字段可以包括：

```text
wallClockTimestamp, queueDelayMs, connectMs, transportMs,
fakeInferenceMs, totalLatencyMs, threadName, socketAddress
```

**为什么 wall-clock 不能进入 canonical evidence**：同一个测试今天和明天运行，真实日期必然不同；这会让行为完全相同的两次运行产生不同结果。

### 2.8.3 用关联键重建一次决定的“履历”

假设你看到 Enemy 在 tick 120 向左移动。理想 trace 应让你沿 ID 追溯：

```text
observationSeq=41
  ↓ AGENT_REQUEST_SENT
requestGeneration=5
  ↓ INTENT_ADOPTED
decisionId=decision-9, source=REMOTE
  ↓ ACTION_EXECUTED
actionIndex=2, actionType=MOVE_LEFT
  ↓ ACTION_FEEDBACK_ENQUEUED
before=(8,4), after=(7,4), rawResult=SUCCESS
```

如果中间发生 reflex：

```text
decision-9 lease
  ↓ REFLEX_OVERRIDE_STARTED
source=REFLEX_ENGAGE, reason=VISIBLE_PLAYER
  ↓ REFLEX_OVERRIDE_ENDED
decision-9 仍满足 TTL/前提 → 恢复
```

因此 trace 不是“多打印几行”。它是跨 observation、Session、仲裁、动作和反馈的因果链。

### 2.8.4 保留 Phase 0/1 语义契约，不锁死完整轨迹

Phase 0/1 已经有各自 schema 和历史 baseline。Phase 2 增加字段时，旧 schema 仍必须保持字段隔离；但寻路、排队和调度行为不再要求完整轨迹逐字节不变。完整 gameplay golden 会把合理重构误判为回归，也容易迫使生产代码保留测试专用后门。

正确做法：

```text
if schema == phase0:
    只序列化 Phase 0 字段
else if schema == phase1:
    只序列化 Phase 1 字段
else if schema == phase2:
    序列化 Phase 2 字段
```

P2-R01 直接断言稳定契约：碰撞规则、私有感知边界、相同输入的确定性，以及旧 schema 不泄漏新字段。`documents/baselines/` 中的 Phase 0/1 JSON 只作为历史审计证据，不参与普通 gate，也不提供普通测试中的自动生成或覆盖入口。

### 2.8.5 Harness 是“可编程的小型游戏现场”

**Test harness（测试支架）** 是为测试搭建的受控运行环境。它不是另一个 Game，也不是 mock 掉所有逻辑；它用真实生产逻辑，只替换外部不确定因素。

共享 `EncounterHarness` 应固定或注入：

- 固定 `runId/floorId/agentId`。
- deterministic decision/message ID generator。
- fake monotonic clock。
- fake transport。
- 固定地图、实体和私有 observation 输入。
- 与正式 Game 相同的 tick 阶段顺序，并直接调用生产 `AiTickLoop`，不得复制 scheduler 或反射 private Game 方法。

提供最少而明确的测试操作：

```text
step()                  // 推进一个完整 logical tick
advanceClockMs(1500)    // 只推进 fake clock，不等待现实时间
injectInbound(message)  // 模拟 IO thread 已收到消息
drainOutbound()         // 观察 Java 想发出的消息
canonicalTrace()        // 取得可比较证据
```

**为什么 harness 必须复用正式 tick 顺序**：如果测试先 collect 再 execute，而正式 Game 先 execute 再 commit，测试通过也不能证明生产代码正确。Harness 应控制输入，不应重写被测规则。

### 2.8.6 fake clock 为什么比 Thread.sleep 更可靠

测试 soft timeout 时，下面的方式很差：

```java
Thread.sleep(1500);
```

它有三个问题：

1. 测试真的要等 1.5 秒，Suite 会越来越慢。
2. CI 或电脑繁忙时调度延迟不稳定。
3. 它只能证明“现实中等了一会儿”，不能精确断言 deadline 前后一个毫秒的状态。

fake clock 的测试可以写成概念步骤：

```text
request at 1000ms
advance to 2499ms → 仍是 AWAITING_INTENT
advance to 2500ms → 进入 SOFT_TIMED_OUT
```

这叫 **deterministic test（确定性测试）**：相同输入和相同虚拟时间，总得到相同输出。

真实 Java ↔ Python TCP integration 可以有短暂且**有上限**的等待，因为它要验证真实进程和 Socket；但状态机单元测试仍不能靠 sleep 推进。

### 2.8.7 按层次组织测试，而不是所有问题都走真网络

| 测试类 | 使用真实 Socket/Python | 负责证明什么 |
|--------|-----------------------|--------------|
| `Phase2ProtocolTest` | 否 | codec、schema、坏输入、知识边界 |
| `Phase2SessionTest` | 否，使用 fake clock/transport | 状态机、单 in-flight、deadline、背压、close |
| `Phase2AiTickTest` | 否 | P0～P4、单动作、safe boundary、commit/feedback 时序 |
| `Phase2IntegrationTest` | 是 | 真实 Java ↔ Python TCP 和五种 runtime 行为 |
| `Phase2TestSuite` | 组合入口 | Phase 0/1 回归与全部 Phase 2 gate |

这叫 **test pyramid（测试金字塔）**：

```text
少量真实进程/Socket 集成测试
        ↑
较多组件测试
        ↑
大量快速、确定的单元测试
```

真实网络测试更接近运行环境，但失败原因更多、执行更慢；fake 测试更适合穷举边界。两者是互补关系。

### 2.8.8 Integration test 怎样管理 Python 子进程

`Phase2IntegrationTest` 可以用 Java `ProcessBuilder` 启动：

```powershell
python bridge/run.py --host 127.0.0.1 --port <test-port> --mode normal
```

测试需要：

1. 启动后等待明确的 ready 信号，不能猜“sleep 500ms 应该够了”。
2. 每一步设置有界超时。
3. 失败时输出当前 connection/request/execution state 和子进程日志。
4. 在 `finally` 或 `@AfterClass` 中关闭 Session 和子进程。
5. 端口冲突时报告所用端口，不无限换端口重试。

建议的清理顺序：

```text
关闭 Java Session
  → 请求 Python 正常退出或关闭进程
  → 有界等待
  → 仍未退出时再强制结束测试子进程
```

测试进程的清理可以比生产 Game 更主动，但不能影响用户手动启动的 Python runtime。只清理由当前测试自己创建并持有句柄的进程。

### 2.8.9 Phase2TestSuite 应当包含什么

确定性统一入口至少覆盖：

```text
EnemyCollisionTest
Phase0EncounterTest
PerceptionSystemTest
Phase1EncounterTest
Phase2ProtocolTest
Phase2AiTickTest
P2-P01 ～ P2-P06
P2-S01 ～ P2-S15
P2-A01 ～ P2-A12
P2-T01、P2-T02
P2-R01、P2-R02
```

`Phase2TestSuite` 只直接列 leaf test class，每个 class 恰好一次，不嵌套 `Phase0TestSuite` 或 `Phase1TestSuite`。`PerceptionSystemTest` 是已审计的 deterministic leaf，应纳入统一入口。

P2-I01 ～ P2-I05 涉及 Python 子进程、端口和有界等待，保留独立 integration 命令；不要混入快速确定性 Suite。这样 agent 可以先用一个短 gate 定位 Java 契约问题，再按需运行跨进程验证。

### 2.8 阶段闸门

通过 P2-T01/P2-T02 和 P2-R01/P2-R02，并检查：

- [ ] 相同 harness 场景运行两次，canonical trace 字节一致。
- [ ] trace 能从 observation 关联到 request、intent、override、action 和 feedback。
- [ ] wall-clock、thread name、socket address 等不稳定字段不进入 canonical evidence。
- [ ] Phase 0/1 schema 语义隔离，碰撞、私有感知和确定性契约仍通过。
- [ ] 历史 gameplay baseline 不参与 gate，也没有普通测试覆盖入口。
- [ ] deadline 单元测试使用 fake clock，不使用 `Thread.sleep()`。
- [ ] integration 每一步都有有界等待，失败信息能指出卡在哪个状态。
- [ ] 测试结束后没有 Python 子进程、Session thread 或端口残留。
- [ ] `Phase2TestSuite` 可用一条命令 headless 运行，且没有 suite 嵌套或重复计数。

---

## 2.9 配置、故障演练和交接

> **一句话目标**：把散落的“魔法数字”变成可校验配置，用真实故障证明降级路径，最后留下别人可以复查的阶段证据。

### 2.9.1 配置是什么，为什么不要把数字写死在代码里

像 1500ms、32 条消息、9876 端口这样的值会因环境或测试目标变化。把它们散落写在多个类中，修改时很容易只改一半。

Phase 2 用：

- `config/game.properties`：给人编辑的文本配置。
- `GameConfig`：读取、解析、校验并向 Java 代码提供有类型的值。
- `AgentSessionConfig`：把 Session 真正需要的子集组织成不可变配置。

默认值：

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

### 2.9.2 bridge.enabled 是 feature flag

**Feature flag（功能开关）** 让新能力可以在不删除代码的情况下启用或关闭。

默认：

```properties
agent.bridge.enabled=false
```

此时必须满足：

- 不创建 Socket。
- 不启动 AgentSession IO thread。
- 正式 Game 仍使用新的三阶段 loop。
- Enemy 使用私有感知和本地 RuleBasedBrain。
- 没启动 Python，游戏也能正常进入和退出。

把默认值设为 false 的原因不是“Phase 2 不重要”，而是网络 runtime 是可选外部进程。默认启动路径不应依赖用户先开另一个终端。

### 2.9.3 在启动边界一次性校验配置

配置错误应在初始化时被发现和报告，而不是到了某个 PLAYING tick 才突然抛异常。

至少检查：

```text
softDeadlineMs > 0
hardDeadlineMs > softDeadlineMs
cancelGraceMs > 0
outbound/inbound/pendingEvent capacity >= 4
highWater > lowWater >= 0
1024 <= maxFrameBytes <= 1,048,576
reconnectMaxMs >= reconnectInitialMs > 0
shutdownJoinMs > 0
heartbeatTicks > 0
port 在 1～65535
host 非空
```

处理策略：

1. 记录具体 key、错误值和采用的默认值。
2. 回退到安全默认值。
3. 保证 PLAYING tick 不再重复解析 properties。

不要悄悄把 `hardDeadlineMs=100` 和 `softDeadlineMs=1500` 都接受下来；这会让 hard timeout 比 soft timeout 先发生，状态机语义自相矛盾。

### 2.9.4 手工故障演练不是“再玩一遍”

自动测试证明精确断言，手工演练用于同时观察真实 Game、真实 Java thread、Socket 和 Python log。每种模式都要提前写出期望，再运行，再比较证据。

#### normal：验证完整主路径

```text
启动 normal runtime
  → bridge=true 启动游戏
  → 让一个 Enemy 产生 observation
  → 观察 intent 被采纳、Action 被执行、feedback 被发送
```

必须保存：

- `AGENT_REQUEST_SENT → INTENT_ADOPTED → ACTION_FEEDBACK_ENQUEUED`
- 相同的 `agentId/observationSeq/decisionId`
- Python 收到 feedback 的日志

#### delay：验证“慢不等于停”

```text
启动 delay runtime
  → 记录请求前 logicalTick
  → 等待超过 soft deadline
  → 确认 logicalTick 持续增长
  → 确认旧计划或 LOCAL_FALLBACK 继续
  → 迟到 intent 仍满足完整身份/语义时才允许采纳
```

关键证据是 `AGENT_SLOW` 和连续 tick，不是肉眼觉得“画面似乎还在动”。

#### malformed：验证坏输入无副作用

```text
启动 malformed runtime
  → 收到坏 JSON/schema
  → 记录 PROTOCOL_ERROR
  → 当前 lease/queue/cooldown 不变
  → 按 fatal 分类忽略或重建 Session
```

#### disconnect/restart：验证本地接管与安全恢复

```text
运行中断开 Python
  → Session DISCONNECTED
  → requestGeneration 失效
  → Enemy 本地继续
  → IO thread 按 backoff 重连
重新启动 Python
  → 新 sessionEpoch/request
  → 新 intent 在 poll 安全边界接管
```

要特别检查旧连接的迟到响应没有在恢复后生效。

#### no-read：验证背压不会冻住游戏

```text
Python 接受连接但停止 recv
  → Java outbound/OS buffer 逐渐饱和
  → observation/heartbeat 按规则合并或丢弃
  → 关键消息失败时进入 trace + degraded/rebuild
  → logicalTick 继续推进，游戏线程不阻塞
```

这一模式通常需要产生足够多消息才能让 buffer 真正饱和。不要只运行几秒就断言背压已经被验证。

汇总表：

| 模式 | 期望行为 | 最关键证据 |
|------|----------|------------|
| normal | 采纳 intent 并产生 feedback | 完整关联链 |
| delay | soft timeout，旧计划/本地控制继续 | `AGENT_SLOW` + tick 连续 |
| malformed | 拒绝坏帧，不修改 Enemy | `PROTOCOL_ERROR` + 状态不变 |
| disconnect/restart | 本地接管，按 backoff 重连并安全恢复 | 新 epoch + 旧响应失效 |
| no-read | outbound 饱和但 tick 继续 | coalesced/dropped + tick 证据 |

### 2.9.5 写 PHASE_2_COMPLETION.md：记录证据，不写完成感想

Completion 文档回答的是：“别人如何复查 Phase 2 确实达到 Spec”，而不是“我觉得做完了”。

至少记录：

1. 日期、基线 commit、最终 commit、工作分支。
2. Java/Python 版本和运行环境。
3. 完整编译/测试命令、测试总数、通过数、失败数和耗时。
4. normal/delay/malformed/disconnect/no-read 的命令、结果与证据路径。
5. 一条完整 observation → intent → action → feedback canonical trace。
6. 一条 stale response 被拒绝且当前行为不变的 trace。
7. 一条 reflex override 开始和结束的 trace。
8. queue 饱和时 logical tick 继续的证据。
9. 与 Spec 的所有偏差及原因；没有偏差也明确写“无”。
10. 已知限制、未解决风险和 Phase 3 交接内容。

**基线 commit** 是开始 Phase 2 时作为比较起点的 Git 提交；**最终 commit** 是验收所对应的实现提交。这样测试结果和代码版本能一一对应。

### 2.9.6 Phase 3 可以依赖什么

交接中明确列出已经稳定的基础：

- `phase2.session.v1` envelope 和严格 NDJSON codec。
- 每 Enemy 独立持久 Session、身份、队列、deadline、重连和 close。
- 单 in-flight、latest observation 合并和 cancel/rebuild。
- `IntentLease`、Validator、Arbiter、ReflexController。
- Game 分阶段 tick、单 Action cadence 和 commit barrier。
- `ActionOutcome`/feedback 的稳定关联字段。
- deterministic fake runtime、harness、clock、transport 和 trace。

也明确列出 Phase 3 **不得假设**：

- Python 已经有 LLM、LangGraph、Tool Calling、记忆或 checkpoint。
- Phase 2 的四个 skill 是最终战术能力。
- feedback 已经驱动多轮持续规划。
- 多个 Enemy 共享 context、预算或传输。
- Session 状态可以从存档恢复。

### 2.9 阶段闸门

- [ ] 所有配置有安全默认值和范围校验。
- [ ] `bridge=false` 时不创建网络线程，Game 仍使用新 loop 并正常运行。
- [ ] 五种 fake runtime 模式都有命令、日志、trace 和观察结论。
- [ ] 故障演练中没有用无限等待或强行阻塞游戏线程来“保证顺序”。
- [ ] 单一 `Phase2TestSuite` 中的 Phase 0/1/2 deterministic contracts 全部且仅执行一次。
- [ ] `PHASE_2_COMPLETION.md` 中的证据能对应到明确 commit。
- [ ] Spec 偏差、已知风险和 Phase 3 可依赖/不得假设项已写清。

---

## 文件导航

下面是按责任划分的施工地图，不是要求一次性创建的任务清单。

| 区域 | 主要文件 |
|------|----------|
| 协议与 Session | `byog/Bridge/AgentProtocol.java`、`AgentProtocolCodec.java`、`AgentSessionConfig.java`、`MonotonicClock.java`、`AgentHandler.java`、`AgentSession.java` |
| 私有感知 | `byog/Perception/VisibleTile.java`、`ObservationEnvelope.java`、`PerceptionSystem.java` |
| 双速控制 | `byog/AI/AiTickContext.java`、`ReflexObservation.java`、`InterruptPolicy.java`、`IntentLease.java`、`DecisionValidator.java`、`IntentArbiter.java`、`ReflexController.java` |
| 动作执行 | `byog/Action/ActionOutcome.java`、`ActionQueue.java`、`byog/AI/ClassicalPlanner.java` |
| 生产集成 | `byog/Entity/Enemy.java`、`byog/Core/Game.java` |
| 可观测性与配置 | `byog/Trace/AgentTrace.java`、`byog/IO/GameConfig.java`、`config/game.properties` |
| Python fake runtime | `bridge/protocol.py`、`fake_agent.py`、`run.py`、`smoke_test.py` |
| 测试 | `byog/Test/EncounterHarness.java`、`byog/AI/AiTickLoop.java`、`Phase2ProtocolTest.java`、`Phase2SessionTest.java`、`Phase2AiTickTest.java`、`Phase2IntegrationTest.java`、`Phase2TestSuite.java` |

明确不要创建旧方案中的：

```text
byog/AI/PythonBridgeBrain.java
byog/Core/AgentMailbox.java
byog/Core/AgentRequest.java
byog/Core/AgentDecision.java
```

---

## 验证命令

在项目根目录使用 PowerShell：

```powershell
$javaSources = Get-ChildItem byog -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }

javac -encoding UTF-8 `
    -cp "..\library-sp18\javalib\*" `
    -d out `
    $javaSources
```

全部 Phase 2 与回归：

```powershell
java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.Phase2TestSuite
```

分层定位：

```powershell
java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.Phase2ProtocolTest

java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.Phase2SessionTest

java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.Phase2AiTickTest

java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.Phase2IntegrationTest
```

实现过程中优先跑最小相关测试，再跑聚合 Suite。网络集成测试可以有有界等待；deterministic tests 不得用 sleep 推进 deadline。

---

## 出问题时先看这里

| 症状 | 最可能原因 | 第一检查点 |
|------|------------|------------|
| 玩家输入后卡顿 | 游戏线程碰了 connect/read/write/join | P2-S11；搜索所有 Socket 调用线程 |
| Python 一慢 Enemy 就 PATROL | 每 tick 清空旧队列，或把远程 Agent 塞进同步 Brain | 检查固定 fallback 顺序 |
| observation 泄漏墙后信息 | serializer 重新扫描了完整 world | P2-P04；只序列化 `visibleTiles` |
| soft timeout 后请求消失 | soft/hard deadline 语义混淆 | soft 只改状态和 trace |
| hard timeout 后旧响应仍生效 | generation/epoch 未参与完整身份校验 | P2-S03、P2-S10 |
| 楼层切换后走向旧坐标 | 旧 Session 未 supersede/close | floor lifecycle + requestGeneration |
| 两个 Enemy 决策串线 | 共享 Session、queue 或 request context | P2-S15、P2-I02 |
| outbound 满时 feedback 消失 | 把所有消息都当可丢弃 offer | critical rejection 规则 |
| close 后测试挂住 | 没先关闭 Socket，read 无法解除 | P2-S14 |
| observation 发不出去 | 单 IO thread 永久阻塞在 read | 有界读/写调度 |
| 70 KiB 帧仍让内存暴涨 | `readLine()` 后才检查大小 | 使用 bounded byte frame reader |
| Python 接受重复 key/NaN | 使用裸 `json.loads()` | `object_pairs_hook`、`parse_constant` |
| 玩家离开 FOV 后仍被精确追踪 | 快脑读了真实 Player/world | P2-A08；检查数据依赖 |
| reflex 结束后远程计划丢失 | override 时直接清掉 lease | SUSPENDED → 条件恢复 |
| Phase 0/1 contract 失败 | 改了碰撞、私有感知、确定性或旧 trace schema | P2-R01；按失败的具体不变量定位 |

排错顺序建议：

1. 先看 connection/request/execution 三组状态。
2. 再核对完整身份元组。
3. 再检查 Game tick 所处阶段。
4. 最后才看网络日志和墙钟耗时。

不要用更多 sleep “修复”竞态；把缺少的状态或时钟 seam 补出来。

---

## 最终验收清单

### 协议与知识边界

- [ ] Java/Python 对 `phase2.session.v1` 的字段和限制一致。
- [ ] 坏 JSON、重复 key、非法数值、尾随垃圾、过深/过大帧均被拒绝。
- [ ] wire observation 不含墙后 tile、实体或可推断隐藏地图的信息。
- [ ] 未知 skill/parameter 不产生 lease 或 action。

### Session 与非阻塞

- [ ] 游戏线程从不执行阻塞 IO。
- [ ] 每个 Enemy 最多一个有效 in-flight；新 observation 会合并。
- [ ] soft timeout 保留请求和旧计划；hard timeout 使旧 generation 失效。
- [ ] queue 满时低优先级可丢、observation 可合并、关键消息不静默丢失。
- [ ] disconnect/reconnect、cancel grace 和 close 都有确定状态转换。
- [ ] 两个 Enemy 的 identity、queue、generation 和 response 完全隔离。

### 双速行为

- [ ] 每个 action tick 至多执行一个 Action。
- [ ] Python 慢或断线时 Enemy 继续旧计划或本地 fallback。
- [ ] 玩家进入 FOV/相邻时，P2/P1 在下一个 action tick 生效。
- [ ] GUARD 可以限制主动追击，但不能禁用 P0/P1。
- [ ] reflex override 显式开始/结束，旧 lease 只在仍有效时恢复。
- [ ] 新远程 lease 只在 poll 安全边界接管。

### 回归、证据和范围

- [ ] `Phase2TestSuite` headless 全绿。
- [ ] Phase 0/1 碰撞、私有感知、确定性和旧 trace schema 语义契约通过。
- [ ] 历史 Phase 0/1 baseline 仅作审计证据，不阻塞合理的 gameplay 重构。
- [ ] normal/delay/malformed/disconnect/no-read 五种模式均有证据。
- [ ] canonical trace 可关联 observation、request、intent、override、action、feedback。
- [ ] `agent.bridge.enabled=false` 是默认值，且不创建网络线程。
- [ ] 未引入 LLM、Tool Calling、共享上下文、复杂 skill 或多 Agent 协作。
- [ ] 未围绕 `playWithInputString()` 新建 runtime 路径。
- [ ] `PHASE_2_COMPLETION.md` 已记录测试、偏差和 Phase 3 交接。

通过这些检查后，Phase 2 才算完成；“Python 能回一条 CHASE”只是链路冒烟，不是阶段验收。
