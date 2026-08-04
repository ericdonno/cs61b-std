package byog.Test;

import byog.AI.ReflexObservation;
import byog.Common.Facing;
import byog.Common.VisionMode;
import byog.Entity.Enemy;
import byog.Entity.EntityManager;
import byog.Entity.Player;
import byog.IO.GameConfig;
import byog.Perception.ObservationEnvelope;
import byog.Perception.PerceptionSystem;
import byog.Perception.VisibleEntity;
import byog.Perception.VisibleTile;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;
import org.junit.Test;

import java.util.Properties;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 半菱形 directional FOV：四朝向过滤、中线可见、LOS 保留、全向 baseline
 * 与背后玩家不泄漏。
 */
public class DirectionalFovTest {

    private static final String WORLD = "world-fov";
    private static final String RUN = "run-fov";

    private GameConfig config() {
        return new GameConfig(byog.Common.Difficulty.BALANCED,
                new Properties());
    }

    private TETile[][] openWorld(int w, int h) {
        TETile[][] world = new TETile[w][h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                world[x][y] = Tileset.FLOOR;
            }
        }
        return world;
    }

    private ObservationEnvelope observe(TETile[][] world,
                                        EntityManager em,
                                        Enemy self, Player player,
                                        VisionMode mode) {
        return PerceptionSystem.computeObservation(
                WORLD, RUN, 1, 0, world, em, self, player,
                self.getSightRange(), mode, 0);
    }

    private Enemy enemyAt(int x, int y, Facing facing) {
        Enemy enemy = new Enemy(new Position(x, y), Tileset.ENEMY,
                20, 3, 5, 10, 3, new Random(1), "guard-a");
        enemy.setFacing(facing);
        return enemy;
    }

    // ---------- P25-FOV-01 四朝向半菱形 ----------

    @Test
    public void directionalMaskKeepsOnlyFacingHalf() {
        for (Facing facing : Facing.values()) {
            TETile[][] world = openWorld(9, 9);
            EntityManager em = new EntityManager();
            Enemy self = enemyAt(4, 4, facing);
            em.addEntity(self);
            Player player = new Player(new Position(8, 8), config());
            em.addEntity(player);

            ObservationEnvelope obs = observe(world, em, self, player,
                    VisionMode.DIRECTIONAL);

            for (int x = 0; x < world.length; x++) {
                for (int y = 0; y < world[0].length; y++) {
                    boolean inRange = Math.abs(x - 4) + Math.abs(y - 4) <= 3;
                    boolean onHalf = switch (facing) {
                        case NORTH -> y >= 4;
                        case SOUTH -> y <= 4;
                        case EAST -> x >= 4;
                        case WEST -> x <= 4;
                    };
                    boolean selfTile = x == 4 && y == 4;
                    assertEquals("facing=" + facing + " tile=(" + x + "," + y + ")",
                            inRange && (selfTile || onHalf),
                            obs.isVisible(x, y));
                }
            }
        }
    }

    // ---------- P25-FOV-02 背后玩家不泄漏 ----------

    @Test
    public void playerBehindFacingIsNotVisibleAnywhere() {
        TETile[][] world = openWorld(9, 9);
        EntityManager em = new EntityManager();
        Enemy self = enemyAt(4, 4, Facing.NORTH);
        em.addEntity(self);
        // 玩家在 self 正背后（南边 2 格，距离 2 <= sightRange 3）
        Player player = new Player(new Position(4, 2), config());
        em.addEntity(player);

        ObservationEnvelope obs = observe(world, em, self, player,
                VisionMode.DIRECTIONAL);

        assertFalse(obs.canSeePlayer());
        assertFalse("visibleEntities must not contain player",
                obs.getVisibleEntities().stream()
                        .anyMatch(ve -> ve.getType()
                                == VisibleEntity.EntityType.PLAYER));
        ReflexObservation reflex = ReflexObservation.from(obs);
        assertFalse(reflex.canSeePlayer());
    }

    @Test
    public void playerInFrontOfFacingIsVisible() {
        TETile[][] world = openWorld(9, 9);
        EntityManager em = new EntityManager();
        Enemy self = enemyAt(4, 4, Facing.NORTH);
        em.addEntity(self);
        Player player = new Player(new Position(4, 6), config());
        em.addEntity(player);

        ObservationEnvelope obs = observe(world, em, self, player,
                VisionMode.DIRECTIONAL);

        assertTrue(obs.canSeePlayer());
        VisibleEntity visible = obs.getVisiblePlayer();
        assertNotNull(visible);
        assertEquals(new Position(4, 6), visible.getPosition());
    }

    // ---------- P25-FOV-03 地图边缘不越界 ----------

    @Test
    public void mapCornerDoesNotThrowForAnyFacing() {
        for (Facing facing : Facing.values()) {
            TETile[][] world = openWorld(5, 5);
            EntityManager em = new EntityManager();
            Enemy self = enemyAt(0, 0, facing);
            em.addEntity(self);
            Player player = new Player(new Position(4, 4), config());
            em.addEntity(player);
            // 不应抛异常
            ObservationEnvelope obs = observe(world, em, self, player,
                    VisionMode.DIRECTIONAL);
            assertNotNull(obs.getVisibleMask());
        }
    }

    // ---------- P25-FOV-04 墙与墙角保留 LOS ----------

    @Test
    public void wallBlocksVisionInsideFacingHalf() {
        TETile[][] world = openWorld(9, 9);
        world[4][6] = Tileset.WALL; // 挡住 north 方向的第 2、3 格
        EntityManager em = new EntityManager();
        Enemy self = enemyAt(4, 4, Facing.NORTH);
        em.addEntity(self);
        Player player = new Player(new Position(4, 7), config());
        em.addEntity(player);

        ObservationEnvelope obs = observe(world, em, self, player,
                VisionMode.DIRECTIONAL);

        assertFalse("wall must block LOS inside the facing half",
                obs.canSeePlayer());
    }

    // ---------- P25-FOV-05 全向 baseline ----------

    @Test
    public void omnidirectionalMatchesLegacyDiamond() {
        TETile[][] world = openWorld(9, 9);
        EntityManager em = new EntityManager();
        Enemy self = enemyAt(4, 4, Facing.NORTH);
        em.addEntity(self);
        Player player = new Player(new Position(8, 8), config());
        em.addEntity(player);

        ObservationEnvelope obs = observe(world, em, self, player,
                VisionMode.OMNIDIRECTIONAL);

        for (int x = 0; x < world.length; x++) {
            for (int y = 0; y < world[0].length; y++) {
                boolean legacyVisible =
                        Math.abs(x - 4) + Math.abs(y - 4) <= 3;
                assertEquals("omnidirectional must equal legacy diamond ("
                                + x + "," + y + ")",
                        legacyVisible, obs.isVisible(x, y));
            }
        }
    }

    // ---------- P25-FOV-06 双敌人不同朝向 ----------

    @Test
    public void twoEnemiesWithDifferentFacingSeeDifferentPlayers() {
        TETile[][] world = openWorld(9, 9);
        EntityManager em = new EntityManager();
        // A 朝东，B 朝西，站在同一位置两侧
        Enemy east = enemyAt(4, 4, Facing.EAST);
        Enemy west = enemyAt(4, 4, Facing.WEST);
        em.addEntity(east);
        em.addEntity(west);
        // 玩家在 A 前方、B 背后
        Player player = new Player(new Position(7, 4), config());
        em.addEntity(player);

        ObservationEnvelope obsEast = observe(world, em, east, player,
                VisionMode.DIRECTIONAL);
        ObservationEnvelope obsWest = observe(world, em, west, player,
                VisionMode.DIRECTIONAL);

        assertTrue("east-facing enemy must see the player",
                obsEast.canSeePlayer());
        assertFalse("west-facing enemy must not see the player",
                obsWest.canSeePlayer());
    }

    // ---------- 苹果在 directional FOV 中仍为地板 ----------

    @Test
    public void appleInsideFacingHalfStaysFloorInSnapshot() {
        TETile[][] world = openWorld(9, 9);
        world[4][6] = Tileset.APPLE;
        EntityManager em = new EntityManager();
        Enemy self = enemyAt(4, 4, Facing.NORTH);
        em.addEntity(self);
        Player player = new Player(new Position(8, 8), config());
        em.addEntity(player);

        ObservationEnvelope obs = observe(world, em, self, player,
                VisionMode.DIRECTIONAL);

        boolean sawApple = false;
        for (VisibleTile tile : obs.getVisibleTiles()) {
            if (tile.getX() == 4 && tile.getY() == 6) {
                assertEquals(VisibleTile.TileType.FLOOR, tile.getType());
                sawApple = true;
            }
        }
        assertTrue("apple tile must be inside the facing half", sawApple);
    }
}
