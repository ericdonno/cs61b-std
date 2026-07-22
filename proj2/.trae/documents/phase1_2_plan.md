# Phase 1.2 实现计划：PerceptionSystem

## 仓库调研结论

### 现有结构
- 感知相关类已放在 `byog/Perception/` 目录：`VisibleEntity.java`, `ObservationEnvelope.java`, `HeardEvent.java`
- `Entity.java` 已包含 `agentId` 字段及 getter/setter
- `EntityManager.findEntityAt()` 可用于查询位置实体
- `Entity.canStandOn()` 可用于判断 tile 是否阻挡视线
- `Tileset.WALL` / `Tileset.NOTHING` 用于判断 LOS 遮挡
- `MathHelper.manhattanDistance()` 用于缩窄 FOV 候选范围

### 关键依赖
- `byog.Entity.Enemy` - 需要感知系统的敌人实体
- `byog.Entity.Player` - 玩家实体
- `byog.Entity.EntityManager` - 实体管理
- `byog.TileEngine.TETile` - 瓦片类型
- `byog.TileEngine.Tileset` - 瓦片常量
- `byog.lab5.Position` - 位置坐标

## 待编辑文件

### 新建文件
| 文件路径 | 说明 |
|---------|------|
| `byog/Perception/PerceptionSystem.java` | 核心感知计算系统 |
| `byog/Test/PerceptionSystemTest.java` | LOS 单元测试 |

### 无修改文件
- 阶段1.2不修改现有文件，只新建文件
- 所有数据类（`VisibleEntity`, `ObservationEnvelope`, `HeardEvent`）保持不变

## 实施步骤

### Step 1：创建 PerceptionSystem.java

实现三个核心方法：

#### 1.1 blocksVision()
- 参数：`TETile tile`
- 返回：`boolean`（WALL 或 NOTHING 返回 true，其他返回 false）

#### 1.2 hasLineOfSight()（Bresenham 射线投射）
- 参数：`TETile[][] world, int x0, int y0, int x1, int y1`
- 返回：`boolean`（终点可见返回 true）
- 算法：
  - 使用 Bresenham 算法遍历从起点到终点的所有格子
  - 对每个格子检查是否阻挡视线
  - 如果中途遇到 WALL/NOTHING，立即终止，返回 false
  - 如果到达终点且中途未被阻挡，返回 true

#### 1.3 computeObservation()
- 参数：`TETile[][] world, EntityManager entityMgr, Enemy self, Player player, int sightRange, long currentTurn`
- 返回：`ObservationEnvelope`
- 算法流程：
  1. 以 `self.getPosition()` 为中心，遍历曼哈顿距离 ≤ `sightRange` 的所有 tile
  2. 对每个候选 tile，调用 `hasLineOfSight()` 判断是否可见
  3. 如果 LOS 畅通，标记 `visibleMask[tileX][tileY] = true`
  4. 对每个可见位置，调用 `entityMgr.findEntityAt(pos)` 检查实体
  5. 收集所有在可见位置的活实体（不含自身）→ `VisibleEntity` 列表
  6. 收集当前 tick 的听觉事件 → `HeardEvent` 列表（Phase 1 为空）
  7. 构造并返回 `ObservationEnvelope`

### Step 2：创建 PerceptionSystemTest.java

编写单元测试：

#### 2.1 blocksVision 测试
- 测试 WALL 阻挡视线
- 测试 NOTHING 阻挡视线
- 测试 FLOOR 不阻挡视线
- 测试 STAIRS 不阻挡视线

#### 2.2 hasLineOfSight 小地图测试
- **测试场景 1**：直线无遮挡（5x5 地图，起点(2,2)，终点(4,2)，中间全是 FLOOR）
- **测试场景 2**：直线被墙遮挡（起点(2,2)，终点(4,2)，中间(3,2)是 WALL）
- **测试场景 3**：斜线路径（起点(0,0)，终点(4,4)，无遮挡）
- **测试场景 4**：斜线被墙遮挡（起点(0,0)，终点(4,4)，中间(2,2)是 WALL）

## 潜在依赖与考虑

### 坐标系统约定
- world 数组索引：`world[x][y]`
- 与 Phase 0 一致，无需额外转换

### 性能考虑
- sight range = 7 时，曼哈顿距离范围内约有 200 个 tile
- 每个 tile 一条射线，计算量可控

### 边界情况
- 起点和终点相同 → 总是可见
- 目标在边界外 → 不可见
- 射线穿过边界 → 提前终止

## 风险处理

### Bresenham 射线"漏光"问题
- **风险**：射线可能在对角墙角穿过（从墙格子旁边滑过去）
- **处理方式**：按 Spec 要求，不做特殊处理。`baseline-two-guards` 地图中极少对角墙角漏光情况，且敌人偶尔多看到一点不影响游戏体验。

### 可见性判断规则
- 可见的 WALL tile 计入 FOV（敌人知道墙在哪里）
- 可见的实体：如果实体所在 tile 在 FOV 内，则实体对该敌人可见

## 验证方式

1. **编译验证**：所有新建文件编译通过
2. **单元测试**：`PerceptionSystemTest` 全部测试通过
3. **功能验证**：`hasLineOfSight()` 在手写小地图上正确判断遮挡

## 产出

- `byog/Perception/PerceptionSystem.java` - 感知计算系统
- `byog/Test/PerceptionSystemTest.java` - LOS 单元测试
