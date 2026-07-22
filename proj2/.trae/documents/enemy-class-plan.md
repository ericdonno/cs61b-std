# 创建 Enemy 类 & 多类型敌人扩展性设计

## 摘要

按照构建指南阶段 1.3-1.4，创建 Action / MoveAction / ActionQueue 体系，再实现 Enemy 类。同时设计 Enemy 类的扩展点，使未来引入不同类型敌人（Boss、巡逻兵、追击者）时只需组合不同组件，无需修改 Enemy 类。

---

## 当前状态分析

| 文件 | 状态 |
|------|------|
| `Enemy.java` | 空壳，仅 `Enemy(Position, TETile)` 构造函数 |
| `Entity.java` | 抽象基类，字段 `position`(Position) + `tile`(TETile) |
| `Direction.java` | 仅有 UP/DOWN/LEFT/RIGHT 四个枚举值，无 dx/dy |
| `Action.java` / `MoveAction.java` / `ActionQueue.java` | **不存在**，需新建 |
| `Tileset.ENEMY` | 已存在，`'E'` 红色前景黑色背景 |
| `Game.java` | entities 列表仅含 player，无 AI tick，碰撞检测未处理敌人 |
| `Position` | **无 equals() 覆写**，比较须用 `p1.x == p2.x && p1.y == p2.y` |

---

## 拟议变更

### 步骤 1：创建 Action 接口 & MoveAction 类

**文件**：`byog/Core/Action.java`（新建）、`byog/Core/MoveAction.java`（新建）

**做什么**：
- `Action` 接口：定义 `ActionResult execute(TETile[][] world, Position currentPos)` 方法
- `ActionResult` 枚举：`SUCCESS` / `BLOCKED` / `INTERRUPTED` / `COMPLETED`
- `MoveAction` 实现 `Action`：持有一个 `Direction` 字段，`execute()` 中计算 `newX = currentPos.x + direction.dx`（手动计算，暂不依赖 Direction 的 dx/dy 字段——Direction 的扩展是独立步骤 1.2），用 `Player.canMoveTo()` 检测合法性，合法则更新 Position 并返回 `SUCCESS`，否则返回 `BLOCKED`。

**为什么**：MoveAction 是敌人执行一步移动的最小动作单元。将"移动一步"封装为独立 Action，后续 ClassicalPlanner 可以把 BFS 路径转换为 MoveAction 序列。

**如何验证**：写 main 测试，创建 MoveAction → 用假 world 调用 execute → 检查 position 变化。

---

### 步骤 2：创建 ActionQueue 类

**文件**：`byog/Core/ActionQueue.java`（新建）

**做什么**：
- 内部用 `ArrayDeque<Action>` 存储动作
- `enqueue(Action)` / `enqueueAll(List<Action>)` — 入队
- `poll()` — 出队并返回
- `needRefill()` — 队列长度 ≤ 2 时返回 true
- `clear()` — 清空队列

**为什么**：ActionQueue 是"滚动视界预测"的基础——AI 一次生成多个动作存入队列，逐帧消费。队列空了才触发下一批思考。这解耦了"AI 思考频率"和"游戏帧率"。

**如何验证**：手工人队几个 MoveAction → 逐一 poll 并打印。

---

### 步骤 3：创建 Enemy 类（含多类型扩展性设计）

**文件**：`byog/Core/Enemy.java`（修改）

#### 3.1 基础字段

```java
public class Enemy extends Entity {
    private ActionQueue actionQueue;    // 动作队列
    private int hp;                      // 生命值
    private int sightRange;              // 视野范围（为阶段二预留）
    private Random random;               // 独立随机序列
}
```

#### 3.2 构造函数设计（扩展点 1：参数化差异化）

```java
// 默认构造函数（一般敌人）
public Enemy(Position position, TETile tile, Random random)

// 可配置构造函数（不同类型敌人通过参数区分）
public Enemy(Position position, TETile tile, int hp, int sightRange, Random random)
```

**设计思想**：不同敌人类型的差异（HP、视野、瓦片外观）通过构造函数参数注入，而非通过子类化。例如：
- 巡逻兵：`new Enemy(pos, Tileset.ENEMY, 10, 5, random)` — 低 HP 短视野
- Boss：`new Enemy(pos, BOSS_TILE, 50, 10, random)` — 高 HP 长视野
- 快速敌人：`new Enemy(pos, FAST_TILE, 5, 3, random)` — 低 HP 短视野但可多动（通过 Brain 控制）

