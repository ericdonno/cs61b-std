# 包架构重构计划：拆分 byog.Core

## 摘要

将 `byog.Core` 中的 41 个源文件按职责拆分为 10 个子包，消除"大包装一切"的问题。同时移除 AGENTS.md 中的 CS61B autograder 约束。

## 当前状态

* 项目共约 62 个 `.java` 文件，其中 `byog.Core` 占 41 个（66%）

* 现有包结构：`byog.Core`、`byog.TileEngine`、`byog.Helper`、`byog.lab5`、`byog.SaveDemo`

* 所有 Core 文件共享 `package byog.Core;`，类名直接引用无需 import

* 跨包依赖：大量文件引用 `byog.TileEngine.TETile`、`byog.TileEngine.Tileset`、`byog.lab5.Position`、`byog.Helper.*`

## 目标包结构

```
byog/
  Core/          Game.java, Main.java                       # 游戏入口
  Entity/        Entity.java, Player.java, Enemy.java,
                 EntityManager.java, EntityState.java
  AI/            EnemyBrain.java, RuleBasedBrain.java,
                 ClassicalPlanner.java, StrategicIntent.java,
                 BFSPathfinder.java, GameStateSnapshot.java
  Action/        Action.java, MoveAction.java,
                 AttackAction.java, ActionQueue.java
  WorldGen/      WorldGenerator.java, WorldGenResult.java,
                 Room.java, SquareRoom.java, Hall.java,
                 RoomGraph.java
  Perception/    ObservationEnvelope.java, VisibleEntity.java,
                 HeardEvent.java
  IO/            SaveLoadManager.java, GameSaveData.java,
                 GameConfig.java
  Trace/         AgentTrace.java
  Common/        Direction.java, Difficulty.java,
                 RandomUtils.java
  Test/          Phase0TestSuite.java, Phase0EncounterTest.java,
                 Phase0EncounterTestHelper.java,
                 EnemyCollisionTest.java,
                 Phase0EncounterHarness.java,
                 Phase0BaselineMain.java, Phase0DemoMain.java,
                 MathTest.java
  TileEngine/    (不变)
  Helper/        (不变)
  lab5/          (不变)
  SaveDemo/      (不变)
```

## 包间依赖关系

```
Common ← (无内部依赖，叶节点)
Entity ← Common (RandomUtils)
Action ← Entity, Common
AI ← Entity, Action, Common
WorldGen ← Common
Perception ← Entity
IO ← Entity, Common
Trace ← (仅依赖 lab5.Position)
Core ← Entity, AI, Action, WorldGen, Perception, IO, Common, Trace
Test ← 全部
```

**已知的包级循环**：`Entity ↔ Action`

* Enemy (Entity) → ActionQueue, EnemyBrain, StrategicIntent (Action, AI)

* MoveAction, AttackAction (Action) → EntityManager, Enemy, Direction (Entity, Common)

* Java 允许包级循环，不影响编译。如需消除，可将 MoveAction/AttackAction 中对 EntityManager 的依赖抽接口，但不属于本次重构范围。

## 逐文件变更清单

每个文件的变更包括：① 更新 `package` 声明 ② 新增跨包子包的 import ③ 保留原有外部包 import 不变。

### Step 1: 更新 AGENTS.md

移除 CS61B autograder 约束，标注包拆分已完成。

### Step 2: Common 包（3 个文件，零内部依赖）

| 文件                 | 新 package     | 新增 import |
| ------------------ | ------------- | --------- |
| `Direction.java`   | `byog.Common` | 无         |
| `Difficulty.java`  | `byog.Common` | 无         |
| `RandomUtils.java` | `byog.Common` | 无         |

### Step 3: Entity 包（5 个文件）

