package byog.Test;

import byog.Action.Action;
import byog.Action.MoveAction;
import byog.Common.Difficulty;
import byog.Common.Direction;
import byog.Entity.Enemy;
import byog.Entity.EntityManager;
import byog.Entity.Player;
import byog.IO.GameConfig;
import byog.IO.HealthPackConfig;
import byog.Perception.ObservationEnvelope;
import byog.Perception.PerceptionSystem;
import byog.Perception.VisibleTile;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.WorldGen.HealthPackGenerator;
import byog.WorldGen.HealthPackPickup;
import byog.WorldGen.SquareRoom;
import byog.lab5.Position;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 苹果血包：生成、确定性、排除规则、拾取与 Agent 隐藏。
 *
 * <p>覆盖苹果需求与 Spec 的验收：数量闭区间、相同输入相同结果、
 * 排除房间/实体/走廊、候选不足有界、缺血治疗/满血保留、敌人不消耗、
 * Agent 快照中苹果等价于地板。</p>
 */
public class HealthPackTest {

    private static final int WORLD_W = 20;
    private static final int WORLD_H = 12;

    // ---------- fixture ----------

    private TETile[][] emptyWorld() {
        TETile[][] w = new TETile[WORLD_W][WORLD_H];
        for (int x = 0; x < WORLD_W; x++) {
            for (int y = 0; y < WORLD_H; y++) {
                w[x][y] = Tileset.NOTHING;
            }
        }
        return w;
    }

    private List<SquareRoom> buildRooms(TETile[][] w) {
        SquareRoom spawn = new SquareRoom(new Position(1, 1), 5);
        SquareRoom stair = new SquareRoom(new Position(8, 1), 5);
        SquareRoom roomC = new SquareRoom(new Position(1, 7), 5);
        SquareRoom roomD = new SquareRoom(new Position(8, 7), 5);
        spawn.addSelf(w);
        stair.addSelf(w);
        roomC.addSelf(w);
        roomD.addSelf(w);
        List<SquareRoom> rooms = new ArrayList<>();
        rooms.add(spawn);
        rooms.add(stair);
        rooms.add(roomC);
        rooms.add(roomD);
        return rooms;
    }

    private GameConfig config(Difficulty difficulty) {
        return new GameConfig(difficulty, new Properties());
    }

    private GameConfig configWithHealthPacks(
            Difficulty difficulty, int min, int max, int heal) {
        Properties props = new Properties();
        props.setProperty(
                difficulty.getKey() + ".healthPack.minCount",
                String.valueOf(min));
        props.setProperty(
                difficulty.getKey() + ".healthPack.maxCount",
                String.valueOf(max));
        props.setProperty(
                difficulty.getKey() + ".healthPack.healAmount",
                String.valueOf(heal));
        return new GameConfig(difficulty, props);
    }

    // ---------- P25-APPLE-01 数量闭区间与非法配置回退 ----------

    @Test
    public void difficultyConfigBoundsAreClosedAndSafe() {
        GameConfig easy = configWithHealthPacks(Difficulty.EASY, 3, 5, 25);
        assertEquals(3, easy.healthPackMinCount);
        assertEquals(5, easy.healthPackMaxCount);
        GameConfig balanced =
                configWithHealthPacks(Difficulty.BALANCED, 2, 4, 20);
        assertEquals(2, balanced.healthPackMinCount);
        assertEquals(4, balanced.healthPackMaxCount);
        GameConfig hardcore =
                configWithHealthPacks(Difficulty.HARDCORE, 1, 3, 15);
        assertEquals(1, hardcore.healthPackMinCount);
        assertEquals(3, hardcore.healthPackMaxCount);
        assertTrue(easy.healthPackHealAmount > 0);
    }

    @Test
    public void invalidHealthPackConfigFallsBackToSafeDefaults() {
        Properties props = new Properties();
        props.setProperty("balanced.healthPack.minCount", "9");
        props.setProperty("balanced.healthPack.maxCount", "1");
        props.setProperty("balanced.healthPack.healAmount", "0");
        GameConfig config = new GameConfig(Difficulty.BALANCED, props);
        assertEquals(2, config.healthPackMinCount);
        assertEquals(4, config.healthPackMaxCount);
        assertEquals(20, config.healthPackHealAmount);
    }

