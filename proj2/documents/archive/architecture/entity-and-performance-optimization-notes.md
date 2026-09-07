# 优化方案记录

## 概述

本文档记录了项目中所有推荐的优化方案，包含已实现和待实现的优化项。新提出来的优化方案也会添加到这里。

---

## 已实现的优化

### ✅ 优化 1：统一实体容器 + 碰撞检测 O(1)

**问题**: 原有三层冗余结构：
- `List<Entity> entities` — 渲染遍历
- `List<Enemy> enemies` — AI tick 遍历
- 后来加的 `Map<Position, Entity> entityPositions` — 碰撞检测

三者包含相同实体，维护成本高，且碰撞检测依赖 O(n) 线性扫描。

**实现方案**: 只保留 `Map<Position, Entity> entityPositions` 作为唯一实体容器。
- `entityPositions.values()` 用于所有遍历（AI tick、渲染、死实体清理）
- `entityPositions.get(p)` O(1) 碰撞检测
- `instanceof Enemy` 过滤 AI 目标

**改动文件**: [Game.java](../../../byog/Core/Game.java)、[Position.java](../../../byog/lab5/Position.java)（添加 `equals`/`hashCode`）

**代码变更**:
```java
// Game.java — 唯一实体容器
private Map<Position, Entity> entityPositions;

// 添加实体
private void addEntity(Entity e) {
    entityPositions.put(e.getPosition(), e);
}

// 移除实体
private void removeEntity(Entity e) {
    entityPositions.remove(e.getPosition());
}

// AI tick — values() 遍历 + instanceof 过滤，位置变更延迟到帧末
for (Entity e : entityMgr.getAllEntities()) {
    if (e instanceof Enemy enemy && e.isAlive()) {
        enemy.updateAI(world, entityMgr);
    }
}
entityMgr.flushPendingChanges();

// EntityManager.flushPendingChanges() — 帧末统一重建索引
public void flushPendingChanges() {
    Map<Position, Entity> newIndex = new HashMap<>();
    for (Entity e : positionIndex.values()) {
        newIndex.put(e.getPosition(), e);
    }
    positionIndex.clear();
    positionIndex.putAll(newIndex);
    for (Entity e : pendingAdd) {
        positionIndex.put(e.getPosition(), e);
    }
    pendingAdd.clear();
}

// 碰撞检测 O(1)
private boolean isPlayerColliding(Position p) {
    if (!Player.canMoveTo(p, world)) {
        return true;
    }
    Entity e = entityPositions.get(p);
    return e != null && e.isAlive();
}

// 死实体清理
private void removeDeadEntities() {
    List<Position> toRemove = new ArrayList<>();
    for (Entity e : entityPositions.values()) {
        if (!e.isAlive()) {
            toRemove.add(e.getPosition());
        }
    }
    for (Position p : toRemove) {
        entityPositions.remove(p);
    }
}

// 玩家移动后同步索引
private void movePlayer(Player player, Direction direction) {
    Position oldPos = player.getPosition();
    player.move(direction, this::isPlayerColliding);
    Position newPos = player.getPosition();
    if (!oldPos.equals(newPos)) {
        entityPositions.remove(oldPos);
        entityPositions.put(newPos, player);
    }
}
```

**收益**:
- 消除三层冗余，单一数据源，零不一致风险
- 碰撞检测从 O(n) 降为 O(1)
- 新增实体类型只需 `addEntity()` 一行

---

### ✅ 优化 2：Entity 添加 `isAlive` 标志

**问题**: 没有机制标记实体为"已死亡但尚未从列表移除"。

**实现方案**: 在 `Entity` 中添加 `alive` 字段和相关方法。

**改动文件**: [Entity.java](../../../byog/Entity/Entity.java)

**代码变更**:
```java
// Entity.java
protected boolean alive = true;

public boolean isAlive() { return alive; }

/** 标记实体为死亡状态，待下一帧清理 */
public void die() { this.alive = false; }
```

**收益**: 安全的延迟删除机制，避免遍历期间并发修改异常。

---

### ✅ 优化 3：延迟增删队列

