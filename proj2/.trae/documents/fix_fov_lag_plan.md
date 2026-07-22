# 修复 FOV 滞后问题

## 问题分析

[Enemy.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Entity/Enemy.java#L93-L192) 的 `updateAI()` 中：

1. **第93-100行**：感知计算 → `cachedVisibleMask` 被设为移动前的 FOV
2. **第143-192行**：Planner 规划 + 动作执行，敌人可能移动 1-4 步
3. 下一个 AI tick 之前（长达 `moveInterval` 帧），渲染层读到的 mask 都反映旧位置

## 修复方案

动作执行完成后，如果 `perceptionEnabled=true`，用敌人**当前（移动后）位置**重新计算一次感知，更新 `cachedVisibleMask`。不生成 trace 事件（纯渲染用途）。

## 具体改动

**文件**：`byog/Entity/Enemy.java`

在动作执行 for 循环结束后（第192行 `}` 之后、第193行 `}` 之前），新增：

```java
            // 动作执行完成后，重新计算 FOV 以反映移动后的位置
            if (perceptionEnabled) {
                ObservationEnvelope postMoveObs = PerceptionSystem.computeObservation(
                        "fov_update", 0, 0,
                        world, entityMgr,
                        this, player,
                        this.sightRange, 0);
                this.cachedVisibleMask = postMoveObs.getVisibleMask();
            }
```

## 影响

- 每个 AI tick 多算一次 `PerceptionSystem.computeObservation()`（每 5 帧一次，开销可忽略）
- 不影响 AI 决策（决策用的是移动前的感知，这是正确的）
- 不影响 trace 输出（不生成 trace 事件）
- 不影响 legacy 路径

## 验证

- 编译通过
- 游戏内 FOV 紧跟敌人移动，无延迟感