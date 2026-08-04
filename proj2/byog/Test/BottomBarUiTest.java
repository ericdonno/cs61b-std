package byog.Test;

import byog.Common.Difficulty;
import byog.Common.Facing;
import byog.Core.HoverModel;
import byog.Core.ScreenLayout;
import byog.Entity.Enemy;
import byog.Entity.EntityManager;
import byog.Entity.Player;
import byog.IO.GameConfig;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;
import org.junit.Test;

import java.util.Properties;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 底部上下文 UI：屏幕坐标互逆映射、hover model（敌人/苹果/地形/空）。
 */
public class BottomBarUiTest {

    private GameConfig config() {
        return new GameConfig(Difficulty.BALANCED, new Properties());
    }

    private TETile[][] world() {
        TETile[][] w = new TETile[ScreenLayout.WORLD_WIDTH]
                [ScreenLayout.WORLD_HEIGHT];
        for (int x = 0; x < w.length; x++) {
            for (int y = 0; y < w[0].length; y++) {
                w[x][y] = Tileset.FLOOR;
            }
        }
        return w;
    }

    // ---------- P25-UI-01 坐标互逆映射 ----------

    @Test
    public void screenMappingIsInverseAndKeepsWorldSize() {
        assertEquals(80, ScreenLayout.WORLD_WIDTH);
        assertEquals(30, ScreenLayout.WORLD_HEIGHT);
        assertEquals(2, ScreenLayout.BOTTOM_UI_HEIGHT);
        assertEquals(35, ScreenLayout.WINDOW_HEIGHT);
        for (int y = 0; y < ScreenLayout.WORLD_HEIGHT; y++) {
            assertEquals(y,
                    ScreenLayout.screenToWorldY(
                            ScreenLayout.worldToScreenY(y)));
        }
        // 世界底部第一行画在 y=2，顶部最后一行画在 y=31
        assertEquals(2, ScreenLayout.worldToScreenY(0));
        assertEquals(31, ScreenLayout.worldToScreenY(29));
    }

    // ---------- P25-UI-02 敌人 hover model ----------

    @Test
    public void enemyHoverShowsHpBarAndFacing() {
        TETile[][] w = world();
        EntityManager em = new EntityManager();
        Enemy enemy = new Enemy(new Position(4, 4), Tileset.ENEMY,
                14, 5, 5, 10, 3, new Random(1), "guard-a");
        enemy.setMaxHp(20);
        enemy.setFacing(Facing.EAST);
        em.addEntity(enemy);
        Player player = new Player(new Position(0, 0), config());
        em.addEntity(player);

        HoverModel.HoverInfo info = HoverModel.resolve(
                w, em, player, config(), new Position(4, 4));

        assertTrue(info.line1().contains("HP: 14/20"));
        assertTrue(info.line1().contains(HoverModel.healthBar(14, 20)));
        assertTrue(info.line2().contains("EAST"));
        assertEquals(enemy, info.focusedEnemy());
        // 血条比例正确
        assertEquals("[=======---]",
                HoverModel.healthBar(14, 20));
        assertEquals("[==========]",
                HoverModel.healthBar(20, 20));
        assertEquals("[----------]",
                HoverModel.healthBar(0, 20));
    }

    // ---------- P25-UI-03 apple/terrain/no hover ----------

    @Test
    public void appleHoverShowsHealAndFullHpHint() {
        TETile[][] w = world();
        w[6][6] = Tileset.APPLE;
        EntityManager em = new EntityManager();
        Player player = new Player(new Position(0, 0), config());
        em.addEntity(player);

        HoverModel.HoverInfo info = HoverModel.resolve(
                w, em, player, config(), new Position(6, 6));

        assertTrue(info.line1().contains("apple health pack"));
        assertTrue(info.line2().contains("Heal +" + config().healthPackHealAmount));
        assertNull(info.focusedEnemy());
    }

    @Test
    public void terrainHoverShowsDescription() {
        TETile[][] w = world();
        w[3][3] = Tileset.WALL;
        HoverModel.HoverInfo info = HoverModel.resolve(
                w, new EntityManager(), null, config(),
                new Position(3, 3));
        assertEquals("wall", info.line1());
    }

    @Test
    public void nothingHoverIsEmpty() {
        TETile[][] w = world();
        HoverModel.HoverInfo outOfBounds = HoverModel.resolve(
                w, new EntityManager(), null, config(),
                new Position(-1, -1));
        assertTrue(outOfBounds.isEmpty());
        HoverModel.HoverInfo inside = HoverModel.resolve(
                w, new EntityManager(), null, config(),
                new Position(2, 2));
        assertFalse(inside.isEmpty());
        assertEquals("floor", inside.line1());
    }
}
