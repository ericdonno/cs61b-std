# Step 1.6 实现计划：扩展 AgentTrace + 注释 updateAI 中的测试部分

## 目标

1. 扩展 `AgentTrace`：新增 `OBSERVATION_GENERATED` 事件类型、新字段、factory、schema version 升级
2. 把 `Enemy.updateAI()` 中的测试/trace 相关部分注释出来

## 当前状态分析

### Step 1.6 要求（来自 PHASE\_1\_SPEC.md §10.6）

| 任务             | 描述                                                                                |
| -------------- | --------------------------------------------------------------------------------- |
| 新增 EventType   | `OBSERVATION_GENERATED`                                                           |
| 新增 Event 字段    | `visiblePlayer` (Boolean)、`visibleEntityCount` (Integer)、`fovTileCount` (Integer) |
| 新增 factory     | `Event.observationGenerated(Context, boolean, int, int)`                          |
| 更新 JSON 输出     | `InMemorySink.toCanonicalJson()` 添加新字段                                            |
| Schema version | 升级为 `phase1.trace.v1`                                                             |
| 更新 inputKind   | 私有感知路径使用 `"private-perception-v1"`                                                |
| 在 updateAI 中调用 | 私有感知路径记录 OBSERVATION\_GENERATED 事件                                                |

### 需要注释的测试相关代码

`Enemy.updateAI()` 中所有 trace 相关代码：

* `AgentTrace.Context traceContext` 和 `AgentTrace.Sink traceSink` 参数

* 所有 `if (traceContext != null)` 块

* 所有 `safeRecord()` 调用

## 实施步骤

### 步骤 1：扩展 AgentTrace.EventType

在 `EventType` 枚举中添加 `OBSERVATION_GENERATED`：

```java
public enum EventType {
    LEGACY_DECISION_INPUT,
    INTENT_SELECTED,
    ACTION_ATTEMPTED,
    ACTION_RESULT,
    OBSERVATION_GENERATED  // 新增
}
```

### 步骤 2：更新 SCHEMA\_VERSION

```java
public static final String SCHEMA_VERSION = "phase1.trace.v1";
```

### 步骤 3：扩展 AgentTrace.Event 字段

在 `Event` 类中添加新字段：

```java
// ----- Perception 相关字段（OBSERVATION_GENERATED 时填充）-----
public final Boolean visiblePlayer;
public final Integer visibleEntityCount;
public final Integer fovTileCount;
```

### 步骤 4：更新 Event 构造方法

更新私有构造方法，添加新字段参数。

### 步骤 5：新增 observationGenerated factory

```java
public static Event observationGenerated(Context context,
        boolean visiblePlayer, int visibleEntityCount, int fovTileCount) {
    return new Event(SCHEMA_VERSION, context.scenarioId,
            context.scenarioVersion, context.logicalTick,
            null, context.actorKey,
            EventType.OBSERVATION_GENERATED,
            "private-perception-v1",
            null, null, null, null,
            null, null, null, null, null, null,
            visiblePlayer, visibleEntityCount, fovTileCount);
}
```

### 步骤 6：更新 InMemorySink.toCanonicalJson()

在 JSON 输出中添加新字段：

```java
appendNullableBool(sb, "visiblePlayer", e.visiblePlayer, false);
appendNullableInt(sb, "visibleEntityCount", e.visibleEntityCount, false);
appendNullableInt(sb, "fovTileCount", e.fovTileCount, true);  // 最后一个字段
```

需要新增 `appendNullableBool` 方法。

### 步骤 7：注释 Enemy.updateAI() 中的测试相关代码

保留现有方法签名，只注释掉内部的 trace 相关代码块：

* 所有 `if (traceContext != null)` 条件块

* 所有 `safeRecord()` 调用

* TODO 注释（记录 OBSERVATION\_GENERATED 的占位）

方法签名保持不变：

```java
public void updateAI(TETile[][] world, EntityManager entityMgr, Player player) { ... }
public void updateAI(TETile[][] world, EntityManager entityMgr, Player player,
                     AgentTrace.Context traceContext, AgentTrace.Sink traceSink) { ... }
```

这样调用方（如 Phase0EncounterHarness）仍然可以编译通过，但 trace 记录被禁用。

### 步骤 8：编译验证

确保编译通过。

### 步骤 9：测试验证

运行 Phase 0 测试，确保行为不变。

## 风险与注意事项

1. **Event 构造方法变更**：需要更新所有现有的 factory 方法（legacyDecisionInput、intentSelected、actionAttempted、actionResult），添加新字段参数。
2. **JSON 字段顺序**：新字段必须按顺序添加到 toCanonicalJson 中。
3. **Schema version 升级**：Phase 0 golden 将不再匹配，但 Phase 0 测试不会使用新路径。
4. **注释后的代码**：注释掉的代码应该保留，以便未来恢复。

## 产出

* 扩展后的 `AgentTrace.java`（OBSERVATION\_GENERATED、新字段、v1 schema）

* 注释了测试部分的 `Enemy.updateAI()` 方法

* 编译通过

* Phase 0 测试绿色

