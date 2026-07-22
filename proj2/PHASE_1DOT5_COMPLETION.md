# Phase 1.5 完成文档：敌人视野可视化

## 阶段概况

- **目标**：在游戏画面上实时渲染敌人的私有视野范围（FOV），作为开发者调试工具
- **实现方式**：配置文件开关控制，渲染层叠加，不修改任何 AI 逻辑
- **完成日期**：2026-07-22

## 修改清单

| 文件 | 改动 | 行号 |
|------|------|------|
| [Tileset.java](byog/TileEngine/Tileset.java#L26-L28) | 新增 `FLOOR_FOV` 常量：绿色字符 + 深红背景 | 26-28 |
| [GameConfig.java](byog/IO/GameConfig.java) | 新增 `debugShowEnemyFov` 字段 + `getBoolean()` 方法 + 默认配置模板 | 32, 50, 82-89, 141-143 |
| [Enemy.java](byog/Entity/Enemy.java) | 新增 `cachedVisibleMask` 字段 + `getVisibleMask()` getter | 40-41, 252-254 |
| [Enemy.java](byog/Entity/Enemy.java#L191-L199) | 动作执行后重新计算 FOV（修复滞后问题） | 191-199 |
| [Game.java](byog/Core/Game.java#L515-L531) | `buildActiveFrame()` 新增 FOV 叠加循环 | 515-531 |
| [game.properties](config/game.properties#L42-L43) | 新增 `debug.showEnemyFov` 配置项 | 42-43 |

## 架构说明

### 数据流

```
Enemy.updateAI() → PerceptionSystem.computeObservation()
  → cachedVisibleMask（移动后重新计算，紧跟敌人位置）

Game.buildActiveFrame() → 遍历 entityMgr
  → 读取 Enemy.getVisibleMask()
  → 将 mask 中可见的 FLOOR tile 替换为 FLOOR_FOV
```

### 渲染层叠加顺序

1. `TETile.copyOf(world)` — 基础地形
2. 实体叠加（player、enemy）
3. **FOV 叠加**（新增）：FLOOR → FLOOR_FOV
4. 攻击动画

FOV 叠加在实体之后，确保敌人脚下的 tile 不被覆盖。

### 配置控制

- 配置键：`debug.showEnemyFov`
- 默认值：`false`（不影响正常游戏体验）
- `GameConfig` 通过 `getBoolean()` 读取，key 缺失时优雅降级为 `false`
- 修改配置后需重启游戏生效

## 已验证项

- [x] `Tileset.FLOOR_FOV` 已定义
- [x] `GameConfig` 支持读取 `debug.showEnemyFov`
- [x] `config/game.properties` 默认模板包含此配置项
- [x] `Enemy.getVisibleMask()` 已实现
- [x] `buildActiveFrame()` 中 FOV 叠加逻辑已加入
- [x] `debug.showEnemyFov=false` 时画面与 Phase 1 完全一致
- [x] `debug.showEnemyFov=true` 时敌人视野内 FLOOR tile 高亮（人工确认）
- [x] Phase 1 全部 27 个测试通过
- [x] FOV 紧跟敌人移动（动作执行后重新计算，无滞后）

## 调试中发现的问题与修复

### FOV 视觉不够明显

- **初始颜色**：浅绿 `(160,224,160)` vs 原 FLOOR `(128,192,128)`，差异太细微
- **最终颜色**：绿色字符 `(128,192,128)` + 深红背景 `(80,20,20)`，与黑色背景的 FLOOR 形成明显对比

### FOV 滞后于敌人移动

- **根因**：`cachedVisibleMask` 在动作执行前缓存，敌人移动后 mask 仍反映旧位置
- **修复**：动作执行完后用当前位置重新计算一次 FOV

## 使用方式

1. 编辑 `config/game.properties`，将 `debug.showEnemyFov` 设为 `true`
2. 重启游戏
3. 敌人视野内的 FLOOR tile 会显示为深红背景
4. 改回 `false` 即可关闭

## 后续 Phase 可依赖

- `FLOOR_FOV` tile 和 `debugShowEnemyFov` 配置（作为持续可用的 debug 基础设施）
- 开发者在后续 Phase 调试感知和通信时，可随时开启 FOV 可视化验证

## 不得假设

- FOV 热键已实现（未实现）
- FOV 可视化在 headless 模式下可用（不可用）