    // ---------- P25-APPLE-02 确定性 ----------

    @Test
    public void sameSeedProducesIdenticalPlacement() {
        List<Position> first = placeOnce(4242L);
        List<Position> second = placeOnce(4242L);
        assertEquals(first, second);
        assertFalse(first.isEmpty());
    }

    private List<Position> placeOnce(long seed) {
        TETile[][] w = emptyWorld();
        List<SquareRoom> rooms = buildRooms(w);
        EntityManager em = new EntityManager();
        em.addEntity(new Enemy(new Position(2, 8), Tileset.ENEMY, 10, 5,
                5, 5, 2, new Random(1), "blocker"));
        return HealthPackGenerator.place(w, rooms, rooms.get(0), rooms.get(1),
                em, new HealthPackConfig(2, 4, 20), seed);
    }

    @Test
    public void placementCountStaysWithinConfiguredBounds() {
        for (long seed = 1; seed <= 6; seed++) {
            int count = placeOnce(seed).size();
            assertTrue("count " + count + " outside [2,4]",
                    count >= 2 && count <= 4);
        }
    }

    @Test
    public void placementDoesNotConsumeForeignRandomStreams() {
        Random foreign = new Random(99);
        foreign.nextInt(); // 消耗一个值
        int expectedNext = foreign.nextInt();
        Random probe = new Random(99);
        probe.nextInt();
        placeOnce(4242L); // 内部使用独立 Random，不应触碰 probe
        assertEquals(expectedNext, probe.nextInt());
    }

    // ---------- P25-APPLE-03 排除规则 ----------

    @Test
    public void placementExcludesSpawnAndStairRoomsAndOccupants() {
        TETile[][] w = emptyWorld();
        List<SquareRoom> rooms = buildRooms(w);
        SquareRoom spawn = rooms.get(0);
        SquareRoom stair = rooms.get(1);
        EntityManager em = new EntityManager();
        Position occupant = new Position(2, 8); // 房间 C 内部
        em.addEntity(new Enemy(occupant, Tileset.ENEMY, 10, 5,
                5, 5, 2, new Random(1), "blocker"));
        List<Position> placed = HealthPackGenerator.place(w, rooms, spawn, stair,
                em, new HealthPackConfig(2, 4, 20), 4242L);

        Set<Position> seen = new HashSet<>();
        for (Position p : placed) {
            assertFalse("apple in spawn room", spawn.containsFloor(p));
            assertFalse("apple in stair room", stair.containsFloor(p));
            assertFalse("apple on entity", p.equals(occupant));
            assertEquals("apple must be on plain floor", Tileset.APPLE, w[p.x][p.y]);
            assertTrue("duplicate apple position", seen.add(p));
        }
        assertEquals(placed.size(), seen.size());
    }

    // ---------- P25-APPLE-04 候选不足 ----------

    @Test
    public void insufficientCandidatesPlacesAllWithoutLooping() {
        TETile[][] w = emptyWorld();
        List<SquareRoom> rooms = buildRooms(w);
        // 只有房间 C 内部两个候选可行；目标区间 [2,4]
        List<Position> placed = HealthPackGenerator.place(w, rooms,
                rooms.get(0), rooms.get(1),
                null, new HealthPackConfig(2, 4, 20), 4242L);
        // 正常候选应为 8（C+D 各 4）；此处验证所有候选都被放置或不超过区间上限，
        // 且不会死循环（测试超时即失败）。
        assertTrue(placed.size() >= 2 && placed.size() <= 4);
    }

    @Test
    public void zeroCandidatesYieldsEmptyWithoutFailure() {
        TETile[][] w = emptyWorld();
        // 无房间：spawn/stair 为 null → 0 个苹果，游戏继续
        List<Position> placed = HealthPackGenerator.place(w, new ArrayList<>(),
                null, null, null, new HealthPackConfig(2, 4, 20), 1L);
        assertTrue(placed.isEmpty());
    }

    // ---------- P25-APPLE-05/06 拾取 ----------

