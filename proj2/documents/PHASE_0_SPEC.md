# Phase 0 Spec：固定遭遇、确定性基线与 Trace Seam

## 0. 元数据

- **Phase**：0
- **状态**：Draft — ready for Builder review；实现尚未开始
- **作者**：Codex（持续 Advisor）
- **创建日期**：2026-07-18
- **基线分支**：`ai-enemis`
- **基线 commit**：`a97353e6404fc06a063bea35e4648b30ef9f6176`
- **前一阶段 Completion**：无；Phase 0 是首个阶段
- **上位文档**：`PROJECT_INTENT_zh-CN.md`、`DEVELOPMENT_ROADMAP.md`

## 1. 必读输入与审计范围

本 Spec 基于以下实际输入，而不是从 Roadmap 标题自由扩写：

- `PROJECT_INTENT_zh-CN.md`
- `DEVELOPMENT_ROADMAP.md`
- `PHASE_SPEC_TEMPLATE.md`
- `byog/Core/Game.java`
- `byog/Core/Enemy.java`
- `byog/Core/RuleBasedBrain.java`
- `byog/Core/GameStateSnapshot.java`
- `byog/Core/Entity.java`
- `byog/Core/EntityManager.java`
- `byog/Core/Action.java`、`MoveAction.java`、`AttackAction.java`
- `byog/Core/EnemyCollisionTest.java`、`MathTest.java`
- `byog/Core/WorldGenerator.java`
- `byog/Helper/Logger.java`
- IntelliJ/JDK/JUnit 配置与当前命令行编译链

Phase 0 不引入新的易变外部依赖，因此本阶段不需要框架选型或网络调研。

## 2. 阶段目标与成功定义

建立一个不依赖 GUI、键盘、墙钟时间、网络、程序地图生成变化或默认存档的双守卫固定遭遇。它必须能够重复执行真实的 `RuleBasedBrain → ClassicalPlanner → ActionQueue → Action` 链，并产出可机器比较的 canonical trace。

Phase 0 成功后，我们将第一次拥有一个可靠实验台：后续可以只替换感知、Brain 或通信机制，并知道行为差异来自 Agent 系统，而不是地图、调度顺序或测试环境漂移。

本阶段不宣称已经实现 Agent。它负责建立证明未来 Agent 是否真实、是否作弊、是否优于基线所需的实验条件。

## 3. 起始事实

### 3.1 已确认的代码事实

1. 键盘主循环会在每帧遍历敌人并调用 `Enemy.updateAI()`，随后刷新实体索引：[Game.java](byog/Core/Game.java:91)。
2. `playWithInputString()` 只处理输入字符，不推进敌人 AI tick，并且结尾会初始化 `TERenderer`：[Game.java](byog/Core/Game.java:398)。因此它不能作为 headless Agent 遭遇入口。
3. `Enemy.updateAI()` 已包含完整的 Brain、Planner、ActionQueue 和 Action 执行链，可直接复用：[Enemy.java](byog/Core/Enemy.java:46)。
4. 现有 `EnemyCollisionTest` 已证明可以用手工小地图、明确实体和直接 tick 驱动进行测试：[EnemyCollisionTest.java](byog/Core/EnemyCollisionTest.java:13)。
5. `EntityManager` 使用 `HashMap`，`getAllEntities()` 返回没有顺序契约的 values view：[EntityManager.java](byog/Core/EntityManager.java:23)。固定遭遇不能依赖该遍历顺序决定 A/B 谁先行动。
6. 当前 `Entity.id` 是 JVM 全局静态自增整数，且不随存档保存：[Entity.java](byog/Core/Entity.java:12)。它不能进入 canonical trace，也不能承担未来正式 `agentId`。
7. `Logger` 只是写入 `System.out/System.err` 的自由文本：[Logger.java](byog/Helper/Logger.java:3)。解析日志不能作为结构化验收。
8. `GameStateSnapshot` 仍包含完整世界和玩家精确位置：[GameStateSnapshot.java](byog/Core/GameStateSnapshot.java:9)。Phase 0 只能把它记录为 `LEGACY_DECISION_INPUT`，不能称为正式感知。
9. `ActionResult` 当前混合执行状态和效果；攻击命中返回 `DAMAGE`，而 `Enemy.updateAI()` 只有收到 `SUCCESS` 才停止 retry：[Action.java](byog/Core/Action.java:5)、[AttackAction.java](byog/Core/AttackAction.java:78)、[Enemy.java](byog/Core/Enemy.java:73)。Phase 0 记录原始结果，但不擅自重定义语义。
10. 程序地图在非空 seed 下基本可重复，但会随生成算法和配置变化，不适合作为唯一 golden fixture：[WorldGenerator.java](byog/Core/WorldGenerator.java:14)、[Enemy.java](byog/Core/Enemy.java:132)。

