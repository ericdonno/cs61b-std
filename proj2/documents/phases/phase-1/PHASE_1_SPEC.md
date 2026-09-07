Phase 1 Spec：私有感知与知识边界

## 0. 元数据

- **Phase**：1
- **状态**：Draft — ready for Builder review；实现尚未开始
- **作者**：Codex（持续 Advisor）
- **创建日期**：2026-07-20
- **基线分支**：`ai-enemis`
- **基线 commit**：`a97353e6404fc06a063bea35e4648b30ef9f6176`
- **前一阶段 Completion**：`PHASE_0_COMPLETION.md`
- **上位文档**：`PROJECT_INTENT_zh-CN.md`、`DEVELOPMENT_ROADMAP.md`

## 1. 必读输入与审计范围

本 Spec 基于以下实际输入：

- `PROJECT_INTENT_zh-CN.md`（§3 Agent 身份与边界、§4 单层游戏循环）
- `DEVELOPMENT_ROADMAP.md`（阶段 1 定义、INV-01/02/03/04/06、阶段间交付链）
- `PHASE_0_COMPLETION.md`（Phase 0 遗留问题、可交付 artifacts）
- `PHASE_0_SPEC.md`（固定场景定义、trace seam 设计）
- `PHASE_SPEC_TEMPLATE.md`
- `byog/AI/GameStateSnapshot.java`——全知 snapshot，Phase 0 标记为 `LEGACY_DECISION_INPUT`
- `byog/Entity/Enemy.java`——`updateAI()` 构造 `GameStateSnapshot(world, player.getPosition(), ...)`
- `byog/AI/RuleBasedBrain.java`——`think(GameStateSnapshot)` 使用全知世界地图
- `byog/AI/EnemyBrain.java`——当前只有 `think(GameStateSnapshot)` 一个方法
- `byog/Entity/Entity.java`——`id` 是 JVM 全局自增，不随存档保存
- `byog/Entity/EntityManager.java`——`findEntityAt()` 可查询任意位置实体
- `byog/Entity/Player.java`——HP、position 等状态公开可读
- `byog/Trace/AgentTrace.java`——Phase 0 trace seam；Phase 1 需新增感知事件类型
- `byog/Test/Phase0EncounterHarness.java`——固定场景 `baseline-two-guards:v1`、headless runner
- `byog/AI/StrategicIntent.java`——Goal/Strategy 枚举
- `byog/TileEngine/Tileset.java`——WALL/FLOOR/NOTHING 等 tile 常量

## 2. 阶段目标与成功定义

Phase 0 建立了一个固定遭遇实验台，但每个敌人仍然收到完整世界地图——它们可以"看穿"墙壁，精确知道玩家位置和 HP，不同敌人收到完全相同的输入。

Phase 1 的目标是：**切断这条全知输入通道，让每个敌人只能获得自己合理感知到的信息。** 具体来说：

1. 引入 `PerceptionSystem`，用射线投射（raycasting）计算每个敌人的视野范围（FOV, field of view），墙后的 tile 和实体不可见。
2. 引入 `ObservationEnvelope`，作为每敌人独立的私有感知结果：可见地形、可见实体、自身状态、听觉事件和行动反馈，每人一份，各不相同。
3. 引入正式、可保存的 agent 身份（`agentId`），结束 Phase 0 只能使用 scenario-local `actorKey` 的局限。
4. 更新 `RuleBasedBrain` 使其基于 `ObservationEnvelope`（而非全知 `GameStateSnapshot`）决策。
5. 用 `baseline-two-guards` 固定场景验证：Guard A 能看见玩家（无墙遮挡），Guard B 不能（墙在中间）。

成功后，我们将第一次拥有真正的"信息不对称"——这是后续 Agent 推理、通信和战术反制的物理基础。没有私有感知，就没有"有限知识"；没有有限知识，Agent 只是一个用更多算力做同样事情的脚本。

## 3. 起始事实

### 3.1 全知输入的确切位置

当前 `Enemy.updateAI()` 在 [Enemy.java:62-63](../../../byog/Entity/Enemy.java) 构造：

```java
GameStateSnapshot snapshot = new GameStateSnapshot(world,
        player.getPosition(), this.getPosition(), this.getId());
```

`world` 是完整的 `TETile[][]`，`player.getPosition()` 是玩家的精确坐标。`RuleBasedBrain.think()` 直接使用 `state.getWorld()` 获取全地图：[RuleBasedBrain.java:48](../../../byog/AI/RuleBasedBrain.java)。

### 3.2 `baseline-two-guards:v1` 的感知结构

固定场景地图（ASCII，从上到下为高 y 到低 y）：

```text
#################   y=7
#.......#......>#   y=6
#.......#...B...#   y=5
#.......#####.###   y=4
#...............#   y=3
#..P.....A......#   y=2
#...............#   y=1
#################   y=0
```

- **Guard A** 坐标 `(9, 2)`，玩家 `(3, 2)`，曼哈顿距离 6，中间无墙 → LOS 畅通
- **Guard B** 坐标 `(12, 5)`，玩家 `(3, 2)`，曼哈顿距离 12，射线穿过 `(7, 4)` 的墙 → LOS 被遮挡

这个场景天然适合验证 Phase 1：同一个地图、同一时刻，A 和 B 看到的世界不同。

### 3.3 当前代码中可复用的部分

- `EntityManager.findEntityAt(Position)`（[EntityManager.java:71](../../../byog/Entity/EntityManager.java)）：可用作"在该位置看到了什么实体"的查询入口，不引入新的搜索机制。
- `Entity.canStandOn()`（[Entity.java:56](../../../byog/Entity/Entity.java)）：判断 tile 是否阻挡视线和移动——WALL 和 NOTHING 阻挡，FLOOR/STAIRS 不阻挡。
- `Tileset.WALL` / `Tileset.NOTHING`（`byog/TileEngine/Tileset.java`）：可直接用于判断 LOS 遮挡。
- `MathHelper.manhattanDistance()`：用于缩窄 FOV 候选范围，但不替代射线检测——曼哈顿距离不能判断遮挡。
- `Phase0EncounterHarness`：可复用其 ASCII parser、实体构造和 headless 调度，只需切换感知模式。
- `AgentTrace.Context/Event/Sink/InMemorySink`：可扩展事件类型，兼容现有 canonical JSON 生成。

