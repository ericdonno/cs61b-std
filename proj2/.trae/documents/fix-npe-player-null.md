# 修复：NPE - player 为空时的空指针异常

## 问题概述

`buildActiveFrame()` 在 `world` 或 `player` 为 `null` 时直接访问导致崩溃，需要增强健壮性。

## 涉及文件

| 文件 | 修改内容 |
|------|----------|
| [Game.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Game.java) | `buildActiveFrame()` 添加空指针防御 |

## 修改方案

### `buildActiveFrame()` — 添加空指针防御

**位置**: `Game.java` 第 270-281 行

**修改内容**: 在方法开头添加 null 检查，状态未就绪时安全返回空世界：

```java
if (world == null || player == null) {
    return createEmptyWorld();
}
```

## 验证步骤

1. 删除 `./save/game.ser`（如果有的话）
2. 运行 `Main.main()`，按 `n` → 输入种子 → `s`，确认新游戏正常运行
3. 游戏中按 `:q` 保存后，再按 `l` 加载，确认正常
