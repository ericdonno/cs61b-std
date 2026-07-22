# Step 1.4 实现计划：扩展 EnemyBrain 接口 + 更新 RuleBasedBrain

## 任务概述

根据 PHASE_1_SPEC.md Step 1.4 的要求，需要：
1. 扩展 `EnemyBrain` 接口，添加 `thinkFromObservation(ObservationEnvelope)` default 方法
2. 更新 `RuleBasedBrain` 实现基于私有感知的决策逻辑

## 代码现状分析

### 已完成的前置步骤
- Step 1.1：数据结构已创建（`VisibleEntity`, `HeardEvent`, `ObservationEnvelope`），`Entity` 已有 `agentId`
- Step 1.2：`PerceptionSystem` 已实现（Bresenham LOS、`computeObservation`）
- Step 1.3：**尚未完成** - Enemy 构造方法还没有 `agentId` 参数，也没有 `perceptionEnabled` 开关

### 当前文件状态

| 文件 | 状态 | 备注 |
|------|------|------|
| `EnemyBrain.java` | 只有 `think(GameStateSnapshot)` | 需要添加新方法 |
| `RuleBasedBrain.java` | 只有 `think(GameStateSnapshot)` | 需要实现新方法 |
| `ObservationEnvelope.java` | 已完成 | 已有 `isWalkable()` 方法 |
| `Enemy.java` | 旧构造，无 `perceptionEnabled` | Step 1.3 未完成 |
| `Phase0EncounterHarness.java` | 旧构造调用 | 需要配合 Enemy 更新 |

## 实施步骤

### 步骤 1：扩展 EnemyBrain 接口

**文件**：`byog/AI/EnemyBrain.java`

**改动**：
- 添加 `thinkFromObservation(ObservationEnvelope)` default 方法
- 默认实现抛出 `UnsupportedOperationException`

**关键代码**：
```java
public interface EnemyBrain {
    StrategicIntent think(GameStateSnapshot state);
    
    default StrategicIntent thinkFromObservation(ObservationEnvelope obs) {
        throw new UnsupportedOperationException(
            "This brain does not support private observation");
    }
}
```

### 步骤 2：实现 RuleBasedBrain.thinkFromObservation()

**文件**：`byog/AI/RuleBasedBrain.java`

**改动**：
- 添加 `thinkFromObservation(ObservationEnvelope)` 方法
- 逻辑：
  1. 检查 `obs.canSeePlayer()` → 获取玩家位置
  2. 如果玩家可见且距离为 1 → ATTACK
  3. 如果玩家可见 → CHASE
  4. 否则 → PATROL（从可见区域随机选择巡逻目标）

**关键设计决策**：
- 使用 `ObservationEnvelope.isWalkable(x, y)` 判断巡逻目标是否可行走
- patrol 范围限制在可见区域内（符合有限知识原则）

### 步骤 3：编译验证

- 执行编译命令确保代码无语法错误
- 不需要运行完整测试套件（Step 1.3 未完成，Enemy 构造还不兼容）

## 依赖与风险

### 依赖
- `ObservationEnvelope.java` - 已完成，提供 `isWalkable()` 方法
- `VisibleEntity.java` - 已完成，提供玩家信息

### 风险
- **Step 1.3 未完成**：当前 Enemy 构造方法还没有 `agentId` 参数，且缺少 `perceptionEnabled` 开关。这意味着 Step 1.4 的改动不会立即被 `Enemy.updateAI()` 使用，需要等待 Step 1.5 完成。
- **patrol 目标生成**：只能从可见区域选择，可能导致巡逻范围缩小。这是符合设计的预期行为（敌人只朝看得见的地方巡逻）。

## 验证标准

- 编译通过
- `EnemyBrain` 接口扩展成功，不破坏现有实现
- `RuleBasedBrain` 实现新方法，逻辑符合私有感知决策规则

## 后续步骤

Step 1.4 完成后，需要继续：
- Step 1.5：更新 `Enemy.updateAI()` 双路径（需要先完成 Step 1.3）
- Step 1.6：扩展 AgentTrace
- Step 1.7：创建 Phase1EncounterHarness