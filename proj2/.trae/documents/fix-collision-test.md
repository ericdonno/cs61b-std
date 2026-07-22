# EnemyCollisionTest 编译修复

## 问题

[EnemyCollisionTest.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/EnemyCollisionTest.java) L33-34 调用 `updateAI(world, em)` 缺少第三个参数 `player`。

## 原因

2.5 将 `Enemy.updateAI` 签名从 `(world, entityMgr)` 改为 `(world, entityMgr, player)`，但测试文件未同步。

## 变更

**文件**：[EnemyCollisionTest.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/EnemyCollisionTest.java)

L33-34 两行添加 `player` 参数：

```java
e1.updateAI(world, em, player);
e2.updateAI(world, em, player);
```

## 验证

编译 `EnemyCollisionTest.java`，无编译错误。
