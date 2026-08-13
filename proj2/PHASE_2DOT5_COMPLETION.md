# Phase 2.5 Completion：持久世界状态与可读敌人感知

## 1. 元数据

- **完成日期**：2026-08-03；2026-08-13 增补玩家连续移动与巡视面墙脱困修正（新手感仍待用户复验）
- **分支**：`ai-enemis`
- **基线 HEAD**：`142452b4c32050536f282f6647f349ac4c492e31`（`phase2.5 done by deepseekv4flash/reasonix`）
- **工作树**：本次验证对应该 HEAD 加玩家输入文档、生产代码与测试改动；用户已有的 `1.md` 修改和三个 `run*_out.txt` 不属于本次交付
- **规范**：`PHASE_2DOT5_SPEC.md`
- **构建指南**：`PHASE_2DOT5_BUILD_GUIDE.md`
- **运行环境**：Windows / PowerShell，Java 19.0.2，Python 3.14.6

> 玩家输入修正尚未形成最终 commit（用户未授权提交）。正式提交后需回填 commit。

## 2. 验收摘要

| 入口 | 测试数 | 结果 | 说明 |
|------|--------|------|------|
| Java 编译 | 全部 `byog/**/*.java` | PASS | 2026-08-13 复验 15.101s；无错误，仅 unchecked 警告 |
| `PatrolControllerTest` | 9 | PASS | 0.363s；含面墙无候选时转向后恢复巡视的回归 |
| `CoreGameplayRegressionSuite` | 174 | PASS | 19 个 leaf 类；9.283s；单一 deterministic gate |
| `SocketTransportTest` | 9 | PASS | 独立网络回归 |
| `AgentRuntimeIntegrationTest` | 7 | PASS | 真实 Python 进程端到端 |
| `MathTest` | 5 | PASS | 独立工具测试 |
| Python unittest | 26 | PASS | 0.651s；含 3 个共享 fixture runner |

资源检查：集成测试创建的 Python 子进程由持有句柄的 harness 有界关闭；TCP 监听数 0。

## 3. 可复查命令

```powershell
$javaSources = Get-ChildItem byog -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
javac -encoding UTF-8 -cp "..\library-sp18\javalib\*" -d out $javaSources

java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.PatrolControllerTest

java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.CoreGameplayRegressionSuite

java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Bridge.SocketTransportTest

java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.AgentRuntimeIntegrationTest

java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.MathTest

cd agent/python
python -B -m unittest discover -s tests
```

源码命名审计（byog / config / agent/python 内 `Phase[0-9]`、`Step[0-9]`、`阶段 N`、`步骤 N`
以及 `P0-`/`P1-`/`P2-` Test ID 注释）已清零；`lab5/` 课程代码中的普通循环变量
`step` 不属于开发阶段编号。

## 4. 交付物与 Spec Test ID 状态

### 玩家、苹果与存档

