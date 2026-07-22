package byog.Test;

import byog.Entity.Entity;
import byog.Entity.EntityManager;
import byog.Entity.Enemy;
import byog.Entity.Player;
import byog.Trace.AgentTrace;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.Arrays;
import java.util.Random;

/**
 * Phase 0 固定遭遇的无头、确定性测试运行支架（test harness）。
 *
 * <p>Harness 的责任是准备受控输入、调用真实游戏逻辑并收集证据，而不是复制一套
 * Brain、Planner 或 Action 规则。它解析手写 ASCII 场景，显式保存玩家和两个守卫，
 * 按固定顺序 {@code [guard-a, guard-b]} 调用真实 {@link Enemy#updateAI}，最后产出
 * canonical trace 和 canonical state。</p>
 *
 * <p>这个类刻意不启动渲染器、不读键盘、不 sleep、不访问网络，也不读写默认存档，
 * 因此可以在 JUnit 或 CI 中 headless（无窗口）运行。它定义的是 Phase 0 实验调度
 * 协议，不代表正式游戏已经采用相同的敌人公平调度规则。</p>
 */
public final class Phase0EncounterHarness {

    /** 场景的稳定身份；修改场景语义时应提升版本而不是覆盖 v1。 */
    private static final String SCENARIO_ID = "baseline-two-guards";
    private static final int SCENARIO_VERSION = 1;

    /** 只保存地形 tile；Player/Enemy 由 EntityManager 独立管理。 */
    private final TETile[][] world;
    private final int width;
    private final int height;

    /**
     * 显式保存参与者引用，避免依赖 EntityManager 内部 HashMap 的无顺序 values view。
     */
    private final Player player;
    private final Enemy guardA;
    private final Enemy guardB;
    private final EntityManager entityMgr;
    private final Position stairsPos;

    /** Phase 0 使用的内存事件接收器；追加顺序定义全局 trace sequence。 */
    private final AgentTrace.InMemorySink traceSink;

    /** 逻辑时钟，从 0 开始；只在完整 step 结束后递增。 */
    private long logicalTick = 0;

    /**
     * 包内构造方法主要供固定场景工厂和测试 parser 使用。
     * 构造器不复制数组或实体，调用方必须保证它们只属于当前 Harness 实例。
     */
    Phase0EncounterHarness(TETile[][] world, Player player,
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
    }

    /**
     * 从 ASCII 字符串数组构建 Phase0EncounterHarness。
     *
     * <p>这是 Phase 0 唯一的生产 parser。Harness 的 v1 工厂和非法输入测试都应
     * 通过此方法解析 ASCII，避免测试复制生产逻辑导致假绿。</p>
     *
     * @param ascii ASCII 地图行数组，第一行为最高 y
     * @param playerHp 玩家 HP
     * @param guardASeed guard-a 的 Random seed
     * @param guardBSeed guard-b 的 Random seed
     * @throws IllegalArgumentException 不等宽、未知字符、重复或缺失 P/A/B/>
     */
    public static Phase0EncounterHarness fromAscii(String[] ascii, int playerHp,
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
        Enemy guardA = new Enemy(aPos, Tileset.ENEMY, 20, 7, 1, 1, 0, new Random(guardASeed), "guard-a");
        Enemy guardB = new Enemy(bPos, Tileset.ENEMY, 20, 7, 1, 1, 0, new Random(guardBSeed), "guard-b");

        EntityManager em = new EntityManager();
        em.addEntity(player);
        em.addEntity(guardA);
        em.addEntity(guardB);

        AgentTrace.InMemorySink sink = new AgentTrace.InMemorySink();

        return new Phase0EncounterHarness(world, player, guardA, guardB, em, stairsPos, sink);
    }

