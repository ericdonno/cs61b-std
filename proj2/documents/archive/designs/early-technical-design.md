# DungeonMind 技术设计文档 (TDD)

## 1. 概述

### 项目名称

DungeonMind：基于大语言模型的自适应敌人系统

### 项目背景

本项目基于 CS61B 课程 Project 2（BYOG — Build Your Own Game）的现有代码库。当前项目已具备：

- **完整的程序化地牢生成系统**（`byog.Core.WorldGenerator`）：使用随机种子生成由 `SquareRoom` 和 `Hall` 组成的确定性地图，地图尺寸为 80×30 瓦片。
- **玩家实体**（`byog.Core.Player extends Entity`）：支持 WASD 移动，具备碰撞检测（墙体不可通行、仅 `FLOOR` 瓦片可走）。
- **房间图系统**（`byog.Core.RoomGraph`）：基于 Prim 最小生成树算法确保所有房间连通，同时额外添加符合泊松分布的冗余走廊以形成环路。
- **瓦片渲染引擎**（`byog.TileEngine.TERenderer` / `TETile` / `Tileset`）：基于 Princeton StdDraw 的图形渲染，每瓦片 16px。
- **状态机架构**（`byog.Core.Game`）：MENU → SEED_INPUT → PLAYING → QUIT_PENDING → QUIT，支持键盘交互和字符串输入自动评分两种模式。
- **存档系统**（`byog.Core.SaveLoadManager` / `GameSaveData`）：基于种子的确定性存档，通过 `GameSaveData.extraData`（`Map<String, Serializable>`）预留了扩展点。
- **抽象实体基类**（`byog.Core.Entity`）：定义了 `Position` 和 `TETile` 属性，为敌人实体的添加提供了架构基础。

**目前尚不存在**：敌人实体、寻路算法（BFS/A*）、AI 行为系统、感知系统、战斗系统。

### 目标

设计一个能够在程序化地牢中自适应的敌人 AI 代理。

该敌人应该能够：

- **感知**游戏世界（将 `TETile[][]` 原始数据转化为可理解的描述）
- **推理**玩家行为（基于玩家历史轨迹预测意图）
- **生成**策略性计划（设定目标、选择行动序列）
- **可靠执行**动作（通过现有的 Entity 移动框架落地）
- **基于反馈自适应**（记忆玩家模式，调整策略）

系统组合以下技术：

- 大语言模型（LLM）高层推理
- 传统游戏算法（BFS/A* 寻路、状态机）
- 异步架构（避免 API 调用阻塞游戏主循环）
- 记忆系统（持久化玩家行为模式）

------------------------------------------------------------------------

## 2. 高层架构

### 2.1 两大引擎分离

系统在架构层面被划分为两个独立引擎，通过明确定义的契约进行通信：

```
┌─── Game Engine (Java) ────────────────────────────┐
│                                                    │
│  Game.java (60 FPS 主循环)                          │
│       │                                            │
│       v                                            │
│  Enemy Controller (Enemy extends Entity)            │
│       │                                            │
│  ┌────┴──────────────────────────────┐             │
│  │ ActionQueue  │ Action Executor    │             │
│  │ (线程安全)    │ (逐帧执行+碰撞检测)  │             │
│  └───────────────────────────────────┘             │
│       │                          ▲                 │
│       │    Game State API        │                 │
│       ▼                          │                 │
│  GameStateSnapshot ──────────────┘                 │
│  (不可变快照, 线程边界)                              │
│                                                    │
└────────────────────┬───────────────────────────────┘
                     │ Game State API Contract
                     │ (JSON-serializable DTOs)
                     ▼
┌─── Decision Engine (可插拔) ───────────────────────┐
│                                                    │
│  Perception Layer → PromptBuilder → LLM API        │
│       │                               │            │
│       v                               v            │
│  WorldDescription             StrategicIntent      │
│  (局部地图+房间拓扑)           {goal,target,strategy} │
│                                      │             │
│                                      v             │
│                               ClassicalPlanner     │
│                               (BFS/A* 路径→Actions)│
│                                      │             │
│                                      v             │
│                               Action List          │
│                               [Move↑,Wait,...]     │
│                                      │             │
│  ┌───────────────────────────────────┘             │
│  │  EnemyMemory (可序列化, 支持持久化)               │
│  └───────────────────────────────────┘             │
│                                                    │
└────────────────────────────────────────────────────┘
```

**为何要这样分层？**

Game Engine 和 Decision Engine 之间的边界是整个架构最重要的设计决策。这个边界的意义在于：

| 维度 | Game Engine（Java） | Decision Engine（可插拔） |
|------|-------------------|------------------------|
| **职责** | 世界模拟、渲染、碰撞、规则执行 | 感知、推理、规划、记忆 |
| **性能要求** | 每帧 16ms，必须实时 | 秒级延迟可接受 |
| **可替换性** | 固定（BYOG 框架） | 可替换为 Python Agent Runtime |
| **状态访问** | 直接读写游戏对象 | 只通过 GameStateSnapshot 读取 |

这个分层直接为后期的 Agentic 架构（Python LangGraph、Multi-Agent、Vector Memory）做好了准备——Decision Engine 可以被整体替换为远程 Agent Runtime，而不改变 Game Engine 的任何代码。

### 2.2 阶段一架构（当前设计：Decision Engine 内嵌于 Java）

当前阶段，Decision Engine 以 Java 类的形式直接运行在 JVM 中。`EnemyBrain` 接口是 Decision Engine 和 Game Engine 之间的契约：

```
Game Engine                    Decision Engine (Java)
    │                                │
    │  EnemyBrain.think(snapshot)    │
    │ ─────────────────────────────> │
    │                                │ → PromptBuilder.build()
    │                                │ → LLMClient.send()
    │                                │ → ClassicalPlanner.translate()
    │  StrategicIntent                │
    │ <───────────────────────────── │
    │                                │
    │  (内部调用 Planner → Queue)     │
```

### 2.3 阶段二架构（未来：Decision Engine 独立为 Agent Runtime）

后期可将 Decision Engine 迁移到独立的 Python 进程，通过 HTTP/WebSocket 与 Game Engine 通信：

```
Game Engine (Java)              Agent Runtime (Python)
    │                                │
    │  POST /api/state (JSON)        │
    │ ─────────────────────────────> │ → LangGraph workflow
    │                                │     Perception Node
    │                                │     Planning Node
    │                                │     Memory Retrieval
    │                                │     Tool Calling
    │  {goal, target, strategy, actions}
    │ <───────────────────────────── │
    │                                │
    │  (ClassicalPlanner 仍在 Java)   │
```

关键：`EnemyBrain` 接口只需新增一个 `RemoteBrain` 实现（通过 HTTP 调用 Python Agent），Game Engine 的其他代码完全不变。这是"为 agentic 做准备"的核心设计意图。

### 2.4 关键设计决策

1. 敌人通过 `Entity` 基类融入现有架构。`Game.java` 中的 `List<Entity> entities` 列表已经设计为可容纳多个实体。

2. **LLM 只输出战略意图（StrategicIntent），不输出具体移动动作。** LLM 说"我要去房间5拦截玩家"；`ClassicalPlanner` 负责"用 BFS 找到最短路径并生成逐格动作"。这保证了——Decision Engine 可以被替换时，Planner 仍留在 Game Engine 侧，因为它需要直接访问 `TETile[][]` 进行寻路。

3. **Decision Engine 通过 GameStateSnapshot 感知世界，通过 StrategicIntent 表达意图。** 这两个对象构成了 Game Engine 和 Decision Engine 之间的完整 API 契约。它们的设计必须是纯数据、可序列化的——为未来跨进程通信做准备。

------------------------------------------------------------------------

## 3. 设计原则

### 3.1 智能与执行分离

**LLM 负责（高层智能 / Strategic Reasoning）：**

- 理解当前处境："玩家正在向东逃跑，他可能在寻找出口"
- 预测未来事件："基于过去两次遭遇，玩家可能会在血量低时主动攻击"
- 选择目标与策略：拦截玩家 / 守卫宝藏 / 巡逻 / 伏击
- 输出战略意图：目标房间 + 策略 + 推理（不包含具体路径或移动方向）

**Classical Planner 负责（战术执行 / Tactical Execution）：**

- 将战略意图转化为具体动作序列
- BFS/A* 寻路：计算从当前位置到目标位置的最短路径
- 生成 `MoveAction` 序列（沿路径逐步移动）
- 添加辅助动作（如到达后 `WaitAction` 观察）

**游戏引擎负责（底层执行 / Mechanical Execution）：**

