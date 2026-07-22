# Bug 报告：敌人可以走到玩家身上

## 一、现象

在游戏中测试 Enemy 系统时，发现一个奇怪的问题：敌人偶尔会直接穿过玩家，和玩家站在同一个格子上。两个实体叠在一起，就像中间没有发生任何碰撞一样。同时也没有任何战斗效果——敌人不攻击、不掉血，就好像玩家是空气。

Bug 并不总是发生。有时候一切正常，有时候突然就出现了，很难捉摸。

## 二、根因分析

### 2.1 问题的源头：延迟索引

要理解这个 bug，得先知道 `EntityManager` 是怎么管理位置的。

游戏中所有实体（玩家、敌人）的位置都被存在一个叫 `positionIndex` 的 HashMap 里。每个可移动的东西要走到新位置之前，都得先问一声："`canMoveTo`，这个位置有人吗？" EntityManager 就去查这个 HashMap，如果目标位置是空的，就放行。

但这里有一个关键设计：这个 HashMap **不是实时更新的**。实体的 `position` 字段变了，HashMap 并不会马上跟着变。它要等到每一帧结束时，`flushPendingChanges()` 才会拿着所有实体当前最新的位置，重新把 HashMap 建一遍。

这个设计本来是合理的——如果在敌人大批量移动的过程中去修改 HashMap，会导致遍历期间抛 `ConcurrentModificationException`。但这个"延迟"就是 bug 的根源。

### 2.2 具体是怎么出错的

每一帧的执行顺序是这样的：

```
帧开始
  │
  ├─ 1. 处理玩家按键 → player.move() → player.position 变了
  │
  ├─ 2. 遍历所有敌人 → enemy.updateAI()
  │      └─ MoveAction.execute()
  │           └─ canMoveTo()  ← 这里查的是旧的 positionIndex！
  │           └─ entity.position 变了
  │
  └─ 3. flushPendingChanges() → 根据最新 position 重建 positionIndex
帧结束
```

重点在步骤 2。当敌人调用 `canMoveTo` 想检查"目标位置有没有人"时，它查到的是一份**已经过时的地图**。这份地图不知道玩家在步骤 1 已经走了，也不知道前面那些敌人在步骤 2 已经挪了地方。

举个例子：一开始玩家在 A 点，敌人在 B 点（紧邻 A）。玩家按了一下方向键，走到了旁边的 C 点。这一帧结束前，`positionIndex` 还以为玩家在 A 点。然后轮到敌人执行 AI——敌人随机选了个方向，恰好也指向 C 点。`canMoveTo` 一查：C 点没人啊（因为 `positionIndex` 还以为玩家在 A）！于是敌人开开心心地走到了 C 点——和玩家站到了一起。

回到步骤 3，`flushPendingChanges` 要重建索引了。它把玩家放进 C 点，又把敌人也放进 C 点。HashMap 同一个 key 放两次，后放的覆盖先放的——于是一个实体"消失"了。

### 2.3 为什么不是每次都出现

因为 bug 需要三个条件同时满足：

1. **玩家刚好在这一帧移动了**
2. **敌人刚好在这一帧到了 AI tick**（敌人不是每帧都动，`moveInterval=5`，每 5 帧才动一次）
3. **敌人随机到的方向，恰好指向玩家刚刚走到的新位置**

三个条件缺一不可，所以 bug 是偶发的。大部分时候敌人走的是别的方向，或者玩家根本没动，那就一切正常。这也是为什么用户反映"突然又没了"——不是修好了，只是没撞上。

## 三、用测试复现

偶发 bug 最难办的就是"不好稳定复现"。为了让 bug 必定触发，设计了一个专门的测试场景。

### 3.1 测试设计：3x3 小房间

在 [EnemyCollisionTest.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/EnemyCollisionTest.java) 中，手工构造了一个极小的世界——

```
  #  #  #  #  #
  #  ·  ·  ·  #
  #  ·  @  E  #
  #  E  ·  ·  #
  #  #  #  #  #
```