### 3.2 构建与测试事实

- 项目没有 Gradle/Maven；IntelliJ 将整个 `proj2` 当作 source root。
- 当前环境使用 JDK 19，JUnit 4.12 位于 `../library-sp18/javalib`。
- 2026-07-18 实际运行以下基线命令时，全部 Java 源码编译成功，但 `EnemyCollisionTest` 失败：

```powershell
$javaSources = Get-ChildItem byog -Recurse -Filter *.java | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -cp "..\library-sp18\javalib\*" -d out $javaSources
java -cp "out;..\library-sp18\javalib\*" org.junit.runner.JUnitCore byog.Core.EnemyCollisionTest
```

失败为：`Tick 24: Enemy2 overlapped Player`。原因是玩家已被杀死，而碰撞规则允许进入死亡实体位置；测试却继续把死亡玩家当作活体占位。这应通过修正测试前提处理，不在 Phase 0 顺手决定“尸体是否占格”的玩法。

`MathTest` 使用无 seed 的随机数、系统时间、控制台解析和 GUI 入口，不作为 Phase 0 自动回归门槛。

## 4. 需求追踪

| Requirement | Phase 0 的处理 | 验收证据 |
|-------------|----------------|----------|
| INV-01 独立身份 | 使用场景级稳定 `actorKey` 区分 `guard-a/guard-b`；正式可保存 `agentId` 留给 Phase 1 | 同 JVM 两次运行 actorKey 和 trace 一致 |
| INV-02 有限知识 | 尚未满足；当前输入必须明确标记 `LEGACY_DECISION_INPUT` | trace 不出现“正式 observation”命名；Completion 记录该限制 |
| INV-03 世界内通信 | 不在本阶段实现 | Out of Scope 检查 |
| INV-04 Java 权威 | fixture 调用真实 Java Brain/Planner/Action 链，不模拟结果 | lifecycle trace 与实体最终状态 |
| INV-05 分层控制 | 固定 `RuleBasedBrain` 与确定性低层执行作为 baseline | 首次 CHASE/PATROL 与 action trace |
| INV-06 严格契约 | 先为 trace 建立版本化、固定字段契约 | canonical JSON 与 schemaVersion |
| INV-07 异步时效 | 不在本阶段实现 | Out of Scope 检查 |
| INV-08 可追踪评估 | 建立逻辑 tick、actor、intent、action、raw result 的关联 seam | trace 完整性测试 |
| INV-09 玩法价值 | 固定遭遇未来可用于 Rule/Agent A/B，但本阶段不判断谁更好玩 | fixture 与 baseline artifact |

## 5. 范围与非目标

### 5.1 In Scope

- 一个版本化的手写 ASCII 双守卫固定场景。
- 一个无头、单步、确定顺序的 `Phase0EncounterHarness`。
- 一个最小、可注入、默认 no-op 的结构化 `AgentTrace` seam。
- 对现有 `Enemy.updateAI()` 的无行为变化 trace overload。
- 一份 `RuleBasedBrain` canonical baseline JSON。
- JUnit 4 Phase 0 测试套件和明确的编译/运行命令。
- 修正 `EnemyCollisionTest` 的死亡玩家测试前提，使其重新验证“活实体不重叠”。
- 一个固定 seed 程序世界的次要确定性 smoke test。

