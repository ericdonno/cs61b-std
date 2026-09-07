# Phase 1.5 Spec：敌人视野可视化

## 0. 元数据

- **Phase**：1.5
- **状态**：Draft — 等待 Builder review；实现尚未开始
- **作者**：Codex（持续 Advisor）
- **创建日期**：2026-07-20
- **基线分支**：`ai-enemis`
- **基线 commit**：Phase 1 done
- **前一阶段 Completion**：`PHASE_1_COMPLETION.md`
- **上位文档**：`PROJECT_INTENT_zh-CN.md`、`DEVELOPMENT_ROADMAP.md`、`PHASE_1_SPEC.md`

## 1. 必读输入与审计范围

本 Spec 基于以下实际输入：

- `PHASE_1_SPEC.md`——Phase 1 完成后将产出 `PerceptionSystem`（含 `boolean[][] visibleMask`）和 `ObservationEnvelope`
- `byog/Core/Game.java`——`buildActiveFrame()`（第 485 行）是每帧渲染的入口；`drawGameWithPauseButton()`（第 294 行）调用它
- `byog/IO/GameConfig.java`——已有的配置文件读写机制；`generateDefaultConfigFile()` 负责生成默认配置
- `byog/Entity/Enemy.java`——Phase 1 完成后，Enemy 将在 `updateAI()` 内调用 `PerceptionSystem.computeObservation()`，可缓存 `visibleMask`
- `byog/TileEngine/TETile.java`——不可变 tile 对象，有 `char`、`textColor`、`backgroundColor`、`description` 四个核心字段
- `byog/TileEngine/Tileset.java`——已有的 `FLOOR` tile：`new TETile('·', new Color(128, 192, 128), Color.black, "floor")`
- `byog/Entity/EntityManager.java`——`getAllEntities()` 返回所有实体，可用于遍历敌人获取 FOV mask
- `config/game.properties`——已存在的配置文件，由 `GameConfig.generateDefaultConfigFile()` 生成
- `DEVELOPMENT_ROADMAP.md` §5——Phase 7 试玩驱动深化，debug 可视化是该阶段的前置调试基础设施

## 2. 阶段目标与成功定义

Phase 1 为每个敌人计算了私有视野（`boolean[][] visibleMask`），但这些都是内存中的数据结构——开发者无法肉眼确认感知是否正确、遮挡是否合理、不同敌人的视野差异是否真实存在。

本阶段的目标是：**在游戏画面上实时渲染敌人的视野范围，作为开发者调试工具。** 通过配置文件开关，玩家/开发者在游戏中看到：

- 每个敌人视野内的 FLOOR tile 以不同的颜色（高亮绿色）显示
- WALL tile 即使可见也不变色（墙本身已经可见）
- 多个敌人的视野重叠区域自然叠加（同一个 tile 被多个敌人看到，只显示一次高亮）
- 关闭开关后画面完全恢复正常，不留痕迹

这是一个纯粹的**渲染层叠加**，不修改任何游戏逻辑、AI 决策或感知数据。

## 3. 起始事实

### 3.1 已有可复用能力

- `PerceptionSystem.computeObservation()` 每 tick 为每个敌人产出 `ObservationEnvelope`，内含 `boolean[][] visibleMask`（[PHASE_1_SPEC.md §8.2](../phase-1/PHASE_1_SPEC.md)）
- `Game.buildActiveFrame()` 已遍历所有 tile 构建显示帧（[Game.java:485-513](../../../byog/Core/Game.java)）——FOV 叠加只需在此方法中添加一步
- `GameConfig` 已有完整的配置加载机制（[GameConfig.java](../../../byog/IO/GameConfig.java)），新增一个 boolean 配置项只需加一个字段
- `TETile` 构造函数 `TETile(char, Color, Color, String)` 可直接创建同字符、不同颜色的 tile 变体（[TETile.java:56](../../../byog/TileEngine/TETile.java)）
- `EntityManager.getAllEntities()` 返回所有实体（含 Enemy），可用于遍历获取 FOV mask

### 3.2 需要新增的部分

- Enemy 需要暴露最近一次计算出的 `visibleMask`（Phase 1 只算不用，未对外暴露）
- Tileset 需要一个 `FLOOR_FOV` tile——和 FLOOR 相同字符，但文字颜色更亮
- GameConfig 需要一个 `debugShowEnemyFov` 布尔配置项
- `buildActiveFrame()` 需要在实体叠加后、攻击动画前，对 FOV 内的 FLOOR tile 做颜色替换

### 3.3 与 Phase 1 的边界

