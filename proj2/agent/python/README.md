# Python 智能体运行时

这个目录提供独立的 Python 智能体进程。它通过本机 TCP/NDJSON 接收 Java 生成的私有观察，
返回 `strategic-intent.v2`；移动、碰撞、攻击、伤害和最终提交始终由 Java 决定。

当前可直接运行两种大脑：

- `deterministic`：无第三方模型调用，用固定规则返回意图；
- `scripted`：用脚本化 `ModelAdapter` 驱动真实 LangGraph、只读工具、checkpoint、scheduler 和取消链。

真实模型模式尚未绑定任何供应商，也不会读取 API key。需要联调真实模型时，先实现项目自己的
`ModelAdapter`，再配置对应 API；不要把凭据写进仓库、trace 或 ready 输出。

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
  protocol.py        严格 envelope 与 intent v2 编解码
  server.py          多连接 reader、串行 response emitter 与生命周期
  config.py          有界运行配置；真实模型未配置时在 ready 前失败
  checkpoint.py      InMemorySaver / SqliteSaver 生命周期
  observability.py   白名单字段 model trace
  brain/             deterministic、graph brain 与 factory
  graph/             Agent state、只读 tools 和有界 StateGraph
  model/             供应商无关 adapter 与全局 scheduler
```

每条连接拥有独立 brain 和 response sequence。LangGraph checkpoint 用
`(worldId, floorId, agentId)` 编码后的无歧义 `thread_id` 隔离；scheduler 只共享并发和遭遇预算，
不能读取某个 Agent 的 graph state。

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
$env:PYTHONPATH = (Resolve-Path agent/python).Path
& agent/python/.venv/Scripts/python.exe -m unittest discover `
    -s agent/python/tests -v
```

测试覆盖跨语言 fixture、图工具链、checkpoint 重开、Agent 隔离、scheduler 并发上限、排队取消、
迟到结果抑制和 trace 脱敏。测试默认不访问外网、不调用付费服务，也不写玩家存档。

## 接入自己的 API

现在不需要配置。准备真实模型联调时再完成以下三件事：

1. 在 `dungeonmind_agent/model/` 实现 `ModelAdapter.invoke()`；
2. 在 `brain/factory.py` 的 `model` 组合路径注入该 adapter，并在 ready 前校验必需配置；
3. 通过本地环境变量或外部 secret store 提供凭据，然后单独运行真实 provider smoke。

不要改变 Java `TacticalSkillRegistry` 来适配供应商，也不要让 Python 直接生成 Java `Action`。
