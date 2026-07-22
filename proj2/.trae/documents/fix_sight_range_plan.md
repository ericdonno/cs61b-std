# 修复超远距离索敌问题计划

## 问题分析

**现象：** 敌人能够跨越整个地图追踪玩家，即使玩家已经跑出了敌人的视野范围。

**根因：** 当前 AI 决策流程存在逻辑漏洞：

1. `RuleBasedBrain.think()` 在距离 ≤ `sightRange`（默认为1）时返回 `CHASE` 意图
2. `ClassicalPlanner.translate()` 调用 `BFSPathfinder.findPath()` 规划**完整路径**到玩家位置
3. 所有路径上的动作被加入 `ActionQueue`，敌人会持续执行直到队列耗尽
4. `ActionQueue.needRefill()` 只在队列长度 ≤ 2 时才触发重新规划
5. 即使玩家已经跑出视野，敌人仍然沿着旧路径走到终点

**调用链：**

```
Enemy.updateAI()
  → GameStateSnapshot(玩家位置, 敌人位置)
  → RuleBasedBrain.think() → 判断是否在视野内
  → ClassicalPlanner.translate() → BFS规划完整路径
  → ActionQueue.enqueueAll() → 入队所有动作
```

## 修复方案

**核心思路：** 在每次 AI 更新时，先检查玩家是否在视野范围内，不在则立即中断追踪。

### 方案一（推荐）：在 Enemy.updateAI() 中增加视野检查

当意图从 CHASE 变为 PATROL 时，清空动作队列，立即中断追踪。

**修改文件：**

* `byog/Core/Enemy.java`

**修改内容：**

* 在 `updateAI()` 中，获取意图后检查是否从 CHASE 变为非 CHASE

* 如果是，调用 `actionQueue.clear()` 清空队列

### 方案二：限制路径长度

在 `ClassicalPlanner.translate()` 中，限制 CHASE 策略的路径长度为 `sightRange`。

**修改文件：**

* `byog/Core/ClassicalPlanner.java`

**修改内容：**

* 添加参数 `maxPathLength`

* 截取路径的前 `maxPathLength` 步

### 方案选择

选择方案一，原因：

1. 更符合"视野外不再追踪"的直观逻辑
2. 修改更局部，不影响 Planner 的通用性
3. 实现简单，只需在 Enemy 中添加状态检查

## 实施步骤

1. 修改 `Enemy.updateAI()` 方法：

   * 获取战略意图后，检查当前意图是否为 CHASE

   * 如果不是 CHASE 且上一次是 CHASE（或直接无条件），清空动作队列

   * 确保 PATROL 状态下不会继续执行之前的追踪路径

2. 验证编译通过

3. 测试游戏运行效果

## 风险评估

* **低风险**：修改仅影响敌人追踪行为，不影响其他系统

* **兼容性**：所有现有 API 保持不变

* **边界情况**：玩家在视野边缘反复进出，敌人会正确切换状态

## 预期效果

修复后，敌人的行为将符合预期：

* 玩家在视野内（曼哈顿距离 ≤ 1）：敌人追踪玩家

* 玩家跑出视野：敌人立即停止追踪，转为巡逻模式

