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

        // 3. 玩家放在 (2,2) 正中，HP 足够高保证 500 tick 内不死亡
        Player player = new Player(new Position(2, 2), 10000, 10);
        em.addEntity(player);

        // 4. 两个敌人放在紧邻位置
        Enemy e1 = new Enemy(new Position(2, 1), Tileset.ENEMY, 20, 7, 5, 10, 3, new Random(42));
        Enemy e2 = new Enemy(new Position(1, 2), Tileset.ENEMY, 20, 7, 5, 10, 3, new Random(99));
        em.addEntity(e1);
        em.addEntity(e2);

        // 5. 运行 500 帧，每帧检查碰撞
        for (int tick = 0; tick < 500; tick++) {
            e1.updateAI(world, em, player);
            e2.updateAI(world, em, player);

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
