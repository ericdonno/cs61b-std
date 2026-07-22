# 碰撞检测 Bug 修复与架构优化计划

## 当前问题分析

### 核心混乱点
碰撞检测分散在三个地方，逻辑不统一：
- `Entity.canMoveTo(p, world)` — 只查地形
- `Game.isPlayerColliding(p)` — 查地形+实体位置（只给 Player 用）
- `MoveAction.execute()` — 只查地形（Enemy 用，完全不管实体碰撞）

结果：Player 有碰撞、Enemy 没有 → 敌人穿人/叠人 → `flushPendingChanges()` 的 HashMap 遇到重复 key 时丢实体。

### 目标
**提供唯一的碰撞检测入口**：`CollisionManager.canMoveTo(entity, target, world)`，同时检查地形和实体占用。Player 和 Enemy 都走同一条路径。

---

## 修改计划

### 第1步：新建 `CollisionManager` 类

创建 `byog/Core/CollisionManager.java`。

**唯一的碰撞检测入口**：
```java
boolean canMoveTo(Entity entity, Position target, TETile[][] world)
```
内部逻辑：
1. 检查地形：`Entity.canMoveTo(target, world)` — 不是墙且不是虚空
2. 检查实体占用：目标位置没有被*另一个*活实体占据

**空间索引管理**（替代原来的 `entityPositions` HashMap）：
- `addEntity(Entity e)` — 添加实体到索引
- `removeEntity(Entity e)` — 从索引移除
- `updatePosition(Entity e, Position oldPos, Position newPos)` — 原子更新，移动前调用 `canMoveTo` 保证不冲突
- `removeDeadEntities()` — 清理死亡实体
- `getAllEntities()` — 返回所有活实体集合（用于渲染/存档遍历）

### 第2步：重构 `MoveAction`

- 构造函数注入 `CollisionManager`
- `execute()` 中调用 `collisionMgr.canMoveTo(entity, newPos, world)` 替代原来的 `Entity.canMoveTo()`
- 移动成功后调用 `collisionMgr.updatePosition()` 同步空间索引

### 第3步：重构 `Enemy.updateAI()`

- 参数改为 `(TETile[][] world, CollisionManager collisionMgr)`
- 构造 `MoveAction` 时传入 `collisionMgr`
- Game 主循环调用时传入 `collisionMgr`

### 第4步：重构 `Game.java`

删除 / 替换：
- `Map<Position, Entity> entityPositions` → `CollisionManager collisionMgr`
- `flushPendingChanges()` → 简化为只 flush `pendingAdd`（位置索引由 `updatePosition` 实时维护）
- `removeDeadEntities()` → 委托给 `collisionMgr`
- `isPlayerColliding()` → 改为调用 `collisionMgr.canMoveTo(player, p, world)`
- `movePlayer()` → 改用 `collisionMgr.canMoveTo` + `collisionMgr.updatePosition`
- 所有 `entityPositions.values()` → `collisionMgr.getAllEntities()`

### 第5步：重构 `Player.move()`

- 移除 `Predicate<Position>` 回调参数
- 改为接收 `(Direction, TETile[][], CollisionManager)`
- 内部调用 `collisionMgr.canMoveTo(this, newPos, world)`

---

## 涉及文件

| 文件 | 操作 |
|---|---|
| `byog/Core/CollisionManager.java` | **新建** |
| `byog/Core/MoveAction.java` | **修改** |
| `byog/Core/Enemy.java` | **修改** |
| `byog/Core/Game.java` | **修改** |
| `byog/Core/Player.java` | **修改** |

## 不变的

`Entity.java`, `Action.java`, `ActionQueue.java`, 所有 TileEngine/Helper/lab 包

---

## 碰撞检测调用路径（重构后）

```
玩家按WASD → Game.movePlayer()
              → collisionMgr.canMoveTo(player, target, world)  ← 唯一入口
              → collisionMgr.updatePosition(player, old, new)

敌人AI更新 → Enemy.updateAI(world, collisionMgr)
              → MoveAction.execute(world, enemy)
                  → collisionMgr.canMoveTo(enemy, target, world)  ← 同一入口
                  → collisionMgr.updatePosition(enemy, old, new)
```