- 逐瓦片的移动与碰撞检测（复用 `Player.canMoveTo()` 逻辑）
- 动作合法性校验："这个瓦片能走吗？这个位置在地图内吗？"
- 渲染（`TERenderer.renderFrame()`）
- 游戏状态更新（`Game.update()`）

**LLM 永远不应输出具体移动方向或坐标序列。** LLM 的唯一产出是 `StrategicIntent`——目标 + 策略 + 推理。路径规划（"怎么走"）完全由 `ClassicalPlanner` 通过 BFS/A* 完成。这保证了架构的一致性：如果 LLM 真的"不负责怎么走"，那它就应该连 "MOVE_NORTH" 都不输出。

### 3.2 混合 AI 架构

系统结合两种 AI 范式：

#### LLM（大语言模型）— 处理高层智能问题

> 我为什么要这样做？
> 我的目标是什么？
> 我应该使用什么策略？
> 玩家可能想做什么？

LLM 的优势在于模式识别、常识推理和自然语言理解——这些正是传统算法难以处理的。例如，LLM 可以推断"玩家反复向东跑 → 可能东边有重要目标"，而无需显式编码这种规则。

#### Classical Planner（传统规划器）— 处理战术层问题

> 我如何到达目标房间？（BFS 最短路径）
> 到达后应该做什么？（根据策略生成守卫/等待动作）
> 路径是否仍然可行？（实时中断检测）

这是 LLM 输出和最终动作之间的关键桥梁。LLM 表达意图，Planner 落地执行。在现有代码库中：

- `RoomGraph` + `MatrixGraph<T>`：已经实现了 Prim 算法和距离计算
- `Room.distanceTo(Room)`：已实现欧几里得距离
- `Player.canMoveTo(Position, TETile[][])`：现有的碰撞检测可复用
- 需要新增：BFS/A* 寻路器、`ClassicalPlanner`、视野范围计算（Line-of-Sight）

### 3.3 面向 Agentic 架构的可扩展性设计

本节阐述当前设计如何为后期的 Agentic 架构（Python LangGraph、Multi-Agent、Tool Calling、Vector Memory）做好准备。核心思想是：**当前阶段不需要实现 Agent Runtime，但架构中的接口和边界应该为它预留空间。**

#### 3.3.1 清晰的引擎边界 = Agent 化的前提

当前架构已经天然地将系统分为两个引擎（见第 2 节），这个边界恰好对应 Agentic 架构中的"环境（Environment）"和"智能体（Agent）"：

| 当前概念 | Agentic 对应概念 | 说明 |
|---------|------------------|------|
| Game Engine | Environment | 世界模拟器，提供状态和接受动作 |
| Decision Engine | Agent Runtime | 智能体运行时，感知→推理→规划 |
| `GameStateSnapshot` | State / Observation | 智能体对环境的观察 |
| `StrategicIntent` | Agent Goal Output | 智能体的决策输出 |
| `Action` | Action / Tool Call | 智能体可执行的操作 |
| `EnemyBrain` 接口 | Agent Interface | 环境与智能体之间的协议 |
| `EnemyMemory` | Memory / Retrieval | 智能体的长期记忆 |

#### 3.3.2 接口设计的 Agentic 友好性

当前设计在整个数据流中使用了纯数据对象（POJO / DTO），且都是可序列化的：

```
Game Engine                          Decision Engine
    │                                      │
    │  GameStateSnapshot (纯数据)           │
    │ ──────────────────────────────────> │
    │                                      │
    │  StrategicIntent (纯数据)             │
    │ <────────────────────────────────── │
    │                                      │
    │  (ClassicalPlanner 将 Intent          │
    │   转化为 List<Action>)                │
```

这三个对象——`GameStateSnapshot`、`StrategicIntent`、`Action`——共同构成了 Game Engine 和 Decision Engine 之间的 **Game State API 契约**。因为它们都是纯数据、可序列化为 JSON 的，所以后期将它们通过 HTTP/WebSocket 传输到外部 Python 进程几乎不需要修改数据结构。

#### 3.3.3 Action 系统的 Tool Calling 对齐

当前的 `Action` 接口设计可以自然地映射到 LLM Tool Calling 模式：

```java
// 当前设计：每种 Action 是一个类
MoveAction{ direction: "north" }
WaitAction{ turns: 3 }
GuardAction{ center: (10,20), radius: 5 }

// 未来 Agentic 映射：每种 Action 是一个 Tool Definition
// {
//   "name": "move",
//   "description": "向指定方向移动一格",
//   "parameters": { "direction": "north|south|east|west" }
// }
```

在 Tool Calling 模式下，LLM 不再仅输出战略意图的 JSON，而是可以直接调用这些 Tool。`ClassicalPlanner` 变为 Tool 的实现者，`StrategicIntent` 变为 Tool 选择的推理结果。接口不需要变化——只有调用方式从"单轮 LLM 调用"变为"多轮 Tool Calling 循环"。

#### 3.3.4 Memory 系统的检索式扩展

当前 `EnemyMemory` 以 Java 集合存储事件和模式，但它的接口设计支持后期的检索式升级：

```java
// 当前接口（阶段一）
String generateSummary();  // 返回格式化的文本摘要

// 未来接口（阶段二 — 只需新增方法，不动现有代码）
List<MemoryEvent> retrieveRelevant(String query);  // 语义检索
// 内部可以用 embedding + vector DB 实现
```

`EnemyMemory` 的 `generateSummary()` 返回的是文本，正好可以作为 Prompt 的一部分注入。后期如果替换为 vector-based retrieval，只需新增 `retrieveRelevant(query)` 方法，`PromptBuilder` 调用它而非固定摘要。现有代码不需要任何修改。

#### 3.3.5 演进路径：从单体 Java 到 Agent Runtime

整个架构的演进路径是渐进式的，每一步都不需要重构核心逻辑：

```
阶段一（当前）：
   Decision Engine 内嵌于 Java
   EnemyBrain → LLMBrain (本地 HTTP 调用 OpenAI)

阶段二（中期）：
   Decision Engine 独立为 Python 进程
   EnemyBrain → RemoteBrain (HTTP 调用 Python Agent)
   Python 中实现：LangGraph workflow + Perceive-Plan-Act 循环

阶段三（远期）：
   Multi-Agent 编排
   Scout Agent + Strategy Agent + Execution Agent
   Vector Memory (Chroma/FAISS)
   Tool Calling 模式
   MCP (Model Context Protocol) — Game Engine 作为 MCP Server
```

**关键的架构保证**：从阶段一切换到阶段二，Java 侧只需要新增一个 `RemoteBrain implements EnemyBrain`。`Enemy`、`ActionQueue`、`ClassicalPlanner`、`GameStateSnapshot` 等核心类完全不需要修改。"为 agentic 做准备"就是保证这个切换成本趋近于零。

------------------------------------------------------------------------

## 4. 核心类设计

### 4.1 Entity（已存在）

`byog.Core.Entity` 是已有的抽象基类，位于 `d:\Courses\cs61b\cs61b-std\proj2\byog\Core\Entity.java`。

```java
public abstract class Entity {
    protected Position position;  // 使用 byog.lab5.Position (int x, int y)
    protected TETile tile;        // 敌人可定义自己的瓦片外观

    public abstract void update(GameStateSnapshot state);

    public Position getPosition() { return position; }
    public void setPosition(Position p) { this.position = p; }
    public TETile getTile() { return tile; }
    public void setTile(TETile t) { this.tile = t; }
}
```

### 4.2 Enemy（需新建）

继承 `Entity`，是主要的 AI 控制实体。

```java
public class Enemy extends Entity {
    private EnemyBrain brain;          // AI 大脑接口
    private ActionQueue actionQueue;   // 行动队列（线程安全，支持异步补货）
    private EnemyMemory memory;        // 持久化记忆
    private ClassicalPlanner planner;  // 战术规划器（BFS寻路 + 动作生成）
    private int hp;                    // 生命值

    public Enemy(Position startPos, EnemyBrain brain, int hp) {
        this.position = startPos;
        this.tile = new TETile('E', Color.RED, Color.BLACK, "Enemy");
        this.brain = brain;
        this.actionQueue = new ActionQueue();
        this.memory = new EnemyMemory();
        this.planner = new ClassicalPlanner();
        this.hp = hp;
    }

    @Override
    public void update(GameStateSnapshot state) {
        // 1. 检查行动队列是否需要补货
        if (actionQueue.needRefill()) {
            // 异步请求 LLM 生成战略意图（不阻塞游戏主循环）
            brain.requestStrategicIntentAsync(state, this);
        }

        // 2. 取出并执行下一个动作
        Action action = actionQueue.poll();
        if (action != null) {
            action.execute(state, this);
        }

        // 3. 记录本回合的观察（用于记忆系统）
        memory.recordObservation(state, this);
    }
}
```