| Phase 1 提供 | Phase 1.5 使用 |
|-------------|---------------|
| `PerceptionSystem` | 不直接调用（复用 Enemy 的缓存结果） |
| `ObservationEnvelope.getVisibleMask()` | Enemy 缓存后由 getter 提供给渲染层 |
| `Enemy.updateAI()` 中的私有感知路径 | 不变——本阶段只读，不修改 AI 行为 |
| `perceptionEnabled` 开关 | Phase 1.5 也尊重此开关：`perceptionEnabled=false` 的敌人无 FOV 可显示 |

## 4. 需求追踪

本阶段不是 Roadmap §5 中的正式阶段，而是 Phase 1 的开发辅助功能。它不直接映射到 INV-01~09 约束，但服务于以下需求：

| 需要 | 如何满足 | 验收 |
|------|---------|------|
| Phase 1 FOV 计算可肉眼验证 | 画面实时渲染敌人可见的 floor | 开发者启动游戏后能直观看到不同敌人的视野差异 |
| 不影响正常游戏体验 | config 开关控制，默认关闭 | `debug.showEnemyFov=false` 时画面与 Phase 1 完全一致 |
| 不增加 AI 性能负担 | 复用已计算的 visibleMask，仅渲染层多一层判断 | 帧率无明显变化 |
| 不影响 canonical trace | 渲染层改动不涉及 AI tick 或 trace 事件 | Phase 1 golden 不变 |

## 5. 范围与非目标

### In Scope

- `Tileset` 新增 `FLOOR_FOV` tile（同字符、更亮绿色）
- `GameConfig` 新增 `debugShowEnemyFov` 配置项（默认 `false`）
- `config/game.properties` 的默认生成模板中加入此项
- `Enemy` 新增 `getVisibleMask()` 方法（返回最近一次 `computeObservation` 的 `visibleMask`）
- `Game.buildActiveFrame()` 新增 FOV 叠加逻辑

### Out of Scope

- 热键实时切换（可通过后续 Phase 加专用热键）
- 不同敌人用不同颜色区分视野（调试不需要）
- 视野范围圈、射线可视化、LOS 线
- 在 headless 测试中使用 FOV 可视化（纯视觉功能，headless 不需要）
- 修改 Phase 1 的任何 AI 逻辑或数据结构
- 在 UI 栏显示 FOV 开关状态指示器

## 6. 已锁定决定、假设与待决定项

### 6.1 已锁定决定

#### D1-01：FOV tile 为 FLOOR 的颜色变体

```java
// Tileset.java 新增
public static final TETile FLOOR_FOV = new TETile('·',
    new Color(160, 224, 160), Color.black, "floor (enemy sight)");
```

- 字符不变（`·`），方便对齐
- 文字颜色从 `(128, 192, 128)` 提亮到 `(160, 224, 160)`，在黑色背景上明显但不刺眼
- 背景色保持黑色，与 FLOOR 一致
- 不改 WALL tile——即便在视野内，敌人看到的就是墙本身，不需要额外标记

#### D1-02：覆盖逻辑优先级

`buildActiveFrame()` 中的渲染顺序为：
1. `TETile.copyOf(world)` —— 基础地形
2. 实体叠加（player、enemy）
3. **FOV 叠加**（新增）：对所有 enemy，将其 `visibleMask` 中为 true 的 FLOOR tile 替换为 `FLOOR_FOV`
4. 攻击动画

FOV 叠加在实体之后、攻击动画之前，意味着：
- 敌人脚下的 tile 不会被 FOV 替换（已被 entity tile 覆盖）
- 攻击动画覆盖所有内容（闪光优先级最高）

#### D1-03：多敌人视野重叠处理

直接遍历所有敌人，用 `visibleMask` 逐像素覆盖。如果 tile A 在敌人 1 和敌人 2 的视野中都可见，两次替换结果都是 `FLOOR_FOV`——同一个 tile，没有叠加色差。这符合"这是至少一个敌人能看到的地板"的语义。

#### D1-04：perceptionEnabled 的交互

只有 `perceptionEnabled=true` 的敌人才有 `visibleMask`。`getVisibleMask()` 在 `perceptionEnabled=false` 时返回 `null`，渲染层跳过该敌人。这确保了：
- Phase 0 兼容模式不产生 FOV 可视化（因为没有 `visibleMask` 数据）
- 未来如果混合存在（部分敌人启用感知、部分不启用），渲染层自然跳过无 FOV 数据的敌人

#### D1-05：配置方式

```properties
# config/game.properties 新增一行
debug.showEnemyFov=false
```

