# Step 1.3 实现计划：适配 Enemy 构造方法

## 目标

修改 `Enemy` 类的构造方法，增加 `String agentId` 参数，并更新所有调用方。同时添加 `perceptionEnabled` 开关和 `observationSeq` 字段，确保 Phase 0 兼容性（`perceptionEnabled` 默认 `false`）。

## 当前状态分析

### 已完成的前置工作
- Step 1.1：`VisibleEntity`、`HeardEvent`、`ObservationEnvelope` 已创建
- Step 1.2：`PerceptionSystem` 已实现
- `Entity.java` 已有 `agentId` 字段（第 18 行）和 getter/setter（第 45-51 行）

### 需要修改的文件

| 文件 | 修改内容 |
|------|----------|
| `byog/Entity/Enemy.java` | 修改构造方法签名，增加 agentId 参数；添加 perceptionEnabled 和 observationSeq 字段 |
| `byog/Test/Phase0EncounterHarness.java` | 更新 Enemy 构造调用，传入 "guard-a" / "guard-b" |
| `byog/Entity/Enemy.java` (spawnEnemies) | 更新 Enemy 构造调用，传入 "enemy-" + i |
| `byog/Test/EnemyCollisionTest.java` | 更新 Enemy 构造调用，传入测试用 agentId |
| `byog/Core/Game.java` | 更新 Enemy 构造调用（读档时），传入 agentId |

### 当前 Enemy 构造方法签名

```java
public Enemy(Position position, TETile tile, int hp, int sightRange,
             int moveInterval, int attackDamage, int damageVariance, Random random)
```

## 实施步骤

### 步骤 1：修改 Enemy.java

**目标**：更新构造方法，添加新字段和访问方法

**具体改动**：

1. **添加新字段**（类顶部现有字段旁边）：
   ```java
   private boolean perceptionEnabled = false;
   private String agentId;
   private long observationSeq = 0;
   ```

2. **修改构造方法签名**（增加最后一个参数 `String agentId`）：
   ```java
   public Enemy(Position position, TETile tile, int hp, int sightRange,
                int moveInterval, int attackDamage, int damageVariance,
                Random random, String agentId) {
       super(position, tile);
       this.hp = hp;
       this.sightRange = sightRange;
       this.moveInterval = moveInterval;
       this.attackDamage = attackDamage;
       this.damageVariance = damageVariance;
       this.tickCounter = 0;
       this.random = random;
       this.actionQueue = new ActionQueue();
       this.brain = new RuleBasedBrain(sightRange, random);
       this.agentId = agentId;
       this.perceptionEnabled = false;
       this.observationSeq = 0;
   }
   ```

3. **添加访问方法**：
   ```java
   public void setPerceptionEnabled(boolean enabled) { this.perceptionEnabled = enabled; }
   public boolean isPerceptionEnabled() { return perceptionEnabled; }
   public String getAgentId() { return agentId; }
   public long getAndIncrementObservationSeq() { return observationSeq++; }
   ```

### 步骤 2：更新 Phase0EncounterHarness.java

**目标**：在创建 guardA 和 guardB 时传入 agentId

**具体改动**：

修改 `fromAscii` 方法中创建 Enemy 的两行（约第 168-169 行）：

```java
Enemy guardA = new Enemy(aPos, Tileset.ENEMY, 20, 7, 1, 1, 0, new Random(guardASeed), "guard-a");
Enemy guardB = new Enemy(bPos, Tileset.ENEMY, 20, 7, 1, 1, 0, new Random(guardBSeed), "guard-b");
```

### 步骤 3：更新 Enemy.spawnEnemies()

**目标**：在生成敌人时传入 agentId

**具体改动**：

修改 `spawnEnemies` 方法中创建 Enemy 的行（约第 201-203 行）：

```java
Enemy enemy = new Enemy(new Position(0, 0), Tileset.ENEMY,
        config.enemyHp, config.enemySightRange, config.enemyMoveInterval,
        config.enemyAttack, config.enemyDamageVariance, random, "enemy-" + i);
```

### 步骤 4：更新 EnemyCollisionTest.java

**目标**：在测试中传入 agentId

**具体改动**：

修改创建 Enemy 的两行（第 30-31 行）：

```java
Enemy e1 = new Enemy(new Position(2, 1), Tileset.ENEMY, 20, 7, 5, 10, 3, new Random(42), "test-enemy-1");
Enemy e2 = new Enemy(new Position(1, 2), Tileset.ENEMY, 20, 7, 5, 10, 3, new Random(99), "test-enemy-2");
```

### 步骤 5：更新 Game.java

**目标**：在读档时传入 agentId

**具体改动**：

修改读档部分创建 Enemy 的行（第 721-722 行）：

```java
Enemy enemy = new Enemy(new Position(s.x, s.y), Tileset.ENEMY,
        s.hp, s.sightRange, mvInterval, atk, atkVariance, random, s.agentId);
```

> **注意**：需要检查 GameSaveData 是否已经保存了 agentId 字段。如果还没有，需要先更新 GameSaveData（这是 Step 1.9 的内容）。作为临时解决方案，可以使用 `"entity-" + s.id` 作为 agentId。

## 验证

### 编译验证

```powershell
$javaSources = Get-ChildItem byog -Recurse -Filter *.java | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -cp "..\library-sp18\javalib\*" -d out $javaSources
```

### 测试验证

```powershell
java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" org.junit.runner.JUnitCore byog.Test.Phase0TestSuite
```

**预期结果**：Phase 0 全部 19 个测试绿色（因为 `perceptionEnabled` 默认 `false`，行为不变）。

## 风险与注意事项

1. **遗漏调用方**：所有 `new Enemy(...)` 的地方都必须更新。编译失败会直接提示遗漏的位置。
2. **GameSaveData 兼容性**：读档时需要确保 agentId 能正确恢复。如果 GameSaveData 还没有 agentId 字段，需要先处理。
3. **行为变化**：确保 `perceptionEnabled` 默认 `false`，否则会改变 Phase 0 测试的行为。

## 产出

- 修改后的 `Enemy.java`（新构造方法 + 新字段 + 访问方法）
- 更新后的所有调用方（Phase0EncounterHarness、Enemy.spawnEnemies、EnemyCollisionTest、Game.java）
- 编译通过
- Phase 0 测试绿色