### 5.2 Out of Scope

- LOS/FOV、听觉、光照、私有 observation。
- Python、HTTP、LangGraph、LLM、Tool Calling。
- 正式 `runId/floorId/agentId`、存档身份迁移和 checkpoint。
- 异步 mailbox、超时、fallback、过期决策。
- 世界内呼喊、消息、报警和设施。
- 重定义 `ActionResult`、实现最终 `ActionOutcome`。
- 修改 `RuleBasedBrain` 的全知输入或战术规则。
- 统一键盘 Game loop 与测试 harness 的调度器。
- trace UI、LangSmith、OpenTelemetry、数据库。
- 游戏平衡和战斗/潜行/欺骗的最终侧重。

## 6. 已锁定决定、假设与待决定项

### 6.1 已锁定决定

#### D0-01：主 fixture 使用手写 ASCII，不使用程序生成 seed

场景 ID 为 `baseline-two-guards`，版本为 `1`。ASCII 第一行代表最高 y：

```text
#################
#.......#......>#
#.......#...B...#
#.......#####.###
#...............#
#..P.....A......#
#...............#
#################
```

尺寸为 `17 × 8`，坐标原点在左下角：

- Player `P`：`(3, 2)`
- Guard A `A`：`(9, 2)`
- Guard B `B`：`(12, 5)`
- Stairs `>`：`(15, 6)`

地形映射：`# → WALL`、`. → FLOOR`、`> → STAIRS`；`P/A/B` 所在底层 tile 均为 `FLOOR`。

该几何让 A 与玩家曼哈顿距离为 6，B 与玩家距离为 12。使用 sight range 7 时，A 的首次意图应为 `CHASE`，B 的首次意图应为 `PATROL`。墙与单一连接缺口可供 Phase 1/5 复用，但 Phase 0 不实现遮挡或通信。

#### D0-02：实体参数完全显式

- Player：HP `1000`；Phase 0 不发送玩家动作。
- Guard A：HP `20`，sight `7`，move interval `1`，attack `1`，variance `0`，Random seed `101`。
- Guard B：HP `20`，sight `7`，move interval `1`，attack `1`，variance `0`，Random seed `202`。
- actorKey 固定为 `guard-a`、`guard-b`。
- 不读取 `config/game.properties`。

#### D0-03：固定调度语义

- `logicalTick` 从 `0` 开始。
- 每 tick 按 `[guard-a, guard-b]` 顺序调用真实 `Enemy.updateAI()`。
- 两名敌人完成更新后，只调用一次 `EntityManager.flushPendingChanges()`，再调用 `removeDeadEntities()`。
- canonical baseline 执行 12 tick，玩家始终 WAIT。
- harness 不启动 `TERenderer`、不读取键盘、不 sleep、不访问网络、不读写默认存档。

#### D0-04：canonical 身份和时间

- canonical trace 使用 `actorKey`，禁止包含当前 `Entity.id`。
- canonical 时间只使用 `logicalTick` 与 sink 分配的单调 `sequence`。
- wall-clock timestamp、耗时和控制台日志可以作为诊断信息，但不能参与 golden 比较。
- `actorKey` 目前是 scenario-local 测试身份；Phase 1 再定义生产 `agentId` 和存档语义。

#### D0-05：只观测 legacy 行为，不偷偷修 AI

- Phase 0 不改变 `RuleBasedBrain`、Planner、ActionQueue、retry 或 `ActionResult` 行为。
- 改造前后，相同世界和 Random seed 的位置/HP 轨迹必须相同。
- 攻击返回 `DAMAGE` 等现状应原样记入 `rawActionResult`，并作为 Phase 4 的输入问题。

#### D0-06：golden 更新必须显式

普通测试只能读取和比较 `documents/baselines/phase0_rule_baseline_v1.json`，不得在失败时自动覆盖。更新 baseline 必须运行显式生成命令，并经过 diff 审查；行为有意变化时应优先增加 scenarioVersion，而不是静默改写历史。

