# DungeonMind Agent 架构约束

## 包架构决策

### 包拆分决定
- 已移除 CS61B autograder 对 `byog.Core` 包结构的约束。
- 项目采用模块化包架构，按职责划分：

```
byog/
  Core/          Game.java, Main.java                          # 游戏入口
  Entity/        Entity, Player, Enemy, EntityManager, EntityState
  AI/            EnemyBrain, RuleBasedBrain, ClassicalPlanner,
                 StrategicIntent, BFSPathfinder, GameStateSnapshot
  Action/        Action, MoveAction, AttackAction, ActionQueue
  WorldGen/      WorldGenerator, Room, SquareRoom, Hall, RoomGraph, WorldGenResult
  Perception/    PerceptionSystem, ObservationEnvelope, VisibleEntity, HeardEvent
  Bridge/        AgentProtocol, AgentSession, SocketTransport  # 游戏侧远程 Agent 边界
  IO/            SaveLoadManager, GameSaveData, GameConfig
  Trace/         AgentTrace
  Common/        Direction, Difficulty, RandomUtils
  Test/          Phase0/Phase1 测试与 harness
  TileEngine/    TETile, TERenderer, Tileset
  Helper/        Logger, MathHelper, MatrixGraph, ListGraph, ArrayDeque
  lab5/          Position, HexWorld, RandomWorldDemo
```

外部 Agent runtime 按语言隔离，并共享稳定的 wire contract：

```
agent/
  contract/      跨语言协议说明与兼容性 fixtures
  python/        dungeonmind_agent、CLI 与 Python 测试
  typescript/    未来 TypeScript runtime，与 python/ 平行
```

`byog/Bridge` 只负责 Java 游戏进程一侧的协议、会话与传输；具体语言的
Agent 大脑不得放入 `byog/Bridge` 或根目录 `bridge/`。

### 历史约束（已作废）

`Game.playWithInputString(String)` is a legacy CS61B autograder API that DungeonMind does not use or maintain, so do not build any new runtime, Agent, testing, or integration work around it unless explicitly requested.

## 禁止将开发阶段写入代码

- 编写或修改代码时，严禁出现 `Phase`、`Step`、`阶段 X`、`步骤 X` 等开发路线图编号或同义命名。
- 该禁令覆盖文件名、包名、类名、接口名、方法名、字段名、变量名、测试名、注释、Javadoc、日志、异常消息、运行时字符串和配置键。
- 命名必须表达稳定的领域职责或行为，例如使用 `AiTickLoop`、`IntentArbiter`，不得使用 `Phase2Loop`、`Step23Arbiter` 等阶段性名称。
- Phase/Step 编号只允许出现在开发路线图、Phase Spec、build guide、评审记录和提交说明等项目管理文档中，不得进入任何源代码或代码内文本。
- 修改已有代码时，如果所触及的代码附近存在此类阶段性命名，应在不扩大任务范围的前提下同步改为领域命名；若会破坏兼容性，必须先向用户说明。

## 控制台输出

- 游戏运行期间的所有控制台输出必须通过 `Logger` 类完成。
- 禁止直接使用 `System.out`、`System.err`、`System.out.printf`、`Throwable#printStackTrace` 或其他绕过 `Logger` 的控制台输出方式。

## 代码注释

- 新增的方法应添加简短注释，说明其职责或关键行为。
- 构造方法不需要注释，类本身也不强制添加注释。
- 如果类名或方法名已经准确描述功能，且实现逻辑简单直白，则不需要额外注释，例如只返回字段的 `getXX()` 方法。

## 验证要求

- 如果一次对话中只新增或修改了注释，不执行编译或测试验证。

---

- 如果要生成 Phase Spec，请先看 DEVELOPMENT_ROADMAP.md 标题一内容。