`GameConfig` 新增字段 `public final boolean debugShowEnemyFov`。用户修改配置文件后重启游戏生效。不提供运行时热键切换（Phase 1.5 聚焦最小实现）。

### 6.2 暂时假设

- **假设 A1**：Phase 1 完成后，Enemy 的内部 `visibleMask` 引用在两次 `updateAI()` 调用之间保持有效（不被置 null）。如果 Phase 1 设计为每次调用后丢弃，则 Enemy 需要显式缓存，本 Spec §14 提供了处理方案。
- **假设 A2**：`buildActiveFrame()` 每帧被调用一次（当前的确如此）。如果未来引入帧缓冲或多 pass 渲染，FOV 叠加位置可能需要调整，但逻辑不变。

### 6.3 需要 Builder 决定

#### Q1：是否需要运行时热键切换

当前方案：修改 config 文件 → 重启游戏 → 生效。最简单。

备选方案：在游戏中使用键盘快捷键（如 `Ctrl+F` 或游戏中未使用的键）实时切换 FOV 显示。优点是不用重启，缺点是多了一小段 state 管理代码。

Advisor 建议：**先不加热键**。Phase 1.5 是调试基础设施，不是玩家功能。每次修改 config 重启已经足够方便，同时保持了代码最小改动原则。

## 7. 目标架构与数据流

```text
每帧渲染时：

Game.drawGameWithPauseButton()
  │
  └─ buildActiveFrame()
       │
       ├─ 1. TETile.copyOf(world)         → frame 含基础地形
       ├─ 2. 实体叠加                      → player/enemy tile 写入 frame
       ├─ 3. FOV 叠加（新增）
       │     │
       │     ├─ if (!gameConfig.debugShowEnemyFov) → 跳过
       │     │
       │     └─ for (Entity e : entityMgr.getAllEntities())
       │           if (e instanceof Enemy enemy && enemy.isAlive())
       │             boolean[][] mask = enemy.getVisibleMask()
       │             if (mask == null) → 跳过（perceptionEnabled=false 的敌人）
       │             for 每个 (x, y) in mask where mask[x][y]==true
       │               if (frame[x][y] == Tileset.FLOOR)
       │                 frame[x][y] = Tileset.FLOOR_FOV
       │
       └─ 4. 攻击动画叠加（不变）
```

数据所有权：
- `visibleMask` 由 `PerceptionSystem` 创建，Enemy 缓存引用
- `Enemy.getVisibleMask()` 只读暴露，Game 不修改
- `frame` 是 `TETile[][]` 副本，FOV 叠加在上面覆盖，不污染原 `world` 数组

失败处理：
- `getVisibleMask()` 返回 `null` → 跳过该敌人，不影响其他敌人和游戏逻辑
- `debugShowEnemyFov=false` → 整步跳过，零开销
- 任何异常 → 不应发生（全是纯数组访问和引用比较），但若有，标准 Java 异常冒泡到调用方

## 8. 接口与数据契约

### 8.1 Enemy 新增 getter

```java
// Enemy.java 新增
/** 返回最近一次感知计算的可见性遮罩。perceptionEnabled=false 时返回 null。 */
public boolean[][] getVisibleMask();
```

当 `perceptionEnabled=true` 且至少执行过一次 `updateAI()` 后，返回有效 `boolean[][]`。

### 8.2 Tileset 新增

```java
// Tileset.java 新增
/** 敌人视野内的地板瓦片：与 FLOOR 相同字符，更亮的绿色 */
public static final TETile FLOOR_FOV = new TETile('·',
    new Color(160, 224, 160), Color.black, "floor (enemy sight)");
```

### 8.3 GameConfig 新增

```java
// GameConfig.java 新增字段
public final boolean debugShowEnemyFov;

// 构造方法中新增读取
this.debugShowEnemyFov = getBoolean(props, "debug.showEnemyFov", false);

// 新增 getBoolean 辅助方法
private static boolean getBoolean(Properties props, String key, boolean defaultValue) {
    String value = props.getProperty(key);
    if (value == null) return defaultValue;
    return Boolean.parseBoolean(value.trim());
}
```

### 8.4 config/game.properties 新增

在默认配置生成 `generateDefaultConfigFile()` 末尾追加：

```properties
# ========== Debug ==========
debug.showEnemyFov=false
```

### 8.5 buildActiveFrame() 新增

```java
// Game.java buildActiveFrame() 方法中，实体叠加之后、攻击动画之前插入：

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
```

注意：遍历 `mask` 时用 `mask[x].length` 而非 `mask[0].length`（防御不规则数组）。