### 6.2 暂时假设

- 12 个 logical tick 足以让 A、B 都产生至少一次 intent 和 action result。实现后若证据不满足，只允许调整 tick 数并重新审查 golden，不改变场景几何来迎合测试。
- JDK 19 与当前 `java.util.Random` 构成 Phase 0 canonical 环境。未来升级 JDK 时必须重新运行 determinism 测试，而不是假设 golden 必然不变。
- test harness 的显式更新顺序用于实验可重复性，不代表已经定义最终游戏中所有敌人的公平调度规则。

### 6.3 需要 Builder 决定

Phase 0 开始实现前没有必须由 Builder 决定的产品问题。以上选择属于可回滚的实验基础设施决定。

如果 Builder 希望更换场景几何或 baseline 长度，应在实现前提出；一旦 baseline artifact 被接受，修改必须提升 scenarioVersion。

## 7. 目标架构与数据流

```text
ASCII fixture
    ↓ parse once
Phase0EncounterHarness
    ├─ terrain TETile[][]
    ├─ Player
    ├─ ordered actors [guard-a, guard-b]
    ├─ EntityManager
    └─ AgentTrace.InMemorySink
             │
             │ step(logicalTick)
             ▼
Enemy.updateAI(..., TraceContext, TraceSink)
    ├─ LEGACY_DECISION_INPUT
    ├─ EnemyBrain.think
    ├─ INTENT_SELECTED
    ├─ ClassicalPlanner / ActionQueue
    ├─ ACTION_ATTEMPTED
    └─ ACTION_RESULT
             │
             ▼
EntityManager.flushPendingChanges / removeDeadEntities
             │
             ▼
invariant checks + canonical JSON + golden comparison
```

重要边界：

- 世界状态仍由现有 Java 对象持有和修改。
- `AgentTrace` 只观察，不允许改变 intent、action 或结果。
- `TraceSink.NO_OP` 必须保持现有游戏调用无额外行为。
- harness 不复制 Brain/Planner/Action 规则；它只负责构造场景和确定性调度。

## 8. 接口与数据契约

### 8.1 `AgentTrace`

新增单一容器类 `byog.Core.AgentTrace`，其中包含以下嵌套类型：

```java
public final class AgentTrace {
    public static final String SCHEMA_VERSION = "phase0.trace.v1";

    public enum EventType {
        LEGACY_DECISION_INPUT,
        INTENT_SELECTED,
        ACTION_ATTEMPTED,
        ACTION_RESULT
    }

    public static final class Context {
        public final String scenarioId;
        public final int scenarioVersion;
        public final long logicalTick;
        public final String actorKey;
    }

    public static final class Event { /* immutable fields below */ }

    public interface Sink {
        void record(Event event);
    }

    public static final Sink NO_OP = event -> { };

    public static final class InMemorySink implements Sink {
        public List<Event> events();
        public String toCanonicalJson();
    }
}
```

`Event` 应提供四个静态 factory，而不是让调用方任意组合 nullable 字段：

```java
Event.legacyDecisionInput(Context context)
Event.intentSelected(Context context, StrategicIntent intent)
Event.actionAttempted(Context context, int actionOrdinal,
                      Action action, Position before)
Event.actionResult(Context context, int actionOrdinal,
                   Action action, Action.ActionResult result,
                   Position before, Position after)
```

`Event` 的固定字段为：