| Test ID | 场景 | 状态 | 证据 |
|---------|------|------|------|
| P25-PLAYER-01 | 非满 HP 下楼 HP 不变、对象不同、charge=0 | [x] | `PlayerRunStateTest.nextFloorRestoresHpOnNewPlayerObject` |
| P25-PLAYER-02 | 读档恢复 charge、动画计时不恢复 | [x] | `WorldSaveRepositoryTest.roundTripRestoresFullTypedState` + `restoreCharge` 入口 |
| P25-PLAYER-03 | save/run DTO 不含 Session/queue/hover/animation | [x] | `PlayerRunStateTest.saveDtoCarriesNoRuntimeState`（字段白名单） |
| P25-APPLE-01 | 三难度数量闭区间、非法配置回退 | [x] | `HealthPackTest.difficultyConfigBoundsAreClosedAndSafe` |
| P25-APPLE-02 | 相同输入重复生成一致、随机隔离 | [x] | `sameSeedProducesIdenticalPlacement` / `placementDoesNotConsumeForeignRandomStreams` |
| P25-APPLE-03 | 排除房间与占位 | [x] | `placementExcludesSpawnAndStairRoomsAndOccupants` |
| P25-APPLE-04 | 候选不足有界 | [x] | `insufficientCandidatesPlacesAllWithoutLooping` / `zeroCandidatesYieldsEmptyWithoutFailure` |
| P25-APPLE-05 | 缺血拾取治疗 clamp | [x] | `pickupHealsAndConsumesWhenInjured` |
| P25-APPLE-06 | 满血经过不消耗 | [x] | `pickupDoesNotConsumeAtFullHp` |
| P25-APPLE-07 | 敌人经过不消失 | [x] | `enemyWalkingOverAppleLeavesItIntact` |
| P25-APPLE-08 | Agent 快照 apple→FLOOR | [x] | `appleMapsToFloorInAgentSnapshot` + `DirectionalFovTest.appleInsideFacingHalfStaysFloorInSnapshot` |
| P25-SAVE-01 | 多世界、摘要、稳定排序 | [x] | `multipleWorldsListWithStableSummarySort` |
| P25-SAVE-02 | 大小写无关判重、覆盖新 worldId | [x] | `overwriteGeneratesNewWorldIdAndDeletesOldFile` |
| P25-SAVE-03 | 首次保存与 `:q` 只覆盖活动世界 | [x] | `firstSavePersistsAndReloads` + Game 状态机 |
| P25-SAVE-04 | 原子失败不破坏旧档 | [x] | `writeFailureKeepsExistingWorldIntact` / `successfulSaveLeavesNoTempFiles` |
| P25-SAVE-05 | 坏档隔离、不删除 | [x] | `corruptFileDoesNotBreakListOrDeleteItself` |
| P25-SAVE-06 | 状态完整恢复 | [x] | `roundTripRestoresFullTypedState` |
| P25-SAVE-07 | 路径安全 | [x] | `invalidWorldIdsCannotEscapeSaveRoot` |
| P25-SAVE-08 | 注入时钟决定保存时间 | [x] | `savedAtComesFromInjectedClock` |
| P25-SAVE-09 | 覆盖清理失败：新档保留、旧项隐藏 | [x] | `leftoverReplacedWorldIsHiddenFromList` |

### 朝向、FOV 与 UI

| Test ID | 场景 | 状态 | 证据 |
|---------|------|------|------|
| P25-FACING-01 | 固定 seed 朝向确定且隔离 | [x] | `spawnedFacingIsDeterministicAndIsolated` |
| P25-FACING-02 | 成功/受阻移动都更新朝向 | [x] | `moveUpdatesFacingOnSuccessAndBlock` |
| P25-FACING-03 | 命中/落空攻击都更新朝向 | [x] | `attackUpdatesFacingOnHitAndMiss` |
| P25-FACING-04 | Turn 消耗 cadence、Wait 不改朝向 | [x] | `turnChangesFacingAndWaitDoesNot` / `aiTickLoopExecutesAtMostOneActionPerCooldown` |
| P25-FOV-01 | 四朝向半菱形、中线可见 | [x] | `directionalMaskKeepsOnlyFacingHalf` |
| P25-FOV-02 | 背后玩家不可见 | [x] | `playerBehindFacingIsNotVisibleAnywhere` |
| P25-FOV-03 | 地图边缘不越界 | [x] | `mapCornerDoesNotThrowForAnyFacing` |
| P25-FOV-04 | 墙与墙角 LOS 保留 | [x] | `wallBlocksVisionInsideFacingHalf` |
| P25-FOV-05 | 全向 baseline 与原菱形一致 | [x] | `omnidirectionalMatchesLegacyDiamond` |
| P25-FOV-06 | 双敌人不同朝向不同 Observation | [x] | `twoEnemiesWithDifferentFacingSeeDifferentPlayers` |
| P25-UI-01 | bottom offset 坐标互逆 | [x] | `screenMappingIsInverseAndKeepsWorldSize` |
| P25-UI-02 | enemy hover model 来源正确 | [x] | `enemyHoverShowsHpBarAndFacing` |
| P25-UI-03 | apple/terrain/no hover 文案 | [x] | `appleHoverShowsHealAndFullHpHint` 等 |
| P25-UI-04 | 人工视觉（E、金色方向标、FOV、emoji/fallback） | [x] | 用户于 2026-08-13 确认除玩家操控手感外无问题 |
| P25-INPUT-01 | 新方向按下立即移动 | [x] | `PlayerInputCadenceTest.newDirectionMovesImmediately` |
| P25-INPUT-02 | 长按固定 cadence、慢帧不追赶 | [x] | `heldDirectionRepeatsWithoutCatchingUp` |
| P25-INPUT-03 | 松开停止、再按立即 | [x] | `releaseStopsAndPressAgainMovesImmediately` |
| P25-INPUT-04 | 最近方向优先与释放恢复 | [x] | `newestHeldDirectionWinsAndReleaseRestoresPrevious` |

