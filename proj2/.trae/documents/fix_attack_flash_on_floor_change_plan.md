# 修复进入下一层攻击闪光残留 Bug

## 根因

[Game.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Game.java) 的 `nextFloor()` 中重置了 `frameCounter = 0`，但未重置 `attackFrame`。

`buildActiveFrame()` 的闪光判断条件：

```java
if (attackFrame >= 0 && frameCounter - attackFrame < 8)
```

当旧 `attackFrame` 是一个大正数（如 500），而新 `frameCounter` 是 0 时，`0 - 500 < 8` 恒为 true，闪光持续渲染。直到玩家再次攻击更新 `attackFrame` 才恢复。

## 修复

**文件**: `byog/Core/Game.java`

在 `nextFloor()` 方法中，`frameCounter = 0;` 之后添加：

```java
attackFrame = -1;
```

## 验证

1. 编译通过
2. 游戏中攻击一次（看到闪光）
3. 踩上传送方块进入下一层
4. 确认新层没有攻击闪光残留
