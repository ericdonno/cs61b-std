# DungeonMind 测试系统：从确定性基线到私有感知

> 本文档介绍 DungeonMind 当前测试系统的跨版本设计：Phase 0 建立的确定性实验台，以及 Phase 1 在此基础上叠加的私有感知验证。当前共有 47 个不同的测试：Phase 1 总门禁包含 19 个 Phase 0 测试和 8 个 Phase 1 场景测试，另有 20 个独立感知单元测试。
>
> **阅读建议**：如果你还不清楚"确定性实验台"是什么，先看下面的"核心设计理念"。如果只关心如何运行测试和更新 baseline，直接跳到"操作指南"。

## 核心设计理念

整个测试系统围绕一个关键问题设计：**怎么确保今天改的代码没有悄悄改变昨天的 AI 行为？**

答案是"确定性隔离"——把地图、实体参数、随机种子、更新顺序全部固定。这样同一个场景跑两遍，从 AI 的决策到最终的世界状态，每一个字节都必须完全一致。一旦不一致，你就知道代码行为变了。

可以把它想象成一个**固定排练的录像对比系统**：

- `Harness` 是导演，搭好舞台后按固定顺序喊 Action
- 两个守卫是演员，按既有规则表演
- `AgentTrace` 是场记，逐帧记录每个守卫的决策和动作
- `baseline` 是保存下来的标准录像带，以后的排练都跟它逐帧比对

Phase 1 没有推翻这套系统，而是在上面加了一层：**同一个舞台，但演员戴上了不同的"眼罩"**——Guard A 能看到玩家，Guard B 被墙挡住看不见。Phase 1 的测试套件完整包含 Phase 0 的所有测试，确保新代码不会破坏旧的确定性契约。

## 共享的舞台：`baseline-two-guards` 场景

两个 Phase 使用的是同一张固定 ASCII 地图、同一组实体参数、同一个固定 seed。换场景不需要改 Java 代码——字符串数组一改就行。

```
#################   y=7  （人眼看最上面一行）
#.......#......>#   y=6
#.......#...B...#   y=5
#.......#####.###   y=4
#...............#   y=3
#..P.....A......#   y=2
#...............#   y=1
#################   y=0  （代码里 y=0 是最下面）
```

关键坐标（游戏坐标系，`y=0` 在最下）：

| 角色 | 坐标 | HP | 视野 | 攻击力 | 随机种子 |
|------|------|-----|------|--------|----------|
| 玩家 P | `(3, 2)` | 1000 | — | — | — |
| Guard A | `(9, 2)` | 20 | 7 | 1 | `new Random(101)` |
| Guard B | `(12, 5)` | 20 | 7 | 1 | `new Random(202)` |

**为什么视野设为 7**：A 到玩家的曼哈顿距离是 `|9-3| + |2-2| = 6`，刚好在视野内；B 到玩家是 `|12-3| + |5-2| = 12`，超出视野。这个差值让 Phase 0 就能验证"距离决定行为"——A 追击，B 巡逻。

**为什么用固定 seed 而不是 `new Random()`**：`new Random(101)` 每次调用 `nextInt()` 产生的序列完全相同。AI 中任何依赖随机数的决策（巡逻方向、伤害波动）都能精确复现。`new Random()` 用系统时钟做种子，每次运行结果不同——baseline 就没法比对了。

**为什么 A 和 B 的 seed 不同**：如果 seed 相同且 AI 需要随机巡逻方向，两个守卫会走出完全相同的步伐——这既不真实，也掩盖了"它们决策独立"这一事实。

> **一个容易踩的坑**：ASCII 字符串第 0 行是人眼看到的"最上面一行"，但代码里 `y=0` 对应最下面。Harness 里有一行 `gameY = h - 1 - row` 做翻转。如果你直接把 ASCII 行号当 y 坐标用，实体会出现在镜像位置，追踪 trace 时看到的位置和地图对不上。

## Phase 0：确定性实验台

Phase 0 回答的问题是：**"AI 的行为是否可重复、可追踪、可回归？"**

### 它怎么运作

每个 tick（逻辑回合），Harness 按以下固定顺序推进：

```
第 1 步：guardA.updateAI(world, entityMgr, player, ctxA, sink)
第 2 步：guardB.updateAI(world, entityMgr, player, ctxB, sink)
第 3 步：entityMgr.flushPendingChanges()     // 刷新位置索引
第 4 步：entityMgr.removeDeadEntities()      // 清理死亡实体
第 5 步：logicalTick++
```

