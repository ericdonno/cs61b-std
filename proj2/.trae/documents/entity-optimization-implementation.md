# 实体管理优化实施文档

## 概述

对实体添加/删除控制进行了两项架构优化：

1. **统一单列表**：移除 `enemies` 并行列表，改为 `entities` 单一列表 + `instanceof Enemy` 过滤
2. **`isAlive` 标志**：引入实体存活状态，支持安全的延迟删除

---

## 改动详情

### 改动 1：Entity.java — 添加 `alive` 字段

**文件**: `byog/Core/Entity.java`

**新增字段**:
```java
protected boolean alive = true;
```

**新增方法**:
```java
public boolean isAlive() { return alive; }

/** 标记实体为死亡状态，待下一帧清理 */
public void die() { this.alive = false; }
```

**说明**:
- `alive` 默认 `true`，新创建的实体自动处于存活状态
- 调用 `die()` 后实体立即停止参与 AI 运算和渲染，但不立刻从列表中删除
- 下一帧末尾由 `removeDeadEntities()` 统一清理，避免并发修改异常

---

### 改动 2：Game.java — 统一单列表 + 延迟清理

**文件**: `byog/Core/Game.java`

#### 2.1 移除并行列表

删除 `private List<Enemy> enemies` 字段及其所有初始化/添加调用。

#### 2.2 修改 AI tick 循环

**原来**:
```java
for (Enemy e : enemies) {
    e.updateAI(world);
}
```

**现在**:
```java
for (Entity e : entities) {
    if (e instanceof Enemy enemy && e.isAlive()) {
        enemy.updateAI(world);
    }
}
removeDeadEntities();
```

- 使用 Java 16 的 `instanceof` 模式匹配，一行完成类型判断 + 变量绑定
- `isAlive()` 过滤确保死实体不执行 AI
- 每帧末尾调用 `removeDeadEntities()` 清理

#### 2.3 新增 `removeDeadEntities()` 方法

```java
/** 清理所有已死亡实体 */
private void removeDeadEntities() {
    entities.removeIf(e -> !e.isAlive());
}
```

- 每帧 AI tick 后执行，安全的延迟清理
- 使用 `List.removeIf()` 原子操作

#### 2.4 修改 `buildActiveFrame()` — 跳过死实体渲染

```java
if (e != player && e.isAlive()) { ... }
```

#### 2.5 修改 `isPlayerColliding()` — 只检查活实体

```java
if (e.isAlive() && e.getPosition().equals(p)) { return true; }
```

**附带修正**: 原代码使用 `e == player && e.getPosition().equals(p)` 判断碰撞（始终为 false），现改为正确的 `e.isAlive() && e.getPosition().equals(p)`，确保玩家与其他活跃实体发生碰撞检测。

---

## 使用方式（供后续开发）

### 添加实体
```java
entities.add(newEntity);  // 只需一行，不再需要同时操作 enemies 列表
```

### 删除实体（如敌人被击杀）
```java
enemy.die();  // 标记死亡，下一帧自动清理
```

### 检查实体是否存活
```java
if (entity.isAlive()) { ... }
```

---

## 兼容性说明

- 所有改动对现有 API 向后兼容
- `playWithInputString()` 结果不变（无实体死亡时 `isAlive()` 始终返回 `true`）
- 不影响渲染引擎、世界生成器、动作系统
- 编译通过（javac 零错误）
