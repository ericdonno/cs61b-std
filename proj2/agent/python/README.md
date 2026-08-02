# Python 智能体运行时

此目录包含 DungeonMind 的 Python 智能体实现。它作为独立进程运行，通过
本机 TCP 和 NDJSON 协议与 Java 游戏进程通信，不直接访问 Java 对象、完整地图
或玩家的隐藏状态。

完整设计说明见 [`agentarchitecture.md`](../../agentarchitecture.md)。

文中的 runtime（运行时）是这个独立 Python 进程；TCP 是提供可靠有序字节流的网络
协议；NDJSON 是“每行一个 JSON 对象”的消息格式；codec（编解码器）负责把 Python
对象与协议字节互相转换并校验字段；brain（大脑）只根据 observation（私有观察）生成
intent（战略意图），不会直接生成或执行 Java `Action`；deterministic（确定性）表示
相同观察产生相同决策。

## 目录结构

```text
python/
  dungeonmind_agent/
    protocol.py            严格协议编解码与字段校验
    server.py              多连接 TCP 服务器与故障模式
    brain/
      deterministic.py     确定性决策大脑
  run.py                   命令行入口
```

协议与传输、大脑决策相互独立：`protocol.py` 只负责通信契约，`server.py` 只负责
连接和生命周期，`brain/` 只根据已经校验的 observation 生成受限 intent。

## 启动运行时

在项目根目录执行：

```powershell
python agent/python/run.py `
    --host 127.0.0.1 `
    --port 9876 `
    --mode normal
```

服务器启动成功后会向标准输出写入一行 JSON ready 信号；ready 表示监听端口已经建立，
父进程或操作者现在可以连接。生产 `Main` 不负责
自动启动或结束该进程，并且当前使用默认关闭的 Bridge 配置；单独看到 ready
不表示交互式游戏已经连接。自定义入口可向 `Game` 传入启用的 `AgentSessionConfig`。

## 运行模式

| 模式 | 行为 |
|------|------|
| `normal` | 立即返回合法且确定的 intent |
| `delay` | 故意延迟返回，使 Java 在等待期间继续使用本地控制 |
| `malformed` | 故意返回非法帧，使 Java 进入协议拒绝路径 |
| `disconnect` | 收到 observation 后断开连接 |
| `no-read` | 接受连接但不读取数据，让发送压力逐步传回 Java 的有界队列 |

`delay` 模式可通过 `--delay-seconds` 调整延迟时间。所有模式均不得访问外部
模型或网络服务。

## 实现边界

- Python 只消费协议提供的私有 observation，不读取 Java 世界对象。
- Python 只能提出白名单内的战略 intent，移动、碰撞、攻击和伤害仍由 Java 判定。
- 每条 TCP 连接拥有独立的大脑和消息序列，不共享 Enemy 状态。
- 新的大脑实现放入 `dungeonmind_agent/brain/`，不得混入协议或服务器模块。
- 跨语言协议变更必须同步更新 `agent/contract/`、Java codec 和所有语言实现。