**问题**: 主循环遍历 `entityPositions.values()` 时，`updateAI()` 导致 Entity 位置变更，
直接 `remove`/`put` 会抛出 `ConcurrentModificationException`。此外，将来 AI 可能在帧中
需要生成新实体（如敌人分裂、掉落物），同样不能在遍历期间直接修改集合。

完整调用链：

```
Game.playWithKeyboard() 第56行
  │
  ├─ for (Entity e : entityMgr.getAllEntities())  ← 创建 Iterator
  │       │
  │       └─ EntityManager.getAllEntities()
  │              └─ return positionIndex.values()  ← 返回 HashMap 视图，不是副本！
  │
  └─ enemy.updateAI(world, entityMgr)  第58行
          │
          └─ MoveAction.execute()
                  │
                  └─ entityMgr.updatePosition(entity, oldPos, newPos)  ← 直接修改 HashMap！
                          ├─ positionIndex.remove(oldPos)
                          └─ positionIndex.put(newPos, e)
```



**实现方案**: "收集→统一应用"模式——AI tick 中只更新 Entity 自身状态，帧末调用
`EntityManager.flushPendingChanges()` 根据 Entity 最新 position 重建位置索引。新增 `pendingAdd` 列表
供将来 AI 请求添加实体。

```java
// EntityManager — 帧末统一重建索引
public void flushPendingChanges() {
    Map<Position, Entity> newIndex = new HashMap<>();
    for (Entity e : positionIndex.values()) {
        newIndex.put(e.getPosition(), e);
    }
    positionIndex.clear();
    positionIndex.putAll(newIndex);

    for (Entity e : pendingAdd) {
        positionIndex.put(e.getPosition(), e);
    }
    pendingAdd.clear();
}

// Game.java AI tick — 调用 EntityManager
for (Entity e : entityMgr.getAllEntities()) {
    if (e instanceof Enemy enemy && e.isAlive()) {
        enemy.updateAI(world, entityMgr);  // 只改 entity.position，不移调索引
    }
}
entityMgr.flushPendingChanges();  // 帧末统一重建
```

**改动文件**: [Game.java](../../../byog/Core/Game.java)、[EntityManager.java](../../../byog/Entity/EntityManager.java)

**收益**: 彻底避免并发修改异常，任意时刻安全地增删/移动实体。

---

**⚠️ 重构历史问题记录**

2026-07-12 在重构到 `EntityManager` 时，擅自将 `MoveAction.execute()` 中的位置更新从"延迟更新"改为"即时更新"，引入了重大逻辑不一致：

```java
// ❌ 错误做法（已修复）— 遍历期间直接修改 HashMap，触发 CME
// MoveAction.execute()
if (entityMgr.canMoveTo(entity, newPos, world)) {
    entity.setPosition(newPos);
    entityMgr.updatePosition(entity, oldPos, newPos);  // 直接修改索引！
}

// Game.java AI tick — 遍历视图，期间被修改
for (Entity e : entityMgr.getAllEntities()) {  // 返回 HashMap.values() 视图
    enemy.updateAI(world, entityMgr);           // 内部调用 MoveAction，修改 HashMap
}                                              // next() 检测到 modCount 变化 → CME
```

**根因**: `getAllEntities()` 返回的是 `positionIndex.values()` 视图（非快照），遍历期间任何 `remove()`/`put()` 都会导致 `modCount != expectedModCount`，触发 `ConcurrentModificationException`。

**修复**: 恢复延迟更新模式——所有写入路径（`MoveAction`、`Player.move`）只修改 Entity 对象的 `position` 字段，不碰 HashMap；帧末统一调用 `flushPendingChanges()` 重建索引。

**教训**: 
1. 重构时必须保持原有架构契约一致，不能混用即时更新和延迟更新
2. 引入新方法前必须检查是否破坏现有语义（如 `updatePosition()` 破坏了延迟更新契约）
3. 改动后必须验证遍历期间的修改安全性

---

### ✅ 优化 4：存档持久化实体状态

**问题**: 当前 `saveGameState()` 只保存 `seed` + `playerX/Y`，世界和敌人靠 seed 确定性重建。
一旦实体被 `die()` 标记删除，读档后从 seed 重建会复活所有敌人。此外 Player 的 hp/sightRange 也无法持久化。

