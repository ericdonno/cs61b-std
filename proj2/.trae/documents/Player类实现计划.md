# Player 类实现计划

## 概述
为 BYOG (Build Your Own Game) 项目实现 Player 类，用于表示游戏世界中的玩家角色。

## 现有代码分析
- `Player.java` 目前只有 `position` 字段和空构造函数
- `Game.java` 中有玩家输入处理框架（wrdGenerated 后处理移动）
- 使用 `Position` 类表示坐标 (x, y)
- `Tileset.PLAYER` 定义了玩家图标为 '@'

## Player 类功能规划

### 1. 属性
- `Position position`: 玩家在游戏世界中的坐标
- 可考虑添加 `TETile tile`: 玩家显示的贴片（使用 Tileset.PLAYER）

### 2. 构造函数
- `Player()`: 默认构造函数，初始化位置为 (0, 0)
- `Player(Position position)`: 根据指定位置创建玩家

### 3. 核心方法
- `getPosition()`: 返回玩家当前位置
- `setPosition(Position p)`: 设置玩家位置
- `move(Direction direction)`: 根据方向移动玩家（需要碰撞检测）

### 4. 移动方法（可选实现）
- `moveUp()`: 向上移动
- `moveDown()`: 向下移动
- `moveLeft()`: 向左移动
- `moveRight()`: 向右移动

### 5. 碰撞检测方法
- `canMoveTo(Position p, TETile[][] world)`: 检查是否可以移动到指定位置（需要检查是否为 FLOOR 或非 WALL）

## 实现步骤

1. **修改 Player.java**
   - 添加必要的 import 语句
   - 实现构造函数
   - 实现 getter/setter 方法
   - 实现移动相关方法
   - 实现碰撞检测逻辑

2. **定义 Direction 枚举（可选）**
   - UP, DOWN, LEFT, RIGHT

## 注意事项
- 移动时需要检查目标位置是否为有效地板（不是 WALL 或 NOTHING）
- 坐标系统中 y 向上增加（参考 TETile.toString() 的实现）
- Player 应该与 Game 类协同工作，Game 处理输入，Player 处理位置逻辑