**职责：**
- 维护自身位置、血量、瓦片外观
- 每帧从 `ActionQueue` 中消费并执行动作
- 与 `EnemyBrain` 通信，触发异步战略规划
- 将回合观察写入 `EnemyMemory`

**与 Game.java 的集成点：**
- `Game.update()` 遍历 `entities` 列表时调用 `enemy.update(gameState)`
- `Game.renderFrame()` 遍历 `entities` 列表渲染 `enemy.getTile()` 到正确位置
- `GameSaveData.extraData` 可存储所有敌人的位置和血量

### 4.3 EnemyBrain 接口（需新建）

定义 AI 大脑的抽象接口。**关键改变**：返回类型从 `Plan`（含动作列表）改为 `StrategicIntent`（纯战略意图，无动作）。

```java
public interface EnemyBrain {
    /**
     * 同步获取战略意图 —— 用于规则 AI，在主线程中直接返回
     * @param state 当前游戏状态快照
     * @return 战略意图（goal + target + strategy）
     */
    StrategicIntent think(GameStateSnapshot state);

    /**
     * 异步请求战略意图 —— 用于 LLM AI，在后台线程中调用 API
     * 完成后回调：Planner 将 StrategicIntent 转化为 Actions 并写入 ActionQueue
     * @param state 当前游戏状态快照
     * @param enemy 调用者敌人
     */
    void requestStrategicIntentAsync(GameStateSnapshot state, Enemy enemy);
}
```

**实现类层次：**

```
        EnemyBrain (接口)
             |
    -------------------------
    |           |           |
RuleBasedBrain LLMBrain  RemoteBrain  ← 新增：阶段二 Agentic
(确定性规则)   (大语言模型) (HTTP→Python Agent)
```

**RuleBasedBrain（保底方案）：**
- 不使用任何 API，纯本地计算
- 直接构造 `StrategicIntent`：如 `{goal: CHASE, target: playerPosition}`
- 交由 `ClassicalPlanner` 转化为具体动作
- 当 LLM 不可用时自动切换

**LLMBrain（核心实现）：**
- 调用远程 LLM API（如 OpenAI / Claude）
- 将 `GameStateSnapshot` 转化为结构化 prompt（由 `PromptBuilder` 负责）
- 解析 LLM 返回的 JSON，提取 `StrategicIntent`
- 交由 `ClassicalPlanner` 转化为具体动作

**RemoteBrain（预留，阶段二启用）：**
- 不直接调用 LLM API，而是通过 HTTP/WebSocket 将 `GameStateSnapshot` 发送到外部 Python Agent Runtime
- Python 侧运行 LangGraph 等 Agent 框架，完成 Perception → Planning → Action 循环
- 接收 Python Agent 返回的 `StrategicIntent`（或直接返回 `List<Action>`）
- `ClassicalPlanner` 仍在 Java 侧执行（因为 BFS 需要访问 `TETile[][]`）
- 当 Agent Runtime 不可用时，自动降级为 `LLMBrain` 或 `RuleBasedBrain`

**这种设计的好处：**
- 易于单元测试：可以 mock `EnemyBrain`，注入预设意图
- 离线保底：LLM 不可用时自动降级为 `RuleBasedBrain`
- 模型可替换：只需新增 `EnemyBrain` 实现，不影响其他代码
- 分工纯净：LLM 输出意图，Planner 落地执行，各司其职
- **Agentic 就绪**：`RemoteBrain` 是切换 Python Agent Runtime 的唯一插入点，Game Engine 零改动

### 4.4 ClassicalPlanner（需新建）

这是架构中**最关键的新增组件**。它接收 LLM 的 `StrategicIntent`，通过 BFS/A* 寻路生成具体的 `Action` 序列。

```java
public class ClassicalPlanner {
    private final BFSPathfinder pathfinder;  // BFS 寻路器

    /**
     * 将战略意图转化为可执行的动作序列
     * @param intent  LLM 输出的战略意图
     * @param state   当前游戏状态快照
     * @param enemy   执行者
     * @return 具体动作列表（交由 ActionQueue 消费）
     */
    public List<Action> translate(StrategicIntent intent,
                                   GameStateSnapshot state,
                                   Enemy enemy) {

        List<Action> actions = new ArrayList<>();

        // 1. BFS 寻路到目标位置
        Position start = enemy.getPosition();
        Position target = intent.getTargetPosition();
        List<Position> path = pathfinder.findPath(start, target, state.getWorld());

        // 2. 将路径转化为 MoveAction 序列
        for (int i = 1; i < path.size(); i++) {
            Position prev = path.get(i - 1);
            Position curr = path.get(i);
            Direction dir = Direction.fromDelta(curr.x - prev.x, curr.y - prev.y);
            actions.add(new MoveAction(dir));
        }

        // 3. 根据策略添加到达后的行为
        switch (intent.getStrategy()) {
            case AMBUSH:
                actions.add(new WaitAction(3));  // 等待伏击
                break;
            case PATROL:
                actions.addAll(generatePatrolActions(target, state));
                break;
            case CHASE:
                // 到达目标位置后，如果没有玩家，触发重规划
                break;
            default:
                actions.add(new WaitAction(1));  // 到达后观察一回合
        }

        return actions;
    }
}
```

**ClassicalPlanner 的职责边界：**
- **负责**：路径计算、动作生成、到达后行为编排
- **不负责**：目标选择（那是 LLM 的事）、高层推理（那是 LLM 的事）

------------------------------------------------------------------------

## 5. 感知层

### 5.1 目的

将原始游戏状态转换为 LLM 可理解的结构化文本描述。

**输入（原始游戏数据）：**

| 数据 | 来源 | 类型 |
|------|------|------|
| 世界瓦片地图 | `Game.world` | `TETile[80][30]` |
| 玩家对象 | `Game.player` | `Player extends Entity` |
| 玩家历史位置 | `EnemyMemory.playerTrail` | `List<Position>` |
| 房间图 | `WorldGenerator` 阶段生成 | `RoomGraph` |
| 敌人自身状态 | `Enemy` 对象 | 位置、血量、当前目标 |
| 遭遇历史 | `EnemyMemory.encounters` | `List<Event>` |

**输出（LLM 可读的世界描述）：**

一个 `WorldDescription` 对象，包含：
- 以敌人为中心的 **局部地图**（文本网格表示）
- **战略层面摘要**（房间连通关系、玩家最后出现位置）
- **历史事件摘要**（最近 N 次遭遇的关键信息）

### 5.2 局部地图表示

感知层以敌人坐标为中心，从 `TETile[][]` 中提取一个矩形窗口（如 15×15），将瓦片转为符号字符。

**瓦片符号映射（基于现有 `Tileset`）：**

| TETile 常量 | 字符 | 含义 |
|-------------|------|------|
| `Tileset.WALL` | `#` | 墙壁 |
| `Tileset.FLOOR` | `.` | 地面 |
| `Tileset.PLAYER` | `P` | 玩家（如果在视野内） |
| `Tileset.NOTHING` | ` ` | 虚空/地图外 |
| Enemy 瓦片 | `E` | 敌人自身 |

**示例输出（15×15 窗口，敌人为中心）：**

```
    ###########
    #.........#
    #..P......#
    #.........#
    #....E....#
    #.........#
    #######.###
```

**实现要点：**
- 窗口大小可配置（建议 11×11 或 15×15，平衡信息量与 token 消耗）
- 如果玩家不在窗口范围内，用 `?` 表示"玩家在视野外"
- 地图边界以外的瓦片统一表示为空格
- 注意坐标转换：`TETile[x][y]` 的 (0,0) 在左下角，需要正确映射到文本的行列

### 5.3 战略层面世界表示

除局部地图外，还需要给 LLM 一个"鸟瞰"视角。这不需要完整地图，而是要传递结构化的拓扑信息。

**包含内容：**

```
地牢结构图（房间图）:

    房间0 ---- 房间1 ---- 房间2
      |          |
    房间3 ---- 房间4
      |
    房间5

敌人当前位置: 房间4
玩家最后出现: 房间2 (5回合前向东移动)
宝藏守卫点:   房间1
```

**关键信息：**
- 房间总数和连通关系（由 `RoomGraph` 的边推导）
- 敌人在哪个房间（通过检查当前位置是否在某个 `Room` 的包围盒内）
- 玩家最近出现位置和移动趋势
- 特殊位置（宝藏、出口等）

这种表示方式使得 LLM 可以进行战略推理，例如："玩家在房间2，我在房间4，我可以经过房间1去拦截，或者直接去房间2东边的走廊堵他。"

