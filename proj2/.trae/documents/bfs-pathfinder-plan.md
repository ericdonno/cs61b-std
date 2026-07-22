# 2.1 BFS 寻路器实现计划

## 概述

创建 `BFSPathfinder.java`，实现一个静态方法 `findPath(start, goal, world)`，使用 BFS（广度优先搜索）在地牢地图中计算最短路径。

## 当前状态分析

### 已有基础设施
- **Direction 枚举**（[Direction.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Direction.java)）：已有 `dx`/`dy` 字段和 `fromDelta()` 静态方法，可直接在路径回溯中使用
- **Position 类**（[Position.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/lab5/Position.java)）：有 `x`/`y` 公有字段，已覆写 `equals()` 和 `hashCode()`（与指南描述不同，实际已实现），可直接作为 BFS 队列元素
- **TETile**（[TETile.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/TileEngine/TETile.java)）：`description()` 返回 String，`equals()` 按 character 比较；TETile 是 immutable 对象，每个常量只有一份实例
- **Tileset**（[Tileset.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/TileEngine/Tileset.java)）：FLOOR 的 description 为 `"floor"`，WALL 为 `"wall"`
- **Game**（[Game.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/Game.java)）：`WIDTH=80`, `HEIGHT=30`，`world` 数组为 `TETile[WIDTH][HEIGHT]` 即 `world[x][y]`

### 不存在的文件
- `BFSPathfinder.java`：需要新建

### 指南 vs 实际代码的差异
指南说"Position 没有覆写 equals()"，但实际 Position 已覆写 `equals()` 和 `hashCode()`。使用 `Position` 对象比较时可以直接用 `.equals()`。不过为了一致性，BFS 的 cameFrom 数组仍用方向枚举存储，不依赖 Position 作 HashMap key。

## 拟议变更

### 新建文件：`byog/Core/BFSPathfinder.java`

**方法签名**：`static List<Position> findPath(Position start, Position goal, TETile[][] world)`

**返回值**：
- 从起点到终点的有序位置列表（不包含起点自身）
- 如果不可达，返回空列表 `new ArrayList<>()`
- 如果起点等于终点，返回空列表

**算法流程**：

1. **初始化**：
   - 获取地图尺寸：`int w = world.length`，`int h = world[0].length`
   - 创建 `boolean[WIDTH][HEIGHT]` 的 visited 数组
   - 创建 `Direction[WIDTH][HEIGHT]` 的 cameFrom 数组，记录"到达位置 (x,y) 的上一步方向"
   - 使用 `java.util.ArrayDeque<Position>` 作为 BFS 队列

2. **BFS 遍历**：
   - 标记起点为 visited，入队
   - 循环：从队列取出一个 Position，如果是 goal 则退出循环
   - 对四个方向（UP/DOWN/LEFT/RIGHT），计算邻居坐标 `(nx, ny)`
   - 邻居必须：在边界内、未被访问、是 FLOOR 瓦片（或 goal 位置的瓦片 —— 因为 goal 可能是敌人/玩家站的位置，但起点除外）

3. **可通行判断**：
   - 使用 `tile.description().equals(Tileset.FLOOR.description())` 判断是否为地板
   - **重要设计决策**：goal 位置可能被玩家或敌人占据，其瓦片不是 FLOOR。需要在 BFS 中将 goal 位置也视为可通行。方案：允许遍历到 goal 位置（用坐标比较），或者允许任何非 WALL 瓦片。按指南建议，用 FLOOR 判断 + 对 goal 坐标特殊处理。

4. **路径回溯**：
   - BFS 结束后，如果 goal 未被访问（cameFrom[goalX][goalY] == null），返回空列表
   - 从 goal 沿 cameFrom 数组反向回溯到 start
   - 回溯时用 `Direction.fromDelta()` 反推出方向，得到上一步坐标
   - 收集路径上的 Position（不含起点），最后反转列表

**边界处理**：
- 坐标需在 `[0, WIDTH)` 和 `[0, HEIGHT)` 范围内
- `world` 为 null 时直接返回空列表

**代码结构**：
- 类级 Javadoc 简要说明
- 一个 `public static` 方法 `findPath`
- 所有逻辑在方法内部完成，不拆分子方法（保持简洁）

## 假设与决策

1. **可通行节点**：仅 FLOOR 瓦片，goal 位置特殊允许（因为 goal 上的 Entity tile 不是 FLOOR）。这是 BFS 的核心假设。
2. **对角线移动**：不支持。指南明确说四个方向。
3. **TETile 比较**：使用 `description().equals()` 而非 `==`。虽然 TETile 的 equals 按 character 比较且常量只有一份实例，但 `description()` 方式更语义化且符合指南建议。
4. **Goal 特殊处理**：goal 坐标可能被 Entity 占据（tile 不是 FLOOR），BFS 需要能"到达" goal。通过在 neighbors 检查中额外允许"邻居坐标 equals goal"实现。
5. **start == goal**：返回空列表（无需移动）。

## 验证步骤

1. 编译：`javac byog/Core/BFSPathfinder.java`
2. 手动测试：在 Enemy.java 或新测试方法的 main 中，构造小地图，调用 findPath 并打印路径长度和坐标
3. 与指南一致：确保使用 `description().equals(Tileset.FLOOR.description())` 判断可通行性
