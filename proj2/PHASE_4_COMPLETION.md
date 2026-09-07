# Phase 4 Completion：执行反馈与事件驱动重规划

## 1. 验收结论

Phase 4 的 provider-neutral 生产实现和自动化闸门已完成。Python runtime 可以建立并有界执行
多步骤计划；Java 仍是当前步骤、动作合法性、世界提交、进度判断和一次局部恢复的唯一权威。
步骤成功可不调用模型直接推进，失败或重要事件才进入重规划；heartbeat 不触发推理。

当前结论是“自动化实现完成”，不是“完整人工验收”。`PLAY-01` 需要 Builder 在 GUI 中观察固定
blocked 遭遇，真实 provider smoke 需要 Builder 先提供 adapter 和本地凭据；两项本轮均未执行。

## 2. 元数据

- 验证日期：2026-08-23
- 分支：`result`
- 基线 HEAD：`7e0665ac360466c114a6b6bc8fdaf812388241ea`
- Java：JDK / `javac` 19.0.2，UTF-8 编译
- Python：3.14.6，仓库本地 `.venv`
- 平台：Windows / PowerShell
- Spec：[`PHASE_4_SPEC.md`](PHASE_4_SPEC.md)
- Build Guide：[`PHASE_4_BUILD_GUIDE.md`](PHASE_4_BUILD_GUIDE.md)
- 前置完成事实：[`PHASE_3_COMPLETION.md`](documents/phases/phase-3/PHASE_3_COMPLETION.md)

验证开始前工作树已有用户改动和生成物；本次没有删除、清理或重写 `out/production/`、`.venv/`
或其他用户生成物。

## 3. 自动化验收结果

| 闸门 | 结果 | 数量 | runner / wall time |
|---|---|---:|---:|
| Python contract、graph、runtime 全测 | PASS | 39 | 2.039 s / 4.139 s |
| UTF-8 Java 全量编译 | PASS | — | 6.853 s wall |
| `AgentRuntimeTestSuite` | PASS | 66 | 0.342 s / 0.937 s |
| `EventDrivenAgentIntegrationTest` | PASS | 9 | 23.829 s / 24.394 s |
| `SocketTransportTest` | PASS | 9 | 0.269 s / 0.906 s |
| `CoreGameplayRegressionSuite` | PASS | 181 | 8.836 s / 9.425 s |

真实 TCP integration 启动仓库本地 Python runtime，覆盖正常往返、慢响应、断线、重连、
多 Agent 隔离、生命周期关闭和 scripted 多步骤执行；未访问外网或付费 provider。

## 4. 可复查命令

```powershell
# Python 全测必须从 runtime 目录运行；本地包未安装进 site-packages。
Push-Location agent/python
& .venv/Scripts/python.exe -m unittest discover -s tests -v
Pop-Location

$javaSources = Get-ChildItem byog -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
javac -encoding UTF-8 -cp "..\library-sp18\javalib\*" -d out $javaSources

java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.AgentRuntimeTestSuite

java "-Dfile.encoding=UTF-8" `
    "-Ddungeonmind.python=$((Resolve-Path agent/python/.venv/Scripts/python.exe).Path)" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.EventDrivenAgentIntegrationTest

java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Bridge.SocketTransportTest

java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.CoreGameplayRegressionSuite
```

## 5. 已交付行为

### 跨语言执行契约

- `private-observation.v3`、`action-feedback.v2`、`agent-event.v1` 在 Java、Python 和共享
  fixtures 同次硬切；旧版本、未知状态和缺失稳定 ID 被确定性拒绝。
- commit 后反馈携带 `feedbackId / decisionId / planId / stepId / planRevision`、原因、步骤状态和
  计划状态；事件携带稳定 `eventId` 并按 occurrence 去重。
- 新游戏先建立稳定 `worldId` 再创建 run/session identity；新 run、换层、死亡和关闭不会复用旧执行状态。

### Java 权威执行与恢复

- `ActionOutcome` 在 execute 时冻结当前 decision/plan/step/skill，commit 后补全权威位置、生命和结果。
- PATROL、CHASE、ATTACK、GUARD 通过 `TacticalSkill.evaluateProgress()` 判断继续、成功、失败或暂停。
- 第一次 blocked 且 policy 允许时只在 Java 清队列并本地重规划；重复 blocked 产生
  `REPLAN_REQUIRED`，不无限撞墙。
- Reflex transition 显式区分开始、升级、Lease 恢复和 Lease 失效；反馈和 trace 保留原关联。

### Python 事件式执行图

- 每连接独立、线程安全、有界的 execution inbox 以稳定 ID 去重，并用 snapshot/commit 两阶段消费；
  写出失败、取消和迟到结果不会提前吞掉输入。
- `submit_plan` 接受 1–6 步并先验证整份计划；future steps 不进入 Java wire，一次只投影当前步骤。
- `STEP_SUCCEEDED` 直接推进下一步；冷启动、步骤失败、计划结束/取消、玩家出现、声音、消息或前提变化
  才触发模型路径。
- checkpoint 窗口、feedback/event 窗口、已消费 ID、计划 revision 和本地恢复次数均有界。

### 可观测性

- Java canonical gameplay trace 升级为 `agent-runtime.trace.v4`，记录 feedback/event ID、plan/step、
  reason/status、reroute 与 replan trigger。
- Python model trace 升级为 `agent-model.trace.v2`，只保留白名单关联、tool、usage 和状态字段；
  API key、raw prompt、raw response 和自由 reasoning 不进入 trace 或日志。

## 6. 偏差与未验证项

- `PLAY-01` 未执行：自动 blocked/reroute/replan 场景已通过，但本轮没有打开 GUI 做玩家视角人工试玩。
- `PROVIDER-01` 未执行：仓库仍保持 provider-neutral，没有具体 SDK、模型 ID、endpoint 或 API key。
- `MESSAGE_RECEIVED` 已有稳定协议、inbox 和触发入口，但世界内消息 producer、传播距离和可阻断规则属于
  下一阶段；`SOUND_HEARD` 只消费已有听觉事件，不扩展声音系统。
- Python 测试不能直接从仓库根目录运行，除非先设置 `PYTHONPATH`；Build Guide 已改成从
  `agent/python` 进入后执行的可复制命令。

## 7. 下一阶段可依赖

下一阶段可以依赖稳定 event/feedback ID、每 Agent 独立 inbox、当前 plan/step reducer、反射暂停/恢复、
有界局部恢复和事件触发重规划。它不能假设消息或声音会自动传播，也不能让 Agent 共享 checkpoint、
计划或私有知识；世界内通信仍必须由 Java 产生、可被阻断并只交付给实际收到的 Agent。
