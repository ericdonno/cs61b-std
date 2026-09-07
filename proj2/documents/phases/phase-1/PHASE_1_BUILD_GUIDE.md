# DungeonMind 阶段一构建指南：私有感知与知识边界

> 本文档是 DungeonMind Phase 1 的实施手册。读完你会建成一套私有感知系统：每个敌人不再"全知全能"，而是通过射线投射（raycasting）获得自己的视野范围，墙后的玩家不再出现在"雷达"上。
>
> **阅读建议**：如果你对 Phase 0 的固定场景和 trace 系统还不熟悉，建议先读 `PHASE_0_SPEC.md` 和 `PHASE_1_SPEC.md` 的 §1-§3。

---

## 目录

- [架构概览](#架构概览)
- [前置准备](#前置准备)
- [阶段 1.1：定义数据结构](#阶段-11定义数据结构)
- [阶段 1.2：实现射线投射](#阶段-12实现射线投射)
- [阶段 1.3：扩展观察信封](#阶段-13扩展观察信封)
- [阶段 1.4：适配 Enemy 构造函数](#阶段-14适配-enemy-构造函数)
- [阶段 1.5：更新 Brain 接口](#阶段-15更新-brain-接口)
- [阶段 1.6：改造 Enemy.updateAI](#阶段-16改造-enemyupdateai)
- [阶段 1.7：扩展 Trace 系统](#阶段-17扩展-trace-系统)
- [阶段 1.8：创建 Phase 1 测试支架](#阶段-18创建-phase-1-测试支架)
- [阶段 1.9：存档持久化](#阶段-19存档持久化)
- [附录 A：常见陷阱](#附录-a常见陷阱)
- [附录 B：验收清单](#附录-b验收清单)

---

## 架构概览

Phase 0 的敌人 AI 决策链路是这样：

```
全知世界地图 (TETile[][])
    ↓
Enemy.updateAI() → new GameStateSnapshot(world, player.pos, self.pos)
    ↓
RuleBasedBrain.think(snapshot) → StrategicIntent
    ↓
ClassicalPlanner → ActionQueue → Action
```

问题出在第一步：`GameStateSnapshot` 包含了整个 `TETile[][]` 世界地图和玩家精确坐标。这意味着**每个人敌人都是全知的**——它们可以"看穿"墙壁，精确知道玩家在哪、有多少血。

Phase 1 要做的就是在上面链路的最前面插入一个"过滤器"：

```
全知世界地图 (TETile[][])
    ↓
PerceptionSystem.computeObservation()    ← 新增：射线投射 FOV
    ↓
ObservationEnvelope                      ← 新增：私有感知结果
    ↓
RuleBasedBrain.thinkFromObservation()    ← 新增：基于私有感知决策
    ↓
ClassicalPlanner → ActionQueue → Action  ← 不变
```

用一句比喻来说：**`GameStateSnapshot` 是上帝视角的监控画面，`ObservationEnvelope` 是每个守卫头戴 GoPro 拍到的东西。** 两个守卫站在同一地图的不同位置，GoPro 拍到的内容完全不同——这就是"私有感知"。

**核心设计理念——射线投射 FOV**：从敌人的眼睛位置出发，向视野范围内的每个格子发射一条"光线"（Bresenham 直线算法）。如果光线在中途撞到墙，那堵墙后面的东西就不可见。这模拟的是真实世界中"你只能看到直线方向上没有遮挡的东西"。

---

## 前置准备

### 你需要新建/修改的文件

| 文件 | 新建/修改 | 一句话职责 |
|------|-----------|-----------|
| `byog/Perception/VisibleEntity.java` | **新建** | 可见实体的简要摘要（防信息泄漏） |
| `byog/Perception/HeardEvent.java` | **新建** | 听觉事件数据结构（本阶段只定义不实现） |
| `byog/Perception/ObservationEnvelope.java` | **新建** | 每敌人每 tick 的私有感知结果容器 |
| `byog/Perception/PerceptionSystem.java` | **新建** | 射线投射 FOV 计算引擎 |
| `byog/Test/Phase1EncounterHarness.java` | **新建** | Phase 1 无头测试支架 |
| `byog/Test/Phase1EncounterTest.java` | **新建** | Phase 1 合约测试 |
| `byog/Test/Phase1TestSuite.java` | **新建** | JUnit 聚合入口 |
| `byog/Test/Phase1BaselineMain.java` | **新建** | Golden baseline 生成入口 |
| `byog/Entity/Entity.java` | 修改 | 新增 `agentId` 字段 |
| `byog/Entity/Enemy.java` | 修改 | 新增 `agentId`、`perceptionEnabled`、双路径 updateAI |
| `byog/AI/EnemyBrain.java` | 修改 | 新增 `thinkFromObservation()` 接口方法 |
| `byog/AI/RuleBasedBrain.java` | 修改 | 实现私有感知决策 |
| `byog/Trace/AgentTrace.java` | 修改 | 新增 `OBSERVATION_GENERATED` 事件 |
| `byog/IO/GameSaveData.java` | 修改 | 存档 agentId |
| `byog/Test/Phase0EncounterHarness.java` | 修改 | 适配新 Enemy 构造签名 |
| `byog/Test/EnemyCollisionTest.java` | 修改 | 适配新 Enemy 构造签名 |

### 三个需要特别注意的坑

1. **Bresenham 射线会"漏光"——不需要处理**。标准 Bresenham 只覆盖"射线穿过"的格子。如果一堵墙只占一个格子，射线可能从墙的左右两侧"滑过去"，造成"看到墙后"的假象。**但 `baseline-two-guards` 地图中极少（甚至没有）对角墙角漏光情况。** 即便极少数情况下漏光，看到就是看到了——敌人偶尔多看到一点不影响游戏体验。不需要使用 supercover 变体来堵漏光。

2. **Enemy 构造函数签名变更波及面大**。Phase 1 给 `Enemy` 构造方法增加了一个 `String agentId` 参数。项目中所有 `new Enemy(...)` 的地方都必须更新，包括 `Phase0EncounterHarness`、`Enemy.spawnEnemies()`、`EnemyCollisionTest` 等。**忘记更新任何一处都会编译失败**，IDE 会直接标红，不用担心漏掉。变更波及面大是已知的，慢慢改就行，注意全部改完才能编译。

3. **Phase 0 的 19 个测试必须保持绿色**。这是硬约束。Phase 1 引入了 `perceptionEnabled` 开关（默认 `false`），确保 legacy 路径行为完全不变。如果误改了 `RuleBasedBrain.think(GameStateSnapshot)` 的逻辑，或者 trace 输出格式变了，Phase 0 测试就会红。**但不需要每次代码改动都运行测试**，仅在 Builder 明确要求时再运行。

---

## 阶段 1.1：定义数据结构

> **一句话目标**：编译通过——四个新数据类创建完成，`Entity` 有了 `agentId`，但没有行为变化。

### 1.1.1 创建 VisibleEntity

**做什么**：创建一个纯数据类，描述"敌人看到了什么实体"。

**解释**：Phase 0 中 Brain 通过 `state.getWorld()` 和 `state.getPlayerPosition()` 直接访问完整世界。Phase 1 不能让 Brain 拿到完整 `Entity` 对象引用——那样 Brain 就能通过引用访问 `player.getHp()`、`player.isAlive()` 等所有字段，相当于信息又全泄露了。`VisibleEntity` 是一个"摘要卡片"，只包含类型、位置、可见 HP 和 agentId。

**怎么做**：

在 `byog/Perception/VisibleEntity.java` 中创建：

- 内部枚举 `EntityType`：`PLAYER`、`ENEMY`、`OTHER` 三个值
- 构造参数：`EntityType type, Position position, int visibleHp, String agentId`
- 四个字段 + getter：`getType()`、`getPosition()`、`getVisibleHp()`、`getAgentId()`
- 类标记为 `public final`，字段为 `private final`

**为什么用 `EntityType` 而不是直接存 Class 引用**：因为 Class 引用会暴露"还有哪些实体类型存在"这一信息。用枚举限定了 Brain 能区分的信息种类：它最多能区分"这是玩家"、"这是另一个敌人"、"其他"三种。

**验证**：编译通过。

### 1.1.2 创建 HeardEvent

**做什么**：创建一个纯数据类，描述"敌人听到了什么声音"。

**解释**：Phase 1 只定义数据结构，不实际产生听觉事件。听觉事件的传播规则（距离衰减、障碍物影响、优先级）留到 Phase 5。本阶段先把这个"占位符"放好，`ObservationEnvelope` 中引用它，后续阶段直接使用。

**怎么做**：

在 `byog/Perception/HeardEvent.java` 中创建：

- 内部枚举 `SoundType`：`ATTACK`、`MOVE`、`DEATH`、`ALERT`
- 构造参数：`SoundType type, Position sourcePosition, long turn`
- 三个字段 + getter：`getType()`、`getSourcePosition()`、`getTurn()`
- 类标记为 `public final`

**验证**：编译通过。

### 1.1.3 创建 ObservationEnvelope

**做什么**：创建"每个敌人每 tick 的私有感知结果"容器类。

**解释**：如果把 `ObservationEnvelope` 比作一张"战场态势报告"，它包含四个部分：
- **身份信息**：谁的报告？哪个回合的？第几份报告？
- **自身状态**：我在哪？我还有多少血？
- **可见世界**：我能看到哪些格子？我看到哪些实体？玩家在不在视野里？
- **听觉事件**：我听到了什么声音？（Phase 1 这个列表始终为空）

**怎么做**：

在 `byog/Perception/ObservationEnvelope.java` 中创建：

构造参数列表：
- `String runId` — 游戏运行标识（本次测试中固定为 `"phase1-test"`）
- `int floorId` — 楼层号（固定场景中为 `1`）
- `String agentId` — 此敌人的正式 agentId
- `long observationSeq` — 此敌人第几个 observation（从 0 自增）
- `long observedAtTurn` — 游戏全局 turn 号
- `Position selfPosition` — 自身位置
- `int selfHp` — 自身 HP
- `boolean[][] visibleMask` — 与 world 同尺寸的可见性标记，`true` 表示可见
- `List<VisibleEntity> visibleEntities` — 可见实体列表（不含自身）
- `List<HeardEvent> heardEvents` — 听觉事件列表（Phase 1 始终为空列表 `Collections.emptyList()`）

所有字段 `private final`，提供对应的 getter。此外提供两个便捷方法：
- `boolean isVisible(int x, int y)` — 查询某个坐标是否可见（先边界检查，再查 visibleMask）
- `boolean canSeePlayer()` — 遍历 `visibleEntities`，检查是否有 `EntityType.PLAYER` 类型的项

**为什么 `visibleMask` 只存 boolean 而不复制 tile 数据**：每个格子的 `TETile` 对象是共享的，不需要为每个敌人的观察复制一份完整的 tile 数组。Brain 需要知道"某个可见位置能不能走"时，通过 `ObservationEnvelope` 提供的 `isWalkable()` 方法查询——该方法内部持有 `TETile[][] world` 引用，但先检查 `visibleMask[x][y]`，如果不可见则返回 `false`，即使那个位置实际上是 FLOOR。Brain 永远无法通过这个方法知道墙后的地形。

`isWalkable(int x, int y)` 的实现思路：
1. 检查 `x, y` 是否在边界内，不在则返回 `false`
2. 检查 `visibleMask[x][y]` 是否为 `true`，不是则返回 `false`
3. 检查 `world[x][y]` 是否为可通行 tile（不是 WALL、不是 NOTHING）

**验证**：编译通过。

### 1.1.4 在 Entity 中添加 agentId

**做什么**：给 `Entity.java` 添加一个 `agentId` 字段。

**解释**：当前 `Entity.id` 是 JVM 全局自增的 `int`，且不随存档保存。这意味着加载存档后，同一个敌人的 ID 可能和存档前不同。Phase 1 引入 String 类型的 `agentId`，由调用方在创建时分配（如 `"guard-a"`），可持久化到存档。

**怎么做**：

在 `Entity.java` 中：
- **位置一**：添加字段 `protected String agentId;`
- **位置二**：在现有的构造函数 `Entity(Position position, TETile tile)` 中，将 `agentId` 初始化为 `"entity-" + id`（兜底值，确保所有已有 Entity 子类都有 agentId）
- **位置三**：添加 `public String getAgentId()` 和 `public void setAgentId(String agentId)` 方法

**验证**：编译通过。

---

## 阶段 1.2：实现射线投射

> **一句话目标**：`PerceptionSystem.hasLineOfSight()` 在手画的小地图上正确判断"墙后不可见、空地可见"。

### 1.2.1 实现 blocksVision

**做什么**：`PerceptionSystem` 需要一个判断"这个 tile 是否阻挡视线"的方法。

**解释**：不是所有 tile 都挡视线。FLOOR（地板）和 STAIRS（楼梯）不挡视线——你站在走廊里可以看到走廊尽头的人。WALL（墙）和 NOTHING（虚空/地图边界外）挡视线——墙后面你看不到。

**怎么做**：

在 `byog/Perception/PerceptionSystem.java` 中创建类和方法：

`public static boolean blocksVision(TETile tile)`：
- 返回 `tile == Tileset.WALL || tile == Tileset.NOTHING`

### 1.2.2 实现 Bresenham 射线检测

**做什么**：实现 `hasLineOfSight(world, x0, y0, x1, y1)` 方法。

**解释**：Bresenham 直线算法是计算机图形学中最经典的算法之一，用来在像素格子上"画直线"。想象你在方格纸上从格子 A 画一条直线连到格子 B——Bresenham 告诉你这条线经过了哪些格子。

在我们的场景中，从敌人眼睛位置 `(x0, y0)` 向目标位置 `(x1, y1)` 发射射线。射线沿 Bresenham 路径逐格前进，每走一格就检查：这一格是不是墙？如果是，射线终止（墙本身可见，但射线不再穿透）。

**原理**：Bresenham 的核心思想是用整数运算模拟直线的斜率。它维护一个"误差累积"变量，当累积误差超过 0.5 时就"斜着走一步"。整个过程只用加法和比较，不用浮点数，所以确定性强。

伪代码：

```
function hasLineOfSight(world, x0, y0, x1, y1):
    dx = abs(x1 - x0)
    dy = abs(y1 - y0)
    sx = (x0 < x1) ? 1 : -1      // x 方向步长
    sy = (y0 < y1) ? 1 : -1      // y 方向步长
    err = dx - dy                 // 误差累积

    x = x0, y = y0
    loop:
        // 当前位置是目标位置？
        if (x == x1 and y == y1):
            return true           // 到达目标，中途无墙 → 可见

        // 检查当前格是否挡视线（注意：起点不检查，从下一步开始）
        if (不是起点 and blocksVision(world[x][y])):
            return false          // 撞墙 → 不可见

        // Bresenham 步进逻辑
        e2 = 2 * err
        if (e2 > -dy):
            err -= dy
            x += sx
        if (e2 < dx):
            err += dx
            y += sy
```

**关键细节——墙本身可见**：当射线到达一堵墙的格子时，`hasLineOfSight` 返回 `false`。但在上层 `computeObservation` 中，我们仍然把墙所在的 tile 标记为可见（你知道墙在那里）——只是不继续沿射线方向标记墙后的 tile。这模拟的是真实体验：你看到一堵墙，但看不到墙后面。

**关键细节——射线可能从对角线"滑过"墙角**：标准 Bresenham 只走射线穿过的格子。如果墙只占一个格子，射线可能从墙的对角线方向"擦过"而不被阻挡。这是 Bresenham 的已知特性（参见附录 A-1 的 supercover 变体）。

**怎么做**：

实现上述伪代码。注意：
- 起点 `(x0, y0)` 不检查阻挡（敌人自身不阻挡视线）
- 边界检查：如果 `x, y` 超出 world 边界，视为阻挡（等同于 NOTHING）
- 如果起点==终点，直接返回 `true`

**验证**：

写一个快速的手动测试。创建一个简单的 5×5 小地图（用 `TETile[][]`），放入一堵墙，调用 `hasLineOfSight`：
- `hasLineOfSight(world, 0, 0, 1, 1)` 中间无墙 → 返回 `true`
- `hasLineOfSight(world, 0, 0, 4, 4)` 中间有墙 → 返回 `false`
- `hasLineOfSight(world, 0, 0, 4, 2)` 射线直达，无墙 → 返回 `true`

如果以上三个断言通过，Bresenham 实现基本正确。

### 1.2.3 实现 computeObservation

**做什么**：实现完整的 `computeObservation` 方法——以敌人为中心，扫描视野范围内的所有 tile，生成一个 `ObservationEnvelope`。

**解释**：现在有了"单条射线"的判断能力（`hasLineOfSight`），我们需要把它用到整个视野范围。思路很简单：对 sight range 内的每个 tile，发射一条射线检测是否可见。

**怎么做**：

`public static ObservationEnvelope computeObservation(...)` 的实现步骤：

1. **初始化**：创建 `boolean[width][height]` 的 `visibleMask`，全部 `false`。创建空的 `List<VisibleEntity>`。

2. **两层循环遍历候选 tile**：
   - 外层 `dx` 从 `-sightRange` 到 `+sightRange`
   - 内层 `dy` 从 `-sightRange` 到 `+sightRange`
   - 跳过超出世界边界的坐标
   - 跳过曼哈顿距离 > sightRange 的坐标（sightRange 是曼哈顿距离上限，不是正方形）
   - 跳过自身位置

3. **射线检测**：对每个候选 tile `(tx, ty)`，调用 `hasLineOfSight(world, selfX, selfY, tx, ty)`
   - 如果返回 `true`：标记 `visibleMask[tx][ty] = true`
   - 如果返回 `false` 且当前 tile 是 WALL：也标记 `visibleMask[tx][ty] = true`（墙本身可见）——这需要在 `hasLineOfSight` 返回 `false` 后额外检查 `blocksVision(world[tx][ty])`

   > **实现提示**：更简单的方法是修改 `hasLineOfSight` 的行为定义——改为"射线沿路径前进，遇到阻挡 tile 就停；如果最后一步到达目标 tile（不管目标 tile 是否阻挡），都算'到达'"。这样 `hasLineOfSight` 返回 `true` 的含义是"射线走到了目标 tile"，不等于"目标 tile 可通行"。上层 `computeObservation` 根据是否需要看到实体来区分处理。

   推荐的做法——调整 `hasLineOfSight` 的语义为"射线是否到达了目标 tile"（不管目标 tile 本身是否阻挡）：
   - 伪代码中把 `blocksVision` 检查移到步进之前，确保"走到目标 tile 后不检查阻挡"：
   ```
   loop:
       if (x == x1 and y == y1):
           return true     // 到达目标
       e2 = 2 * err
       // 决定下一步走到哪
       nextX = x, nextY = y
       if (e2 > -dy): nextX = x + sx
       if (e2 < dx):  nextY = y + sy
       // 检查下一步的 tile 是否阻挡
       if (blocksVision(world[nextX][nextY])):
           return false    // 下一步是墙，射线终止
       // 更新位置
       x = nextX, y = nextY
   ```
   这样 `hasLineOfSight` 返回 `true` 意味着射线走到了目标格子（目标格子本身可以是墙）。

4. **收集可见实体**：对每个 `visibleMask[x][y] == true` 的位置，调用 `entityMgr.findEntityAt(new Position(x, y))`：
   - 如果该位置有活实体，且不是 `self`：创建 `VisibleEntity` 加入列表
   - 如果实体是 `Player`：`EntityType.PLAYER`，`visibleHp = player.getHp()`
   - 如果实体是 `Enemy`：`EntityType.ENEMY`，`visibleHp = enemy.getHp()`

5. **构造返回**：
   ```java
   return new ObservationEnvelope(
       runId, floorId, self.getAgentId(),
       self.getObservationSeq(), currentTurn,
       self.getPosition(), self.getHp(),
       visibleMask, visibleEntities,
       Collections.emptyList()  // Phase 1: 无听觉事件
   );
   ```

**为什么曼哈顿距离和射线检测两层过滤**：曼哈顿距离是快速的粗筛——O(1) 计算省掉了 sight range 外的射线。射线检测是精确的细筛——只有 LOS 畅通的 tile 最终被标记为可见。两层组合比纯射线检测快很多。

**验证**：用一个小的手写世界，手动构造敌人和玩家，调用 `computeObservation`，断言可见实体列表只包含在 LOS 内的实体。第 1.8 节会写正式的 JUnit 测试，这里先用 `main` 方法或简单的断言验证。

---

## 阶段 1.3：扩展观察信封

> **一句话目标**：`ObservationEnvelope` 提供 `isWalkable()` 方法，Brain 能通过它判断"某个可见位置能不能走"。

### 1.3.1 添加 isWalkable 和 getVisiblePlayer

**做什么**：为 `ObservationEnvelope` 添加两个便捷查询方法。

**解释**：Brain 在做 PATROL 决策时需要随机选择一个"可见且能走"的位置作为巡逻目标。`isWalkable()` 封装了"先检查是否可见、再检查地形"的逻辑。`getVisiblePlayer()` 封装了"遍历 visibleEntities 找到 PLAYER 类型的实体"。

**怎么做**：

- `public boolean isWalkable(int x, int y)`：
  1. 如果 `x < 0 || x >= width || y < 0 || y >= height`，返回 `false`
  2. 如果 `!visibleMask[x][y]`，返回 `false`
  3. 检查 `world[x][y]` 不是 WALL 且不是 NOTHING → 返回 `true` 或 `false`

  注意：`ObservationEnvelope` 需要在构造时保存一个 `TETile[][] world` 引用（或只保存 `int width, int height` 加上 `TETile[][] world` 引用）。

- `public VisibleEntity getVisiblePlayer()`：
  遍历 `visibleEntities`，找到 `type == EntityType.PLAYER` 的项并返回，找不到返回 `null`。

**验证**：编译通过，`isWalkable` 逻辑与 `Entity.canStandOn` 一致（只是多了一层 `visibleMask` 前置检查）。

---

## 阶段 1.4：适配 Enemy 构造函数

> **一句话目标**：所有 `new Enemy(...)` 的地方编译通过，Phase 0 全部 19 个测试绿色。

### 1.4.1 修改 Enemy 构造方法

**做什么**：给 `Enemy` 构造方法增加 `String agentId` 参数。

**解释**：这是 Phase 1 唯一的不兼容变更。所有创建 Enemy 的地方都需要传入 agentId。为避免 Phase 0 行为变化，`perceptionEnabled` 默认为 `false`。

**怎么做**：

在 `Enemy.java` 中，**位置一**：修改构造方法签名，增加最后一个参数 `String agentId`：

```java
public Enemy(Position position, TETile tile, int hp, int sightRange,
             int moveInterval, int attackDamage, int damageVariance,
             Random random, String agentId) {
    super(position, tile);
    // ... 现有赋值 ...
    this.agentId = agentId;          // 新增
    this.perceptionEnabled = false;  // 新增
    this.observationSeq = 0;         // 新增
}
```

**位置二**：添加三个新字段（在类顶部已有字段旁边）：
```java
private boolean perceptionEnabled = false;
private String agentId;
private long observationSeq = 0;
```

**位置三**：添加 setter 和 getter：
```java
public void setPerceptionEnabled(boolean enabled) { this.perceptionEnabled = enabled; }
public boolean isPerceptionEnabled() { return perceptionEnabled; }
public String getAgentId() { return agentId; }
public long getAndIncrementObservationSeq() { return observationSeq++; }
```

### 1.4.2 更新所有调用方

**做什么**：找出项目中所有 `new Enemy(...)` 的地方，补上 `agentId` 参数。

**解释**：每个调用方根据自己的场景传入合适的 agentId。

**怎么做**：

**位置一**：`Phase0EncounterHarness.fromAscii()`（约第 163-164 行）
- `new Enemy(aPos, Tileset.ENEMY, 20, 7, 1, 1, 0, new Random(guardASeed))` → 末尾加 `, "guard-a"`
- `new Enemy(bPos, Tileset.ENEMY, 20, 7, 1, 1, 0, new Random(guardBSeed))` → 末尾加 `, "guard-b"`

**位置二**：`Enemy.spawnEnemies()`（约第 191 行）
- `new Enemy(new Position(0, 0), Tileset.ENEMY, config.enemyHp, ...)` → 末尾加 `, "enemy-" + i`

**位置三**：`EnemyCollisionTest`
- 找到测试中所有 `new Enemy(...)` 的调用，末尾加 `, "test-enemy-1"`、`"test-enemy-2"` 等

**验证**：编译通过即可。Phase 0 测试验证在 Builder 要求时运行（见"三个需要特别注意的坑"第 3 点）。

---

## 阶段 1.5：更新 Brain 接口

> **一句话目标**：`RuleBasedBrain` 能够基于私有 `ObservationEnvelope`（而非全知 `GameStateSnapshot`）做出决策。

### 1.5.1 扩展 EnemyBrain 接口

**做什么**：给 `EnemyBrain` 接口增加一个新方法 `thinkFromObservation`。

**解释**：Phase 0 的 `think(GameStateSnapshot)` 不能直接删掉——Phase 0 的 legacy 路径还在用。我们用一个带默认实现的 Java `default` 方法来添加新接口，不影响已有实现编译。

**怎么做**：

在 `EnemyBrain.java` 中：
```java
/**
 * 基于私有 ObservationEnvelope 做决策。Phase 1+ 使用。
 * 默认抛出 UnsupportedOperationException，子类按需覆写。
 */
default StrategicIntent thinkFromObservation(ObservationEnvelope obs) {
    throw new UnsupportedOperationException(
        "This brain does not support private observation");
}
```

### 1.5.2 在 RuleBasedBrain 中实现 thinkFromObservation

**做什么**：让 `RuleBasedBrain` 覆写 `thinkFromObservation`，用私有感知数据做决策。

**解释**：`thinkFromObservation` 和 `think` 的决策逻辑本质上相同——玩家在视野内就追，不在就巡逻。区别在于：
- `think` 用 `state.getWorld()` 获取全知地图来生成巡逻目标
- `thinkFromObservation` 用 `obs.isWalkable(x, y)` 来生成巡逻目标——只能选**可见且可通行**的格子

**怎么做**：

在 `RuleBasedBrain.java` 中，覆写 `thinkFromObservation`：

```java
@Override
public StrategicIntent thinkFromObservation(ObservationEnvelope obs) {
    Position selfPos = obs.getSelfPosition();
    VisibleEntity player = obs.getVisiblePlayer();

    if (player != null) {
        int dist = MathHelper.manhattanDistance(selfPos, player.getPosition());
        if (dist == 1) {
            // 玩家在相邻格 → 攻击
            return new StrategicIntent(StrategicIntent.Goal.ATTACK_PLAYER,
                    StrategicIntent.Strategy.ATTACK, player.getPosition());
        } else {
            // 玩家在视野内但不在相邻格 → 追击
            return new StrategicIntent(StrategicIntent.Goal.CHASE,
                    StrategicIntent.Strategy.CHASE, player.getPosition());
        }
    } else {
        // 看不到玩家 → 从可见 FLOOR tile 中随机选一个巡逻目标
        Position patrolTarget = generatePatrolPosFromObservation(obs, selfPos);
        return new StrategicIntent(StrategicIntent.Goal.PATROL,
                StrategicIntent.Strategy.PATROL, patrolTarget);
    }
}
```

新增辅助方法 `generatePatrolPosFromObservation`：

```
function generatePatrolPosFromObservation(obs, selfPos):
    // 收集所有可见且可行走的位置
    candidates = []
    for dx in [-8 .. 8]:
        for dy in [-8 .. 8]:
            nx = selfPos.x + dx
            ny = selfPos.y + dy
            if obs.isWalkable(nx, ny) and manhattanDistance >= 3:
                candidates.add((nx, ny))

    if candidates 不为空:
        return random.choice(candidates)
    else:
        return selfPos  // 没有可选位置 → 原地不动
```

**为什么巡逻目标范围从"全图随机"缩小到"可见区域"**：因为 Brain 不再拥有全图——它只能看到 `visibleMask` 范围内的地形。这符合"有限知识"原则：敌人不知道墙后面有没有房间，所以它不会朝墙后巡逻。巡逻范围缩小是符合直觉的。

**验证**：暂时只有编译验证。行为验证在 1.8 节通过 Phase 1 测试做。

---

## 阶段 1.6：改造 Enemy.updateAI

> **一句话目标**：`Enemy.updateAI()` 根据 `perceptionEnabled` 选择"全知路径"或"私有感知路径"，Phase 0 测试全绿。

### 1.6.1 实现双路径 updateAI

**做什么**：在现有 `updateAI(trace overload)` 方法中，用 `if (perceptionEnabled)` 分叉。

**解释**：我们不新增 `updateAI` 的方法重载（签名已经够多了），而是在现有的 trace overload 内部做分支。两个分支的后续逻辑（Planner/ActionQueue/Action/Retry）完全相同，只有"构造什么输入、调用 Brain 的哪个方法、发什么 trace 事件"不同。

**怎么做**：

在 `Enemy.java` 的 `updateAI(world, entityMgr, player, traceContext, traceSink)` 方法中（约第 55 行），找到构造 `GameStateSnapshot` 的位置（约第 62 行），用 `if (perceptionEnabled)` 包裹：

```
if (perceptionEnabled):
    // 私有感知路径
    ObservationEnvelope obs = PerceptionSystem.computeObservation(
        world, entityMgr, this, player, sightRange, logicalTick);
    observationSeq 自增（通过 getAndIncrementObservationSeq）

    safeRecord(OBSERVATION_GENERATED 事件)
    intent = brain.thinkFromObservation(obs)
else:
    // Phase 0 legacy 路径（现有代码不变）
    GameStateSnapshot snapshot = new GameStateSnapshot(world, ...)
    safeRecord(LEGACY_DECISION_INPUT 事件)
    intent = brain.think(snapshot)

// 后续：safeRecord(INTENT_SELECTED) 和 Planner/ActionQueue 不变
```

**关键问题——私有感知路径中 `brain.thinkFromObservation(obs)` 需要什么**：如果 `brain` 是 `RuleBasedBrain` 的实例（`Enemy` 构造函数中的默认 Brain），它已经实现了 `thinkFromObservation`，正常工作。但如果某个 Enemy 被设置了其他 Brain 实现（未实现 `thinkFromObservation`），调用会抛 `UnsupportedOperationException`。目前所有 Enemy 都用 `RuleBasedBrain`，所以暂时安全。

**验证**：编译通过。因为 `perceptionEnabled` 默认为 `false`，所有现有代码走 legacy 路径。Phase 0 测试在 Builder 要求时运行。

---

## 阶段 1.7：扩展 Trace 系统

> **一句话目标**：`OBSERVATION_GENERATED` 事件出现在 trace JSON 中，包含 `visiblePlayer`、`visibleEntityCount`、`fovTileCount`。

### 1.7.1 修改 AgentTrace

**做什么**：在 `AgentTrace.java` 中新增事件类型和对应字段。

**解释**：Phase 0 的 trace 记录了 `LEGACY_DECISION_INPUT` → `INTENT_SELECTED` → `ACTION_ATTEMPTED` → `ACTION_RESULT` 四个生命周期。Phase 1 在"私有感知路径"下，第一个事件变为 `OBSERVATION_GENERATED`，携带感知摘要。

**怎么做**：

在 `AgentTrace.java` 中：

**位置一**：`EventType` 枚举新增值 `OBSERVATION_GENERATED`

**位置二**：`SCHEMA_VERSION` 改为 `"phase1.trace.v1"`

**位置三**：`Event` 类新增三个字段（放在 `eventType` 和 `inputKind` 之间）：
```java
public final Boolean visiblePlayer;
public final Integer visibleEntityCount;
public final Integer fovTileCount;
```
同时更新私有构造函数（增加这三个参数）。

**位置四**：新增 factory 方法：
```java
public static Event observationGenerated(Context context,
        boolean visiblePlayer, int visibleEntityCount, int fovTileCount) {
    return new Event(SCHEMA_VERSION, context.scenarioId,
            context.scenarioVersion, context.logicalTick,
            null, context.actorKey,
            EventType.OBSERVATION_GENERATED,
            "private-perception-v1",   // inputKind
            null, null, null, null,
            null, null, null, null, null, null,
            visiblePlayer, visibleEntityCount, fovTileCount);
}
```
注意：所有 factory 方法都需要更新（因为构造函数参数增加了三个），legacy 事件（`legacyDecisionInput`, `intentSelected`, `actionAttempted`, `actionResult`）新增的三个参数全部传 `null`。

**位置五**：`InMemorySink.toCanonicalJson()` 方法中，新增三个字段的输出（放在 `inputKind` 之后）。nullable Boolean/Integer 的处理方式与现有 nullable 字段相同。

**为什么 Schema Version 从 `phase0.trace.v1` 升级为 `phase1.trace.v1`**：因为 Event 的字段结构变了（增加了三个字段）。Phase 0 的 golden 文件使用旧 schema，Phase 1 的 golden 使用新 schema——两个 schema 在同一个 `AgentTrace` 类中共存，通过不同的事件类型区分。旧事件类型（`LEGACY_DECISION_INPUT` 等）的新字段输出为 `null`，不影响 Phase 0 golden 的 JSON 结构。

### 1.7.2 在 updateAI 私有感知路径发事件

**做什么**：在 1.6 节的私有感知路径中，`PerceptionSystem.computeObservation()` 返回后，记录 `OBSERVATION_GENERATED` 事件。

**怎么做**：

在 `Enemy.updateAI()` 的 `if (perceptionEnabled)` 分支中，`computeObservation` 返回后：

```java
ObservationEnvelope obs = PerceptionSystem.computeObservation(...);
if (traceContext != null) {
    int entityCount = obs.getVisibleEntities().size();
    int fovCount = countVisibleTiles(obs.getVisibleMask());
    safeRecord(traceSink,
        AgentTrace.Event.observationGenerated(
            traceContext, obs.canSeePlayer(), entityCount, fovCount));
}
```

其中 `countVisibleTiles` 是一个简单的辅助方法：遍历 `visibleMask` 计数 `true`。

**验证**：编译通过。完整行为验证在 1.8 节。

---

## 阶段 1.8：创建 Phase 1 测试支架

> **一句话目标**：Phase 1 全部测试通过，A 能看到玩家，B 看不到，Phase 0 的 19 个测试依然全绿。

### 1.8.1 创建 Phase1EncounterHarness

**做什么**：创建一个无头测试支架，复用 `baseline-two-guards:v1` 场景但启用私有感知。

**解释**：`Phase0EncounterHarness` 已经提供了地图解析、实体构造和调度逻辑。Phase 1 harness 通过委托复用这些能力，然后打开 `perceptionEnabled` 开关。这样两个 harness 共享同一套地图解析逻辑，避免 parser 分叉。

**怎么做**：

在 `byog/Test/Phase1EncounterHarness.java` 中：

```java
public final class Phase1EncounterHarness {

    // 内部委托给 Phase0EncounterHarness
    private final Phase0EncounterHarness delegate;
    private final AgentTrace.InMemorySink traceSink;

    private Phase1EncounterHarness(Phase0EncounterHarness delegate) {
        this.delegate = delegate;
        // 启用私有感知
        delegate.guardA().setPerceptionEnabled(true);
        delegate.guardB().setPerceptionEnabled(true);
        // 使用独立的 trace sink（覆盖 Phase 0 sink）
        this.traceSink = new AgentTrace.InMemorySink();
    }

    public static Phase1EncounterHarness baselineTwoGuardsV1() {
        // 复用 Phase 0 的地图解析和实体构造
        Phase0EncounterHarness base = Phase0EncounterHarness.baselineTwoGuardsV1();
        return new Phase1EncounterHarness(base);
    }
}
```

**改造 updateAI 调用**：由于 Phase 0 harness 的 `step()` 方法调用 `guardA.updateAI(world, entityMgr, player, ctxA, traceSink)`，其中 traceSink 是 Phase 0 harness 内部的。Phase 1 harness 需要用自己的 traceSink。最简单的方式是复制 `step()` 逻辑：

```
public void step():
    ctxA = new AgentTrace.Context("baseline-two-guards", 1, logicalTick, "guard-a")
    ctxB = new AgentTrace.Context("baseline-two-guards", 1, logicalTick, "guard-b")
    delegate.guardA().updateAI(world, entityMgr, player, ctxA, this.traceSink)
    delegate.guardB().updateAI(world, entityMgr, player, ctxB, this.traceSink)
    delegate.entityMgr.flushPendingChanges()
    delegate.entityMgr.removeDeadEntities()
    logicalTick++
```

> **实现提示**：为了让 `Phase1EncounterHarness` 能访问 `Phase0EncounterHarness` 的内部字段（`world`, `entityMgr`, `player`），你可能需要把 `Phase0EncounterHarness` 中相关字段的访问修饰符从 `private` 改为 package-private（去掉 `private`），或者在 `Phase0EncounterHarness` 上增加 getter 方法。如果已经在 Phase 0 提供了 `player()`, `guardA()`, `guardB()`, `terrainCopy()` 等 getter，直接使用即可；如果缺少 `entityMgr` 的 getter，增加一个 `public EntityManager getEntityMgr()`。

**验证**：编译通过。

### 1.8.2 编写 Phase 1 测试

**做什么**：创建 `Phase1EncounterTest.java`，包含 8 个核心测试。

**怎么做**：

每个测试遵循相同的模式：创建 `Phase1EncounterHarness`，运行若干 tick，断言。

**P1-T01**：`guardB_cannot_see_player_behind_wall`
- 创建 harness，运行 1 tick
- 检查 B 的 observation：`canSeePlayer()` 返回 `false`，`visibleEntities` 中不含 PLAYER 类型实体
- 验证方式：在 `step()` 中通过某种方式获取 observation——要么 `Enemy` 暴露上一个 observation，要么通过 trace 事件验证

> **实现提示——如何获取 observation 进行断言**：`ObservationEnvelope` 在 `Enemy.updateAI()` 内部创建后只传给了 Brain 和 trace sink，没有保存。最简单的做法是给 `Enemy` 加一个 `private ObservationEnvelope lastObservation` 字段，在私有感知路径中赋值，并提供 `public ObservationEnvelope getLastObservation()` 的 getter。测试就可以直接查询。

**P1-T02**：`guardA_can_see_player_no_wall`
- A 的 observation：`canSeePlayer()` 返回 `true`，player 在 visibleEntities 中且 HP 可见

**P1-T03**：`different_enemies_different_observations`
- 同 tick 的 A 和 B 的 `visibleEntities.size()` 不同

**P1-T04**：`brain_decision_based_on_private_obs`
- B 首次 intent 为 PATROL，A 首次 intent 为 CHASE

**P1-T05**：`agentId_stable_across_instances`
- 两次构造的 Enemy（同 agentId 字符串）的 `getAgentId()` 一致
- `getAgentId()` 不等于任意 JVM 自增 `id`

**P1-T06**：`observation_has_identity_fields`
- ObservationEnvelope 的 `getRunId()`、`getFloorId()`、`getAgentId()`、`getObservationSeq()`、`getObservedAtTurn()` 都有非默认值

**P1-T07**：`trace_contains_perception_events`
- canonical trace JSON 包含 `OBSERVATION_GENERATED` 事件
- A 和 B 的 `visiblePlayer` 字段值不同

**P1-T08**：`phase1_rule_baseline_matches_golden`
- 运行 12 tick 后的 canonical trace + final state 与 golden JSON 一致
- 先暂时跳过（golden 尚未生成），等 1.8.3 生成后再启用

### 1.8.3 生成 Phase 1 golden baseline

**做什么**：创建 `Phase1BaselineMain.java`，生成 `documents/baselines/phase1_rule_baseline_v1.json`。

**怎么做**：

参考 `Phase0BaselineMain` 的实现模式：
1. 创建 `Phase1EncounterHarness`，运行 12 tick
2. 获取 `canonicalTraceJson()` 和 `canonicalState()`
3. 组装顶层 JSON（与 Phase 0 baseline 格式一致，但 schema 版本不同）
4. 如果传入 `--write documents/baselines/phase1_rule_baseline_v1.json`，写入文件；否则打印到 stdout

生成 golden 后，人工审查 JSON：
- 搜索 `"visiblePlayer"`：A 的事件中有 `true`，B 的事件中有 `false`
- 搜索 `"inputKind"`：私有感知事件中为 `"private-perception-v1"`
- 搜索 `"schemaVersion"`：为 `"phase1.trace.v1"`

确认无误后，在 P1-T08 中启用 golden comparison。

### 1.8.4 创建 Phase1TestSuite

**做什么**：创建聚合测试套件。

**怎么做**：

```java
@RunWith(Suite.class)
@Suite.SuiteClasses({
    Phase0EncounterTest.class,   // Phase 0 回归
    EnemyCollisionTest.class,    // Phase 0 回归
    Phase1EncounterTest.class    // Phase 1 新测试
})
public class Phase1TestSuite { }
```

**验证**：Builder 要求时运行 Phase 1 TestSuite（聚合 Phase 0 19 个 + Phase 1 8 个 = 27 tests）。

---

## 阶段 1.9：存档持久化

> **一句话目标**：加载存档后，Enemy 的 agentId 与存档前一致。

### 1.9.1 扩展 GameSaveData

**做什么**：在存档中保存 `Entity.id → agentId` 的映射。

**解释**：`agentId` 是 String，当前存档格式支持基本类型和序列化对象。最简单的方式是在 `GameSaveData` 中添加一个 `HashMap<Integer, String> entityAgentIds`，用 JVM 的 `Entity.id` 作为 key（因为它是当前的存档关联键），`agentId` 作为 value。加载时根据 `id` 恢复 `agentId`。

**怎么做**：

在 `GameSaveData.java` 中：

- 添加字段 `private Map<Integer, String> entityAgentIds = new HashMap<>();`
- 存档时：遍历 `entityMgr.getAllEntities()`，对每个 Entity 调用 `put(entity.getId(), entity.getAgentId())`
- 读档时：遍历恢复的 Entity，根据 `entity.getId()` 从 `entityAgentIds` 中查找，找到则 `setAgentId(value)`，找不到则设置为 `"entity-" + entity.getId()`（兜底）

**验证**：

手动流程：
1. 启动游戏（GUI），进入一个程序生成的地图（有敌人）
2. 存档（按 `:` 然后 `q`）
3. 重新启动游戏，加载存档
4. 观察——游戏正常运行

或者更可靠的方法——在测试中创建 Entity，存入 GameSaveData，序列化后反序列化，验证 agentId 一致。这个可以留到 Phase 2 写自动化测试。

---

## 附录 A：常见陷阱

### A-1：Bresenham 射线"漏光"（不需要处理）

**现象**：敌人站在墙的对角线位置，射线从墙的边上"滑过去"，看到了墙后的实体。

**原因**：标准 Bresenham 只追踪"射线穿过的格子"。如果墙只占一个格子，从对角线方向看，射线可能绕过墙而不碰到墙的格子。

**本阶段处理**：不作处理。`baseline-two-guards` 地图中极少（甚至没有）对角墙角漏光情况。即便极少数情况下漏光，看到就是看到了——敌人偶尔多看到一点不影响游戏体验。不需要使用 supercover 变体。

### A-2：Phase 0 测试在修改 Enemy 构造后红了

**现象**：运行 `Phase0TestSuite` 后部分测试失败。

**原因**：最常见的原因是某处 `new Enemy(...)` 没有更新——但这种情况会导致**编译失败**，不是测试失败。如果编译通过但测试失败，检查以下可能：
- `Enemy` 构造函数中 agentId 赋值的逻辑错误（如 agentId 为 null 导致 NPE）
- `RuleBasedBrain` 的 `think(GameStateSnapshot)` 被意外修改

**正确做法**：用 `git diff` 检查 `RuleBasedBrain.java` 的 `think()` 方法是否被误改。Phase 1 不应对 `think(GameStateSnapshot)` 做任何修改。

### A-3：FOV 扫描性能问题

**现象**：sight range 较大（如 15+）时游戏卡顿。

**原因**：以曼哈顿距离 R 为半径的圆形区域约有 `2R²` 个 tile，每个 tile 发射一条 Bresenham 射线。R=7 时约 200 条射线，R=15 时约 900 条——接近 5 倍增长。

**正确做法**：Phase 1 的 sight range 为 7，性能完全不是问题。如果后续阶段需要使用更大的 sight range，考虑升级为递归阴影投射（recursive shadowcasting）——它只对视野边界的 tile 做计算，时间复杂度与视野周长（而非面积）成正比。

---

## 附录 B：验收清单

- [ ] 编译通过，无错误无警告
- [ ] Phase 0 全部 19 个测试绿色（`java ... JUnitCore byog.Core.Phase0TestSuite`）
- [ ] `PerceptionSystem.hasLineOfSight()` 在手写小地图上正确判断遮挡
- [ ] Guard A (9,2) 能通过 LOS 看到玩家 (3,2)——中间全是 FLOOR，无墙
- [ ] Guard B (12,5) 看不到玩家 (3,2)——射线被 (7,4) 的墙阻挡
- [ ] A 和 B 同一 tick 的 `visibleEntities` 列表不同
- [ ] A 的首次 intent 为 CHASE（看到玩家），B 的首次 intent 为 PATROL（看不到）
- [ ] `ObservationEnvelope` 包含 runId、floorId、agentId、observationSeq、observedAtTurn 字段
- [ ] Canonical trace JSON 中出现 `OBSERVATION_GENERATED` 事件，且 `inputKind` 为 `"private-perception-v1"`
- [ ] Phase 1 golden baseline 已生成，人工审查确认 A 和 B 的 `visiblePlayer` 字段不同
- [ ] P1-T01 ~ P1-T08 全部通过
- [ ] Phase 1 TestSuite 聚合测试全部绿色（27 tests）
- [ ] 存读档后 agentId 保持一致
- [ ] 现有 GUI 游戏入口编译通过（`Main.java` 正常运行）