------------------------------------------------------------------------

## 6. PromptBuilder —— Prompt 构建器

`PromptBuilder` 是 LLM 交互质量的核心决定因素。它将感知层的输出和记忆系统的摘要组装为结构化的 LLM Prompt。本节将其独立展开。

### 6.1 设计职责

`PromptBuilder` 的职责是将游戏状态的不同维度**分层组装**成最终 Prompt。它的输入和输出都非常明确：

```
输入:
├── WorldDescription (局部地图 + 房间拓扑)
├── EnemyMemory (玩家模式摘要 + 最近事件)
└── Constraints (JSON Schema + 输出格式要求)

输出:
├── System Prompt (角色定义, 一次设定, 可缓存)
└── User Message   (动态部分, 每回合变化)
```

### 6.2 Prompt 组装结构

```
PromptBuilder.build(state, memory):

┌─ System Prompt ─────────────────────────────┐
│ 1. Role Definition                          │
│    "你是一个地牢守护者 AI。"                  │
│                                             │
│ 2. Capability Description                   │
│    - 你能做什么                              │
│    - 你的限制是什么                          │
│                                             │
│ 3. Output Format (JSON Schema)              │
│    - 必须输出的字段                          │
│    - 字段类型和约束                          │
└─────────────────────────────────────────────┘
                    +
┌─ User Message ──────────────────────────────┐
│ 4. World State (动态)                       │
│    - 局部地图文本                            │
│    - 房间拓扑摘要                            │
│                                             │
│ 5. Memory Context (动态)                    │
│    - 学到的玩家模式                          │
│    - 最近遭遇事件                            │
│                                             │
│ 6. Current Status (动态)                    │
│    - 敌人HP、当前位置                        │
│    - 上一轮目标状态                          │
└─────────────────────────────────────────────┘
```

### 6.3 为什么 PromptBuilder 值得独立一章

很多 LLM 项目的效果差异根源于 Prompt 设计，而非模型本身。对于 DungeonMind 而言：

- **信息密度控制**：局部地图太大浪费 token，太小信息不足。`PromptBuilder` 封装了这个权衡。
- **记忆注入策略**：并不是所有记忆都应该塞进 Prompt——只注入与当前局势最相关的模式，这需要过滤逻辑。
- **Schema 一致性**：确保每轮 Prompt 中的 JSON Schema 约束一致，减少解析失败。
- **可调优性**：可以 A/B 测试不同 Prompt 模板，而不改动其他代码。

### 6.4 完整 Prompt 示例

```
[System]
你是一个地牢守护者 AI。你的职责是保护地牢中的宝藏，阻止玩家到达目标。

你的能力：
- 你了解地牢的拓扑结构（房间及其连通关系）
- 你可以移动到任意可达的房间
- 你可以选择不同的策略：拦截、伏击、巡逻、守卫

你的限制：
- 你只能看到局部地图范围内的东西（以你为中心的 7 格半径）
- 你不需要规划具体路径——路径由游戏引擎自动计算
- 你的决策必须基于实际观察，不能"开图"

输出格式要求：
你必须以 JSON 格式输出，字段如下：
- goal: 目标类型（intercept_player | guard_treasure | patrol | ambush | chase | retreat）
- goal_reasoning: 你的推理过程（1-2句话）
- target_room: 目标房间编号（可选，如果你有明确的房间目标）
- target_position: {"x": int, "y": int} 或 null
- strategy: 策略类型（intercept | ambush | patrol | guard | chase）
- confidence: 0.0~1.0 之间的数值，反映你对该决策的确定性
不要输出 JSON 以外的任何内容。

[User]
=== 当前回合状态 ===

局部地图（E=你, P=玩家, #=墙, .=地面）:
    ##########
    #........#
    #..P.....#
    #........#
    #....E...#
    ##########

地牢拓扑信息：
- 房间总数: 8
- 你当前在房间: 3
- 玩家最后出现在房间: 5
- 从房间3到房间5的路径: 3 -> 2 -> 5 (经过2个走廊)

记忆摘要：
- 玩家在最近10回合中70%的移动方向是东
- 上次在房间5被目击后，玩家向东移动消失

最近观察历史：
- 回合10: 玩家在房间5，向东移动
- 回合9:  玩家在房间5，向东移动
- 回合8:  玩家在房间4，向北移动

敌人状态：
- HP: 80/100
- 当前位置: 房间3

请基于以上信息，输出你的战略意图。
```

### 6.5 LLM 输出格式（StrategicIntent）

**关键改变**：LLM 不再输出 `actions` 数组。它只输出战略意图——去哪里、用什么策略、为什么。具体路径由 `ClassicalPlanner` 生成。

```json
{
  "goal": "intercept_player",
  "goal_reasoning": "玩家连续向东移动，可能在寻找东侧出口。我应该在房间5的走廊入口拦截。",
  "target_room": 5,
  "target_position": null,
  "strategy": "intercept",
  "confidence": 0.85
}
```

**目标类型（goal）：**

| 值 | 含义 | 典型触发条件 |
|----|------|-------------|
| `intercept_player` | 拦截玩家 | 已知玩家位置，可以预测其路径 |
| `guard_treasure` | 守卫宝藏 | 玩家靠近宝藏，或拦截失败后退守 |
| `patrol` | 巡逻 | 不知道玩家位置，进行区域搜索 |
| `ambush` | 伏击 | 预测玩家会经过某个狭窄通道 |
| `chase` | 追击 | 玩家在视线内且正在逃跑 |
| `retreat` | 撤退 | 敌人血量低，需要恢复 |

**策略类型（strategy）** 影响 `ClassicalPlanner` 的到达后行为：

| 值 | Planner 的到达后行为 |
|----|---------------------|
| `intercept` | 到达目标后 `WaitAction(2)`，观察玩家是否出现 |
| `ambush` | 到达目标后 `WaitAction(3)` 并停止移动，模拟伏击 |
| `patrol` | 在目标房间内生成巡逻路径 |
| `guard` | 在目标位置周围小范围巡逻 |
| `chase` | 到达目标后如果玩家不在，立即触发重规划 |

### 6.6 API 调用流程

```
1. 感知层生成 WorldDescription
2. PromptBuilder 组装最终 Prompt:
   build(worldDescription, memorySummary, jsonSchema)
3. LLMClient.sendRequest(prompt)  → 异步 HTTP 调用
4. 收到响应 → JSON Parser 尝试解析为 StrategicIntent
5. 解析成功 → IntentValidator 校验合法性
   - JSON Schema 校验：字段齐全、类型正确
   - 目标合法性校验：target_room 是否存在、target_position 是否可达
   - 信心值范围校验：0.0 ~ 1.0
6. 校验通过 → ClassicalPlanner.translate(intent) → 生成 Action 序列
7. Action 序列 → 写入 ActionQueue（线程安全）
8. 校验失败 / API 超时 → 降级为 RuleBasedBrain
```

------------------------------------------------------------------------

## 7. StrategicIntent 与 Plan 系统

### 7.1 为什么不直接让 LLM 输出 WASD？

**错误做法：**
```
LLM → 直接输出 "W" / "A" / "S" / "D"
```

**问题：**
- LLM 不了解精确的地图几何，可能输出无效移动（撞墙）
- 没有碰撞感知，可能走入不可通行区域
- 缺乏长期规划能力——每回合都让 LLM 决策既不经济也不稳定
- token 消耗巨大：每回合都需要完整 prompt，成本不可控

### 7.2 为什么 LLM 也不应该输出 action 序列？

一个更深层的问题：即使 LLM 输出的是结构化 action（如 `{type: "move", direction: "north"}`），仍然存在矛盾。

```
如果设计原则是 "LLM 不负责怎么走"，
那为什么 LLM 还要输出 move north / move east / wait？
```

`move north` 本质上就是"怎么走"的一部分。真正的分离应该是：

```
LLM 回答：  去哪里？用什么策略？为什么？  →  StrategicIntent
Planner 回答：怎么走？需要几步？到达后做什么？ →  List<Action>
```

这确保了：
- LLM 决策的语义层级正确（永远在"目标/策略"层面）
- 任何路径相关的问题都由 BFS/A* 保证正确性
- 架构的一致性：分工边界清晰，不存在灰色地带

### 7.3 StrategicIntent 对象

