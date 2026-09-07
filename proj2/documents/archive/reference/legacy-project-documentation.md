# BYOG 项目文档

## 项目概述

本项目是一个基于Java的游戏开发项目，包含世界生成、地图渲染、游戏逻辑等功能。项目结构清晰，分为多个模块，每个模块负责不同的功能领域。

---

## 目录

- [Core 模块](#core-模块)
- [Helper 模块](#helper-模块)
- [TileEngine 模块](#tileengine-模块)
- [Lab5 模块](#lab5-模块)
- [Lab6 模块](#lab6-模块)
- [SaveDemo 模块](#savedemo-模块)

---

## Core 模块

核心游戏逻辑模块，包含游戏主类、世界生成器、房间和走廊等核心组件。

### 1. Game 类

完整路径: `byog.Core.Game`

所属模块: Core

功能描述: 游戏主类，负责游戏的整体流程控制，包括键盘交互和字符串输入处理。

#### 方法列表

##### playWithKeyboard()

- 参数: 无
- 返回值: `void`
- 功能描述: 用于启动键盘交互模式的游戏，游戏从主菜单开始
- 使用示例:
```java
Game game = new Game();
game.playWithKeyboard();
```

##### playWithInputString(String input)

- 参数: 
  - `input`: String - 输入字符串，例如 "n123sswwdasdassadwas", "n123sss:q", "lwww"
- 返回值: `TETile[][]` - 表示世界状态的二维瓦片数组
- 功能描述: 用于自动评分和测试游戏代码。输入字符串模拟用户在键盘模式下输入的字符序列。如果字符串以 ":q" 结尾，游戏会保存状态
- 使用示例:
```java
Game game = new Game();
TETile[][] worldState = game.playWithInputString("n123sss:q");
```

```plain text
┌─────────────────────────────────────────────────────────────┐
│                    输入字符串结构                            │
├─────────────────────────────────────────────────────────────┤
│  N  12345  S  wwassd  :Q                                   │
│  │   │     │    │      │                                   │
│  │   │     │    │      └── 保存退出命令                      │
│  │   │     │    └── 玩家移动命令 (WASD)                      │
│  │   │     └── 种子结束标记                                  │
│  │   └── 种子值 (任意长度的数字)                             │
│  └── 新游戏命令 (New Game)                                  │
└─────────────────────────────────────────────────────────────┘
```

#### 常量

- `WIDTH`: `int` = 80 - 游戏世界的宽度
- `HEIGHT`: `int` = 30 - 游戏世界的高度

---

### 2. Main 类

完整路径: `byog.Core.Main`

所属模块: Core

功能描述: 程序的主入口点，负责解析命令行参数并启动游戏。

#### 方法列表

##### main(String[] args)

- 参数: 
  - `args`: String[] - 命令行参数数组
- 返回值: `void`
- 功能描述: 程序主入口，根据命令行参数决定启动键盘模式还是字符串输入模式
- 使用示例:
```java
// 命令行调用
java byog.Core.Main n123sss:q
```

---

### 3. WorldGenerator 类

完整路径: `byog.Core.WorldGenerator`

所属模块: Core

功能描述: 世界生成器，负责生成随机房间和走廊的游戏世界。

#### 方法列表

##### RandomSquareRoomWrd(TETile[][] world, String seed)

- 参数: 
  - `world`: TETile[][] - 二维瓦片数组，表示游戏世界
  - `seed`: String - 随机种子字符串
- 返回值: `TETile[][]` - 生成的世界数组
- 功能描述: 根据给定的种子生成包含随机方形房间和走廊的世界。使用偏置分布和泊松分布来控制房间数量和走廊选择
- 使用示例:
```java
TETile[][] world = new TETile[80][30];
world = WorldGenerator.RandomSquareRoomWrd(world, "12345");
```

##### ifIsNothingToWall(TETile[][] world, Position p)

- 参数: 
  - `world`: TETile[][] - 二维瓦片数组
  - `p`: Position - 位置坐标
- 返回值: `void`
- 功能描述: 辅助方法，如果指定位置是空白（NOTHING），则将其转换为墙壁（WALL）

---

### 4. Room 接口

完整路径: `byog.Core.Room`

所属模块: Core

功能描述: 房间接口，定义了房间的基本属性和行为。

#### 方法列表

##### getSize()

- 参数: 无
- 返回值: `int` - 房间的大小
- 功能描述: 返回房间的大小

##### getPosition()

- 参数: 无
- 返回值: `Position` - 房间的位置
- 功能描述: 返回房间的位置坐标

##### isIntersect(SquareRoom other)

- 参数: 
  - `other`: SquareRoom - 另一个方形房间
- 返回值: `boolean` - 如果相交返回 true，否则返回 false
- 功能描述: 检查当前房间是否与另一个方形房间相交

##### isWithinBounds(TETile[][] world)

- 参数: 
  - `world`: TETile[][] - 二维瓦片数组表示的世界
- 返回值: `boolean` - 如果在边界内返回 true，否则返回 false
- 功能描述: 检查房间是否在给定世界的边界内

##### addSelf(TETile[][] world)

- 参数: 
  - `world`: TETile[][] - 二维瓦片数组表示的世界
- 返回值: `boolean` - 如果成功添加返回 true，否则返回 false
- 功能描述: 将房间添加到给定的世界中

##### getShape()

- 参数: 无
- 返回值: `String` - 房间的形状描述
- 功能描述: 返回房间的形状描述

##### distanceTo(Room other)

- 参数: 
  - `other`: Room - 另一个房间
- 返回值: `double` - 两个房间之间的距离
- 功能描述: 计算到另一个房间的欧几里得距离（默认方法）

---

### 5. SquareRoom 类

完整路径: `byog.Core.SquareRoom`

所属模块: Core

功能描述: 方形房间实现类，由墙壁包围的地板结构。

#### 构造方法

##### SquareRoom(Position p, int size)

- 参数: 
  - `p`: Position - 房间的位置（右下角）
  - `size`: int - 房间的大小（包括墙壁）
- 功能描述: 创建一个指定位置和大小方形房间

#### 方法列表

##### getSize()

- 参数: 无
- 返回值: `int` - 房间的大小
- 功能描述: 返回房间的大小（包括墙壁）

##### getPosition()

- 参数: 无
- 返回值: `Position` - 房间的位置
- 功能描述: 返回房间的位置坐标

##### isIntersect(SquareRoom other)

- 参数: 
  - `other`: SquareRoom - 另一个方形房间
- 返回值: `boolean` - 如果相交返回 true，否则返回 false
- 功能描述: 检查当前房间是否与另一个方形房间相交，通过检查水平和垂直投影是否重叠来判断

##### isWithinBounds(TETile[][] world)

- 参数: 
  - `world`: TETile[][] - 二维瓦片数组表示的世界
- 返回值: `boolean` - 如果在边界内返回 true，否则返回 false
- 功能描述: 检查房间的右上角是否在世界边界内

##### addSelf(TETile[][] world)

- 参数: 
  - `world`: TETile[][] - 二维瓦片数组表示的世界
- 返回值: `boolean` - 如果成功添加返回 true，否则返回 false
- 功能描述: 将房间添加到世界中，边界设置为墙壁，内部设置为地板

##### getShape()

- 参数: 无
- 返回值: `String` - "SQUARE"
- 功能描述: 返回房间的形状描述

---

### 6. Hall 类

完整路径: `byog.Core.Hall`

所属模块: Core

功能描述: 走廊类，连接两个房间，在绘制时确定具体形状。

#### 构造方法

##### Hall(Room room1, Room room2)

- 参数: 
  - `room1`: Room - 第一个房间
  - `room2`: Room - 第二个房间
- 功能描述: 创建连接两个房间的走廊

#### 方法列表

##### getScale()

- 参数: 无
- 返回值: `double` - 走廊的规模
- 功能描述: 返回走廊的规模，由两个房间之间的距离描述

##### addSelf(TETile[][] world, Random random)

- 参数: 
  - `world`: TETile[][] - 二维瓦片数组表示的世界
  - `random`: Random - 随机数生成器
- 返回值: `boolean` - 如果成功添加返回 true，否则返回 false
- 功能描述: 将走廊添加到世界中，根据两个房间的相对位置决定走廊的形状（直线或带拐弯）

##### generateStraightPath(TETile[][] world, Position start, Position end, boolean pathIsVertical)

- 参数: 
  - `world`: TETile[][] - 二维瓦片数组
  - `start`: Position - 起始位置
  - `end`: Position - 结束位置
  - `pathIsVertical`: boolean - 是否为垂直走廊
- 返回值: `void`
- 功能描述: 生成直线走廊

##### generatePathWithBend(TETile[][] world, Position start, Position end, boolean isPathStartVertically)

- 参数: 
  - `world`: TETile[][] - 二维瓦片数组
  - `start`: Position - 起始位置
  - `end`: Position - 结束位置
  - `isPathStartVertically`: boolean - 路径是否从垂直方向开始
- 返回值: `void`
- 功能描述: 生成带拐弯的走廊

---

### 7. RoomGraph 类

完整路径: `byog.Core.RoomGraph`

所属模块: Core

功能描述: 房间图数据结构，包含生成走廊的方法。

#### 构造方法

##### RoomGraph(List<? extends Room> allRoom)

- 参数: 
  - `allRoom`: List<? extends Room> - 所有房间的列表
- 功能描述: 根据房间列表创建房间图，计算所有房间之间的距离并添加边

#### 方法列表

##### mstHalls()

- 参数: 无
- 返回值: `List<Hall>` - 最小生成树的走廊列表
- 功能描述: 返回最小生成树的走廊集合

##### mstAndOtherHalls()

- 参数: 无
- 返回值: `List<Hall>[]` - 包含两个列表的数组，第一个是MST走廊，第二个是其他走廊
- 功能描述: 同时返回最小生成树走廊和其他走廊

##### edgeToHall(MatrixGraph.Edge<Room> edge)

- 参数: 
  - `edge`: MatrixGraph.Edge<Room> - 图的边
- 返回值: `Hall` - 对应的走廊
- 功能描述: 将图的边转换为走廊对象

##### toString()

- 参数: 无
- 返回值: `String` - 图的字符串表示
- 功能描述: 返回图的字符串表示，用于测试

---

### 8. RandomUtils 类

完整路径: `byog.Common.RandomUtils`

所属模块: Common

功能描述: 随机数生成工具库，提供多种分布的随机数生成方法。

#### 方法列表

##### uniform(Random random)

- 参数: 
  - `random`: Random - 随机数生成器
- 返回值: `double` - [0, 1) 范围内的随机实数
- 功能描述: 返回均匀分布在 [0, 1) 范围内的随机实数

##### uniform(Random random, int n)

- 参数: 
  - `random`: Random - 随机数生成器
  - `n`: int - 可能的整数数量
- 返回值: `int` - [0, n) 范围内的随机整数
- 功能描述: 返回均匀分布在 [0, n) 范围内的随机整数

##### uniform(Random random, long n)

- 参数: 
  - `random`: Random - 随机数生成器
  - `n`: long - 可能的长整数数量
- 返回值: `long` - [0, n) 范围内的随机长整数
- 功能描述: 返回均匀分布在 [0, n) 范围内的随机长整数

##### uniform(Random random, int a, int b)

- 参数: 
  - `random`: Random - 随机数生成器
  - `a`: int - 左端点
  - `b`: int - 右端点
- 返回值: `int` - [a, b) 范围内的随机整数
- 功能描述: 返回均匀分布在 [a, b) 范围内的随机整数

##### uniform(Random random, double a, double b)

- 参数: 
  - `random`: Random - 随机数生成器
  - `a`: double - 左端点
  - `b`: double - 右端点
- 返回值: `double` - [a, b) 范围内的随机实数
- 功能描述: 返回均匀分布在 [a, b) 范围内的随机实数

##### biasUniform(Random random, double a, double b, double bias)

- 参数: 
  - `random`: Random - 随机数生成器
  - `a`: double - 左端点
  - `b`: double - 右端点
  - `bias`: double - 偏置系数（>1偏向a，<1偏向b，=1为均匀分布）
- 返回值: `double` - 带偏置的随机实数
- 功能描述: 返回带偏置的均匀分布随机数

##### bernoulli(Random random, double p)

- 参数: 
  - `random`: Random - 随机数生成器
  - `p`: double - 成功概率
- 返回值: `boolean` - 以概率 p 返回 true
- 功能描述: 返回伯努利分布的随机布尔值

##### bernoulli(Random random)

- 参数: 
  - `random`: Random - 随机数生成器
- 返回值: `boolean` - 以概率 1/2 返回 true
- 功能描述: 返回伯努利分布的随机布尔值（成功概率为 1/2）

##### gaussian(Random random)

- 参数: 
  - `random`: Random - 随机数生成器
- 返回值: `double` - 标准高斯分布的随机实数
- 功能描述: 返回标准高斯分布（均值0，标准差1）的随机实数

##### gaussian(Random random, double mu, double sigma)

- 参数: 
  - `random`: Random - 随机数生成器
  - `mu`: double - 均值
  - `sigma`: double - 标准差
- 返回值: `double` - 高斯分布的随机实数
- 功能描述: 返回指定均值和标准差的高斯分布随机实数

##### geometric(Random random, double p)

- 参数: 
  - `random`: Random - 随机数生成器
  - `p`: double - 成功概率
- 返回值: `int` - 几何分布的随机整数
- 功能描述: 返回几何分布的随机整数

##### poisson(Random random, double lambda)

- 参数: 
  - `random`: Random - 随机数生成器
  - `lambda`: double - 均值
- 返回值: `int` - 泊松分布的随机整数
- 功能描述: 返回泊松分布的随机整数

##### pareto(Random random)

- 参数: 
  - `random`: Random - 随机数生成器
- 返回值: `double` - 标准帕累托分布的随机实数
- 功能描述: 返回标准帕累托分布的随机实数

##### pareto(Random random, double alpha)

- 参数: 
  - `random`: Random - 随机数生成器
  - `alpha`: double - 形状参数
- 返回值: `double` - 帕累托分布的随机实数
- 功能描述: 返回指定形状参数的帕累托分布随机实数

##### cauchy(Random random)

- 参数: 
  - `random`: Random - 随机数生成器
- 返回值: `double` - 柯西分布的随机实数
- 功能描述: 返回柯西分布的随机实数

##### discrete(Random random, double[] probabilities)

- 参数: 
  - `random`: Random - 随机数生成器
  - `probabilities`: double[] - 每个整数的出现概率数组
- 返回值: `int` - 离散分布的随机整数
- 功能描述: 返回指定离散分布的随机整数

##### discrete(Random random, int[] frequencies)

- 参数: 
  - `random`: Random - 随机数生成器
  - `frequencies`: int[] - 每个整数的频率数组
- 返回值: `int` - 离散分布的随机整数
- 功能描述: 返回频率比例的离散分布随机整数

##### exp(Random random, double lambda)

- 参数: 
  - `random`: Random - 随机数生成器
  - `lambda`: double - 指数分布的速率参数
- 返回值: `double` - 指数分布的随机实数
- 功能描述: 返回指数分布的随机实数

##### shuffle(Random random, Object[] a)

- 参数: 
  - `random`: Random - 随机数生成器
  - `a`: Object[] - 要打乱的数组
- 返回值: `void`
- 功能描述: 随机打乱数组元素的顺序

##### shuffle(Random random, double[] a)

- 参数: 
  - `random`: Random - 随机数生成器
  - `a`: double[] - 要打乱的数组
- 返回值: `void`
- 功能描述: 随机打乱double数组元素的顺序

##### shuffle(Random random, int[] a)

- 参数: 
  - `random`: Random - 随机数生成器
  - `a`: int[] - 要打乱的数组
- 返回值: `void`
- 功能描述: 随机打乱int数组元素的顺序

##### shuffle(Random random, char[] a)

- 参数: 
  - `random`: Random - 随机数生成器
  - `a`: char[] - 要打乱的数组
- 返回值: `void`
- 功能描述: 随机打乱char数组元素的顺序

##### shuffle(Random random, Object[] a, int lo, int hi)

- 参数: 
  - `random`: Random - 随机数生成器
  - `a`: Object[] - 要打乱的数组
  - `lo`: int - 左端点（包含）
  - `hi`: int - 右端点（不包含）
- 返回值: `void`
- 功能描述: 随机打乱数组子区间元素的顺序

##### permutation(Random random, int n)

- 参数: 
  - `random`: Random - 随机数生成器
  - `n`: int - 元素数量
- 返回值: `int[]` - 随机排列数组
- 功能描述: 返回 n 个元素的随机排列

##### permutation(Random random, int n, int k)

- 参数: 
  - `random`: Random - 随机数生成器
  - `n`: int - 元素总数
  - `k`: int - 要选择的元素数量
- 返回值: `int[]` - 随机排列数组
- 功能描述: 返回 n 个元素中 k 个元素的随机排列

---

### 9. MathTest 类

完整路径: `byog.Core.MathTest`

所属模块: Core

功能描述: 数学工具和世界生成器的测试类。

#### 方法列表

##### biasUniformDistributionTest()

- 参数: 无
- 返回值: `void`
- 功能描述: 测试偏置均匀分布的频率分布

##### squareRoomTest()

- 参数: 无
- 返回值: `void`
- 功能描述: 测试方形房间是否在边界内

##### testRandomSquareRoomWrd()

- 参数: 无
- 返回值: `void`
- 功能描述: 测试随机方形房间世界的生成，计算房间存活率

##### poissonDistributionTest()

- 参数: 无
- 返回值: `void`
- 功能描述: 测试泊松分布的频率分布和期望值

##### temptest()

- 参数: 无
- 返回值: `void`
- 功能描述: 临时测试方法

---

## Helper 模块

辅助工具模块，提供日志、数学计算、数据结构等辅助功能。

### 1. Logger 类

完整路径: `byog.Helper.Logger`

所属模块: Helper

功能描述: 日志记录工具类，提供不同级别的日志输出功能。

#### 方法列表

##### debug(String format, Object... args)

- 参数: 
  - `format`: String - 格式字符串
  - `args`: Object... - 格式参数
- 返回值: `void`
- 功能描述: 输出调试级别的日志信息

##### info(String format, Object... args)

- 参数: 
  - `format`: String - 格式字符串
  - `args`: Object... - 格式参数
- 返回值: `void`
- 功能描述: 输出信息级别的日志信息

##### error(String format, Object... args)

- 参数: 
  - `format`: String - 格式字符串
  - `args`: Object... - 格式参数
- 返回值: `void`
- 功能描述: 输出错误级别的日志信息

##### section(String title)

- 参数: 
  - `title`: String - 章节标题
- 返回值: `void`
- 功能描述: 输出章节分隔线和标题

##### subsection(String title)

- 参数: 
  - `title`: String - 子章节标题
- 返回值: `void`
- 功能描述: 输出子章节分隔线和标题

---

### 2. MathHelper 类

完整路径: `byog.Helper.MathHelper`

所属模块: Helper

功能描述: 数学辅助工具类，提供数学计算相关的辅助方法。

#### 方法列表

##### findMiddleTwo(int a, int b, int c, int d)

- 参数: 
  - `a`: int - 第一个数
  - `b`: int - 第二个数
  - `c`: int - 第三个数
  - `d`: int - 第四个数
- 返回值: `int[]` - 包含第二小和第二大数的数组 {b, c}
- 功能描述: 返回四个数中第二小和第二大的两个数

##### poissonDistributedIndecies(Random random, int size, int needed)

- 参数: 
  - `random`: Random - 随机数生成器
  - `size`: int - 索引范围大小
  - `needed`: int - 需要的索引数量
- 返回值: `int[]` - 泊松分布的索引数组
- 功能描述: 返回泊松分布的索引数组，索引可能重复，数组为均匀分布

---

### 3. MatrixGraph 类

完整路径: `byog.Helper.MatrixGraph`

所属模块: Helper

功能描述: 矩阵图数据结构，使用邻接矩阵表示图，支持最小生成树算法。

#### 构造方法

##### MatrixGraph(int capacity)

- 参数: 
  - `capacity`: int - 图的容量
- 功能描述: 创建指定容量的矩阵图

#### 方法列表

##### toString()

- 参数: 无
- 返回值: `String` - 图的字符串表示
- 功能描述: 返回图的字符串表示，用于测试

##### findNodeIndex(T node)

- 参数: 
  - `node`: T - 节点对象
- 返回值: `int` - 节点的索引
- 功能描述: 查找节点在图中的索引

##### getNodeCount()

- 参数: 无
- 返回值: `int` - 图中的节点数
- 功能描述: 获取图中的节点数

##### getEdgeCount()

- 参数: 无
- 返回值: `int` - 图中的边数
- 功能描述: 获取图中的边数，时间复杂度 O(size^2)

##### getAllEdges()

- 参数: 无
- 返回值: `List<Edge<T>>` - 所有边的列表
- 功能描述: 获取图中的所有边，时间复杂度 O(size^2)

##### addNode(T node)

- 参数: 
  - `node`: T - 要添加的节点
- 返回值: `void`
- 功能描述: 向图中添加节点

##### addEdge(T from, T to, double weight)

- 参数: 
  - `from`: T - 起始节点
  - `to`: T - 目标节点
  - `weight`: double - 边的权重
- 返回值: `void`
- 功能描述: 向图中添加带权重的边

##### getMinimumSpanningTree(T startNode)

- 参数: 
  - `startNode`: T - 起始节点
- 返回值: `List<Edge<T>>` - 最小生成树的边集合
- 功能描述: 使用Prim算法获取最小生成树的边集合

##### addEdgesToQueue(int nodeIndex, PriorityQueue<Edge<T>> edgeQueue)

- 参数: 
  - `nodeIndex`: int - 节点索引
  - `edgeQueue`: PriorityQueue<Edge<T>> - 边的优先队列
- 返回值: `void`
- 功能描述: 将节点的邻接边加入优先队列

#### 内部类

##### Edge<T>

- 字段: 
  - `from`: T - 起始节点
  - `to`: T - 目标节点
  - `weight`: double - 边的权重
- 构造方法: `Edge(T from, T to, double weight)`
- 功能描述: 表示两个节点之间的连接边

---

### 4. ListGraph 类

完整路径: `byog.Helper.ListGraph`

所属模块: Helper

功能描述: 邻接表图数据结构，使用邻接表表示图，支持最小生成树算法。

#### 构造方法

##### ListGraph()

- 参数: 无
- 功能描述: 创建空的邻接表图

#### 方法列表

##### addNode(T node)

- 参数: 
  - `node`: T - 要添加的节点
- 返回值: `void`
- 功能描述: 向图中添加节点

##### addEdge(T from, T to, double weight)

- 参数: 
  - `from`: T - 起始节点
  - `to`: T - 目标节点
  - `weight`: double - 边的权重
- 返回值: `void`
- 功能描述: 向图中添加带权重的边（无向图）

##### getNodes()

- 参数: 无
- 返回值: `Set<T>` - 节点集合
- 功能描述: 获取图中的所有节点

##### getMinimumSpanningTree(T startNode)

- 参数: 
  - `startNode`: T - 起始节点
- 返回值: `List<Edge<T>>` - 最小生成树的边集合
- 功能描述: 使用Prim算法获取最小生成树的边集合

#### 内部类

##### Edge<T>

- 字段: 
  - `from`: T - 起始节点
  - `to`: T - 目标节点
  - `weight`: double - 边的权重
- 构造方法: `Edge(T from, T to, double weight)`
- 功能描述: 表示两个节点之间的连接边

---

### 5. ArrayDeque 类

完整路径: `byog.Helper.ArrayDeque`

所属模块: Helper

功能描述: 双端队列实现类，支持在队列两端添加和删除元素。

#### 构造方法

##### ArrayDeque()

- 参数: 无
- 功能描述: 创建空的双端队列

#### 方法列表

##### resize(int capacity)

- 参数: 
  - `capacity`: int - 新的容量
- 返回值: `void`
- 功能描述: 调整队列的容量

##### size()

- 参数: 无
- 返回值: `int` - 队列的大小
- 功能描述: 返回队列中元素的数量

##### isEmpty()

- 参数: 无
- 返回值: `boolean` - 如果为空返回 true
- 功能描述: 检查队列是否为空

##### isFull()

- 参数: 无
- 返回值: `boolean` - 如果已满返回 true
- 功能描述: 检查队列是否已满

##### addFirst(T item)

- 参数: 
  - `item`: T - 要添加的元素
- 返回值: `void`
- 功能描述: 在队列前端添加元素

##### addLast(T item)

- 参数: 
  - `item`: T - 要添加的元素
- 返回值: `void`
- 功能描述: 在队列后端添加元素

##### removeFirst()

- 参数: 无
- 返回值: `T` - 移除的元素
- 功能描述: 移除并返回队列前端的元素

##### removeLast()

- 参数: 无
- 返回值: `T` - 移除的元素
- 功能描述: 移除并返回队列后端的元素

##### get(int index)

- 参数: 
  - `index`: int - 索引位置
- 返回值: `T` - 对应位置的元素
- 功能描述: 获取指定索引位置的元素

##### getLast()

- 参数: 无
- 返回值: `T` - 最后一个元素
- 功能描述: 获取队列的最后一个元素

##### getFirst()

- 参数: 无
- 返回值: `T` - 第一个元素
- 功能描述: 获取队列的第一个元素

##### printDeque()

- 参数: 无
- 返回值: `void`
- 功能描述: 打印队列中的所有元素

##### iterator()

- 参数: 无
- 返回值: `Iterator<T>` - 迭代器
- 功能描述: 返回队列的迭代器

---

### 6. ArrayDequeTest 类

完整路径: `byog.Helper.ArrayDequeTest`

所属模块: Helper

功能描述: 双端队列的测试类。

#### 方法列表

##### addRemovePrintTest()

- 参数: 无
- 返回值: `void`
- 功能描述: 测试双端队列的添加、删除和打印功能

##### main(String[] args)

- 参数: 
  - `args`: String[] - 命令行参数
- 返回值: `void`
- 功能描述: 测试程序的主入口

---

## TileEngine 模块

瓦片引擎模块，负责瓦片的定义、渲染和显示。

### 1. TETile 类

完整路径: `byog.TileEngine.TETile`

所属模块: TileEngine

功能描述: 瓦片对象，表示游戏世界中的单个瓦片，包含字符、颜色和描述等信息。

#### 构造方法

##### TETile(char character, Color textColor, Color backgroundColor, String description, String filepath)

- 参数: 
  - `character`: char - 显示的字符
  - `textColor`: Color - 字符的颜色
  - `backgroundColor`: Color - 背景颜色
  - `description`: String - 瓦片的描述
  - `filepath`: String - 图像文件的路径（16x16）
- 功能描述: 创建完整的瓦片对象

##### TETile(char character, Color textColor, Color backgroundColor, String description)

- 参数: 
  - `character`: char - 显示的字符
  - `textColor`: Color - 字符的颜色
  - `backgroundColor`: Color - 背景颜色
  - `description`: String - 瓦片的描述
- 功能描述: 创建不带图像文件的瓦片对象

##### TETile(TETile t, Color textColor)

- 参数: 
  - `t`: TETile - 要复制的瓦片
  - `textColor`: Color - 新的前景颜色
- 功能描述: 创建瓦片的副本，使用新的前景颜色

#### 方法列表

##### draw(double x, double y)

- 参数: 
  - `x`: double - x坐标
  - `y`: double - y坐标
- 返回值: `void`
- 功能描述: 在指定位置绘制瓦片，如果有图像文件则绘制图像，否则绘制字符

##### character()

- 参数: 无
- 返回值: `char` - 瓦片的字符表示
- 功能描述: 返回瓦片的字符表示

##### description()

- 参数: 无
- 返回值: `String` - 瓦片的描述
- 功能描述: 返回瓦片的描述信息

##### colorVariant(TETile t, int dr, int dg, int db, Random r)

- 参数: 
  - `t`: TETile - 要复制的瓦片
  - `dr`: int - 红色值的最大差异
  - `dg`: int - 绿色值的最大差异
  - `db`: int - 蓝色值的最大差异
  - `r`: Random - 随机数生成器
- 返回值: `TETile` - 颜色变体的瓦片
- 功能描述: 创建瓦片的副本，颜色略有变化

##### toString(TETile[][] world)

- 参数: 
  - `world`: TETile[][] - 二维瓦片数组
- 返回值: `String` - 世界的字符串表示
- 功能描述: 将二维瓦片数组转换为字符串，用于调试

##### copyOf(TETile[][] tiles)

- 参数: 
  - `tiles`: TETile[][] - 要复制的二维瓦片数组
- 返回值: `TETile[][]` - 复制的二维瓦片数组
- 功能描述: 创建二维瓦片数组的副本

##### equals(Object x)

- 参数: 
  - `x`: Object - 要比较的对象
- 返回值: `boolean` - 如果相等返回 true
- 功能描述: 比较两个瓦片是否相等

##### hashCode()

- 参数: 无
- 返回值: `int` - 哈希码
- 功能描述: 返回瓦片的哈希码

---

### 2. TERenderer 类

完整路径: `byog.TileEngine.TERenderer`

所属模块: TileEngine

功能描述: 瓦片渲染工具类，负责初始化画布和渲染瓦片数组。

#### 方法列表

##### initialize(int w, int h, int xOff, int yOff)

- 参数: 
  - `w`: int - 窗口宽度（瓦片数）
  - `h`: int - 窗口高度（瓦片数）
  - `xOff`: int - x偏移量
  - `yOff`: int - y偏移量
- 返回值: `void`
- 功能描述: 初始化StdDraw参数，设置画布大小和偏移量

##### initialize(int w, int h)

- 参数: 
  - `w`: int - 窗口宽度（瓦片数）
  - `h`: int - 窗口高度（瓦片数）
- 返回值: `void`
- 功能描述: 初始化StdDraw参数，设置画布大小

##### renderFrame(TETile[][] world)

- 参数: 
  - `world`: TETile[][] - 二维瓦片数组
- 返回值: `void`
- 功能描述: 将二维瓦片数组渲染到屏幕上，从xOffset和yOffset位置开始绘制

---

### 3. Tileset 类

完整路径: `byog.TileEngine.Tileset`

所属模块: TileEngine

功能描述: 瓦片常量类，包含预定义的瓦片对象，避免在不同代码部分重复创建相同的瓦片。

#### 常量字段

- `PLAYER`: TETile - 玩家瓦片（'@'，白色，黑色背景）
- `WALL`: TETile - 墙壁瓦片（'#'，棕红色，深灰色背景）
- `FLOOR`: TETile - 地板瓦片（'·'，绿色，黑色背景）
- `NOTHING`: TETile - 空白瓦片（' '，黑色，黑色背景）
- `GRASS`: TETile - 草地瓦片（'"'，绿色，黑色背景）
- `WATER`: TETile - 水瓦片（'≈'，蓝色，黑色背景）
- `FLOWER`: TETile - 花朵瓦片（'❀'，紫色，粉色背景）
- `LOCKED_DOOR`: TETile - 锁门瓦片（'█'，橙色，黑色背景）
- `UNLOCKED_DOOR`: TETile - 开门瓦片（'▢'，橙色，黑色背景）
- `SAND`: TETile - 沙地瓦片（'▒'，黄色，黑色背景）
- `MOUNTAIN`: TETile - 山脉瓦片（'▲'，灰色，黑色背景）
- `TREE`: TETile - 树木瓦片（'♠'，绿色，黑色背景）

#### 方法列表

##### random()

- 参数: 无
- 返回值: `TETile` - 随机瓦片（不包括NOTHING）
- 功能描述: 返回一个随机瓦片（不包括NOTHING）

---

## Lab5 模块

实验室5模块，包含六边形世界和坐标系统相关的类。

### 1. Position 类

完整路径: `byog.lab5.Position`

所属模块: Lab5

功能描述: 位置类，描述二维数组中的位置坐标。

#### 构造方法

##### Position(int x, int y)

- 参数: 
  - `x`: int - x坐标
  - `y`: int - y坐标
- 功能描述: 创建位置对象

#### 字段

- `x`: int - x坐标
- `y`: int - y坐标

#### 方法列表

##### toString()

- 参数: 无
- 返回值: `String` - 位置的字符串表示
- 功能描述: 返回位置的字符串表示 "(x, y)"

---

### 2. Hexagon 类

完整路径: `byog.lab5.Hexagon`

所属模块: Lab5

功能描述: 六边形类，基本的生物群落单元。

#### 构造方法

##### Hexagon(int size, TETile style)

- 参数: 
  - `size`: int - 六边形的大小
  - `style`: TETile - 六边形的样式
- 功能描述: 创建未知位置的六边形

##### Hexagon(Position p, int size, TETile style)

- 参数: 
  - `p`: Position - 六边形的位置
  - `size`: int - 六边形的大小
  - `style`: TETile - 六边形的样式
- 功能描述: 创建指定位置的六边形

#### 方法列表

##### addSelf(TETile[][] world)

- 参数: 
  - `world`: TETile[][] - 二维瓦片数组
- 返回值: `void`
- 功能描述: 将六边形添加到世界中

##### setPosition(Position p)

- 参数: 
  - `p`: Position - 新的位置
- 返回值: `void`
- 功能描述: 设置六边形的位置

##### getPosition()

- 参数: 无
- 返回值: `Position` - 六边形的位置
- 功能描述: 获取六边形的位置

##### getStyle()

- 参数: 无
- 返回值: `String` - 六边形的样式描述
- 功能描述: 获取六边形的样式描述

##### addHexagon(TETile[][] world, Position p, int size, TETile style)

- 参数: 
  - `world`: TETile[][] - 二维瓦片数组
  - `p`: Position - 六边形的位置
  - `size`: int - 六边形的大小
  - `style`: TETile - 六边形的样式
- 返回值: `void`
- 功能描述: 静态方法，将六边形添加到世界中的指定位置

---

### 3. HexWorld 类

完整路径: `byog.lab5.HexWorld`

所属模块: Lab5

功能描述: 六边形世界演示类，绘制包含六边形区域的世界。

#### 常量

- `WIDTH`: int = 80 - 世界宽度
- `HEIGHT`: int = 80 - 世界高度

#### 方法列表

##### main(String[] args)

- 参数: 
  - `args`: String[] - 命令行参数
- 返回值: `void`
- 功能描述: 主方法，初始化瓦片渲染引擎并绘制六边形世界

---

### 4. HexCoorSys 类

完整路径: `byog.lab5.HexCoorSys`

所属模块: Lab5

功能描述: 六边形坐标系统类，管理六边形的位置和坐标。

#### 构造方法

##### HexCoorSys(Position p, int size, TETile style)

- 参数: 
  - `p`: Position - 原点在世界中的位置
  - `size`: int - 六边形的大小（同一坐标系统中的六边形大小必须相同）
  - `style`: TETile - 原点的样式
- 功能描述: 创建六边形坐标系统，设置原点

#### 方法列表

##### calculatePosition(int x, double y)

- 参数: 
  - `x`: int - x坐标
  - `y`: double - y坐标
- 返回值: `Position` - 计算出的位置
- 功能描述: 根据坐标计算六边形在世界中的位置

##### addHex(int x, double y, TETile style)

- 参数: 
  - `x`: int - x坐标
  - `y`: double - y坐标
  - `style`: TETile - 六边形的样式
- 返回值: `boolean` - 如果成功添加返回 true
- 功能描述: 在指定坐标添加六边形

##### removeHex(int x, double y)

- 参数: 
  - `x`: int - x坐标
  - `y`: double - y坐标
- 返回值: `boolean` - 如果成功删除返回 true
- 功能描述: 删除指定坐标的六边形（原点不可删除）

##### getHex(int x, double y)

- 参数: 
  - `x`: int - x坐标
  - `y`: double - y坐标
- 返回值: `Hexagon` - 对应坐标的六边形
- 功能描述: 获取指定坐标的六边形

##### hasHex(int x, double y)

- 参数: 
  - `x`: int - x坐标
  - `y`: double - y坐标
- 返回值: `boolean` - 如果存在返回 true
- 功能描述: 检查指定坐标是否存在六边形

##### addSelf(TETile[][] world)

- 参数: 
  - `world`: TETile[][] - 二维瓦片数组
- 返回值: `void`
- 功能描述: 将坐标系统中的所有六边形添加到世界中

##### iterator()

- 参数: 无
- 返回值: `Iterator<Hexagon>` - 六边形迭代器
- 功能描述: 返回六边形的迭代器

---

### 5. HexHoneyComb 类

完整路径: `byog.lab5.HexHoneyComb`

所属模块: Lab5

功能描述: 六边形蜂巢类，表示连通的六边形集合。

#### 构造方法

##### HexHoneyComb(Hexagon pioneerHex, int size)

- 参数: 
  - `pioneerHex`: Hexagon - 源六边形
  - `size`: int - 六边形的大小
- 功能描述: 根据源六边形创建蜂巢

#### 方法列表

##### addRight(double hight, TETile style)

- 参数: 
  - `hight`: double - 相对高度
  - `style`: TETile - 六边形的样式
- 返回值: `void`
- 功能描述: 在右侧添加六边形（相对位置）

##### addLeft(double hight, TETile style)

- 参数: 
  - `hight`: double - 相对高度
  - `style`: TETile - 六边形的样式
- 返回值: `void`
- 功能描述: 在左侧添加六边形（相对位置）

##### addVertical(int hori_X, int vert_N, int upOdown, TETile style)

- 参数: 
  - `hori_X`: int - 横向偏移
  - `vert_N`: int - 垂直数量（当前为1）
  - `upOdown`: int - 方向（1为上，-1为下）
  - `style`: TETile - 六边形的样式
- 返回值: `void`
- 功能描述: 在垂直方向添加六边形

##### addSelf(TETile[][] world)

- 参数: 
  - `world`: TETile[][] - 二维瓦片数组
- 返回值: `void`
- 功能描述: 将蜂巢中的所有六边形添加到世界中

##### iterator()

- 参数: 无
- 返回值: `Iterator<Hexagon>` - 六边形迭代器
- 功能描述: 返回蜂巢中六边形的迭代器

---

### 6. BoringWorldDemo 类

完整路径: `byog.lab5.BoringWorldDemo`

所属模块: Lab5

功能描述: 单调世界演示类，绘制大部分空白的世界。

#### 常量

- `WIDTH`: int = 60 - 世界宽度
- `HEIGHT`: int = 30 - 世界高度

#### 方法列表

##### main(String[] args)

- 参数: 
  - `args`: String[] - 命令行参数
- 返回值: `void`
- 功能描述: 主方法，初始化瓦片渲染引擎并绘制单调世界

---

### 7. RandomWorldDemo 类

完整路径: `byog.lab5.RandomWorldDemo`

所属模块: Lab5

功能描述: 随机世界演示类，绘制包含随机瓦片的世界。

#### 常量

- `WIDTH`: int = 50 - 世界宽度
- `HEIGHT`: int = 50 - 世界高度
- `SEED`: long = 2873123 - 随机种子

#### 方法列表

##### fillWithRandomTiles(TETile[][] tiles)

- 参数: 
  - `tiles`: TETile[][] - 二维瓦片数组
- 返回值: `void`
- 功能描述: 用随机瓦片填充二维数组

##### randomTile()

- 参数: 无
- 返回值: `TETile` - 随机瓦片
- 功能描述: 返回随机瓦片（33%墙壁，33%花朵，33%空白）

##### main(String[] args)

- 参数: 
  - `args`: String[] - 命令行参数
- 返回值: `void`
- 功能描述: 主方法，初始化瓦片渲染引擎并绘制随机世界

---

### 8. HexCoorSysTest 类

完整路径: `byog.lab5.HexCoorSysTest`

所属模块: Lab5

功能描述: 六边形坐标系统的测试类。

#### 方法列表

##### test()

- 参数: 无
- 返回值: `void`
- 功能描述: 测试六边形坐标系统的添加、删除和获取功能

---

## Lab6 模块

实验室6模块，包含记忆游戏相关的类。

### 1. MemoryGame 类

完整路径: `byog.lab6.MemoryGame`

所属模块: Lab6

功能描述: 记忆游戏类，玩家需要记住并输入随机生成的字符序列。

#### 构造方法

##### MemoryGame(int width, int height)

- 参数: 
  - `width`: int - 游戏宽度
  - `height`: int - 游戏高度
- 功能描述: 创建记忆游戏，初始化画布

#### 常量

- `CHARACTERS`: char[] - 可用字符数组（a-z）
- `ENCOURAGEMENT`: String[] - 鼓励语句数组

#### 方法列表

##### generateRandomString(int n)

- 参数: 
  - `n`: int - 字符串长度
- 返回值: `String` - 随机字符串
- 功能描述: 生成指定长度的随机字符串

##### drawFrame(String s)

- 参数: 
  - `s`: String - 要显示的字符串
- 返回值: `void`
- 功能描述: 在屏幕中央显示字符串，并在顶部显示游戏信息

##### flashSequence(String letters)

- 参数: 
  - `letters`: String - 字符序列
- 返回值: `void`
- 功能描述: 逐个显示字符序列中的每个字符，字符之间清空屏幕

##### solicitNCharsInput(int n)

- 参数: 
  - `n`: int - 要读取的字符数量
- 返回值: `String` - 用户输入的字符串
- 功能描述: 读取用户输入的n个字符

##### startGame()

- 参数: 无
- 返回值: `void`
- 功能描述: 启动游戏，设置相关变量并建立游戏循环

##### main(String[] args)

- 参数: 
  - `args`: String[] - 命令行参数（需要提供种子）
- 返回值: `void`
- 功能描述: 主方法，启动记忆游戏

---

### 2. MemoryGameSolution 类

完整路径: `byog.lab6.MemoryGameSolution`

所属模块: Lab6

功能描述: 记忆游戏的完整解决方案类。

#### 构造方法

##### MemoryGameSolution(int width, int height, long seed)

- 参数: 
  - `width`: int - 游戏宽度
  - `height`: int - 游戏高度
  - `seed`: long - 随机种子
- 功能描述: 创建记忆游戏，初始化画布和随机数生成器

#### 方法列表

##### startGame()

- 参数: 无
- 返回值: `void`
- 功能描述: 启动游戏，包含完整的游戏循环逻辑

##### generateRandomString(int n)

- 参数: 
  - `n`: int - 字符串长度
- 返回值: `String` - 随机字符串
- 功能描述: 生成指定长度的随机字符串

##### flashSequence(String letters)

- 参数: 
  - `letters`: String - 字符序列
- 返回值: `void`
- 功能描述: 逐个显示字符序列，字符之间清空屏幕并暂停

##### solicitNCharsInput(int n)

- 参数: 
  - `n`: int - 要读取的字符数量
- 返回值: `String` - 用户输入的字符串
- 功能描述: 读取用户输入的n个字符

##### drawFrame(String s)

- 参数: 
  - `s`: String - 要显示的字符串
- 返回值: `void`
- 功能描述: 在屏幕中央显示字符串，并在顶部显示游戏信息

##### main(String[] args)

- 参数: 
  - `args`: String[] - 命令行参数（需要提供种子）
- 返回值: `void`
- 功能描述: 主方法，启动记忆游戏

---

## SaveDemo 模块

保存演示模块，展示如何保存和加载游戏状态。

### 1. World 类

完整路径: `byog.SaveDemo.World`

所属模块: SaveDemo

功能描述: 世界类，包含方形对象集合，支持序列化保存。

#### 构造方法

##### World()

- 参数: 无
- 功能描述: 创建空的世界对象

#### 方法列表

##### addRandomSquare()

- 参数: 无
- 返回值: `void`
- 功能描述: 向世界中添加随机方形对象

##### draw()

- 参数: 无
- 返回值: `void`
- 功能描述: 绘制世界中的所有方形对象

---

### 2. Square 类

完整路径: `byog.SaveDemo.Square`

所属模块: SaveDemo

功能描述: 方形类，表示可序列化的方形对象。

#### 构造方法

##### Square(double xp, double yp, double sizep, Color cp)

- 参数: 
  - `xp`: double - x位置
  - `yp`: double - y位置
  - `sizep`: double - 大小
  - `cp`: Color - 颜色
- 功能描述: 创建方形对象

#### 方法列表

##### draw()

- 参数: 无
- 返回值: `void`
- 功能描述: 绘制方形对象

---

### 3. Main 类

完整路径: `byog.SaveDemo.Main`

所属模块: SaveDemo

功能描述: 保存演示的主类，展示如何保存和加载世界状态。

#### 方法列表

##### main(String[] args)

- 参数: 
  - `args`: String[] - 命令行参数
- 返回值: `void`
- 功能描述: 主方法，启动保存演示程序

##### drawEverything(World w)

- 参数: 
  - `w`: World - 世界对象
- 返回值: `void`
- 功能描述: 绘制世界和用户界面提示信息

##### loadWorld()

- 参数: 无
- 返回值: `World` - 加载的世界对象
- 功能描述: 从文件加载世界对象，如果文件不存在则返回新世界

##### saveWorld(World w)

- 参数: 
  - `w`: World - 要保存的世界对象
- 返回值: `void`
- 功能描述: 将世界对象保存到文件

---

## 项目架构总结

本项目采用模块化设计，各模块职责明确：

1. Core模块: 负责核心游戏逻辑，包括世界生成、房间和走廊管理、游戏流程控制等
2. Helper模块: 提供辅助工具，包括日志记录、数学计算、数据结构（图、双端队列）等
3. TileEngine模块: 负责瓦片渲染和显示，是游戏可视化的基础
4. Lab5模块: 六边形世界实验，展示六边形坐标系统和蜂巢结构
5. Lab6模块: 记忆游戏实验，展示游戏交互和用户输入处理
6. SaveDemo模块: 保存演示，展示游戏状态的序列化和反序列化

项目使用了多种算法和数据结构，包括：
- Prim算法用于最小生成树
- 邻接矩阵和邻接表表示图
- 双端队列实现循环数组
- 泊松分布和偏置分布用于随机生成
- 序列化用于游戏状态保存

---

## 使用建议

1. 世界生成: 使用 `WorldGenerator.RandomSquareRoomWrd()` 方法生成随机世界
2. 游戏启动: 通过 `Main.main()` 方法启动游戏，可选择键盘模式或字符串输入模式
3. 瓦片渲染: 使用 `TERenderer` 类初始化画布并渲染瓦片数组
4. 日志记录: 使用 `Logger` 类记录不同级别的日志信息，便于调试
5. 随机数生成: 使用 `RandomUtils` 类提供多种分布的随机数生成方法

---

## 开发者注意事项

1. 所有类和方法都遵循Java编码规范
2. 测试类（如 `MathTest`, `ArrayDequeTest`, `HexCoorSysTest`）用于验证功能正确性
3. 项目使用了第三方库 `StdDraw` 用于图形渲染
4. 序列化保存的文件路径为 `./world.ser`
5. 游戏世界的默认大小为 80x30 瓦片

---

文档生成日期: 2026-06-20

项目版本: CS61B Standard Project 2