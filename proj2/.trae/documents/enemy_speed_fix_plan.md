# 敌人移动速率调整方案

## 问题分析

当前敌人移动速率过快的原因：

1. **主循环频率**：[Game.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Game.java#L69) 中 `StdDraw.pause(16)` 意味着约 60FPS
2. **每帧执行**：[Game.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Game.java#L56-L60) 每帧调用所有敌人的 `updateAI()`
3. **无条件移动**：[Enemy.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Enemy.java#L44-L52) 的 `updateAI()` 每帧都执行一次移动动作

结果：敌人每秒移动约 60 次，远超玩家移动频率。

## 解决方案

在 `Enemy` 类中添加 **移动冷却机制**（tick 计数器）：
- 添加 `moveInterval` 字段：控制移动间隔（帧数）
- 添加 `tickCounter` 字段：记录当前帧计数
- 修改 `updateAI()`：只在 `tickCounter` 达到 `moveInterval` 时执行移动

## 修改文件

### 1. `byog/Core/Enemy.java`

**修改内容：**
- 添加字段：
  - `private int moveInterval;` - 移动间隔帧数（默认 5，即每 5 帧移动一次）
  - `private int tickCounter;` - 当前帧计数器
- 修改构造方法：初始化 `moveInterval` 和 `tickCounter`
- 修改 `updateAI()`：添加 tick 计数逻辑，仅在间隔达到时执行移动

### 2. `byog/Core/GameSaveData.java`（如需）

检查是否需要保存 `moveInterval` 和 `tickCounter`。由于 `tickCounter` 是运行时状态，可以不保存；`moveInterval` 是配置常量，也不需要保存。

## 具体修改步骤

1. **添加字段**（Enemy.java）：
```java
private int moveInterval;
private int tickCounter;
```

2. **修改构造方法**（Enemy.java）：
```java
public Enemy(Position position, Random random) {
    this(position, Tileset.ENEMY, 10, 7, 5, random);  // 默认间隔 5 帧
}

public Enemy(Position position, TETile tile, int hp, int sightRange, int moveInterval, Random random) {
    super(position, tile);
    this.hp = hp;
    this.sightRange = sightRange;
    this.moveInterval = moveInterval;
    this.tickCounter = 0;
    this.random = random;
    this.actionQueue = new ActionQueue();
}
```

3. **修改 updateAI()**（Enemy.java）：
```java
public void updateAI(TETile[][] world, EntityManager entityMgr) {
    tickCounter++;
    if (tickCounter >= moveInterval) {
        tickCounter = 0;
        if (actionQueue.needRefill()) {
            actionQueue.enqueue(new MoveAction(randomDirection(), entityMgr));
        }
        Action action = actionQueue.poll();
        if (action != null) {
            action.execute(world, this);
        }
    }
}
```

4. **更新 spawnEnemies()**（Enemy.java）：更新调用新构造方法

5. **更新 loadGameState()**（Game.java）：更新读档时的构造方法调用

## 风险评估

- **低风险**：修改局限于 Enemy 类内部逻辑，不影响其他模块
- **可调整**：`moveInterval` 值可根据游戏体验调整（建议从 5 开始测试）
- **兼容性**：需要同步更新构造方法调用点

## 预期效果

- 敌人移动速率从 ~60 次/秒 降低到 ~12 次/秒（当 `moveInterval = 5`）
- 玩家移动频率保持不变（按键触发）
- 游戏体验更平衡

## 测试建议

1. 运行游戏，观察敌人移动速度是否明显降低
2. 根据实际体验调整 `moveInterval` 值（3-10 之间）
3. 测试读档功能是否正常
