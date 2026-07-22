# 2.3 RuleBasedBrain 实现计划

## 概述

创建 `RuleBasedBrain`，实现 `EnemyBrain` 接口。这是保底 AI——纯手写规则，不依赖任何外部 API。当阶段四 LLM 不可用时，敌人降级为规则 AI 继续运作。

## 当前状态分析

### 已就绪的基础设施
| 文件 | 关键内容 |
|------|----------|
| [EnemyBrain.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/EnemyBrain.java) | 接口，`StrategicIntent think(GameStateSnapshot)` |
| [GameStateSnapshot.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/GameStateSnapshot.java) | `getWorld()`, `getPlayerPosition()`, `getEnemyPosition()` |
| [StrategicIntent.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/StrategicIntent.java) | `Goal.CHASE` / `Goal.PATROL`，构造器 `(Goal, Strategy, Position)` |
| [Enemy.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Enemy.java) | `sightRange = 7`（默认），`distance()` 静态方法已实现曼哈顿距离 |
| [Position.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/lab5/Position.java) | `x`, `y` 公有字段 |

### 需要新建的文件
- `RuleBasedBrain.java` — 单一文件，实现 `EnemyBrain` 接口

## 拟议变更

### 新建：`byog/Core/RuleBasedBrain.java`

**类签名**：`public class RuleBasedBrain implements EnemyBrain`

**核心方法**：`think(GameStateSnapshot state)` → `StrategicIntent`

**决策逻辑**（两层判断）：

1. 从快照获取敌人位置、玩家位置、世界数组
2. 计算曼哈顿距离：`|ex - px| + |ey - py|`
3. 如果距离 ≤ 14（= sightRange × 2，对应 7 格半径）→ 玩家在视野内
   - 返回 `new StrategicIntent(Goal.CHASE, Strategy.CHASE, playerPos)`
4. 否则玩家在视野外
   - 返回 `new StrategicIntent(Goal.PATROL, Strategy.PATROL, playerPos)`
   - 阶段二无 EnemyMemory，"最后已知位置"直接使用当前玩家位置

**为什么距离阈值是 14 而不是 7**：Enemy 的 `sightRange = 7` 表示 7 格半径。在方形网格中，7 格半径最远对角线的曼哈顿距离为 14（因为走 7 步 x + 7 步 y）。使用 `sightRange * 2` 与实际字段绑定，避免魔数。

**代码结构**：
- 类级简短注释
- 实现 `think()` 方法，~10 行逻辑
- 不拆分私有方法（足够简单）

## 假设与决策

1. **视野检测简化**：阶段二用曼哈顿距离，不判断视线遮挡（能"看穿"墙壁）。符合指南"优点是实现简单，缺点是穿墙感知不够真实"的说明。
2. **PATROL 目标**：阶段二"玩家最后已知位置"直接用当前玩家位置。无 `EnemyMemory`，Brain 每轮拿到的是最新快照，这个做法自然合理。
3. **不修改现有文件**：2.3 只创建新文件。Enemy 的改造在 2.5 进行。

## 验证步骤

1. 编译：`javac -cp ".;d:\Courses\cs61b\cs61b-std\library-sp18\javalib\stdlib-package.jar" byog\Core\RuleBasedBrain.java`
2. 无编译错误，`.class` 文件生成
3. 后续 2.5 集成到 Enemy 时通过实际游戏验证：玩家靠近 7 格内 → 敌人追击，跑远 → 切换巡逻