```java
public class StrategicIntent {
    public enum Goal {
        INTERCEPT_PLAYER, GUARD_TREASURE, PATROL, AMBUSH, CHASE, RETREAT
    }

    public enum Strategy {
        INTERCEPT, AMBUSH, PATROL, GUARD, CHASE
    }

    private Goal goal;                  // 高层目标
    private String goalReasoning;       // LLM 的目标推理（用于调试和记忆）
    private int targetRoom;             // 目标房间编号（-1 表示未指定）
    private Position targetPosition;    // 目标坐标（可为 null，由 Planner 根据 targetRoom 计算）
    private Strategy strategy;          // 执行策略（影响 Planner 的到达后行为）
    private double confidence;          // LLM 自评信心，作为启发式控制参数
    private long timestamp;             // 意图生成时间戳
}
```

`StrategicIntent` 是纯战略层面的数据对象。它不包含任何路径或动作信息。转化为可执行动作是 `ClassicalPlanner` 的职责。

### 7.4 完整数据流

```
LLM Output (StrategicIntent)
{goal: INTERCEPT, targetRoom: 5, strategy: AMBUSH, confidence: 0.85}
     |
     v
ClassicalPlanner.translate(intent, state, enemy)
     |
     ├── 1. 确定目标坐标
     │      targetRoom=5 → 查 RoomGraph → 取房间5的中心或入口位置
     │
     ├── 2. BFS 寻路
     │      start=enemy.position → target=房间5入口
     │      路径: [(10,8), (10,9), (11,9), (12,9), ...]
     │
     ├── 3. 路径 → MoveAction 序列
     │      MoveAction↑, MoveAction↑, MoveAction→, MoveAction→, ...
     │
     └── 4. 策略 → 到达后动作
            strategy=AMBUSH → WaitAction(3)
     |
     v
ActionQueue
[Move↑, Move↑, Move→, Move→, Move→, Wait, Wait, Wait]
```

------------------------------------------------------------------------

## 8. 动作系统

### 8.1 Action 接口

```java
public interface Action {
    /**
     * 执行动作
     * @param state 当前游戏状态快照（只读引用，不应修改）
     * @param enemy 执行该动作的敌人
     * @return 执行结果：成功 / 失败 / 被中断
     */
    ActionResult execute(GameStateSnapshot state, Enemy enemy);
}

public enum ActionResult {
    SUCCESS,        // 动作成功执行
    BLOCKED,        // 被阻挡（墙壁、其他实体）
    INTERRUPTED,    // 被中断（需要重新规划）
    COMPLETED       // 目标达成（如到达目的地）
}
```

**具体 Action 实现：**

| 类 | 描述 | 生成者 | Tool Calling 映射 |
|----|------|--------|-------------------|
| `MoveAction` | 向指定方向移动一格 | ClassicalPlanner | `move(direction)` |
| `WaitAction` | 原地等待 N 回合 | ClassicalPlanner | `wait(turns)` |
| `AttackAction` | 攻击相邻目标 | ClassicalPlanner | `attack(target_position)` |
| `GuardAction` | 在小范围内巡逻 | ClassicalPlanner | `guard(center, radius)` |

**所有 Action 都由 ClassicalPlanner 生成**，LLM 永远不会直接创建 Action 对象。这保证了分工纯净。

### 8.1.1 Action 的 Tool Calling 兼容性

每个 `Action` 子类天然具备 Tool Definition 所需的三要素：名称、描述、参数。这为后期 Agentic Tool Calling 模式做好准备：

```java
// 当前：Action 是 Java 类
public class MoveAction implements Action {
    private Direction direction;   // 参数
    // getName() → "move"
    // getDescription() → "向指定方向移动一格"
}

// 未来：同样的语义可以序列化为 LLM Tool Definition
// {
//   "type": "function",
//   "function": {
//     "name": "move",
//     "description": "向指定方向移动一格。方向必须是 north/south/east/west 之一。",
//     "parameters": {
//       "type": "object",
//       "properties": {
//         "direction": {"type": "string", "enum": ["north","south","east","west"]}
//       },
//       "required": ["direction"]
//     }
//   }
// }
```

在 Tool Calling 模式下：
- LLM 不再仅输出 JSON StrategicIntent，而是可以在推理过程中**主动调用 Tool** 获取更多信息
- 例如 LLM 先调用 `inspect_room(5)` 查看房间5的详情，再决定是否去拦截
- `ClassicalPlanner` 仍然是 BFS 寻路和动作生成的实际执行者，但它暴露为 Tool 让 LLM 调用
- 这种模式使得 LLM Agent 的行为更加灵活——它可以"边看边想，边想边做"

### 8.2 ActionQueue（行动队列）

敌人内部维护一个未来动作的队列，实现"滚动预测"（见第 10 节）。

**内部结构：**

```
Queue: [MoveAction↑, MoveAction↑, WaitAction, MoveAction→, AttackAction]
         ↑ 队首（下次执行）
```

**每回合执行流程：**

```java
// Enemy.update() 中调用
Action action = actionQueue.poll();   // 取出队首
if (action != null) {
    ActionResult result = action.execute(state, this);
    if (result == ActionResult.INTERRUPTED) {
        actionQueue.clear();                 // 情况变化，清空队列
        brain.requestStrategicIntentAsync(state, this);  // 立即请求新意图
    }
}
```

**队列补货策略：**

- 当队列长度 ≤ `REFILL_THRESHOLD`（建议值：2）时触发 `needRefill()`
- 触发后：LLM 生成新的 `StrategicIntent` → `ClassicalPlanner` 转化为新 Action 序列 → 追加到队列末尾
- 除非 `ActionResult.INTERRUPTED` 触发紧急重规划

### 8.3 与现有移动系统的集成

`MoveAction` 的碰撞检测复用现有 `Player` 的移动逻辑：

```java
public class MoveAction implements Action {
    private Direction direction;

    @Override
    public ActionResult execute(GameStateSnapshot state, Enemy enemy) {
        Position target = new Position(
            enemy.getPosition().x + direction.dx,
            enemy.getPosition().y + direction.dy
        );

        // 复用现有的碰撞检测逻辑
        if (Player.canMoveTo(target, state.getWorld())) {
            // 额外检查：目标位置没有被其他实体占据
            if (!state.isOccupiedByEntity(target)) {
                enemy.setPosition(target);
                return ActionResult.SUCCESS;
            }
        }
        return ActionResult.BLOCKED;
    }
}
```

`Player.canMoveTo()` 方法已经检查了边界和瓦片类型（只允许 `FLOOR`），只需额外添加实体间碰撞即可。

------------------------------------------------------------------------

## 9. GameStateSnapshot —— 线程边界 & Game State API 契约

`GameStateSnapshot` 扮演双重角色：
1. **线程边界**：Game Thread 和 AI Thread 之间的唯一数据通道
2. **Game State API 契约**：Game Engine 对外暴露状态的标准化格式——当前用于内部线程间通信，未来可直接用于跨进程 HTTP API

### 9.1 设计动机

在异步架构中，AI Thread 需要读取游戏状态来构建 Prompt。但 `Game.world`、`Player` 等对象在主线程中每帧都在更新。直接传递这些对象的引用会导致竞态条件。解决方案：每次规划请求时，主线程生成一份不可变的快照。

### 9.2 类定义

```java
public class GameStateSnapshot {
    // === 世界状态（只读） ===
    private final TETile[][] world;           // 世界瓦片（深拷贝，避免主线程修改影响）
    private final RoomGraph roomGraph;        // 房间图（只读引用，生成后不变）

    // === 玩家快照 ===
    private final Position playerPosition;    // 玩家位置
    private final int playerHP;               // 玩家血量（预留）
    private final Direction playerLastMove;   // 玩家上一回合的移动方向

    // === 敌人自身快照 ===
    private final Position enemyPosition;     // 敌人自身位置
    private final int enemyHP;                // 敌人自身血量
    private final int currentRoom;            // 敌人当前所在房间编号

    // === 元数据 ===
    private final int turnNumber;             // 当前回合数
    private final long snapshotTimestamp;     // 快照创建时间戳

    // === 记忆摘要（只读） ===
    private final String memorySummary;       // 由 EnemyMemory 生成的文本摘要

    // 构造函数一次性设置所有字段
    // 所有 getter 返回不可变对象或防御性拷贝
    // 无 setter —— 快照是不可变的
}
```

### 9.3 快照与 AI Thread 的交互

```
Game Thread (每 N 帧):
  1. 检测到 actionQueue.needRefill()
  2. 创建 GameStateSnapshot:
     - 深拷贝 TETile[][] visibleRegion (不是整个 world，只传可见区域)
     - 拷贝所有标量字段
     - 调用 memory.generateSummary() 生成文本摘要
  3. 将快照传给 AI Thread:
     brain.requestStrategicIntentAsync(snapshot, enemy)

AI Thread (异步):
  1. 使用 snapshot 构建 Prompt (PromptBuilder)
  2. 调用 LLM API
  3. 解析 StrategicIntent
  4. 调用 ClassicalPlanner.translate(intent, snapshot, enemy)
  5. 将生成的 Actions 写入 ActionQueue
```