| 文件                   | 新 package     | 新增 import（除已有外部包）                                                                                                                                                                                                                       |
| -------------------- | ------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Entity.java`        | `byog.Entity` | `import byog.Common.RandomUtils;`                                                                                                                                                                                                       |
| `EntityState.java`   | `byog.Entity` | 无                                                                                                                                                                                                                                       |
| `Player.java`        | `byog.Entity` | `import byog.Entity.Entity;` (extends)                                                                                                                                                                                                  |
| `Enemy.java`         | `byog.Entity` | `import byog.Entity.Entity;`, `import byog.Action.ActionQueue;`, `import byog.AI.EnemyBrain;`, `import byog.AI.StrategicIntent;`, `import byog.AI.RuleBasedBrain;`, `import byog.AI.ClassicalPlanner;`, `import byog.Common.Direction;` |
| `EntityManager.java` | `byog.Entity` | `import byog.Entity.Entity;`, `import byog.Entity.Enemy;`, `import byog.Entity.Player;`                                                                                                                                                 |

### Step 4: AI 包（6 个文件）

| 文件                       | 新 package | 新增 import                                                                                                                                                                                         |
| ------------------------ | --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `EnemyBrain.java`        | `byog.AI` | `import byog.Entity.Entity;` (如果需要引用)                                                                                                                                                             |
| `StrategicIntent.java`   | `byog.AI` | 无                                                                                                                                                                                                 |
| `GameStateSnapshot.java` | `byog.AI` | 无                                                                                                                                                                                                 |
| `RuleBasedBrain.java`    | `byog.AI` | `import byog.AI.EnemyBrain;`, `import byog.AI.StrategicIntent;`, `import byog.Action.Action;`, `import byog.Action.MoveAction;`, `import byog.Entity.Entity;`, `import byog.Common.Direction;`    |
| `ClassicalPlanner.java`  | `byog.AI` | `import byog.AI.StrategicIntent;`, `import byog.AI.BFSPathfinder;`, `import byog.Action.MoveAction;`, `import byog.Action.Action;`, `import byog.Common.Direction;`, `import byog.Entity.Entity;` |
| `BFSPathfinder.java`     | `byog.AI` | 无                                                                                                                                                                                                 |

### Step 5: Action 包（4 个文件）

| 文件                  | 新 package     | 新增 import                                                                                                                                                          |
| ------------------- | ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `Action.java`       | `byog.Action` | 无                                                                                                                                                                  |
| `MoveAction.java`   | `byog.Action` | `import byog.Action.Action;`, `import byog.Common.Direction;`, `import byog.Entity.EntityManager;`, `import byog.Entity.Entity;`                                   |
| `AttackAction.java` | `byog.Action` | `import byog.Action.Action;`, `import byog.Common.Direction;`, `import byog.Common.RandomUtils;`, `import byog.Entity.EntityManager;`, `import byog.Entity.Enemy;` |
| `ActionQueue.java`  | `byog.Action` | `import byog.Action.Action;`                                                                                                                                       |

### Step 6: WorldGen 包（6 个文件）

| 文件                    | 新 package       | 新增 import                                                                                                                                                                                                                                      |
| --------------------- | --------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Room.java`           | `byog.WorldGen` | 无（接口）                                                                                                                                                                                                                                          |
| `SquareRoom.java`     | `byog.WorldGen` | `import byog.WorldGen.Room;`                                                                                                                                                                                                                   |
| `Hall.java`           | `byog.WorldGen` | `import byog.WorldGen.Room;`, `import byog.WorldGen.SquareRoom;`, `import byog.Common.RandomUtils;`                                                                                                                                            |
| `RoomGraph.java`      | `byog.WorldGen` | `import byog.WorldGen.Room;`, `import byog.WorldGen.SquareRoom;`                                                                                                                                                                               |
| `WorldGenerator.java` | `byog.WorldGen` | `import byog.WorldGen.Room;`, `import byog.WorldGen.SquareRoom;`, `import byog.WorldGen.Hall;`, `import byog.WorldGen.RoomGraph;`, `import byog.WorldGen.WorldGenResult;`, `import byog.Common.RandomUtils;`, `import byog.Common.Difficulty;` |
| `WorldGenResult.java` | `byog.WorldGen` | `import byog.WorldGen.Room;`                                                                                                                                                                                                                   |

### Step 7: Perception 包（3 个文件）

| 文件                         | 新 package         | 新增 import                                                                     |
| -------------------------- | ----------------- | ----------------------------------------------------------------------------- |
| `VisibleEntity.java`       | `byog.Perception` | 无                                                                             |
| `HeardEvent.java`          | `byog.Perception` | 无                                                                             |
| `ObservationEnvelope.java` | `byog.Perception` | `import byog.Perception.VisibleEntity;`, `import byog.Perception.HeardEvent;` |

### Step 8: IO 包（3 个文件）

| 文件                     | 新 package | 新增 import                        |
| ---------------------- | --------- | -------------------------------- |
| `GameConfig.java`      | `byog.IO` | `import byog.Common.Difficulty;` |
| `SaveLoadManager.java` | `byog.IO` | 无（静态工具类）                         |
| `GameSaveData.java`    | `byog.IO` | 无（纯 DTO）                         |