### 3.4 需要改变的部分

- `GameStateSnapshot` 的全知设计——Phase 1 之后它不再是敌人的主要感知输入，降级为仅 Phase 0 兼容。
- `EnemyBrain` 接口——当前只有 `think(GameStateSnapshot)`，需要增加接受 `ObservationEnvelope` 的方法。
- `Enemy.updateAI()`——需要从 "构造全知 snapshot" 改为 "调用 PerceptionSystem 生成私有 observation"。
- `Entity.id`——不能作为正式 agent 身份，Phase 1 需要引入 `agentId`。

### 3.5 Phase 0 遗留问题与本阶段的关系

| Phase 0 遗留问题 | Phase 1 处理方式 |
|-----------------|-----------------|
| 全知 snapshot | **解决**：引入私有 `ObservationEnvelope` 替代 |
| Entity ID 不可存档 | **解决**：引入正式 `agentId`，持久化到存档 |
| HashMap 游戏调度 | 不解决（Phase 2+） |
| ActionResult 语义 | 不解决（Phase 4） |
| 碰撞测试前提修复 | 不解决（后续玩法决定） |

### 3.6 构建与测试基线

- 编译命令、测试运行命令与 Phase 0 相同（见 `PHASE_0_COMPLETION.md`）。
- Phase 0 的 19 个测试必须保持绿色——这意味着 `Enemy.updateAI()` 的 legacy 路径（`perceptionEnabled=false`）不能有行为变化。**不需要每次代码改动都运行测试**，仅在 Builder 明确要求时再运行。
- 新增 Phase 1 测试不纳入 Phase 0 的 golden comparison。

## 4. 需求追踪

| Requirement | Phase 1 的处理 | 验收证据 |
|-------------|----------------|----------|
| INV-01 独立身份 | 为 Enemy 引入正式 `agentId`（String），随存档保存；不再依赖 JVM 自增 `id` | P1-T05：agentId 跨实例稳定 |
| INV-02 有限知识 | `PerceptionSystem` 计算每敌人的私有 FOV；`ObservationEnvelope` 只包含可见信息 | P1-T01：墙后玩家不可见；P1-T02：未观察到的 HP/位置不泄露 |
| INV-03 世界内通信 | 本阶段只定义听觉事件字段，不实现消息传播 | Out of Scope 检查 |
| INV-04 Java 权威 | 感知计算、遮挡判断、实体可见性全部由 Java 引擎决定 | 射线投射测试 |
| INV-05 分层控制 | `RuleBasedBrain` 从 `ObservationEnvelope` 决策，不直接接触全知世界 | P1-T04：决策依据只有 observation 中的可见信息 |
| INV-06 严格契约 | `ObservationEnvelope` 包含 `runId/floorId/agentId/observationSeq/observedAtTurn` 版本字段 | P1-T06：observation 包含完整身份与版本 |
| INV-07 异步时效 | 不在本阶段实现 | Out of Scope 检查 |
| INV-08 可追踪评估 | `AgentTrace` 新增 `OBSERVATION_GENERATED` 事件类型，记录每个敌人的 observation 摘要 | P1-T07：trace 可区分 A/B 的不同 observation |
| INV-09 玩法价值 | `baseline-two-guards` 场景可展示 A 看见玩家、B 看不见的信息不对称 | P1-R01：人工验收场景对比 |

## 5. 范围与非目标

### 5.1 In Scope

- Java `PerceptionSystem` 类：Bresenham 射线投射 FOV 计算。
- Java `ObservationEnvelope` 类：每敌人独立的私有感知结果。
- `VisibleEntity` 记录：可见实体的类型、位置、可见 HP（仅当实体在 LOS 内）。
- `HeardEvent` 记录：听觉事件（Phase 1 只记录自身行动产生的声音，不实现声波传播）。
- 正式 `agentId`：String 类型，只属于 Enemy，在 Enemy 构造时分配并随存档保存。
- `runId`、`floorId`、`observationSeq`、`observedAtTurn` 版本字段。
- `Enemy` 新增 `perceptionEnabled` 开关，默认 `false` 保持 Phase 0 兼容。
- `EnemyBrain` 接口新增 `thinkFromObservation(ObservationEnvelope)` 方法（带 default 实现）。
- `RuleBasedBrain` 实现新方法，基于 `ObservationEnvelope` 决策。
- `AgentTrace` 新增 `OBSERVATION_GENERATED` 事件类型。
- Phase 1 JUnit 测试套件（P1-T01 至 P1-T08）。
- Phase 1 harness：复用 `baseline-two-guards` 场景但启用私有感知。

### 5.2 Out of Scope

- 自然语言场景描述。
- 嗅觉、第六感等非视觉/听觉感知。
- 声波传播、衰减、距离判定（Phase 5）。
- 记忆系统——敌人不记住"曾经看到但现已离开视野"的信息（Phase 6）。
- 视野外的 tile 完全不可见（不保留"探索过的地图"）——这是 Phase 1 的简化，Phase 6 可引入 `knownTerrain`。
- Python、LLM、HTTP、异步 mailbox。
- 修改 `ActionResult` 语义。
- 程序地图生成中的感知相关逻辑（Phase 1 只用固定场景验证）。

## 6. 已锁定决定、假设与待决定项

### 6.1 已锁定决定

#### D1-01：FOV 算法使用 Bresenham 射线投射

从敌人位置向 sight range（曼哈顿距离）内的每个 tile 发射一条 Bresenham 直线。射线沿路径逐格前进，遇到 WALL 或 NOTHING tile 即终止——该 tile 本身视为可见（你可以看见墙），但射线不再继续。射线到达目标 tile 且途中未被阻挡 → 目标 tile 可见。

选择 Bresenham 的理由：
- 确定性，不依赖浮点运算——同输入同输出，支持 canonical trace。
- 计算量可控——sight range 为 7 时最多约 200 条射线，远低于实时渲染压力。
- 行为可直观理解——"你能从当前位置画一条直线连到目标，且中间不穿过墙"。

Bresenham 的一个已知特性是可能在墙角对角线方向"漏光"（射线从墙格子旁边滑过去）。对于 Phase 1 的 AI 感知，这不构成问题：
- `baseline-two-guards` 地图中极少（甚至没有）对角墙角漏光情况。即便极少数情况下漏光，看到就是看到了——敌人偶尔多看到一点不影响游戏体验。
- 不需要使用 supercover 变体来堵漏光。

