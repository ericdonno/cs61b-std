# 阶段二完成后的架构审查与后续规划

## 1. 当前状态总览

阶段二（规则 AI + BFS 路径规划）已完成。敌人从"随机闲逛"升级为 **Brain → Planner → Action 三层编排**——手写规则做高层决策，BFS 做路径计算，动作队列消费执行。

与 [stage1-review-and-next-steps.md](stage-1-review-and-next-steps.md) 4.3 节的集成顺序对比：8 个步骤中完成了 7 个半。唯一未落实的是"MoveAction 简化构造函数"——实际走了另一条路（ClassicalPlanner 直接持有 EntityManager 引用），虽能工作但带来了耦合问题，见第 3 节。

### 阶段二新增文件

```
byog/Core/
├── EnemyBrain.java           # AI 大脑接口（策略模式）
├── RuleBasedBrain.java       # 规则 AI：曼哈顿距离判定追击/巡逻
├── GameStateSnapshot.java    # 游戏状态截屏（供 Brain 决策）
├── StrategicIntent.java      # 高层决策：Goal/Strategy/targetPosition
├── BFSPathfinder.java        # BFS 最短路径（纯函数）
├── ClassicalPlanner.java     # 意图→动作序列翻译器
└── (Enemy.java 重构)         # 引入 brain + 策略切换逻辑
```

### 阶段二的 AI 管线

```
Game.java AI tick (每帧)
    │
    │ GameStateSnapshot(world, playerPos, enemyPos, enemyId)
    ▼
Enemy.updateAI()
    │
    ├── RuleBasedBrain.think(snapshot)           ← Brain 层：高层决策
    │       ├── dist ≤ 7 → CHASE (target=playerPos)
    │       └── dist > 7 → PATROL (target=随机地板)
    │
    ├── ClassicalPlanner.translate(intent, ...)   ← Planner 层：翻译
    │       ├── BFSPathfinder.findPath(start, goal, world)
    │       ├── 路径 → List<MoveAction> (Direction.fromDelta)
    │       └── 不可达 → 随机方向占位
    │
    └── ActionQueue.poll() → execute() × 4 retries ← Action 层：执行
```

策略切换：Brain 产出不同策略 → `actionQueue.clear()` → 重新规划。同策略下队列 ≤2 时自动续补。

---

## 2. 架构审查：做对了什么

### 2.1 EnemyBrain 接口：为 LLM 打开的大门

`EnemyBrain` 是一个单一方法接口：

```java
StrategicIntent think(GameStateSnapshot state);
```

这意味着后续任何 AI 实现——手写规则、LLM、行为树、A* 变体——只要实现这个接口，就能无缝接入 Enemy。类比：这就像给 Enemy 装了一个可插拔的"大脑插槽"，今天插的是 RuleBasedBrain，明天可以拔下来换 LLMBrain，Enemy 自己完全不知道也不关心。

### 2.2 StrategicIntent：决策与执行之间的协议

`StrategicIntent` 不是简单的"去坐标 X,Y"，它承载了**目的语义**（Goal）和**策略语义**（Strategy）。更重要的是预留了 `confidence` 和 `targetRoom` 两个字段——这在阶段三（感知层 LLM 返回房间级别的模糊目标）和阶段四（LLM 带置信度的决策）中会直接派上用场。

> 类比：StrategicIntent 是指挥官给士兵的任务简报（"目标：拦截玩家，策略：抄近路埋伏，目的地：坐标(35,12)"），而不是微观的操作手册（"左脚 35 厘米，右脚 40 厘米"）。Planner 负责把简报翻译成操作手册。

### 2.3 BFSPathfinder：纯粹的、无依赖的计算

BFSPathfinder 是一个静态工具类，不依赖 EntityManager、不依赖 Game、不依赖任何外部状态。它的唯一输入是 `TETile[][]`（纯瓦片地图）+ 起点 + 终点，输出是位置列表。

这个纯粹性带来了两个好处：
- **可测试**：随时构造一个 5×5 瓦片数组就能测 BFS 行为
- **可复用**：阶段三的 LocalMapRenderer 想知道"从这到那多远"，可以直接调用它

