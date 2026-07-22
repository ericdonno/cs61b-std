# 2.4 ClassicalPlanner 实现计划

## 概述

创建 `ClassicalPlanner`，AI 规划层的核心——把"战略意图"翻译成具体的 `MoveAction` 序列。Brain 说"我要去 (x,y)"，ClassicalPlanner 负责算出"怎么走"。

## 当前状态分析

### 已就绪的关键依赖

| 文件 | 关键 API | 说明 |
|------|----------|------|
| [BFSPathfinder.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/BFSPathfinder.java) | `findPath(start, goal, world)` → `List<Position>` | 不含起点的有序路径 |
| [MoveAction.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/MoveAction.java) | 构造器 `(Direction, EntityManager)` | **已支持 `direction == null`**（L25-27，返回 SUCCESS）——即"等待"动作 |
| [ActionQueue.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/ActionQueue.java) | `enqueue(Action)`, `enqueueAll(List)` | |
| [Direction.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Direction.java) | `fromDelta(dx, dy)` → Direction | 路径中相邻点方向反推 |
| [StrategicIntent.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/StrategicIntent.java) | `getTargetPosition()`, `getStrategy()` | |
| [Enemy.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Enemy.java) | `getRandom()` → `Random` | 用于不可达时生成随机方向占位 |

### 需要新建的文件
- `ClassicalPlanner.java`

## 拟议变更

### 新建：`byog/Core/ClassicalPlanner.java`

**类签名**：纯静态工具类（无状态），`public class ClassicalPlanner`

**核心方法签名**：

```java
public static List<MoveAction> translate(StrategicIntent intent, Position enemyPos,
                                          TETile[][] world, EntityManager entityMgr, Random random)
```

**参数说明**：
- `intent`：Brain 产出的战略意图
- `enemyPos`：敌人当前位置
- `world`：世界瓦片数组，传给 BFSPathfinder
- `entityMgr`：传给 MoveAction 构造器（MoveAction 需要它做碰撞检测）
- `random`：不可达时生成随机方向占位动作（防止敌人卡死）

**翻译流程**：

1. **取目标位置**：`intent.getTargetPosition()`
2. **BFS 寻路**：`BFSPathfinder.findPath(enemyPos, targetPos, world)` → `List<Position> path`
3. **路径 → MoveAction**：
   - 从 enemyPos 开始，遍历 path 中每个位置
   - 每个相邻对 `(prev, cur)` 计算 `dx = cur.x - prev.x`, `dy = cur.y - prev.y`
   - 用 `Direction.fromDelta(dx, dy)` 得方向 → `new MoveAction(direction, entityMgr)`
   - 加入结果列表
4. **Strategy 后缀行为**（路径终点到达后的行为）：
   - `AMBUSH`：追加 3 个 `new MoveAction(null, entityMgr)`（等待/埋伏）
   - `GUARD`：追加 1 个 `new MoveAction(null, entityMgr)`
   - `CHASE` / `PATROL` / 其他：不追加后缀
5. **不可达处理**：如果 BFS 返回空路径 → 插入一个随机方向的 MoveAction 作为占位，防止队列彻底为空导致敌人原地卡死

**代码结构**：
- 类级简短注释
- 一个 `public static` 方法 `translate`，所有逻辑内联
- ~25 行核心逻辑

## 假设与决策

1. **EntityManager 参数**：指南未在方法签名中列出，但 `MoveAction` 构造器需要它做碰撞检测。作为依赖注入传入，符合现有代码风格（MoveAction 已使用此模式）。

2. **Random 参数**：指南说不可达时"插入一个任意方向的 MoveAction 作为占位"。Enemy 已有 `random` 字段，从 Enemy 传入保证确定性（同一 seed 同一行为）。

3. **路径遍历起点**：BFSPathfinder 返回不包含起点的路径。从 enemyPos 开始，第一个元素是紧邻起点的第一步。所以遍历时 `prev = enemyPos`，`cur = path[0]`，然后依次前进。

4. **"等待"动作**：`MoveAction` 已内置 `direction == null → SUCCESS` 的逻辑（L25-27），无需额外修改。

5. **不修改现有文件**：2.4 只创建新文件。Enemy 的改造在 2.5 进行。

## 验证步骤

1. 编译：`javac -cp ".;d:\Courses\cs61b\cs61b-std\library-sp18\javalib\stdlib-package.jar" byog\Core\ClassicalPlanner.java`
2. 无编译错误，`.class` 文件生成
3. 后续 2.5 集成到 Enemy 时通过实际游戏验证：CHASE 意图 → 敌人沿 BFS 最短路径追击玩家
