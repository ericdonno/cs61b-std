# 游戏实体架构重构计划

## 一、当前问题分析

### 1.1 现有代码结构
- **Player.java**：玩家直接修改 `world` 数组，使用 `standingOn` 字段保存脚下 tile
- **Game.java**：混合了世界生成、玩家初始化和输入处理逻辑
- **TETile**：不可变对象，作为 tile 的共享常量

### 1.2 当前方案的缺陷
1. `standingOn` 初始为 `null`，首次移动会导致世界出现空洞
2. Player 直接修改世界数组，污染了静态地形数据
3. 扩展性差：添加敌人、道具等需要大量修改
4. 渲染逻辑与游戏状态耦合紧密

---

## 二、推荐方案：静态世界 + 实体列表

### 2.1 架构设计

```
┌─────────────────────────────────────────────────────┐
│                    Game.java                        │
│  ┌─────────────────┐  ┌─────────────────────────┐  │
│  │ TETile[][] world │  │ List<Entity> entities   │  │
│  │ (静态地形)        │  │ (玩家、敌人、道具等)     │  │
│  └─────────────────┘  └─────────────────────────┘  │
│          │                        │                │
│          ▼                        ▼                │
│  ┌─────────────────────────────────────────────┐   │
│  │           renderFrame()                      │   │
│  │  1. 拷贝 world → frame                       │   │
│  │  2. 叠加所有实体到 frame                      │   │
│  │  3. 返回 frame                              │   │
│  └─────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

### 2.2 核心类设计

| 类名 | 职责 | 状态 |
|------|------|------|
| **Entity** | 实体基类，定义位置和显示 tile | position, tile |
| **Player** | 玩家实体，继承 Entity | 移动逻辑 |
| **Game** | 游戏主控，管理世界和实体 | world, entities, player |

---

## 三、修改计划

### 3.1 文件修改清单

| 文件 | 操作 | 修改内容 |
|------|------|----------|
| `byog/Core/Entity.java` | **新增** | 实体基类 |
| `byog/Core/Player.java` | 修改 | 继承 Entity，移除 world 操作 |
| `byog/Core/Game.java` | 修改 | 添加实体管理和渲染逻辑 |

### 3.2 步骤分解

#### 步骤 1：创建 Entity 基类
```java
public abstract class Entity {
    protected Position position;
    protected TETile tile;
    // 构造函数和 getter/setter
}
```

#### 步骤 2：重构 Player 类
- 继承 Entity
- 移除 `standingOn` 字段
- 移除直接修改 world 的逻辑
- 保留移动方向计算和碰撞检测

#### 步骤 3：修改 Game 类
- 分离静态世界和动态实体
- 添加实体列表 `List<Entity>`
- 实现 `renderFrame()` 方法（拷贝世界 + 叠加实体）
- 实现玩家移动的协调逻辑

---

## 四、关键代码变更

### 4.1 Player.move() 重构前
```java
public void move(Direction direction, TETile[][] worldState) {
    // 直接修改 world 数组
    worldState[this.position.x][this.position.y] = this.standingOn;
    // ...
}
```

### 4.2 Player.move() 重构后
```java
// Player 不再直接操作 world，由 Game 层负责协调
public Position getNewPosition(Direction direction) { /* ... */ }
```

### 4.3 Game.renderFrame() 实现
```java
public TETile[][] renderFrame() {
    TETile[][] frame = TETile.copyOf(world);
    // 叠加玩家
    Position p = player.getPosition();
    frame[p.x][p.y] = player.getTile();
    // 叠加其他实体
    for (Entity e : entities) {
        Position ep = e.getPosition();
        frame[ep.x][ep.y] = e.getTile();
    }
    return frame;
}
```

---

## 五、风险与处理

| 风险 | 处理方式 |
|------|----------|
| 渲染性能 | 使用浅拷贝 `TETile.copyOf()`，只复制引用数组 |
| 碰撞检测遗漏 | 在 Game 层统一处理静态和动态碰撞 |
| 现有测试失效 | 修改后运行测试验证，确保行为一致 |
| 序列化保存 | 只需保存 world 和实体状态，逻辑清晰 |

---

## 六、扩展性分析

完成重构后，添加新功能将非常简单：

1. **添加敌人**：创建 `Enemy extends Entity`，添加 AI 移动逻辑
2. **添加道具**：创建 `Item extends Entity`，实现拾取逻辑
3. **开门机制**：修改 Game 层碰撞检测，检测钥匙状态
4. **多层地图**：添加 `currentFloor` 和 `floors` 列表

---

## 七、任务清单

1. ✅ 创建 Entity 基类
2. ✅ 重构 Player 类
3. ✅ 修改 Game 类
4. ✅ 测试验证