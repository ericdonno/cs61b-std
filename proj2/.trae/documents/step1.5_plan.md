# Step 1.5 实现计划：更新 Enemy.updateAI() 双路径

## 目标

修改 `Enemy.updateAI()` 方法，根据 `perceptionEnabled` 字段选择两条路径：
- `false`：legacy 路径（构造全知 `GameStateSnapshot`，调用 `brain.think()`）
- `true`：私有感知路径（调用 `PerceptionSystem.computeObservation()`，调用 `brain.thinkFromObservation()`）

两个路径的后续逻辑（Planner/ActionQueue/Action/Retry）完全相同。

## 当前状态分析

### 已完成的前置工作
- Step 1.1：`VisibleEntity`、`HeardEvent`、`ObservationEnvelope` 已创建
- Step 1.2：`PerceptionSystem` 已实现（包含 `computeObservation()`）
- Step 1.3：`Enemy` 已添加 `perceptionEnabled`、`agentId`、`observationSeq` 字段
- Step 1.4：`EnemyBrain` 接口已扩展，`RuleBasedBrain` 已实现 `thinkFromObservation()`

### 当前 Enemy.updateAI() 逻辑

```java
// 当前只有 legacy 路径（约第 79-92 行）
GameStateSnapshot snapshot = new GameStateSnapshot(world,
        player.getPosition(), this.getPosition(), this.getId());
safeRecord(traceSink, AgentTrace.Event.legacyDecisionInput(traceContext));
StrategicIntent intent = brain.think(snapshot);
safeRecord(traceSink, AgentTrace.Event.intentSelected(traceContext, intent));
```

### PerceptionSystem.computeObservation() 参数需求

```java
public static ObservationEnvelope computeObservation(
        String runId, int floorId, long observationSeq,
        TETile[][] world, EntityManager entityMgr,
        Enemy self, Player player,
        int sightRange, long currentTurn)
```

**参数映射方案**：

`AgentTrace.Context` 当前只有 `scenarioId`、`scenarioVersion`、`logicalTick`、`actorKey` 字段，没有 `runId`、`floorId`、`turn`。使用以下映射：

| computeObservation 参数 | Context 字段 | 默认值（无 Context 时） |
|------------------------|-------------|------------------------|
| runId | scenarioId | "unknown" |
| floorId | - | 1 |
| currentTurn | logicalTick | 0 |
| observationSeq | - | Enemy.getAndIncrementObservationSeq() |

## 实施步骤

### 步骤 1：检查 AgentTrace.Context 是否包含所需字段

查看 `AgentTrace.Context` 的结构，确认是否有 `runId`、`floorId`、`currentTurn` 字段。如果没有，需要添加。

### 步骤 2：修改 Enemy.updateAI() 方法

**目标**：在现有方法内部添加 `if (perceptionEnabled)` 分支

**具体改动**：

1. 添加 `PerceptionSystem` 和 `ObservationEnvelope` 的 import

2. 修改 `updateAI(trace overload)` 方法（第 72-139 行）：
```java
// 替换原有的 snapshot 构造 + think 调用
if (perceptionEnabled) {
    // 私有感知路径
    long seq = this.getAndIncrementObservationSeq();
    long currentTurn = traceContext != null ? traceContext.turn : 0;
    String runId = traceContext != null ? traceContext.runId : "unknown";
    int floorId = traceContext != null ? traceContext.floorId : 1;
    
    ObservationEnvelope observation = PerceptionSystem.computeObservation(
            runId, floorId, seq,
            world, entityMgr,
            this, player,
            this.sightRange, currentTurn);

    if (traceContext != null) {
        // TODO: 记录 OBSERVATION_GENERATED 事件（Step 1.6）
    }

    StrategicIntent intent = brain.thinkFromObservation(observation);

    if (traceContext != null) {
        safeRecord(traceSink,
                AgentTrace.Event.intentSelected(traceContext, intent));
    }
} else {
    // Legacy 路径（原有逻辑）
    GameStateSnapshot snapshot = new GameStateSnapshot(world,
            player.getPosition(), this.getPosition(), this.getId());

    if (traceContext != null) {
        safeRecord(traceSink,
                AgentTrace.Event.legacyDecisionInput(traceContext));
    }

    StrategicIntent intent = brain.think(snapshot);

    if (traceContext != null) {
        safeRecord(traceSink,
                AgentTrace.Event.intentSelected(traceContext, intent));
    }
}
```

### 步骤 3：编译验证

确保编译通过，没有遗漏的 import 或类型错误。

### 步骤 4：测试验证

运行 Phase 0 测试，确保 legacy 路径（`perceptionEnabled=false`）行为不变。

## 关键设计决策

### 参数传递方式

`runId`、`floorId`、`currentTurn` 通过 `AgentTrace.Context` 传递：
- 如果 `traceContext` 为 null（普通游戏运行），使用默认值（`"unknown"`、`1`、`0`）
- 如果 `traceContext` 不为 null（测试 harness），从 context 中读取

这样既保持了现有方法签名兼容，又能在测试场景中传递完整信息。

### trace 事件时机

私有感知路径中，`OBSERVATION_GENERATED` 事件在 `computeObservation` 返回后、`thinkFromObservation` 调用前记录。这确保了 trace 能捕获完整的决策输入。

### 代码复用

两个路径共享后续的 Planner/ActionQueue/Action/Retry 逻辑（第 94-138 行），只在决策输入构造和 Brain 调用上不同。

## 风险与注意事项

1. **AgentTrace.Context 字段缺失**：如果 `Context` 没有 `runId`、`floorId`、`turn` 字段，需要先添加这些字段。
2. **trace 事件未实现**：`OBSERVATION_GENERATED` 事件在 Step 1.6 实现，当前用 TODO 占位。
3. **行为变化**：确保 `perceptionEnabled=false` 时行为与原来完全一致。

## 产出

- 修改后的 `Enemy.updateAI()` 方法（双路径实现）
- 编译通过
- Phase 0 测试绿色（legacy 路径未变）