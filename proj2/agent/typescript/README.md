# TypeScript 运行时边界

未来的 TypeScript 实现应放在此目录，并与 Python 运行时保持相同的稳定职责：

runtime（运行时）指独立于 Java 游戏的 Agent 进程；protocol（协议）规定跨进程消息；
codec（编解码器）负责类型化对象与 NDJSON 字节的转换和校验；DTO（数据传输对象）只
承载固定协议字段；brain（大脑）根据私有观察提出战略意图；server（服务器）监听连接
并管理每条连接的生命周期。

```text
typescript/
  src/
    protocol/     严格编解码和数据传输对象校验
    brain/        确定性决策与模型驱动的决策实现
    server/       多连接运行时与生命周期
  package.json    依赖、构建脚本和命令行入口
```

TypeScript 运行时必须实现与 Java、Python 相同的消息字段、方向、版本、大小限制和
拒绝规则。它不得导入 Python 实现代码，也不得把模型逻辑移入 Java 的 `byog/Bridge` 包。
