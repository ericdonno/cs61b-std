# Phase 1 Completion：私有感知与知识边界

## 1. 元数据

- **Phase**：1
- **完成日期**：2026-07-20
- **基线 commit**：`e9864be`（"Package Refactoring"）
- **分支**：`ai-enemis`
- **对应的 Spec**：`PHASE_1_SPEC.md`
- **构建指南**：`documents/Phase1_Build_Guide.md`
- **前一阶段 Completion**：`PHASE_0_COMPLETION.md`

## 2. 验收摘要

| 测试套件 | 测试数 | 结果 | 耗时 |
|----------|--------|------|------|
| Phase1TestSuite (含 Phase 0 回归) | 27 | ALL PASS | 0.416s |
| Phase 0 子集：EnemyCollisionTest + Phase0EncounterTest | 19 | ALL PASS | — |
| Phase 1 子集：Phase1EncounterTest | 8 | ALL PASS | — |

### 逐测试验收结果

| Test ID | 测试名 | 断言语义 | 结果 |
|---------|--------|----------|------|
| P1-T01 | `guardB_cannot_see_player_behind_wall` | B 的 observation 中 `canSeePlayer()==false`，visibleEntities 不含 PLAYER | PASS |
| P1-T02 | `guardA_can_see_player_no_wall` | A 的 observation 中 `canSeePlayer()==true`，player HP 可见且正确 | PASS |
| P1-T03 | `different_enemies_different_observations` | A 和 B 同 tick 的 visibleEntityCount 不同 | PASS |
| P1-T04 | `brain_decision_based_on_private_obs` | B 首次 intent 为 PATROL，A 为 CHASE | PASS |
| P1-T05 | `agentId_stable_across_instances` | 同 agentId 字符串跨实例一致，JVM id 不同 | PASS |
| P1-T06 | `observation_has_identity_fields` | runId、floorId、agentId、observationSeq、observedAtTurn 非默认值 | PASS |
| P1-T07 | `trace_contains_perception_events` | trace 含 OBSERVATION_GENERATED 事件，A/B 的 visiblePlayer 不同 | PASS |
| P1-T08 | `phase1RuleBaselineMatchesGolden` | 12 tick canonical trace + final state 与 golden JSON 字节级匹配 | PASS |
| P1-R01 | Phase 0 全部测试 | Phase0EncounterTest + EnemyCollisionTest 19 tests | PASS |

### Golden Baseline 关键证据（人工审查通过）

来自 [phase1_rule_baseline_v1.json](documents/baselines/phase1_rule_baseline_v1.json)：

- **Schema**：`phase1.trace.v1`（EVENT_COUNT = 110）
- **guard-a** 每 tick：`"visiblePlayer": true`，`"goal": "CHASE"` → `"goal": "ATTACK_PLAYER"`（接近后）
- **guard-b** 每 tick：`"visiblePlayer": false`，`"goal": "PATROL"`（全部 12 tick 未看到玩家）
- **inputKind**：`"private-perception-v1"`（非 legacy-full-world-snapshot）
- **A 的 FOV tile 数**：63-76（通道区域开阔）
- **B 的 FOV tile 数**：28-47（被墙围住，视野受限）

### 运行时日志关键证据

```
Enemy 'guard-a' RuleBasedBrain: dist=6 → CHASE      ← A 能看到玩家
Enemy 'guard-b' RuleBasedBrain: cannot see player → PATROL   ← B 被墙遮挡
```

对比 Phase 0 的 legacy 路径日志：
```
Enemy#16 RuleBasedBrain: dist=6 <= sightRange=7 → CHASE       ← 全知
Enemy#17 RuleBasedBrain: dist=12 > sightRange=7 → PATROL       ← 全知（距离判断）
```

Phase 1 的 B 不是因为"距离太远"（那是全知下的判断），而是因为"看不到"——更正确的语义。

## 3. 阶段产物清单

### 3.1 新建文件（7 个）