### Step 9: Trace 包（1 个文件）

| 文件                | 新 package    | 新增 import |
| ----------------- | ------------ | --------- |
| `AgentTrace.java` | `byog.Trace` | 无         |

### Step 10: Core 包（2 个文件，精简为入口）

| 文件          | 新 package   | 新增 import（全部跨包 import）                                                                                                                                                                                          |
| ----------- | ----------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Game.java` | `byog.Core` | `import byog.Entity.*;`, `import byog.AI.*;`, `import byog.Action.*;`, `import byog.WorldGen.*;`, `import byog.Perception.*;`, `import byog.IO.*;`, `import byog.Common.*;`, `import byog.Trace.*;` (按实际使用精确列出) |
| `Main.java` | `byog.Core` | `import byog.Common.Direction;`, `import byog.Entity.Entity;` 等                                                                                                                                                 |

> Game.java 和 Main.java 的 package 保持 `byog.Core` 不变，但内容大幅精简——只保留游戏启动逻辑。

### Step 11: Test 包（8 个文件）

| 文件                               | 新 package   | 新增 import                                                                                                                                           |
| -------------------------------- | ----------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Phase0TestSuite.java`           | `byog.Test` | `import byog.Test.*;` (JUnit Suite)                                                                                                                 |
| `Phase0EncounterTest.java`       | `byog.Test` | `import byog.Test.Phase0EncounterHarness;`, `import byog.Test.Phase0EncounterTestHelper;`, `import byog.Entity.*;`, `import byog.Trace.AgentTrace;` |
| `Phase0EncounterTestHelper.java` | `byog.Test` | `import byog.Test.Phase0EncounterHarness;`                                                                                                          |
| `EnemyCollisionTest.java`        | `byog.Test` | `import byog.Entity.*;`                                                                                                                             |
| `Phase0EncounterHarness.java`    | `byog.Test` | `import byog.Entity.*;`, `import byog.Trace.AgentTrace;`                                                                                            |
| `Phase0BaselineMain.java`        | `byog.Test` | `import byog.Test.Phase0EncounterHarness;`                                                                                                          |
| `Phase0DemoMain.java`            | `byog.Test` | `import byog.Test.Phase0EncounterHarness;`                                                                                                          |
| `MathTest.java`                  | `byog.Test` | `import byog.Common.RandomUtils;`, `import byog.Entity.*;`                                                                                          |

### Step 12: 更新编译命令

拆分后源码分布在更多包中，编译命令需覆盖新目录：

```powershell
javac -encoding UTF-8 -cp "D:\Courses\cs61b\cs61b-std\library-sp18\javalib\*" -d out/production/proj2 @sources.txt
```

其中 `sources.txt` 或直接指定所有目录：

```powershell
javac -encoding UTF-8 -cp "D:\Courses\cs61b\cs61b-std\library-sp18\javalib\*" -d out/production/proj2 byog/Core/*.java byog/Entity/*.java byog/AI/*.java byog/Action/*.java byog/WorldGen/*.java byog/Perception/*.java byog/IO/*.java byog/Trace/*.java byog/Common/*.java byog/Test/*.java byog/TileEngine/*.java byog/Helper/*.java byog/lab5/*.java byog/SaveDemo/*.java
```

### Step 13: 更新项目记忆

更新 `project_memory.md` 中的编译命令。

## 假设与决策

1. **不移动** **`byog.lab5.Position`**：28 个文件依赖它，但它是 CS61B lab 产物，保持原位置。
2. **不处理** **`byog.SaveDemo`**：SaveDemo 包独立于 Core，未受影响。
3. **接受 Entity ↔ Action 包级循环**：Java 允许，且消除循环需要接口抽象，超出本次重构范围。
4. **PerceptionSystem.java 尚未存在**：Phase 1 实现时直接放入 `byog.Perception` 包。
5. **Test 包独立**：测试文件放入 `byog.Test`，与源码分离，JUnit Suite 引用路径相应更新。

## 验证步骤

1. 编译通过（IDEA 或命令行）
2. Phase 0 测试全绿（Builder 要求时运行）
3. 确认 IDE 中无红色波浪线（即所有 import 正确）

## 风险

* **遗漏的隐式引用**：同包内的类名引用变为跨包后需加 import，IDE 会标红，逐文件修复即可。

* **Phase 1 新文件**：`PerceptionSystem.java` 创建时直接放入 `byog.Perception`。

* **git 历史**：文件移动会导致 git 视为删除+新增。建议用 `git mv` 保留历史。

