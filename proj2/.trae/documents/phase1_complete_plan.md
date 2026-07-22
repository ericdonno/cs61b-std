# 阶段一：基础敌人 - 完成计划

## 一、当前状态分析

根据构建指南的阶段一验收清单，逐项分析：

| 验收项                      | 状态 | 说明                                      |
| ------------------------ | -- | --------------------------------------- |
| 编译通过                     | ✅  | 当前代码结构完整                                |
| 红色 'E' 出现在地牢中（至少 3 个）    | ❌  | spawnEnemy 只生成 1 个敌人                    |
| 敌人随机移动，不穿墙               | ✅  | Enemy.updateAI + MoveAction 已实现         |
| 玩家无法走入敌人占据的瓦片            | ✅  | isPlayerColliding 通过 entityPositions 检查 |
| `:q` 保存后重新加载，敌人出现在保存时的位置 | ✅  | entityStates 序列化已包含敌人坐标/HP              |

**结论**：唯一缺失的是生成多个敌人（≥3 个）。其他四项已满足。

另外，构建指南 1.2 节要求 Direction 添加 dx/dy 字段和 fromDelta 方法，当前 Direction 只有枚举值。

## 二、需要修改的文件

### 1. Direction.java - 添加偏移量字段和 fromDelta 方法

**说明**：构建指南 1.2 节明确要求。

```java
UP(0, 1), DOWN(0, -1), LEFT(-1, 0), RIGHT(1, 0);

public final int dx;
public final int dy;

public static Direction fromDelta(int dx, int dy) { ... }
```

### 2. MoveAction.java - 简化方向计算

**说明**：当前用 switch-case 计算新坐标。改为使用 `direction.dx` / `direction.dy`。
同时改用 `Entity.canMoveTo` 替代 `Player.canMoveTo`。

### 3. Enemy.java - 添加 spawnEnemies 静态工厂方法

**说明**：用户明确要求不新建 EnemySpawner 类，将生成逻辑作为 Enemy 的静态方法。

```java
/**
 * 在世界中随机生成多个敌人。
 * @param world 游戏世界
 * @param seed 种子
 * @param playerPos 玩家位置，用于距离检查
 * @return 生成的敌人列表
 */
public static List<Enemy> spawnEnemies(TETile[][] world, String seed, Position playerPos) {
    List<Enemy> enemies = new ArrayList<>();
    Random countRandom = new Random((seed + "_enemy_count").hashCode());
    int count = 3 + countRandom.nextInt(3); // 3~5

    for (int i = 0; i < count; i++) {
        Random random = new Random((seed + "_enemy_" + i).hashCode());
        Enemy enemy = new Enemy(new Position(0, 0), random);
        Entity.initEntity(enemy, world, seed + "_pos_" + i);
        // 确保与玩家距离 ≥ 5
        while (distance(enemy.getPosition(), playerPos) < 5) {
            Entity.initEntity(enemy, world, seed + "_pos_" + i + "_retry");
        }
        enemies.add(enemy);
    }
    return enemies;
}
```

### 4. Game.java - 使用 Enemy.spawnEnemies 生成多个敌人

**改动位置**：`processInput` 的 SEED\_INPUT / 's' 分支（当前第 117-118 行）。

**当前代码**：

```java
Enemy enemy = spawnEnemy(this.seed);
addEntity(enemy);
```

**改为**：

```java
List<Enemy> enemies = Enemy.spawnEnemies(world, seed, player.getPosition());
for (Enemy e : enemies) {
    addEntity(e);
}
```

同时删除原有的 `spawnEnemy` 方法（第 390-395 行），因为不再需要。

## 三、文件修改清单

| 文件              | 修改内容                                                                    |
| --------------- | ----------------------------------------------------------------------- |
| Direction.java  | 添加 dx/dy 字段和 fromDelta 静态方法                                             |
| MoveAction.java | 用 Direction.dx/dy 替代 switch-case；用 Entity.canMoveTo 替代 Player.canMoveTo |
| Enemy.java      | 添加 spawnEnemies 静态方法（含距离检查）                                             |
| Game.java       | SEED\_INPUT 分支改为调用 Enemy.spawnEnemies；删除旧的 spawnEnemy 方法                |

## 四、不变的部分

| 文件/方法                                     | 原因                                         |
| ----------------------------------------- | ------------------------------------------ |
| Player.java getNewPosition 中的 switch-case | 构建指南 A.4 节明确要求保留，dx/dy 作为补充                |
| Game.java saveGameState / loadGameState   | 已通过 entityStates 正确处理敌人存档/读档               |
| Game.java isPlayerColliding               | 已通过 entityPositions 正确检查实体碰撞               |
| Game.java AI tick                         | 已遍历 entityPositions 中所有 Enemy 并调用 updateAI |

## 五、风险

1. **saveGameState/loadGameState 已正确实现**：敌人位置通过 entityStates 序列化保存/恢复，无需额外修改。
2. **Direction 兼容性**：Player.getNewPosition 中的 switch-case 保留不动，Direction.dx/dy 仅作为新增字段。
3. **playWithInputString 无 AI tick**：当前实现无 AI tick，测试用例只检查敌人存在于地图上，不影响验收。