| 字段 | 类型 | 规则 |
|------|------|------|
| `schemaVersion` | String | 固定 `phase0.trace.v1` |
| `scenarioId` | String | 固定场景为 `baseline-two-guards` |
| `scenarioVersion` | int | 当前为 1 |
| `logicalTick` | long | 从 0 单调增长 |
| `actionOrdinal` | nullable Integer | 同 actor、同 tick 内从 0 递增；attempt/result 共用 |
| `actorKey` | String | `guard-a` 或 `guard-b` |
| `eventType` | enum | 上述四类之一 |
| `inputKind` | nullable String | decision input 时为 `legacy-full-world-snapshot` |
| `goal` | nullable String | `StrategicIntent.Goal.name()` |
| `strategy` | nullable String | `StrategicIntent.Strategy.name()` |
| `targetX/targetY` | nullable Integer | intent 目标 |
| `actionType` | nullable String | `MoveAction` / `AttackAction` 等类名 |
| `rawActionResult` | nullable String | 当前 `ActionResult.name()`，不重解释 |
| `beforeX/beforeY` | nullable Integer | action 执行前 actor 位置 |
| `afterX/afterY` | nullable Integer | action 执行后 actor 位置 |

`sequence` 不需要存入 `Event` 构造参数；`InMemorySink` 按 append index 从 0 生成 canonical JSON 中的 sequence。字段输出顺序固定为：`schemaVersion, scenarioId, scenarioVersion, logicalTick, sequence, actionOrdinal, actorKey, eventType, inputKind, goal, strategy, targetX, targetY, actionType, rawActionResult, beforeX, beforeY, afterX, afterY`。nullable 字段统一输出 JSON `null`，禁止依赖 HashMap 序列化顺序。

### 8.2 `Enemy.updateAI` trace overload

保留现有签名，确保所有旧调用继续工作：

```java
public void updateAI(TETile[][] world, EntityManager entityMgr, Player player)
```

新增：

```java
public void updateAI(TETile[][] world,
                     EntityManager entityMgr,
                     Player player,
                     AgentTrace.Context traceContext,
                     AgentTrace.Sink traceSink)
```

旧签名必须委托给新签名并使用 `AgentTrace.NO_OP`。新签名在以下位置发事件：

1. 构造当前 `GameStateSnapshot` 后：`LEGACY_DECISION_INPUT`。
2. `brain.think()` 返回后：`INTENT_SELECTED`。
3. 每次从 ActionQueue poll 出非空 action 后、执行前：`ACTION_ATTEMPTED`。
4. `action.execute()` 返回后：`ACTION_RESULT`，包含 raw result 和前后位置。

同一 actor、同一 tick 内，每次 retry 使用从 0 开始的 `actionOrdinal`；attempt/result 必须共享 ordinal。除把返回值保存到局部变量用于记录外，现有 retry 和 break 条件不得改变。

### 8.3 `Phase0EncounterHarness`

```java
public final class Phase0EncounterHarness {
    public static Phase0EncounterHarness baselineTwoGuardsV1();
    public void step();
    public void runTicks(int count);
    public String canonicalTraceJson();
    public String canonicalState();
    public TETile[][] terrainCopy();
    public Player player();
    public Enemy guardA();
    public Enemy guardB();
    public Position stairsPosition();
}
```

`canonicalState()` 必须使用以下固定结构，不包含 `Entity.id`、对象 hash、墙钟时间或 HashMap 遍历顺序：

```json
{
  "logicalTick": 12,
  "terrain": ["top row first", "...", "bottom row last"],
  "stairs": {"x": 15, "y": 6},
  "actors": [
    {"actorKey": "player", "x": 3, "y": 2, "hp": 1000, "alive": true},
    {"actorKey": "guard-a", "x": 0, "y": 0, "hp": 20, "alive": true},
    {"actorKey": "guard-b", "x": 0, "y": 0, "hp": 20, "alive": true}
  ]
}
```

示例中的 `logicalTick` 以外数值只用于展示字段，其中守卫最终坐标和所有最终 HP 必须由真实运行结果产生；actors 数组顺序固定为 player、guard-a、guard-b。

ASCII parser 遇到未知字符、重复 P/A/B/>、缺少任何必需角色或不等宽行时，必须抛出 `IllegalArgumentException`，不能静默修复。

### 8.4 Baseline 生成入口

baseline 文件顶层结构固定为：

```json
{
  "baselineSchemaVersion": "phase0.baseline.v1",
  "scenarioId": "baseline-two-guards",
  "scenarioVersion": 1,
  "tickCount": 12,
  "events": [],
  "finalState": {}
}
```