#### 3.3 核心方法

```java
// 随机方向选择
private Direction randomDirection() { ... }

// AI 更新（阶段一：纯随机移动）
public void updateAI(TETile[][] world) {
    if (actionQueue.needRefill()) {
        // 随机选方向 → 创建 MoveAction → 入队
        actionQueue.enqueue(new MoveAction(randomDirection()));
    }
    Action action = actionQueue.poll();
    if (action != null) {
        action.execute(world, this.position);
    }
}
```

**阶段一的行为**：队列空时随机生成一个 MoveAction 入队 → 取出执行。撞墙返回 BLOCKED 时不做额外处理（下一帧队列可能为空，会再次随机选方向）。

#### 3.4 扩展点 2：EnemyBrain 策略注入（阶段二预备）

在类中预留 `EnemyBrain brain` 字段（现阶段为 null），后续阶段二的 `updateAI` 将变为：

```java
// 阶段二后的 updateAI 逻辑（Predicate 写法）
public void updateAI(TETile[][] world, Player player) {
    if (actionQueue.needRefill()) {
        if (brain != null) {
            // 用 brain 生成意图 → ClassicalPlanner 翻译为动作序列
            StrategicIntent intent = brain.think(buildSnapshot(world, player));
            List<MoveAction> actions = ClassicalPlanner.translate(intent, this.position, world);
            actionQueue.enqueueAll(actions);
        } else {
            // 回退：随机移动
            actionQueue.enqueue(new MoveAction(randomDirection()));
        }
    }
    Action action = actionQueue.poll();
    if (action != null) {
        action.execute(world, this.position);
    }
}
```

**多类型敌人的最终形态**：不同类型敌人 = 相同 Enemy 类 + 不同 Brain 实现 + 不同构造参数。完全通过组合实现多态，无需任何子类化。

#### 3.5 扩展点 3：getter 方法

```java
public int getHp() { return hp; }
public int getSightRange() { return sightRange; }
public ActionQueue getActionQueue() { return actionQueue; }  // 用于阶段五中断检测
```

---

### 总结：多类型敌人方案

| 差异维度 | 实现方式 |
|----------|----------|
| 外观（瓦片） | 构造函数参数 `TETile tile` |
| 生命值 | 构造函数参数 `int hp` |
| 视野范围 | 构造函数参数 `int sightRange` |
| AI 行为 | `EnemyBrain` 接口的不同实现（RuleBased、LLM、Random） |
| 特殊能力 | 通过自定义 Brain 实现（如 AmbushBrain、BossBrain） |

**零子类化**：不需要 `class BossEnemy extends Enemy`、`class PatrolEnemy extends Enemy` 等。所有类型敌人都是 `Enemy` 实例，差异通过组合注入。

---

## 假设与决策

1. **暂不修改 Direction.java**：MoveAction 中手动计算 `x + (direction == RIGHT ? 1 : ...)` ，等步骤 1.2 再统一添加 dx/dy。
2. **Action 接口签名使用 `TETile[][]`**：阶段一用 `TETile[][]` 而非 `GameStateSnapshot`，避免"还没造的轮子阻碍当前进度"（构建指南 1.3 的设计思想）。
3. **Random 由外部注入**：保证与世界生成、玩家放置的随机种子体系一致。
4. **阶段一用纯随机移动**：目的是先打通 Enemy 的集成链路（渲染、AI tick、碰撞、存档），不引入复杂 AI。

---

## 验证步骤

1. 编译通过，`Action`/`MoveAction`/`ActionQueue`/`Enemy` 四个类无语法错误
2. 单独测试 MoveAction：创建 → execute → 验证 position 正确变化 / 撞墙被阻挡
3. 单独测试 ActionQueue：入队 5 个动作 → 逐一 poll → 验证顺序正确
4. 单独测试 Enemy：创建敌人 → 多次调用 `updateAI(world)` → 验证位置在合法范围内随机变化
5. 集成后验证：敌人不穿墙、不走出地图
