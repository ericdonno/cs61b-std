# 修复敌人追击响应延迟

## 一、摘要

敌人当前只在 `actionQueue.needRefill()`（队列 ≤ 2）时才会重新调用 `brain.think()` 评估策略。如果敌人正在执行一段较长的巡逻路径（如 8 步），玩家进入视野后敌人不会立即切换为 CHASE，而是必须先走完队列中的旧巡逻动作，直到队列降至 ≤ 2 才会重新评估。这导致明显的追击响应延迟。

## 二、现状分析

### 2.1 当前循环逻辑 (`Enemy.updateAI()`)

```
每 moveInterval=5 帧执行一次：
  if (actionQueue.needRefill()) {      // 只有 queue.size() <= 2 才进入
      brain.think()                    // 评估新策略
      ClassicalPlanner.translate()     // BFS 生成路径
      actionQueue.enqueueAll()         // 入队
  }
  消费 1 个动作
```

### 2.2 问题复现

假设敌人生成了一条 8 步的 PATROL 路径，入队后玩家进入视野：

| 时刻 | 队列长度 | needRefill? | brain.think 调用? | 行为 |
|------|---------|-------------|-------------------|------|
| T=0 | 8 | false | 否 | 执行旧 PATROL 第 1 步 |
| T=1 | 7 | false | 否 | 执行旧 PATROL 第 2 步 |
| T=2 | 6 | false | 否 | 执行旧 PATROL 第 3 步 |
| T=3 | 5 | false | 否 | 执行旧 PATROL 第 4 步 |
| T=4 | 4 | false | 否 | 执行旧 PATROL 第 5 步 |
| T=5 | 3 | false | 否 | 执行旧 PATROL 第 6 步 |
| T=6 | 2 | **true** | **是，终于重新评估** | CHASE 入队 |

- 延迟 = (8 - 2) × moveInterval = 6 × 5 = **30 游戏帧**
- 巡逻路径越长，延迟越大

### 2.3 根因

`needRefill()` 作为唯一触发 `brain.think()` 的条件，导致策略切换是"被动"的——必须等队列消耗到阈值才会重新评估。

## 三、修复方案

**核心思路**：每 AI tick 都调用 `brain.think()` 评估局势。用 `currentStrategy` 字段追踪当前队列是为哪个策略服务的，当策略发生变化时立即清空旧队列并重新规划。同策略则保持 needRefill 续补逻辑。

设计决策：
- **策略切换 = 立即中断**：任何策略变化（PATROL→CHASE, CHASE→PATROL, 或未来任何新策略）都触发清空重规划。通用、可扩展，不特判某个策略。
- **同策略 = 走完再续**：CHASE 持续中不做无谓的 BFS 重算，等 needRefill 自然续补。省算力。
- **每 tick 都 think**：`RuleBasedBrain.think()` 只是曼哈顿距离判断，开销极小。

### 3.1 修改 `Enemy.java`

新增字段 `currentStrategy`，重构 `updateAI()`：

```java
// 新增字段
private StrategicIntent.Strategy currentStrategy;

public void updateAI(TETile[][] world, EntityManager entityMgr, Player player) {
    tickCounter++;
    if (tickCounter >= moveInterval) {
        tickCounter = 0;

        // 每 tick 评估当前局势
        GameStateSnapshot snapshot = new GameStateSnapshot(world,
                player.getPosition(), this.getPosition(), this.getId());
        StrategicIntent intent = brain.think(snapshot);
        StrategicIntent.Strategy newStrategy = intent.getStrategy();

        // 策略切换 → 立即清空旧队列，重新规划
        if (newStrategy != currentStrategy) {
            actionQueue.clear();
            currentStrategy = newStrategy;
            List<Action> actions = ClassicalPlanner.translate(intent,
                    this.getPosition(), this.getId(), world, entityMgr, random);
            actionQueue.enqueueAll(actions);
        } else if (actionQueue.needRefill()) {
            // 同策略续补
            List<Action> actions = ClassicalPlanner.translate(intent,
                    this.getPosition(), this.getId(), world, entityMgr, random);
            actionQueue.enqueueAll(actions);
        }

        // 消费动作（不变）
        for (int i = 0; i < MAX_RETRY; i++) {
            Action action = actionQueue.poll();
            if (action == null) break;
            if (action.execute(world, this) == Action.ActionResult.SUCCESS) break;
        }
    }
}
```

## 四、影响范围

| 文件 | 改动 |
|------|------|
| `byog/Core/Enemy.java` | 新增 `currentStrategy` 字段 + 重构 `updateAI()` |

**不影响**：`RuleBasedBrain`、`ClassicalPlanner`、`ActionQueue`、`StrategicIntent` 等均无需修改。

## 五、验证方式

1. 敌人巡逻时玩家靠近进入视野，敌人立即转向追击
2. CHASE 持续中不会每 tick 重复 BFS
3. 玩家脱离视野后敌人立即停止追击、开始巡逻
4. 正常巡逻/追击行为不受影响
