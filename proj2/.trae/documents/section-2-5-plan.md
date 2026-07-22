# 2.5 升级 Enemy 集成决策层和规划层

## 概述

在 `Enemy.java` 中接入阶段二已实现的所有组件（Brain + Planner），用 `RuleBasedBrain` 决策 + `ClassicalPlanner` 规划替换阶段一的随机漫步逻辑。同步修改 `Game.java` 中的 AI tick 调用签名。

## 当前状态分析

### 已就绪的组件

| 组件       | 文件                                                                                                  | 关键 API                                                         |
| -------- | --------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| Brain    | [RuleBasedBrain.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/RuleBasedBrain.java)       | `think(GameStateSnapshot)` → `StrategicIntent`                 |
| Planner  | [ClassicalPlanner.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/ClassicalPlanner.java)   | `static translate(intent, enemyPos, world, entityMgr, random)` |
| Snapshot | [GameStateSnapshot.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/GameStateSnapshot.java) | `new GameStateSnapshot(world, playerPos, enemyPos)`            |
| Intent   | [StrategicIntent.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/StrategicIntent.java)     | 数据类，含 `getTargetPosition()`, `getStrategy()`                   |

### 当前 Enemy 和 Game 状态

* [Enemy.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Enemy.java)：`updateAI(world, entityMgr)` 使用 `randomDirection()` 随机漫步

* [Game.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Game.java)：L58 调用 `enemy.updateAI(world, entityMgr)`，`player` 字段已存在

## 拟议变更

### 变更 1：`Enemy.java` — 接入 Brain + Planner

**新增字段**：

* `private EnemyBrain brain;` — AI 大脑，默认 `new RuleBasedBrain()`

**构造器修改**：在两个构造器中均添加 `this.brain = new RuleBasedBrain();`

**updateAI 签名升级**：

* 旧：`updateAI(TETile[][] world, EntityManager entityMgr)`

* 新：`updateAI(TETile[][] world, EntityManager entityMgr, Player player)`

* 原因：Brain 需要 Player 位置构建 GameStateSnapshot

**updateAI 内部逻辑重写**：

```
tickCounter++; if (tickCounter < moveInterval) return;

if (actionQueue.needRefill()) {
    // 1. 构建快照
    GameStateSnapshot snapshot = new GameStateSnapshot(world, player.getPosition(), this.getPosition());
    // 2. Brain 决策
    StrategicIntent intent = brain.think(snapshot);
    // 3. Planner 翻译
    List<MoveAction> actions = ClassicalPlanner.translate(intent, this.getPosition(), world, entityMgr, random);
    // 4. 入队
    actionQueue.enqueueAll(actions);
}

// 逐帧消费动作（MAX_RETRY 重试，不再有内层随机补充）
for (int i = 0; i < MAX_RETRY; i++) {
    Action action = actionQueue.poll();
    if (action == null) break;
    if (action.execute(world, this) == Action.ActionResult.SUCCESS) break;
}
```

**删除**：`randomDirection()` 私有方法（不再使用）。

**保留不变**：`MAX_RETRY`、`moveInterval`、`tickCounter`、`spawnEnemies`、getter。

### 变更 2：`Game.java` — AI tick 调用签名

**位置**：`playWithKeyboard()` L58

**旧**：`enemy.updateAI(world, entityMgr)`
**新**：`enemy.updateAI(world, entityMgr, player)`

仅改一行。`playWithInputString` 不遍历敌人 AI tick，无需修改。

## 假设与决策

1. **EnemyBrain 字段不存 planner**：`ClassicalPlanner` 是纯静态工具类，无实例状态，不需要作为字段存储。

2. **Brain 默认 RuleBasedBrain**：构造器中硬编码 `new RuleBasedBrain()`。阶段四添加 LLMBrain 时，会新增一个接受 `EnemyBrain` 参数的构造器（见指南 4.5）。

3. **重试循环简化**：旧逻辑在重试循环内二次调用 `needRefill` + 新随机动作，因为一次只入队一个动作。新逻辑一次性批量入队，BLOCKED 时直接取下一个即可。

4. **Game.java 的 playWithInputString 不变**：按当前代码，`playWithInputString` 仅处理状态机 + 最终渲染帧，不运行 AI tick。敌人在该模式下不移动，符合固有问题（测试模式只看静态帧）。

## 验证步骤

1. 编译：`javac -cp ".;d:\Courses\cs61b\cs61b-std\library-sp18\javalib\stdlib-package.jar" byog\Core\Enemy.java byog\Core\Game.java`
2. 无编译错误
3. 启动游戏 → 输入种子 → 玩家靠近敌人 7 格内 → 敌人沿 BFS 最短路径追击 → 跑远 → 敌人停止追击

