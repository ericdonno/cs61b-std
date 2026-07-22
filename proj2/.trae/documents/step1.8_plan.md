# Step 1.8 实现计划：Generate Phase 1 golden baseline + P1-T08

## 目标

1. 创建 `Phase1BaselineMain` 生成 golden baseline JSON
2. 运行它生成 `phase1_rule_baseline_v1.json`
3. 添加 P1-T08 golden comparison 测试
4. 手工审核：确认 A 能看到玩家、B 不能

## 当前状态

* `Phase0BaselineMain` — 已有模式，支持 `--write <path>` 写入 golden

* `Phase1EncounterHarness.buildBaselineJson()` — 已就绪，使用 `"phase1.baseline.v1"`

* `Phase1EncounterTest` — 已有 P1-T01 至 P1-T07，P1-T08 待添加

* `documents/baselines/` — 仅有 `phase0_rule_baseline_v1.json`

## 实施步骤

### 步骤 1：创建 Phase1BaselineMain

**文件**：`byog/Test/Phase1BaselineMain.java`（新建）

镜像 `Phase0BaselineMain`：

* `main(args)` 打印 usage，查找 `--write <path>`

* 创建 `Phase1EncounterHarness.baselineTwoGuardsV1()`

* `harness.runTicks(12)`

* 输出到指定的 path（自动创建父目录）

* 打印确认信息

### 步骤 2：生成 golden baseline 文件

运行：

```
java -cp "out;..\library-sp18\javalib\*" byog.Test.Phase1BaselineMain --write documents/baselines/phase1_rule_baseline_v1.json
```

### 步骤 3：手工审核 golden JSON

确认两件事：

1. Guard A（`guard-a`）的 `OBSERVATION_GENERATED` 事件中 `visiblePlayer` 为 `true`
2. Guard B（`guard-b`）的 `OBSERVATION_GENERATED` 事件中 `visiblePlayer` 为 `false`

### 步骤 4：添加 P1-T08 到 Phase1EncounterTest

按 `Phase0EncounterTest.T09` 相同模式：

* `phase1RuleBaselineMatchesGolden()` — 读 golden → 跑 12 ticks → `assertEquals`

* 需要 `IOException` throws

## 涉及文件

| 文件                                                 | 新建/修改  | 说明              |
| -------------------------------------------------- | ------ | --------------- |
| `byog/Test/Phase1BaselineMain.java`                | **新建** | Golden 生成入口     |
| `documents/baselines/phase1_rule_baseline_v1.json` | **新建** | Golden baseline |
| `byog/Test/Phase1EncounterTest.java`               | **修改** | 添加 P1-T08       |

## 验证步骤

1. 编译通过
2. `Phase1BaselineMain` 生成 golden 文件成功
3. 手工确认 golden JSON 中 A(`guard-a`) 的 `visiblePlayer` 字段
4. Phase1TestSuite 全部 27 测试绿色（26 + P1-T08）
5. Phase0TestSuite 19 个回归绿色