新增 `Phase0BaselineMain`。只有显式传入：

```text
--write documents/baselines/phase0_rule_baseline_v1.json
```

才允许写文件。无参数时只向 stdout 输出 canonical JSON。禁止普通 JUnit 测试写 golden。

## 9. 逐文件变更计划

| 文件 | 新建/修改 | 责任 | 关键变更 | 不应包含 |
|------|-----------|------|----------|----------|
| `byog/Core/AgentTrace.java` | 新建 | 最小结构化 trace seam | Context、Event、Sink、InMemorySink、canonical JSON | LLM、网络、wall-clock、正式 Observation |
| `byog/Core/Enemy.java` | 修改 | 暴露无行为变化 trace overload | 四类 lifecycle event；旧签名委托 NO_OP | 修改规则、retry、Planner 或 ActionResult |
| `byog/Core/Phase0EncounterHarness.java` | 新建 | ASCII fixture、显式 A/B 调度、状态查询 | 固定场景 v1、12 tick runner、invariants 所需 getters | Renderer、键盘、默认存档、外部配置 |
| `byog/Core/Phase0BaselineMain.java` | 新建 | 显式生成或打印 baseline | `--write` 门槛、UTF-8 固定输出 | 测试失败时自动更新 golden |
| `byog/Core/Phase0EncounterTest.java` | 新建 | Phase 0 contract tests | P0-T01 至 P0-T09 | 人工观察代替断言 |
| `byog/Core/Phase0TestSuite.java` | 新建 | 单一 JUnit 入口 | 聚合 Phase0EncounterTest 与 EnemyCollisionTest | MathTest |
| `byog/Core/EnemyCollisionTest.java` | 修改 | 恢复活实体碰撞测试前提 | 使用不会在 500 tick 内死亡的玩家，或死亡即停止 | 决定尸体占格玩法 |
| `documents/baselines/phase0_rule_baseline_v1.json` | 新建 artifact | RuleBased canonical baseline | schemaVersion、scenario、events、finalState | entityId、timestamp、自由文本日志 |
| `PHASE_0_COMPLETION.md` | 验收时新建 | 关闭阶段与交接 | commit、命令、结果、偏差、Phase 1 输入 | 未经验证的“完成”声明 |

## 10. 实施顺序

### Step 0.1：恢复可信测试起点

- 修改 `EnemyCollisionTest`，让玩家在测试期间保持存活，继续只验证活实体不重叠。
- 新增 `Phase0TestSuite`，暂时只包含修复后的 collision test。
- 编译并运行，要求绿色。

### Step 0.2：建立 trace seam

- 新增 `AgentTrace`。
- 为 `Enemy.updateAI` 添加 overload 和四类事件。
- 保留旧签名 NO_OP 路径。
- 增加一项回归测试：启用 NO_OP 前后，相同输入的最终位置、HP 和 actionQueue size 相同。

### Step 0.3：建立固定 fixture 与 headless runner

- 实现严格 ASCII parser。
- 按 D0-01/D0-02 创建 terrain 和实体。
- 按 D0-03 实现 step/runTicks。
- 先完成 layout、determinism 和 headless 测试。

### Step 0.4：完成 lifecycle 与 invariant 测试

- 验证 A 首次 CHASE、B 首次 PATROL。
- 验证每个 `INTENT_SELECTED` 后存在同 actor、同 tick 的 action attempt/result，允许没有合法 action 时显式说明。
- 每 tick 检查所有活实体位置唯一、在界内且可站立。
- 比较同 JVM 两次运行的 canonical state 和 trace。

### Step 0.5：生成并锁定 baseline artifact

- 运行 `Phase0BaselineMain --write ...`。
- 人工审查 JSON 中没有 entityId、wall-clock、对象 hash、全局地图假装成正式 observation 等字段。
- 将 golden comparison 加入 P0-T09。
- 普通测试再次运行，确认不写文件且结果绿色。

### Step 0.6：记录 Completion