- 外圈全是墙（`#`），内圈只有 3×3 = 9 个可走的格子（`·`）
- 玩家（`@`）放在正中间 (2,2)
- 两个敌人（`E`）放在 (2,1) 和 (1,2)，和玩家紧挨着

9 个格子里挤了 3 个实体，敌人每帧随机往四个方向走，在这种空间里碰上的概率极高。

测试跑 500 个 tick，每个 tick 之后检查玩家和每个敌人的位置是否重叠。

### 3.2 测试结果

修复前，测试在第 114 tick 就失败了：

```
java.lang.AssertionError: Tick 114: Enemy1 overlapped Enemy2
    at byog.Core.EnemyCollisionTest.testEnemyNotWalkOntoPlayer(EnemyCollisionTest.java:43)
```

两个敌人走到了同一个格子。这证实了延迟索引更新的设计缺陷确实导致碰撞检测失效。

## 四、修复方案

### 4.1 核心思路

不能让 `canMoveTo` 再读一份过时的地图了。需要在每一帧的运行过程中，实时知道"哪些位置已经被占用了"。

最简单的办法是在帧中间修改 `positionIndex` 本身。但这不行——AI tick 在遍历 `positionIndex.values()` 的同时如果修改 HashMap，Java 会直接抛异常。

于是引入一个**额外的轻量集合** `frameOccupied`。它的职责很简单：记录"这一帧内有哪些位置刚刚被实体占据了"。任何实体移动成功后，立即把新位置扔进这个集合。后续的其他实体在 `canMoveTo` 时，除了查 `positionIndex`，还要查这个集合——这样就能看到同帧内先走的那些实体已经占好的位置了。

帧结束时，`flushPendingChanges` 根据最新的 `position` 字段重建主索引，然后清空 `frameOccupied`，一切回归正常。

### 4.2 具体修改

修改了三个文件。思路统一：任何实体移动成功后，通知 EntityManager 一声"这个位置我占了"。

#### EntityManager.java

这个类是改动最大的地方。增加了三样东西：

- **一个 `frameOccupied` 集合**（HashSet）：记录帧内被占用的新位置
- **`canMoveTo` 中的额外检查**：在查 `positionIndex` 之前，先看一眼 `frameOccupied` 有没有这个位置。如果有，直接拒绝——说明已经有别的实体这一帧走到这里了
- **`claimPosition` 方法**：让外面的人在移动成功后，调用这个方法把新位置登记进来
- **`flushPendingChanges` 末尾的清空**：帧结束后重置

#### Player.java

玩家移动的 `move()` 方法，原来成功后只是 `this.position = newPos`。现在加了一行，把新位置通知给 EntityManager。

#### MoveAction.java

敌人的移动动作 `execute()`，同样是成功后通知 EntityManager。

### 4.3 设计权衡

这里有一个取舍：`frameOccupied` 只负责"新位置被占用"的通知，不负责"旧位置释放"的通知。

也就是说，当玩家从 A 点走到 B 点，A 点被标记为占用、B 点也被标记为占用。但 `positionIndex` 里玩家还显示在 A 点。这意味着其他实体既走不到 A（被 `positionIndex` 拦住），也走不到 B（被 `frameOccupied` 拦住）。

短期来看，A 点被"误拒"了——它其实是空的，但索引还认为有人。这是一个安全的假阳性，宁可多拦一个，不能多放一个。帧末重建索引后，A 点就会正确显示为空，下一帧不受影响。

## 五、验证

修复后，`EnemyCollisionTest` 的 500 个 tick 全部通过。两个敌人在狭小的 3x3 房间里反复随机移动了 500 轮，从未出现位置重叠。

游戏中也确认了效果：走近敌人时，敌人会在相邻格子停下或被墙壁阻挡，不再出现穿过玩家的情况。存档（`:q`）和读档（`l`）也正常工作，不受影响。