#### D1-02：可见性判断规则

对于一个 tile 或实体：
- 如果射线到达该位置且中途未被 WALL/NOTHING 阻挡 → **可见**，否则**不可见**。
- 可见的 WALL tile 计入 FOV（敌人知道墙在哪里）。
- 可见的实体：如果实体所在 tile 在 FOV 内，则实体对该敌人可见；记录其类型和当前位置。
- 玩家的 HP 和精确位置只在玩家可见时出现在 `ObservationEnvelope` 中。不可见时，`visibleEntities` 列表不含玩家。

#### D1-03：正式 agentId

```java
// Entity.java 新增
protected String agentId;  // 正式可存档身份

// Enemy 构造时分配（由 EnemySpawner 或 Harness 提供）
public Enemy(..., String agentId, ...)
```

- `agentId` 是 String 类型，稳定性由调用方保证。Phase 1 harness 使用 `"guard-a"` / `"guard-b"`（与 actorKey 一致）。
- `agentId` 随存档持久化（Phase 1 在 `GameSaveData` 中新增字段）。
- 保留现有 `id`（JVM 自增）用于内部日志和非 canonical 诊断，canonical trace 只使用 `agentId`。

#### D1-04：perceptionEnabled 开关与向后兼容

```java
// Enemy.java 新增字段
private boolean perceptionEnabled = false;  // Phase 0 兼容默认值
```

- `updateAI()` 根据此标志选择路径：
  - `false`：构造 `GameStateSnapshot`（Phase 0 兼容路径），事件类型 `LEGACY_DECISION_INPUT`
  - `true`：调用 `PerceptionSystem.computeObservation()` → `ObservationEnvelope`，事件类型 `OBSERVATION_GENERATED`
- Phase 0 测试不修改 `Enemy` 构造，`perceptionEnabled` 保持 `false` → golden 不变。
- Phase 1 harness 显式设置 `true`。

这个开关是临时工程手段，Phase 2+ 应该移除，使私有感知成为唯一路径。

#### D1-05：AgentTrace 新增感知事件

新增事件类型 `OBSERVATION_GENERATED`，在 `PerceptionSystem` 计算完成后、Brain 决策前记录。Event 扩展字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `visiblePlayer` | nullable Boolean | 玩家是否在 FOV 内 |
| `visibleEntityCount` | nullable Integer | 可见实体总数（不含自身） |
| `fovTileCount` | nullable Integer | FOV 内可见 tile 数量 |
| `heardEventCount` | nullable Integer | 当前 tick 听到的事件数 |

这些字段加入 `AgentTrace.Event`，`inputKind` 从 `legacy-full-world-snapshot` 变为 `private-perception-v1`。

Phase 1 的 canonical JSON schema version 升级为 `phase1.trace.v1`。

#### D1-06：Phase 1 golden baseline 独立于 Phase 0

- Phase 1 生成自己的 baseline：`documents/baselines/phase1_rule_baseline_v1.json`。
- Phase 0 golden 保持不变，P0-T09 仍引用 `phase0_rule_baseline_v1.json`。
- Phase 1 测试 P1-T08 引用 `phase1_rule_baseline_v1.json`。

### 6.2 暂时假设

- **假设 A1**：sight range 内、LOS 畅通但距离超过 sight range 的 tile 不可见。这是合理的——sight range 是感知上限，不是"我能看穿无限远的直线"。
- **假设 A2**：Bresenham 射线投射的结果在 JDK 19 上稳定。如果未来 JDK 升级导致整数运算行为变化（极端不可能），需要重新生成 golden。
- **假设 A3**：Phase 1 的 `RuleBasedBrain` 基于 `ObservationEnvelope` 决策时，如果玩家不可见，应回退到 PATROL（与 Phase 0 的"超出 sight range"行为一致）。这保证了在 `baseline-two-guards` 场景中，B 的行为不变（仍为 PATROL），但原因从"距离太远"变为"看不见"——后者是更正确的语义。
- **假设 A4**：Phase 1 harness 的 12 tick baseline 中，Guard A 仍能 CHASE 玩家并最终接近或攻击。因为 A 在 LOS 内能看到玩家，距离 6 ≤ sight range 7。如果 12 tick 不足以让 A 走到玩家旁边，可以调整 tick 数，但优先保持 12 tick 与 Phase 0 对齐以支持对比。

### 6.3 需要 Builder 决定

#### Q1：`HeardEvent` 的 Phase 1 范围

当前 `AttackAction.execute()` 已经会改变实体 HP。Phase 1 可以：**（a）只定义 `HeardEvent` 数据结构和 ObservationEnvelope 中的字段，不实际产生听觉事件**（最简，focus 在视觉）或 **（b）让 AttackAction 产生简单的听觉事件（"在 (x,y) 发生了攻击"），附近敌人在下一 tick 的 observation 中收到**。

Advisor 建议：**(a)**。Phase 1 的核心价值是视觉感知（FOV/LOS）。听觉事件的传播规则（距离衰减、障碍物影响、优先级）是 Phase 5 的主题，在 Phase 1 中只定义数据结构而不实现传播，避免 scope creep。如果 Builder 认为至少需要"自己听到自己的攻击"来验证数据流，也可以选 (b) 的最小版本。

#### Q2：`RuleBasedBrain` 的 patrol 目标生成在没有全图时如何处理

当 `RuleBasedBrain` 基于 `ObservationEnvelope` 决策且需要 PATROL 时，`generateRandomPatrolPos()` 之前依赖 `state.getWorld()` 获取全图来验证 target tile 是否为 FLOOR。在私有感知下，它只能访问 `ObservationEnvelope` 中的 `visibleTiles`。

选项：
- **(a)** 只从当前可见的 FLOOR tile 中随机选择目标——更真实（敌人只朝看得见的地方巡逻），但可能导致巡逻范围缩小。
- **(b)** 让 `ObservationEnvelope` 携带敌人所在房间的完整地形（不限于 FOV）——需要额外的房间感知逻辑。

Advisor 建议：**(a)**。简单、正确、符合有限知识原则。巡逻范围缩小是符合直觉的——敌人在看不到的地方只能凭记忆行动（Phase 6 才引入记忆）。如果后续发现这让巡逻太无聊，可以在 Phase 6 加入"已知但不可见"的地形缓存。