## 9. 逐文件变更计划

| 文件 | 新建/修改 | 责任 | 关键变更 | 不应包含 |
|------|-----------|------|----------|----------|
| `byog/TileEngine/Tileset.java` | 修改 | FOV 地板瓦片 | 新增 `FLOOR_FOV` 常量 | 其他视觉样式 |
| `byog/IO/GameConfig.java` | 修改 | 配置加载 | 新增 `debugShowEnemyFov` 字段 + `getBoolean()` 方法 + 默认配置生成 | 其他 debug 配置项 |
| `byog/Entity/Enemy.java` | 修改 | 暴露 visibleMask | 新增 `getVisibleMask()` 方法 | AI 逻辑改动 |
| `byog/Core/Game.java` | 修改 | FOV 渲染叠加 | `buildActiveFrame()` 中新增 FOV 叠加代码块 | UI 栏热键 |

**零新建文件。** 全部改动在已有文件中。

## 10. 实施顺序

### Step 1.5-1：Tileset 新增 FLOOR_FOV

**输入**：当前 Tileset.java。

**具体改动**：在 `FLOOR` 定义下一行新增 `FLOOR_FOV` 常量。

**验证**：编译通过。

**产出**：Tileset.java 修改。

### Step 1.5-2：GameConfig 新增 debug 配置项

**输入**：当前 GameConfig.java。

**具体改动**：
1. 新增 `getBoolean()` 私有辅助方法
2. 新增 `debugShowEnemyFov` 字段并在构造方法中初始化
3. `generateDefaultConfigFile()` 中追加 `debug.showEnemyFov=false`

**验证**：编译通过；删除 `config/game.properties` 后运行游戏，确认文件重新生成且包含新配置项。

**产出**：GameConfig.java 修改。

### Step 1.5-3：Enemy 新增 getVisibleMask()

**输入**：Phase 1 完成后的 Enemy.java（此时 Enemy 内部已有 `visibleMask` 或能通过 `ObservationEnvelope` 获取）。

**具体改动**：添加 `getVisibleMask()` 方法，返回最近一次 `computeObservation()` 的 `visibleMask`。如果 Phase 1 实现在每次 tick 后丢弃引用，则新增 `private boolean[][] cachedVisibleMask` 字段缓存。

**验证**：编译通过；Phase 1 测试不受影响（纯新增公有 getter）。

**产出**：Enemy.java 修改。

### Step 1.5-4：Game.buildActiveFrame() 新增 FOV 叠加

**输入**：Step 1.5-3 的 Enemy、Step 1.5-2 的 GameConfig、Step 1.5-1 的 FLOOR_FOV。

**具体改动**：在 `buildActiveFrame()` 的实体叠加之后、攻击动画之前，插入 §8.5 的 FOV 叠加代码。

**验证**：
1. 编译通过
2. `debug.showEnemyFov=false` 时启动游戏，画面与 Phase 1 完全一致
3. `debug.showEnemyFov=true` 时启动游戏，敌人视野内的 FLOOR tile 显示为更亮的绿色
4. Phase 1 全部测试仍然通过（`debugShowEnemyFov` 不影响 `playWithInputString` 和 headless 测试）

**产出**：Game.java 修改。

### Step 1.5-5：人工验收

**输入**：Phase 1 fixed scenario `baseline-two-guards:v1`。

**具体改动**：无。

**验证**：
1. 启动游戏，进入随机地图
2. 修改 `debug.showEnemyFov=true`，重启
3. 肉眼确认：敌人周围 sightRange 内的 FLOOR tile 变为亮绿色；墙后面的 tile 不变色
4. 修改 `debug.showEnemyFov=false`，重启，确认画面恢复正常
5. 换楼层后 FOV 随敌人位置变化更新

**产出**：人工确认记录（截图或录像）。

## 11. 测试与验收矩阵

| Test ID | 场景 | 断言 | 自动/人工 | 备注 |
|---------|------|------|-----------|------|
| 1.5-T01 | `debugShowEnemyFov=false` 时画面不变 | 与 Phase 1 最终画面一致 | 人工 | 修改 config 重启对比 |
| 1.5-T02 | `debugShowEnemyFov=true` 时 FOV 地板变色 | 敌人周围 sightRange 内的 floor 为亮绿色 | 人工 | 固定场景或随机地图 |
| 1.5-T03 | 墙后 floor 不变色 | 敌人视线被墙遮挡的位置保持原色 | 人工 | 手动走到墙后观察 |
| 1.5-T04 | 敌人脚下的 tile 不被 FOV 覆盖 | 敌人位置显示 `E`，不是 `FLOOR_FOV` | 人工 | — |
| 1.5-T05 | 多敌人 FOV 重叠正常 | 两个敌人视野重叠区域只显示一次亮绿 | 人工 | — |
| 1.5-T06 | Phase 1 测试回归 | Phase 1 全部测试通过 | 自动 | 编译 + JUnit |
| 1.5-T07 | 帧率无明显下降 | 开启 FOV 后帧率与关闭时一致 | 人工 | 肉眼判断即可 |

