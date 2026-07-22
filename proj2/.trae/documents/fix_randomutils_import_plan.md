# 修复 RandomUtils 导入路径计划

## 问题描述

包重构后，`RandomUtils` 从 `byog.Core` 迁移到了 `byog.Common`，但部分文件仍引用旧路径，导致编译错误。

## 影响文件

通过全局搜索 `byog.Core.RandomUtils`，发现以下文件需要修复：

| 文件 | 行号 | 当前引用 | 需修改为 |
|------|------|----------|----------|
| `byog/SaveDemo/World.java` | 7 | `import byog.Core.RandomUtils;` | `import byog.Common.RandomUtils;` |
| `documents/PROJECT_DOCUMENTATION.md` | 355 | `byog.Core.RandomUtils` | `byog.Common.RandomUtils` |

## 修改步骤

1. 修改 `byog/SaveDemo/World.java` 的 import 语句
2. 修改 `documents/PROJECT_DOCUMENTATION.md` 中的路径引用

## 风险评估

- 低风险：仅修改 import 语句和文档中的路径引用，不涉及代码逻辑变更
- 无需测试：这是包重构后的残留问题，编译验证已通过

## 验证方法

修复后可通过编译验证确认无误。