## 7. 目标架构与数据流

```text
Phase 1（私有感知路径，perceptionEnabled=true）：

Enemy.updateAI()
  │
  ├─ PerceptionSystem.computeObservation(world, entityMgr, self, player)
  │     ├─ Bresenham ray 扫描 sightRange 内所有 tile
  │     ├─ 可见 tile 集合 → boolean[][] visibleMask
  │     ├─ 对可见 tile 位置的实体 → VisibleEntity 列表
  │     └─ 听觉事件收集（Phase 1: 仅自身行动）→ HeardEvent 列表
  │     └─ 返回 ObservationEnvelope
  │
  ├─ traceSink.record(OBSERVATION_GENERATED)   ← 新事件
  │
  ├─ brain.thinkFromObservation(observation)   ← 新接口方法
  │     └─ 返回 StrategicIntent（与 Phase 0 相同）
  │
  ├─ traceSink.record(INTENT_SELECTED)
  │
  └─ [后续：ClassicalPlanner / ActionQueue / Action / RETRY]
       （与 Phase 0 完全相同，不变）

Phase 0（legacy 路径，perceptionEnabled=false）：

Enemy.updateAI()
  │
  ├─ new GameStateSnapshot(world, player.pos, self.pos, self.id)
  ├─ traceSink.record(LEGACY_DECISION_INPUT)
  ├─ brain.think(snapshot)                     ← 旧方法
  ├─ traceSink.record(INTENT_SELECTED)
  └─ [后续不变]
```

重要边界：

- `PerceptionSystem` 是纯函数：输入 world + 坐标，输出 observation。不修改任何状态。
- `ObservationEnvelope` 是不可变对象：创建后字段不变化。
- `VisibleEntity` 只包含 Entity 的类型、位置和可见 HP——不暴露完整 Entity 引用（防止通过引用泄漏信息）。
- `visibleMask` 是 `boolean[][]`，只标记可见性——不复制 tile 数据（节省内存）。Brain 需要查询 tile 类型时通过坐标访问原 world 数组。这是有意的不完全隔离：为了 patrol 目标生成需要验证 tile 类型，但 Brain 不应遍历不可见的 tile。
- Phase 1 harness 强制 `perceptionEnabled=true`，但旧 harness（Phase 0 测试）不修改，保持兼容。

谁创建数据？谁可以读取和修改？
- `PerceptionSystem` 创建 `ObservationEnvelope`（纯计算，不缓存）。
- `Enemy.updateAI()` 持有 observation，传递给 Brain、trace sink。
- Brain 可以读取 observation 但不能修改（不可变对象）。
- 没有任何代码可以将 observation "升级"回全知 snapshot。

失败、取消或过期时发生什么？
- `PerceptionSystem.computeObservation()` 不会失败——它总是返回合法的 `ObservationEnvelope`（最坏情况下所有 tile 不可见、没有可见实体）。
- Brain 的 fallback 行为（无可见目标 → PATROL）是 Brain 内部逻辑，PerceptionSystem 不参与决策。

哪些数据会保存，何时清理？
- `ObservationEnvelope` 不跨 tick 保存——每次 `updateAI()` 重新计算。
- `agentId` 存入 `Enemy`，随存档持久化。
- Observation 字段（seq、turn）只在 trace 中持久化。

## 8. 接口与数据契约

### 8.1 `PerceptionSystem`

```java
package byog.Core;

/**
 * 私有感知计算系统。纯函数，不持有状态。
 * 为每个敌人计算该 tick 内可感知的世界切片。
 */
public final class PerceptionSystem {

    /** 计算单个敌人的私有感知结果 */
    public static ObservationEnvelope computeObservation(
            TETile[][] world,
            EntityManager entityMgr,
            Enemy self,
            Player player,
            int sightRange,
            long currentTurn);

    /**
     * Bresenham 直线检测：从 (x0,y0) 到 (x1,y1) 的射线是否被 WALL/NOTHING 阻挡。
     * @return true 如果终点可见（射线路径上无障碍物）
     */
    public static boolean hasLineOfSight(
            TETile[][] world, int x0, int y0, int x1, int y1);

    /** 判断某个 tile 是否阻挡视线（WALL 或 NOTHING） */
    public static boolean blocksVision(TETile tile);
}
```

`computeObservation` 的算法流程：
1. 以 `self.getPosition()` 为中心，遍历曼哈顿距离 ≤ `sightRange` 的所有 tile。
2. 对每个候选 tile，调用 `hasLineOfSight(world, selfX, selfY, tileX, tileY)`。
3. 如果 LOS 畅通，标记 `visibleMask[tileX][tileY] = true`。
4. 对每个 `visibleMask == true` 的位置，调用 `entityMgr.findEntityAt(pos)` 检查实体。
5. 收集所有在可见位置的活实体（不含自身）→ `VisibleEntity` 列表。
6. 收集当前 tick 的听觉事件 → `HeardEvent` 列表。
7. 构造并返回 `ObservationEnvelope`。

### 8.2 `ObservationEnvelope`

```java
package byog.Core;

/**
 * 单个敌人、单个 tick 的私有感知结果。不可变。
 */
public final class ObservationEnvelope {

    // ── 身份与版本 ──
    public String getRunId();           // 本次游戏运行的 ID
    public int getFloorId();            // 当前楼层
    public String getAgentId();         // 此敌人的正式 agentId
    public long getObservationSeq();    // 此敌人的 observation 序号（从 0 单调增）
    public long getObservedAtTurn();    // 游戏逻辑 turn 号

    // ── 自身状态 ──
    public Position getSelfPosition();
    public int getSelfHp();

    // ── 可见世界 ──
    public boolean[][] getVisibleMask();           // 与 world 同尺寸，true=可见
    public boolean isVisible(int x, int y);        // 便捷查询
    public List<VisibleEntity> getVisibleEntities(); // 可见实体列表
    public boolean canSeePlayer();                 // 玩家是否在可见实体中

    // ── 听觉事件 ──
    public List<HeardEvent> getHeardEvents();      // 当前 tick 听到的事件

    // ── 查询辅助 ──
    /** 如果玩家可见则返回 VisibleEntity 中的玩家信息，否则返回 null */
    public VisibleEntity getVisiblePlayer();
}
```

