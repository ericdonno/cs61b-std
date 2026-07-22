# 修复超远距离索敌 Bug

## 一、摘要

敌人无论在距离玩家多远的位置都会 BFS 寻路走向玩家，表现为"超远距离索敌"。根因是 `RuleBasedBrain` 在 PATROL（巡逻）模式时同样将 `targetPosition` 设为玩家位置，导致 PATROL 的实际行为和 CHASE 完全一致。

## 二、现状分析

### 2.1 调用链路

```
Enemy.updateAI() → actionQueue.needRefill()?
  → brain.think(snapshot)     [RuleBasedBrain]
  → ClassicalPlanner.translate() [BFS 寻路到 targetPosition]
  → actionQueue.enqueueAll()
```

### 2.2 根因

`RuleBasedBrain.java:31-41` — CHASE 和 PATROL 分支都设置了相同的 `targetPosition = playerPos`：

```java
if (dist <= sightRange) {
    return new StrategicIntent(..., playerPos);  // CHASE → 玩家
} else {
    return new StrategicIntent(..., playerPos);  // PATROL → 也是玩家！
}
```

`ClassicalPlanner.java:32` 对所有意图统一做 BFS 寻路到 `targetPosition`，不区分 PATROL/CHASE。`BFSPathfinder` 无距离上限，全地图搜索。结果：敌人无论多远都会走向玩家。

### 2.3 `sightRange` 的影响

默认 `sightRange = 1`（`Enemy.java:23`），意味着只有相邻格子才触发 CHASE。但"视野外"的 PATROL 行为同样走向玩家，所以 `sightRange` 形同虚设——无论 1 还是 100，敌人都从地图另一端走向玩家。

## 三、修复方案

**核心思路**：PATROL 时生成随机巡逻目标点（而非玩家位置），让敌人只在视野内追击玩家，视野外则随机走动。

### 3.1 修改 `RuleBasedBrain.java`

1. 构造函数增加 `Random` 参数，用于生成随机巡逻目标
2. PATROL 分支生成一个**随机可达的巡逻目标**：
   - 在敌人位置周围随机偏移（曼哈顿距离 3~8 格范围内）
   - 锁定在地图边界内
   - 检查目标格是否为可通行 FLOOR 瓦片
   - 最多重试 20 次，若全部失败则原地不动（返回 `enemyPos` 自身）

```java
// 伪代码示意
if (dist <= sightRange) {
    return new StrategicIntent(..., CHASE, playerPos);
} else {
    Position patrolTarget = generateRandomPatrolPos(world, enemyPos, random);
    return new StrategicIntent(..., PATROL, patrolTarget);
}
```

### 3.2 修改 `Enemy.java`

构造函数中传递 `random` 给 `RuleBasedBrain`：

```java
this.brain = new RuleBasedBrain(sightRange, random);
```

## 四、影响范围

| 文件 | 改动 |
|------|------|
| `byog/Core/RuleBasedBrain.java` | 加 `Random` 参数 + PATROL 随机目标生成逻辑 |
| `byog/Core/Enemy.java` | 构造 `RuleBasedBrain` 时传入 `random` |

**不影响**：`ClassicalPlanner`、`BFSPathfinder`、`StrategicIntent`、`ActionQueue` 等均无需修改——PATROL 策略的 planner 翻译逻辑天然支持任意 `targetPosition`。

## 五、验证方式

1. 运行游戏，确认敌人在远离玩家时不会径直走向玩家
2. 用 `Logger` 日志确认 PATROL 目标点随机变化
3. 观察敌人在视野外是否有自然随机的巡逻行为
4. 确认玩家靠近敌人（曼哈顿距离 ≤ 1）时敌人正常追击
