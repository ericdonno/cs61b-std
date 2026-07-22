# Phase 0 Completion Report

## 元数据

- **Phase**: 0
- **完成日期**: 2026-07-20（遗留问题修复于 2026-07-20）
- **基线分支**: `ai-enemis`
- **基线 commit**: `a97353e6404fc06a063bea35e4648b30ef9f6176`
- **JDK**: 19
- **Baseline SHA256**: `F79068C340EAF3991B071BC7DEA3E72CE0E6A78C26D8F618754698F322771DF9`

## 构建与运行命令

```powershell
# 编译
$javaSources = Get-ChildItem byog -Recurse -Filter *.java | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -cp "..\library-sp18\javalib\*" -d out $javaSources

# 运行测试
java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" org.junit.runner.JUnitCore byog.Core.Phase0TestSuite

# 生成 baseline（仅在变更场景时运行）
java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" byog.Core.Phase0BaselineMain --write documents/baselines/phase0_rule_baseline_v1.json
```

## 测试结果

```
OK (19 tests)
```

| Test ID | 名称 | 结果 |
|---------|------|------|
| P0-R01 | `EnemyCollisionTest.testEnemyNotWalkOntoPlayer` | PASS |
| P0-T01 | `fixtureHasExpectedLayout` | PASS |
| P0-T02 | `fixtureRejectsInvalidAscii` (10 个子用例) | PASS |
| P0-T03 | `fixtureBuildIsDeterministic` | PASS |
| P0-T04 | `sameRunProducesSameCanonicalTrace` | PASS |
| P0-T05 | `bothGuardsProduceExpectedInitialIntent` | PASS |
| P0-T06 | `traceContainsDecisionLifecycle` | PASS |
| P0-T07 | `liveEntitiesRespectWorldInvariants` | PASS |
| P0-T08 | `fixedSeedWorldIsDeterministicSmokeTest` | PASS |
| P0-T09 | `ruleBaselineMatchesGolden` | PASS |

## Definition of Done 检查

- [x] P0-T01 至 P0-T09、P0-R01 全部通过
- [x] `Phase0TestSuite` 可通过文档中的单一命令运行，全程不打开窗口
- [x] 同一 JVM 连续运行两次得到字节一致的 canonical trace（P0-T04 验证）
- [x] trace 中可独立过滤 guard-a 和 guard-b，并关联 input → intent → action → raw result（P0-T06 验证）
- [x] baseline JSON 存在，普通测试不会覆盖它（仅 Phase0BaselineMain --write 可写）
- [x] 现有游戏入口仍能编译；旧 `Enemy.updateAI(world, entityMgr, player)` 调用保持兼容（委托到 NO_OP）
- [x] Phase 0 没有把 legacy full-world snapshot 宣称为私有 observation（明确标记 `LEGACY_DECISION_INPUT`）
- [x] 没有引入 Python、LLM、网络、向量数据库或正式记忆系统
- [x] 已知失败、测试排除项和未解决 legacy 行为写入 Completion
- [x] `PHASE_0_COMPLETION.md` 已生成，并列出 Phase 1 所需 artifacts

## 新增/修改文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `byog/Core/AgentTrace.java` | 新建 | Context、Event、Sink、InMemorySink、canonical JSON |
| `byog/Core/Enemy.java` | 修改 | 新增 trace overload `updateAI(..., Context, Sink)`，旧签名委托 NO_OP |
| `byog/Core/Phase0EncounterHarness.java` | 新建 | ASCII fixture parser、headless runner、canonical state/JSON |
| `byog/Core/Phase0EncounterTestHelper.java` | 新建→修改 | 原复制 parser 逻辑，现已委托到生产 `fromAscii()` |
| `byog/Core/Phase0EncounterTest.java` | 新建→修改 | P0-T01~T09；新增重复 A/B/Stairs 用例；P0-T06 改为按 tick 配对；P0-T09 改为 assert 失败 |
| `byog/Core/Phase0TestSuite.java` | 新建 | JUnit Suite 聚合入口 |
| `byog/Core/Phase0BaselineMain.java` | 新建 | `--write` 显式生成/打印 baseline |
| `byog/Core/EnemyCollisionTest.java` | 修改 | 玩家 HP 100→10000，修复活体碰撞测试前提 |
| `documents/baselines/phase0_rule_baseline_v1.json` | 新建 artifact | RuleBasedBrain canonical baseline |

