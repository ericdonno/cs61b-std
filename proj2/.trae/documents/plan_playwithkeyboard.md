# Plan: 实现 playWithKeyboard()

## Summary
实现 `Game.playWithKeyboard()` 方法，使游戏能够通过实时键盘输入进行交互。方法需支持：主菜单（新建游戏/加载游戏/退出）、种子输入、新游戏游玩、保存退出等功能。

## Current State Analysis
- `Game.playWithKeyboard()` 方法体为空（仅有注释）
- `Game.playWithInputString()` 已完整实现所有游戏逻辑状态机
- 项目使用 `StdDraw` 进行图形渲染和键盘输入
- TERenderer 已实现 `initialize()` 和 `renderFrame()` 方法
- `WorldGenerator.RandomSquareRoomWrd()` 用于生成世界
- `SaveLoadManager` 已实现存档功能

## Proposed Changes

### 1. 实现 `playWithKeyboard()` 方法
**文件**: `byog/Core/Game.java`

参考 `playWithInputString()` 的状态机逻辑，但改用实时键盘输入：

```
1. 初始化 TERenderer (ter.initialize(WIDTH, HEIGHT))
2. 初始化 entities, player, keyBindings
3. 进入菜单状态循环:
   - 绘制主菜单 (New Game 'n' / Load Game 'l' / Quit 'q')
   - 等待键盘输入

   MENU 状态:
   - 'n' -> 切换到 SEED_INPUT
   - 'l' -> 调用 loadGameState(), 成功则进入 PLAYING
   - 'q' -> 直接返回

   SEED_INPUT 状态:
   - 数字键 -> 追加到 seedStr
   - 's' -> 生成世界, spawnPlayer, 进入 PLAYING
   - 实时绘制已输入的种子数字

   PLAYING 状态:
   - 'w/a/s/d' -> 移动玩家
   - ':' -> 进入 QUIT_PENDING
   - 其他输入 -> 通过 handlePlayerInput 处理

   QUIT_PENDING 状态:
   - 'q' -> 保存并退出
   - 其他 -> 返回 PLAYING 并处理该输入
```

### 2. 绘制菜单和UI的辅助方法
需要新增以下私有方法：

```java
private void drawMenu() // 绘制主菜单
private void drawSeedInput(String seed) // 绘制种子输入界面
private void drawHUD() // 可选：绘制游戏中的HUD
```

## Key Implementation Details

### 键盘输入处理
```java
// 主循环
while (currentState != QUIT) {
    if (StdDraw.hasNextKeyTyped()) {
        char c = Character.toLowerCase(StdDraw.nextKeyTyped());
        // 处理输入
    }
    // 根据状态绘制
    StdDraw.show();
    StdDraw.pause(50); // 控制帧率
}
```

### 菜单绘制
使用 `StdDraw.text()` 在屏幕中央绘制选项：
- "New Game (N)"
- "Load Game (L)"
- "Quit (Q)"

### 种子输入绘制
- 显示 "Enter seed:" 和已输入的数字
- 实时更新显示

### 游戏画面绘制
使用 `ter.renderFrame(frame)` 绘制游戏世界

## Assumptions & Decisions
1. 使用与 `playWithInputString()` 相同的状态机和逻辑
2. 菜单使用纯文本界面，使用 StdDraw 绘制
3. 游戏循环使用 `StdDraw.pause(50)` 控制约20FPS
4. 忽略大小写，统一转换为小写处理
5. 如果加载存档失败，保持在 MENU 状态

## Verification Steps
1. 编译运行程序，无编译错误
2. 验证主菜单正确显示
3. 按 'n' 后输入种子，验证种子输入界面
4. 按 's' 开始游戏，验证世界生成和玩家出现
5. 使用 WASD 移动玩家，验证碰撞检测
6. 输入 ':q' 退出，验证存档保存
7. 重新运行，按 'l' 验证存档加载
