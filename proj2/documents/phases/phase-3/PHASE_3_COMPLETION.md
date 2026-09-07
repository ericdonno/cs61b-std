# Phase 3 Completion：有状态工具 Agent 与 Java 技能权威

## 1. 验收结论

Phase 3 的供应商无关范围已于 2026-08-13 在 `result` 分支完成：Python runtime 可以用
脚本化 adapter 驱动真实 LangGraph、只读工具、checkpoint、取消和全局调度；Java/Python 已硬切
`strategic-intent.v2`；Java 通过统一 `TacticalSkillRegistry` 校验并执行远程意图。

本轮按 Builder 指示不接入具体模型 API。仓库没有新增具体 provider SDK、模型 ID 或凭据；真实
provider smoke 明确延后到 Builder 配置自己的 API 和 adapter 后执行。该未验证项不影响本阶段的
确定性控制流、跨语言契约和 Java 权威边界验收。

## 2. 元数据

- 完成日期：2026-08-13
- 分支：`result`
- 实施基线：`e6befe638304`
- 最终提交：本文件所在提交
- Spec：[`PHASE_3_SPEC.md`](PHASE_3_SPEC.md)
- Build Guide：[`PHASE_3_BUILD_GUIDE.md`](PHASE_3_BUILD_GUIDE.md)
- 前置 Completion：[`PHASE_2DOT5_COMPLETION.md`](../phase-2.5/PHASE_2DOT5_COMPLETION.md)
- 环境：Windows / PowerShell，Java 19.0.2，Python 3.14.6，pip 26.1.2

## 3. 自动化验收结果

| 入口 | 测试数/范围 | 结果 | 测试框架耗时 |
|---|---:|---|---:|
| Python unittest discovery | 34 | PASS | 2.578s |
| UTF-8 Java 全量编译 | 全部 `byog/**/*.java` | PASS | 19.182s（命令墙钟） |
| `AgentRuntimeTestSuite` | 58 | PASS | 0.887s |
| `AgentRuntimeIntegrationTest` | 8 | PASS | 39.908s |
| `SocketTransportTest` | 9 | PASS | 0.523s |
| `CoreGameplayRegressionSuite` | 176 | PASS | 10.084s |

Python 环境已从 committed `pylock.toml` 安装，并验证 `langgraph`、`langchain`、Pydantic 和
SQLite checkpointer 可导入。已检查 lock 与已安装包，不含具体模型 SDK。

## 4. 可复查命令

```powershell
$env:PYTHONPATH = (Resolve-Path agent/python).Path
& agent/python/.venv/Scripts/python.exe -m unittest discover `
    -s agent/python/tests -v

$javaSources = Get-ChildItem byog -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
javac -encoding UTF-8 -cp "..\library-sp18\javalib\*" -d out $javaSources

java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.AgentRuntimeTestSuite

java "-Dfile.encoding=UTF-8" `
    "-Ddungeonmind.python=$((Resolve-Path agent/python/.venv/Scripts/python.exe).Path)" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.AgentRuntimeIntegrationTest

java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Bridge.SocketTransportTest

java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.CoreGameplayRegressionSuite
```

## 5. 已交付行为

### Python runtime

- `pyproject.toml` 与带 hashes 的 `pylock.toml` 固定 Python 3.14 运行环境。
- `RuntimeBrain`、`BrainFactory` 和 `ResponseEmitter` 分离连接读取、决策任务与有序写回。
- `GraphAgentBrain` 在 worker 中执行有界 LangGraph；reader 在推理期间仍能处理 cancel。
- graph 强制先使用私有观察工具，再允许提交 v2 intent；工具不能读取完整 Java world。
- checkpoint 按编码后的 `worldId / floorId / agentId` 隔离，支持内存与 SQLite saver。
- scheduler 限制全局并发、排队长度及每个 `runId / floorId` 遭遇的调用/token 预算。
- cancel、连接关闭和新决策会取消或抑制旧结果；queued cancel 不调用 adapter。
- `agent-model.trace.v1` 只允许稳定白名单字段，不记录 raw prompt、reasoning、响应或凭据。

### 跨语言协议

- intent 唯一版本为 `strategic-intent.v2`；v1 与未知版本明确拒绝。
- skill 是结构合法的 ASCII 大写领域 ID；codec 不冻结未来 registry 支持集。
- parameters 支持有深度、对象键数、数组长度和字符串长度上限的通用 JSON。
- `planMetadata(planId, stepId, revision)` 贯穿 Python、wire、Java Lease 和 runtime trace。
- Java/Python 共用合法 v2 intent 与旧版拒绝 fixtures。

### Java 权威执行

- immutable `TacticalSkillRegistry` 统一处理 PATROL、CHASE、ATTACK、GUARD。
- 每个 skill definition 同时负责参数、知识边界、当前前提、内部意图转换、有界规划和恢复判断。
- `DecisionValidator.validateDetailed()` 原子返回 validation、`StrategicIntent` 和 `InterruptPolicy`；
  rejection 不进入 Lease 或 planner。
- remote Lease 通过 registry 规划；Java 仍独占碰撞、伤害、动作 cadence 和 commit barrier。
- `agent-runtime.trace.v3` 增加 `skillId / planId / stepId / planRevision` 关联字段。

## 6. 未验证项与后续配置提醒

真实模型 API 尚未配置，因此没有执行真实 provider smoke，也没有声称任何供应商兼容。准备联调时：

1. 选择并实现 `dungeonmind_agent/model/` 下的具体 `ModelAdapter`；
2. 在 factory 的 `model` 组合路径注入 adapter，并在 ready 前校验 endpoint、模型 ID 和凭据；
3. 用本地环境变量或 secret store 配置 API，不能提交 `.env` 或 key；
4. 单独运行一次有界 provider smoke，补记 tool round、v2 intent 被 Java 接受和 Action 提交证据。

当前 `--brain model` 会在 bind/ready 前以“provider 未配置”明确失败，避免误启动半完成路径。

## 7. Definition of Done

| 条件 | 状态 |
|---|---|
| 依赖与 lock 可重建，默认无具体 provider SDK | [x] |
| v2 Java/Python codec、fixtures 与旧版拒绝 | [x] |
| Java immutable skill registry 与 detailed validation | [x] |
| Agent state/checkpoint 隔离与 SQLite 重开 | [x] |
| 有界 model-tool-model graph 与私有知识工具 | [x] |
| reader/task/emitter 取消链和迟到抑制 | [x] |
| 全局 scheduler 并发、队列和遭遇预算 | [x] |
| 脱敏 trace 与 plan/action 关联 | [x] |
| 真实 Python/TCP scripted integration | [x] |
| Socket 与核心游戏回归 | [x] |
| 真实 provider smoke | [ ] 等 Builder 配置自己的 API/adapter 后补验 |