注意：AI Thread 永远不会持有 `Game.world` 或 `Enemy` 对象的引用。它只操作快照中的数据。

### 9.4 作为 Game State API 契约

`GameStateSnapshot` 的设计目标不仅是线程安全，更是 **Game Engine 对外暴露状态的标准化 API**。这意味着：

1. **所有字段可序列化为 JSON**：`Position` 可序列化为 `{"x":10,"y":20}`，`Direction` 可序列化为 `"north"` 等。这为后期通过 HTTP 传输到 Python Agent Runtime 做好准备。

2. **字段语义自描述**：每个字段的含义不需要额外的文档——`playerPosition`、`enemyHP`、`currentRoom` 等命名本身即文档。

3. **不可变性保证幂等**：快照一旦创建就不会变化，Agent 可以多次读取而不产生副作用。这是 Agentic 系统中 Observation 的标准要求。

4. **未来 JSON 传输示例**：

```json
{
  "turn_number": 42,
  "timestamp": 1715760000000,
  "visible_map": [
    "#####",
    "#.P.#",
    "#.E.#",
    "#####"
  ],
  "player": {
    "position": {"x": 10, "y": 20},
    "hp": 100,
    "last_move_direction": "east"
  },
  "enemy": {
    "position": {"x": 12, "y": 20},
    "hp": 80,
    "current_room": 3
  },
  "room_graph": {
    "total_rooms": 8,
    "connections": [[0,1], [1,2], [0,3], [3,4], [3,5]]
  },
  "memory_summary": "玩家在最近10回合中70%的移动方向是东"
}
```

这个 JSON 结构可以直接作为 `POST /api/state` 的请求体，发送给 Python Agent Runtime。Game Engine 只需要一个 `GameStateSnapshot.toJson()` 方法即可完成序列化。

------------------------------------------------------------------------

## 10. 滚动视界预测

### 概念

与其让 LLM 一次性规划到"永远"，不如采用滚动视界（Rolling Horizon / Model Predictive Control）方法：

```
观察世界 → LLM 输出战略意图 → Planner 生成 N 步动作 → 执行 M 步 → 重新观察 → 重复
```

### 具体流程

```
回合 0:  LLM → StrategicIntent → Planner → 5 个 Actions → 写入 Queue
回合 1:  执行 Action[0]     → Queue 剩余 4 个
回合 2:  执行 Action[1]     → Queue 剩余 3 个
回合 3:  执行 Action[2]     → Queue 剩余 2 个 → 触发 needRefill()
         LLM → 新 StrategicIntent → Planner → 5 个 Actions → 追加
回合 4:  执行 Action[3]     → Queue 剩余 6 个
回合 5:  执行 Action[4]     → ...
```

注意：LLM 每次生成的是战略意图（"去哪里"），而 Planner 每次生成的是近期动作（"怎么走"）。即使目标不变（如连续多轮都是"拦截玩家"），Planner 也会根据当前最新位置重新计算路径。

### 参数配置建议

| 参数 | 建议值 | 说明 |
|------|--------|------|
| `PLAN_HORIZON` | 由路径长度决定 | 不再是固定值——Planner 根据 BFS 路径长度 + 策略到达后动作确定 |
| `REFILL_THRESHOLD` | 2 | 队列剩余多少动作时触发补货 |
| `INTERRUPT_DISTANCE` | 3 | 玩家位置偏差超过多少格时触发中断重规划 |

------------------------------------------------------------------------

## 11. 异步架构

### 问题

LLM API 调用通常需要 1-5 秒的延迟。如果同步调用：

```
    Game Thread（60 FPS = 每帧 16ms）
    ↓
    更新玩家位置 (0.1ms)
    ↓
    调用 LLM API (2000ms)  ← 游戏冻结 2 秒！
    ↓
    更新敌人位置 (0.1ms)
    ↓
    渲染 (2ms)
```

这会导致游戏每几秒卡顿一次，体验极差。

### 解决方案：双线程模型

```
    Game Thread（主线程，60 FPS）
    ├── 处理玩家输入
    ├── 创建 GameStateSnapshot (如果 needRefill)
    ├── 更新所有实体 (Game.update)
    │   └── Enemy.update: 从 ActionQueue 消费动作，不阻塞
    ├── 渲染 (renderFrame)
    └── 循环 (16ms pause)

    AI Thread（后台线程）
    ├── 收到 GameStateSnapshot
    ├── PromptBuilder 构建 Prompt
    ├── 调用 LLM API（可能耗时数秒）
    ├── 解析 StrategicIntent JSON
    ├── IntentValidator 校验合法性
    ├── ClassicalPlanner.translate() → 生成 Action 序列
    └── 线程安全地写入 ActionQueue（synchronized）
```

### 关键实现要点

**线程安全的 ActionQueue：**

```java
public class ActionQueue {
    private final Queue<Action> queue = new ArrayDeque<>();
    private final Object lock = new Object();

    public void enqueueActions(List<Action> actions) {
        synchronized (lock) {
            queue.addAll(actions);
        }
    }

    public Action poll() {
        synchronized (lock) {
            return queue.poll();
        }
    }

    public int size() {
        synchronized (lock) {
            return queue.size();
        }
    }

    public boolean needRefill() {
        synchronized (lock) {
            return queue.size() <= REFILL_THRESHOLD;
        }
    }

    public void clear() {
        synchronized (lock) {
            queue.clear();
        }
    }
}
```

**AI 线程生命周期管理：**
- 使用 `ExecutorService`（单线程池）管理 AI 线程
- `Game.start()` 时创建线程池，`Game.quit()` 时调用 `shutdownNow()`
- 每个敌人可以共享一个线程池，或各自拥有独立线程（取决于敌人数量）

------------------------------------------------------------------------

## 12. 重规划与中断机制

### 问题：预测失效

计划是基于"玩家会怎么做"的预测。如果玩家的实际行为与预测偏差过大，预存的 Action 序列将失去意义。

**场景示例：**
```
LLM 意图:   拦截玩家（预测玩家会去房间5）
Planner:    生成前往房间5走廊的路径

实际:       玩家突然转向南，进入了房间3

问题:       Queue 中的动作全部指向错误方向，继续执行毫无意义
```

### 解决方案：偏差监控 + 紧急中断

```java
public class IntentValidator {
    /**
     * 监控预测偏差，决定是否需要中断当前执行
     */
    public static boolean shouldInterrupt(
            StrategicIntent currentIntent,
            Position predictedPlayerPos,
            Position actualPlayerPos,
            double threshold) {

        double deviation = Math.sqrt(
            Math.pow(actualPlayerPos.x - predictedPlayerPos.x, 2) +
            Math.pow(actualPlayerPos.y - predictedPlayerPos.y, 2)
        );

        return deviation > threshold;
    }
}
```

**触发中断的条件：**

| 条件 | 阈值 | 说明 |
|------|------|------|
| 玩家位置偏差 | > 3 格 | 玩家行为与预测严重不符 |
| 敌人进入新房间 | - | 拓扑位置变化，需要重新评估战略 |
| 目标变得不可达 | - | 如目标房间被封锁 |
| 玩家进入视野 | - | 机会窗口出现，可能需要切换策略 |

**中断后的处理：**
1. 清空 `ActionQueue`
2. 记录中断事件到 `EnemyMemory`（用于 LLM 理解"为什么我的计划失败了"）
3. 立即触发同步/异步请求新的 `StrategicIntent`
4. 如果新意图尚未返回，执行一个"默认安全动作"（如原地等待或 RuleBasedBrain 的简单行为）

------------------------------------------------------------------------

## 13. 信心驱动执行策略

### 概念

LLM 输出的 `confidence` 值用作**启发式控制参数（heuristic signal）**，而非经过校准的概率值。研究表

明，LLM 的自评 confidence 通常不是 well-calibrated probability——模型说 0.9 不代表真的 90% 正确。但作为相对排序和控制信号，confidence 仍有实用价值。

系统将 confidence 作为动态调节执行节奏的启发式参数：

```json
{
  "confidence": 0.85
}
```

### 执行策略对照

| 信心值范围 | 执行策略 | 理由 |
|-----------|---------|------|
| **≥ 0.8（高信心）** | Planner 生成完整路径后连续执行，中间不重新评估 | LLM 对局势判断清晰，大概率预测准确 |
| **0.5 ~ 0.8（中等信心）** | 每执行 1 个动作后重新评估局势 | 有一定不确定性，需要高频验证 |
| **< 0.5（低信心）** | 放弃意图，立即降级为 `RuleBasedBrain` | LLM 自身对决策不确定，确定性 AI 更安全 |

