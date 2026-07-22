# 第三层卡死修复计划（终版）

## 现象

玩家踩上楼梯进入下一层时瞬间卡死。

## 根因分析

卡死位置在 [Enemy.java#L145-L149](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Enemy.java#L145-L149)：

```java
int retryCount = 0;
while (MathHelper.manhattanDistance(enemy.getPosition(), playerPos) < 5) {
    Entity.initEntity(enemy, world, seed + "_pos_" + i + "_retry_" + retryCount);
    retryCount++;
}
```

### 为什么不同 seed 也死循环

关键在于 [Entity.initEntity()](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Entity.java#L75) 的实现：

```java
while (!canStandOn(entity.position, world)) {   // ← 先检查当前位置
    xPos = uniform(random, world.length);        // ← 只有当前位置非法才执行
    yPos = uniform(random, world[0].length);
    entity.position = new Position(xPos, yPos);
}
```

**问题**：第一次 `initEntity` 已经把敌人放在了一个合法地板上（位置 A）。retry 时再次调用 `initEntity`，`canStandOn(A, world)` 返回 `true`（它刚被放在地板上），while 循环体**从不执行**，`Random` 根本没被用到。敌人位置不变，距离不变，循环无限。

```
retry 1: entity 在 A(合法地板) → canStandOn(A) → true → 跳过while体 → entity 仍在 A → 距离仍 < 5
retry 2: entity 仍在 A → canStandOn(A) → true → 跳过while体 → entity 仍在 A → 无限循环！
```

### 为什么第三层才触发

- 与前几层相比，第三层的某个敌人恰好被初始放置在距玩家 < 5 的位置
- 楼层越高敌人数越多（`extraCount = floorLevel - 1`），触发概率增大
- **一旦触发就是必然死循环**，不管 retry 多少次

## 修复方案

在 retry 调用 `initEntity` 之前，先把实体位置设为一个非法位置（如 `(-1, -1)`），强制 `initEntity` 内部真正执行随机放置：

```java
int retryCount = 0;
while (MathHelper.manhattanDistance(enemy.getPosition(), playerPos) < 5) {
    enemy.setPosition(new Position(-1, -1));  // 强制重新随机放置
    Entity.initEntity(enemy, world, seed + "_pos_" + i + "_retry_" + retryCount);
    retryCount++;
}
```

## 需要修改的文件

| 文件 | 修改内容 |
|------|----------|
| [Enemy.java#L145-L149](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Enemy.java#L145-L149) | retry 循环内 `enemy.setPosition(new Position(-1, -1))` 放在 `initEntity` 前 |

## 验证

编译通过即可。`(-1, -1)` 超出世界边界（80×30），`canStandOn` 必定返回 `false`，`initEntity` 的 while 循环必定执行，每次用不同 seed 产生不同位置。