    /**
     * 构建 {@code baseline-two-guards:v1} 固定场景。
     *
     * <p>所有会影响行为的参数都在方法中显式给出：地图、坐标、HP、视野、移动间隔、
     * 攻击、伤害波动和两个独立 Random seed。主 fixture 不读取外部配置，避免本地
     * properties 文件变化导致 golden 漂移。</p>
     */
    public static Phase0EncounterHarness baselineTwoGuardsV1() {
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

        Phase0EncounterHarness h = fromAscii(ascii, 1000, 101, 202);

        // v1 的尺寸也是场景协议的一部分，防止只改 ASCII 却忘记提升场景版本。
        if (h.width != 17 || h.height != 8) {
            throw new IllegalArgumentException(
                    "Expected 17×8 world, got " + h.width + "×" + h.height);
        }

        return h;
    }

    /**
     * 推进一个完整 logical tick。
     *
     * <p>调度顺序固定为 A 后 B。两名守卫更新期间 EntityManager 使用帧内占位信息；
     * 完成后只 flush 一次，再清理死亡实体。logicalTick 必须最后递增，这样本次产生的
     * 全部事件都带有相同 tick 值。</p>
     */
    public void step() {
        // actorKey 是场景内稳定身份，不能替换为跨运行不稳定的 Entity.id。
        AgentTrace.Context ctxA = new AgentTrace.Context(
                AgentTrace.PHASE0_SCHEMA_VERSION,
                SCENARIO_ID, SCENARIO_VERSION, logicalTick, "guard-a");
        AgentTrace.Context ctxB = new AgentTrace.Context(
                AgentTrace.PHASE0_SCHEMA_VERSION,
                SCENARIO_ID, SCENARIO_VERSION, logicalTick, "guard-b");

        // 显式调用保证 scheduling determinism，不依赖 HashMap 遍历顺序。
        guardA.updateAI(world, entityMgr, player, ctxA, traceSink);
        guardB.updateAI(world, entityMgr, player, ctxB, traceSink);

        entityMgr.flushPendingChanges();
        entityMgr.removeDeadEntities();

        logicalTick++;
    }

    /**
     * 连续推进指定 tick 数。每一次循环都经过完整 {@link #step()} 边界，
     * 不提供跳帧或批量更新捷径，以保证单步测试和 12 tick baseline 使用相同语义。
     */
    public void runTicks(int count) {
        for (int i = 0; i < count; i++) {
            step();
        }
    }

    /** 返回按事件到达顺序规范化的 trace JSON。 */
    public String canonicalTraceJson() {
        return traceSink.toCanonicalJson();
    }

    /**
     * 返回当前世界的 canonical state JSON。
     *
     * <p>它是运行结果证据，与记录过程的 trace 互补。输出不含 Entity.id、wall-clock、
     * 对象 hash 或集合自然顺序；terrain 固定从最高 y 输出到最低 y，actors 固定为
     * player、guard-a、guard-b，因此两次相同运行可以直接做字节比较。</p>
     */
    public String canonicalState() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"logicalTick\":").append(logicalTick).append(",\n");

        // terrain 必须 top row first，与人类阅读 ASCII fixture 的方向保持一致。
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

        // 楼梯不是 Entity，单独作为固定场景设施输出。
        sb.append("  \"stairs\":{\"x\":").append(stairsPos.x)
                .append(",\"y\":").append(stairsPos.y).append("},\n");

        // actor 数组顺序是 canonical 协议的一部分，不能改成遍历 EntityManager。
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

    /**
     * 返回 terrain 二维数组的结构副本，供测试检查地形而不替换 Harness 内部列数组。
     * TETile 在当前项目中作为共享 tile 值使用，因此这里只复制数组层级。
     */
    public TETile[][] terrainCopy() {
        TETile[][] copy = new TETile[width][height];
        for (int x = 0; x < width; x++) {
            System.arraycopy(world[x], 0, copy[x], 0, height);
        }
        return copy;
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

    /**
     * 构建完整 golden baseline JSON。
     * 顶层同时包含过程证据 events 和结果证据 finalState；本方法只生成字符串，
     * 不执行文件写入，避免普通测试拥有隐式更新 golden 的能力。
     */
    public String buildBaselineJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"baselineSchemaVersion\":\"phase0.baseline.v1\",\n");
        sb.append("  \"scenarioId\":\"baseline-two-guards\",\n");
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

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }
}
