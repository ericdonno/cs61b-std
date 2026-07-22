# 2.2 StrategicIntent / EnemyBrain / GameStateSnapshot 实现计划

## 概述

创建 AI 决策层的三个基础文件：`StrategicIntent`（战略意图数据类）、`EnemyBrain`（AI 大脑接口）、`GameStateSnapshot`（游戏状态快照，阶段二简化版）。

这三个文件构成了阶段二 AI 决策层的"骨架"——只定义结构和接口，暂不实现具体逻辑（那是 2.3 RuleBasedBrain 和 2.4 ClassicalPlanner 的事）。

## 当前状态分析

### 现有相关文件
| 文件 | 状态 |
|------|------|
| [Enemy.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Enemy.java) | 已实现阶段一功能：随机漫步、ActionQueue、碰撞检测 |
| [BFSPathfinder.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/BFSPathfinder.java) | 2.1 已完成：`findPath(start, goal, world)` |
| [Direction.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Direction.java) | 已含 `dx`/`dy` 和 `fromDelta()` |
| [Position.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/lab5/Position.java) | 已覆写 `equals()` / `hashCode()` |
| [Player.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Player.java) | 含 `getPosition()`, `getHp()` 方法 |
| [Entity.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Entity.java) | 含 `getPosition()`, `getTile()`, `isAlive()` 方法 |

### 需要新建的文件
- `StrategicIntent.java` — 纯数据类（字段 + 构造器 + getter）
- `EnemyBrain.java` — 接口（单方法 `think`）
- `GameStateSnapshot.java` — 简化版不可变快照（3 字段）

## 拟议变更

### 文件 1：`byog/Core/StrategicIntent.java`（新建）

**角色**：纯数据类，描述 AI 大脑做出的一个高层决策（"我要做什么"）。

**内部枚举定义**：

```
Goal 枚举（"我要达成什么"）：
  INTERCEPT_PLAYER, GUARD, PATROL, CHASE, AMBUSH, RETREAT
  
Strategy 枚举（"我用什么方式达成"）：
  INTERCEPT, AMBUSH, PATROL, GUARD, CHASE
```

**字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `goal` | `Goal` | AI 想要达成的目标 |
| `strategy` | `Strategy` | 实现目标的方式 |
| `targetPosition` | `Position` | 目标位置（要去哪） |
| `confidence` | `double` | AI 信心值 0.0~1.0，阶段五使用 |
| `targetRoom` | `int` | 目标房间编号，暂未使用，默认 -1 |

**方法**：构造器 + 所有字段的 getter。无业务逻辑。

**设计决策**：Goal 和 Strategy 分开。Goal 表达"要达成什么"（What），Strategy 表达"用什么方式"（How）。ClassicalPlanner 会根据 Strategy 决定到达目标后的具体行为。

---

### 文件 2：`byog/Core/EnemyBrain.java`（新建）

**角色**：AI 决策的统一接口（策略模式）。

**方法签名**：`StrategicIntent think(GameStateSnapshot state)`

输入是当前游戏状态的快照，输出是一个战略意图。

**为什么用接口**：策略模式。Enemy 只依赖 `EnemyBrain` 接口，不关心具体实现。阶段二用 `RuleBasedBrain`（2.3），阶段四换成 `LLMBrain`（4.4），`Enemy` 的代码一行都不需要改。

---

### 文件 3：`byog/Core/GameStateSnapshot.java`（新建）

**角色**：某时刻游戏世界的"截屏"。阶段二做简化版（3 个字段），阶段四升级为完整版。

**阶段二字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `world` | `TETile[][]` | 世界地图数组引用 |
| `playerPosition` | `Position` | 玩家位置 |
| `enemyPosition` | `Position` | 敌人自身位置 |

**方法**：构造器 + 所有字段的 getter。

**设计决策**：
- 阶段二够 `RuleBasedBrain` 用了（需要世界地图判断可通行性、需要玩家位置判断距离、需要自身位置计算路径）
- 阶段四会新增 `enemyHp`, `currentRoom`, `turnNumber`, `timestamp`, `memorySummary` 等字段
- 为兼容阶段四升级，保留简化构造函数作为重载

## 假设与决策

1. **StrategicIntent 的 Goal/Strategy 枚举是内嵌还是独立文件**：内嵌在 StrategicIntent 类中作为 `public static` 内部枚举。指南将它们列在 StrategicIntent 下，放在一起更内聚。

2. **GameStateSnapshot 的 world 字段**：阶段二直接存 `TETile[][]` 引用（非副本）。BFSPathfinder 和 isPassable 都需要它。阶段四升级时可能需要考虑线程安全（不可变快照做 world 深拷贝），但当前阶段不需要。

3. **confidence 默认值**：构造器中 `confidence` 默认 0.5（中等信心），阶段二规则 AI 不实际使用此字段。

4. **targetRoom 默认值**：`-1` 表示"未指定"，阶段二不使用房间系统。

5. **不修改 Enemy.java**：2.2 只创建新文件。Enemy 的改造在 2.5 进行。

## 验证步骤

1. 编译：`javac -cp ".;d:\Courses\cs61b\cs61b-std\library-sp18\javalib\stdlib-package.jar" byog\Core\StrategicIntent.java byog\Core\EnemyBrain.java byog\Core\GameStateSnapshot.java`
2. 无编译错误，三个 `.class` 文件生成