构造参数（包内可见，由 `PerceptionSystem` 创建）：

| 字段 | 类型 | 规则 |
|------|------|------|
| `runId` | String | 游戏运行标识 |
| `floorId` | int | 当前楼层号 |
| `agentId` | String | 敌人的正式 agentId |
| `observationSeq` | long | 自增序号，此次运行中此敌人的第几个 observation |
| `observedAtTurn` | long | 游戏全局 turn 计数器 |
| `selfPosition` | Position | 敌人自身位置 |
| `selfHp` | int | 敌人自身 HP |
| `visibleMask` | boolean[][] | 与 world 同尺寸 |
| `visibleEntities` | List\<VisibleEntity\> | 可见实体（不含自身） |
| `heardEvents` | List\<HeardEvent\> | 听觉事件 |

### 8.3 `VisibleEntity`

```java
package byog.Core;

/**
 * 感知系统中可见实体的简要信息。不暴露完整 Entity 引用以防信息泄漏。
 */
public final class VisibleEntity {
    public enum EntityType { PLAYER, ENEMY, OTHER }

    public EntityType getType();       // 实体类型
    public Position getPosition();     // 所在位置
    public int getVisibleHp();         // 可见 HP（type==PLAYER 时总是可见；type==ENEMY 时可选）
    public String getAgentId();        // 如果是 enemy，返回其 agentId
}
```

`EntityType` 按优先级分配：`PLAYER > ENEMY > OTHER`（每个位置只有一个活实体）。

### 8.4 `HeardEvent`

```java
package byog.Core;

/**
 * 敌人听到的声音事件。Phase 1 只定义结构，实现最小听觉。
 */
public final class HeardEvent {
    public enum SoundType { ATTACK, MOVE, DEATH, ALERT }

    public SoundType getType();        // 声音类型
    public Position getSourcePosition(); // 声源位置（可能不精确）
    public long getTurn();             // 产生声音的 turn
}
```

Phase 1 实现范围（取决于 Builder 对 Q1 的决定）：
- 至少定义此类及其字段。
- 如果选 Q1(b)：`AttackAction.execute()` 产生 ATTACK 事件，`Enemy.die()` 产生 DEATH 事件，`MoveAction.execute()` 产生 MOVE 事件。事件存入全局队列（`EntityManager` 或新的 `SoundEventBus`），下一 tick 的 `computeObservation` 收集附近事件。

### 8.5 `EnemyBrain` 接口扩展

```java
public interface EnemyBrain {
    /** Phase 0 legacy：接收全知 GameStateSnapshot */
    StrategicIntent think(GameStateSnapshot state);

    /** Phase 1+：接收私有 ObservationEnvelope。默认抛异常，子类按需实现 */
    default StrategicIntent thinkFromObservation(ObservationEnvelope obs) {
        throw new UnsupportedOperationException(
            "This brain does not support private observation");
    }
}
```

### 8.6 `RuleBasedBrain` 更新

`RuleBasedBrain` 同时实现两个方法：
- `think(GameStateSnapshot)`：保持现有逻辑不变（Phase 0 兼容）。
- `thinkFromObservation(ObservationEnvelope)`：新逻辑——检查 `obs.canSeePlayer()`，是则根据距离决定 CHASE/ATTACK，否则 PATROL。patrol 目标从 `visibleMask` 中的可见 FLOOR tile 随机选择。

### 8.7 `Enemy` 更新

```java
public class Enemy extends Entity {
    // 新增字段
    private boolean perceptionEnabled = false;     // Phase 0 兼容默认
    private String agentId;                         // 正式可存档身份
    private long observationSeq = 0;                // observation 序号

    // 修改构造方法，增加 agentId 参数
    public Enemy(Position position, TETile tile, int hp, int sightRange,
                 int moveInterval, int attackDamage, int damageVariance,
                 Random random, String agentId) { ... }

    // 新增 setter（供 harness 使用）
    public void setPerceptionEnabled(boolean enabled);

    // updateAI() 内部根据 perceptionEnabled 选择路径（见 §7 架构图）
}
```

旧构造方法签名改变：增加 `String agentId` 参数，所有调用方需要更新（`Phase0EncounterHarness`、`Enemy.spawnEnemies()` 等）。

### 8.8 `AgentTrace` 扩展

新增：

```java
// EventType 枚举新增值
OBSERVATION_GENERATED

// SCHEMA_VERSION 升级
public static final String SCHEMA_VERSION = "phase1.trace.v1";

// Event 新增字段（仅 OBSERVATION_GENERATED 时填充）
public final Boolean visiblePlayer;       // 玩家是否在 FOV 内
public final Integer visibleEntityCount;  // 可见实体总数（不含自身）
public final Integer fovTileCount;        // FOV 内可见 tile 数量

// 新增 factory
public static Event observationGenerated(Context context,
        boolean visiblePlayer, int visibleEntityCount, int fovTileCount);
```

`inputKind` 在 Phase 1 事件中设为 `"private-perception-v1"`。

### 8.9 `Phase1EncounterHarness`

```java
package byog.Core;

/**
 * Phase 1 固定遭遇测试支架。复用 baseline-two-guards:v1 场景，但启用私有感知。
 */
public final class Phase1EncounterHarness {
    /** 构建启用私有感知的双守卫场景 */
    public static Phase1EncounterHarness baselineTwoGuardsV1(
            String runId, int floorId);

    /** 与 Phase 0 harness 相同的 step/runTicks/canonicalTraceJson/canonicalState */
    // ... (接口与 Phase0EncounterHarness 相同)
}
```

内部实现：复用 `Phase0EncounterHarness.fromAscii()` 的地图解析逻辑（直接委托），然后对创建的 Enemy 调用 `setPerceptionEnabled(true)` 并设置 `runId`/`floorId`。

### 8.10 存档格式扩展

`GameSaveData` 新增字段：

```java
// 每个实体的 agentId（用于恢复身份）
private Map<Integer, String> entityAgentIds;  // Entity.id → agentId
```

不需要新增存档文件格式版本——`agentId` 是可选字段，旧存档加载时缺失则用 `Entity.id` 生成临时 agentId（`"entity-" + id`）。

## 9. 逐文件变更计划