**实现方案**: 新增 `EntityState` 快照类，存档时将所有实体（position、alive、hp、sightRange）序列化到
`extraData.entityStates`。读档时优先从此字段恢复，旧存档无此字段时 fallback 到 seed 重建。

```java
// saveGameState — 遍历 entityPositions 写出所有实体
List<EntityState> states = new ArrayList<>();
for (Entity e : entityPositions.values()) {
    EntityState s = new EntityState();
    s.x = e.getPosition().x;
    s.y = e.getPosition().y;
    s.alive = e.isAlive();
    if (e instanceof Player pl) { s.type = "Player"; s.hp = pl.getHp(); ... }
    else if (e instanceof Enemy enemy) { s.type = "Enemy"; s.hp = enemy.getHp(); ... }
    states.add(s);
}
data.extraData.put("entityStates", (Serializable) states);

// loadGameState — 从 entityStates 恢复，alive=false 则调 die()
for (EntityState s : states) {
    Entity e = createFromState(s, data.seed);
    if (!s.alive) e.die();
    addEntity(e);
}
```

**改动文件**: [EntityState.java](../../../byog/Entity/EntityState.java)（新建）、[Player.java](../../../byog/Entity/Player.java)（添加 hp/sightRange）、[Game.java](../../../byog/Core/Game.java)（save/load）

**收益**: 实体增删状态在存档间保持正确。

---

## 待实现的优化

### 📋 优化 5：Entity 添加唯一标识 (ID)

**问题**: 无 ID 字段，存档中无法追踪"哪个敌人被杀了"，只能用坐标隐式判断。

**推荐方案**: 在 `Entity` 中添加自增 ID：

```java
private static int nextId = 0;
protected final int id = nextId++;

public int getId() { return id; }
```

**改动文件**: [Entity.java](../../../byog/Entity/Entity.java)

**收益**: 精准追踪实体身份，使存档/网络同步/日志调试更可靠。

---

### 📋 优化 6：flushPendingChanges 全量重建 → 增量更新

**问题**: 当前 `flushPendingChanges()` 每帧都遍历全量实体、创建新 HashMap、逐条 put 重建索引，
复杂度 O(n)。即使没有任何实体移动也要执行，纯浪费。当实体数量增多时（几百个），每帧分配新 HashMap
的 GC 压力不可忽视。

**推荐方案**: AI tick 中收集本帧实际移动的实体，`flushPendingChanges()` 只对移动的实体做增量
`remove` + `put`。无移动时零开销。

```java
// AI tick — 只收集，不修改 entityPositions
if (currentState == GameState.PLAYING) {
    List<MoveRecord> moves = new ArrayList<>();
    for (Entity e : entityPositions.values()) {
        if (e instanceof Enemy enemy && e.isAlive()) {
            Position oldPos = enemy.getPosition();
            enemy.updateAI(world);
            Position newPos = enemy.getPosition();
            if (!oldPos.equals(newPos)) {
                moves.add(new MoveRecord(oldPos, newPos, enemy));
            }
        }
    }
    flushPendingChanges(moves);
    removeDeadEntities();
}

// 新增内部类记录单次移动
private record MoveRecord(Position oldPos, Position newPos, Entity entity) {}

// 只对真正移动的实体做增量更新
private void flushPendingChanges(List<MoveRecord> moves) {
    for (MoveRecord m : moves) {
        entityPositions.remove(m.oldPos());
        entityPositions.put(m.newPos(), m.entity());
    }
    for (Entity e : pendingAdd) {
        entityPositions.put(e.getPosition(), e);
    }
    pendingAdd.clear();
}
```

**改动文件**: [Game.java](../../../byog/Core/Game.java)

**收益**:
 - 无移动时 O(1)，完全不分配额外内存
 - 有 m 个移动时 O(m)，远优于全量 O(n)
 - 消除每帧 new HashMap 的 GC 压力

| 方案 | 无移动 | 有 m 个移动 |
|------|--------|------------|
| 当前（全量重建） | O(n) + new HashMap | O(n) + new HashMap |
| 优化版（增量更新） | O(1) | O(m) |

