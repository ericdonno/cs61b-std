# 敌人碰撞卡顿修复计划

## 现象

敌人朝某个方向移动时，如果该方向有墙/另一个实体阻挡，敌人会在原地"愣住"一个动作周期，看起来像卡顿。

## 根因分析

问题出在 [Enemy.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Enemy.java#L48-L60) 的 `updateAI()` 方法：

```java
public void updateAI(TETile[][] world, EntityManager entityMgr) {
    tickCounter++;
    if (tickCounter >= moveInterval) {
        tickCounter = 0;
        if (actionQueue.needRefill()) {
            actionQueue.enqueue(new MoveAction(randomDirection(), entityMgr));
        }
        Action action = actionQueue.poll();
        if (action != null) {
            action.execute(world, this);  // ← 返回值被忽略！
        }
    }
}
```

关键问题：`MoveAction.execute()` 在碰撞阻挡时会返回 `ActionResult.BLOCKED`，但 `updateAI()` **完全忽略了这个返回值**。流程如下：

1. 敌人随机选一个方向 → 创建 `MoveAction`
2. 执行移动，`EntityManager.canMoveTo()` 检测到墙/实体阻挡 → 返回 `BLOCKED`
3. `updateAI()` 不检查结果 → `tickCounter` 已被重置为 0
4. 敌人必须等满 `moveInterval`（默认 5 帧，约 80ms）才能再试下一次方向

这就造成了"愣一个动作"的现象——敌人选了个走不通的方向，虽然瞬间就知道走不通，却被动作周期"惩罚"了等待时间。

**打个比方**：这就像一个人走路撞了墙，明明可以立刻换个方向继续走，却非要原地发呆 5 秒才动。

## 修复方案

在 `updateAI()` 中，当单次移动被阻挡时，**立即重试其他方向**。

核心改动：不依赖 `ActionQueue` 做单动作管理，改用重试循环。

伪代码：

```
for (int retry = 0; retry < MAX_RETRY; retry++):
    direction = randomDirection()
    result = moveAction.execute(world, this)
    if result == SUCCESS: break
```

* `MAX_RETRY = 4`：四个方向全试完，等价于"真的无路可走"

* 如果 4 个方向都走不通（如完全被围住），敌人不动——这是正确的行为

## 改动文件

**仅修改** **[Enemy.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Enemy.java)，`updateAI()`** **方法**：

| 项    | 当前                  | 改为                 |
| ---- | ------------------- | ------------------ |
| 动作调度 | `ActionQueue` 入队再出队 | 直接循环重试             |
| 失败处理 | 忽略 `ActionResult`   | 立即换方向重试            |
| 重试次数 | 0                   | 最多 `MAX_RETRY=4` 次 |

## 不做的

* 不加寻路（pathfinding），超出当前需求

* 不改 `ActionQueue`、`MoveAction`、`EntityManager` 等其他类

* 不改 `moveInterval` 默认值

## 验证

1. 让敌人追到墙边/角落
2. 预期：碰墙后应立即换方向，无"愣住"停顿
3. 完全围住时不动是正确行为

