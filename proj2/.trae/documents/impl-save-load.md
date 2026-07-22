# 实现 playWithInputString 的 Load 和 Save 功能

## 摘要
为 `Game` 类实现存档功能，采用模块化设计，支持未来扩展游戏机制。

## 当前状态分析
- `Game.java` 已有状态机框架，支持 `n` (新游戏)、`l` (加载)、`q` (退出)
- TODO 标记了两处：`loadGameState()` 和 `saveGameState()`
- 世界由 seed 确定性生成，无需保存整个 TETile[][]
- `SaveDemo` 中已有 Java 序列化存档的参考实现

## 架构设计（三层分离）

```
GameSaveData (DTO) ──→ SaveLoadManager (静态工具) ──→ Game.java (游戏逻辑)
     │                         │
     └── 纯数据容器              └── 文件 I/O
```

## 实现步骤

### Step 1: 新建 `byog/Core/GameSaveData.java`
- 实现 `Serializable`
- 字段：`seed`, `playerX`, `playerY`
- 开放式 `extraData: Map<String, Serializable>` 用于未来扩展

### Step 2: 新建 `byog/Core/SaveLoadManager.java`
- 静态工具类，与 Game 解耦
- 方法：`save(GameSaveData)`, `load()`, `saveExists()`
- 文件路径：`./save/game.ser`

### Step 3: 修改 `byog/Core/Game.java`
**3.1 添加 seed 字段**
- 在 `world`, `player`, `entities`, `keyBindings` 之后添加 `private String seed;`

**3.2 MENU 状态接入加载**
- 将 `// TODO: 加载游戏存档逻辑` 替换为 `if (loadGameState())`

**3.3 SEED_INPUT 状态保存 seed**
- 将 `String seed = seedStr.toString()` 改为 `this.seed = seedStr.toString()`
- 对应 `WorldGenerator.RandomSquareRoomWrd(world, this.seed)` 和 `Player.initPlayer(player, world, this.seed)`

**3.4 QUIT_PENDING 状态接入保存**
- 将 `// TODO: 保存游戏存档逻辑` 替换为 `saveGameState()`

**3.5 添加两个私有方法**
- `private void saveGameState()`：收集 seed + 玩家位置，调用 SaveLoadManager
- `private boolean loadGameState()`：调用 SaveLoadManager.load()，用 seed 重建世界，恢复玩家位置

## 验证步骤
1. 编译：`javac -cp . byog/Core/*.java`
2. 测试新游戏存档：`java byog.Core.Main n123sss:q` → 应生成 `save/game.ser`
3. 测试加载：`java byog.Core.Main lww` → 应加载存档并继续移动
4. 测试加载不存在的存档：程序应正常退出

## 注意事项
- 保留 Game.java 中所有现有注释不变
- 不要修改 `playWithKeyboard()` 方法
- Player 构造函数已有 `Player(Position)` 支持位置恢复
