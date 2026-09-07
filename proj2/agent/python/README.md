# Python 智能体运行时

这个目录提供独立的 Python 智能体进程。它通过本机 TCP/NDJSON 接收 Java 生成的私有观察，
返回 `strategic-intent.v2`；移动、碰撞、攻击、伤害和最终提交始终由 Java 决定。

当前可直接运行三种大脑：

- `deterministic`：无第三方模型调用，用固定规则返回意图；
- `scripted`：用脚本化 `ModelAdapter` 驱动事件式 LangGraph、有限多步计划、只读工具、
  checkpoint、scheduler 和取消链。
- `model`：调用 OpenAI-compatible Chat Completions endpoint，执行真实的模型—工具—计划循环。

`model` 使用 Python 标准库，不需要供应商 SDK。Python 只从进程环境读取 API key；根目录启动器可从
Windows DPAPI 密文解密后临时传入。key 不会进入仓库、trace、ready 输出或模型请求正文。

完整进程边界见 [`agentarchitecture.md`](../../agentarchitecture.md)，wire 字段见
[`agent/contract/README.md`](../contract/README.md)。

## 环境

项目固定使用 Python `>=3.14,<3.15`，直接依赖及传递依赖记录在 `pylock.toml`。本地虚拟环境
`.venv/` 是生成物，不提交。

```powershell
python -m venv agent/python/.venv
& agent/python/.venv/Scripts/python.exe -m pip install -r agent/python/pylock.toml
$env:PYTHONPATH = (Resolve-Path agent/python).Path
```

`pylock.toml` 当前只锁定 LangGraph、LangChain、SQLite checkpointer 和 Pydantic 这一条
供应商无关的运行链，不包含具体模型 SDK。

## 启动

确定性运行时：

```powershell
$env:PYTHONPATH = (Resolve-Path agent/python).Path
& agent/python/.venv/Scripts/python.exe agent/python/run.py `
    --host 127.0.0.1 --port 9876 `
    --mode normal --brain deterministic
```

脚本化图运行时：

```powershell
$env:PYTHONPATH = (Resolve-Path agent/python).Path
& agent/python/.venv/Scripts/python.exe agent/python/run.py `
    --host 127.0.0.1 --port 9876 `
    --mode normal --brain scripted `
    --checkpoint-db save/agent-checkpoint.sqlite `
    --runtime-trace reports/agent-model.ndjson
```

启动成功后，stdout 只写一行 JSON ready 信号和协议定义的输出。诊断信息走 stderr。
默认游戏配置仍关闭 Bridge；看到 ready 不代表交互式游戏已经连接。

## 运行结构

```text
dungeonmind_agent/
  protocol.py        严格 envelope、observation v3、feedback v2 与 event v1 编解码
  server.py          多连接 reader、串行 response emitter 与生命周期
  config.py          有界运行配置；真实模型配置不完整时在 ready 前失败
  checkpoint.py      InMemorySaver / SqliteSaver 生命周期
  observability.py   白名单字段 model trace
  brain/             deterministic、graph brain 与 factory
  graph/             执行 inbox、Agent state、只读 tools 和有界 StateGraph
  model/             adapter contract、OpenAI-compatible HTTP adapter 与全局 scheduler
```

每条连接拥有独立 brain 和 response sequence。LangGraph checkpoint 用
`(worldId, floorId, agentId)` 编码后的无歧义 `thread_id` 隔离；scheduler 只共享并发和遭遇预算，
不能读取某个 Agent 的 graph state。

## 事件式执行循环

每条连接持有独立的有界 execution inbox。`feedbackId` 和 `eventId` 用于去重；只有
`submit_intent` 成功写出，或图明确完成一次无需响应的本地状态转换后，才提交本次消费。
取消、写出失败和迟到结果不会提前吞掉执行输入。

`submit_plan` 一次接受 1–6 个步骤，并先校验整份计划。运行时一次只把当前步骤投影成
`strategic-intent.v2`；`STEP_SUCCEEDED` 会在不调用模型的情况下推进下一步。冷启动、重要世界
事件、步骤失败、计划完成/取消或前提变化才触发模型。heartbeat 只维持连接，不触发推理。

运行时状态、反馈窗口、事件窗口、已消费 ID 和计划 revision 都有固定上限；新 `runId` 会清除
旧执行上下文，避免读档或新局继承上一次运行的计划。

## 故障演练模式

| `--mode` | 行为 |
|---|---|
| `normal` | 由所选 brain 正常返回 intent |
| `delay` | 延迟响应，验证 Java tick 和本地控制继续 |
| `malformed` | 返回非法 JSON，验证协议拒绝 |
| `disconnect` | 收到 observation 后断开 |
| `no-read` | 接受连接但不读取，验证有界背压 |

这些模式不会访问真实模型服务。

## 验证

```powershell
Push-Location agent/python
& .venv/Scripts/python.exe -m unittest discover -s tests -v
Pop-Location
```

测试覆盖跨语言 fixture、图工具链、checkpoint 重开、Agent 隔离、scheduler 并发上限、排队取消、
迟到结果抑制、执行输入去重、无模型推进和 trace 脱敏。测试默认不访问外网、不调用付费服务，
也不写玩家存档。

## 接入自己的 API

最短路径是在仓库根目录双击 `provider-smoke.bat`。首次运行会询问：

1. API base，当前默认 `https://api.xiaomimimo.com/v1`；
2. 模型 ID，当前默认 `mimo-v2.5`；
3. API key，输入时字符不会显示。

这会进行一次有界的真实付费调用，完整验证 HTTP、tool calling、`submit_plan` 和
`strategic-intent.v2`。成功时会输出一行 `{"result":"ok",...}`，然后将 endpoint、模型、token 参数和
API key 一起写入 `config/provider.local`。整个文件由 Windows DPAPI `CurrentUser` 加密，只有同一
Windows 用户可以解密，并且该文件被 Git 忽略。以后双击 `play-model.bat` 会自动读取，不再询问。
若 smoke 失败则不会保存。
普通的 `play.bat` 继续使用无费用的 scripted Agent。

也可以在启动 PowerShell 前由 secret store 设置以下进程环境变量，从而跳过交互输入：

```text
DUNGEONMIND_API_BASE
DUNGEONMIND_MODEL
DUNGEONMIND_API_KEY
```

默认向 `<API base>/chat/completions` 发送 OpenAI-compatible function tools。小米 endpoint 自动使用
`max_completion_tokens`；其他 endpoint 默认使用 `max_tokens`。需要覆盖时设置：

```text
DUNGEONMIND_TOKEN_LIMIT_FIELD=max_completion_tokens
```

当前 adapter 面向 Chat Completions 兼容服务。只提供原生 Responses、Anthropic Messages 或 Gemini
协议而没有兼容 endpoint 的供应商，需要单独的协议 adapter。

如需更换账号或清除凭据，关闭游戏后删除 `config/provider.local`，再重新运行
`provider-smoke.bat`。

不要改变 Java `TacticalSkillRegistry` 来适配供应商，也不要让 Python 直接生成 Java `Action`。