---

### 📋 优化 7：PerceptionSystem FOV 循环范围优化

**问题**: 当前 `PerceptionSystem.computeObservation()` 中的 FOV 计算遍历了整个 `world` 数组（O(W×H)），
内部通过 `manhattanDistance <= sightRange` 过滤。sightRange 通常只有 7，但需要检查世界中的每一个 tile。

```java
// ❌ 当前：遍历整个世界，靠距离过滤
for (int x = 0; x < world.length; x++) {
    for (int y = 0; y < world[0].length; y++) {
        int dist = Math.abs(x - selfX) + Math.abs(y - selfY);
        if (dist <= sightRange) {
            if (hasLineOfSight(world, selfX, selfY, x, y)) {
                visibleMask[x][y] = true;
            }
        }
    }
}
```

sightRange=7 时曼哈顿菱形区域只有约 113 个 tile（`2R² + 2R + 1`），
但当前 50×30 的世界需要迭代 1500 次，其中 92% 被距离检查过滤掉。

**推荐方案**: 直接迭代 sightRange 菱形区域内的坐标，避免遍历世界：

```java
// ✅ 优化：只遍历 sightRange 菱形区域（约 R² 个 tile）
int maxX = world.length;
int maxY = world[0].length;

for (int dx = -sightRange; dx <= sightRange; dx++) {
    int maxDy = sightRange - Math.abs(dx);
    for (int dy = -maxDy; dy <= maxDy; dy++) {
        int x = selfX + dx;
        int y = selfY + dy;
        if (x < 0 || x >= maxX || y < 0 || y >= maxY) {
            continue;
        }
        if (hasLineOfSight(world, selfX, selfY, x, y)) {
            visibleMask[x][y] = true;
        }
    }
}
```

同样，第二步"收集可见实体"的双重循环也应改为只遍历 FOV 内的区域。

**改动文件**: [PerceptionSystem.java](../../../byog/Perception/PerceptionSystem.java)

**收益**:

| sightRange | 菱形 tile 数 | 50×30 世界 | 节省 |
|------------|-------------|-----------|------|
| 5 | 61 | 1500 | 96% |
| 7 | 113 | 1500 | 92% |
| 10 | 221 | 1500 | 85% |

- 迭代次数从 O(W×H) 降为 O(R²)，每个敌人每 tick 的计算量大幅减少
- 无需在循环内计算曼哈顿距离（距离由 dx/dy 直接确定）

**注意**: 菱形遍历会改变 tile 的访问顺序（从行优先变为菱形扩散）。这**不改变** `visibleMask` 的结果——每个 tile 的判断仍然是独立的 `hasLineOfSight` 调用。所有现有 LOS 测试应保持绿色。

**当前世界尺寸下此优化不是必需的**（1500 次迭代仍然很快），但如果世界扩大或敌人数量增加，节省会很明显。建议在引入大量敌人（10+）之前实施。

---
## 实施优先级建议

| 优先级 | 优化项 | 状态 | 理由 |
|--------|--------|------|------|
| **P0（必须）** | 优化 2: isAlive 标志 | ✅ 已实现 | 实体删除的基础前提 |
| **P0（必须）** | 优化 3: 延迟增删队列 | ✅ 已实现 | 防止运行时异常 |
| **P1（推荐）** | 优化 1: 统一容器+O(1)碰撞 | ✅ 已实现 | 单数据源，零冗余 |
| **P1（推荐）** | 优化 4: 存档持久化 | ✅ 已实现 | 实体状态跨存档持久化 |
| **P2（可选）** | 优化 5: Entity ID | 📋 待实现 | 配合优化 4 使用，增加确定性 |
| **P2（可选）** | 优化 6: 增量更新索引 | 📋 待实现 | 实体少时无关紧要，数量增长后有意义 |
| **P2（可选）** | 优化 7: FOV 菱形遍历 | 📋 待实现 | 当前世界尺寸下性能足够，敌人/世界变大后再实施 |

---

## 架构演进总结