**为什么必须先 A 后 B，不能反过来**：如果 B 先动、A 后动，trace 的时间线就完全变了，baseline 对不上。Harness 不依赖 `EntityManager` 的 `HashMap` 遍历顺序（HashMap 不保证顺序），而是显式写死调用顺序。

**为什么先让所有实体思考、再统一刷新**：如果边行动边刷新，B 在第 2 步看到的 A 位置可能取决于 A 是否已经刷新了位置索引——这引入了不确定性。先思考后刷新的两阶段模式，保证了帧边界的语义清晰。

每个守卫在一个 tick 内至少产生四条 trace 事件；发生动作重试时，`ACTION_ATTEMPTED/ACTION_RESULT` 会成对出现多次：

```
LEGACY_DECISION_INPUT  →  "我看到了什么"  （全知世界快照）
INTENT_SELECTED        →  "我决定做什么"  （CHASE 或 PATROL）
ACTION_ATTEMPTED       →  "我尝试了哪个动作"
ACTION_RESULT          →  "动作执行结果"   （SUCCESS / FAILURE / DAMAGE）
```

**为什么第一条事件叫 `LEGACY_DECISION_INPUT` 而不是 `OBSERVATION`**：Phase 0 的 Brain 收到的是完整的 `GameStateSnapshot`——包含全部地形和玩家的精确坐标。守卫能看到墙后的东西。这个名字明确告诉后续开发者：这不是正式感知，是旧版的全知输入。Phase 1 的 `OBSERVATION_GENERATED` 才是真正的私有感知事件。

运行 12 tick 后，整个 trace 被序列化成 canonical JSON——字段顺序固定、没有 `Entity.id`、没有时间戳、没有对象 hashCode。这个 JSON 就是 baseline（也叫 golden），相当于 AI 的"指纹"。

### 测试矩阵

Phase 0 测试入口：`Phase0TestSuite`，聚合 `EnemyCollisionTest` + `Phase0EncounterTest`，共 19 个测试。

| ID | 可观察的验证行为 |
|----|-----------------|
| P0-T01 | 构建固定场景后，地图宽 17、高 8，地形字符匹配预期 |
| P0-T02 | 传入不等宽行、未知字符、缺少必要角色、角色重复的 ASCII → 抛异常（10 个子用例） |
| P0-T03 | 两次构建同一场景 → `canonicalState()` 输出逐字节完全一致 |
| P0-T04 | 两次运行同一场景 → `canonicalTraceJson()` 输出逐字节完全一致 |
| P0-T05 | 第 0 tick，guard-a 的 Intent 为 CHASE，guard-b 为 PATROL |
| P0-T06 | 每 tick 每个守卫的 trace 事件链完整：有 input 必有 intent 必有 action attempt 必有 action result |
| P0-T07 | 12 tick 内，每帧所有活实体位置不越界、不重叠、站在合法 tile 上 |
| P0-T08 | 相同 seed 生成两张程序地图 → 逐字节一致 |
| P0-T09 | 完整 trace + final state 与 `phase0_rule_baseline_v1.json` 逐项一致 |
| P0-R01 | 500 tick 内，活实体坐标不与玩家或其他敌人坐标相同 |

> **P0-T06 和 P0-T09 的区别**：T06 验证 trace 的事件结构完整性（"有没有缺事件"），T09 验证整个 trace 的内容与 baseline 逐字节一致（"内容有没有变"）。如果只加了一个新的 trace 事件类型，T06 可能仍然通过，但 T09 会失败——因为 baseline JSON 里没有这个新字段。

## Phase 1：私有感知层

Phase 1 回答的问题是：**"在切断全知输入后，AI 的行为还符合私有知识的预期吗？"**

### 它改变了什么

Phase 1 引入了一个 `perceptionEnabled` 开关（默认 `false`，保持 Phase 0 兼容）。当开关打开时，`Enemy.updateAI()` 不再构造全知 `GameStateSnapshot`，而是调用 `PerceptionSystem.computeObservation()` 为每个守卫计算私有感知结果。

Bresenham 射线投射算法从守卫位置向视野范围内的每个 tile 发射一条直线——中途遇到墙就停。Guard A 到玩家 `(3, 2)` 的射线不穿过任何墙 → LOS 畅通 → 玩家可见。Guard B 到玩家的射线穿过 `(7, 4)` 的墙 → LOS 被挡 → 玩家不可见。

