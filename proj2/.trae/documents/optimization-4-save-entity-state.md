# 实现优化 4：存档持久化实体状态

## 问题

当前 `saveGameState()` 只保存 `seed` + `playerX/Y`，世界和敌人靠 seed 确定性重建。
一旦实体被 `die()` 标记删除，读档后从 seed 重建会复活所有敌人。

## 方案

将实体状态（position、alive、hp 等）序列化存入 `GameSaveData.extraData`。
读档时从存档数据恢复实体，而非从 seed 纯重建。

## 改动清单

### 1. 新增 `EntityState.java` — 实体快照 DTO

```java
// byog/Core/EntityState.java
package byog.Core;

import java.io.Serializable;

/** 实体的可序列化快照，用于存档 */
public class EntityState implements Serializable {
    private static final long serialVersionUID = 1L;

    public String type;    // "Player" | "Enemy"
    public int x, y;       // position
    public boolean alive;
    public int hp;
    public int sightRange;
}
```

### 2. [Game.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Game.java) — `saveGameState()`

```java
private void saveGameState() {
    Logger.section("Save Game");
    Logger.info("Saving game...");
    GameSaveData data = new GameSaveData();
    data.seed = this.seed;
    data.playerX = player.getPosition().x;
    data.playerY = player.getPosition().y;

    // 保存所有实体状态
    List<EntityState> states = new ArrayList<>();
    for (Entity e : entityPositions.values()) {
        EntityState s = new EntityState();
        s.x = e.getPosition().x;
        s.y = e.getPosition().y;
        s.alive = e.isAlive();
        if (e instanceof Player player) {
            s.type = "Player";
            s.hp = player.getHp();
            s.sightRange = player.getSightRange();
        } else if (e instanceof Enemy enemy) {
            s.type = "Enemy";
            s.hp = enemy.getHp();
            s.sightRange = enemy.getSightRange();
        }
        states.add(s);
    }
    data.extraData.put("entityStates", (Serializable) states);

    SaveLoadManager.save(data);
    Logger.info("Game saved successfully.");
}
```

### 3. [Game.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Game.java) — `loadGameState()`

```java
private boolean loadGameState() {
    if (!SaveLoadManager.saveExists()) {
        Logger.info("No save file found.");
        return false;
    }
    GameSaveData data = SaveLoadManager.load();
    if (data == null) {
        return false;
    }

    world = generateWorld(data.seed);
    entityPositions = new HashMap<>();

    // 从存档恢复实体
    @SuppressWarnings("unchecked")
    List<EntityState> states = (List<EntityState>) data.extraData.get("entityStates");
    if (states != null) {
        for (EntityState s : states) {
            Entity e;
            if ("Player".equals(s.type)) {
                player = new Player(new Position(s.x, s.y), s.hp, s.sightRange);
                e = player;
            } else if ("Enemy".equals(s.type)) {
                Random random = new Random(data.seed.hashCode());
                Enemy enemy = new Enemy(new Position(s.x, s.y), random, s.hp, s.sightRange);
                e = enemy;
            } else {
                continue;
            }
            if (!s.alive) {
                e.die();
            }
            addEntity(e);
        }
    }

    // 兼容旧存档（无 entityStates）：fallback 到旧逻辑
    if (entityPositions.isEmpty()) {
        player = spawnPlayerAt(data.playerX, data.playerY);
        addEntity(player);
        Enemy enemy = spawnEnemy(data.seed);
        addEntity(enemy);
    }

    Logger.info("Game loaded successfully.");
    return true;
}
```

### 4. [Enemy.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Enemy.java) — 新增不依赖 initEntity 的构造方法路径

需要新增一个接受 hp、sightRange 的构造方法，或者改造现有方法避免第二次 initEntity（位置已由存档指定）。现有的 `Enemy(Position, Random)` → `Enemy(Position, TETile, int, int, Random)` 已经接受所有参数，直接使用即可。

```java
// 现有构造方法已满足需求，读档时直接调用：
new Enemy(new Position(s.x, s.y), Tileset.ENEMY, s.hp, s.sightRange, random)
```

于是 `loadGameState()` 中 Enemy 创建改为使用该构造方法，需要传 Tileset.ENEMY。

### 5. 下载 Enemy 构造方法链确保兼容

`Enemy(Position, Random)` → `Enemy(Position, Tileset.ENEMY, 10, 7, Random)` →
`Enemy(Position, TETile, int, int, Random)` → `super(position, tile)`.

读档时直接调用 `new Enemy(position, Tileset.ENEMY, hp, sightRange, random)` 即可，
不再调用 `initEntity`（位置已确定）。

### 5. [Player.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Player.java) — 添加 hp、sightRange 字段

```java
public class Player extends Entity {
    private int hp;
    private int sightRange;

    public Player() {
        this(new Position(0, 0), 100, 10);
    }

    public Player(Position position) {
        this(position, 100, 10);
    }

    public Player(Position position, int hp, int sightRange) {
        super(position, Tileset.PLAYER);
        this.hp = hp;
        this.sightRange = sightRange;
    }

    public int getHp() { return hp; }
    public void setHp(int hp) { this.hp = hp; }
    public int getSightRange() { return sightRange; }
}
```

默认值 `hp=100, sightRange=10`，新游戏时使用默认构造方法，读档时使用四参数构造方法恢复存档值。

## 兼容性

- `extraData` 存 `entityStates` 是增量字段，旧存档无此 key 时 fallback 到旧逻辑
- `EntityState` 是新文件，新增不修改现有文件的行为
- `saveGameState()` 调用 `entityPositions.values()` 不在大遍历期间，不存在 CME

## 变更文件汇总

| 文件 | 操作 | 说明 |
|------|------|------|
| `byog/Core/EntityState.java` | **新建** | 实体快照 DTO |
| `byog/Core/Player.java` | 修改 | 添加 hp、sightRange 字段及构造方法 |
| `byog/Core/Game.java` | 修改 `saveGameState()` | 写入 entityStates（含 Player hp/sightRange） |
| `byog/Core/Game.java` | 修改 `loadGameState()` | 从 entityStates 恢复（含 Player hp/sightRange） |

## 验证

- 编译通过
- 新游戏 → 杀死敌人 → 存档 → 读档 → 敌人仍死亡
- 新游戏 → 直接存档读档 → 世界一致
- 旧存档（无 entityStates）→ 自动 fallback，不崩溃
