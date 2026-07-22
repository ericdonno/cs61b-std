# 地牢层数机制实现计划

## 需求分析

1. **地牢层数机制**：多层地牢，每层独立，楼层无限递增
2. **每层地图按种子伪随机生成**：同种子每层都一样，种子格式 `seed + "_F" + floorLevel`
3. **敌人数量每层加1**：基础数量(3) + (层数-1)
4. **传送方块**：玩家踩上进入下一层，放在离玩家生成位置最远的房间里的随机地板位置
5. **最远房间判定**：使用 RoomGraph MST 拓扑图距离（见下文分析）

## 最远房间：曼哈顿 vs 图拓扑距离

| 方案 | 原理 | 优点 | 缺点 |
|------|------|------|------|
| 曼哈顿距离 | `\|x1-x2\| + \|y1-y2\|` | 极简 | 几何最远的房间可能只隔一条短走廊就到，玩家走两步就踩到传送门，体验差 |
| **图拓扑距离**（选用） | 在 RoomGraph 的 MST 树上做 BFS，累加边权得到房间间路径长 | 反映地牢真实连通性，"最远"=要走最长的走廊路径 | 需拿到 RoomGraph 实例 |

**选择图拓扑距离**：MST 是地牢的主干道网络，用它算出的"最远房间"才是玩家需要穿越最多房间和走廊才能到达的地方——传送门放在那里才有挑战意义。

## 实现步骤

### 步骤1：添加传送方块瓦片
**文件**: `byog/TileEngine/Tileset.java`
- 添加 `STAIRS` 瓦片，`'>'`，绿色字体，黑色背景

### 步骤2：创建 WorldGenResult（单层生成结果包装）
**文件**: `byog/Core/WorldGenResult.java`（新建）
- `TETile[][] world` 和 `List<SquareRoom> rooms`
- 简单 DTO，getter 方法

### 步骤3：修改 WorldGenerator 返回房间列表
**文件**: `byog/Core/WorldGenerator.java`
- `RandomSquareRoomWrd()` 返回 `WorldGenResult`（包含 world + rooms）
- 内部改动小：之前就生成了 `allRoom` 列表，现在打包返回即可

### 步骤4：为 SquareRoom 添加地板坐标枚举方法
**文件**: `byog/Core/SquareRoom.java`
- 添加 `getFloorPositions()` 返回房间内部所有地板坐标列表（排除墙壁边框）
- 用于随机选择传送方块位置

### 步骤5：RoomGraph 添加拓扑最远房间计算
**文件**: `byog/Core/RoomGraph.java`
- 添加 `Room findFarthestRoom(Position fromPos, List<SquareRoom> rooms)`
  - 先在 rooms 中找到包含 fromPos 的房间（起始房间）
  - 在 MST 树上做 Dijkstra/BFS，计算从起始房间到所有房间的图距离
  - 返回图距离最大的房间

### 步骤6：参数化敌人生成数量
**文件**: `byog/Core/Enemy.java`
- `spawnEnemies()` 新增参数 `int extraCount`
- 总数量 = 3 + extraCount（即 floorLevel - 1）

### 步骤7：修改 Game.java 核心层数逻辑
**文件**: `byog/Core/Game.java`

新增字段：
- `private int floorLevel = 1`
- `private WorldGenResult currentGenResult`（保存当前层房间列表）

修改/新增方法：
- `generateWorld(seed)` → 适配 WorldGenResult 返回类型
- 新增 `placeStairs(WorldGenResult result, Position playerPos)`：
  1. 用 RoomGraph 找离 playerPos 图距离最远的房间
  2. 获取该房间的 `getFloorPositions()`
  3. 用种子随机选一个位置，放置 `Tileset.STAIRS`
- 新增 `nextFloor()`：
  1. `floorLevel++`
  2. 重新生成世界（种子 = seed + "_F" + floorLevel）
  3. 清除实体管理器
  4. 重生玩家
  5. 生成敌人（数量 = 基础 + floorLevel - 1）
  6. 放置传送方块
  7. 重置 frameCounter
- 在 `movePlayer()` 后检测传送：
  - 移动成功 → 检查玩家新位置瓦片 == `Tileset.STAIRS` → 调用 `nextFloor()`
- UI 增加楼层显示 `Floor: {floorLevel}`

### 步骤8：存档支持
**文件**: `byog/Core/GameSaveData.java`
- `extraData.put("floorLevel", floorLevel)`
- 读档恢复 floorLevel（旧存档默认1）

## 潜在依赖

1. **种子确定性**：每层种子 = 原始种子 + "_F" + 层数，确保同种子同层相同
2. **传送方块可通行**：`Tileset.STAIRS` 需被 `Entity.canStandOn()` 识别为可通行（不是 WALL/NOTHING）——当前逻辑已满足，因为 STAIRS 不是 WALL 也不是 NOTHING
3. **存档兼容性**：旧存档无 floorLevel 时默认1

## 风险处理

1. **玩家生成在传送方块上**：可能性极低（传送方块在最远房间，玩家随机位置），但可在放置前检查避免
2. **图距离计算时玩家不在任何房间**：玩家初始生成使用 `initEntity`，只在 FLOOR 上放置。若恰好生成在走廊而非房间内 → fallback 到曼哈顿距离选最远房间

## 验证计划

1. 编译通过
2. 新游戏进入显示 "Floor: 1"
3. 找到 `>` 传送方块并踩上进入第2层
4. 第2层敌人比第1层多1个
5. 重新输入同一种子，每层地图与之前相同
6. 存档/读档后楼层正确恢复
7. 传送方块在离玩家最远的房间内