| 文件 | 新建/修改 | 责任 | 关键变更 | 不应包含 |
|------|-----------|------|----------|----------|
| `byog/Perception/PerceptionSystem.java` | **新建** | FOV 计算 | Bresenham LOS、computeObservation、blocksVision | 记忆系统、声波传播 |
| `byog/Perception/ObservationEnvelope.java` | **新建** | 私有感知结果 | 身份字段、visibleMask、visibleEntities、heardEvents | 自然语言描述、向量检索 |
| `byog/Perception/VisibleEntity.java` | **新建** | 可见实体摘要 | EntityType 枚举、position、visibleHp、agentId | 完整 Entity 引用 |
| `byog/Perception/HeardEvent.java` | **新建** | 听觉事件结构 | SoundType 枚举、sourcePosition、turn | 声波传播、衰减（Phase 5） |
| `byog/Entity/Enemy.java` | 修改 | 私有感知开关、正式身份 + 新构造 | perceptionEnabled、agentId、observationSeq、updateAI 双路径 | 修改 retry/Action/Planner |
| `byog/AI/EnemyBrain.java` | 修改 | 接口扩展 | `thinkFromObservation(ObservationEnvelope)` default 方法 | 移除 think(GameStateSnapshot) |
| `byog/AI/RuleBasedBrain.java` | 修改 | 实现私有感知决策 | `thinkFromObservation()` 实现 | 修改现有 think() 逻辑 |
| `byog/Trace/AgentTrace.java` | 修改 | 新增感知事件 | OBSERVATION_GENERATED、新字段、v1 schema | 修改 Phase 0 事件格式 |
| `byog/Test/Phase1EncounterHarness.java` | **新建** | Phase 1 headless runner | 委托 fromAscii、启用 perception、Phase 1 baseline 生成 | Renderer、键盘、默认存档 |
| `byog/Test/Phase1EncounterTest.java` | **新建** | Phase 1 contract tests | P1-T01 至 P1-T08 | 人工观察代替断言 |
| `byog/Test/Phase1TestSuite.java` | **新建** | 单一 JUnit 入口 | 聚合 Phase 0 Suite + Phase 1 测试 | MathTest |
| `byog/IO/GameSaveData.java` | 修改 | 存档 agentId | entityAgentIds Map + 读写逻辑 | 修改存档格式版本号 |
| `byog/Test/Phase0EncounterHarness.java` | 修改 | 适配新 Enemy 构造 | 传入 agentId 参数 | 改变行为或 golden |
| `byog/Test/EnemyCollisionTest.java` | 修改 | 适配新 Enemy 构造 | 传入 agentId 参数 | 改变测试逻辑 |
| `documents/baselines/phase1_rule_baseline_v1.json` | **新建 artifact** | Phase 1 canonical baseline | 私有感知路径的 golden trace | Phase 0 golden 覆盖 |
| `PHASE_1_COMPLETION.md` | 验收时新建 | 关闭阶段与交接 | commit、命令、结果、偏差、Phase 2 输入 | — |

**受影响的旧调用方（需更新 Enemy 构造方法调用）**：
- `Phase0EncounterHarness.fromAscii()`——传入 `"guard-a"` / `"guard-b"` 作为 agentId
- `Enemy.spawnEnemies()`——传入 `"enemy-" + i` 作为 agentId
- `EnemyCollisionTest`——传入测试用 agentId

## 10. 实施顺序

### Step 1.1：定义数据结构（无行为变化）

**输入**：当前仓库，无新增依赖。

**具体改动**：
1. 创建 `VisibleEntity.java`（纯数据类，3 个字段 + getter）。
2. 创建 `HeardEvent.java`（纯数据类，3 个字段 + getter）。
3. 创建 `ObservationEnvelope.java`（纯数据类，所有字段 + getter）。
4. 在 `Enemy.java` 中添加 `agentId` 字段 + getter；非 Agent 实体不持有 agentId。

**验证**：编译通过。

**产出**：4 个新类 + Entity 修改。

### Step 1.2：实现 PerceptionSystem（无副作用）

**输入**：Step 1.1 的数据结构。

**具体改动**：
1. 创建 `PerceptionSystem.java`，实现 `blocksVision()`。
2. 实现 `hasLineOfSight()`（Bresenham）。
3. 实现 `computeObservation()`——遍历、射线检测、实体收集。
4. 编写独立单元测试：`PerceptionSystem` 的小地图 LOS 测试。

**验证**：`PerceptionSystem.hasLineOfSight()` 在手写小地图上正确判断遮挡。

**产出**：`PerceptionSystem.java` + LOS 单元测试。

### Step 1.3：适配 Enemy 构造方法（全调用方更新）

**输入**：Step 1.2 的 PerceptionSystem。

**具体改动**：
1. 修改 `Enemy` 构造方法，增加 `String agentId` 参数。
2. 更新所有调用方：`Phase0EncounterHarness.fromAscii()`、`Enemy.spawnEnemies()`、`EnemyCollisionTest`。
3. 为 `Enemy` 添加 `perceptionEnabled` 字段和 setter（默认 false）。
4. 为 `Enemy` 添加 `observationSeq` 字段。

**验证**：编译通过 + Phase 0 全部 19 个测试绿色（`perceptionEnabled=false`，行为不变）。

**产出**：修改后的 Enemy + 所有调用方更新。

### Step 1.4：扩展 EnemyBrain 接口 + 更新 RuleBasedBrain

**输入**：Step 1.3 的 Enemy。

**具体改动**：
1. `EnemyBrain` 添加 `thinkFromObservation(ObservationEnvelope)` default 方法。
2. `RuleBasedBrain` 实现 `thinkFromObservation()`：
   - 用 `obs.canSeePlayer()` 代替 `dist <= sightRange`。
   - patrol 目标从 `obs.getVisibleMask()` 的 FLOOR tile 中选（需要 world 引用，Brain 需持有或通过 observation 查询 tile 类型）。
3. 处理 Brain 需要查询 tile 类型的问题：要么 `ObservationEnvelope` 也存储可见 tile 的 tile 引用，要么 Brain 保留 `TETile[][] world` 引用但只通过 `visibleMask` 查询。

**验证**：编译通过。

**产出**：扩展的 EnemyBrain + 更新后的 RuleBasedBrain。

> **实现提示**：Brain 需要判断一个可见位置是否为 FLOOR。最简单的做法是 `ObservationEnvelope` 在构造时接受 `TETile[][] world` 引用，提供 `isWalkable(int x, int y)` 方法（先检查 visibleMask，再检查 tile 类型）。这样 Brain 不需要直接持有 world 引用。

