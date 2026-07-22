# 移除冗余 List，只保留 Map 作为实体容器

## 分析

当前代码同时维护 `List<Entity> entities` 和 `Map<Position, Entity> entityPositions`，两者始终包含相同的实体，是典型的冗余。`HashMap<Position, Entity>` 可以完全替代 List：

- `Map.values()` 可用于所有遍历（AI tick、渲染、死实体清理）
- `Map.get(p)` / `Map.containsKey(p)` 提供 O(1) 碰撞检测
- 无需同步维护两个数据结构的 add/remove/移动更新

## 具体改动

### Game.java — 移除 `entities` 字段，所有地方改用 `entityPositions`

| # | 位置 | 原来 | 改为 |
|---|------|------|------|
| 1 | 字段声明 L22 | `private List<Entity> entities;` | 删除 |
| 2 | `playWithKeyboard()` L38 | `entities = new ArrayList<>();` | 删除 |
| 3 | `playWithInputString()` L205 | `entities = new ArrayList<>();` | 删除 |
| 4 | `loadGameState()` L412 | `entities = new ArrayList<>();` | 删除 |
| 5 | AI tick L56 | `for (Entity e : entities)` | `for (Entity e : entityPositions.values())` |
| 6 | `buildActiveFrame()` L296 | `for (Entity e : entities)` | `for (Entity e : entityPositions.values())` |
| 7 | `addEntity()` L306-309 | 同时改 entities + map | 只改 map: `entityPositions.put(e.getPosition(), e)` |
| 8 | `removeEntity()` L312-315 | 同时改 entities + map | 只改 map: `entityPositions.remove(e.getPosition())` |
| 9 | `removeDeadEntities()` L318-325 | 遍历 entities 收集死的→删两边 | 遍历 `values()` 收集死的位置→按 key 删除 |

### 改动前后对比（removeDeadEntities）

```java
// 原来（冗余）
private void removeDeadEntities() {
    for (Entity e : entities) {
        if (!e.isAlive()) {
            entityPositions.remove(e.getPosition());
        }
    }
    entities.removeIf(e -> !e.isAlive());
}

// 改为（单源）
private void removeDeadEntities() {
    List<Position> toRemove = new ArrayList<>();
    for (Entity e : entityPositions.values()) {
        if (!e.isAlive()) {
            toRemove.add(e.getPosition());
        }
    }
    for (Position p : toRemove) {
        entityPositions.remove(p);
    }
}
```

### 改动前后对比（addEntity / removeEntity）

```java
// 原来
private void addEntity(Entity e) {
    entities.add(e);
    entityPositions.put(e.getPosition(), e);
}

private void removeEntity(Entity e) {
    entities.remove(e);
    entityPositions.remove(e.getPosition());
}

// 改为
private void addEntity(Entity e) {
    entityPositions.put(e.getPosition(), e);
}

private void removeEntity(Entity e) {
    entityPositions.remove(e.getPosition());
}
```

## 不变的地方

- `movePlayer()` 中 `entityPositions.remove(oldPos)` / `entityPositions.put(newPos, player)` 不变
- AI tick 中敌人移动后的位置索引更新不变
- `isPlayerColliding()` 不变
- `player` 独立字段不变

## 文件清单

- 仅修改 [Game.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Game.java)
- `List` import 不再需要，`ArrayList` import 可能不再需要（视 compilation 而定）

## 验证

- javac 编译通过
- 行为等价：遍历 `HashMap.values()` 与遍历 `ArrayList` 对当前逻辑无差异（AI tick、渲染都不依赖遍历顺序）
