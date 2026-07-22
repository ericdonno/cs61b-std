# 暂停功能实现计划

## 需求分析

用户要求添加暂停功能：
- 通过**鼠标点击 UI 按钮**和 **`p` 键快捷键**触发暂停/恢复
- 暂停时游戏画面不变（冻结），仅按钮文字变化
- **不**使用"任意键恢复"

## 现有代码分析

### 状态机结构（Game.java）
```java
private enum GameState {
    MENU, SEED_INPUT, PLAYING, QUIT_PENDING, QUIT
}
```

### 主循环
```java
while (currentState != GameState.QUIT) {
    if (StdDraw.hasNextKeyTyped()) { ... processInput ... }
    if (currentState == GameState.PLAYING) { ... AI tick ... }
    draw(currentState, seedStr.toString());
    StdDraw.show();
    StdDraw.pause(16);
}
```

### 绘制逻辑（现状）
```java
case PLAYING:
case QUIT_PENDING:
    ter.renderFrame(buildActiveFrame());
    break;
```

### 渲染管线问题

`TERenderer.renderFrame()` 内部调用了 `StdDraw.clear()` + `StdDraw.show()`，完成一次完整的"清屏→绘制→显示"。这意味着要叠加 UI 按钮，**不能**简单在 `renderFrame()` 之后补画按钮然后靠主循环的 `show()` 显示 —— 双缓冲的缓冲区交换会导致按钮落在不含瓦片内容的旧缓冲区上。

**解决方案**：对 PLAYING/PAUSED 状态，不再使用 `ter.renderFrame()`，改为手动绘制瓦片 + 按钮，最后一次性 `show()`。

## 实现方案

### 1. 状态机扩展

```java
private enum GameState {
    MENU, SEED_INPUT, PLAYING, PAUSED, QUIT_PENDING, QUIT
}
```

### 2. 输入处理

**processInput 中的按键处理：**
- PLAYING 状态：`p` → PAUSED；`:` → QUIT_PENDING
- PAUSED 状态：`p` → PLAYING；`:` → QUIT_PENDING；其余按键忽略
- 其他状态不变

**主循环中的鼠标处理：**

新增鼠标按下状态追踪字段 `private boolean mouseWasPressed = false;`，实现边沿检测避免持续触发：

```java
// 处理鼠标点击暂停按钮（边沿检测）
boolean mousePressed = StdDraw.isMousePressed();
if (mousePressed && !mouseWasPressed) {
    double mx = StdDraw.mouseX();
    double my = StdDraw.mouseY();
    if (isInsidePauseButton(mx, my)) {
        if (currentState == GameState.PLAYING) {
            currentState = GameState.PAUSED;
        } else if (currentState == GameState.PAUSED) {
            currentState = GameState.PLAYING;
        }
    }
}
mouseWasPressed = mousePressed;
```

### 3. AI 更新控制

暂停时不执行敌人 AI，现有逻辑天然支持（只检查 `PLAYING`）：

```java
if (currentState == GameState.PLAYING) {
    // 敌人 AI 更新
}
```

### 4. 绘制逻辑（核心变更）

PLAYING 和 PAUSED 统一走自绘路径，仅在按钮文字上区分：

```java
case PLAYING:
case PAUSED:
case QUIT_PENDING:
    drawGameWithPauseButton(state == GameState.PAUSED);
    break;
```

**`drawGameWithPauseButton(boolean isPaused)` 方法：**

```java
private void drawGameWithPauseButton(boolean isPaused) {
    TETile[][] frame = buildActiveFrame();
    StdDraw.clear(new Color(0, 0, 0));
    // 绘制瓦片地图
    for (int x = 0; x < frame.length; x++) {
        for (int y = 0; y < frame[0].length; y++) {
            frame[x][y].draw(x, y);  // offset 均为 0
        }
    }
    // 绘制暂停按钮
    drawPauseButton(isPaused);
    StdDraw.show();
}
```

### 5. 暂停按钮 UI

**位置**：右上角区域，中心坐标 `(75.5, 28.5)`

**按钮绘制**（两个半透明矩形 + 文字）：

```java
// 按钮常量
private static final double BTN_CENTER_X = 75.5;
private static final double BTN_CENTER_Y = 28.5;
private static final double BTN_HALF_W = 3.5;
private static final double BTN_HALF_H = 0.8;

/** 绘制暂停/恢复按钮 */
private void drawPauseButton(boolean isPaused) {
    // 按钮背景
    StdDraw.setPenColor(isPaused ? new Color(60, 120, 60) : new Color(80, 80, 80));
    StdDraw.filledRectangle(BTN_CENTER_X, BTN_CENTER_Y, BTN_HALF_W, BTN_HALF_H);
    // 边框
    StdDraw.setPenColor(StdDraw.WHITE);
    StdDraw.rectangle(BTN_CENTER_X, BTN_CENTER_Y, BTN_HALF_W, BTN_HALF_H);
    // 文字
    StdDraw.text(BTN_CENTER_X, BTN_CENTER_Y, isPaused ? "|> Resume (P)" : "|| Pause (P)");
}

/** 判断鼠标点击是否在按钮区域内 */
private boolean isInsidePauseButton(double mouseX, double mouseY) {
    return mouseX >= BTN_CENTER_X - BTN_HALF_W
        && mouseX <= BTN_CENTER_X + BTN_HALF_W
        && mouseY >= BTN_CENTER_Y - BTN_HALF_H
        && mouseY <= BTN_CENTER_Y + BTN_HALF_H;
}
```

- 未暂停时：灰底白字 `"|| Pause (P)"`（暗示 P 键快捷键）
- 暂停时：绿底白字 `"|> Resume (P)"`（视觉效果区分）

## 修改文件

| 文件 | 修改内容 |
|------|----------|
| `byog/Core/Game.java` | 1. 添加 PAUSED 枚举值<br>2. 添加 `mouseWasPressed` 字段<br>3. processInput 新增 PAUSED 分支<br>4. 主循环新增鼠标点击检测<br>5. draw 方法改为自绘 + 按钮<br>6. 新增 `drawGameWithPauseButton`、`drawPauseButton`、`isInsidePauseButton` 方法 |

## 具体步骤

1. **GameState 枚举**：添加 `PAUSED`
2. **新增字段**：`private boolean mouseWasPressed = false;`
3. **processInput 方法**：
   - PLAYING：`'p'` → PAUSED
   - PAUSED：`'p'` → PLAYING；`':'` → QUIT_PENDING；其余忽略
4. **主循环**：在按键处理之后、AI 更新之前，添加鼠标边沿检测
5. **draw 方法**：PLAYING / PAUSED / QUIT_PENDING 统一调用 `drawGameWithPauseButton()`
6. **新增方法**：`drawGameWithPauseButton()`、`drawPauseButton()`、`isInsidePauseButton()`
7. **编译验证**

## 风险评估

- **低风险**：仅修改 Game.java，不涉及其他模块
- **渲染变化**：PLAYING/QUIT_PENDING 的渲染从 `ter.renderFrame()` 改为自绘，功能等价但需验证画面效果一致
- **鼠标支持**：依赖 StdDraw 的 `isMousePressed()` / `mouseX()` / `mouseY()`，这些是 stdlib-package.jar 中 StdDraw 的标准方法

## 按键设计

| 输入 | 作用 |
|------|------|
| `p` 键 | 暂停 / 恢复 toggle |
| 鼠标点击按钮 | 暂停 / 恢复 toggle |
| PAUSED 状态下其他按键 | 忽略 |
| PAUSED 状态下 `:` | 进入 QUIT_PENDING（退出流程） |
