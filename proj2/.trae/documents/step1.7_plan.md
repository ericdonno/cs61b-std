# Step 1.7 实现计划：Phase1EncounterHarness + Phase 1 测试

## 目标

创建 Phase 1 的 headless runner 和测试套件：
1. `Phase1EncounterHarness` — 委托 Phase 0 parser，启用私有感知
2. `Phase1EncounterTest` — P1-T01 至 P1-T08
3. `Phase1TestSuite` — 聚合 Phase0TestSuite + Phase1EncounterTest

## 当前状态分析

### 已有资产

- `Phase0EncounterHarness` — 成熟模式：fromAscii 解析、step/runTicks 调度、trace 收集、canonicalState/canonicalTraceJson/buildBaselineJson
- `Phase0EncounterTest` — 9 个测试（T01-T09），包含 golden comparison 测试
- `Phase0TestSuite` — JUnit Suite 聚合
- `PerceptionSystem.computeObservation()` — 纯函数，直接可调用
- `Enemy.setPerceptionEnabled(true)` — 开关已就绪

### 关键问题：测试如何访问 ObservationEnvelope？

P1-T01/T02/T03/T06 需要检查 `ObservationEnvelope` 内部细节（visibleEntities、身份字段）。但 `ObservationEnvelope` 在 `Enemy.updateAI()` 内部创建，不对外暴露。

**方案**：测试直接调用 `PerceptionSystem.computeObservation()` 获取 observation 实例。这是一个公开的纯函数，输入参数都能从 harness 拿到（world, entityMgr, guard, player, sightRange）。

trace `OBSERVATION_GENERATED` 事件只记录摘要（visiblePlayer/visibleEntityCount/fovTileCount），足够用于 T04（intent 判断）、T07（trace 事件存在）。

### 数据流

```
Phase1EncounterHarness.step()
  ├─ guardA.updateAI(world, entityMgr, player, ctxA, traceSink)
  │    └─ 内部: PerceptionSystem.computeObservation() → OBSERVATION_GENERATED event → thinkFromObservation
  ├─ guardB.updateAI(world, entityMgr, player, ctxB, traceSink)
  │    └─ 同上
  └─ entityMgr.flush/removeDead + logicalTick++

测试 P1-T01:
  直接调用 PerceptionSystem.computeObservation(world, entityMgr, guardB, player, ...)
  → 断言 observation.canSeePlayer() == false
  → 断言 observation.getVisibleEntities() 中不含 PLAYER
```

## 实施步骤

### 步骤 1：创建 Phase1EncounterHarness

**文件**：`byog/Test/Phase1EncounterHarness.java`（新建）

**结构**：与 `Phase0EncounterHarness` 镜像，差异如下：

| 方面 | Phase 0 | Phase 1 |
|------|---------|---------|
| runId | 无 | `"perception-test"` |
| floorId | 无 | `1` |
| agentId | `"guard-a"` / `"guard-b"` | 同 Phase 0 |
| perceptionEnabled | `false`（默认） | `true`（构造时设置） |
| trace path | `LEGACY_DECISION_INPUT` | `OBSERVATION_GENERATED` |
| baselineSchemaVersion | `"phase0.baseline.v1"` | `"phase1.baseline.v1"` |

**构造方式**：独立构造，直接复用 ASCII 解析逻辑，不委托 `Phase0EncounterHarness.fromAscii()`：
```java
// fromAscii 的内部解析逻辑完全一样，但需要额外步骤：
// 1. 解析 ASCII → world, positions
// 2. 创建 Player + Enemy（同 Phase 0）
// 3. enemy.setPerceptionEnabled(true) ← 唯一新增
// 4. 创建 EntityManager + traceSink
```

为什么不复用 `Phase0EncounterHarness.fromAscii()`？因为它返回的是 `Phase0EncounterHarness` 对象，而 Phase 1 需要不同的 harness 类型（不同的 scenarioId、perception 设置）。直接委托 fromAscii 后还需要重新包装，不如独立构造（代码量不大，约 180 行）。

**核心方法**（与 Phase 0 相同）：
- `baselineTwoGuardsV1()` — 固定场景工厂
- `step()` — 推进一个 tick，调度 A→B，Context 使用 Phase 1 的 scenarioId
- `runTicks(int)` — 连续推进
- `canonicalTraceJson()` / `canonicalState()` / `buildBaselineJson()`
- getter：`player()`, `guardA()`, `guardB()`, `terrainCopy()`, `getTraceSink()`, `width()`, `height()`, `getLogicalTick()` 等

**额外 getter**（测试直接调用 computeObservation 需要）：
- `getWorld()` — 返回 `TETile[][] world`
- `getEntityMgr()` — 返回 `EntityManager`

### 步骤 2：创建 Phase1EncounterTest（P1-T01 至 P1-T08）

**文件**：`byog/Test/Phase1EncounterTest.java`（新建）

#### P1-T01: guardB_cannot_see_player_behind_wall

