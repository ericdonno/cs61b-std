# Phase 1.5 实现计划：敌人视野可视化

## 1. 代码库研究结论

### 当前状态

根据 [PHASE_1DOT5_SIGHT_SHOW.md](file:///d:/Courses/cs61b/cs61b-std/proj2/PHASE_1DOT5_SIGHT_SHOW.md) 和实际代码分析：

**已就绪的能力**：
- `PerceptionSystem.computeObservation()` 每 tick 为每个敌人产出 `ObservationEnvelope`，内含 `boolean[][] visibleMask`
- `Game.buildActiveFrame()` 已遍历所有 tile 构建显示帧（第502-530行）
- `GameConfig` 已有完整的配置加载机制（`getInt()` 方法）
- `TETile` 构造函数支持创建同字符、不同颜色的 tile 变体

**需要新增的部分**：
- `Tileset` 需要 `FLOOR_FOV` tile（同字符、更亮绿色）
- `GameConfig` 需要 `debugShowEnemyFov` 配置项和 `getBoolean()` 方法
- `Enemy` 需要缓存 `visibleMask`（当前 `ObservationEnvelope` 是局部变量）和 `getVisibleMask()` getter
- `Game.buildActiveFrame()` 需要 FOV 叠加逻辑

### 关键文件位置

| 文件 | 路径 | 关键行号 |
|------|------|---------|
| Tileset.java | `byog/TileEngine/Tileset.java` | 第24-25行（FLOOR 定义） |
| GameConfig.java | `byog/IO/GameConfig.java` | 第20-32行（字段定义）、第67-79行（getInt 方法）、第82-135行（generateDefaultConfigFile） |
| Enemy.java | `byog/Entity/Enemy.java` | 第25-57行（字段定义）、第91-95行（computeObservation） |
| Game.java | `byog/Core/Game.java` | 第502-530行（buildActiveFrame） |

### 重要发现

**假设 A1 验证**：Enemy 的 `visibleMask` 确实不跨帧存活。`ObservationEnvelope observation` 是 `updateAI()` 方法内的局部变量（第91行），方法返回后即被 GC 回收。因此必须在 Enemy 中显式缓存。

## 2. 文件修改计划

### Step 1：Tileset 新增 FLOOR_FOV

**文件**：`byog/TileEngine/Tileset.java`

**改动**：
- 在 `FLOOR` 定义（第24-25行）之后新增 `FLOOR_FOV` 常量

```java
public static final TETile FLOOR_FOV = new TETile('·',
        new Color(160, 224, 160), Color.black, "floor (enemy sight)");
```

### Step 2：GameConfig 新增 debug 配置项

**文件**：`byog/IO/GameConfig.java`

**改动**：
1. 新增 `getBoolean()` 私有辅助方法（参考 `getInt()` 的模式）
2. 新增 `debugShowEnemyFov` 字段并在构造方法中初始化
3. `generateDefaultConfigFile()` 末尾追加 `debug.showEnemyFov=false`

### Step 3：Enemy 缓存 visibleMask 并暴露 getter

**文件**：`byog/Entity/Enemy.java`

**改动**：
1. 新增 `private boolean[][] cachedVisibleMask` 字段
2. 在 `updateAI()` 的私有感知路径中（`perceptionEnabled=true` 分支），`computeObservation()` 返回后缓存引用
3. 新增 `getVisibleMask()` 方法，返回缓存的 `visibleMask`（`perceptionEnabled=false` 时返回 `null`）

### Step 4：Game.buildActiveFrame() 新增 FOV 叠加

**文件**：`byog/Core/Game.java`

**改动**：
- 在 `buildActiveFrame()` 的实体叠加之后（第514行）、攻击动画之前（第516行）插入 FOV 叠加代码

```java
// --- FOV 可视化（debug）---
if (gameConfig != null && gameConfig.debugShowEnemyFov) {
    for (Entity e : entityMgr.getAllEntities()) {
        if (e instanceof Enemy enemy && e.isAlive()) {
            boolean[][] mask = enemy.getVisibleMask();
            if (mask == null) continue;
            for (int x = 0; x < frame.length && x < mask.length; x++) {
                for (int y = 0; y < frame[0].length && y < mask[x].length; y++) {
                    if (mask[x][y] && frame[x][y] == Tileset.FLOOR) {
                        frame[x][y] = Tileset.FLOOR_FOV;
                    }
                }
            }
        }
    }
}
// --- end FOV ---
```

## 3. 实施顺序

1. **Step 1**：Tileset 新增 FLOOR_FOV → 编译验证
2. **Step 2**：GameConfig 新增 debug 配置项 → 编译验证
3. **Step 3**：Enemy 缓存 visibleMask 并暴露 getter → 编译验证
4. **Step 4**：Game.buildActiveFrame() 新增 FOV 叠加 → 编译验证 + 测试回归

## 4. 依赖与注意事项

### 依赖关系
- Step 4 依赖 Step 1（FLOOR_FOV）、Step 2（debugShowEnemyFov）、Step 3（getVisibleMask）
- 所有改动都是增量的，不修改现有逻辑

### 注意事项
- `visibleMask` 的生命周期：缓存只在 `perceptionEnabled=true` 的 Enemy 中有效
- 渲染顺序：FOV 叠加必须在实体之后、攻击动画之前（确保敌人脚下不被覆盖）
- 边界检查：遍历 `mask` 时使用 `mask[x].length` 而非 `mask[0].length`（防御不规则数组）

## 5. 风险处理

| 风险 | 处理方案 |
|------|---------|
| visibleMask 生命周期问题 | Enemy 显式缓存，`updateAI()` 结束时赋值 |
| FLOOR_FOV 颜色效果不佳 | 调整 RGB 值即可，不影响其他代码 |
| 过度工程化 | 严格按 Out of Scope 来，不加 hotkey、UI 指示器等 |
| 编译失败 | 每步验证编译，失败则回退检查 |

## 6. 验证标准

- [ ] 每步修改后编译通过
- [ ] `debug.showEnemyFov=false` 时画面与 Phase 1 完全一致
- [ ] `debug.showEnemyFov=true` 时敌人视野内 FLOOR tile 高亮（更亮绿色）
- [ ] Phase 1 全部测试通过
- [ ] 帧率无明显下降