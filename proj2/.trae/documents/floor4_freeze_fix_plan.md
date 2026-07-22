# 第三关进第四层卡死修复计划

## 现象

游戏进入第 4 层（floor 3→4）时，程序卡死，无响应。

## 根因分析

卡死位置在 [Enemy.java#L136-L138](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Enemy.java#L136-L138) 的 while 循环：

```java
while (MathHelper.manhattanDistance(enemy.getPosition(), playerPos) < 5) {
    Entity.initEntity(enemy, world, seed + "_pos_" + i + "_retry");
}
```

**为什么是死循环**：

1. [Entity.initEntity()](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Entity.java#L72) 使用 `new Random(seed.hashCode())` 生成位置，是**完全确定性**的 — 相同 seed 永远产出相同位置。

2. retry 的 seed 是 `seed + "_pos_" + i + "_retry"` — **每次循环迭代完全相同**。

3. 所以每次 `initEntity` 都把敌人放在同一个位置。如果那个位置恰好距玩家 < 5 曼哈顿距离，while 条件永远为 true → 死循环。

**为什么第三层才触发**：

- 每层玩家位置不同（随机放置），敌人生成位置由 seed 决定（确定性的）。当某层玩家的随机位置碰巧与某个敌人的 retry 位置 < 5 时，就触发死循环。
- 楼层越高，敌人越多（`extraCount = floorLevel - 1`），触发概率越大。

## 修复方案

让 retry seed 包含递增计数器，确保每次迭代产生不同位置：

```java
int retryCount = 0;
while (MathHelper.manhattanDistance(enemy.getPosition(), playerPos) < 5) {
    Entity.initEntity(enemy, world, seed + "_pos_" + i + "_retry_" + retryCount);
    retryCount++;
}
```

## 需要修改的文件

| 文件 | 修改内容 |
|------|----------|
| [Enemy.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Enemy.java#L136-L138) | retry seed 加入递增计数器 |

## 验证

修改后编译通过即可，该 bug 是确定性的逻辑错误，不存在运行时不确定性。
