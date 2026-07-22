# Entity ID 实现与日志优化计划

## 需求分析

**目标：**

1. 实现 entity-optimization.md 中的优化5：为 Entity 添加唯一标识 (ID)
2. 决策层日志（Brain + Planner）带上 enemy ID，便于区分不同敌人
3. 移除 Enemy.java 中冗余的日志
4. 日志级别 debug → info

## 修改文件

| 文件                     | 修改内容                                            |
| ---------------------- | ----------------------------------------------- |
| Entity.java            | 添加静态 nextId 和实例 id 字段，添加 getId()                |
| GameStateSnapshot.java | 添加 enemyId 字段和 getter                           |
| Enemy.java             | 构建 snapsho时传入 this.getId()；移除 Logger 调用和 import |
| RuleBasedBrain.java    | debug → info，日志带 enemy ID                       |
| ClassicalPlanner.java  | debug → info，日志带 enemy ID                       |

## 实施步骤

1. **Entity.java** — 添加 ID

   ```
   private static int nextId = 0;
   protected final int id = nextId++;
   public int getId() { return id; }
   ```

2. **GameStateSnapshot.java** — 添加 enemyId

   * 构造参数加 `int enemyId`

   * 添加 `getEnemyId()` getter

3. **Enemy.java** — 传 ID + 移除日志

   * 构造 snapshot 时传入 `this.getId()`

   * 删除原有的 Logger 日志调用和 import

4. **RuleBasedBrain.java** — 日志改进

   * 从 snapshot 获取 enemyId

   * debug → info

   * 格式：`[INFO] Enemy#N RuleBasedBrain: dist=X <= sightRange=Y → CHASE`

5. **ClassicalPlanner.java** — 日志改进

   * 传入 enemyId（从 intent 或参数获取）

   * debug → info

   * 格式：`[INFO] Enemy#N ClassicalPlanner: pathLength=X, strategy=Y, actions=Z`

6. 编译验证

## 依赖考虑

* ClassicalPlanner.translate() 需要拿到 enemy ID。最轻量的方式：intent 已经携带了 targetPosition，再加 enemyId 到 intent 中，或者直接给 translate 加个 `int enemyId` 参数

* GameStateSnapshot 构造由 Enemy.java 创建，在那里有 `this.getId()`

## 预期效果

```
[INFO] Enemy#1 RuleBasedBrain: dist=3 <= sightRange=5 → CHASE
[INFO] Enemy#1 ClassicalPlanner: pathLength=8, strategy=CHASE, actions=8
[INFO] Enemy#2 RuleBasedBrain: dist=15 > sightRange=5 → PATROL
[INFO] Enemy#3 RuleBasedBrain: dist=1 <= sightRange=5 → CHASE
[INFO] Enemy#3 ClassicalPlanner: pathLength=2, strategy=CHASE, actions=2
```

每行带编号，谁做了什么一目了然。