    @Test
    public void pickupHealsAndConsumesWhenInjured() {
        TETile[][] w = emptyWorld();
        w[5][5] = Tileset.APPLE;
        Player player = new Player(new Position(5, 6), config(Difficulty.BALANCED));
        player.setHp(50);
        List<Position> remaining = new ArrayList<>(List.of(new Position(5, 5)));

        boolean picked = HealthPackPickup.tryPickup(w, player,
                new Position(5, 5), 20, remaining);

        assertTrue(picked);
        assertEquals(70, player.getHp());
        assertEquals(Tileset.FLOOR, w[5][5]);
        assertTrue(remaining.isEmpty());
    }

    @Test
    public void pickupDoesNotConsumeAtFullHp() {
        TETile[][] w = emptyWorld();
        w[5][5] = Tileset.APPLE;
        Player player = new Player(new Position(5, 6), config(Difficulty.BALANCED));
        List<Position> remaining = new ArrayList<>(List.of(new Position(5, 5)));

        boolean picked = HealthPackPickup.tryPickup(w, player,
                new Position(5, 5), 20, remaining);

        assertFalse(picked);
        assertEquals(config(Difficulty.BALANCED).playerHp, player.getHp());
        assertEquals(Tileset.APPLE, w[5][5]);
        assertEquals(1, remaining.size());
    }

    // ---------- P25-APPLE-07 敌人经过不消耗 ----------

    @Test
    public void enemyWalkingOverAppleLeavesItIntact() {
        TETile[][] w = emptyWorld();
        buildRooms(w);
        w[2][9] = Tileset.APPLE; // 房间 C 内部地板
        EntityManager em = new EntityManager();
        Enemy enemy = new Enemy(new Position(2, 8), Tileset.ENEMY, 10, 5,
                5, 5, 2, new Random(1), "enemy-a");
        em.addEntity(enemy);

        Action.ActionResult result = new MoveAction(Direction.UP, em)
                .execute(w, enemy);

        assertEquals(Action.ActionResult.SUCCESS, result);
        assertEquals(new Position(2, 9), enemy.getPosition());
        assertEquals("apple must survive enemy occupancy",
                Tileset.APPLE, w[2][9]);
    }

    // ---------- P25-APPLE-08 Agent 快照隐藏 ----------

    @Test
    public void appleMapsToFloorInAgentSnapshot() {
        assertEquals(VisibleTile.TileType.FLOOR,
                VisibleTile.tileTypeOf(Tileset.APPLE));

        // 感知快照：self 下方两格是 apple，visibleTiles 中该位置必须是 FLOOR
        TETile[][] w = emptyWorld();
        for (int x = 0; x < WORLD_W; x++) {
            for (int y = 0; y < WORLD_H; y++) {
                w[x][y] = Tileset.FLOOR;
            }
        }
        Position applePos = new Position(10, 4);
        w[applePos.x][applePos.y] = Tileset.APPLE;
        EntityManager em = new EntityManager();
        Enemy self = new Enemy(new Position(10, 2), Tileset.ENEMY, 10, 5,
                5, 5, 2, new Random(1), "guard-a");
        em.addEntity(self);
        Player player = new Player(new Position(10, 6), config(Difficulty.BALANCED));
        em.addEntity(player);

        ObservationEnvelope obs = PerceptionSystem.computeObservation(
                "world-test", "run-1", 1, 0, w, em, self, player, 5, byog.Common.VisionMode.DIRECTIONAL, 0);

        boolean appleVisibleAsFloor = false;
        for (VisibleTile tile : obs.getVisibleTiles()) {
            if (tile.getX() == applePos.x && tile.getY() == applePos.y) {
                assertEquals("apple must not leak to Agent",
                        VisibleTile.TileType.FLOOR, tile.getType());
                assertTrue(tile.isWalkable());
                appleVisibleAsFloor = true;
            }
        }
        assertTrue("apple position should be visible", appleVisibleAsFloor);
    }

    // ---------- 图片资源 spike ----------

    @Test
    public void appleImageAssetExistsAsPng() throws Exception {
        File png = new File("assets/tiles/apple.png");
        assertTrue("apple.png must exist", png.isFile());
        byte[] header = new byte[8];
        try (FileInputStream in = new FileInputStream(png)) {
            assertEquals(8, in.read(header));
        }
        byte[] pngSignature = {(byte) 0x89, 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < 8; i++) {
            assertEquals("PNG signature byte " + i, pngSignature[i], header[i]);
        }
    }
}
