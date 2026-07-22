package byog.Test;

import byog.Entity.EntityManager;
import byog.Entity.Enemy;
import byog.Entity.Player;
import byog.Trace.AgentTrace;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.Random;

/**
 * Phase 1 固定遭遇的无头、私有感知测试运行支架。
 *
 * <p>与 Phase0EncounterHarness 镜像，但启用私有感知（perceptionEnabled=true）。
 * trace 路径从 LEGACY_DECISION_INPUT 变为 OBSERVATION_GENERATED。</p>
 *
 * <p>这个类刻意不启动渲染器、不读键盘、不 sleep、不访问网络，也不读写默认存档，
 * 因此可以在 JUnit 或 CI 中 headless 运行。</p>
 */
public final class Phase1EncounterHarness {

    private static final String SCENARIO_ID = "baseline-two-guards-perception";
    private static final int SCENARIO_VERSION = 1;
    private static final String RUN_ID = "perception-test";
    private static final int FLOOR_ID = 1;

    private final TETile[][] world;
    private final int width;
    private final int height;
    private final Player player;
    private final Enemy guardA;
    private final Enemy guardB;
    private final EntityManager entityMgr;
    private final Position stairsPos;
    private final AgentTrace.InMemorySink traceSink;
    private long logicalTick = 0;

    Phase1EncounterHarness(TETile[][] world, Player player,
                           Enemy guardA, Enemy guardB,
                           EntityManager entityMgr, Position stairsPos,
                           AgentTrace.InMemorySink traceSink) {
        this.world = world;
        this.width = world.length;
        this.height = world[0].length;
        this.player = player;
        this.guardA = guardA;
        this.guardB = guardB;
        this.entityMgr = entityMgr;
        this.stairsPos = stairsPos;
        this.traceSink = traceSink;

        guardA.setPerceptionEnabled(true);
        guardB.setPerceptionEnabled(true);
    }

    /**
     * 从 ASCII 字符串数组构建 Phase1EncounterHarness。
     * 解析逻辑与 Phase0EncounterHarness.fromAscii 相同，但创建后启用私有感知。
     */
    public static Phase1EncounterHarness fromAscii(String[] ascii, int playerHp,
                                                    long guardASeed, long guardBSeed) {
        int h = ascii.length;
        int w = ascii[0].length();
        TETile[][] world = new TETile[w][h];

        Position playerPos = null;
        Position aPos = null;
        Position bPos = null;
        Position stairsPos = null;

        for (int i = 1; i < h; i++) {
            if (ascii[i].length() != w) {
                throw new IllegalArgumentException(
                        "Row " + i + " has unequal width: " + ascii[i].length()
                        + " vs expected " + w);
            }
        }

        for (int row = 0; row < h; row++) {
            int gameY = h - 1 - row;
            String line = ascii[row];
            for (int x = 0; x < w; x++) {
                char c = line.charAt(x);
                switch (c) {
                    case '#':
                        world[x][gameY] = Tileset.WALL;
                        break;
                    case '.':
                        world[x][gameY] = Tileset.FLOOR;
                        break;
                    case '>':
                        world[x][gameY] = Tileset.STAIRS;
                        if (stairsPos != null) {
                            throw new IllegalArgumentException(
                                    "Duplicate stairs '>' at (" + x + "," + gameY + ")");
                        }
                        stairsPos = new Position(x, gameY);
                        break;
                    case 'P':
                        world[x][gameY] = Tileset.FLOOR;
                        if (playerPos != null) {
                            throw new IllegalArgumentException(
                                    "Duplicate player 'P' at (" + x + "," + gameY + ")");
                        }
                        playerPos = new Position(x, gameY);
                        break;
                    case 'A':
                        world[x][gameY] = Tileset.FLOOR;
                        if (aPos != null) {
                            throw new IllegalArgumentException(
                                    "Duplicate guard 'A' at (" + x + "," + gameY + ")");
                        }
                        aPos = new Position(x, gameY);
                        break;
                    case 'B':
                        world[x][gameY] = Tileset.FLOOR;
                        if (bPos != null) {
                            throw new IllegalArgumentException(
                                    "Duplicate guard 'B' at (" + x + "," + gameY + ")");
                        }
                        bPos = new Position(x, gameY);
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "Unknown character '" + c + "' at (" + x + "," + gameY + ")");
                }
            }
        }

        if (playerPos == null) {
            throw new IllegalArgumentException("Missing player 'P'");
        }
        if (aPos == null) {
            throw new IllegalArgumentException("Missing guard 'A'");
        }
        if (bPos == null) {
            throw new IllegalArgumentException("Missing guard 'B'");
        }
        if (stairsPos == null) {
            throw new IllegalArgumentException("Missing stairs '>'");
        }

        Player player = new Player(playerPos, playerHp, 10);
        Enemy guardA = new Enemy(aPos, Tileset.ENEMY, 20, 7, 1, 1, 0,
                new Random(guardASeed), "guard-a");
        Enemy guardB = new Enemy(bPos, Tileset.ENEMY, 20, 7, 1, 1, 0,
                new Random(guardBSeed), "guard-b");

