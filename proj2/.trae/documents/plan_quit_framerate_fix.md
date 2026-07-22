# Plan: 修复 `:q` 不退出 + 帧率问题

## Summary
修复两个问题：1. `:q` 输入后游戏不退出；2. 游戏帧率不高。

## 问题 1：`:q` 不退出

### 根因
`playWithKeyboard()` 方法返回后，JVM 未退出。StdDraw 创建的 GUI 线程（如 EventDispatchThread）保活了 JVM 进程。

### 修复
**文件**: `byog/Core/Main.java`

在 `playWithKeyboard()` 调用后添加 `System.exit(0)`：
```java
} else {
    Game game = new Game();
    game.playWithKeyboard();
    System.exit(0);
}
```

---

## 问题 2：帧率不高

### 根因
主循环每帧都调用 `renderFrame()` 重绘全部 80×30 = 2400 个 tile，无意义地重复绘制。

### 修复
**文件**: `byog/Core/Game.java`

在 `playWithKeyboard()` 主循环中，当状态为 `PLAYING` 且无输入时（状态没变化），跳过本帧绘制：

```java
while (currentState != GameState.QUIT) {
    if (StdDraw.hasNextKeyTyped()) {
        char c = Character.toLowerCase(StdDraw.nextKeyTyped());
        currentState = updateState(currentState, c, seedStr);
        // 有输入时绘制
        draw(currentState, seedStr.toString());
    } else {
        // 无输入且在 PLAYING 状态时，跳过绘制（游戏画面不变）
        if (currentState != GameState.PLAYING) {
            draw(currentState, seedStr.toString());
        }
    }
    StdDraw.show();
    StdDraw.pause(16);
}
```

---

## 修改文件清单
1. `byog/Core/Main.java` — 添加 `System.exit(0)`
2. `byog/Core/Game.java` — 优化渲染逻辑，无输入时跳过绘制

## Verification
1. 编译运行，按 `n` → 输入种子 → `s` 开始游戏
2. 按 `:q` 确认游戏正确退出
3. 移动玩家确认画面正常更新
