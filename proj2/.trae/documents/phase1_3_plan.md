# Phase 1.3 实现计划：扩展 ObservationEnvelope

## 仓库调研结论

### 当前问题
根据 SPEC §8.6 和实现提示，`RuleBasedBrain` 在基于 `ObservationEnvelope` 决策时需要：
- 用 `obs.canSeePlayer()` 代替 `dist <= sightRange`
- patrol 目标从可见的 FLOOR tile 中选

**问题**：当前 `ObservationEnvelope` 只有 `visibleMask`（标记哪些 tile 可见），没有提供判断可见 tile 是否为可行走地面的方法。Brain 需要知道一个可见位置是否为 FLOOR 才能生成巡逻目标。

### 解决方案
按照 SPEC 实现提示：`ObservationEnvelope` 在构造时接受 `TETile[][] world` 引用，提供 `isWalkable(int x, int y)` 方法（先检查 visibleMask，再检查 tile 类型）。

## 待编辑文件

| 文件路径 | 修改内容 |
|---------|---------|
| `byog/Perception/ObservationEnvelope.java` | 添加 world 引用和 isWalkable() 方法 |
| `byog/Perception/PerceptionSystem.java` | 更新 computeObservation() 调用，传入 world 引用 |

## 实施步骤

### Step 1：扩展 ObservationEnvelope

#### 1.1 添加 world 字段
```java
private final TETile[][] world;  // 引用，不复制，用于查询 tile 类型
```

#### 1.2 修改构造方法
添加 `TETile[][] world` 参数，并保存引用：
```java
ObservationEnvelope(String runId, int floorId, String agentId,
                    long observationSeq, long observedAtTurn,
                    Position selfPosition, int selfHp,
                    boolean[][] visibleMask,
                    List<VisibleEntity> visibleEntities,
                    List<HeardEvent> heardEvents,
                    TETile[][] world) {
    // ... 现有字段赋值 ...
    this.world = world;
}
```

#### 1.3 添加 isWalkable() 方法
```java
/**
 * 判断 (x,y) 是否为可见且可行走的位置。
 * 先检查 visibleMask（必须在 FOV 内），再检查 tile 类型（必须不是墙或虚空）。
 * 这样 Brain 不需要直接持有 world 引用，也无法访问不可见区域。
 */
public boolean isWalkable(int x, int y) {
    if (!isVisible(x, y)) {
        return false;
    }
    return world[x][y] != Tileset.WALL && world[x][y] != Tileset.NOTHING;
}
```

### Step 2：更新 PerceptionSystem

#### 2.1 更新 computeObservation() 调用
在创建 `ObservationEnvelope` 时传入 `world` 引用：
```java
return new ObservationEnvelope(runId, floorId, self.getAgentId(),
        observationSeq, currentTurn,
        selfPos, self.getHp(),
        visibleMask, visibleEntities, heardEvents,
        world);  // 新增参数
```

## 潜在依赖与考虑

### 内存开销
- world 只是引用，不复制，内存开销为零
- 保持 ObservationEnvelope 的不可变性（只读取，不修改）

### 安全性
- `isWalkable()` 先检查 `visibleMask`，确保 Brain 无法通过此方法查询不可见区域
- 符合"有限知识"原则（INV-02）

### 与后续步骤的关系
- Step 1.4 的 `RuleBasedBrain.thinkFromObservation()` 将使用 `isWalkable()` 生成巡逻目标

## 风险处理

### 性能风险
- **处理方式**：`isWalkable()` 只是简单的数组访问和条件判断，性能开销可忽略

### 封装性风险
- **处理方式**：不提供 world 的 getter，只通过 `isWalkable()` 暴露必要功能

## 验证方式

1. **编译验证**：所有文件编译通过
2. **单元测试**：运行现有的 `PerceptionSystemTest`，确保 LOS 测试仍通过

## 产出

- 扩展后的 `ObservationEnvelope.java`（支持 `isWalkable()` 查询）
- 更新后的 `PerceptionSystem.java`（传入 world 引用）