```
Guard A (9,2) → 玩家 (3,2) 的射线：
  (9,2)→(8,2)→(7,2)→(6,2)→(5,2)→(4,2)→(3,2)
  全程 FLOOR，无墙遮挡 → LOS 畅通

Guard B (12,5) → 玩家 (3,2) 的射线：
  (12,5)→(11,5)→(10,4)→(9,4)→(8,4)→(7,4)→...
  在 (7,4) 处撞墙 → LOS 被遮挡
```

这样，同一个 tick、同一张地图，A 和 B 看到的世界**不同**——A 的 `ObservationEnvelope` 中 `canSeePlayer() = true`，B 的为 `false`。这是后续 Agent 推理、通信和战术反制的物理基础。

### 它没改变什么

Phase 1 的 Harness 复用了 Phase 0 的 ASCII 解析逻辑、实体构造参数、headless 调度和 tick 推进顺序。`perceptionEnabled=false` 时，`Enemy.updateAI()` 的行为与 Phase 0 完全一致。

这意味着 **Phase 1 测试套件可以完整包含 Phase 0 的所有测试**。如果 Phase 1 的代码改动破坏了 legacy 路径，P0-T09 会立即失败。

### 测试矩阵

Phase 1 测试入口：`Phase1TestSuite`，聚合 `EnemyCollisionTest` + `Phase0EncounterTest` + `Phase1EncounterTest`，共 27 个测试（19 个 Phase 0 回归 + 8 个 Phase 1 测试）。

| ID | 可观察的验证行为 |
|----|-----------------|
| P1-T01 | Guard B 在私有感知下 `canSeePlayer() = false`，`visibleEntities` 不含 PLAYER |
| P1-T02 | Guard A 在私有感知下 `canSeePlayer() = true`，`getVisiblePlayer().getVisibleHp()` 等于真实 HP |
| P1-T03 | 同一 tick 内 A 和 B 的 `visibleEntityCount` 不同（信息不对称） |
| P1-T04 | B 首次 Intent 为 PATROL（看不到玩家），A 首次 Intent 为 CHASE（看得到） |
| P1-T05 | 两次构造的 Enemy 使用相同 `agentId` 字符串 → agentId 一致，与 JVM 自增 `id` 无关 |
| P1-T06 | `ObservationEnvelope` 包含 `runId`、`floorId`、`agentId`、`observationSeq`、`observedAtTurn` |
| P1-T07 | canonical trace 每 tick 包含 `OBSERVATION_GENERATED` 事件，且 A/B 的 `visiblePlayer` 字段值不同 |
| P1-T08 | 完整 Phase 1 trace + final state 与 `phase1_rule_baseline_v1.json` 逐字节一致 |

### 独立感知单元测试

`PerceptionSystemTest` 当前有 20 个测试，不在 `Phase1TestSuite` 中，需要单独运行：

- 6 个 `blocksVision` tile 分类测试；
- 12 个 Bresenham LOS 方向、遮挡、边界和终点墙测试；
- 1 个 observation 防御性复制测试，验证外部不能通过 `Position` 或 mask 修改 observation/实体；
- 1 个 walkability 快照测试，验证 live world 后续变化不会改写历史 observation。

## 两个 Phase 如何共存

### 套件嵌套

```
Phase1TestSuite
├── EnemyCollisionTest        (P0-R01)
├── Phase0EncounterTest       (P0-T01 ~ P0-T09)
└── Phase1EncounterTest       (P1-T01 ~ P1-T08)
```

运行 `Phase1TestSuite` 等于同时运行了 Phase 0 和 Phase 1 的全部测试。这不是"Phase 1 替代了 Phase 0"，而是"Phase 1 叠加在 Phase 0 之上"。

### trace 事件的版本演进

同一个 `AgentTrace` 协议被两个 Phase 共享，但事件类型不同：

| | Phase 0（legacy 路径） | Phase 1（私有感知路径） |
|---|---|---|
| 输入事件 | `LEGACY_DECISION_INPUT` | `OBSERVATION_GENERATED` |
| `inputKind` | `legacy-full-world-snapshot` | `private-perception-v1` |
| Brain 看到的 | 完整世界地图 + 精确玩家坐标 | 仅 FOV 内可见的 tile 和实体 |
| schema 版本 | `phase0.trace.v1` | `phase1.trace.v1` |

