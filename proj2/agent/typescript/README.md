# TypeScript 运行时边界

未来的 TypeScript 实现应放在此目录，并与 Python 运行时保持相同的稳定职责：

```text
typescript/
  src/
    protocol/     严格编解码和数据传输对象校验
    brain/        确定性决策与模型驱动的决策实现
    server/       多连接运行时与生命周期
  test/           契约、大脑和运行时测试
  package.json    命令行入口与验证命令
```

TypeScript 运行时必须使用与 Java、Python 相同的契约测试样例。它不得导入
Python 实现代码，也不得把模型逻辑移入 Java 的 `byog/Bridge` 包。
