# Step 1.9 实现计划：存档扩展

## 目标

在 `GameSaveData` 中新增 `entityAgentIds` Map，实现存档时写入、读档时恢复 agentId。

## 当前状态分析

### 已有的 agentId 保存机制

`Game.java` 的 `saveGameState()` 和 `loadGameState()` 已经通过 `EntityState` 对象保存和恢复 `agentId`：

- **存档**（第 658 行）：`s.agentId = enemy.getAgentId()`
- **读档**（第 722 行）：`String agentId = (s.agentId != null) ? s.agentId : "entity-" + states.indexOf(s)`

### Spec 要求的新增字段

Spec Step 1.9 要求：
1. `GameSaveData` 新增 `entityAgentIds` Map（`Entity.id → agentId`）
2. 存档时写入这个 Map
3. 读档时从这个 Map 恢复 agentId

这是一个**额外的、独立的**存储方式，与现有 `EntityState.agentId` 并存。

## 实施步骤

### 步骤 1：GameSaveData 新增 entityAgentIds 字段

**文件**：`byog/IO/GameSaveData.java`

新增字段：
```java
public Map<Integer, String> entityAgentIds;
```

在构造方法中初始化：
```java
public GameSaveData() {
    extraData = new HashMap<>();
    entityAgentIds = new HashMap<>();
}
```

### 步骤 2：存档时写入 entityAgentIds

**文件**：`byog/Core/Game.java`，`saveGameState()` 方法

在循环遍历实体时，同时填充 `entityAgentIds`：
```java
for (Entity e : entityMgr.getAllEntities()) {
    // ... 现有 EntityState 创建逻辑 ...
    if (e instanceof Enemy enemy) {
        data.entityAgentIds.put(enemy.getId(), enemy.getAgentId());
    }
}
```

### 步骤 3：读档时恢复 agentId

**文件**：`byog/Core/Game.java`，`loadGameState()` 方法

恢复 Enemy 时，优先从 `entityAgentIds` 获取，兜底策略不变：
```java
String agentId = null;
if (data.entityAgentIds != null) {
    agentId = data.entityAgentIds.get(states.indexOf(s));
}
if (agentId == null) {
    agentId = (s.agentId != null) ? s.agentId : "entity-" + states.indexOf(s);
}
Enemy enemy = new Enemy(new Position(s.x, s.y), Tileset.ENEMY,
        s.hp, s.sightRange, mvInterval, atk, atkVariance, random, agentId);
```

### 步骤 4：验证

手动测试存读档后 agentId 不变。Phase 1 不为此写自动化测试（Phase 2 的确定性桥接测试会覆盖）。

## 涉及文件

| 文件 | 新建/修改 | 说明 |
|------|-----------|------|
| `byog/IO/GameSaveData.java` | **修改** | 新增 `entityAgentIds` 字段 |
| `byog/Core/Game.java` | **修改** | 存档时写入，读档时恢复 |

## 风险与注意事项

1. **版本兼容性**：旧存档没有 `entityAgentIds` 字段，读档时需要检查 `data.entityAgentIds != null`。兜底策略使用现有的 `EntityState.agentId`，再兜底到 `"entity-" + id`。

2. **Map key 的选择**：Spec 要求 `Entity.id → agentId`。由于读档时是通过 `states.indexOf(s)` 顺序恢复，这里用 `states.indexOf(s)` 作为 key 与读档时的索引一致。

3. **Player 的 agentId**：Player 没有 agentId 字段，所以只保存 Enemy 的映射。

## 验证步骤

1. 编译通过
2. Phase1TestSuite 全部 27 测试绿色（回归保证）
3. Phase0TestSuite 19 测试绿色
4. 手动存读档验证 agentId 不变（Phase 2 会有自动化测试覆盖）