# Phase 2 Completion：确定性事件桥接与双速执行骨架

## 1. 元数据

- **完成日期**：2026-08-02
- **分支**：`ai-enemis`
- **基线 commit**：`4c1bc98b861d215ab4eb9494a90622adbdac8f57`
- **验收时 HEAD**：`bbd5dae99a9827a87017b751b196c42c3ece436d`
- **最终 commit**：未创建；验收对应当前未提交工作树，提交后需回填 commit
- **规范**：`PHASE_2_SPEC.md`
- **构建指南**：`PHASE_2_BUILD_GUIDE.md`
- **运行环境**：Windows / PowerShell，Java 19.0.2，Python 3.14.6

验收开始前工作树已经包含 Phase 2 前序实现和文档改动。本次没有重置、覆盖或自动提交这些改动。
因此本文把 HEAD 与工作树状态分开记录，不把未创建的提交写成完成证据。

## 2. 验收摘要

| 入口 | 测试数 | 结果 | 耗时 |
|------|--------|------|------|
| Java 编译 | 全部 `byog/**/*.java` | PASS | 25.707s |
| `Phase2TestSuite` | 100 | PASS | 1.467s |
| `SocketTransportTest` | 9 | PASS | 0.393s |
| `AgentRuntimeIntegrationTest` | 7 | PASS | 5.476s |
| Python unittest discovery | 23 | PASS | 0.855s |

验收结束后的资源检查：测试创建的 Python 子进程均由持有其句柄的 harness 有界关闭，
TCP 9876 监听数为 0。没有把 integration 测试放入快速 deterministic Suite。

## 3. 可复查命令

```powershell
$javaSources = Get-ChildItem byog -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
javac -encoding UTF-8 -cp "..\library-sp18\javalib\*" -d out $javaSources

java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.Phase2TestSuite

java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Bridge.SocketTransportTest

java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.AgentRuntimeIntegrationTest

$env:PYTHONPATH = "agent/python"
python -m unittest discover -s agent/python/tests -v
```

手工启动任一 fake runtime 的通用命令：

```powershell
python agent/python/run.py --host 127.0.0.1 --port 9876 --mode normal
python agent/python/run.py --host 127.0.0.1 --port 9876 --mode delay --delay-seconds 2
python agent/python/run.py --host 127.0.0.1 --port 9876 --mode malformed
python agent/python/run.py --host 127.0.0.1 --port 9876 --mode disconnect
python agent/python/run.py --host 127.0.0.1 --port 9876 --mode no-read
```

真实进程故障演练由 `AgentRuntimeIntegrationTest` 使用端口 0 启动，读取明确的 ready envelope，
每一步都有 4 秒以内的有界等待，并且只清理由该测试创建的子进程。

## 4. 配置验收

`config/game.properties` 现在包含完整安全默认值；`GameConfig` 在难度选择或读档时只解析一次，
并生成不可变 `AgentSessionConfig`。PLAYING tick 不读取 properties。

| 配置组 | 验收行为 |
|--------|----------|
| bridge flag / host / port | 默认关闭、host 非空、端口限制为 1–65535 |
| soft / hard deadline | 均为正且 hard 大于 soft；关系错误时两者一起回退 |
| cancel / reconnect / shutdown | 正数校验；reconnect max 不小于 initial |
| outbound / inbound / pending | 容量至少为 4 |
| frame | 1024–1,048,576 UTF-8 bytes |
| heartbeat / poll bound | 正数校验 |
| ActionQueue water marks | `highWater > lowWater >= 0`，并传入新建及读档 Enemy |

`GameConfigTest` 验证缺失值默认、合法值传递、非法值/关系回退和解析快照不随原
`Properties` 后续修改而改变。已有 `disabledSessionDoesNotStartTransport` 验证 bridge 关闭时
transport 不启动；正式 Game 仍使用 `AiTickLoop`、私有观察和本地 `RuleBasedBrain`。

## 5. 五种真实故障演练

