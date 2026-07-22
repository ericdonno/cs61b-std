# 修复 ConcurrentModificationException

## 根因

[Game.java#L56](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Game.java#L56)

```java
for (Entity e : entityMgr.getAllEntities()) {  // ← 迭代 HashMap 的 live view
    if (e instanceof Enemy enemy && e.isAlive()) {
        enemy.updateAI(world, entityMgr);       // → MoveAction.execute()
    }                                           //   → entityMgr.updatePosition()
}                                               //     → HashMap.remove() + put()
                                                //     → CME!
```

`getAllEntities()` 返回 `positionIndex.values()`，是 HashMap 的 **live view**。迭代期间 `MoveAction` 调用 `updatePosition()` 修改了同一个 HashMap，触发 CME。

## 修复

[EntityManager.java#L61-L64](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/EntityManager.java#L61-L64)

`getAllEntities()` 改为返回快照（`new ArrayList<>(...)`），所有调用方自动安全：

```java
/** 获取所有活实体的快照（安全迭代，不受当前位置索引变更影响）。 */
public Collection<Entity> getAllEntities() {
    return new ArrayList<>(positionIndex.values());
}
```

每次调用多一次 O(E) 的 ArrayList 构造，E ≈ 5，完全可忽略。

## 改动范围

| 文件 | 改动 |
|---|---|
| `EntityManager.java` | `getAllEntities()` 返回 `new ArrayList<>(positionIndex.values())` |

其余文件无需改动——所有调用方（Game 循环、渲染、存档）自动获得安全快照。