```
优化前:
  List<Entity> entities ──── 渲染遍历
  List<Enemy> enemies  ──── AI tick
  碰撞检测 O(n) ─────────── 遍历 entities

优化后:
  EntityManager ──── 唯一实体管理入口
    ├── positionIndex (Map) → 空间索引
    │     ├── values()     → AI tick + 渲染
    │     └── get(p)       → 碰撞检测 O(1)
    ├── flushPendingChanges() → 帧末重建索引 + 添加新实体
    └── removeDeadEntities()  → 清理已死亡实体
```

## 兼容性说明

所有优化均属于架构增量改进，不破坏现有行为：
- 不影响渲染引擎、世界生成器、动作系统
- 现有测试用例 `playWithInputString` 结果不变
- 已实现部分编译通过（javac 零错误）

---

## 补充：方案 B — World + CollisionDetector 分离（未来大规模选项）

### 当前方案A：EntityManager（已实现）

`EntityManager` 同时承担实体 CRUD 和碰撞检测，命名为 `EntityManager` 已诚实反映其职责。适合当前小规模项目。

### 方案B：拆为 World + CollisionDetector

当实体系统增长到多个子系统（AI 索敌、视野遮蔽、阵营判定、战斗）时，EntityManager 会膨胀为上帝对象。

**架构：**

```
              Game
            /   |   \
      World     AI     Combat     Render
     (实体数据)  System   System    System
        |        |        |
        |    sightQuery  damageQuery
        |        |        |
        +-- CollisionDetector
            (纯查询, 只读)
```

**World** — 纯数据层，只管位置索引：

```java
public class World {
    private Map<Position, Entity> index = new HashMap<>();

    public Entity getAt(Position p) { return index.get(p); }
    public Collection<Entity> getAll() { return index.values(); }
    public void add(Entity e) { index.put(e.getPosition(), e); }
    public void remove(Entity e) { index.remove(e.getPosition()); }
    public void moveTo(Entity e, Position oldPos, Position newPos) {
        index.remove(oldPos);
        index.put(newPos, e);
    }
    public void cleanupDead() { ... }
}
```

**CollisionDetector** — 只依赖 World 的只读接口：

```java
public class CollisionDetector {
    private final World world;

    public boolean canMoveTo(Entity entity, Position target, TETile[][] tiles) {
        if (!Entity.canMoveTo(target, tiles)) return false;
        Entity occupant = world.getAt(target);
        return occupant == null || occupant == entity || !occupant.isAlive();
    }
}
```

**调用路径：**

```
Player.move(dir, tiles, collisionDetector)
  → collisionDetector.canMoveTo(player, target, tiles)
    → world.getAt(target)  ← 只读查询

MoveAction.execute(tiles, entity)
  → collisionDetector.canMoveTo(entity, target, tiles)
  → world.moveTo(entity, oldPos, newPos)  ← 写操作
```

### 方案B vs 方案A 对比

| 维度 | 方案A: EntityManager | 方案B: World + Detector |
|---|---|---|
| 类数量 | 1 | 2 |
| 渲染依赖 | `entityMgr.getAllEntities()` | `world.getAll()` |
| 碰撞依赖 | `entityMgr.canMoveTo()` | `detector.canMoveTo()` |
| 新增 AI 寻路 | 加方法到 EntityManager | 新建 PathFinder，只读 World |
| 新增阵营系统 | 加字段到 EntityManager | 新建 FactionSystem，只读 World |
| 碰撞规则变更 | 改 EntityManager | 只改 CollisionDetector |
| 适合规模 | 小项目（≤10 实体类型） | 大项目（20+ 子系统） |

### 何时应切换到方案B

1. 实体系统超过 3 个不同"关心方向"（如碰撞 + 视野 + 战斗 + AI）
2. EntityManager 方法数超过 15 个
3. 需要独立测试碰撞规则而不依赖 EntityManager 全部状态
4. 需要为不同子系统提供不同"视角"（如 FogSystem 只能看到视野内实体，CollisionDetector 看全量）

### 当前决策

当前项目实体仅 ~6 个，子系统仅 AI 移动 + 碰撞，**保持方案A（EntityManager）**。当需要引入阵营判定、视野遮蔽、战斗伤害时，再升级为方案B。