| 模式 | Java 验收入口 | 结果与关键证据 |
|------|---------------|----------------|
| normal | `javaPythonRoundTripKeepsDecisionAndFeedbackCorrelation` | observation、intent、action、feedback 的 decisionId 相同；Python 日志记录 feedback；trace 含 request/adopted/feedback |
| delay | `slowRuntimeDoesNotStopLogicalTicksOrLocalPlan` | 超过 soft deadline 后 8 个 tick 在 500ms 内完成，本地计划继续；trace 含 `AGENT_SLOW` |
| malformed | `malformedRuntimeIsRejectedWithoutRemoteSideEffects` | 坏 JSON 触发 fatal protocol rejection；采纳前 lease、queue、位置不变；随后本地接管；trace 含 `PROTOCOL_ERROR` |
| disconnect/restart | `runtimeRestartResumesOnlyWhenPollAdoptsFreshIntent` | 断开后本地接管；重启后 sessionEpoch 增加，新 intent 只在 poll 生效；trace 含 `REMOTE_AGENT_RESUMED` |
| no-read | `noReadBackpressureDoesNotBlockGameThread` | 有界 outbound 明确返回关键拒绝，20 个 tick 在 1 秒内继续；trace 含 `OUTBOUND_MESSAGE_DROPPED` |

Python 的 `RuntimeModeTest` 另外直接验证 delay 的实际延迟、malformed 的 JSON 失败类型、
disconnect 的 EOF、no-read 的读取超时，以及两连接的序号状态隔离。

## 6. Canonical trace 证据

下面是 `AgentTraceContractTest` 固定 `guard-a` 场景中可按 ID 关联的核心事件；完整 JSON
由 `EncounterHarness.canonicalTrace()` 以稳定字段顺序生成，两次运行字节一致。

```text
tick=0 OBSERVATION_GENERATED  agentId=guard-a observationSeq=0
tick=0 AGENT_REQUEST_SENT     decisionId=guard-a-decision-0 observationSeq=0 generation=0
tick=1 INTENT_ADOPTED         decisionId=guard-a-decision-0 observationSeq=0 source=REMOTE_AGENT
tick=1 ACTION_ATTEMPTED       decisionId=guard-a-decision-0 actionIndex=1 actionType=MoveAction
tick=1 ACTION_RESULT          decisionId=guard-a-decision-0 actionIndex=1 before=(1,1) after=(2,1)
tick=1 ACTION_FEEDBACK_ENQUEUED decisionId=guard-a-decision-0 actionIndex=1 source=REMOTE_AGENT
```

额外边界证据：

- 同一 response 再次注入后产生 `STALE_RESPONSE_DROPPED`，`validationResult=STALE_IDENTITY`，
  当前 lease 的 decisionId 不变。
- 玩家进入和离开私有 FOV 后分别产生 `REFLEX_OVERRIDE_STARTED` 与
  `REFLEX_OVERRIDE_ENDED`，原 lease 只在 TTL 与前提仍成立时恢复。
- canonical schema 不包含 wall-clock、线程名、socket 地址或耗时；这些只属于 diagnostics。
- Phase 0/1 schema 不出现 runId、sessionEpoch 等新字段，历史 gameplay baseline 不参与 gate。

## 7. 实际交付物

- 严格版本化 envelope、Java/Python codec 和跨语言 fixtures。
- Python 标准库 fake runtime，支持 normal/delay/malformed/disconnect/no-read。
- 每 Enemy 独立 `AgentSession`、IO worker、有界队列、单 in-flight、deadline、cancel、重连和 terminal close。
- `DecisionValidator`、`IntentLease`、`IntentArbiter`、`ReflexController` 和本地 fallback。
- 生产 `AiTickLoop` 的 poll → execute → commit → collect 顺序和单 Action cadence。
- commit 后 `ActionOutcome` / feedback 关联。
- `agent.trace.v1` canonical trace、共享 `EncounterHarness`、fake clock/ID/transport seam。
- 文件配置到 `AgentSessionConfig` 和 ActionQueue water marks 的一次性校验链路。
- leaf-only deterministic Suite 与独立真实进程 integration 入口。