### 巡视、契约与回归

| Test ID | 场景 | 状态 | 证据 |
|---------|------|------|------|
| P25-PATROL-01 | 目标持续且在私有候选内 | [x] | `targetStaysStableWhileTraveling` |
| P25-PATROL-02 | 相同脚本 trace 稳定 | [x] | `sameScriptRepeatsSameTrace` |
| P25-PATROL-03 | 到达 Wait→顺时针 Turn×2→重选（当前实现；与 Spec 的 3 次冲突） | [x] | `reachingTargetWaitsThenScansClockwise` |
| P25-PATROL-04 | 两次受阻放弃进扫描 | [x] | `twoConsecutiveBlocksAbandonTargetIntoScan` |
| P25-PATROL-05 | 扫描中玩家可见被 reflex 中断 | [x] | `visiblePlayerInterruptsScanningPatrol` |
| P25-PATROL-06 | 隐藏玩家不入状态/候选 | [x] | `hiddenPlayerNeverEntersPatrolStateOrCandidates` |
| P25-PATROL-07 | 保存/读档恢复 | [x] | `saveRestoreKeepsPatrolStateFields` + 存档 round-trip |
| 补充巡视回归 | 扫描朝墙且无候选时继续逐向转身，不永久 Wait | [x] | `emptyCandidateViewTurnsUntilPatrolCanResume` |
| P25-PROTOCOL-01 | valid v2 fixture 双端一致 | [x] | `AgentContractFixtureTest` + `test_contract_fixtures.py` |
| P25-PROTOCOL-02 | old/unknown version 双端拒绝 | [x] | 6 个 invalid fixture 稳定 code |
| P25-PROTOCOL-03 | invalid facing/mode/worldId 拒绝 | [x] | 同上 |
| P25-TRACE-01 | v2 trace 含新字段、canonical 稳定 | [x] | `AgentTraceContractTest` + `sameScriptRepeatsSameTrace` |
| P25-TRACE-02 | 领域 schema 命名迁移 | [x] | 命名扫描 + `legacy-decision/private-perception/agent-runtime` 版本 |
| P25-REGRESSION-01 | Phase 2 leaf tests 迁移后全过 | [x] | `CoreGameplayRegressionSuite` 174 tests |
| P25-REGRESSION-02 | 真实 Python 模式有界通过 | [x] | `AgentRuntimeIntegrationTest` 7 tests |
| P25-REGRESSION-03 | 源码命名扫描 | [x] | byog/config/agent/python 清零 |

## 5. 跨语言共享 fixtures

`agent/contract/fixtures/`（8 个，Java runner `AgentContractFixtureTest` 与
Python runner `test_contract_fixtures.py` 同次验证）：

