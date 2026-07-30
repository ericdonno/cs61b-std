# DungeonMind Agent 测试入口

本文件只描述当前 Agent/CI 契约。Phase 0/1 的旧说明、嵌套 Suite 和完整 gameplay golden 流程已经退役；历史 JSON 仍保存在 `documents/baselines/`，仅用于审计，不参与 gate。

## 唯一 deterministic gate

在仓库根目录运行：

```powershell
$javaSources = Get-ChildItem byog -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
javac -encoding UTF-8 -cp "..\library-sp18\javalib\*" -d out $javaSources

java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.Phase2TestSuite
```

2026-07-31 的已验证基线为 `OK (89 tests)`。测试数量不是兼容 API；关键约束是所有 leaf test class 恰好执行一次。
统一 Suite 会临时关闭生产 DEBUG/INFO 日志，使成功输出保持在 JUnit 摘要级别；测试结束后恢复原日志级别。

`Phase2TestSuite` 当前直接列出：

- `EnemyCollisionTest`
- `Phase0EncounterTest`
- `PerceptionSystemTest`
- `Phase1EncounterTest`
- `Phase2ProtocolTest`
- `Phase2AiTickTest`
- `Phase2ArbiterTest`
- `AgentSessionTest`

不要恢复 `Phase0TestSuite` / `Phase1TestSuite` 的嵌套组合。跨 Python 进程的 `Phase2IntegrationTest` 使用独立命令，不混入快速 deterministic gate。

## 架构边界

| 组件 | 责任 |
|------|------|
| `byog/AI/AiTickLoop.java` | 生产 Game 与测试共用的 poll → execute → commit → collect 调度 |
| `byog/Test/EncounterHarness.java` | two-guard 场景唯一 ASCII parser、fixture 和 scheduler |
| `Phase0EncounterHarness` / `Phase1EncounterHarness` | 旧测试名称的薄兼容 adapter；不得新增逻辑 |
| leaf test class | 断言一个层级的契约；可单独运行以定位失败 |
| `Phase2TestSuite` | 只组合 leaf classes，不包含其他 Suite |

测试不得复制 Game tick loop，也不得反射 private Game 方法。生产调度需要测试时，先提取一个窄的 production seam，再由 Game 和 harness 共用。

## 断言策略

优先断言可解释的不变量：

- 碰撞、HP、死亡与地形不被实体覆盖；
- 私有 observation 不泄漏墙后 tile 或 entity；
- 每个 cooldown 最多执行一个 Action；
- world commit 后才生成 outcome 和下一份 observation；
- 相同输入产生相同 canonical trace/state；
- Phase 0/1 trace schema 不出现后续阶段字段；
- malformed protocol 在明确边界失败。

完整逐 tick gameplay 轨迹不做 byte-for-byte golden。byte golden 只允许用于稳定的外部 schema、wire codec 或明确要求字节兼容的 artifact。

`documents/baselines/phase0_rule_baseline_v1.json` 与 `phase1_rule_baseline_v1.json` 是历史 evidence：

- 普通测试不读取、不生成、不覆盖；
- 不因为寻路、队列或 scheduler 的合理重构而更新；
- 如需审计历史行为，人工读取文件和对应旧 Phase Spec。

## Agent 失败定位

先运行统一 gate；失败后只运行对应 leaf class：

```powershell
java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.Phase2AiTickTest
```

替换末尾类名即可定位其他层级。不要通过反复运行整个嵌套 Suite 判断偶发失败；deterministic test 不使用 `Thread.sleep()`、真实时钟、随机端口或默认存档。

## 新增测试规则

1. Test ID 保持可搜索，并在当前 Phase Spec 中追踪。
2. 一个测试只验证一个主要失败原因，错误消息指出实体、tick、期望与实际值。
3. fixture/parser/clock/ID generator 优先共享，禁止复制粘贴第二套 harness。
4. deterministic Suite 只列 leaf classes；integration 入口独立。
5. 测试代码可以为 Agent 优化，但生产代码中不得出现仅为旧测试服务的无界队列、特殊分支或后门。
6. 测试结果不自动改写任何 baseline 或完成报告。