两个 schema 共用 `AgentTrace` 的事件模型，但 canonical 输出保持隔离：Phase 0 不输出 Phase 1 新增的感知字段，避免通过改写旧 golden 掩盖回归。

`INTENT_SELECTED`、`ACTION_ATTEMPTED`、`ACTION_RESULT` 三个阶段在两个路径中保持一致——私有感知只改变了"输入从哪来"，不改"决策怎么做"和"动作怎么执行"。

### 独立的 golden baseline

两个 Phase 各有自己的 baseline 文件，互不干扰：

| baseline | 路径 | 引用的测试 |
|---|---|---|
| Phase 0 golden | `documents/baselines/phase0_rule_baseline_v1.json` | P0-T09 |
| Phase 1 golden | `documents/baselines/phase1_rule_baseline_v1.json` | P1-T08 |

更新一个 baseline 不会影响另一个。如果改了 Brain 的决策逻辑，两个 baseline **都需要**手动审查后更新——因为同样的逻辑变化在 legacy 和 perception 路径下可能产生不同的 trace。

### `perceptionEnabled` 开关的生命周期

```
Phase 0：开关不存在，所有代码走 legacy 路径
Phase 1：开关引入，默认 false。Harness 显式设为 true。
Phase 2+：生产路径切换为 true，legacy 路径逐步下线
Phase 3+：开关和 GameStateSnapshot 全部移除
```

这个开关是临时工程手段——它让两个 Phase 的代码共存而不互相破坏。但它不应该活到 Phase 3。

### `actorKey` 到 `agentId` 的身份进化

| | Phase 0 | Phase 1 |
|---|---|---|
| 身份标识 | `actorKey`（硬编码字符串 `"guard-a"`） | `agentId`（Enemy 专属字段，可存档） |
| 来源 | Harness 在构造 `AgentTrace.Context` 时传入 | Enemy 构造参数 |
| 稳定性 | 只在这个场景下稳定 | 相同显式 agentId 跨 Enemy 实例稳定，持久化由存档层负责 |
| 在测试中的位置 | trace JSON 的 `actorKey` 字段 | `ObservationEnvelope.agentId` 与 P1-T05/P1-T06 |

`actorKey` 像是给演员起的艺名——不管今天第几个到场，艺名永远是"张三"。`Entity.id`（JVM 全局自增）像是签到序号——第一个到就是 1，但明天可能有人更早到。`agentId` 把"艺名"变成了身份证号——写入档案，跨场景有效。

## 操作指南

### 编译和运行

在 `proj2` 目录打开 PowerShell。

**编译**（位置一）：

```powershell
$javaSources = Get-ChildItem byog -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }

javac -encoding UTF-8 `
    -cp "..\library-sp18\javalib\*" `
    -d out $javaSources
```

**运行 Phase 0 测试**（位置二）：期望输出 `OK (19 tests)`。

```powershell
java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore `
    byog.Test.Phase0TestSuite
```

**运行 Phase 1 测试**（位置三）：期望输出 `OK (27 tests)`，其中包含全部 19 个 Phase 0 测试。

```powershell
java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore `
    byog.Test.Phase1TestSuite
```

**运行独立感知单元测试**（位置四）：期望输出 `OK (20 tests)`。

```powershell
java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore `
    byog.Test.PerceptionSystemTest
```

### 最近一次实测结果

验证日期：2026-07-22；工作目录：`proj2`；全程 headless。

| 步骤 | 结果 |
|------|------|
| 全量 `javac` 编译 | PASS；仅有既有 unchecked/unsafe operations 提示 |
| `Phase0TestSuite` | PASS — `OK (19 tests)` |
| `Phase1TestSuite` | PASS — `OK (27 tests)` |
| `PerceptionSystemTest` | PASS — `OK (20 tests)` |

本次验证同时确认：Phase 0 golden 继续使用 `phase0.trace.v1` 且字节级匹配；Phase 1 golden 使用 `phase1.trace.v1`；ObservationEnvelope 的防御性复制和 walkability 快照语义通过新增单元测试。

### baseline 更新规则

Baseline 是 AI 的"指纹"。日常测试只读不写。**只有确认行为变化是有意的，才能更新 baseline。**

更新 Phase 0 baseline：

```powershell
java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    byog.Test.Phase0BaselineMain `
    --write documents/baselines/phase0_rule_baseline_v1.json
```

