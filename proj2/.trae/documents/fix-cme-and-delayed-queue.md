# 修复 ConcurrentModificationException + 实现延迟增删队列

## 问题

AI tick 遍历 `entityPositions.values()` 时，敌人移动直接调用 `entityPositions.remove()`/`put()`，
抛出 `ConcurrentModificationException`。

根本原因：**在遍历期间修改集合**。之前用 `List` 时 `for-each` 同样会遇到，所以优化 3 的初心就是
用一个通用机制解决——帧中只记录变更，帧末统一执行。

## 方案

核心思想：AI tick 中绝不触碰 `entityPositions`，只调用 `enemy.updateAI(world)`（内部改
`entity.position`）。帧末由 `flushPendingChanges()` 根据 Entity 的最新 position 重建索引。

兼顾优化 3 的目标——新增 `pendingAdd` 列表，供将来 AI 在帧中任意时刻请求生成新实体。

## 改动清单（仅 [Game.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Game.java)）

### 改动 1：新增字段

```java
/** 本帧内请求添加的实体，帧末 flush 时统一加入 */
private List<Entity> pendingAdd = new ArrayList<>();
```

### 改动 2：简化 AI tick 循环

```java
// 原来（抛 CME）
if (currentState == GameState.PLAYING) {
    for (Entity e : entityPositions.values()) {
        if (e instanceof Enemy enemy && e.isAlive()) {
            Position oldPos = enemy.getPosition();
            enemy.updateAI(world);
            Position newPos = enemy.getPosition();
            if (!oldPos.equals(newPos)) {
                entityPositions.remove(oldPos);   // ← CME
                entityPositions.put(newPos, enemy);
            }
        }
    }
    removeDeadEntities();
}

// 改为（遍历不碰 entityPositions）
if (currentState == GameState.PLAYING) {
    for (Entity e : entityPositions.values()) {
        if (e instanceof Enemy enemy && e.isAlive()) {
            enemy.updateAI(world);
        }
    }
    flushPendingChanges();
    removeDeadEntities();
}
```

### 改动 3：新增 `flushPendingChanges()` 和 `requestAddEntity()`

```java
/** 供外部在帧中任意时刻请求添加实体，帧末统一执行 */
public void requestAddEntity(Entity e) {
    pendingAdd.add(e);
}

/** 帧末统一处理：根据 Entity 最新 position 重建位置索引 + 添加待加入实体 */
private void flushPendingChanges() {
    Map<Position, Entity> newIndex = new HashMap<>();
    for (Entity e : entityPositions.values()) {
        newIndex.put(e.getPosition(), e);
    }
    entityPositions.clear();
    entityPositions.putAll(newIndex);

    for (Entity e : pendingAdd) {
        entityPositions.put(e.getPosition(), e);
    }
    pendingAdd.clear();
}
```

### 不变的地方

- `movePlayer()` — 不在遍历期间执行，不需要改
- `addEntity()` / `removeEntity()` — 保留，用于初始化阶段
- `removeDeadEntities()` — 不变
- `buildActiveFrame()` — 不变
- `isPlayerColliding()` — 不变

## 验证

- 编译通过
- 敌人移动不再抛 `ConcurrentModificationException`
- `playWithInputString` 行为等价
