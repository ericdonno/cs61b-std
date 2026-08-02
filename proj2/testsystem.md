# DungeonMind Agent 测试入口

本文件只描述当前 Agent/CI 契约。Phase 0/1 的旧说明、嵌套 Suite 和完整 gameplay golden 流程已经退役；历史 JSON 仍保存在 `documents/baselines/`，仅用于审计，不参与 gate。

本文使用的几个测试术语：

| 术语 | 本项目中的含义 |
|------|----------------|
| deterministic（确定性） | 相同输入和受控时间必然得到相同结果，不依赖随机端口、真实等待或线程碰运气 |
| gate（准入检查） | 合并代码前必须通过的一组检查 |
| leaf test class（叶子测试类） | 直接包含测试方法、不会再套入另一个测试 Suite 的类 |
| fixture（固定样例） | 为测试预先准备且可重复使用的输入数据或场景 |
| fake（可控替身） | 实现同一接口、但由测试直接控制时间或 IO 行为的简单对象 |
| smoke test（冒烟检查） | 只确认最短关键路径能运行的快速检查，不等同于完整集成验证 |
| integration test（集成测试） | 启动多个真实组件，检查它们跨进程或跨模块协作的测试 |

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

2026-08-02 的已验证基线为 `OK (100 tests)`。测试数量不是兼容 API；关键约束是所有 leaf test class 恰好执行一次。
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
- `AgentTraceContractTest`
- `GameConfigTest`

不要恢复 `Phase0TestSuite` / `Phase1TestSuite` 的嵌套组合。跨 Python 进程的
`AgentRuntimeIntegrationTest` 使用独立命令，不混入快速 deterministic gate：

```powershell
java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.AgentRuntimeIntegrationTest
```

当前结果为 `OK (7 tests)`，覆盖完整反馈闭环、双连接隔离、delay、malformed、
no-read、disconnect/restart 和已连接 Session 的 terminal close。

## 测试层次与独立入口

| 层次 | 入口 | 当前基线 | 负责证明 |
|------|------|----------|----------|
| Java 确定性准入检查 | `byog.Test.Phase2TestSuite` | `OK (100 tests)` | gameplay、感知、协议、AI Tick、仲裁、Session、AgentTrace 与启动配置契约 |
| Socket 传输层 | `byog.Bridge.SocketTransportTest` | `OK (9 tests)` | 真实 localhost 往返，以及连接、读取、写出、退避和关闭边界 |
| Python 运行时 | `python -m unittest ...` | `23 tests` | Python 协议、大脑和服务器模式 |
| Python 冒烟检查 | `agent/python/smoke_test.py` | 单次最短往返 | Python 服务能接收 observation 并返回合法 intent |
| Java/Python 集成 | `byog.Test.AgentRuntimeIntegrationTest` | `OK (7 tests)` | Game/Enemy、Session、Socket 和 Python 子进程共同工作 |

Socket 传输层单独运行：

```powershell
java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Bridge.SocketTransportTest
```

Python 全部测试：

```powershell
python -m unittest discover -s agent/python -p "test_*.py" -v
```

Python 冒烟检查可自行启动临时服务器：

```powershell
python agent/python/smoke_test.py --spawn-server
```

也可以先启动运行时，再从另一个终端连接：

```powershell
python agent/python/run.py --host 127.0.0.1 --port 9876 --mode normal
python agent/python/smoke_test.py --host 127.0.0.1 --port 9876
```

冒烟检查只经过 Python 协议、服务器和确定性大脑，不经过 Java 游戏。Java/Python
集成测试才覆盖以下完整路径：

```text
世界提交后的私有 observation
  → AgentSession
  → SocketTransport
  → Python runtime
  → submit_intent
  → Java 校验、仲裁和动作执行
  → 提交后的 ActionOutcome 返回 Python
```

集成入口另外检查两个 Enemy 的连接与身份隔离、延迟和不读数据时游戏逻辑不阻塞、
断线后的本地接管、重启后的恢复，以及 Enemy 死亡后的永久关闭。

## 可控测试接口

Session 状态机使用以下替身，把时间和消息顺序变成可直接控制的数据：

- `FakeClock`：推进单调时间，不等待现实时间。
- `InMemoryTransport`：取得出站消息并注入入站消息，不打开端口。
- `RecordingHandler`：记录 intent、取消确认、协议拒绝和处理结果。
- deterministic `IdGenerator`：生成固定的 decision/message ID。
- 真实 `PerceptionSystem`：仍负责生成私有 `ObservationEnvelope`，避免复制观察规则。

`SocketTransport` 需要同时验证真实 Socket 和难以靠操作系统时序稳定制造的边界，因此
保留以下同包可替换接口：

```text
ConnectionFactory
  → 创建 TransportConnection

TransportConnection
  → connect / setReadTimeout / input / output / close

BackoffWaiter
  → await(delayMs) / wake()
```

生产实现是 `JavaSocketConnection` 和 `MonitorBackoffWaiter`。测试替身可以预定连接失败、
记录调用线程、阻塞或分段返回输入、记录退避时长，并在指定字节处触发上限。

Python 的 `create_server(host, 0, ...)` 允许操作系统分配空闲端口。持有服务器或子进程的
测试必须在 `finally` 中关闭自己创建的资源，并在有界时间内等待线程或进程退出。

## 各层覆盖重点

`AgentSessionTest` 重点检查：单请求、observation 合并、soft/hard deadline、取消确认、
cancel grace、完整身份匹配、有界邮箱、关键消息拒绝、事件合并、重连和永久关闭。

`SocketTransportTest` 的 9 项检查分别覆盖：

- localhost 双向 NDJSON；
- connect/read/write 仅由一个 IO worker 执行；
- `250 → 500 ms` 指数退避、成功后重置和重连 epoch；
- 关闭能唤醒阻塞读取并在上限内结束 worker；
- 输入帧在第一个超限字节立即失败；
- 读取超时不会丢失已经收到的半帧；
- 非法 UTF-8 被严格拒绝；
- 过大的出站帧在任何写出前失败；
- 未知消息类型形成明确拒绝，但兼容情形不强制断线。

Python 的 23 项测试分为：协议编解码 10 项、确定性大脑 7 项、运行模式 6 项。
它们检查严格字段、类型和大小边界，固定决策规则，每连接状态隔离，以及 ready、delay、
malformed、disconnect 和 no-read 等服务器行为。

## 架构边界

| 组件 | 责任 |
|------|------|
| `byog/AI/AiTickLoop.java` | 生产 Game 与测试共用的 poll → execute → commit → collect 调度 |
| `byog/Test/EncounterHarness.java` | two-guard 场景唯一 ASCII parser、fixture 和 scheduler |
| `Phase0EncounterHarness` / `Phase1EncounterHarness` | 旧测试名称的薄兼容 adapter；不得新增逻辑 |
| leaf test class | 断言一个层级的契约；可单独运行以定位失败 |
| `Phase2TestSuite` | 只组合 leaf classes，不包含其他 Suite |
| `AgentRuntimeIntegrationTest` | 启动真实 Python 子进程，验证跨语言闭环与故障恢复；独立运行 |
| `AgentTraceContractTest` | 验证 canonical 关联链、确定性、deadline、迟到响应和旧 schema 隔离 |

测试不得复制 Game tick loop，也不得反射 private Game 方法。如果测试需要控制正式调度，
应提取一个职责单一、可直接调用的公共接口，再由 Game 和测试 harness 共用。

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
