# 战斗与血量显示功能实现计划

## 一、需求分析

### 功能需求
1. **战斗系统**：玩家移动到敌人位置时发起攻击，敌人移动到玩家位置时发起攻击
2. **血量管理**：实体受到伤害后HP减少，HP≤0时死亡
3. **血量显示**：在游戏界面顶部显示玩家当前HP和最大HP
4. **游戏结束**：玩家HP归零时进入Game Over状态

### 当前代码状态
- [Entity.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Entity.java)：抽象基类，已有 `alive` 字段和 `die()` 方法，但缺少 `hp` 和伤害处理
- [Player.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Player.java)：已有 `hp` 字段（初始100），`move()` 方法通过 `EntityManager.canMoveTo()` 检测碰撞
- [Enemy.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Enemy.java)：已有 `hp` 字段（初始10），`updateAI()` 方法通过 `MoveAction` 执行移动
- [EntityManager.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/EntityManager.java)：`canMoveTo()` 只返回布尔值，无法获取阻挡实体
- [Game.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Game.java)：已有 `PLAYING`、`MENU` 等状态，但缺少 `GAME_OVER` 状态

## 二、架构设计

### 核心设计决策

| 决策点 | 方案 | 理由 |
|--------|------|------|
| 伤害模型 | 固定伤害值 | 简单易实现，符合CS61B项目范围 |
| 战斗触发 | 移动碰撞检测 | 玩家/敌人尝试移动到对方位置时触发攻击 |
| 血量显示 | 顶部HUD文本 | 直观简洁，使用StdDraw.text绘制 |
| 渲染优化 | 修改TERenderer增加renderFrameNoShow() | 避免双缓冲闪烁 |

### 类图变更

```
Entity (抽象基类)
├── + hp: int (新增)
├── + maxHp: int (新增)
├── + takeDamage(int): void (新增)
└── + getMaxHp(): int (新增)

EntityManager
├── + getEntityAt(Position): Entity (新增)
└── + canMoveTo(Entity, Position, TETile[][]): boolean (修改)

Player
└── + move(Direction, TETile[][], EntityManager): void (修改)

Enemy
└── + updateAI(TETile[][], EntityManager): void (修改)

Game
├── + GAME_OVER: GameState (新增)
└── + drawHUD(): void (新增)
```

## 三、实现步骤

### 步骤1：修改 Entity 类 - 添加血量和伤害处理

**文件**：[Entity.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Entity.java)

**修改内容**：
1. 添加 `hp` 和 `maxHp` 字段
2. 修改构造函数，接收 `hp` 参数
3. 添加 `takeDamage(int damage)` 方法
4. 添加 `getMaxHp()` 方法

**风险**：Player和Enemy的构造函数需要适配新的Entity构造函数签名

---

### 步骤2：修改 EntityManager 类 - 添加获取实体方法

**文件**：[EntityManager.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/EntityManager.java)

**修改内容**：
1. 添加 `getEntityAt(Position p)` 方法，返回指定位置的实体

**风险**：无

---

### 步骤3：修改 Player 类 - 实现攻击逻辑

**文件**：[Player.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Player.java)

**修改内容**：
1. 修改构造函数，适配新的Entity构造函数
2. 修改 `move()` 方法：当目标位置被敌人占据时，攻击敌人而非移动
3. 添加攻击伤害常量（如 PLAYER_ATTACK = 3）

**风险**：需要确保攻击后敌人死亡时正确清理

---

### 步骤4：修改 Enemy 类 - 实现攻击玩家逻辑

**文件**：[Enemy.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Enemy.java)

**修改内容**：
1. 修改构造函数，适配新的Entity构造函数
2. 修改 `updateAI()` 或 `MoveAction`：当目标位置被玩家占据时，攻击玩家
3. 添加攻击伤害常量（如 ENEMY_ATTACK = 2）

**风险**：需要在MoveAction中传递EntityManager以获取阻挡实体

---

### 步骤5：修改 TERenderer 类 - 支持无show渲染

**文件**：[TERenderer.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/TileEngine/TERenderer.java)

**修改内容**：
1. 添加 `renderFrameNoShow(TETile[][] world)` 方法，不调用 `StdDraw.show()`

**风险**：无

---

### 步骤6：修改 Game 类 - 添加血量显示和Game Over状态

**文件**：[Game.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Game.java)

**修改内容**：
1. 在 `GameState` 枚举中添加 `GAME_OVER`
2. 添加 `drawHUD()` 方法，绘制玩家HP
3. 修改 `draw()` 方法，在PLAYING状态下渲染HUD
4. 在主循环中检测玩家死亡，切换到GAME_OVER状态
5. 添加 `drawGameOver()` 方法，绘制游戏结束界面

**风险**：需要确保save/load时正确保存玩家死亡状态

---

### 步骤7：修改 EntityState 类 - 添加 maxHp 字段

**文件**：[EntityState.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/EntityState.java)

**修改内容**：
1. 添加 `maxHp` 字段，用于存档

**风险**：无

---

### 步骤8：修改 SaveLoadManager 相关 - 保存/加载血量

**文件**：[Game.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Game.java)

**修改内容**：
1. 在 `saveGameState()` 中保存 `maxHp`
2. 在 `loadGameState()` 中加载 `maxHp`

**风险**：无

## 四、依赖关系

```
步骤1 (Entity)
    └── 影响步骤3 (Player)、步骤4 (Enemy)、步骤7 (EntityState)
步骤2 (EntityManager)
    └── 影响步骤3 (Player)、步骤4 (Enemy)
步骤5 (TERenderer)
    └── 影响步骤6 (Game)
步骤6 (Game)
    └── 依赖步骤1、步骤5
```

## 五、测试计划

### 单元测试
1. **Entity.takeDamage()**：测试HP减少和死亡逻辑
2. **EntityManager.getEntityAt()**：测试获取指定位置实体
3. **Player.move()**：测试攻击敌人逻辑
4. **Enemy.updateAI()**：测试攻击玩家逻辑

### 集成测试
1. **玩家攻击敌人**：玩家移动到敌人位置，敌人HP减少
2. **敌人攻击玩家**：敌人移动到玩家位置，玩家HP减少
3. **玩家死亡**：玩家HP归零时进入Game Over状态
4. **血量显示**：HUD正确显示玩家HP
5. **存档/读档**：HP状态正确保存和恢复

## 六、风险处理

| 风险 | 处理方案 |
|------|----------|
| Player/Enemy构造函数签名变更 | 保留旧构造函数重载，内部调用新构造函数 |
| 战斗时ConcurrentModificationException | 使用现有的pending机制，攻击后标记死亡，帧末清理 |
| 双缓冲闪烁 | 使用renderFrameNoShow() + 单次StdDraw.show() |
| 玩家死亡状态未保存 | 在GameState中记录并序列化 |

## 七、代码规范

1. 遵循项目现有代码风格（缩进、命名、注释）
2. 新方法添加简单注释说明功能
3. 使用Logger类输出调试信息
4. 确保save/load兼容性（处理旧存档无maxHp字段的情况）