本阶段不新增 JUnit 测试——所有功能性验证通过肉眼完成。

## 12. Observability 与运行证据

本阶段不涉及 trace、log 或 canonical evidence 变更。验收证据为：
- 人工验收截图或录像
- Phase 1 测试套件回归通过的控制台输出

## 13. 失败处理、兼容与迁移

### 向后兼容

- `debugShowEnemyFov` 默认 `false`——不修改 config 文件的用户感知不到任何变化
- 如果 config 文件中缺少 `debug.showEnemyFov` 键，`getBoolean()` 返回默认值 `false`（优雅降级）
- 旧存档加载完全不受影响
- Phase 1 golden 不变（FOV 渲染不在 headless 测试路径中）

### 配置迁移

- `GameConfig.generateDefaultConfigFile()` 写入新配置项。已存在的 config 文件不会自动追加新键——用户手动添加或删除文件重新生成。这是已知限制，不是 bug。

### 降级路径

- 如果 `Enemy.getVisibleMask()` 返回 `null`（Phase 1 尚未完成或 `perceptionEnabled=false`），渲染层跳过该敌人
- 如果 `gameConfig` 为 `null`（极端边界），跳过 FOV 叠加

## 14. 风险与停止条件

### 主要风险

1. **visibleMask 生命周期**：Phase 1 Spec 未明确规定 `visibleMask` 在 `updateAI()` 外是否仍然有效。如果 Phase 1 实现在每次 `updateAI()` 结束时置 null 或回收引用，Enemy 需要显式缓存引用。**处理方案**：在 Enemy 中新增 `private boolean[][] cachedVisibleMask` 字段，`updateAI()` 结束时赋值，`getVisibleMask()` 返回缓存。

2. **FLOOR_FOV 颜色选择**：当前选用的 `(160, 224, 160)` 在黑色背景上偏亮但不刺眼。如果实际显示效果不佳（太亮或与 FLOOR 区分度不够），调整颜色值即可，不影响其他代码。

3. **过度工程化**：容易忍不住加 hotkey、不同敌人不同颜色、UI 指示器等功能。严格按 Out of Scope 来。

### 停止条件

- Phase 1 未完成时不能开始 Phase 1.5（依赖 `PerceptionSystem` 和 `Enemy` 的私有感知路径）
- `buildActiveFrame()` 改动导致编译失败或 Phase 1 测试不通过

## 15. Definition of Done

- [ ] `Tileset.FLOOR_FOV` 已定义
- [ ] `GameConfig` 支持读取 `debug.showEnemyFov`
- [ ] `config/game.properties` 默认模板包含此配置项
- [ ] `Enemy.getVisibleMask()` 已实现
- [ ] `buildActiveFrame()` 中 FOV 叠加逻辑已加入
- [ ] `debug.showEnemyFov=false` 时画面与 Phase 1 完全一致（人工确认）
- [ ] `debug.showEnemyFov=true` 时敌人视野内 FLOOR tile 高亮（人工确认）
- [ ] Phase 1 全部测试通过
- [ ] 帧率无明显下降
- [ ] `PHASE_1DOT5_COMPLETION.md` 已生成

## 16. 下一阶段交接

Phase 2 可以依赖：
- `FLOOR_FOV` tile 和 `debugShowEnemyFov` 配置（作为持续可用的 debug 基础设施）
- 开发者在 Phase 2+ 调试感知和通信时，可随时开启 FOV 可视化验证

Phase 2 不得假设：
- FOV 热键已实现
- FOV 可视化在 headless 模式下可用

## 附录：生成者自检

- [x] 我先检查了 Phase 1 Spec + 当前代码，不是凭空写
- [x] 我区分了"Phase 1 将产出"和"Phase 1.5 新增"
- [x] 我引用了关键代码位置（Game.java、TETile.java、Tileset.java、GameConfig.java）
- [x] 我没有提前实现后续 Phase 的功能
- [x] 我为每项完成条件提供了可执行验证
- [x] 改动范围极小——4 个文件、0 个新建文件