## 8. 与 Spec 的偏差

1. **Trace schema 采用领域名**：代码使用 `AgentTrace.AGENT_SCHEMA_VERSION = "agent.trace.v1"`，
   而不是把开发阶段编号写进标识符。字段和事件语义与 Spec 一致。
2. **故障演练采用可重复 headless 真实进程测试**：没有依赖 GUI 肉眼观察；Java 仍启动真实 Python
   子进程并使用真实 TCP/Socket。这样可以自动断言 tick、状态、trace 和资源清理。
3. **最终 commit 尚未创建**：当前工作树在任务开始前已包含多文件未提交改动，且用户没有授权提交。
   所有测试结果对应本文件元数据中的 HEAD 加当前工作树；正式提交后必须回填 commit。

除此之外，没有引入真实 LLM、第三方 Java/Python 依赖、共享 Agent context、复杂技能或多步计划。

## 9. 已知限制与风险

- 一 Enemy 一 TCP/IO thread 只针对当前少量敌人，尚未做大规模遭遇压力测试。
- fake runtime 不包含 LLM、LangGraph、Tool Calling、checkpoint 或长期记忆。
- Action feedback 已传输并关联，但 Python 尚未据此进行持续多轮重规划。
- `PATROL / CHASE / ATTACK / GUARD` 是当前受限 wire whitelist，不代表最终技能集合。
- Session、lease、queue 和 runtime state 不存档；读档创建新 runId 和新 Session。
- no-read 验收首先命中应用层有界队列；不同操作系统的 TCP buffer 大小不是 canonical 证据。

## 10. Definition of Done 对照

| 条件 | 状态 |
|------|------|
| P2-P01–P2-P06、P2-S01–P2-S15、P2-A01–P2-A12 | [x] |
| P2-I01–P2-I05、P2-R01–P2-R02 | [x] |
| 单一 leaf-only deterministic Suite | [x] |
| 五种 fake runtime 模式及真实进程故障证据 | [x] |
| 游戏线程不直接执行 socket IO 或无限等待 | [x] |
| 单 in-flight、完整身份校验、迟到结果丢弃 | [x] |
| 有界队列、soft/hard deadline、cancel/rebuild | [x] |
| commit 后 observation/outcome/feedback | [x] |
| canonical trace 关联完整且旧 schema 隔离 | [x] |
| 默认 bridge=false 且本地游戏继续 | [x] |
| 配置默认值、范围校验和安全回退 | [x] |
| 完成报告与下一阶段交接 | [x] |
| 验收对应最终 commit | [ ] 提交后回填 |

## 11. Phase 3 交接

### 11.1 可以依赖

- `phase2.session.v1` envelope、严格 NDJSON codec 和 64KiB frame guard。
- 每 Enemy 独立 Session、身份、队列、deadline、重连和有界 close。
- 单 in-flight、latest observation 合并、cancel acknowledgement/rebuild。
- `IntentLease`、validator、arbiter、reflex 与本地 fallback。
- 生产分阶段 tick、单 Action cadence 和 world commit barrier。
- `ActionOutcome` / `action_feedback` 的稳定关联字段。
- deterministic fake runtime、共享 harness、fake clock、确定性 ID 和 canonical trace。
- 文件配置到不可变 Session 配置的安全启动边界。

### 11.2 不得假设

- Python 已经有 LLM、LangGraph、Tool Calling、记忆或 checkpoint。
- 当前四个 skill 是最终战术能力或可绕过 Java planner/validator。
- feedback 已经驱动持续重规划或多步骤计划。
- 多个 Enemy 可以共享 context、memory、预算或连接状态。
- 一 Enemy 一 IO thread 已通过大规模遭遇验证。
- Session、lease 或 runtime state 可以从存档恢复。

### 11.3 首要入口

在不改写 envelope、Session、tick loop 和 validator 的前提下，引入单 Enemy 有状态 Agent runtime、
受限 Tool Calling、可扩展 skill registry，以及整个遭遇级的模型并发和预算控制。