- 记录最终 commit、JDK、命令、测试输出和 baseline 文件 hash。
- 记录未解决的 legacy 问题：全知 snapshot、Entity ID、HashMap 游戏调度、ActionResult 语义、存档身份。
- 明确 Phase 1 可以依赖的 fixture、trace seam 和测试命令。

## 11. 测试与验收矩阵

| Test ID | 场景 | 核心断言 | 类型 | 对应需求 |
|---------|------|----------|------|----------|
| P0-T01 | `fixtureHasExpectedLayout` | 尺寸、四个坐标、唯一角色、terrain 映射准确 | 自动 | INV-04 |
| P0-T02 | `fixtureRejectsInvalidAscii` | 非等宽、未知字符、重复（P/A/B/>）或缺失角色均失败 | 自动 | INV-06 |
| P0-T03 | `fixtureBuildIsDeterministic` | 同 JVM 两次构建 canonical state 一致，不比较 Entity.id | 自动 | INV-01/05 |
| P0-T04 | `sameRunProducesSameCanonicalTrace` | 相同 12 tick 运行两次 trace 字节一致 | 自动 | INV-05/08 |
| P0-T05 | `bothGuardsProduceExpectedInitialIntent` | guard-a 首次 CHASE；guard-b 首次 PATROL | 自动 | INV-05/08 |
| P0-T06 | `traceContainsDecisionLifecycle` | 两名守卫均有 input、intent、action attempted/result 且可关联 | 自动 | INV-08 |
| P0-T07 | `liveEntitiesRespectWorldInvariants` | 每 tick 活实体不重叠、不越界、不站非法 tile | 自动 | INV-04/05 |
| P0-T08 | `fixedSeedWorldIsDeterministicSmokeTest` | 同一非空 seed 两次 terrain 文本一致 | 自动 | INV-05 |
| P0-T09 | `ruleBaselineMatchesGolden` | canonical trace + final state 与 v1 JSON 一致 | 自动 | INV-08/09 |
| P0-R01 | `EnemyCollisionTest` | 修正前提后 500 tick 活实体无重叠 | 自动回归 | INV-04/05 |
| P0-M01 | baseline JSON review | 无隐藏非确定字段，无错误 Observation 命名 | 人工一次 | INV-02/08 |

普通验收命令：

```powershell
$javaSources = Get-ChildItem byog -Recurse -Filter *.java | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -cp "..\library-sp18\javalib\*" -d out $javaSources
java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" org.junit.runner.JUnitCore byog.Core.Phase0TestSuite
```

Phase 0 Completion 必须保存完整命令和最终摘要，不能只写"本地通过"。

## 12. Observability 与运行证据

canonical evidence 包括：

- trace schemaVersion；
- scenario ID/version；
- logicalTick 和 sequence；
- actorKey；
- legacy input 标记；
- goal、strategy、target；
- action type；
- raw ActionResult；
- action 前后位置；
- final canonical state。

以下内容只允许作为诊断，不参与比较：

- `Logger` 自由文本；
- JVM 分配的 Entity.id；
- wall-clock timestamp 和执行耗时；
- 对象 hash、集合自然遍历顺序；
- reasoning 文本。

Phase 0 不要求 trace viewer。JSON 和 JUnit 失败 diff 足以支持本阶段。

## 13. 失败处理、兼容与迁移

- 现有 `Enemy.updateAI(world, entityMgr, player)` 必须保持源代码兼容和行为兼容。
- trace 构造或 sink 失败不得改变游戏状态；Phase 0 实现应使用内存 sink，禁止吞掉测试中的 trace 异常。
- fixture 解析错误立即失败，不进行 best-effort 修复。
- golden 不匹配时测试失败，不自动更新。
- 不修改当前存档格式；正式 agentId、随机流恢复和 Agent checkpoint 留给后续 Phase。
- 不修复 RuleBasedBrain 的全知输入；Completion 必须把它列为 Phase 1 的显式起点。
- 不重新定义死亡实体是否占格；`EnemyCollisionTest` 仅修正活体碰撞测试前提。