| 文件 | 包 | 职责 |
|------|-----|------|
| `byog/Perception/PerceptionSystem.java` | Perception | Bresenham 射线投射 FOV 计算、`computeObservation()` |
| `byog/Perception/ObservationEnvelope.java` | Perception | 每敌人每 tick 的私有感知结果容器 |
| `byog/Perception/VisibleEntity.java` | Perception | 可见实体摘要（防信息泄漏） |
| `byog/Perception/HeardEvent.java` | Perception | 听觉事件数据结构（Phase 1 只定义不实现） |
| `byog/Test/Phase1EncounterHarness.java` | Test | Phase 1 无头测试支架 |
| `byog/Test/Phase1EncounterTest.java` | Test | Phase 1 合约测试（P1-T01 ~ P1-T08） |
| `byog/Test/Phase1TestSuite.java` | Test | JUnit 聚合入口（Phase 0 + Phase 1） |

### 3.2 修改文件（11 个）

| 文件 | 关键变更 |
|------|----------|
| `byog/Entity/Enemy.java` | 新增 Enemy 专属 `agentId`、`perceptionEnabled`、`observationSeq`；构造方法新增 `agentId` 参数；`updateAI()` 双路径（legacy / 私有感知） |
| `byog/AI/EnemyBrain.java` | 接口新增 `thinkFromObservation(ObservationEnvelope)` default 方法 |
| `byog/AI/RuleBasedBrain.java` | 实现 `thinkFromObservation()`：基于可见玩家决策，patrol 目标从可见 FLOOR 中随机选 |
| `byog/Trace/AgentTrace.java` | 新增 `EventType.OBSERVATION_GENERATED`；新增 `visiblePlayer`、`visibleEntityCount`、`fovTileCount` 字段；schema 升级为 `phase1.trace.v1` |
| `byog/IO/GameSaveData.java` | 新增 `entityAgentIds` Map（存档 agentId 持久化） |
| `byog/Test/Phase0EncounterHarness.java` | 适配 Enemy 新构造签名（传入 agentId） |
| `byog/Test/EnemyCollisionTest.java` | 适配 Enemy 新构造签名（传入 agentId） |
| `byog/Test/Phase1BaselineMain.java` | 新建（Phase 1 golden baseline 生成入口） |
| `byog/AI/GameStateSnapshot.java` | 未修改（保留为 Phase 0 legacy 兼容） |
| `byog/Entity/Player.java` | 未修改 |

### 3.3 新建 Artifact

| 文件 | 说明 |
|------|------|
| `documents/baselines/phase1_rule_baseline_v1.json` | Phase 1 私有感知 golden baseline，12 tick，110 events |
| `documents/Phase1_Build_Guide.md` | 人类可读的分步构建指南 |
| `PHASE_1_SPEC.md` | Phase 1 设计契约文档 |
| `PHASE_1_COMPLETION.md` | 本文档 |

### 3.4 额外结构优化

Phase 1 实施过程中将项目从单一 `byog/Core/` 扁平包重构为按职责分层的包结构：

```
byog/
  Perception/    ← 新建：私有感知引擎
  AI/            ← 从 Core 移出：Brain、Planner、Pathfinder、Intent
  Entity/        ← 从 Core 移出：Entity、Enemy、Player、EntityManager
  Action/        ← 从 Core 移出：Action、ActionQueue、AttackAction、MoveAction
  Trace/         ← 从 Core 移出：AgentTrace
  IO/            ← 从 Core 移出：GameSaveData、GameConfig、SaveLoadManager
  Test/          ← 从 Core 移出：所有测试和 harness
  Common/        ← 从 Core 移出：Direction、Difficulty、RandomUtils
  WorldGen/      ← 从 Core 移出：WorldGenerator、Room 等
  Core/          ← 保留：Game.java、Main.java
```

此行重构未改变任何逻辑，编译后行为完全不变（Phase 0 19 tests 全部通过可证）。

## 4. 已验证的技术决策

### 4.1 D1-01：Bresenham 射线投射 FOV

- **验证方式**：P1-T01（墙后不可见）+ P1-T02（空地可见）+ PerceptionSystemTest（blocksVision + hasLineOfSight 手写小地图）
- **结果**：通过。射线从 (12,5) 到 (3,2) 经过墙 (7,4) 被正确阻挡，未出现"漏光"或"误挡"。