另外，BFS 中对 `goal` 位置的特殊处理（允许非 FLOOR 的格子作为终点）是一个重要的细节。因为目标可能是玩家所在位置，而玩家所在的格子瓦片是 PLAYER 不是 FLOOR。如果 BFS 要求终点也是 FLOOR，那么"追到玩家身边一格"是能做到的，但"追到玩家当前所在格"算不出来——而实际上确有可能（玩家移动走后，那个格就是 FLOOR 了）。

### 2.4 ClassicalPlanner 的退化处理

当 BFS 返回空路径（目标不可达，例如 patrol 目标被随机到了墙后面的孤立房间），ClassicalPlanner 不会返回空动作列表让敌人原地卡死，而是返回一个随机方向的 MoveAction：

```java
Direction[] directions = Direction.values();
Direction fallback = directions[random.nextInt(directions.length)];
actions.add(new MoveAction(fallback, entityMgr));
```

这保证了即使规划失败，敌人也会继续尝试移动，不会被"卡住"。

### 2.5 策略切换的队列清空

Enemy 中维护了 `currentStrategy` 字段，每次 Brain 产出新意图后检查是否与当前策略不同。一旦发现切换（如 PATROL → CHASE），立即 `actionQueue.clear()` 清空旧队列，然后为新策略规划新路径。

这解决了"敌人还在执行去老目标的巡逻路径，但新决策说应该追玩家了"的问题——没有这个机制，敌人会在追玩家之前"先走完去上一个巡逻点的路"，行为看起来很蠢。

---

## 3. 架构审查：需要注意的问题

### 3.1 ClassicalPlanner 与 EntityManager 的耦合（中风险）

**现状**：ClassicalPlanner 创建 MoveAction 时需要 `EntityManager` 引用：

```java
actions.add(new MoveAction(d, entityMgr));
```

stage1-review 曾建议给 MoveAction 添加简化构造函数 `MoveAction(Direction direction)`，让 Planner 只产出方向信息，Enemy 在入队时注入 EntityManager。但实际实现中 ClassicalPlanner 直接持有 EntityManager 引用，导致 Planner（本应是纯计算组件）与执行环境耦合。

**为什么现在不是大问题**：ClassicalPlanner 的 `translate()` 是静态方法，entityMgr 作为参数传入而非存储为字段——耦合只存在于方法签名层面，不污染对象状态。

**为什么阶段四可能暴露问题**：当 LLMBrain 产出意图后，我们可能想先检查"这个意图能不能被规划出来"（预验证），再决定是否执行。如果预验证在 Game 层做，Game 层就需要调 ClassicalPlanner.translate()，此时 Game 必须持有 EntityManager——这没问题。但如果预验证在 LLMBrain 或一个独立的验证器中做，就需要 EntityManager 不在其上下文里。

**建议**：参照 stage1-review 的方案 A，给 MoveAction 加一个方向构造函数（不传 entityMgr），然后在 execute 时让 Enemy 注入 entityMgr。改动很小——一个构造函数 + execute 中允许 null entityMgr 的退化。这样做之后 ClassicalPlanner 就可以只产出 `List<Direction>` 或完全不依赖 EntityManager 的 MoveAction。

### 3.2 没有 PlanningResult 中间层（低风险，但阶段四可能需要）

当前 ClassicalPlanner 直接产出 `List<Action>`（可执行动作）。在阶段二这是够的，因为 RuleBasedBrain 的决策是确定性的，不需要"先看一下计划再决定"。

但阶段四 LLMBrain 可能产出"模糊目标"（如 "埋伏在玩家可能经过的路上"），这时你可能想：
1. 先让 ClassicalPlanner 规划几条候选路径
2. 再让 LLM 从候选路径中选择一条
3. 最后执行选择的那条

这种"规划→评估→选择→执行"的多阶段流程需要 ClassicalPlanner 能产出可被 `toString()` 表示或可被 LLM 读取的规划结果，而不是直接包装成 MoveAction。这不是必须现在就做，但在阶段四设计时要意识到这个缺口。

### 3.3 Enemy 缺少 brain setter（低风险）

