# 战斗系统设计方案

## 一、现有架构分析

### 1.1 实体系统

* **Entity**：基类，包含 `position`、`tile`、`alive` 状态和 `die()` 方法

* **Player**：继承 Entity，有 `hp=100`、`sightRange=10`，支持 WASD 移动

* **Enemy**：继承 Entity，有 `hp=10`、`sightRange=7`、AI 系统（Brain → Planner → ActionQueue）

### 1.2 动作系统

* **Action** 接口：定义 `execute()` 方法，返回 `ActionResult`（SUCCESS/BLOCKED/INTERRUPTED/COMPLETED）

* **MoveAction**：实现移动逻辑，通过 `EntityManager.canMoveTo()` 检测碰撞

### 1.3 碰撞检测

* **EntityManager**：唯一碰撞检测入口，维护 `positionIndex` 空间索引

* 延迟更新模式：AI tick 只修改 `position`，帧末 `flushPendingChanges()` 统一重建索引

### 1.4 AI 决策链

```
Enemy.updateAI() → GameStateSnapshot → Brain.think() → StrategicIntent → Planner.translate() → ActionQueue
```

### 1.5 缺失的战斗元素

* 没有攻击动作（AttackAction）

* 没有伤害计算机制

* 玩家无法主动攻击

* 敌人只会移动，不会攻击

* UI 没有 HP 显示和战斗反馈

***

## 二、战斗系统设计

### 2.1 核心设计原则

| 原则       | 说明                            |
| -------- | ----------------------------- |
| **模块化**  | 战斗逻辑独立成 Action，不耦合到 Entity 内部 |
| **回合制**  | 玩家行动与敌人行动分离，避免同帧互相攻击          |
| **近距战斗** | 攻击需要相邻（上下左右），符合 Roguelike 传统  |
| **确定性**  | 伤害计算可配置，支持种子确定性               |
| **可扩展**  | 预留暴击、护甲等扩展点                   |

### 2.2 战斗流程

```
┌─────────────────────────────────────────────────────────────┐
│                     Player Turn                             │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐    │
│  │  WASD移动   │ or │  F键攻击    │ or │  其他操作   │    │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘    │
│         │                  │                  │            │
│         ▼                  ▼                  ▼            │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              EntityManager 碰撞检测                 │   │
│  └─────────────────────────────────────────────────────┘   │
│         │                  │                               │
│         ▼                  ▼                               │
│  ┌─────────────┐    ┌─────────────┐                        │
│  │ 移动成功    │    │ 攻击成功    │                        │
│  │ 更新位置    │    │ 计算伤害    │                        │
│  └─────────────┘    │ 目标受伤    │                        │
│                     │ 死亡检测    │                        │
│                     └─────────────┘                        │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     Enemy AI Turn                           │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Brain 决策：检测玩家距离 → 决定 Chase/Attack          │    │
│  └─────────────────────────────────────────────────────┘    │
│         │                                                   │
│         ▼                                                   │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Planner：生成 MoveAction 或 AttackAction            │    │
│  └─────────────────────────────────────────────────────┘    │
│         │                                                   │
│         ▼                                                   │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ ActionQueue：执行动作 → 移动或攻击                   │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    flushPendingChanges()
                    removeDeadEntities()
```

### 2.3 伤害计算公式

```
伤害 = baseDamage + random(0, variance)
HP = max(0, HP - 伤害)
```

* **Player**：基础伤害 15，方差 5（15-20）

* **Enemy**：基础伤害 10，方差 3（10-13）

***

## 三、代码修改计划

### 3.1 新增文件

| 文件                            | 说明                 |
| ----------------------------- | ------------------ |
| `byog/Core/AttackAction.java` | 攻击动作实现，检测相邻目标并造成伤害 |

### 3.2 修改文件

| 文件                                | 修改内容                             |
| --------------------------------- | -------------------------------- |
| `byog/Core/Action.java`           | 添加 `DAMAGE` ActionResult         |
| `byog/Core/StrategicIntent.java`  | 添加 `ATTACK` Strategy             |
| `byog/Core/Player.java`           | 添加 `attack()` 方法和战斗属性            |
| `byog/Core/Enemy.java`            | 添加 `attack()` 方法和战斗属性            |
| `byog/Core/EntityManager.java`    | 添加 `findAdjacentEntity()` 查找相邻目标 |
| `byog/Core/ClassicalPlanner.java` | 支持生成 AttackAction                |
| `byog/Core/RuleBasedBrain.java`   | 检测相邻玩家并返回 ATTACK 策略              |
| `byog/Core/Game.java`             | 添加攻击键绑定、HP UI、死亡判定               |