更新 Phase 1 baseline：

```powershell
java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    byog.Test.Phase1BaselineMain `
    --write documents/baselines/phase1_rule_baseline_v1.json
```

更新后必须检查 Git diff。**不能因为测试挂了就盲目重生成 baseline**——这是在掩盖问题。

如果场景的物理含义发生变化（地图大小、守卫初始位置、HP/视野等参数），应该增加 `scenarioVersion`（如 v1 → v2），而不是静默覆盖历史 baseline。旧 baseline 是对历史行为的存档，删掉就失去了"这个改动到底改变了什么行为"的对比基准。

### 在代码中手动观察 trace

最简单的用法——运行 12 tick 并打印 Phase 0 的 trace：

```java
Phase0EncounterHarness harness =
        Phase0EncounterHarness.baselineTwoGuardsV1();

harness.runTicks(12);

System.out.println(harness.canonicalTraceJson());
System.out.println(harness.canonicalState());
```

Phase 1 同理，把类名换成 `Phase1EncounterHarness` 即可。

如果只关心某个守卫的决策序列：

```java
List<AgentTrace.TraceEvent> events = harness.getTraceSink().events();

for (AgentTrace.TraceEvent event : events) {
    if ("guard-a".equals(event.actorKey)) {
        System.out.println(event.logicalTick + " " + event.eventType);
    }
}
```

## 常见陷阱

### 陷阱 1：y 坐标是反的

**现象**：阅读 trace JSON 时发现实体坐标和 ASCII 地图对不上。

**原因**：人眼看 ASCII 第一行是地图最上面，但代码里 `y = 0` 是地图最下面。Harness 用 `gameY = h - 1 - row` 翻转。

**正确做法**：永远用游戏坐标系看坐标（`y=0` 在最下）。看 ASCII 地图时心里做翻转：row 0 → y=7，row 5 → y=2。

### 陷阱 2：`HashMap` 遍历顺序不保证

**现象**：如果自己写的测试在遍历 `EntityManager` 时依赖实体出现顺序，会在某些 JVM 上随机挂。

**原因**：`EntityManager` 内部用 `HashMap` 存实体，遍历顺序不保证。

**正确做法**：参考 Harness 的写法——显式写死调用顺序（先 A 后 B），不依赖容器的迭代顺序。

### 陷阱 3：测试日志噪声淹没断言失败

**现象**：运行测试时控制台输出大量 Enemy、Planner 和世界生成的 DEBUG/INFO 日志，真正的断言失败信息被淹没。

**原因**：`Logger` 在测试中仍然以默认级别输出。

**正确做法**：测试环境降低日志级别，失败时再打开详细诊断。诊断日志和机器输出应分离（诊断 → stderr，JSON → stdout）。

### 陷阱 4：盲目重生成 baseline

**现象**：P0-T09 或 P1-T08 失败，开发者重跑 BaselineMain 覆盖 baseline 文件后测试通过。

**原因**：baseline 的存在意义就是检测行为变化。覆盖它等于把"行为变了"这个事实删掉了。

**正确做法**：先确认行为变化是否有意。如果一个 Brain 逻辑改动导致 A 的追敌路径从 6 步变成 4 步，审查后确认这是预期的优化 → 更新 baseline 并记录原因。如果改动是"修复了一个 BFS 的 bug"但 baseline 显示 A 的行为完全没变 → 检查测试是否真的测到了变化路径。

## 术语速查

| 术语 | 一句话解释 |
|------|-----------|
| tick（逻辑回合） | 游戏逻辑推进一次：A 更新一次 + B 更新一次 = 1 tick |
| deterministic | 相同输入永远得到相同输出 |
| random seed | 伪随机数的固定起点（101、202），seed 相同则序列相同 |
| canonical | 约定唯一的 JSON 字段顺序和格式，从而能逐字节字符串比较 |
| baseline / golden | 保存下来的标准答案 JSON，用来做回归对比 |
| trace | 按时间顺序记录 AI 决策和动作的 JSON 事件流 |
| Harness | 不启动渲染器、不读键盘、headless 运行的测试支架 |
| FOV | 视野范围（Field of View），Phase 1 用 Bresenham 射线计算 |
| LOS | 视线（Line of Sight），两点之间无墙遮挡即为通畅 |
| Sink | trace 事件的接收器接口；`InMemorySink` 存内存，`NO_OP` 直接丢弃 |
| `perceptionEnabled` | Enemy 的开关字段，控制走 legacy 路径还是私有感知路径 |
| `actorKey` | Phase 0 的场景内稳定名字（`guard-a`），替代不稳定的 `Entity.id` |
| `agentId` | Phase 1 引入的 Enemy 专属正式身份，String 类型，稳定性由显式分配和存档保证 |
| `LEGACY_DECISION_INPUT` | Phase 0 trace 事件类型，标记全知世界快照输入 |
| `OBSERVATION_GENERATED` | Phase 1 trace 事件类型，标记私有感知输入 |
| headless | 不打开图形窗口运行 |
| regression test | 防止旧功能被新修改弄坏的测试 |

## 测试总览

### Phase 0（19 个测试）

| ID | 类别 | 一句话 |
|----|------|--------|
| P0-T01 | 场景契约 | 地图尺寸、坐标和地形正确 |
| P0-T02 | 输入校验 | 非法 ASCII（不等宽、未知字符、缺失角色、重复角色）立即抛异常 |
| P0-T03 | 确定性 | 两次构建 → canonical state 逐字节一致 |
| P0-T04 | 确定性 | 两次运行 → canonical trace 逐字节一致 |
| P0-T05 | 行为 | 第 0 tick A 选 CHASE，B 选 PATROL |
| P0-T06 | 结构 | 每 tick 事件链完整：input → intent → action attempt → result |
| P0-T07 | 不变式 | 所有活实体不越界、不重叠、站在合法 tile 上 |
| P0-T08 | 烟雾 | 相同 seed → 程序地图一致 |
| P0-T09 | 回归 | trace + state 与 golden baseline 逐项一致 |
| P0-R01 | 碰撞 | 500 tick 内实体不重叠 |

### Phase 1（叠加 8 个测试，共 27 个）

| ID | 类别 | 一句话 |
|----|------|--------|
| P1-T01 | 知识边界 | 墙后玩家不出现在 B 的 observation 中 |
| P1-T02 | 知识边界 | A 的 observation 包含玩家且 HP 正确 |
| P1-T03 | 不对称 | 同 tick 内 A 和 B 的 visibleEntityCount 不同 |
| P1-T04 | 决策 | B 首次 Intent 为 PATROL（看不见玩家），A 为 CHASE |
| P1-T05 | 身份 | agentId 跨实例一致，与 JVM 自增 id 无关 |
| P1-T06 | 契约 | ObservationEnvelope 包含完整身份与版本字段 |
| P1-T07 | trace | canonical trace 包含 OBSERVATION_GENERATED，A/B 的 visiblePlayer 不同 |
| P1-T08 | 回归 | Phase 1 trace + state 与 phase1 golden baseline 逐项一致 |

## 验收清单

读完本文档后，逐条检查自己是否能做到：

- [ ] 能画出 `baseline-two-guards` 的 17×8 ASCII 地图，标出三个实体和楼梯的游戏坐标
- [ ] 能解释 y 坐标翻转的原因和公式 `h - 1 - row`
- [ ] 能手算 guard-a 和 guard-b 到玩家的曼哈顿距离（6 和 12），并预测首次 Intent
- [ ] 能默写 Harness 的 `step()` 五步顺序，并说出"为什么必须先 A 后 B"
- [ ] 能画出单个 tick 的完整事件流：input → intent → action attempt → result
- [ ] 能解释 `LEGACY_DECISION_INPUT` 和 `OBSERVATION_GENERATED` 的区别
- [ ] 能解释 `actorKey` 和 `agentId` 的区别，以及为什么不用 `Entity.id`
- [ ] 能解释 canonical JSON 和普通 JSON 的区别
- [ ] 能说出 baseline 的更新规则：什么时候可以更新、什么时候绝对不能
- [ ] 能在终端编译并运行 `Phase0TestSuite`（19 tests）、`Phase1TestSuite`（27 tests）和独立 `PerceptionSystemTest`（20 tests）
- [ ] 能解释为什么 `Phase1TestSuite` 包含全部 Phase 0 测试
- [ ] 能说出 `perceptionEnabled` 开关的生命周期：它在哪个 Phase 引入、何时默认 false、何时移除
- [ ] 能解释 Bresenham 射线为什么判定 B 看不见墙后的玩家
- [ ] 能说出 P0-T06 和 P0-T09 的区别（结构完整性 vs 内容一致性）