| fixture | 期望 |
|---------|------|
| `valid-observation-directional.json` | 双端接受，typed data 一致（worldId/visionMode/self.maxHp/facing） |
| `valid-observation-omnidirectional.json` | 双端接受 |
| `invalid-old-envelope-version.json` | `SCHEMA_MISMATCH` |
| `invalid-old-observation-version.json` | `UNKNOWN_PAYLOAD_VERSION` |
| `invalid-missing-world-id.json` | `MISSING_REQUIRED` |
| `invalid-facing.json` | `UNKNOWN_FIELD` |
| `invalid-vision-mode.json` | `UNKNOWN_FIELD` |
| `invalid-apple-tile-type.json` | `UNKNOWN_FIELD`（`APPLE` 不是合法 tile type） |

## 6. 确定性证据

- 苹果：`HealthPackTest.sameSeedProducesIdenticalPlacement` 两次运行位置完全一致；
  独立随机流（`placementDoesNotConsumeForeignRandomStreams`）不扰动地图/楼梯/敌人/战斗序列。
- 朝向：`spawnedFacingIsDeterministicAndIsolated` 两次 `spawnEnemies` 同 seed/floor 朝向与位置一致。
- 巡视：`sameScriptRepeatsSameTrace` 两次 8-tick 状态/动作/目标/ordinal 序列字节一致。
- trace：`AgentTraceContractTest` 与 `sameScriptRepeatsSameTrace` 证明 canonical 事件流稳定；
  v2 事件携带 worldId、visionMode、selfFacing/selfHp/selfMaxHp、patrol 字段与 before/after facing。
- 保存时间：`savedAtComesFromInjectedClock` 由注入 clock 决定，墙钟不进入 canonical 决定。

## 7. 偏差与已知限制

1. **测试类领域重命名通过脚本完成**（`tools/rename_test_classes.py`），旧类名无 alias；
   迁移同步了 imports、Suite、命令与 trace 断言。
2. **`SaveLoadManager` 与 `EntityState` 保留为历史薄适配**：不再被 Game 引用
   （命名世界 repository 取代单文件存档），Spec 允许"删除或薄适配"；删除操作受
   权限策略限制，故保留并在 Phase 3 交接中标注为无运行时引用的遗留类型。
3. **`playWithInputString` 不维护**：仍可编译且行为随新菜单变化，但不作为新运行时或存档入口
   （Spec Out of Scope 一致）。
4. **巡视 trace 事件由 Enemy 执行层记录**（`PATROL_STATE_CHANGED` / `PATROL_TARGET_SELECTED`），
   PatrolController 本身不依赖 trace，保持纯决策。
5. **巡视面墙冻结已修复**：2026-08-13 玩家现场日志显示 `Enemy#5` 在巡视期间长时间无规划输出，
   贴近后由 `P1_ADJACENT_THREAT` 恢复追击。根因是 `SCANNING` 朝墙时无 3–8 格候选，原实现返回
   `WaitAction` 且不改变扫描状态。现在返回一次顺时针 `TurnAction`，下一 action opportunity 按新朝向重新选目标。
   生产 Game 仍使用 `AgentTrace.NO_OP`，因此普通控制台日志不含 `NO_PATROL_CANDIDATES` 和 ActionResult；本次以定向回归测试固定该路径。
6. **扫描次数文档冲突待裁决**：批准的 Spec/Build Guide 要求到达后顺时针 Turn 三次；当前生产常量、注释和
   `reachingTargetWaitsThenScansClockwise` 实际固定为两次。本次不改变已有玩法节奏，只记录差异；后续需决定是恢复三次，
   还是修订批准文档为两次。
7. **连续移动新手感待用户复验**：按键状态、60ms cadence（约 16.7 格/秒）、松键无积压和单次画面提交已有自动化/源码证据，
   但本轮没有替用户操作 GUI，最终主观手感仍需人工试玩确认。
8. **最终 commit 未创建**：本次修正的测试结果对应 `142452b` + 当前工作树；提交后需回填。

## 8. Definition of Done 对照