## 已修复的遗留问题（2026-07-20）

1. **P0-T09 假绿**：`ruleBaselineMatchesGolden()` 原先在 baseline 文件缺失时 `return`（静默通过），已改为 `assertTrue` 明确失败。
2. **P0-T02 parser 分离**：`Phase0EncounterTestHelper` 原先复制了一份解析逻辑，现已委托到 `Phase0EncounterHarness.fromAscii()`——测试和生产共享唯一 parser。
3. **P0-T02 重复角色覆盖不全**：新增了重复 guard-a、guard-b、stairs 三个 P0-T02 子用例，覆盖全部 4 种重复场景。
4. **P0-T06 跨 tick 配对**：`assertActionOrdinalConsistency()` 原先用全局 Set 比较 ordinal，无法检测跨 tick 混淆。已替换为 `assertActionLifecyclePerTick()`，按 `(actorKey, logicalTick, actionOrdinal)` 分组验证，同时验证 attempt 在 result 之前。
5. **Sink 异常保护**：`Enemy.updateAI()` 中四处 `traceSink.record()` 调用已通过 `safeRecord()` 包裹 try-catch，防御性保护 AI 行为不被 trace 异常中断。

## 未解决的 Legacy 问题（Phase 1 起点）

1. **全知 snapshot**：`GameStateSnapshot` 包含完整世界地图，RuleBasedBrain 可以"看到"墙后的玩家。trace 已标记 `LEGACY_DECISION_INPUT`，Phase 1 需要用 `PerceptionSystem` 替换。
2. **Entity ID**：`Entity.id` 是 JVM 全局静态自增整数，不可存档、不可用作正式 identity。Phase 0 canonical trace 使用 scenario-local `actorKey`（`guard-a`/`guard-b`）取代。
3. **HashMap 游戏调度**：`EntityManager.getAllEntities()` 返回无顺序契约的 values view。Phase 0 在 harness 中显式规定了调用顺序，但键盘 Game loop 仍依赖 HashMap 遍历顺序。
4. **ActionResult 语义**：攻击命中返回 `DAMAGE` 而非 `SUCCESS`，导致 `Enemy.updateAI()` 的 retry 循环在攻击后继续执行。Phase 0 原样记录，Phase 4 应处理。
5. **存档身份**：当前存档格式不保存 Agent 身份、Brain state 或随机序列。Phase 1 需要定义正式 agentId 和存档迁移。
6. **碰撞测试前提修复**：仅通过提高玩家 HP 绕过死亡；未定义"尸体是否占格"的玩法规则。

## Phase 1 可依赖的 Artifacts

- `baseline-two-guards:v1` 固定几何与 actorKey
- headless、显式顺序的 `Phase0EncounterHarness`
- trace Context/Event/Sink 与 canonical JSON
- RuleBasedBrain baseline artifact (`phase0_rule_baseline_v1.json`)
- Phase 0 JUnit suite 与编译命令
- 已被明确标记的 `LEGACY_DECISION_INPUT` seam

## Phase 1 不得假设的事项

- 当前 `GameStateSnapshot` 已满足知识边界
- scenario actorKey 已经等于生产可保存 agentId
- HashMap 游戏调度已经确定化
- ActionResult 已经是最终 ActionOutcome
- 存读档会保持 Agent 身份、Brain state 或随机序列
- 固定 fixture 已经实现 LOS、声音或通信
