# 敌人碰撞测试：3x3 小房间

## 概述

新建一个 JUnit 测试类 `EnemyCollisionTest`，构造 3x3 小房间，放入玩家和 2 个敌人，运行敌人 AI 并验证不会发生位置重叠。

## 涉及文件

| 文件 | 操作 |
|------|------|
| [EnemyCollisionTest.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/EnemyCollisionTest.java) | **新建** |

## 测试设计方案

### 世界布局（5x5 瓦片）

```
  #  #  #  #  #        y=4
  #  ·  ·  ·  #        y=3
  #  ·  @  E  #        y=2
  #  E  ·  ·  #        y=1
  #  #  #  #  #        y=0
 x=0  1  2  3  4
```

- 外圈（x=0, x=4, y=0, y=4）= 墙
- 内圈 3x3（x=1~3, y=1~3）= 地板
- 玩家 `@` 在 (2, 2)（正中）
- 敌人 `E` 在 (2, 1) 和 (1, 2)（紧邻玩家）

### 测试逻辑

```java
package byog.Core;

import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;
import org.junit.Test;
import java.util.Random;

import static org.junit.Assert.assertTrue;

public class EnemyCollisionTest {

    @Test
    public void testEnemyNotWalkOntoPlayer() {
        // 1. 建 5x5 世界，外圈墙、内圈 3x3 地板
        TETile[][] world = buildSmallRoom();

        // 2. 创建实体管理器
        EntityManager em = new EntityManager();

        // 3. 玩家放在 (2,2) 正中
        Player player = new Player(new Position(2, 2));
        em.addEntity(player);

        // 4. 两个敌人放在紧邻位置
        Enemy e1 = new Enemy(new Position(2, 1), new Random(42));
        Enemy e2 = new Enemy(new Position(1, 2), new Random(99));
        em.addEntity(e1);
        em.addEntity(e2);

        // 5. 运行 500 帧，每帧检查碰撞
        for (int tick = 0; tick < 500; tick++) {
            e1.updateAI(world, em);
            e2.updateAI(world, em);

            Position pPlayer = player.getPosition();
            Position pE1 = e1.getPosition();
            Position pE2 = e2.getPosition();

            String msg = String.format("Tick %d: ", tick);
            assertTrue(msg + "Enemy1 overlapped Player", !pE1.equals(pPlayer));
            assertTrue(msg + "Enemy2 overlapped Player", !pE2.equals(pPlayer));
            assertTrue(msg + "Enemy1 overlapped Enemy2", !pE1.equals(pE2));

            em.flushPendingChanges();
        }
    }

    /** 建造 5x5 小房间，外圈是墙，内圈 3x3 是地板 */
    private TETile[][] buildSmallRoom() {
        TETile[][] world = new TETile[5][5];
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                if (x == 0 || x == 4 || y == 0 || y == 4) {
                    world[x][y] = Tileset.WALL;
                } else {
                    world[x][y] = Tileset.FLOOR;
                }
            }
        }
        return world;
    }
}
```

### 为什么这个测试能复现 bug

- 房间只有 3x3 = 9 个可走格子，2 个敌人和玩家挤在一起
- 敌人移动方向完全随机（4 方向），紧邻玩家时走到玩家位置的概率很高
- 在当前有 bug 的代码下，测试大概率在几十个 tick 内就会失败（敌人走上玩家）
- 修复 bug 后，测试持续跑 500 tick 都不会失败

## 验证步骤

1. 确保旧存档 `./save/game.ser` 已删除
2. **修复前**：运行测试，预期失败（抓到这个 bug）
3. 应用 `fix-enemy-walk-onto-player.md` 的修复方案
4. **修复后**：运行测试，预期通过
