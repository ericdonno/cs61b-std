# 修复：敌人可以走到玩家身上

## 问题概述

敌人（Enemy）在执行移动时，可能走到与玩家（或其他敌人）相同的位置，且没有任何碰撞/攻击效果。

## 根因分析

`EntityManager` 使用**延迟索引重建**设计：空间索引 `positionIndex` 只在帧末通过 `flushPendingChanges()` 统一重建。

每帧的游戏循环执行顺序（[Game.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Game.java) 第 47-64 行）：

```
1. 处理玩家输入 → 可能调用 player.move() → player.position 改变
2. 遍历所有 Enemy → enemy.updateAI() → 执行 MoveAction → enemy.position 改变
3. flushPendingChanges() → 根据最新 position 重建 positionIndex
```

但步骤 2 中所有敌人的 `canMoveTo()` 调用，**读取的是步骤 1 开始前就存在的旧 `positionIndex`**，完全不知道步骤 1 中玩家（以及同帧内先执行 AI 的敌人）已经移动到了新位置。

### 具体场景

初始状态：玩家在 (5,5)，敌人在 (5,4)

1. 玩家按下 `w`（上），移动到 (5,6) — `player.position` 变为 (5,6)
2. 此时 `positionIndex` 仍显示玩家在 (5,5)
3. 敌人 AI tick，随机到向上的方向，目标 (5,6)
4. `canMoveTo(enemy, (5,6), world)` 调用 `positionIndex.get((5,6))` → `null` → **允许通过**
5. 敌人 `position` 设为 (5,6)，与玩家重合
6. `flushPendingChanges` 重建索引，`HashMap` 中同 key (5,6) 先后 put player 和 enemy → 一个被覆盖丢失

## 涉及文件

| 文件 | 修改内容 |
|------|----------|
| [EntityManager.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/EntityManager.java) | 添加"帧内已占用"集合 + `canMoveTo` 中检查 |
| [Player.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Player.java) | `move()` 成功后通知 EntityManager 占用新位置 |
| [MoveAction.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/MoveAction.java) | `execute()` 成功后通知 EntityManager 占用新位置 |

## 修改方案

### 1. EntityManager — 添加帧内占用追踪

**修改内容**：

- 新增字段 `private Set<Position> frameOccupied = new HashSet<>();`
- `canMoveTo()` 中追加检查：如果 `frameOccupied` 中已有该位置，拒绝移动
- 新增方法 `claimPosition(Position p)` 供外部注册占用
- `flushPendingChanges()` 末尾清空 `frameOccupied`

**canMoveTo 修改后**：

```java
public boolean canMoveTo(Entity entity, Position target, TETile[][] world) {
    if (!Entity.canStandOn(target, world)) {
        return false;
    }
    if (frameOccupied.contains(target)) {
        return false;  // 同帧内已被其他实体占用
    }
    Entity occupant = positionIndex.get(target);
    return occupant == null || occupant == entity || !occupant.isAlive();
}
```

### 2. Player.move() — 移动成功后注册占用

在 `this.position = newPos;` 之后增加一行：

```java
entityMgr.claimPosition(newPos);
```

### 3. MoveAction.execute() — 移动成功后注册占用

在 `entity.setPosition(newPos);` 之后增加一行：

```java
entityMgr.claimPosition(newPos);
```

## 设计说明

- 不在帧中直接修改 `positionIndex`（避免 `ConcurrentModificationException`）
- `frameOccupied` 只在帧内"新增"被占用的位置，不处理旧位置的释放（`positionIndex` 中仍显示实体在原位，会阻止其他实体进入该位置，属于安全的"误拒"而非危险的"误放"）
- 帧末 `flushPendingChanges` 统一清空，不影响下一帧

## 验证步骤

1. 启动游戏，`n` → 输入种子 → `s` 进入游戏
2. 主动走向敌人，观察敌人不会走上玩家所在位置（应被阻挡或在相邻位置）
3. 观察多个敌人之间也不会重叠
4. 正常保存（`:q`）和加载（`l`），确认不受影响
