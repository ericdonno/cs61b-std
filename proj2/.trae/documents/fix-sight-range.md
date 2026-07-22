# 视距从 Enemy 传入 Brain

## 问题

`RuleBasedBrain` 硬编码 `SIGHT_RANGE = 7`，而 Enemy 已有 `sightRange` 字段。两者必须一致才能正确工作，目前靠巧合相同。应该让 Enemy 将 `sightRange` 传入 Brain。

## 变更

### 1. [RuleBasedBrain.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/RuleBasedBrain.java)

- 删除 `private static final int SIGHT_RANGE = 7;`
- 新增 `private int sightRange;` 实例字段
- 新增构造器 `RuleBasedBrain(int sightRange)`
- L25：`dist <= SIGHT_RANGE * 2` → `dist <= sightRange`

### 2. [Enemy.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Enemy.java)

- L33：`this.brain = new RuleBasedBrain();` → `this.brain = new RuleBasedBrain(sightRange);`

## 验证

编译零错误。游戏中敌人使用 Enemy 构造器传入的 `sightRange`（默认 7）判定视野。