- [x] `PlayerRunState.currentHp` 跨层保留，charge 读档恢复、换层清零。
- [x] healthpack 非兼容验收条件通过，apple 不进入 Agent knowledge（wire 无 APPLE）。
- [x] 不限数量命名世界可创建/列出/加载/安全覆盖，摘要字段正确。
- [x] 保存使用受限路径 + temp-replace；坏档与旧档不破坏其他世界。
- [x] Enemy maxHp、Facing、Turn/Wait 与成功/失败动作朝向规则实现并保存。
- [x] directional 半菱形、LOS、边缘、墙角、全向 baseline 测试通过。
- [x] 可保存巡视状态机、两次受阻恢复、reflex interrupt 测试通过。
- [x] 巡视面墙无候选时会逐向转身并重新选目标，不会永久 Wait。
- [x] 底部悬停条、朝向标记、FOV 显示与 apple 图片/fallback 已由用户确认可读。
- [x] 玩家移动按下立即、长按 60ms cadence（约 16.7 格/秒）、松开无积压与最近方向优先的自动化测试通过；人工手感待复验。
- [x] `agent-session.v1`、`private-observation.v2`、`agent-runtime.trace.v2` 双语言 gate 通过。
- [x] 触及源码/测试/配置/runtime 字符串使用领域命名，无开发阶段编号。
- [x] deterministic Suite 只列 leaf tests，无嵌套或重复执行。
- [x] 默认 deterministic gate 无 GUI、真实网络、默认存档和 sleep。
- [x] Phase 2 Session/deadline/cancel/fallback/feedback/真实进程故障回归通过。
- [x] Roadmap、healthpack、Phase 3 两份草案已同步（见 §9）。
- [ ] 验证命令、测试数、人工证据、偏差与最终 commit 写入本 Completion —— 连续移动人工复验与
      commit 回填待用户确认后补记。
- [ ] Phase 3 只在以上条件全部满足后开始 —— 等待连续移动人工复验与 commit 授权。

## 9. 文档同步

- `DEVELOPMENT_ROADMAP.md`：Phase 2.5 阶段与交付链在审计阶段已由 Spec 插入；
  本 Completion 关闭后需把"当前规划入口"从 Phase 2.5 推进到 Phase 3。
- `documents/health-pack-requirements.md`：旧存档不迁移、苹果不暴露给 Agent 已同步。
- `PHASE_3_SPEC.md` / `PHASE_3_BUILD_GUIDE.md`：前置契约已按裁决描述
  `agent-session.v1`/`private-observation.v2` 硬切换与 checkpoint 身份。

## 10. Phase 3 交接

### 可以依赖

- 稳定 `worldId`、新 `runId` 语义与命名世界存档（类型化 GameSaveData + repository）。
- `agent-session.v1` + `private-observation.v2` 的 Java/Python strict codec 与共享 fixtures。
- Observation self 的 current/max HP、Facing、VisionMode。
- directional/omnidirectional 可比较基线，背后玩家不泄漏的固定场景。
- 可保存的 Enemy facing、maxHp、PatrolState 与确定性巡视。
- `TurnAction`/`WaitAction`、移动/攻击朝向、单 Action cadence。
- `agent-runtime.trace.v2` 的身份/感知/朝向/巡视/动作关联字段。
- `PlayerRunState`、healthpack 与命名存档完成；apple 对 Agent 等价 floor。

### 不得假设

- 敌人已有完整地图意识、地图记忆、跨楼层记忆或共享上下文。
- checkpoint 是 Java save 的一部分，或缺失 checkpoint 会阻止读档。
- 本地巡视状态机等于未来模型的完整战术计划系统。
- apple 是 Agent 工具、skill 参数或可感知目标。
- 旧 envelope/Observation/intent/trace 仍被接受。
- 存档版本迁移、删除、重命名或 autosave 已实现。

### 首要入口

以本 Completion 为唯一前置：将 deterministic runtime 与 Java skill seam 一次性升级到
`strategic-intent.v2`，建立按 `worldId/floorId/agentId` 隔离的有状态 graph/checkpoint，
并继续复用 Java 的私有 Observation、reflex、巡视 primitive、validator、单 Action cadence
与非阻塞 Session。