Enemy 的 `brain` 字段在构造函数中直接赋值为 `new RuleBasedBrain(...)`，没有公开的 setter：

```java
this.brain = new RuleBasedBrain(sightRange, random);
```

阶段四引入 LLMBrain 时，需要给 Enemy 加一个 `setBrain(EnemyBrain brain)` 方法，或者让构造函数的调用方注入 brain。这是小改动，但不要忘记。

### 3.4 updateAI 签名：Player 直接传递 vs EntityManager 查询

stage1-review 3.2 节讨论了两种方案：
- 方案 A：在 EntityManager 上加 `getPlayer()`，Brain 自己查
- 方案 B：在 Game AI tick 处构建 GameStateSnapshot 传入

实际实现走了折中路线：GameStateSnapshot 在 Enemy.updateAI 内部构建，但 Player 对象作为参数传入 updateAI。这与 stage1-review 推荐的"EntityManager 持有所有信息"方式不同——Player 是直接传递的，不是从 EntityManager 查询的。

**影响**：如果阶段五需要让一个 Enemy 知道"另一个 Enemy 在哪"（多敌人协作），updateAI 签名需要再加参数或者改为从 EntityManager 查询。`EntityManager.getAllEntities()` 已经存在，所以升级成本不高。

### 3.5 可测试性缺口

BFSPathfinder 和 ClassicalPlanner 都没有单元测试。BFSPathfinder 尤其应该测——它是纯函数，测试成本极低。至少应该覆盖：
- 直线路径（无障碍）
- 绕墙路径（L 形走廊）
- 起点=终点（返回空列表）
- 目标不可达（返回空列表）
- goal 在非 FLOOR 格子（玩家所在格）时仍能找到路径到邻格

ClassicalPlanner 的退化逻辑（BFS 不可达时返回随机方向）也应该验证。

---

## 4. 阶段三（感知层）可行性分析

阶段三的核心组件是 `LocalMapRenderer`（局部文本地图渲染）和 `WorldDescription`（世界结构描述）。这两个组件与现有代码的关系：

| 组件 | 依赖 | 风险 |
|------|------|------|
| `LocalMapRenderer` | `TETile[][]` + enemy Position + 视野范围 | 无。纯文本生成函数，不接触 EntityManager |
| `WorldDescription` | `TETile[][]`（全局）| 无。纯函数 |

**与 Stage 2 代码的交互点**：

1. **GameStateSnapshot 需要扩展**：当前只有 4 个字段（world + playerPos + enemyPos + enemyId）。阶段三需要加入局部文本地图和世界描述，扩展为 `perception` 字段。

2. **BFSPathfinder 的复用**：LocalMapRenderer 在生成文本地图时，可以用 BFS 判断"视野内这个可达区域有多大"——这不是必需但可增加信息密度。

3. **EntityManager 查询视野内实体**：stage1-review 5.1 节指出，局部地图渲染时应从 EntityManager 查询视野内是否有其他实体，否则 LLM 看不到其他敌人。这需要在 LocalMapRenderer 中增加 `List<Entity>` 参数或通过 GameStateSnapshot 间接提供。

**建议的 GameStateSnapshot 扩展**：

```java
public class GameStateSnapshot {
    // 阶段二已有
    private final TETile[][] world;
    private final Position playerPosition;
    private final Position enemyPosition;
    private final int enemyId;

    // 阶段三新增
    private final String localMap;       // LocalMapRenderer 产物
    private final String worldContext;   // WorldDescription 产物
    private final List<PerceivedEntity> nearbyEntities; // 视野内其他实体
}
```

**结论**：阶段三对现有代码的改动很小——新增两个纯函数类 + 扩展 GameStateSnapshot 字段。RuleBasedBrain 不受影响（它不看 localMap，只用曼哈顿距离），LLMBrain 会在阶段四接入时使用这些新字段。

---

## 5. 阶段四~五可行性概述

### 5.1 阶段四（LLM 集成）：中风险

**好消息**：EnemyBrain 接口已经就位。LLMBrain 只需实现 `think(GameStateSnapshot)`，返回 `StrategicIntent`。

