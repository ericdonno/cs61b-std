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
  IO/            SaveLoadManager, GameSaveData, GameConfig
  Trace/         AgentTrace
  Common/        Direction, Difficulty, RandomUtils
  Test/          Phase0/Phase1 测试与 harness
  TileEngine/    TETile, TERenderer, Tileset
  Helper/        Logger, MathHelper, MatrixGraph, ListGraph, ArrayDeque
  lab5/          Position, HexWorld, RandomWorldDemo
```

### 历史约束（已作废）

`Game.playWithInputString(String)` is a legacy CS61B autograder API that DungeonMind does not use or maintain, so do not build any new runtime, Agent, testing, or integration work around it unless explicitly requested.

---

如果要生成 Phase Spec，请先看 DEVELOPMENT_ROADMAP.md 标题一内容。