### 实现逻辑

```java
public void executeIntent(StrategicIntent intent, Enemy enemy,
                           GameStateSnapshot state) {
    if (intent.getConfidence() < 0.5) {
        // 低信心：直接降级
        brain.fallbackToRuleBased(enemy);
        return;
    }

    // Planner 生成动作
    List<Action> actions = planner.translate(intent, state, enemy);
    actionQueue.enqueueActions(actions);

    // 根据信心值决定重评估策略
    if (intent.getConfidence() < 0.8) {
        // 中等信心：设置"每步后检查"标志
        actionQueue.setStepByStepValidation(true);
    }
}
```

**注意**：confidence 的阈值（0.5, 0.8）是经验值，需要在实际使用中根据模型表现调整。文档中给出的数值是合理的初始值，但不保证对

所有模型/场景最优。

------------------------------------------------------------------------

## 14. 失败处理与降级

### 14.1 API 失败处理

LLM API 可能出现多种故障，每种故障需要不同的处理策略。

| 故障类型 | 表现 | 处理策略 |
|---------|------|---------|
| **网络超时** | 请求超过 N 秒无响应 | 重试 1 次，若仍超时→降级为 RuleBasedBrain |
| **API 限流 (429)** | 请求过于频繁 | 等待后重试，增加重试间隔（指数退避） |
| **API 错误 (5xx)** | 服务器内部错误 | 记录日志，降级为 RuleBasedBrain |
| **JSON 解析失败** | 返回格式不符合 Schema | 重试时增加格式强调；仍失败→降级 |
| **无效意图** | target_room 不存在 或 target_position 不可达 | 降级为 RuleBasedBrain |

### 14.2 降级行为（RuleBasedBrain）

当 LLM 不可用时，敌人切换到确定性规则 AI：

```
RuleBasedBrain 行为决策树：

1. 玩家在视野内（局部地图窗口内可见）？
   ├── 是 → 构造 StrategicIntent{goal: CHASE, target: playerPosition}
   │        → ClassicalPlanner 生成 BFS 追击路径
   └── 否 → 进入巡逻模式
            ├── 有守卫目标？
            │   └── 是 → StrategicIntent{goal: GUARD, target: guardTarget}
            └── 否 → 随机选择相邻房间 → StrategicIntent{goal: PATROL}
```

**关键**：RuleBasedBrain 也输出 `StrategicIntent`，而不是直接输出动作。这样降级后的执行路径完全一致（Intent → Planner → Actions），只是 Intent 的来源不同（规则 vs LLM）。

### 14.3 降级与恢复

```java
public class LLMBrain implements EnemyBrain {
    private boolean isDegraded = false;
    private RuleBasedBrain fallback = new RuleBasedBrain();
    private int consecutiveFailures = 0;
    private static final int MAX_FAILURES = 3;
    private static final long DEGRADE_COOLDOWN_MS = 30000; // 30秒冷却

    @Override
    public StrategicIntent think(GameStateSnapshot state) {
        if (isDegraded) {
            if (System.currentTimeMillis() - degradeTime > DEGRADE_COOLDOWN_MS) {
                isDegraded = false;
                consecutiveFailures = 0;
            } else {
                return fallback.think(state);
            }
        }

        try {
            StrategicIntent intent = callLLMAPI(state);
            consecutiveFailures = 0;
            return intent;
        } catch (Exception e) {
            consecutiveFailures++;
            if (consecutiveFailures >= MAX_FAILURES) {
                isDegraded = true;
                degradeTime = System.currentTimeMillis();
                Logger.warn("LLM API 连续失败 " + MAX_FAILURES + " 次，降级为 RuleBasedBrain");
            }
            return fallback.think(state);
        }
    }
}
```

------------------------------------------------------------------------

## 15. 线程安全

### 15.1 共享资源分析

```
    Game Thread（主线程）              AI Thread（后台线程）
         |                                  |
    ┌────┴────┐                        ┌───┴──────────┐
    │ Action  │ ←──── 读写 ────→       │ LLM Client   │
    │ Queue   │                        │ + Planner    │
    └─────────┘                        └──────────────┘
         |
    ┌────┴──────────┐
    │ TETile[][]    │ ←── 通过 GameStateSnapshot 传递 ──→  AI Thread 只读
    │ Player/Enemy  │      (深拷贝或不可变引用)
    └───────────────┘
```

### 15.2 安全规则

| 规则 | 说明 |
|------|------|
| **ActionQueue 必须线程安全** | 使用 `synchronized` 块保护所有读写 |
| **AI 线程只通过 GameStateSnapshot 感知世界** | 快照在 Game Thread 中创建，传入 AI Thread 后即不可变 |
| **AI 线程不修改 Enemy 位置** | 位置修改仅在 Game Thread 的 `Action.execute()` 中进行 |
| **EnemyMemory 独立存储** | AI Thread 生成的记忆更新通过回调回传至 Game Thread |
| **GameStateSnapshot 不可变** | 所有字段 final，创建后不可修改 |

------------------------------------------------------------------------

## 16. 记忆系统

### 16.1 EnemyMemory 设计

记忆系统是敌人"学习"和"适应"的基础。它记录玩家行为模式，作为 LLM 推理的额外上下文。

```java
public class EnemyMemory implements Serializable {
    private static final int MAX_EVENTS = 50;        // 最多存储的事件数
    private static final int MAX_PATTERNS = 10;       // 最多存储的模式数

    private List<MemoryEvent> recentEvents;           // 最近事件（环形缓冲）
    private List<PlayerPattern> learnedPatterns;      // 学习到的玩家模式
    private List<Position> playerTrail;               // 玩家轨迹（最近 N 个位置）
    private Map<String, Integer> playerActionCounts;  // 玩家行为统计

    /**
     * 生成记忆摘要文本，供 PromptBuilder 注入 LLM 上下文
     */
    public String generateSummary() {
        // 提取最近的模式 + 最近的遭遇
        // 返回格式化的文本摘要
    }
}
```

**MemoryEvent 结构：**

```java
public class MemoryEvent implements Serializable {
    public enum EventType {
        PLAYER_SPOTTED,     // 发现玩家
        PLAYER_LOST,        // 玩家消失在视野外
        PLAYER_ATTACKED,    // 玩家发起攻击
        PLAYER_FLED,        // 玩家逃跑
        INTENT_SUCCEEDED,   // 我方战略意图成功
        INTENT_FAILED,      // 我方战略意图失败
        INTERRUPTED         // 执行被中断
    }

    private EventType type;
    private int turnNumber;
    private Position playerPosition;
    private Position enemyPosition;
    private String description;
}
```

**PlayerPattern 结构：**

```java
public class PlayerPattern implements Serializable {
    private String patternName;        // 如 "向东逃跑癖好"
    private String description;        // 详细描述
    private int occurrences;           // 出现次数
    private double confidence;         // 模式置信度
}
```

### 16.2 记忆与 LLM 的交互

记忆内容由 `EnemyMemory.generateSummary()` 生成摘要文本，`PromptBuilder` 将其注入 Prompt：

```
[记忆摘要]
基于过去的遭遇，你已学习到以下玩家行为模式：
1. 玩家有强烈的向东移动偏好（过去 20 回合中 60% 的移动方向是东）
2. 玩家会在 HP 低于 50% 时选择逃跑而非战斗
3. 玩家倾向于避开狭窄的走廊，偏好开放房间

最近的遭遇：
- 3回合前，玩家在房间4被发现，你在5回合后抵达房间4时他已离开
- 上次战略意图失败原因：玩家向东移动的速度超出了预测
```

### 16.3 记忆的持久化

`EnemyMemory` 实现 `Serializable`，通过现有存档系统持久化：

```java
// 保存时（Game.saveGameState 扩展）
GameSaveData data = new GameSaveData(seed, playerX, playerY);
for (Enemy enemy : enemies) {
    data.extraData.put("enemy_" + enemy.getId() + "_memory", enemy.getMemory());
}

// 加载时（Game.loadGameState 扩展）
GameSaveData data = SaveLoadManager.load();
EnemyMemory memory = (EnemyMemory) data.extraData.get("enemy_0_memory");
enemy.setMemory(memory);
```

------------------------------------------------------------------------

## 17. 评估体系

为了让项目在 CS61B Gold Point 评审中展现出研究深度，本节定义一套可量化的评估指标。

### 17.1 核心指标

#### 规划成功率（Planning Success Rate）

```
规划成功率 = 成功完整执行的 StrategicIntent 数量 / 总 Intent 数量
```