### Step 1.5：更新 Enemy.updateAI() 双路径

**输入**：Step 1.4 的 Brain 更新。

**具体改动**：
1. 在 `Enemy.updateAI()` 中根据 `perceptionEnabled` 选择路径（见 §7 架构图）。
2. 私有感知路径：调用 `PerceptionSystem.computeObservation()` → `brain.thinkFromObservation()`。
3. 两个路径的后续（Planner/ActionQueue/Action）相同。

**验证**：Phase 0 全部 19 个测试仍绿色（legacy 路径未变）。

**产出**：修改后的 Enemy.updateAI()。

### Step 1.6：扩展 AgentTrace

**输入**：Step 1.5 的 Enemy。

**具体改动**：
1. 新增 `EventType.OBSERVATION_GENERATED`。
2. 新增 `Event` 字段：`visiblePlayer`、`visibleEntityCount`、`fovTileCount`。
3. 新增 `Event.observationGenerated()` factory。
4. 更新 `InMemorySink.toCanonicalJson()` 输出新字段。
5. Scheme version 升级为 `phase1.trace.v1`。
6. 在 `Enemy.updateAI()` 的私有感知路径中调用 traceSink。

**验证**：Phase 0 trace golden 不匹配（schema version 变了）→ 不破坏，因为 Phase 0 harness 使用 legacy 路径不变。

**产出**：扩展后的 AgentTrace。

### Step 1.7：创建 Phase1EncounterHarness + Phase 1 测试

**输入**：所有前序步骤。

**具体改动**：
1. 创建 `Phase1EncounterHarness`：
   - 委托 `Phase0EncounterHarness.fromAscii()` 解析地图。
   - 创建 Enemy 后 `setPerceptionEnabled(true)`。
   - 设置 `runId`/`floorId`。
2. 创建 `Phase1EncounterTest`（P1-T01 至 P1-T08）。
3. 创建 `Phase1TestSuite`（聚合 Phase0TestSuite + 新测试）。

**验证**：所有 Phase 1 测试通过。

**产出**：Phase 1 harness + 测试套件。

### Step 1.8：生成 Phase 1 golden baseline

**输入**：Step 1.7 的 harness。

**具体改动**：
1. 创建 `Phase1BaselineMain`（参考 `Phase0BaselineMain`）。
2. 运行生成 `phase1_rule_baseline_v1.json`。
3. 人工审查 JSON：确认 A 的 observation 包含 player，B 的不包含。
4. 加入 golden comparison 测试 P1-T08。

**验证**：P1-T08 绿色 + 人工审查。

**产出**：Phase 1 golden baseline。

### Step 1.9：存档扩展

**输入**：Step 1.8 完成后的稳定 agentId。

**具体改动**：
1. `GameSaveData` 新增 `entityAgentIds` Map。
2. 存档时写入 `Entity.id → agentId` 映射。
3. 读档时恢复 `agentId`（缺失则用 `"entity-" + id` 兜底）。

**验证**：手动存读档后 agentId 不变（Phase 1 暂不为此写自动化测试，Phase 2 的确定性桥接测试会覆盖）。

**产出**：存档中的 agentId 持久化。

## 11. 测试与验收矩阵

| Test ID | 场景 | 核心断言 | 自动/人工 | 对应需求 |
|---------|------|----------|-----------|----------|
| P1-T01 | `guardB_cannot_see_player_behind_wall` | B 的 observation 中 `canSeePlayer()==false`，`visibleEntities` 不含 player | 自动 | INV-02 |
| P1-T02 | `guardA_can_see_player_no_wall` | A 的 observation 中 `canSeePlayer()==true`，player 在 visibleEntities 中且 HP 可见 | 自动 | INV-02 |
| P1-T03 | `different_enemies_different_observations` | A 和 B 同 tick 的 visibleEntityCount 不同 | 自动 | INV-02/08 |
| P1-T04 | `brain_decision_based_on_private_obs` | B 在私有感知下首次 intent 为 PATROL（看不到玩家），A 为 CHASE | 自动 | INV-05 |
| P1-T05 | `agentId_stable_across_instances` | 两次构造的 Enemy（同 agentId）的 agentId 字符串一致；与 JVM 自增 id 无关 | 自动 | INV-01 |
| P1-T06 | `observation_has_identity_fields` | ObservationEnvelope 包含 runId、floorId、agentId、observationSeq、observedAtTurn | 自动 | INV-06 |
| P1-T07 | `trace_contains_perception_events` | canonical trace 每 tick 包含 OBSERVATION_GENERATED 事件，且 A/B 的 visiblePlayer 不同 | 自动 | INV-08 |
| P1-T08 | `phase1_rule_baseline_matches_golden` | canonical trace + final state 与 phase1 golden JSON 一致 | 自动 | INV-08/09 |
| P1-R01 | `phase0_suite_still_green` | Phase 0 全部 19 个测试通过（legacy 路径未受影响） | 自动回归 | INV-04 |
| P1-R02 | `game_loop_still_works_with_private_perception` | 用 Phase 1 harness 运行 12 tick，A 最终位置接近玩家，B 不接近 | 自动 | INV-02/05 |
| P1-M01 | baseline JSON review | 无隐藏非确定字段，inputKind 为 `private-perception-v1`，A 的 observation 与 B 不同 | 人工一次 | INV-02/08 |

普通验收命令：

```powershell
$javaSources = Get-ChildItem byog -Recurse -Filter *.java | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -cp "..\library-sp18\javalib\*" -d out $javaSources
java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" org.junit.runner.JUnitCore byog.Core.Phase1TestSuite
```

## 12. Observability 与运行证据

Phase 1 canonical evidence 包括（继承 Phase 0 全部 + 新增）：

- `schemaVersion`（升级为 `phase1.trace.v1`）
- `inputKind`（`private-perception-v1` vs `legacy-full-world-snapshot`）
- `visiblePlayer`（boolean）
- `visibleEntityCount`（integer）
- `fovTileCount`（integer）
- observation identity：`runId`/`floorId`/`agentId`/`observationSeq`/`observedAtTurn`

以下内容只允许作为诊断，不参与比较：
- Bresenham 的中间计算步骤
- `Entity.id`（JVM 自增）
- wall-clock 信息