        EntityManager em = new EntityManager();
        em.addEntity(player);
        em.addEntity(guardA);
        em.addEntity(guardB);

        AgentTrace.InMemorySink sink = new AgentTrace.InMemorySink();

        return new Phase1EncounterHarness(world, player, guardA, guardB, em, stairsPos, sink);
    }

    /**
     * 构建启用私有感知的 baseline-two-guards 固定场景。
     */
    public static Phase1EncounterHarness baselineTwoGuardsV1() {
        String[] ascii = {
            "#################",
            "#.......#......>#",
            "#.......#...B...#",
            "#.......#####.###",
            "#...............#",
            "#..P.....A......#",
            "#...............#",
            "#################"
        };

        Phase1EncounterHarness h = fromAscii(ascii, 1000, 101, 202);

        if (h.width != 17 || h.height != 8) {
            throw new IllegalArgumentException(
                    "Expected 17×8 world, got " + h.width + "×" + h.height);
        }

        return h;
    }

    /** 推进一个完整 logical tick。调度顺序固定 A→B。 */
    public void step() {
        AgentTrace.Context ctxA = new AgentTrace.Context(
                AgentTrace.SCHEMA_VERSION,
                SCENARIO_ID, SCENARIO_VERSION, logicalTick, "guard-a");
        AgentTrace.Context ctxB = new AgentTrace.Context(
                AgentTrace.SCHEMA_VERSION,
                SCENARIO_ID, SCENARIO_VERSION, logicalTick, "guard-b");

        guardA.updateAI(world, entityMgr, player, ctxA, traceSink);
        guardB.updateAI(world, entityMgr, player, ctxB, traceSink);

        entityMgr.flushPendingChanges();
        entityMgr.removeDeadEntities();

        logicalTick++;
    }

    /** 连续推进指定 tick 数。 */
    public void runTicks(int count) {
        for (int i = 0; i < count; i++) {
            step();
        }
    }

    public String canonicalTraceJson() {
        return traceSink.toCanonicalJson();
    }

    public String canonicalState() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"logicalTick\":").append(logicalTick).append(",\n");

        sb.append("  \"terrain\":[\n");
        for (int y = height - 1; y >= 0; y--) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < width; x++) {
                row.append(world[x][y].character());
            }
            sb.append("    \"").append(row).append("\"");
            if (y > 0) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ],\n");

        sb.append("  \"stairs\":{\"x\":").append(stairsPos.x)
                .append(",\"y\":").append(stairsPos.y).append("},\n");

        sb.append("  \"actors\":[\n");
        appendActor(sb, "player", player.getPosition(), player.getHp(), player.isAlive(), false);
        appendActor(sb, "guard-a", guardA.getPosition(), guardA.getHp(), guardA.isAlive(), false);
        appendActor(sb, "guard-b", guardB.getPosition(), guardB.getHp(), guardB.isAlive(), true);
        sb.append("  ]\n");

        sb.append("}");
        return sb.toString();
    }

    private void appendActor(StringBuilder sb, String actorKey, Position pos,
                             int hp, boolean alive, boolean last) {
        sb.append("    {\"actorKey\":\"").append(actorKey).append("\"")
                .append(",\"x\":").append(pos.x)
                .append(",\"y\":").append(pos.y)
                .append(",\"hp\":").append(hp)
                .append(",\"alive\":").append(alive)
                .append("}");
        if (!last) {
            sb.append(",");
        }
        sb.append("\n");
    }

    public String buildBaselineJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"baselineSchemaVersion\":\"phase1.baseline.v1\",\n");
        sb.append("  \"scenarioId\":\"baseline-two-guards-perception\",\n");
        sb.append("  \"scenarioVersion\":1,\n");
        sb.append("  \"tickCount\":").append(logicalTick).append(",\n");
        sb.append("  \"events\":");
        sb.append(canonicalTraceJson());
        sb.append(",\n");
        sb.append("  \"finalState\":");
        sb.append(canonicalState());
        sb.append("\n}");
        return sb.toString();
    }

    public TETile[][] terrainCopy() {
        TETile[][] copy = new TETile[width][height];
        for (int x = 0; x < width; x++) {
            System.arraycopy(world[x], 0, copy[x], 0, height);
        }
        return copy;
    }

    public TETile[][] getWorld() {
        return world;
    }

    public EntityManager getEntityMgr() {
        return entityMgr;
    }

    public Player player() {
        return player;
    }

    public Enemy guardA() {
        return guardA;
    }

    public Enemy guardB() {
        return guardB;
    }

    public Position stairsPosition() {
        return stairsPos;
    }

    public long getLogicalTick() {
        return logicalTick;
    }

    public AgentTrace.InMemorySink getTraceSink() {
        return traceSink;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }
}
