# UI 栏迁移计划

## 需求

暂停按钮当前叠加在地图右上角，遮挡游戏内容。改为独立的顶部 UI 栏：
- UI 栏高 2 格，位于窗口最上方
- 暂停按钮放入 UI 栏内，不再遮挡地图
- UI 栏预留空间，后续可放置玩家 HP、状态等信息

## 现状分析

### 核心字段
```
WIDTH=80, HEIGHT=30          → 窗口 80×30 格，世界 TETile[80][30]
ter.initialize(WIDTH, HEIGHT) → yScale = 0~30
drawGameWithPauseButton()     → 自绘瓦片(0..29) + 按钮(y=28.5)
```

### 关键约束
- 不能修改世界数据结构（`playWithInputString` 返回值需保持 80×30）
- 已走自绘路径，不依赖 `ter.renderFrame()`

## 方案设计

### 核心思路

窗口纵向扩展 2 格 → `80×32`，世界数据仍是 `80×30`。瓦片绘制在 y=0..29，UI 栏用 StdDraw 绘制在 y=30..32。

```
┌──────────────────────────────┐  y=32  ← 窗口顶部
│  UI 栏 (StdDraw 绘制)        │  y=30..32
│  [暂停按钮]  [HP: 100] ...   │
├──────────────────────────────┤  y=30  ← UI/地图分界线
│                              │
│  游戏地图 (瓦片绘制)          │  y=0..30
│                              │
│                              │
└──────────────────────────────┘  y=0   ← 窗口底部
```

### 具体变更

#### 1. 新增常量

```java
public static final int UI_HEIGHT = 2;  // UI 栏高度（格）
public static final int WINDOW_HEIGHT = HEIGHT + UI_HEIGHT;  // = 32
```

#### 2. 窗口初始化

`playWithKeyboard()`:
```java
ter.initialize(WIDTH, WINDOW_HEIGHT);  // 原: ter.initialize(WIDTH, HEIGHT)
```

`playWithInputString()` 中的渲染初始化同步修改:
```java
ter.initialize(WIDTH, WINDOW_HEIGHT);  // 原: ter.initialize(WIDTH, HEIGHT)
```

#### 3. UI 栏绘制方法（新增）

```java
/** 绘制顶部 UI 栏。 */
private void drawUIBar(boolean isPaused) {
    // 深灰背景
    StdDraw.setPenColor(new Color(30, 30, 30));
    StdDraw.filledRectangle(WIDTH / 2.0, HEIGHT + UI_HEIGHT / 2.0,
                            WIDTH / 2.0, UI_HEIGHT / 2.0);
    // 分隔线
    StdDraw.setPenColor(new Color(100, 100, 100));
    StdDraw.line(0, HEIGHT, WIDTH, HEIGHT);
    // 暂停按钮
    drawPauseButton(isPaused);
}
```

#### 4. 暂停按钮位置更新

移至 UI 栏区域：

```java
private static final double BTN_CENTER_X = 76.0;
private static final double BTN_CENTER_Y = HEIGHT + UI_HEIGHT / 2.0;  // y=31
private static final double BTN_HALF_W = 3.5;
private static final double BTN_HALF_H = 0.75;
```

#### 5. 渲染方法更新

`drawGameWithPauseButton()` 改为调用 `drawUIBar()`:

```java
private void drawGameWithPauseButton(boolean isPaused) {
    TETile[][] frame = buildActiveFrame();  // 80×30，不变
    StdDraw.clear(new Color(0, 0, 0));
    for (int x = 0; x < frame.length; x++) {
        for (int y = 0; y < frame[0].length; y++) {
            frame[x][y].draw(x, y);
        }
    }
    drawUIBar(isPaused);
    StdDraw.show();
}
```

- 瓦片绘制坐标不变 `(x, y)`，仍在 y=0..29
- UI 栏绘制在 y=30..32，不覆盖瓦片

## 修改清单

| 文件 | 修改内容 |
|------|----------|
| `byog/Core/Game.java` | 添加 `UI_HEIGHT`/`WINDOW_HEIGHT` 常量 |
| | `playWithKeyboard()` 窗口初始化改为 `WINDOW_HEIGHT` |
| | `playWithInputString()` 渲染初始化改为 `WINDOW_HEIGHT` |
| | 按钮坐标常量移到 UI 栏范围 |
| | 新增 `drawUIBar()` 方法 |
| | `drawGameWithPauseButton()` 调用 `drawUIBar()` 替代 `drawPauseButton()` |
| | 删除旧 `drawPauseButton()` 方法（逻辑合并到 drawUIBar） |

## 不变项

- `WIDTH`、`HEIGHT` 值不变
- `createEmptyWorld()` 不变 → `TETile[80][30]`
- `buildActiveFrame()` 返回 80×30 → autograder 兼容
- `processInput()`、AI 更新逻辑不变
- 除 UI 栏坐标外全部渲染逻辑不变

## 验证

1. 编译通过
2. 运行游戏 → 暂停按钮出现在顶部 UI 栏，不遮挡地图
3. 鼠标点击按钮 / 按 P 键 → 暂停切换正常
4. `playWithInputString` 返回帧仍为 80×30