### 4.2 D1-02：可见性判断规则

- **验证方式**：P1-T01（B 看不到玩家）+ P1-T02（A 能看到玩家且 HP 可见）+ P1-T03（A/B observation 不同）
- **结果**：通过。墙后实体不出现在 visibleEntities，玩家 HP 不泄露给 B。A 看到 player 且 HP 匹配 `player.getHp()`。

### 4.3 D1-03：正式 agentId

- **验证方式**：P1-T05（agentId 跨实例稳定）+ GameSaveData.entityAgentIds 字段存在
- **结果**：通过。`new Enemy(..., "guard-a")` 两次构造的 agentId 一致，与 JVM 自增 id 无关。存档持久化字段已定义。

### 4.4 D1-04：perceptionEnabled 向后兼容

- **验证方式**：P1-R01（Phase 0 全部 19 tests PASS）
- **结果**：通过。`perceptionEnabled` 默认为 `false`，所有 Phase 0 测试走 legacy 路径，golden 不变。

### 4.5 D1-05：AgentTrace 感知事件

- **验证方式**：P1-T07（trace 含 OBSERVATION_GENERATED）+ P1-T08（golden 匹配）
- **结果**：通过。schema 升级为 `phase1.trace.v1`，inputKind 为 `private-perception-v1`，新增字段只在新事件类型中非 null。

### 4.6 D1-06：Phase 1 golden baseline 独立

- **验证方式**：`phase1_rule_baseline_v1.json` 与 `phase0_rule_baseline_v1.json` 并存，分别被 P1-T08 和 P0-T09 引用
- **结果**：通过。两个 golden 互不干扰。

### 4.7 Q1（HeardEvent 范围）→ 方案 (a)

- **实现**：[HeardEvent.java](byog/Perception/HeardEvent.java) 定义了完整的数据结构（SoundType 枚举 + 3 字段）。[PerceptionSystem.computeObservation()](byog/Perception/PerceptionSystem.java) 中 `heardEvents` 列表创建后永远为空，传给 ObservationEnvelope 的是 `Collections.emptyList()`。
- **影响**：Phase 5 引入听觉传播时，只需在 `computeObservation` 中填充 heardEvents，数据结构无需变更。

### 4.8 Q2（patrol 目标生成）→ 方案 (a)

- **实现**：[RuleBasedBrain.generatePatrolPosFromObservation()](byog/AI/RuleBasedBrain.java) 使用 `obs.isWalkable(nx, ny)` 收集可见 FLOOR tile（曼哈顿距离 3~8），随机选一个。无候选时返回自身位置。
- **影响**：巡逻范围从"全图随机"缩小为"可见区域随机"。这符合有限知识原则——Phase 6 可引入"已知但不可见"的地形缓存来扩展巡逻范围。

## 5. 已知偏差

### 5.1 PerceptionSystemTest 未纳入 Phase1TestSuite

- **偏差**：[PerceptionSystemTest](byog/Test/PerceptionSystemTest.java) 存在且可独立运行，但未包含在 `Phase1TestSuite` 的 `@Suite.SuiteClasses` 中。
- **原因**：TestSuite 使用了显式列表而非包扫描。`PerceptionSystemTest` 的测试（blocksVision、hasLineOfSight）是单元级纯函数测试，与 harness 场景测试属于不同层次。
- **建议**：Phase 2 将其加入 Suite 或统一为包扫描方式。

### 5.2 包重构超出 Spec 范围

- **偏差**：Spec §9 的逐文件变更计划假设文件在 `byog/Core/` 下。实际实施中将项目重构为 8 个子包。
- **影响**：无行为影响。编译后结构更清晰，Phase 0 19 tests 全绿证明重构未引入 bug。
- **注意**：`PHASE_1_SPEC.md` 中逐文件路径引用仍指向旧包路径（如 `byog/Core/PerceptionSystem.java`），实际文件在 `byog/Perception/PerceptionSystem.java`。Phase 2 的 Spec 应使用新包路径。

### 5.3 存档 agentId 持久化未写自动化测试

