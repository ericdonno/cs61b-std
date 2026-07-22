# DungeonMind 构建指南

> 本文档是 DungeonMind 项目的分阶段实施手册。每阶段都从零开始，逐步搭建一个完整的敌人 AI 系统：从随机移动的红色 'E'，到能用 BFS 追击玩家，再到接入大语言模型（LLM）做出智能决策。
>
> **阅读建议**：如果你对整体架构还不熟悉，建议先读"架构概览"，了解各组件之间的关系，再按阶段实施。

---

## 目录

- [架构概览](#架构概览)
- [前置准备](#前置准备)
- [阶段一：基础敌人](#阶段一基础敌人)
- [阶段二：传统 AI](#阶段二传统-ai)
- [阶段三：感知层](#阶段三感知层)
- [阶段四：LLM 集成](#阶段四llm-集成)
- [阶段五：高级特性](#阶段五高级特性)
- [附录 A：常见陷阱](#附录-a常见陷阱)
- [附录 B：阶段验收清单](#附录-b阶段验收清单)

---

## 架构概览

在动手写代码之前，先理解整个系统的分层结构。敌人 AI 系统分为四层：

```
┌──────────────────────────────────────────┐
│              决策层 (Brain)               │  "我要做什么？"
│  RuleBasedBrain / LLMBrain               │  产出：StrategicIntent（战略意图）
├──────────────────────────────────────────┤
│             规划层 (Planner)              │  "怎么做到？"
│  ClassicalPlanner + BFSPathfinder        │  产出：List<MoveAction>（动作序列）
├──────────────────────────────────────────┤
│             执行层 (Action)               │  "一步一步做"
│  ActionQueue → MoveAction.execute()      │  逐帧消费动作
├──────────────────────────────────────────┤
│             感知层 (Perception)           │  "我看到什么？"
│  LocalMapRenderer → WorldDescription     │  产出：文本地图（给 LLM 读）
└──────────────────────────────────────────┘
```

**数据流向**：感知层从游戏世界采集信息 → 决策层产出一个"战略意图"（如"追击玩家"）→ 规划层把意图翻译成具体移动动作 → 执行层逐帧执行这些动作。

**关键设计理念——"滚动视界预测"**：AI 不是每帧都重新思考"我要干什么"，那样太慢。而是每隔若干帧批量生成一组动作（比如 5 个），存入队列慢慢消费。队列快空了再生成下一批。这解耦了"AI 思考频率"和"游戏帧率"——思考可以有延迟（尤其是调 LLM API 时），但画面不能卡。

---

## 前置准备

### 你需要新建/修改的文件

所有代码放在 `byog.Core` 包下。

| 阶段 | 新建 | 修改 |
|------|------|------|
| 一 | `Enemy.java`, `Action.java`, `MoveAction.java`, `ActionQueue.java` | `Tileset.java`, `Direction.java`, `Game.java`, `GameSaveData.java` |
| 二 | `BFSPathfinder.java`, `StrategicIntent.java`, `EnemyBrain.java`, `RuleBasedBrain.java`, `ClassicalPlanner.java` | `Enemy.java`, `MoveAction.java` |
| 三 | `LocalMapRenderer.java`, `WorldDescription.java` | — |
| 四 | `GameStateSnapshot.java`, `PromptBuilder.java`, `LLMClient.java`, `LLMBrain.java` | `Enemy.java`（升级构造函数） |
| 五 | `EnemyMemory.java`, `EnemySaveData.java` | `Game.java`（升级存档逻辑）, `Enemy.java`（中断检测） |

### 三个需要特别注意的坑

1. **`Position` 没有覆写 `equals()`**。`Position` 是 CS61B 提供的坐标类，但它没有覆写 `equals()` 方法。这意味着两个坐标值相同的 `new Position(5,3)` 用 `==` 或 `.equals()` 比较都会返回 `false`（因为比较的是对象引用而非内容）。**始终用 `p1.x == p2.x && p1.y == p2.y` 比较坐标**。

2. **`TETile[x][y]` 的 y 轴方向**。游戏世界的坐标原点在左下角，y 轴向上增长（这是屏幕渲染的标准坐标系）。但文本打印天然是从上到下——如果你在渲染文本地图时按 y 从 0 到 HEIGHT 遍历，打印出来的地图会上下颠倒。**文本渲染时 y 从高到低遍历**。

3. **`Game.java` 中的渲染和碰撞是分开处理的**。`renderFrame()` 只负责把实体画到屏幕上，`isPlayerColliding()` 只负责阻止玩家穿过实体。敌人加入后，**两处都要修改**——渲染要画敌人，碰撞要阻止玩家走到敌人所在格子上。

---

## 阶段一：基础敌人

> **一句话目标**：屏幕上看得到红色 'E' 在随机走动，且不穿墙、不走出地图。

### 1.1 添加敌人瓦片

**做什么**：在 `Tileset.java` 中添加一个 `ENEMY` 常量瓦片。

**解释**：`Tileset` 是游戏中的"瓦片工厂"——它定义所有可以画在地图上的图形单元。每个瓦片有字符、前景色、背景色三个属性。`WALL` 瓦片画墙，`FLOOR` 瓦片画地板，我们现在要加一个 `ENEMY` 瓦片画敌人。

**怎么做**：用红色文字 + 黑色背景，字符 `'E'`。后续阶段可以通过改瓦片颜色来表示敌人不同状态（如低血量变暗、追击时变亮）。

**验证**：编译通过。

---

### 1.2 给 Direction 枚举添加偏移量

**做什么**：给 `Direction.java` 的每个枚举值添加 `dx` 和 `dy` 字段，表示该方向在 x 轴和 y 轴上的一步偏移量。

**解释**：`Direction` 是一个枚举，当前只有四个方向常量（UP、DOWN、LEFT、RIGHT），但没有包含"朝这个方向走一步坐标怎么变"的信息。每次要计算新坐标都得写 switch-case，很繁琐。

**怎么做**：
- 给枚举添加 `dx` 和 `dy` 字段：`UP=(0,1)`, `DOWN=(0,-1)`, `LEFT=(-1,0)`, `RIGHT=(1,0)`
- 添加一个 `fromDelta(int dx, int dy)` 静态方法：给定两个坐标的差值，反推出是哪个方向（如 dx=0, dy=1 返回 UP）

**为什么需要 `fromDelta`**：后续 BFS 寻路时，我们需要知道"从位置 A 到相邻位置 B 是哪个方向"。BFS 会记录 `cameFrom` 数组，存储"到达每个节点的方向"，然后回溯路径时需要从坐标差反推方向。

**注意事项**：不要删除 `Player.java` 中可能存在的 switch-case 方向逻辑——新增字段作为补充，不破坏现有代码。

**验证**：`Direction.UP.dx` 输出 `0`，`Direction.UP.dy` 输出 `1`。

---

### 1.3 创建 Action 体系

**做什么**：创建三个文件，构建敌人的"动作系统"。

#### Action.java — 动作接口

**作用**：定义所有动作的统一接口。

- `Action` 接口：有一个 `execute()` 方法，执行动作并返回结果
- `ActionResult` 枚举：描述动作执行后的四种结果
  - `SUCCESS`：动作成功执行
  - `BLOCKED`：被阻挡（如撞墙）
  - `INTERRUPTED`：被中断（阶段五使用）
  - `COMPLETED`：动作序列完成

#### MoveAction.java — 移动动作

**作用**：实现敌人移动一步的具体逻辑。这是 Action 接口的唯一实现类。

**执行逻辑**：从敌人当前位置 + 方向偏移计算出目标位置 → 检查目标位置是否可通行（调用已有的碰撞检测逻辑）→ 合法则更新敌人位置返回 SUCCESS，否则返回 BLOCKED。

**注意**：阶段一用简化版——`execute()` 接收 `TETile[][]`（世界地图数组）直接判断瓦片类型。等到阶段四引入 `GameStateSnapshot`（游戏快照类）后，再统一重构签名。这避免了"还没造的轮子阻碍当前进度"。

#### ActionQueue.java — 动作队列

**作用**：存储 AI 生成的待执行动作序列。用 `ArrayDeque`（双端队列）实现。

**提供的方法**：
- `enqueue(Action)`：将动作加入队尾
- `poll()`：从队首取出并移除一个动作
- `needRefill()`：返回队列长度 ≤ 2 时为 true，表示"快空了，该补充了"
- `clear()`：清空队列（阶段五中断重规划时使用）

**为什么需要 ActionQueue**：这是"滚动视界预测"机制的基础——AI 一次生成多个动作存入队列，然后逐帧消费。队列快空了才触发下一轮思考。好处是 AI 不需要每帧都决策（思考有开销，尤其是调 LLM 时），但画面每帧都能更新。

**验证**：写一个 main 方法，手动入队几个 MoveAction → 逐一出队打印。

---

### 1.4 创建 Enemy 类

**做什么**：创建 `Enemy extends Entity`，代表地牢中的一个敌人。

**解释**：`Entity` 是游戏中所有"东西"的基类（玩家、敌人等都继承它），它已经有位置等基本属性。`Enemy` 在此基础上增加 AI 行为。

**阶段一需要的最小字段**：
- `actionQueue`（ActionQueue）：存储待执行动作的队列
- `hp`（int）：生命值，阶段一暂时用不到但先声明
- `randomDirection()` 方法：随机返回一个 Direction

**核心方法 `updateAI(TETile[][] world)`**：
1. 如果队列为空 → 随机选一个方向生成 MoveAction 入队
2. 从队列取出一个动作并执行
3. 如果返回 BLOCKED（撞墙）→ 不额外处理。下一帧队列可能为空，会再次随机选新方向

**为什么阶段一只用随机移动**：目的是先打通整个集成链路——渲染、AI tick、碰撞、存档——而不引入任何复杂 AI。链路打通后，阶段二替换 AI 逻辑为零风险。

**验证**：编译通过，能在 main 方法中创建 Enemy 并调用 updateAI。

---

### 1.5 集成到 Game.java

这是阶段一最关键也最容易出错的步骤，需要修改 Game.java 的 **5 个位置**。

#### 位置一：添加敌人列表

在 `Game` 类的字段区添加 `private List<Enemy> enemies`。

**为什么需要两个列表**：Game.java 中已有 `entities` 列表用于渲染遍历（`renderFrame()` 遍历它画所有实体）。但 AI tick 只需要遍历敌人，遍历 `entities` 会多遍历到玩家和其他非敌人实体。所以单独维护一个 `enemies` 列表专门给 AI tick 用。创建敌人时必须**同时加入两个列表**。

#### 位置二：世界生成后放置敌人

在 `processInput` 的 `case 's'` 分支中（按 s 开始新游戏时），以及在 `loadGameState` 中（加载存档时），在 `entities.add(player)` 之后放置敌人。

**放置策略**：
1. 用 `new Random(seed.hashCode() + 1)` 创建独立随机数生成器（+1 是为了不和世界地图生成的随机序列重叠）
2. 随机生成坐标 → 检查是否为 FLOOR 瓦片（不能放在墙上）
3. 检查是否离玩家至少 5 格远（避免敌人一开局就贴脸）
4. 创建 Enemy 并同时加入 `enemies` 和 `entities` 两个列表

**为什么用 seed 派生随机序列**：保证同一个种子总是生成相同的敌人位置，与游戏世界的确定性设计保持一致。如果你用 `new Random()` 无参构造，每次游戏的敌人位置都不同，不符合 CS61B 项目的要求。

#### 位置三：添加 AI tick

**AI tick** 是指每帧调用所有敌人的 AI 更新逻辑。

在 `playWithKeyboard()` 的 while 循环中，`processInput()` 之后、`draw()` 之前，插入：

```java
if (gameState == GameState.PLAYING) {
    for (Enemy e : enemies) {
        e.updateAI(world);
    }
}
```

**为什么放在 processInput 之后、draw 之前**：AI 需要基于玩家本回合移动后的最新状态做决策；渲染需要展示 AI 移动后的最新画面。

#### 位置四：修复碰撞检测

`isPlayerColliding(Position p)` 方法目前只检查玩家自己是否占据了目标瓦片。需要改为检查**所有实体**是否占据了目标瓦片——玩家不能走到敌人所在的格子上。

用 `p1.x == p2.x && p1.y == p2.y` 比较 Position（因为 Position 没有覆写 equals）。

#### 位置五：存档持久化

**保存时**：在 `saveGameState()` 中，将每个敌人的 `[x, y]` 坐标存入 `GameSaveData.extraData`，key 为 `"enemyData"`。

**加载时**：在 `loadGameState()` 中，从 `extraData` 反序列化坐标列表，逐一创建 Enemy 并加入 `enemies` 和 `entities`。

**为什么用 `extraData` 而不是修改 `GameSaveData` 的字段**：`extraData` 是 `Map<String, Serializable>`（一个键值对字典），可以塞任意可序列化的数据进去而不改存档格式。后续添加 HP、记忆等状态时直接替换 value 为更复杂的对象即可，不需要改 `GameSaveData` 的类结构。

**验证**：启动游戏 → 输入种子 → 看到红色 'E' 随机移动 → 按 `:q` 保存 → 重新加载 → 敌人出现在保存时的位置。

---

## 阶段二：传统 AI

> **一句话目标**：敌人能在 7 格范围内发现玩家，然后通过 BFS 最短路径追击。看不到玩家时就巡逻。

### 2.1 实现 BFS 寻路器

**做什么**：创建 `BFSPathfinder.java`，实现一个静态方法 `findPath(start, goal, world)`。

**解释**：BFS（广度优先搜索）是一种图搜索算法。它从起点出发，逐层向外扩展，直到找到目标。因为是一层一层扩展的，所以找到的第一条路径就是最短路径。

**方法签名**：`static List<Position> findPath(Position start, Position goal, TETile[][] world)`

返回值是从起点到终点的有序位置列表（不包含起点自身），如果不可达则返回空列表。

**算法核心**：
- **可通行节点**：`FLOOR` 瓦片。通过 `tile.description().equals(Tileset.FLOOR.description())` 判断（不能用 `==` 比较 TETile 对象，因为可能不是同一个引用）
- **四个移动方向**：上下左右
- **记录来源**：用 `cameFrom[x][y]` 二维数组记录"到达位置 (x,y) 的上一步方向"。BFS 到达目标后，从目标沿着 `cameFrom` 回溯到起点，就得到了完整路径
- **边界处理**：坐标超出 `[0, WIDTH)` 或 `[0, HEIGHT)` 范围直接跳过

**为什么用 BFS 而不是 A\\***：A\* 是 BFS 的优化版，需要启发函数来引导搜索方向，实现更复杂。80×30 的地图最多 2400 个节点，BFS 完全可以瞬间算完，没必要引入 A\* 的复杂度。保证最短路径 + 实现简单 = 当前最优解。

**验证**：手动构造一个小地图，选两个 FLOOR 点，打印路径长度和中间坐标。

---

### 2.2 创建 StrategicIntent 和 EnemyBrain 接口

这两个类构成了 AI**决策层**的核心。

#### StrategicIntent.java — 战略意图

**作用**：纯数据类（只有字段、构造器、getter，没有业务逻辑），描述 AI 大脑做出的一个高层决策。

**字段说明**：
- `goal`（Goal 枚举）：AI 想要达成的目标，可选值：
  - `INTERCEPT_PLAYER`：拦截玩家去路
  - `GUARD`：守卫某位置
  - `PATROL`：巡逻
  - `CHASE`：追击玩家
  - `AMBUSH`：埋伏
  - `RETREAT`：撤退
- `strategy`（Strategy 枚举）：实现目标的方式，可选值：`INTERCEPT`、`AMBUSH`、`PATROL`、`GUARD`、`CHASE`
- `targetPosition`（Position）：目标位置（要去哪）
- `confidence`（double）：AI 对决策的信心值（0.0~1.0），阶段五使用
- `targetRoom`（int）：目标房间编号，暂未使用

**Goal 和 Strategy 为什么要分开**：Goal 表达"我要达成什么"（What），Strategy 表达"我用什么方式达成"（How）。同一个 Goal 可以用不同 Strategy——比如目标是 GUARD（守卫宝藏），可以用 AMBUSH（埋伏在门口）也可以用 PATROL（围绕宝藏巡逻）。ClassicalPlanner 会根据 Strategy 决定到达目标后的具体行为。

#### EnemyBrain.java — AI 大脑接口

**作用**：定义 AI 决策的统一接口。所有具体的 AI 实现（规则 AI、LLM AI、甚至未来可能远程 Python Agent）都实现这个接口。

**方法**：`StrategicIntent think(GameStateSnapshot state)`

输入是当前游戏状态的快照，输出是一个战略意图。

**为什么用接口**：这是策略模式（Strategy Pattern）的应用——`Enemy` 只依赖 `EnemyBrain` 接口，不关心具体是哪个实现。阶段二用 `RuleBasedBrain`，阶段四换成 `LLMBrain`，`Enemy` 的代码一行都不需要改。

**关于 GameStateSnapshot**：阶段二先做一个简化版（三个字段：world 地图数组、playerPosition 玩家位置、enemyPosition 敌人位置），够 RuleBasedBrain 用了。阶段四再升级为完整版。

---

### 2.3 实现 RuleBasedBrain

**做什么**：`RuleBasedBrain implements EnemyBrain`。这是**保底 AI**，纯手写规则，不依赖任何外部 API。

**作用**：当 LLM 不可用时（网络断了、API Key 过期、额度用尽），游戏不会卡住——敌人降级为规则 AI 继续运作。

**决策逻辑**（两层判断）：
1. 玩家在视野范围内（曼哈顿距离 ≤ 14，即 7 格半径内）→ 返回 `CHASE` 意图，目标设为玩家当前位置
2. 玩家在视野范围外 → 返回 `PATROL` 意图，目标设为玩家最后已知位置

**什么是曼哈顿距离**：两点间沿网格行走的最短距离，公式为 `|x1-x2| + |y1-y2|`。区别于欧几里得距离（直线距离），曼哈顿距离更适合网格地图。7 格半径意味着曼哈顿距离 ≤ 14（因为对角移动在网格中需要 2 步）。

**视野检测的简化**：阶段二用曼哈顿距离做视野检测，敌人能"看穿"墙壁（只要玩家在半径内，隔墙也能感知）。优点是实现简单，缺点是穿墙感知不够真实。阶段三可升级为视线检测（从敌人向玩家方向逐格检查是否被墙挡住），但当前版本已经足够验证整个 AI 链路。

**验证**：把 RuleBasedBrain 注入 Enemy，玩家靠近时敌人开始追击，跑远后停止。

---

### 2.4 实现 ClassicalPlanner

**做什么**：`ClassicalPlanner` 是 AI**规划层**的核心——它把"战略意图"翻译成具体的移动动作序列。

**解释**：Brain 说"我要追击玩家到 (10, 20)"，但 Brain 不知道"怎么走过去"。ClassicalPlanner 负责：先调用 BFS 算出最短路径，再把路径上的每一步变成 `MoveAction` 放入队列。

**核心方法**：`static List<MoveAction> translate(StrategicIntent intent, Position enemyPos, TETile[][] world)`

**翻译流程**：
1. 从 `StrategicIntent` 中取 `targetPosition`（目标位置）
2. 调用 `BFSPathfinder.findPath()` 计算从敌人位置到目标的最短路径
3. 路径上每两个相邻点 → 计算方向（用 `Direction.fromDelta()`）→ 创建一个 `MoveAction`
4. 根据 `Strategy` 类型追加到达目标后的行为：
   - `AMBUSH`（埋伏）：追加 3 个"等待"动作（在原地守株待兔）
   - `GUARD`（守卫）：追加 1 个"等待"动作
   - `CHASE` / `PATROL`：到达后不做特殊处理（队列消耗完后会自动请求新意图）

**"等待"动作的实现**：在 `MoveAction` 中支持 `direction == null` 的情况。null 方向 = 这一帧不移动。这比创建单独的 `WaitAction` 类更简单。

**不可达的处理**：如果 BFS 返回空路径（目标被墙包围、无法到达），插入一个任意方向的 MoveAction 作为占位——防止队列彻底为空导致敌人原地卡死。下一帧 `needRefill()` 触发后 Brain 会重新决策。

**验证**：用 RuleBasedBrain 生成一个 CHASE 意图 → ClassicalPlanner 翻译 → 打印生成的 MoveAction 序列，验证方向和数量合理。

---

### 2.5 升级 Enemy

**做什么**：在 `Enemy` 中接入决策层和规划层。

**新增字段**：
- `brain`（EnemyBrain）：AI 大脑，默认构造时使用 `RuleBasedBrain`
- `planner`（ClassicalPlanner）：动作规划器

**新的 updateAI 逻辑**：
1. 如果 `actionQueue.needRefill()`（队列快空了）→ 构建 GameStateSnapshot → 调用 `brain.think()` 获取意图 → 调用 `ClassicalPlanner.translate()` 生成动作列表 → 逐个入队
2. 从队列取出一个动作 → 执行

**方法签名升级**：`updateAI` 从 `updateAI(TETile[][] world)` 改为 `updateAI(TETile[][] world, Player player)`，因为 Brain 需要知道玩家在哪。

**Game.java 同步修改**：AI tick 调用从 `enemy.updateAI(world)` 改为 `enemy.updateAI(world, player)`。

**验证**：玩家靠近敌人（7 格内）→ 敌人沿最短路径追击 → 玩家拉开距离（超出视野）→ 敌人停止追击回到巡逻。

---

## 阶段三：感知层

> **一句话目标**：将 `TETile[][]` 游戏状态转换成 LLM 能读懂的文本地图。

**解释"感知层"**：LLM 看不懂 Java 的 `TETile[][]` 二维数组。它只能读文本。感知层的任务就是把游戏的视觉信息（地图上的瓦片）翻译成文字描述，方便 LLM 理解"周围环境是什么样的"。

### 3.1 实现局部地图渲染器

**做什么**：`LocalMapRenderer.java`，一个静态方法把世界状态渲染成 ASCII 文本地图。

**方法签名**：`static String render(TETile[][] world, Position enemyPos, Position playerPos)`

**渲染逻辑**：
- 以敌人为中心，取 15×15 的矩形窗口（敌人坐标 ±7）
- y 轴从高到低遍历（游戏坐标 y 轴向上，但文本从上到下打印——所以必须反着遍历，否则地图上下颠倒）
- 瓦片映射规则：敌人在该位置 → `E`，玩家在该位置 → `P`，墙 → `#`，地面 → `.`，地图外 → 空格
- 每行末尾加换行符

**窗口超出地图边界的处理**：窗口坐标超出地图范围的，统一映射为空格。这样做比裁剪窗口更简单，而且让 LLM 知道"这个方向走不通，是地图边界"。

**为什么固定 15×15**：这是信息量和 token 消耗之间的权衡。15×15 = 225 个字符，加上换行约 240 字符。更大的窗口提供更多视野但消耗更多 LLM token（费用更高），而远处的区域对当前决策影响很小。

**验证**：在 Game.java 中临时添加 `System.out.println(LocalMapRenderer.render(...))`，对比游戏画面验证瓦片映射正确。

---

### 3.2 创建 WorldDescription

**做什么**：`WorldDescription.java`，一个数据容器，把感知层产出的所有文字描述聚合在一起。

**作用**：PromptBuilder（阶段四）需要从多个来源组装 LLM 的输入上下文。WorldDescription 作为感知层的统一输出，包含了"世界是什么样的"这一部分信息。它的职责是提供描述，不负责拼接 Prompt。

**包含三种信息**：
- `localMap`（String）：局部地图文本，来自 `LocalMapRenderer`
- `roomTopology`（String）：房间拓扑摘要。描述地图中房间的连通关系（如"房间 0 连接房间 1 和房间 2"），帮助 LLM 理解地图结构。阶段三用占位文本（如"未知"），后续可实现
- `historySummary`（String）：历史事件摘要。阶段五接入 `EnemyMemory`（敌人记忆系统）后，这里会包含"玩家最近的行为模式"等历史信息

**核心方法 `toPromptContext()`**：将三种信息组装成一个带有中文标题的完整文本块，例如：

```
【当前视野】
...局部地图...

【地图结构】
未知

【近期事件】
无历史记录
```

**为什么需要 WorldDescription 而不直接拼字符串**：单一职责——PromptBuilder 负责"怎么写 Prompt"，WorldDescription 负责"描述世界"。分开后各自可以独立修改和测试。

**验证**：构造假数据，调用 `toPromptContext()` 打印输出，检查格式整洁、无乱码。

---

## 阶段四：LLM 集成

> **一句话目标**：敌人能通过调用大语言模型（LLM）API 生成比规则 AI 更聪明的战略意图。

**解释"LLM 集成"**：LLM（Large Language Model，大语言模型）如 GPT-4o-mini 是一个远程 AI 服务。我们通过 HTTP 请求把游戏状态发过去，它返回一个 JSON 格式的战略意图。LLM 的优势在于推理能力——比如看到玩家一直往东走，它能推断出"应该在东边路口拦截"，而规则 AI 只会傻傻追着玩家当前位置跑。

### 4.1 升级 GameStateSnapshot

**做什么**：将阶段二的简化版 `GameStateSnapshot` 升级为包含完整游戏状态的不可变数据类。

**解释**：`GameStateSnapshot`（游戏状态快照）是某时刻游戏世界的一个"截屏"。它在主游戏线程中创建，然后传给 AI 线程使用。"不可变"意味着创建后所有字段都不能修改——这是线程安全的关键保证。

**为什么快照必须是不可变的**：GameStateSnapshot 在游戏线程创建，传入 AI 线程。如果 AI 线程读取的过程中游戏线程同时修改了数据，就会出现数据不一致（竞态条件）。不可变对象从根源上消除了这个问题。

**新增字段**（在阶段二已有的 world、playerPosition、enemyPosition 基础上）：
- `enemyHp`：敌人当前血量（让 LLM 知道是否该撤退）
- `currentRoom`：敌人当前所在房间编号
- `turnNumber`：当前回合数（让 LLM 感知时间流逝）
- `timestamp`：创建时间戳
- `memorySummary`：记忆摘要文本（阶段五接入）

保留阶段二的简化构造函数作为重载——确保 RuleBasedBrain 不需要修改。

---

### 4.2 实现 PromptBuilder

**做什么**：`PromptBuilder.java`，负责组装发送给 LLM 的完整 Prompt（提示词）。

**解释**：Prompt 就是你"告诉 LLM 该干什么"的文字。一个好的 Prompt 包括两部分：System Prompt 定义角色和规则，User Message 提供当前具体信息。

**两个静态方法**：
- `buildSystemPrompt()` → 返回固定的 System Prompt（系统提示词）
- `buildUserMessage(GameStateSnapshot state, WorldDescription worldDesc)` → 动态组装 User Message（用户消息）

**System Prompt 设计要点**（这部分在游戏全程不变）：
- 角色定义：你是地牢守护者 AI，控制一个敌人在迷宫中狩猎玩家
- 能力描述：你可以选择移动策略，但不能穿墙
- 限制声明：你只能看到局部地图，不需要自己规划具体路径
- 输出格式约束：必须返回严格的 JSON，列出每个字段的含义和合法值

**User Message 结构**（这部分每轮动态变化）：
```
局部地图文本 + 房间拓扑 + 记忆摘要 + 敌人当前状态（HP、位置）
```

**为什么 System Prompt 和 User Message 分开**：System Prompt 定义"你是谁"，全程不变。User Message 描述"当前发生了什么"，每轮更新。OpenAI API 会对 System Prompt 做缓存优化，分开写可以减少 token 消耗（省钱）。

**为什么用中文写 Prompt**：LLM 对中文的理解没有障碍。中文 Prompt 对课程评审者更友好，也方便调试时阅读日志。

**验证**：调用 `buildSystemPrompt()` + `buildUserMessage()` 并打印完整 Prompt，人工审查内容是否准确、格式是否规范。

---

### 4.3 实现 LLMClient

**做什么**：`LLMClient.java`，封装 HTTP 调用 OpenAI Chat Completions API 的细节。

**解释**：这是一个网络客户端，负责把 Prompt 发给 OpenAI 的服务器，接收 JSON 响应。它隐藏了 HTTP 连接、请求构造、错误处理等底层细节，对调用者只暴露一个简单的方法。

**方法签名**：`String chat(String systemPrompt, String userMessage)`

**关键参数选择**：
- **模型**：`gpt-4o-mini`。这是 OpenAI 目前性价比最高的模型——推理能力对于游戏 AI 足够，响应快（1-3 秒），成本极低
- **temperature**（温度参数，控制输出随机性）：建议 0.7。范围 0.0~2.0。太低（如 0.1）输出太机械，行为呆板；太高（如 1.0）输出太随机，行为疯狂。0.7 是"有创意但不失控"的平衡点
- **max_tokens**（最大输出长度）：500。StrategicIntent JSON 通常不超过 300 tokens，500 留有余量
- **超时设置**：30 秒。API 正常响应在 1-5 秒，30 秒预留了重试和网络波动的时间

**API Key 管理**：通过 `System.getenv("OPENAI_API_KEY")` 从环境变量读取。**绝不能在代码中硬编码**——提交作业时这会暴露密钥，可能被盗用产生费用。Demo 时如果没 API Key，可以准备一个 mock 版本直接返回预制 JSON。

**JSON 序列化注意事项**：手动拼 JSON 请求体时，Prompt 中的换行符（`\n`）和双引号（`\"`）需要正确转义。大段文本中的未转义引号是 API 调用失败的最常见原因。

**验证**：用 LLMClient 发送一个简单的 "Hello" Prompt，打印返回内容。

---

### 4.4 实现 LLMBrain

**做什么**：`LLMBrain implements EnemyBrain`，调用 LLM API 生成 StrategicIntent。包含完整的**降级机制**。

**解释"降级机制"**：LLM API 不是 100% 可靠的——网络可能断、API 可能挂、Key 可能过期。降级机制确保：LLM 不能用时，AI 自动退回用规则 AI，游戏不会崩溃。

**核心流程**：
1. 检查是否处于 degraded 状态（降级模式）→ 是则跳过 API 调用，直接用 RuleBasedBrain
2. 构建 Prompt → 调用 LLM API → 解析返回的 JSON
3. 解析成功 → 重置失败计数为 0、返回 StrategicIntent
4. 任何异常（网络错误、JSON 格式错误等）→ 失败计数 +1 → 用 RuleBasedBrain → 连续失败 3 次进入 degraded 模式

**degraded 模式（降级模式）**：进入后不再浪费 API 调用（因为连续失败说明是系统性问题）。冷却 30 秒后再尝试一次 API 调用，成功则恢复，失败继续降级。对玩家来说降级是无感的——敌人行为从"智能"退步为"规则"，但不会消失。

**JSON 解析策略**：阶段四用手动字符串解析（查找 `{` 和 `}` 定界符，逐字段提取值）。不引入 Gson/Jackson 等第三方 JSON 库——保持 CS61B 项目的纯净性（只用标准库）。代价是需要手动处理嵌套 JSON（如 `target_position: {x:10, y:20}`）。

**解析的关键字段和默认值**：
- `goal`：必填。无法解析时默认 PATROL（巡逻）
- `target_position`：可为 null。为 null 时用玩家当前位置作为目标
- `strategy`：必填。无法解析时默认 PATROL
- `confidence`：信心值 0.0~1.0，暂无实际使用，阶段五接入

**验证**：连接真实 API → LLMBrain 生成意图 → 对比同一局面下 RuleBasedBrain 的意图，观察差异。

---

### 4.5 在 Enemy 中切换 AI 模式

**做什么**：给 `Enemy` 新增一个接受 API Key 的构造函数。`Game.java` 中创建敌人时根据是否有 API Key 选择用哪个构造函数。

**为什么 Enemy 代码不需要改动**：`Enemy.updateAI()` 只知道调用 `brain.think()`，不关心 brain 内部是调 API 还是跑规则。这就是 EnemyBrain 接口的价值——实现可互换。

**验证**：分别用 RuleBasedBrain 和 LLMBrain 运行游戏，观察敌人行为差异。LLM 敌人应该表现出更智能的决策（如预测玩家移动方向进行拦截，而非简单跟在屁股后面追）。

---

## 阶段五：高级特性

> **一句话目标**：敌人能记住玩家行为模式，并在计划执行中途检测玩家变化并重新规划。

### 5.1 实现 EnemyMemory

**做什么**：`EnemyMemory implements Serializable`，为敌人赋予"记忆"能力。

**解释**：之前敌人每帧的决策只基于"当前状态"，没有"历史"。EnemyMemory 让它能记住玩家过去的行为，从而做出预测。

**记录两类信息**：
1. **玩家轨迹**：最近 20 个玩家位置的环形缓冲（ring buffer——数组满了就覆盖最旧的，始终保留最近 N 条记录）
2. **移动方向统计**：统计玩家往每个方向（东/西/南/北/原地）走了多少次

**每回合的操作**（`recordObservation()` 方法）：
- 将当前玩家位置追加到轨迹
- 如果玩家可见且轨迹长度 ≥ 2：取倒数第二个和最后一个位置，计算移动方向（用 `Direction.fromDelta()`），更新方向计数

**摘要生成**（`generateSummary()` 方法）：返回人类可读的文本，包含：
- 方向偏好排序（出现次数最多的方向排前面，如"向东 70%，向南 20%..."）
- 最近 5 个事件描述

**为什么用方向统计而不是更复杂的模式**：方向统计是最简单有效的玩家行为特征。LLM 看到"玩家 70% 向东移动"会自然推断出"应该在东侧路口拦截"。更复杂的模式（路径序列预测、房间转移图）可以后续版本迭代。

**为什么 EnemyMemory 要实现 Serializable**：存档需要持久化。敌人的记忆数据要随存档一起保存和恢复。

**验证**：在 `Enemy.updateAI()` 中调用 `memory.recordObservation()`，每 10 回合打印一次 `memory.generateSummary()`。

---

### 5.2 完善存档系统

**背景**：阶段一已经保存了敌人坐标。现在需要保存更多状态（HP、记忆）。

**做什么**：
1. 创建 `EnemySaveData implements Serializable`——只包含需要持久化的数据：x、y、hp、memory 四个字段
2. 存档时：遍历所有敌人，为每个创建 EnemySaveData，存入 `GameSaveData.extraData`
3. 读档时：从 extraData 反序列化 EnemySaveData，创建 Enemy 并恢复 hp 和 memory

**为什么需要独立的 EnemySaveData 类而不是直接序列化 Enemy**：Enemy 对象包含 ActionQueue、EnemyBrain、TETile 引用等运行时对象，这些要么不可序列化，要么不需要保存（读档后重新创建即可）。EnemySaveData 只提取"游戏状态相关的数据"，反序列化后通过这些数据重建 Enemy。这是**数据传输对象（DTO）模式**的典型应用。

**验证**：进入游戏 → 敌人在追击中（有轨迹数据）→ `:q` 保存 → 重新加载 → `generateSummary()` 仍有之前的数据。

---

### 5.3 中断重规划

**做什么**：在 `Enemy.updateAI()` 中添加偏差检测，让敌人能对玩家的突然变化做出快速反应。

**问题场景**：ActionQueue 中存有基于"旧世界状态"生成的 5 个 MoveAction。玩家在第 2 帧突然转向反向跑了——但敌人还在傻傻执行剩余 3 个向原方向的 MoveAction，直到队列耗尽才重新规划。

**中断机制**：
- Enemy 维护 `lastKnownPlayerPos`（Position）——记录上次生成 StrategicIntent 时玩家在哪
- 每帧更新时，计算当前玩家位置与 `lastKnownPlayerPos` 的曼哈顿距离
- 偏差超过 3 格 → 清空 ActionQueue（丢弃所有旧动作）→ 记录中断事件到 memory → 立即触发重新规划

**为什么阈值是 3 格而不是 1 格**：玩家正常移动每回合就变 1 格。如果阈值是 1，几乎每回合都会触发中断，造成不必要的重规划开销。3 格表示"玩家行为与预测有明显偏差"——比如本来向东走了好几步，现在突然开始向南走了 3 步。

**验证**：敌人正在沿路径追击 → 玩家突然向反方向连续移动 → 观察敌人是否清空旧路径并转向新方向。

---

## 附录 A：常见陷阱

### A.1 Position 的 equals 问题

**现象**：碰撞检测总是不生效，敌人能穿玩家、玩家能穿敌人。

**原因**：`byog.lab5.Position` 没有覆写 `equals()` 和 `hashCode()`。`new Position(5,3).equals(new Position(5,3))` 返回 `false`。HashMap 也无法用 Position 当 key。

**正确做法**：始终用 `p1.x == p2.x && p1.y == p2.y` 比较坐标。

---

### A.2 TETile 坐标顺序

**现象**：局部地图文本上下颠倒。

**原因**：`TETile[x][y]` 的 y 轴从下到上增长（屏幕坐标系），但文本天然从上到下增长。直接按 y 升序打印会得到倒置的地图。

**正确做法**：文本渲染时 y 从高到低遍历。

---

### A.3 敌人必须同时加入两个列表

**现象**：敌人不渲染（只加入了 enemies 没加入 entities），或敌人能穿玩家（碰撞检测只遍历了 entities 但用了错误的逻辑）。

**原因**：Game.java 中有两个列表——`entities`（渲染用，`renderFrame()` 遍历）和 `enemies`（AI tick 用）。创建敌人时必须同时 `enemies.add()` 和 `entities.add()`。

---

### A.4 Direction 枚举的兼容性

**现象**：修改 Direction.java 后 Player 移动出错。

**原因**：Player.getNewPosition() 中可能有 switch-case 逻辑依赖旧的 Direction 结构。新增 dx/dy 字段作为补充，保留原有的 switch-case。两者可以共存。

---

### A.5 LLM API Key 泄漏

**现象**：GitHub 提交后 OpenAI 账户被盗用产生大量费用。

**原因**：代码中硬编码了 API Key。

**正确做法**：通过 `System.getenv("OPENAI_API_KEY")` 读取。提交前确认 `git diff` 中不包含密钥。Demo 时可以准备一个返回预制 JSON 的 MockLLMClient（不调真实 API）。

---

## 附录 B：阶段验收清单

### 阶段一

- [ ] 编译通过，无错误
- [ ] 红色 'E' 出现在地牢中（至少 3 个敌人）
- [ ] 敌人随机移动，不穿墙、不走出地图
- [ ] 玩家无法走入敌人占据的瓦片
- [ ] `:q` 保存后重新加载，敌人出现在保存时的位置

### 阶段二

- [ ] 敌人在 7 格半径内能发现玩家并开始追击
- [ ] 追击路径为 BFS 最短路径，遇墙绕行
- [ ] 玩家跑出视野后敌人切换回巡逻/随机模式
- [ ] 多敌人时彼此不重叠（如果已实现实体间碰撞）

### 阶段三

- [ ] `LocalMapRenderer.render()` 输出准确反映世界状态
- [ ] 局部地图中 'E' 位于中心
- [ ] 玩家在视野内显示为 'P'，视野外不显示
- [ ] 地图边界外统一显示空格

### 阶段四

- [ ] `PromptBuilder` 输出的 Prompt 格式完整、内容准确
- [ ] `LLMClient` 成功调用 API 并返回响应
- [ ] JSON 解析正确提取 goal、target_position、strategy
- [ ] JSON 解析失败或 API 异常时自动降级为 RuleBasedBrain
- [ ] 连续 3 次失败后进入 degraded 模式，不再浪费 API 调用
- [ ] LLM 敌人表现出比 RuleBased 更智能的行为（如提前拦截、利用地形等）

### 阶段五

- [ ] `EnemyMemory.generateSummary()` 输出正确的玩家方向偏好统计
- [ ] 存档/读档后敌人保留记忆数据
- [ ] 玩家位置偏差超过 3 格时触发队列清空和重规划
- [ ] 敌人在追击中途玩家突然转向 → 敌人清空旧路径并重新寻路
