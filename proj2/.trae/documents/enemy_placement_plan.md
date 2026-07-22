# 敌人放置功能实现计划

## 一、需求分析

用户要求：
1. 写放置敌人的逻辑，和放置 player 一模一样
2. 如果觉得有代码重复，则将放置实体这个功能模块化
3. 除了放置敌人和模块化之外，不要写任何其他东西

## 二、现有代码分析

### 当前玩家放置流程

在 [Player.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Player.java) 中：
- `canMoveTo(Position p, TETile[][] world)` - 判断位置是否可移动（只能站在地板上）
- `initPlayer(Player player, TETile[][] world, String seed)` - 使用 seed 随机初始化玩家位置

在 [Game.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Game.java) 中：
- `spawnPlayer(String seed)` - 创建 Player 并调用 initPlayer
- 在 `processInput` 的 SEED_INPUT 分支调用 `spawnPlayer`

### 代码重复问题

`canMoveTo` 和 `initPlayer` 的逻辑是通用的实体放置逻辑，不依赖 Player 特有属性，可以提取到 Entity 基类。

## 三、实现方案

### 3.1 模块化实体放置逻辑（Entity.java）

将通用的放置逻辑从 Player 移到 Entity 基类：

1. 添加静态方法 `canMoveTo(Position p, TETile[][] world)` - 判断位置是否可移动
2. 添加静态方法 `initEntity(Entity entity, TETile[][] world, String seed)` - 使用 seed 随机放置实体

### 3.2 重构 Player.java

移除重复的 `canMoveTo` 和 `initPlayer` 方法，改为调用 Entity 的共享方法。

### 3.3 添加敌人放置方法（Game.java）

添加 `spawnEnemy(String seed)` 方法，镜像 `spawnPlayer` 实现：
- 创建 Enemy 对象（传入 Random）
- 调用 Entity.initEntity 放置敌人
- 将敌人添加到 entities 和 enemies 列表

### 3.4 调用敌人放置

在以下两个地方调用 `spawnEnemy`：
1. SEED_INPUT 分支（生成新游戏时）
2. loadGameState（加载存档时）

## 四、文件修改清单

| 文件 | 修改内容 |
|------|----------|
| Entity.java | 添加 canMoveTo 和 initEntity 静态方法 |
| Player.java | 删除重复的 canMoveTo 和 initPlayer，改为调用 Entity 的方法 |
| Game.java | 添加 spawnEnemy 方法，在新游戏和读档时调用 |

## 五、风险与注意事项

1. Enemy 构造函数需要 Random 参数，spawnEnemy 需要先创建 Random 再传给 Enemy 构造函数
2. 保持与玩家放置完全一致的逻辑：随机选择位置，直到找到可移动的地板
3. 严格按照用户要求，不添加任何其他功能（如 AI 更新、战斗、敌人存档）