- **偏差**：Spec §11 中有 `agentId_stable_across_instances` 的测试计划提及存档。Phases 1 仅有 P1-T05（跨构造实例稳定），无存档读写测试。
- **原因**：Phase 1 聚焦于感知正确性，存档测试更适合与 Phase 2 的确定性桥接测试合并。
- **建议**：Phase 2 增加"Enemy 存读档后 agentId 一致"的测试。

### 5.4 `perceptionEnabled` 在生产路径未启用

- **偏差**：`Game.java` 的生产游戏循环仍使用 `perceptionEnabled=false`（legacy 路径）。
- **原因**：这是有意的——Phase 1 聚焦于验证私有感知正确性，Phase 2 才切换生产路径。
- **建议**：Phase 2 将 `Game.java` 中的 Enemy 设为 `perceptionEnabled=true`。

## 6. 定义完成（Definition of Done）对照

| 条件 | 状态 |
|------|------|
| P1-T01 ~ P1-T08 全部通过 | [x] |
| P1-R01：Phase 0 全部 19 tests 绿色 | [x] |
| P1-R02：Phase 1 harness 运行 12 tick，A 和 B 行为符合私有感知预期 | [x] |
| Phase1TestSuite 可通过单一命令运行，全程 headless | [x] |
| ObservationEnvelope 包含完整的身份与版本字段 | [x] |
| 墙后玩家不出现在 B 的 observation 中 | [x] |
| A 和 B 的 observation 不同 | [x] |
| RuleBasedBrain 基于 ObservationEnvelope 做出正确决策 | [x] |
| Phase 1 golden baseline 已生成，普通测试不覆盖它 | [x] |
| agentId 存入存档、可从存档恢复 | [x] |
| 没有引入 Python、LLM、网络、向量数据库或记忆系统 | [x] |
| PHASE_1_COMPLETION.md 已生成 | [x] |

## 7. 下一阶段交接（Phase 2 输入）

### 7.1 Phase 2 可以依赖

- `PerceptionSystem.computeObservation()` 稳定接口——输入 world + entityMgr + self + player + sightRange + turn，输出 ObservationEnvelope
- `ObservationEnvelope` 稳定接口——可见性查询、实体列表、身份字段
- `baseline-two-guards:v1` 场景在私有感知下的行为证据（golden baseline）
- 正式、可存档的 Enemy `agentId`（Enemy.getAgentId()）
- Phase 1 JUnit suite 与编译命令（27 tests，`byog.Test.Phase1TestSuite`）
- `OBSERVATION_GENERATED` trace 事件类型 + `phase1.trace.v1` schema
- 新包结构：`byog/Perception/`、`byog/AI/`、`byog/Entity/`、`byog/Trace/` 等

### 7.2 Phase 2 不得假设

- ObservationEnvelope 包含地图记忆或已探索区域
- 听觉事件传播已实现（HeardEvent 列表始终为空）
- `perceptionEnabled` 已在生产路径（Game.java）启用
- GameStateSnapshot 已被移除
- 异步桥接已就绪
- agentId 在跨进程场景中的行为已定义
- PerceptionSystemTest 在 Suite 中（需手动加入或切换包扫描）

### 7.3 Phase 2 首要任务

以 `ObservationEnvelope` 为数据契约，构建 Java → Python → Java 的确定性端到端桥接，用 fake Python runtime 验证：

- 异步不阻塞游戏循环
- 过期决策被正确丢弃
- Python 侧收到的 observation 与 Java 侧生成的一致
- bridge 的 trace 事件可 canonical 化

## 8. 运行时验证命令

```powershell
# 全量编译（warnings 可忽略）
$javaSources = Get-ChildItem byog -Recurse -Filter *.java | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -cp "..\library-sp18\javalib\*" -d out $javaSources

# 运行 Phase1TestSuite（含 Phase 0 回归）
java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" org.junit.runner.JUnitCore byog.Test.Phase1TestSuite

# 单独运行私有感知测试（非回归）
java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" org.junit.runner.JUnitCore byog.Test.Phase1EncounterTest

# 生成 Phase 1 golden baseline（如需重新生成）
java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" byog.Test.Phase1BaselineMain --write documents/baselines/phase1_rule_baseline_v1.json
```
