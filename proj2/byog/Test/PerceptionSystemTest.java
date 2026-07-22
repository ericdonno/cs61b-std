package byog.Test;

import byog.Perception.ObservationEnvelope;
import byog.Perception.PerceptionSystem;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * PerceptionSystem 单元测试。
 * 测试 blocksVision 和 hasLineOfSight 在小地图上的行为。
 */
public class PerceptionSystemTest {

    @Test
    public void testBlocksVision_Wall() {
        assertTrue(PerceptionSystem.blocksVision(Tileset.WALL));
    }

    @Test
    public void testBlocksVision_Nothing() {
        assertTrue(PerceptionSystem.blocksVision(Tileset.NOTHING));
    }

    @Test
    public void testBlocksVision_Floor() {
        assertFalse(PerceptionSystem.blocksVision(Tileset.FLOOR));
    }

    @Test
    public void testBlocksVision_Stairs() {
        assertFalse(PerceptionSystem.blocksVision(Tileset.STAIRS));
    }

    @Test
    public void testBlocksVision_Player() {
        assertFalse(PerceptionSystem.blocksVision(Tileset.PLAYER));
    }

    @Test
    public void testBlocksVision_Enemy() {
        assertFalse(PerceptionSystem.blocksVision(Tileset.ENEMY));
    }

    private TETile[][] createEmptyWorld(int width, int height) {
        TETile[][] world = new TETile[width][height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                world[x][y] = Tileset.FLOOR;
            }
        }
        return world;
    }

    @Test
    public void testLineOfSight_SamePoint() {
        TETile[][] world = createEmptyWorld(5, 5);
        assertTrue(PerceptionSystem.hasLineOfSight(world, 2, 2, 2, 2));
    }

    @Test
    public void testLineOfSight_StraightHorizontal_NoObstacle() {
        TETile[][] world = createEmptyWorld(5, 5);
        assertTrue(PerceptionSystem.hasLineOfSight(world, 2, 2, 4, 2));
    }

    @Test
    public void testLineOfSight_StraightVertical_NoObstacle() {
        TETile[][] world = createEmptyWorld(5, 5);
        assertTrue(PerceptionSystem.hasLineOfSight(world, 2, 2, 2, 4));
    }

    @Test
    public void testLineOfSight_StraightHorizontal_BlockedByWall() {
        TETile[][] world = createEmptyWorld(5, 5);
        world[3][2] = Tileset.WALL;
        assertFalse(PerceptionSystem.hasLineOfSight(world, 2, 2, 4, 2));
    }

    @Test
    public void testLineOfSight_StraightVertical_BlockedByWall() {
        TETile[][] world = createEmptyWorld(5, 5);
        world[2][3] = Tileset.WALL;
        assertFalse(PerceptionSystem.hasLineOfSight(world, 2, 2, 2, 4));
    }

    @Test
    public void testLineOfSight_Diagonal_NoObstacle() {
        TETile[][] world = createEmptyWorld(5, 5);
        assertTrue(PerceptionSystem.hasLineOfSight(world, 0, 0, 4, 4));
    }

    @Test
    public void testLineOfSight_Diagonal_BlockedByWall() {
        TETile[][] world = createEmptyWorld(5, 5);
        world[2][2] = Tileset.WALL;
        assertFalse(PerceptionSystem.hasLineOfSight(world, 0, 0, 4, 4));
    }

    @Test
    public void testLineOfSight_ReverseDirection() {
        TETile[][] world = createEmptyWorld(5, 5);
        assertTrue(PerceptionSystem.hasLineOfSight(world, 4, 4, 0, 0));
    }

    @Test
    public void testLineOfSight_BoundaryOutside() {
        TETile[][] world = createEmptyWorld(5, 5);
        assertFalse(PerceptionSystem.hasLineOfSight(world, -1, 2, 2, 2));
        assertFalse(PerceptionSystem.hasLineOfSight(world, 2, 2, 5, 2));
        assertFalse(PerceptionSystem.hasLineOfSight(world, 2, -1, 2, 2));
        assertFalse(PerceptionSystem.hasLineOfSight(world, 2, 2, 2, 5));
    }

    @Test
    public void testLineOfSight_LShape_NoObstacle() {
        TETile[][] world = createEmptyWorld(5, 5);
        assertTrue(PerceptionSystem.hasLineOfSight(world, 0, 2, 4, 4));
    }

    @Test
    public void testLineOfSight_WallAtEndPoint() {
        TETile[][] world = createEmptyWorld(5, 5);
        world[4][2] = Tileset.WALL;
        assertTrue(PerceptionSystem.hasLineOfSight(world, 2, 2, 4, 2));
    }

    @Test
    public void testLineOfSight_SimpleHallway() {
        TETile[][] world = createEmptyWorld(7, 3);
        world[0][0] = Tileset.WALL;
        world[0][1] = Tileset.WALL;
        world[0][2] = Tileset.WALL;
        world[6][0] = Tileset.WALL;
        world[6][1] = Tileset.WALL;
        world[6][2] = Tileset.WALL;
        assertTrue(PerceptionSystem.hasLineOfSight(world, 1, 1, 5, 1));
    }

    @Test
    public void testObservationDefensivelyCopiesMutableData() {
        Phase1EncounterHarness harness = Phase1EncounterHarness.baselineTwoGuardsV1();
        ObservationEnvelope observation = PerceptionSystem.computeObservation(
                "immutability", 1, 0,
                harness.getWorld(), harness.getEntityMgr(),
                harness.guardA(), harness.player(), 7, 0);

        Position originalSelf = harness.guardA().getPosition();
        Position exposedSelf = observation.getSelfPosition();
        exposedSelf.x = -100;
        exposedSelf.y = -100;
        assertEquals(originalSelf, harness.guardA().getPosition());
        assertEquals(originalSelf, observation.getSelfPosition());

        Position originalPlayer = harness.player().getPosition();
        Position exposedPlayer = observation.getVisiblePlayer().getPosition();
        exposedPlayer.x = -200;
        exposedPlayer.y = -200;
        assertEquals(originalPlayer, harness.player().getPosition());
        assertEquals(originalPlayer, observation.getVisiblePlayer().getPosition());

        boolean[][] exposedMask = observation.getVisibleMask();
        exposedMask[originalSelf.x][originalSelf.y] = false;
        assertTrue(observation.isVisible(originalSelf.x, originalSelf.y));
    }

    @Test
    public void testObservationWalkabilityIsSnapshot() {
        Phase1EncounterHarness harness = Phase1EncounterHarness.baselineTwoGuardsV1();
        Position self = harness.guardA().getPosition();
        ObservationEnvelope observation = PerceptionSystem.computeObservation(
                "snapshot", 1, 0,
                harness.getWorld(), harness.getEntityMgr(),
                harness.guardA(), harness.player(), 7, 0);

        assertTrue(observation.isWalkable(self.x, self.y));
        harness.getWorld()[self.x][self.y] = Tileset.WALL;
        assertTrue("historical observation must not change with the live world",
                observation.isWalkable(self.x, self.y));
    }
}
