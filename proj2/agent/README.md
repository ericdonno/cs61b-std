# 外部智能体运行时

此目录用于隔离 Java 游戏边界与不同语言实现的智能体运行时。

这里的 runtime（运行时）指独立于 Java 游戏进程、负责执行 Agent 决策的程序；
contract（契约）指 Java 与各语言运行时共同遵守的消息格式和语义；codec（编解码器）
负责在类型化对象与网络 JSON 之间转换；Bridge（桥接层）是 Java 游戏侧的协议、会话和
传输边界，不包含具体语言的大脑。

完整架构、线程模型、协议、故障模式和扩展边界见
[`agentarchitecture.md`](../agentarchitecture.md)。

当前 Python deterministic runtime 已与启用配置下的 Game/Enemy 完成真实进程闭环；
普通 `Main` 仍默认关闭 Bridge，真实模型大脑尚未接入。

```text
agent/
  contract/      跨语言通信契约文档
  python/        Python 运行时、大脑和命令行入口
  typescript/    与 Python 平行的 TypeScript 运行时扩展位置
```

每种语言的运行时独立拥有编解码器、传输服务器和大脑实现，并且必须
保持共享契约定义的身份元组和消息语义。特定语言的模型代码不得放入
`byog/Bridge`；该 Java 包只负责游戏侧的客户端边界。
