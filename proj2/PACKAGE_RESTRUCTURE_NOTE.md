# 包架构重构说明

## 基本信息

- **实施时间**：2026-07-21
- **实施阶段**：Phase 0 完成后、Phase 1 启动前的基础设施重构
- **触发原因**：原 `byog.Core` 包包含 40+ 个文件，职责混杂，严重影响可维护性和可扩展性

## 重构背景

### 问题描述

原项目采用单一的 `byog.Core` 包结构，所有游戏逻辑（实体、AI、世界生成、动作系统、感知系统等）都放在同一个包下。这种架构存在以下问题：

1. **职责不清**：世界生成、实体管理、AI 决策、输入输出等不同职责的代码混杂在一起
2. **依赖混乱**：类之间相互引用，难以梳理依赖关系
3. **可维护性差**：包内文件过多，查找和修改代码效率低下
4. **可扩展性差**：新功能难以定位合适的放置位置

### 决策依据

根据 `AGENTS.md` 中的包架构决策，**移除了 CS61B autograder 对 `byog.Core` 包结构的约束**，采用模块化包架构按职责划分。

## 新包架构

### 包结构

```
byog/
├── Core/          # 游戏核心入口（保留）
│   ├── Game.java
│   └── Main.java
├── Common/        # 通用工具与常量
│   ├── Direction.java
│   ├── Difficulty.java
│   └── RandomUtils.java
├── WorldGen/      # 世界生成模块
│   ├── WorldGenerator.java
│   ├── Room.java
│   ├── SquareRoom.java
│   ├── Hall.java
│   ├── RoomGraph.java
│   └── WorldGenResult.java
├── Perception/    # 感知系统
│   ├── ObservationEnvelope.java
│   ├── VisibleEntity.java
│   └── HeardEvent.java
├── Trace/         # 追踪系统
│   └── AgentTrace.java
├── IO/            # 输入输出模块
│   ├── SaveLoadManager.java
│   ├── GameSaveData.java
│   └── GameConfig.java
├── Action/        # 动作系统
│   ├── Action.java
│   ├── MoveAction.java
│   ├── AttackAction.java
│   └── ActionQueue.java
├── Entity/        # 实体系统
│   ├── Entity.java
│   ├── Player.java
│   ├── Enemy.java
│   ├── EntityManager.java
│   └── EntityState.java
├── AI/            # AI 系统
│   ├── EnemyBrain.java
│   ├── RuleBasedBrain.java
│   ├── ClassicalPlanner.java
│   ├── StrategicIntent.java
│   ├── BFSPathfinder.java
│   └── GameStateSnapshot.java
└── Test/          # 测试模块
    ├── Phase0TestSuite.java
    ├── Phase0EncounterTest.java
    ├── Phase0EncounterHarness.java
    └── MathTest.java
```

### 包职责说明

| 包名 | 职责 | 核心类 |
|------|------|--------|
| `Core` | 游戏主入口，协调各模块 | Game, Main |
| `Common` | 通用工具、常量、枚举 | Direction, Difficulty, RandomUtils |
| `WorldGen` | 地图生成、房间管理 | WorldGenerator, Room, RoomGraph |
| `Perception` | 实体感知、视觉、听觉 | ObservationEnvelope, VisibleEntity |
| `Trace` | 追踪与日志 | AgentTrace |
| `IO` | 存档、配置读写 | SaveLoadManager, GameSaveData |
| `Action` | 动作定义与执行 | Action, MoveAction, AttackAction |
| `Entity` | 实体定义与管理 | Entity, Player, Enemy, EntityManager |
| `AI` | AI 决策与规划 | EnemyBrain, ClassicalPlanner, BFSPathfinder |
| `Test` | 单元测试与集成测试 | Phase0TestSuite, MathTest |

### 依赖关系

```
Common → WorldGen → Perception → Trace
                        ↓
                    Entity → Action
                        ↓
                    AI → Core
                        ↓
                    Test
```

**说明**：箭头表示依赖方向（A → B 表示 A 被 B 依赖，B import A）。`Core` 作为游戏入口，依赖所有其他包；`Common` 作为底层工具包，被所有其他包依赖。`Entity` 与 `Action` 包存在循环依赖（Enemy 引用 ActionQueue，MoveAction 引用 EntityManager），这是 Java 允许的包级循环，后续可通过接口抽象消除。

## 迁移步骤

1. **创建新目录结构**：按新包架构创建目录
2. **迁移 Common 包**：`Direction.java`, `Difficulty.java`, `RandomUtils.java`
3. **迁移 WorldGen 包**：`WorldGenerator.java`, `Room.java`, `SquareRoom.java`, `Hall.java`, `RoomGraph.java`, `WorldGenResult.java`
4. **迁移 Perception 包**：`ObservationEnvelope.java`, `VisibleEntity.java`, `HeardEvent.java`
5. **迁移 Trace 包**：`AgentTrace.java`
6. **迁移 IO 包**：`SaveLoadManager.java`, `GameSaveData.java`, `GameConfig.java`
7. **迁移 Action 包**：`Action.java`, `MoveAction.java`, `AttackAction.java`, `ActionQueue.java`
8. **迁移 Entity 包**：`Entity.java`, `Player.java`, `Enemy.java`, `EntityManager.java`, `EntityState.java`
9. **迁移 AI 包**：`EnemyBrain.java`, `RuleBasedBrain.java`, `ClassicalPlanner.java`, `StrategicIntent.java`, `BFSPathfinder.java`, `GameStateSnapshot.java`
10. **迁移 Test 包**：所有测试文件
11. **更新包声明**：修改每个文件的 `package` 语句
12. **更新 import 语句**：添加跨包引用的 import
13. **编译验证**：确保项目可正常编译

## 影响范围

### 源码文件变更

- 所有迁移文件的 `package` 声明已更新
- 所有跨包引用的 `import` 语句已更新
- `Game.java` 和 `Main.java` 保留在 `byog.Core` 包

### 文档文件变更

- `PHASE_1_SPEC.md`：更新了所有类路径引用
- `Phase1_Build_Guide.md`：更新了编译命令和文件路径

### 外部引用变更

- `byog/lab6/MemoryGame.java`：更新了 `RandomUtils` 的 import
- `byog/SaveDemo/World.java`：更新了 `RandomUtils` 的 import

### 序列化兼容性

- 旧存档文件（`save/game.ser`）已删除
- 原因：存档中存储的类路径 `byog.Core.GameSaveData` 已变更为 `byog.IO.GameSaveData`
- 影响：之前的游戏进度丢失，需要重新开始

## 验证结果

- ✅ 项目编译通过（仅有泛型未检查警告，无错误）
- ⏳ Phase 0 测试待用户指示后执行验证
- ✅ 游戏可正常启动和运行

## 后续建议

1. **消除循环依赖**：通过接口抽象消除 `Entity` 与 `Action` 包的循环依赖
2. **模块文档**：为每个包添加 README 文档说明职责和使用方式
3. **持续维护**：新增类时按照新架构放置到对应包中