**需要做的工作**：
1. 新建 `LLMBrain implements EnemyBrain`
2. 拼接 System Prompt + User Message（User Message 内容来自 GameStateSnapshot 的 localMap + worldContext）
3. HTTP 调用 GPT-4o-mini / DeepSeek API，解析 JSON → `StrategicIntent`
4. 给 Enemy 加 `setBrain()` 方法
5. 在 Game 初始化时决定用 RuleBasedBrain 还是 LLMBrain

**主要挑战**：JSON 解析的鲁棒性。LLM 偶尔返回格式不符合预期的 JSON（少了括号、多了文字、嵌套错误）。需要写一个容错解析器——先尝试标准 JSON 解析，失败后用正则提取关键字段。这是阶段四最大的工作量，但技术上不复杂。

**Mock 策略**：建议同时准备 `MockLLMBrain`（返回预制 StrategicIntent），用于无网环境 Demo 和单元测试。

### 5.2 阶段五（记忆与中断）：中风险

**需要做的工作**：
1. 新建 `EnemyMemory` 类（记录 lastKnownPlayerPos、seenPlayerHistory 等）
2. EntityState 增加 memory 字段以支持持久化
3. 存档/读档时处理 memory 的序列化与反序列化
4. 中断重规划：Enemy 发现玩家位置与 memory 中的 lastKnownPlayerPos 不同时触发

**与现有代码的交互点**：
- ClassicalPlanner 的 AMBUSH 策略已经预留了等待动作（3 个 pause），但 AMBUSH 从未被 RuleBasedBrain 产出。阶段五可以让 LLMBrain 产出 AMBUSH。
- 策略切换的 `actionQueue.clear()` 机制已经在 Enemy 中实现，中断重规划直接复用。
- EntityState 需要扩展字段，但由于它能存 `Serializable`，可以直接把 `EnemyMemory` 放进去（前提是 EnemyMemory 实现 Serializable）。

**循环引用风险**：EnemyMemory → EntityState → 存档 → 读档 → 重建 Enemy → 注入 memory。这要求 Enemy 在构造后能接收 memory 对象，可能需要在 EntityState 中加 `memoryData` 字段，在 loadGameState 时注入。

---

## 6. 优先级建议

### 阶段二收尾（建议做）

1. **给 BFSPathfinder 写单元测试**：直线路径、绕墙路径、起点=终点、不可达、goal 在非 FLOOR 格。测试成本低，收益是防止阶段三/四改动时破坏 BFS。
2. **给 Enemy 加 `setBrain(EnemyBrain brain)`**：一行代码，为阶段四做好准备。

### 阶段三核心工作

3. 新建 `LocalMapRenderer`（纯文本地图生成）
4. 新建 `WorldDescription`（世界结构描述）
5. 扩展 `GameStateSnapshot` 容纳 perception 数据
6. 在 Game AI tick 处填充 perception 字段

### 阶段四核心工作

7. 新建 `LLMBrain`（HTTP 调用 LLM → JSON 解析 → StrategicIntent）
8. 容错 JSON 解析器
9. 新建 `MockLLMBrain`（用于测试和无网 Demo）

### 阶段五核心工作

10. 新建 `EnemyMemory`（实现 Serializable）
11. EntityState 扩展 memory 字段
12. 存档/读档适配 memory
13. 中断重规划（复用 strategy switch 机制）

### 可选优化

- **MoveAction 简化构造函数**（见 3.1 节）
- **PlanningResult 中间层**（见 3.2 节，阶段四时再评估是否需要）

---

## 7. 一句话总结

> 阶段二的 AI 分层架构（Brain → Planner → Action）干净利落，EnemyBrain 接口为 LLM 集成铺好了路，BFSPathfinder 的纯函数设计和 ClassicalPlanner 的退化处理体现了防御性思维。阶段二的遗留问题都是局部可修补的——MoveAction 耦合、缺少 brain setter、无测试——不需要结构性重构。阶段三是"加功能"而非"改架构"——新增两个纯函数 + 扩展一个字段——是最安全的一个阶段。阶段四的挑战不在架构而在 LLM JSON 解析的鲁棒性，建议提前准备容错解析器和 MockLLMBrain。