## 13. 失败处理、兼容与迁移

### 向后兼容

- `Enemy` 旧构造方法签名改为增加 `agentId` 参数。这是**唯一的不兼容变更**——所有调用方必须更新。Impact：Phase 0 harness、Enemy.spawnEnemies()、EnemyCollisionTest。变更波及面大是已知的，慢慢改即可，IDE 会标红所有遗漏的调用方，不用担心漏掉。
- `Enemy.updateAI(world, entityMgr, player)` 签名不变，行为在 `perceptionEnabled=false` 时完全不变。
- Phase 0 golden 不变（legacy 路径未改）。
- `GameStateSnapshot` 保留，不移除——Phase 0 兼容需要。

### 渐进迁移路径

- Phase 1 引入 `perceptionEnabled` 开关。
- Phase 2 将 Game.java 的生产路径切换为 `perceptionEnabled=true`。
- Phase 3+ 移除 `perceptionEnabled` 开关和 `GameStateSnapshot`。

### 存档兼容

- `agentId` 在 `GameSaveData` 中为可选字段。旧存档加载时缺失 → 自动生成 `"entity-" + id` 临时 agentId。
- 不新增存档格式版本号——`agentId` 的缺失不会导致加载失败。

### Feature flag

不需要——`perceptionEnabled` 本身就是 flag。

## 14. 风险与停止条件

### 主要风险

1. **Bresenham 射线"漏光"**：射线穿过墙角时可能"泄漏"（看到墙后的实体）。**已确认：不作处理。** `baseline-two-guards` 地图中极少（甚至没有）对角墙角漏光情况。即便极少数情况下漏光，看到就是看到了——敌人偶尔多看到一点不影响游戏体验。不需要专门设计墙角测试，也不需要使用 supercover 变体。
2. **FOV 性能**：sight range=7 时约 200 次射线投射；如果 sight range 在后续阶段增大到 20+，考虑使用递归阴影投射（recursive shadowcasting）替代。Phase 1 的 Bresenham 足以覆盖当前范围。
3. **Enemy 构造签名变更波及面**：所有创建 Enemy 的地方都需要更新。遗漏的调用方会导致编译失败（IDE 可检测），不是隐蔽运行时 bug。
4. **Phase 0 golden 意外漂移**：Enemy 构造更新后，如果 agentId 参数影响了任何行为，立即回退排查。注意**不需要每次代码改动都运行 Phase 0 测试**，仅在 Builder 要求时运行。
5. **Scope creep**：视觉感知完成后，容易顺手加入记忆、听觉传播、视线通信等功能。必须严格遵守 Out of Scope。
6. **`visibleMask` 内存开销**：每个 Enemy 每 tick 创建一个 `boolean[world.width][world.height]`。当前世界约 50×30，每个 mask 约 1.5KB，两个守卫共 3KB/tick，完全可接受。

### 停止条件

出现以下情况时停止实现并回到 Spec：
- Phase 0 测试在非 golden 测试中失败（行为被意外改变）。
- Bresenham 实现无法在合理时间内达到正确的遮挡行为。
- 需要对 Intent 或 Roadmap 进行实质性修改（如引入自然语言感知）。
- `ObservationEnvelope` 的设计导致 `RuleBasedBrain` 无法做出合理的 patrol 决策。
- 实现者准备在 Phase 1 中加入声音传播、地图记忆或通信。

## 15. Definition of Done

- [ ] P1-T01 至 P1-T08 全部通过。
- [ ] P1-R01：Phase 0 全部 19 个测试绿色（legacy 路径未受影响）。
- [ ] P1-R02：Phase 1 harness 运行 12 tick，A 和 B 行为符合私有感知预期。
- [ ] `Phase1TestSuite` 可通过单一命令运行，全程 headless。
- [ ] `ObservationEnvelope` 包含完整的身份与版本字段。
- [ ] 墙后玩家不出现在 B 的 observation 中（P1-T01）。
- [ ] A 和 B 的 observation 不同（P1-T03）。
- [ ] `RuleBasedBrain` 基于 `ObservationEnvelope` 做出正确决策（P1-T04）。
- [ ] Phase 1 golden baseline 已生成，普通测试不覆盖它。
- [ ] `agentId` 存入存档、可从存档恢复。
- [ ] 没有引入 Python、LLM、网络、向量数据库或记忆系统。
- [ ] 已知偏差和遗留问题写入 Completion。
- [ ] `PHASE_1_COMPLETION.md` 已生成，并列出 Phase 2 所需 artifacts。

## 16. 下一阶段交接

Phase 2 可以依赖：

- `PerceptionSystem` 和 `ObservationEnvelope` 接口（稳定）。
- `baseline-two-guards:v1` 场景在私有感知下的行为证据。
- 正式、可存档 `agentId`。
- Phase 1 JUnit suite 与编译命令。
- `OBSERVATION_GENERATED` trace 事件类型。
- 扩展后的 `AgentTrace`（`phase1.trace.v1`）。

Phase 2 不得假设：

- `ObservationEnvelope` 包含地图记忆或已探索区域。
- 听觉事件传播已实现（Phase 5）。
- `perceptionEnabled` 已在生产路径启用。
- `GameStateSnapshot` 已被移除。
- 异步桥接已就绪。
- agentId 在跨进程场景中的行为已定义。

Phase 2 的首要任务将是：以 `ObservationEnvelope` 为数据契约，构建 Java → Python → Java 的确定性端到端桥接，用 fake Python runtime 验证异步不阻塞游戏循环、过期决策被正确丢弃。

## 附录 A：Spec 自检

- [x] 已先审计仓库，不是从 Roadmap 自由扩写。
- [x] 未把旧 LLM Build Guide 当作当前事实。
- [x] 区分了事实、决定、假设和后续问题。
- [x] 引用了关键代码位置和 Phase 0 遗留问题。
- [x] `baseline-two-guards:v1` 墙结构可直接验证 FOV 遮挡（B 看不见玩家）。
- [x] 没有提前建设 Phase 3+ 的 Agent runtime / LLM / 通信。
- [x] 每项完成条件都有可执行验证方式。
- [x] 明确列出了 Phase 2 可以依赖与不得假设的内容。
- [x] `perceptionEnabled` 开关确保 Phase 0 golden 不受影响。