## 14. 风险与停止条件

### 主要风险

1. **Trace instrumentation 改变行为**：如果保存 ActionResult 或位置时意外改变 retry 顺序，baseline 将失真。必须先做 NO_OP 行为等价测试。
2. **Golden 过度脆弱**：禁止纳入 Entity.id、时间、HashMap 顺序和日志文本。
3. **Phase 膨胀**：存档身份、LOS、ActionOutcome 重构都重要，但不属于 Phase 0。
4. **测试与真实游戏混淆**：harness 是可重复实验调度，不宣称已经统一键盘 Game loop。
5. **旧测试假绿色**：当前 collision test 已实测失败；Completion 必须展示修复前后证据。
6. **文档未被 Git 跟踪**：父级 `.gitignore` 当前忽略 Markdown。跨 clone 交接前必须显式 force-add 这些基准文档或调整仓库忽略规则；不能把仅本机存在误认为已版本化。

### 停止条件

出现以下情况时停止实现并回到 Spec：

- 必须改变玩家体验或死亡实体规则才能让测试通过；
- 需要修改 Intent 或跨阶段协议；
- trace 无法在不改 AI 行为的情况下加入；
- fixture 必须依赖 GUI、系统时间或默认存档；
- 实现者准备顺手加入 LOS、Python、LLM 或通信。

## 15. Definition of Done

- [x] P0-T01 至 P0-T09、P0-R01 全部通过（共 19 tests）。
- [x] `Phase0TestSuite` 可通过文档中的单一命令运行，全程不打开窗口。
- [x] 同一 JVM 连续运行两次得到字节一致的 canonical trace（P0-T04 验证）。
- [x] trace 中可独立过滤 guard-a 和 guard-b，并关联 input → intent → action → raw result（P0-T06 验证）。
- [x] baseline JSON 存在，普通测试不会覆盖它（仅 Phase0BaselineMain --write 可写）。
- [x] 现有游戏入口仍能编译；旧 `Enemy.updateAI` 调用保持兼容（委托到 NO_OP）。
- [x] Phase 0 没有把 legacy full-world snapshot 宣称为私有 observation（明确标记 `LEGACY_DECISION_INPUT`）。
- [x] 没有引入 Python、LLM、网络、向量数据库或正式记忆系统。
- [x] 已知失败、测试排除项和未解决 legacy 行为写入 Completion。
- [x] `PHASE_0_COMPLETION.md` 已生成，并列出 Phase 1 所需 artifacts。

## 16. 下一阶段交接

Phase 1 可以依赖：

- `baseline-two-guards:v1` 固定几何与 actorKey；
- headless、显式顺序的 runner；
- trace Context/Event/Sink 与 canonical JSON；
- RuleBasedBrain baseline artifact；
- Phase 0 JUnit suite 与编译命令；
- 已被明确标记的 `LEGACY_DECISION_INPUT` seam。

Phase 1 不得假设：

- 当前 `GameStateSnapshot` 已满足知识边界；
- scenario actorKey 已经等于生产可保存 agentId；
- HashMap 游戏调度已经确定化；
- ActionResult 已经是最终 ActionOutcome；
- 存读档会保持 Agent 身份、Brain state 或随机序列；
- 固定 fixture 已经实现 LOS、声音或通信。

Phase 1 的首要任务将是：在保留同一个 fixed encounter 和 trace 对比能力的前提下，用 Java 权威 `PerceptionSystem` 产生每敌人不同的私有 Observation，并证明墙后和未感知信息不会泄露。

## 附录 A：Spec 自检

- [x] 已先审计仓库，不是从 Roadmap 自由扩写。
- [x] 未把旧 LLM Build Guide 当作当前事实。
- [x] 区分了事实、决定、假设和后续问题。
- [x] 引用了关键代码位置和实际测试失败。
- [x] 没有提前建设后续 Phase 的 Agent 系统。
- [x] 每项完成条件都有可执行验证方式。
- [x] 明确列出了下一名 AI 可以依赖与不得假设的内容。