### 3.3 详细设计

#### 3.3.1 AttackAction.java

```java
public class AttackAction implements Action {
    private EntityManager entityMgr;
    private Direction direction;  // 攻击方向，null 表示自动检测
    
    @Override
    public ActionResult execute(TETile[][] world, Entity entity) {
        // 1. 查找相邻目标
        Entity target = entityMgr.findAdjacentEntity(entity, direction);
        if (target == null) {
            return ActionResult.BLOCKED;
        }
        // 2. 造成伤害
        int damage = calculateDamage(entity);
        dealDamage(target, damage);
        return ActionResult.DAMAGE;
    }
}
```

#### 3.3.2 Player 修改

```java
public class Player extends Entity {
    private int attackDamage = 15;
    private int damageVariance = 5;
    
    /** 向指定方向攻击相邻敌人 */
    public void attack(Direction direction, EntityManager entityMgr) {
        AttackAction action = new AttackAction(entityMgr, direction);
        action.execute(world, this);
    }
}
```

#### 3.3.3 Enemy 修改

```java
public class Enemy extends Entity {
    private int attackDamage = 10;
    private int damageVariance = 3;
    
    /** 更新 AI：检测玩家距离，近则攻击 */
    public void updateAI(TETile[][] world, EntityManager entityMgr, Player player) {
        // ... 现有逻辑
        // 新增：相邻时执行攻击动作
    }
}
```

#### 3.3.4 EntityManager 修改

```java
public class EntityManager {
    /** 查找指定方向上相邻的实体（优先敌对目标） */
    public Entity findAdjacentEntity(Entity attacker, Direction direction) {
        Position targetPos = attacker.getPosition().offset(direction);
        return positionIndex.get(targetPos);
    }
}
```

#### 3.3.5 Game 修改

```java
public class Game {
    // 键位绑定新增
    keyBindings.put('f', () -> attackPlayer(Direction.UP));
    
    // UI 绘制新增 HP 显示
    private void drawUIBar(boolean isPaused) {
        // ... 现有绘制
        // 新增：绘制玩家 HP 和敌人数量
        StdDraw.text(5, barY, String.format("HP: %d/%d", player.getHp(), 100));
        StdDraw.text(20, barY, String.format("Enemies: %d", enemyCount));
    }
    
    // 玩家死亡判定
    private void checkPlayerDeath() {
        if (player.getHp() <= 0) {
            player.die();
            // 游戏结束逻辑
        }
    }
}
```

***

## 四、键位设计

| 按键      | 功能         |
| ------- | ---------- |
| W/A/S/D | 移动         |
| F       | 攻击（面向当前方向） |
| : + Q   | 保存退出       |
| P       | 暂停         |

***

## 五、风险与注意事项

### 5.1 边界情况

| 场景        | 处理方式             |
| --------- | ---------------- |
| 玩家攻击空格子   | 返回 BLOCKED，记录日志  |
| 敌人攻击空格子   | AI 重新规划，不浪费行动    |
| 玩家和敌人同时攻击 | 玩家先行动，敌人后行动（回合制） |
| HP 负数     | clamp 到 0，触发死亡   |

### 5.2 性能考虑

* 攻击检测只需检查 4 个相邻格子，O(1) 复杂度

* 伤害计算为简单算术运算，无性能影响

### 5.3 存档兼容性

* 战斗属性（attackDamage, damageVariance）需要加入 `EntityState` 序列化

* 现有 `entityStates` 字段已支持扩展，兼容旧存档

***

## 六、实施步骤

1. **Step 1**：新增 `AttackAction.java`
2. **Step 2**：修改 `Action.java` 添加 DAMAGE 结果
3. **Step 3**：修改 `StrategicIntent.java` 添加 ATTACK 策略
4. **Step 4**：修改 `EntityManager.java` 添加 findAdjacentEntity
5. **Step 5**：修改 `Player.java` 和 `Enemy.java` 添加战斗属性
6. **Step 6**：修改 `ClassicalPlanner.java` 支持攻击动作
7. **Step 7**：修改 `RuleBasedBrain.java` 添加攻击判定
8. **Step 8**：修改 `Game.java` 添加攻击键绑定和 UI
9. **Step 9**：编译测试