```
1. 创建 Phase1EncounterHarness
2. 直接调用 PerceptionSystem.computeObservation(harness.getWorld(),
   harness.getEntityMgr(), harness.guardB(), harness.player(), 7, 0)
3. 断言 observation.canSeePlayer() == false
4. 断言 observation.getVisibleEntities() 中不含 PLAYER 类型
```

#### P1-T02: guardA_can_see_player_no_wall

```
1. 同上，但用 guardA
2. 断言 observation.canSeePlayer() == true
3. 断言 observation.getVisibleEntities() 中有 PLAYER，且 visibleHp > 0
```

#### P1-T03: different_enemies_different_observations

```
1. 创建 harness
2. 分别调 computeObservation(guardA) 和 computeObservation(guardB)
3. 断言 guardA 的 visibleEntityCount != guardB 的 visibleEntityCount
```

#### P1-T04: brain_decision_based_on_private_obs

```
1. harness.runTicks(1)  // 让 AI 产生第一次决策
2. 从 trace 中找 guardA 的第一个 INTENT_SELECTED → 断言 strategy = CHASE
3. 从 trace 中找 guardB 的第一个 INTENT_SELECTED → 断言 strategy = PATROL
```

#### P1-T05: agentId_stable_across_instances

```
1. 创建两个 Enemy（同 agentId），不同的 JVM 自增 id
2. 断言 agentId 字符串相等
3. 断言 id（JVM 自增）不相等（证明 agentId 独立于 id）
```

#### P1-T06: observation_has_identity_fields

```
1. 调用 computeObservation，传入 runId="test-run", floorId=3
2. 断言 observation.getRunId() == "test-run"
3. 断言 observation.getFloorId() == 3
4. 断言 observation.getAgentId() 非空
5. 断言 observation.getObservationSeq() >= 0
6. 断言 observation.getObservedAtTurn() >= 0
```

#### P1-T07: trace_contains_perception_events

```
1. harness.runTicks(2)
2. 从 trace 中找 OBSERVATION_GENERATED 事件
3. 断言每个 tick 都有 guardA 和 guardB 的 OBSERVATION_GENERATED
4. 断言 A 和 B 至少有一个 tick 的 visiblePlayer 值不同
```

#### P1-T08: phase1_rule_baseline_matches_golden

暂不实现。Phase 1 golden baseline 需要在 Step 1.8 中先生成。此处用 `@Ignore` 占位或留空。

### 步骤 3：创建 Phase1TestSuite

**文件**：`byog/Test/Phase1TestSuite.java`（新建）

```java
@RunWith(Suite.class)
@Suite.SuiteClasses({
    EnemyCollisionTest.class,
    Phase0EncounterTest.class,
    Phase1EncounterTest.class
})
public class Phase1TestSuite { }
```

包含 Phase 0 Suite 的所有测试（回归保证）+ Phase 1 新测试。

### P1-T08 推迟说明

P1-T08 (golden comparison) 需要先生成 `phase1_rule_baseline_v1.json`，这属于 Step 1.8 的工作。Step 1.7 中 P1-T08 添加 `@Ignore` 注解或直接不写，在 Step 1.8 生成 golden 后再激活。

## 涉及文件

| 文件 | 新建/修改 | 说明 |
|------|-----------|------|
| `byog/Test/Phase1EncounterHarness.java` | **新建** | Phase 1 headless runner |
| `byog/Test/Phase1EncounterTest.java` | **新建** | P1-T01 至 P1-T07（T08 推迟到 Step 1.8） |
| `byog/Test/Phase1TestSuite.java` | **新建** | 聚合 Suite |

## 风险与注意事项

1. **代码复用 vs 独立构造**：Phase1EncounterHarness 的 `fromAscii` 解析逻辑与 Phase0 完全相同（约 120 行）。如果后续 ASCII 解析变复杂，可考虑提取共享 parser。当前直接复制是合理的——两个 harness 有不同的感知模式和行为，耦合在一起反而不好维护。

2. **guardB 在私有感知下首次 PATROL**：P1-T04 断言 guardB 首次 intent 为 PATROL。这是因为 B 被墙遮挡看不到玩家。但在 12 tick 后，B 可能巡逻到能看到玩家的位置，导致后续 intent 变为 CHASE（这是 Phase 0 中也存在的行为）。

3. **P1-T07 visiblePlayer 不同**：在 baseline-two-guards 场景中，Guard A 的 LOS 畅通（能看到玩家），Guard B 被墙遮挡（看不到）。因此 `visiblePlayer` 字段必然不同。但如果距离原因导致 A 也看不到玩家（sightRange=7，距离=6，无墙遮挡 → 可见），这不是风险。

## 验证步骤

1. 编译通过
2. `java ... org.junit.runner.JUnitCore byog.Test.Phase1TestSuite` → P1-T01 至 P1-T07 全绿 + Phase 0 19 个回归绿色
3. `java ... org.junit.runner.JUnitCore byog.Test.Phase0TestSuite` → 19 个全部绿色（独立验证 legaxy 路径未被影响）