一个 Intent 被定义为"成功"当且仅当：
- Planner 成功生成到目标的路径
- 路径上的所有 MoveAction 都返回 SUCCESS（没有被 BLOCKED）
- 没有因偏差过大触发 INTERRUPTED

#### 预测准确率（Prediction Accuracy）

```
预测准确率 = 预测玩家方向与实际方向一致的回合数 / 总预测回合数
```

在 LLM 的 `goal_reasoning` 中提取方向预测（如"玩家将向东移动"），与实际玩家移动方向对比。

#### 适应效率（Adaptation Efficiency）

```
适应效率 = 同一情境下第 N 次遭遇的表现改善率
```

例如：
- 第 1 次遭遇"玩家向东逃跑"：敌人向西追（失败）
- 第 3 次遭遇"玩家向东逃跑"：敌人提前到东侧走廊（成功拦截）

适应效率 = 同类情境中的成功率提升幅度。

### 17.2 Demo 场景设计

以下场景适合在演示中展示系统能力：

| 场景 | 展示能力 | 预期效果 |
|------|---------|---------|
| **基线测试** | 纯 RuleBasedBrain：敌人 BFS 追击玩家 | 敌人沿最短路径直追，可预测 |
| **LLM 拦截** | 玩家多次向东移动后，LLM 预测方向并提前拦截 | 敌人不再追尾，而是切近道拦截 |
| **记忆学习** | 玩家反复使用同一逃跑路线后，敌人提前设伏 | 第 5 次遭遇时敌人已在出口等待 |
| **降级保底** | 断开网络 → 敌人自动切换为 RuleBasedBrain | 无缝切换，游戏不会卡住 |
| **中断重规划** | 玩家在敌人途中突然转向 → 敌人清空队列重新规划 | 行为从"继续走错路"变为"掉头" |

### 17.3 评估数据采集

在 `Game.java` 或独立的 `MetricsCollector` 中：

```java
public class MetricsCollector {
    private int totalIntents = 0;
    private int successfulIntents = 0;
    private int correctPredictions = 0;
    private int totalPredictions = 0;

    public void recordIntentResult(StrategicIntent intent, boolean succeeded) { ... }
    public void recordPrediction(Direction predicted, Direction actual) { ... }

    public double getPlanningSuccessRate() {
        return totalIntents == 0 ? 0 : (double) successfulIntents / totalIntents;
    }

    public double getPredictionAccuracy() {
        return totalPredictions == 0 ? 0 : (double) correctPredictions / totalPredictions;
    }
}
```

这些数据可以在游戏结束后输出到日志，用于定量评估 LLM vs RuleBased 的表现差异。

------------------------------------------------------------------------

## 18. 开发路线图

### 阶段 1：基础敌人（核心架构搭建）

**目标**：让一个敌人出现在地牢中并能执行简单动作。

- [ ] 创建 `Enemy extends Entity` 类
- [ ] 将敌人添加到 `Game.entities` 列表
- [ ] 在 `renderFrame()` 中渲染敌人瓦片
- [ ] 实现 `Action` 接口和 `MoveAction`
- [ ] 实现 `ActionQueue`
- [ ] 实现 BFS 寻路算法（在 `byog.Core` 或新建 `byog.AI` 包）
- [ ] 实现 `GameStateSnapshot` 类
- [ ] 将敌人位置持久化到 `GameSaveData.extraData`
- [ ] 添加敌人在世界生成后的随机放置逻辑（放置在远离玩家的 `FLOOR` 瓦片上）

### 阶段 2：传统 AI（确定性行为）

**目标**：敌人能自主巡逻、看到玩家后追击。

- [ ] 实现 `EnemyBrain` 接口 + `StrategicIntent` 类
- [ ] 实现 `ClassicalPlanner`（BFS 寻路 + 动作生成）
- [ ] 实现 `RuleBasedBrain`（输出 StrategicIntent）
- [ ] 实现局部视野检测（以敌人为中心的矩形窗口）
- [ ] 实现 `WaitAction`
- [ ] 敌人 AI 与游戏主循环集成（在 `Game.update()` 中调用）
- [ ] 测试：敌人能否在房间间导航？碰撞检测是否正确？

### 阶段 3：感知层（LLM 可读的世界描述）

**目标**：将游戏状态转化为结构化的文本描述。

- [ ] 实现局部地图文本生成器（`LocalMapRenderer`）
- [ ] 实现战略层面摘要生成器（`StrategicSummaryBuilder`）
- [ ] 实现 `WorldDescription` DTO
- [ ] 瓦片到字符的映射（基于 `Tileset` 常量）
- [ ] 窗口裁剪逻辑（处理地图边界情况）
- [ ] 测试：生成的文本描述是否准确反映了游戏状态？

### 阶段 4：LLM 集成（核心 AI 能力）

**目标**：敌人能通过 LLM 生成智能战略意图。

- [ ] 实现 `LLMBrain extends EnemyBrain`
- [ ] 实现 `PromptBuilder`（System Prompt + World + Memory + Schema）
- [ ] 实现 LLM API 客户端（HTTP 调用 + JSON 解析）
- [ ] 实现 StrategicIntent JSON 解析器 + Schema 验证
- [ ] 实现异步规划流程（AI Thread + 线程安全 ActionQueue）
- [ ] 实现信心驱动执行策略
- [ ] 实现降级机制（API 失败 → RuleBasedBrain）
- [ ] 实现重规划与中断逻辑

### 阶段 5：高级特性（深度与可玩性）

**目标**：记忆、预测、多敌人协同。

- [ ] 实现 `EnemyMemory`（事件记录 + 模式学习）
- [ ] 实现 `generateSummary()` 方法
- [ ] 记忆持久化（通过 `GameSaveData.extraData`）
- [ ] `PromptBuilder` 集成记忆摘要
- [ ] 玩家行为预测（基于历史轨迹与统计）
- [ ] 多敌人支持（敌人间共享信息或独立记忆）
- [ ] 战斗系统（可选：AttackAction 实现）
- [ ] 实现 `MetricsCollector`（评估数据采集）
- [ ] 寻路优化（A* 替代 BFS，适应更大规模地图）

------------------------------------------------------------------------

## 19. 最终架构总结

```
                    Player (WASD 输入)
                        |
                        v
                 Game Simulation (Game.java, 60 FPS)
                        |
                        v
                 Enemy Perception (Perception Layer)
                 - 局部地图 (15x15 文本网格)
                 - 房间拓扑摘要
                 - 历史事件摘要
                        |
                        v
                 GameStateSnapshot (线程边界)
                 - TETile[][] visible
                 - PlayerSnapshot
                 - EnemySnapshot
                 - RoomGraph
                 - MemorySummary
                        |
          +-------------+-------------+
          |                           |
          v                           v
    PromptBuilder              EnemyMemory
    (System + World +          (模式提取 + 摘要生成)
     Memory + Schema)
          |
          v
    LLM Brain (LLMBrain, 异步)
    - 理解、预测、战略规划
    - 输出 StrategicIntent JSON
          |
          v
    StrategicIntent
    {goal, target_room, strategy, confidence}
          |
          v
    Classical Planner (Game Thread 或 AI Thread)
    - BFS/A* 寻路到目标
    - 生成 MoveAction 序列
    - 策略 → 到达后动作
          |
          v
    ActionQueue (线程安全)
    [Move↑, Move→, Move→, Wait, ...]
          |
          v
    Action Executor (Game Thread)
    - 逐帧执行、碰撞检测
    - 中断监控 (IntentValidator)
          |
          v
    Enemy Behavior
    - 移动 / 等待 / 追击 / 伏击
          |
          v
    反馈回路 (Feedback Loop)
    - 记录事件到 EnemyMemory
    - 更新 MetricsCollector
    - 影响未来 LLM Prompt
```

### 架构中最关键的三层分离

```
LLM 回答：   去哪里？用什么策略？为什么？   →  StrategicIntent
Planner 回答：怎么走？需要几步？             →  List<Action>
Engine 回答：  这一步能走吗？撞墙了吗？       →  ActionResult
```

### 核心哲学

我们的目标不是创造一个"按规则执行"的敌人。
规则 AI 的行为是**可预测的、刻板的、容易被玩家利用的**。

我们的目标是创造一个能够：

- **理解**：不只看瓦片，而是理解"玩家在干什么"
- **预测**：不只看当前位置，而是推断玩家意图
- **规划**：不是随机移动，而是执行有目的的策略
- **学习**：不是每次都从零开始，而是从过往遭遇中提炼模式
- **适应**：不是一成不变，而是根据玩家风格调整策略

的智能对手。

这种敌人会让每次游戏体验都不同——因为敌人会根据玩家的行为"成长"，就如同一个真正的地牢守护者。
