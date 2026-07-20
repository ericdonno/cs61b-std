package byog.Test;

import byog.Entity.Entity;
import byog.Entity.Enemy;
import byog.Entity.Player;
import byog.Trace.AgentTrace;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.WorldGen.WorldGenerator;
import byog.lab5.Position;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Phase 0 固定遭遇的契约、确定性、不变量和 golden 回归测试。
 *
 * <p>本类按 Spec 中的 P0-T01 至 P0-T09 编号组织。测试不评价敌人是否“足够聪明”，
 * 而是验证实验输入、调度证据和输出协议是否稳定：相同条件应产生相同 trace，
 * 每个 tick 的活实体必须满足世界不变量，完整结果必须与经过审查的 baseline 一致。</p>
 *
 * <p>已知问题已于 2026-07-20 整改：P0-T02 委托到生产 parser、P0-T06 按
 * (actorKey, logicalTick, actionOrdinal) 验证每 tick 内生命周期、P0-T09
 * golden 缺失时 assert 失败而非静默跳过。</p>
 */
public class Phase0EncounterTest {

    // ---------- P0-T01 ----------

    /**
     * P0-T01：锁定 v1 fixture 的外部契约。
     * 坐标、尺寸和底层 terrain 一旦有意变化，应提升 scenarioVersion，而不是只改断言。
     */
    @Test
    public void fixtureHasExpectedLayout() {
        Phase0EncounterHarness harness = Phase0EncounterHarness.baselineTwoGuardsV1();
        TETile[][] terrain = harness.terrainCopy();
        assertEquals("width", 17, harness.width());
        assertEquals("height", 8, harness.height());

        Position stairs = harness.stairsPosition();
        assertEquals("stairs x", 15, stairs.x);
        assertEquals("stairs y", 6, stairs.y);

        Position pp = harness.player().getPosition();
        assertEquals("player x", 3, pp.x);
        assertEquals("player y", 2, pp.y);

        Position pa = harness.guardA().getPosition();
        assertEquals("guardA x", 9, pa.x);
        assertEquals("guardA y", 2, pa.y);

        Position pb = harness.guardB().getPosition();
        assertEquals("guardB x", 12, pb.x);
        assertEquals("guardB y", 5, pb.y);

        // 实体不直接写入 terrain：P/A/B 出生点下方仍必须是 FLOOR，楼梯保留 STAIRS。
        assertEquals("at player pos", Tileset.FLOOR, terrain[3][2]);
        assertEquals("at guardA pos", Tileset.FLOOR, terrain[9][2]);
        assertEquals("at guardB pos", Tileset.FLOOR, terrain[12][5]);
        assertEquals("at stairs pos", Tileset.STAIRS, terrain[15][6]);
        assertEquals("wall at (0,0)", Tileset.WALL, terrain[0][0]);
    }

    // ---------- P0-T02 ----------

    /** P0-T02 子例：二维 ASCII 行宽不一致时必须 fail-fast。 */
    @Test(expected = IllegalArgumentException.class)
    public void fixtureRejectsNonUniformRows() {
        Phase0EncounterTestHelper.buildFromAscii(new String[]{
            "#####",
            "##"
        });
    }

    /** P0-T02 子例：协议未定义的字符不能被静默当成地板或墙。 */
    @Test(expected = IllegalArgumentException.class)
    public void fixtureRejectsUnknownChar() {
        Phase0EncounterTestHelper.buildFromAscii(new String[]{
            "###",
            "#X#",
            "###"
        });
    }

    /** P0-T02 子例：玩家是 fixture 必需参与者。 */
    @Test(expected = IllegalArgumentException.class)
    public void fixtureRejectsMissingPlayer() {
        Phase0EncounterTestHelper.buildFromAscii(new String[]{
            "#####",
            "#.A.#",
            "#.B.#",
            "#####"
        });
    }

    /** P0-T02 子例：guard-a 缺失时场景身份不完整。 */
    @Test(expected = IllegalArgumentException.class)
    public void fixtureRejectsMissingGuardA() {
        Phase0EncounterTestHelper.buildFromAscii(new String[]{
            "#####",
            "#P..#",
            "#.B.#",
            "#####"
        });
    }

    /** P0-T02 子例：guard-b 缺失时场景身份不完整。 */
    @Test(expected = IllegalArgumentException.class)
    public void fixtureRejectsMissingGuardB() {
        Phase0EncounterTestHelper.buildFromAscii(new String[]{
            "#####",
            "#P..#",
            "#.A.#",
            "#####"
        });
    }

    /** P0-T02 子例：楼梯坐标属于 v1 canonical state，不能缺失。 */
    @Test(expected = IllegalArgumentException.class)
    public void fixtureRejectsMissingStairs() {
        Phase0EncounterTestHelper.buildFromAscii(new String[]{
            "#####",
            "#P..#",
            "#.A.#",
            "#.B.#",
            "#####"
        });
    }

    /**
     * P0-T02 子例：同一种稳定身份只能出现一次。
     * 覆盖重复 P、A、B、stairs 全部四种角色。
     */
    @Test(expected = IllegalArgumentException.class)
    public void fixtureRejectsDuplicatePlayer() {
        Phase0EncounterTestHelper.buildFromAscii(new String[]{
            "#####",
            "#P.P#",
            "#.A.#",
            "#.B.#",
            "#####"
        });
    }

    /** P0-T02 子例：guard-a 重复出现时场景身份有歧义。 */
    @Test(expected = IllegalArgumentException.class)
    public void fixtureRejectsDuplicateGuardA() {
        Phase0EncounterTestHelper.buildFromAscii(new String[]{
            "#####",
            "#P..#",
            "#.A.#",
            "#.A.#",
            "#####"
        });
    }

    /** P0-T02 子例：guard-b 重复出现时场景身份有歧义。 */
    @Test(expected = IllegalArgumentException.class)
    public void fixtureRejectsDuplicateGuardB() {
        Phase0EncounterTestHelper.buildFromAscii(new String[]{
            "#####",
            "#P..#",
            "#.B.#",
            "#.B.#",
            "#####"
        });
    }

    /** P0-T02 子例：楼梯重复出现时场景定义不明确。 */
    @Test(expected = IllegalArgumentException.class)
    public void fixtureRejectsDuplicateStairs() {
        Phase0EncounterTestHelper.buildFromAscii(new String[]{
            "#####",
            "#P.>#",
            "#.A.#",
            "#.B>#",
            "#####"
        });
    }

    // ---------- P0-T03 ----------

    /**
     * P0-T03：同一 JVM 中连续构建两份 fixture，初始 canonical state 必须相同。
     * 比较结果故意不包含全局自增 Entity.id。
     */
    @Test
    public void fixtureBuildIsDeterministic() {
        Phase0EncounterHarness h1 = Phase0EncounterHarness.baselineTwoGuardsV1();
        Phase0EncounterHarness h2 = Phase0EncounterHarness.baselineTwoGuardsV1();
        assertEquals("canonical states must be identical",
                h1.canonicalState(), h2.canonicalState());
    }

    // ---------- P0-T04 ----------

    /**
     * P0-T04：相同场景、种子、调度和 12 tick 输入必须产生字节一致的事件流。
     * 这是 Phase 0 对行为确定性的核心证据。
     */
    @Test
    public void sameRunProducesSameCanonicalTrace() {
        Phase0EncounterHarness h1 = Phase0EncounterHarness.baselineTwoGuardsV1();
        h1.runTicks(12);
        Phase0EncounterHarness h2 = Phase0EncounterHarness.baselineTwoGuardsV1();
        h2.runTicks(12);
        assertEquals("canonical trace must be byte-identical",
                h1.canonicalTraceJson(), h2.canonicalTraceJson());
    }

    // ---------- P0-T05 ----------

    /**
     * P0-T05：验证 fixture 确实在第一次决策中覆盖两个规则分支。
     * A 距离玩家 6，应 CHASE；B 距离玩家 12，应 PATROL。
     */
    @Test
    public void bothGuardsProduceExpectedInitialIntent() {
        Phase0EncounterHarness harness = Phase0EncounterHarness.baselineTwoGuardsV1();
        harness.runTicks(12);

        List<AgentTrace.Event> events = harness.getTraceSink().events();

        String guardAFirstIntent = findFirstIntent(events, "guard-a");
        String guardBFirstIntent = findFirstIntent(events, "guard-b");

        assertEquals("guard-a first intent should be CHASE",
                "CHASE", guardAFirstIntent);
        assertEquals("guard-b first intent should be PATROL",
                "PATROL", guardBFirstIntent);
    }

    /**
     * 从按 sequence 排序的事件列表中查找 actor 的第一个 INTENT_SELECTED goal。
     * 返回 null 让上层 assertEquals 给出明确失败，而不是在 helper 中抛异常。
     */
    private static String findFirstIntent(List<AgentTrace.Event> events, String actorKey) {
        for (AgentTrace.Event e : events) {
            if (e.actorKey.equals(actorKey)
                    && e.eventType == AgentTrace.EventType.INTENT_SELECTED) {
                return e.goal;
            }
        }
        return null;
    }

    // ---------- P0-T06 ----------

    /**
     * P0-T06：检查两名守卫是否都暴露 input、intent、attempt 和 result 四类证据。
     * 目标是确保 trace seam 覆盖完整决策生命周期，而不只是记录最终位置。
     */
    @Test
    public void traceContainsDecisionLifecycle() {
        Phase0EncounterHarness harness = Phase0EncounterHarness.baselineTwoGuardsV1();
        harness.runTicks(12);
        List<AgentTrace.Event> events = harness.getTraceSink().events();

        for (String actorKey : new String[]{"guard-a", "guard-b"}) {
            boolean hasInput = false;
            boolean hasIntent = false;
            boolean hasAttempt = false;
            boolean hasResult = false;

            for (AgentTrace.Event e : events) {
                if (!actorKey.equals(e.actorKey)) {
                    continue;
                }
                switch (e.eventType) {
                    case LEGACY_DECISION_INPUT: hasInput = true; break;
                    case INTENT_SELECTED: hasIntent = true; break;
                    case ACTION_ATTEMPTED: hasAttempt = true; break;
                    case ACTION_RESULT: hasResult = true; break;
                }
            }

            assertTrue(actorKey + " has LEGACY_DECISION_INPUT", hasInput);
            assertTrue(actorKey + " has INTENT_SELECTED", hasIntent);
            assertTrue(actorKey + " has ACTION_ATTEMPTED", hasAttempt);
            assertTrue(actorKey + " has ACTION_RESULT", hasResult);
        }

        // 按 (actorKey, logicalTick, actionOrdinal) 验证动作生命周期完整性。
        assertActionLifecyclePerTick(events, "guard-a");
        assertActionLifecyclePerTick(events, "guard-b");
    }

    /**
     * 验证 actor 在每个 tick 内的 ACTION_ATTEMPTED 与 ACTION_RESULT 一一配对。
     *
     * <p>关联键为 (actorKey, logicalTick, actionOrdinal)，而非全局 Set。
     * 同时验证事件顺序（attempt 在前、result 在后），确保同 tick 内的事件不会被
     * 跨 tick 的相同 ordinal 混淆。</p>
     */
    private void assertActionLifecyclePerTick(List<AgentTrace.Event> events, String actorKey) {
        // 按 logicalTick 分组，保留到达顺序。
        Map<Long, List<AgentTrace.Event>> byTick = new LinkedHashMap<>();
        for (AgentTrace.Event e : events) {
            if (actorKey.equals(e.actorKey)) {
                byTick.computeIfAbsent(e.logicalTick, k -> new ArrayList<>()).add(e);
            }
        }

        for (Map.Entry<Long, List<AgentTrace.Event>> entry : byTick.entrySet()) {
            long tick = entry.getKey();
            List<AgentTrace.Event> tickEvents = entry.getValue();

            Set<Integer> attempted = new HashSet<>();
            Set<Integer> results = new HashSet<>();
            // 记录每个 ordinal 是否已见过 attempt，用于验证 attempt 在 result 之前。
            Map<Integer, Boolean> attemptSeen = new HashMap<>();

            for (AgentTrace.Event e : tickEvents) {
                if (e.actionOrdinal == null) {
                    continue;
                }
                if (e.eventType == AgentTrace.EventType.ACTION_ATTEMPTED) {
                    attempted.add(e.actionOrdinal);
                    attemptSeen.put(e.actionOrdinal, true);
                } else if (e.eventType == AgentTrace.EventType.ACTION_RESULT) {
                    results.add(e.actionOrdinal);
                    assertTrue(actorKey + " tick " + tick + " ordinal " + e.actionOrdinal
                            + " has ACTION_RESULT without prior ACTION_ATTEMPTED",
                            attemptSeen.containsKey(e.actionOrdinal));
                }
            }

            assertEquals(actorKey + " tick " + tick + " action ordinal mismatch",
                    attempted, results);
        }
    }

    // ---------- P0-T07 ----------

    /**
     * P0-T07：初始状态以及每个 step 完成后都检查世界不变量。
     * 中间逐 tick 检查可以发现“最终恢复正常、过程中曾短暂非法”的问题。
     */
    @Test
    public void liveEntitiesRespectWorldInvariants() {
        Phase0EncounterHarness harness = Phase0EncounterHarness.baselineTwoGuardsV1();
        TETile[][] terrain = harness.terrainCopy();

        // 初始状态检查
        assertInvariants(harness, terrain);

        // 每 tick 检查
        for (int t = 0; t < 12; t++) {
            harness.step();
            assertInvariants(harness, terrain);
        }
    }

    private void assertInvariants(Phase0EncounterHarness harness, TETile[][] terrain) {
        Player p = harness.player();
        Enemy a = harness.guardA();
        Enemy b = harness.guardB();

        // 坐标安全：所有参与者位置都必须落在 terrain 数组范围内。
        assertInBounds(p.getPosition(), harness);
        assertInBounds(a.getPosition(), harness);
        assertInBounds(b.getPosition(), harness);

        // 地形安全：实体不能站在 WALL 或 NOTHING 上。
        assertTrue("player on standable tile",
                Entity.canStandOn(p.getPosition(), terrain));
        assertTrue("guardA on standable tile",
                Entity.canStandOn(a.getPosition(), terrain));
        assertTrue("guardB on standable tile",
                Entity.canStandOn(b.getPosition(), terrain));

        // 占位安全：只把活实体之间的位置唯一性当作 Phase 0 不变量。
        if (p.isAlive()) {
            assertNotSame("player/guardA overlap", p.getPosition(), a.getPosition());
            assertNotSame("player/guardB overlap", p.getPosition(), b.getPosition());
        }
        if (a.isAlive() && b.isAlive()) {
            assertNotSame("guardA/guardB overlap", a.getPosition(), b.getPosition());
        }
    }

    private void assertInBounds(Position pos, Phase0EncounterHarness harness) {
        assertTrue("x out of bounds: " + pos.x,
                pos.x >= 0 && pos.x < harness.width());
        assertTrue("y out of bounds: " + pos.y,
                pos.y >= 0 && pos.y < harness.height());
    }

    private void assertNotSame(String msg, Position a, Position b) {
        assertFalse(msg, a.x == b.x && a.y == b.y);
    }

    // ---------- P0-T08 ----------

    /**
     * P0-T08：程序生成世界的次要确定性 smoke test。
     * 它只证明相同非空 seed 在当前环境生成相同 terrain，不替代主 ASCII fixture。
     */
    @Test
    public void fixedSeedWorldIsDeterministicSmokeTest() {
        TETile[][] world1 = new TETile[80][30];
        for (int x = 0; x < 80; x++) {
            for (int y = 0; y < 30; y++) {
                world1[x][y] = Tileset.NOTHING;
            }
        }
        WorldGenerator.RandomSquareRoomWrd(world1, "smoketest42");

        TETile[][] world2 = new TETile[80][30];
        for (int x = 0; x < 80; x++) {
            for (int y = 0; y < 30; y++) {
                world2[x][y] = Tileset.NOTHING;
            }
        }
        WorldGenerator.RandomSquareRoomWrd(world2, "smoketest42");

        assertEquals("same seed should produce identical terrain",
                terrainText(world1), terrainText(world2));
    }

    private String terrainText(TETile[][] world) {
        StringBuilder sb = new StringBuilder();
        for (int y = world[0].length - 1; y >= 0; y--) {
            for (int x = 0; x < world.length; x++) {
                sb.append(world[x][y].character());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ---------- P0-T09 ----------

    /**
     * P0-T09：运行 canonical 12 tick，并与版本化 golden 文件逐字比较。
     * 测试只能读取 baseline，绝不能在失败时自动覆盖标准答案。
     */
    @Test
    public void ruleBaselineMatchesGolden() throws IOException {
        Path goldenPath = Paths.get("documents", "baselines",
                "phase0_rule_baseline_v1.json");

        assertTrue("baseline file must exist at " + goldenPath.toAbsolutePath(),
                Files.exists(goldenPath));

        Phase0EncounterHarness harness = Phase0EncounterHarness.baselineTwoGuardsV1();
        harness.runTicks(12);

        String golden = Files.readString(goldenPath, StandardCharsets.UTF_8);
        String expected = harness.buildBaselineJson();

        assertEquals("canonical baseline must match golden", golden, expected);
    }

    // ---------- 旧占位 helper；当前没有测试调用，应在后续清理 ----------

    /** 当前未使用；真实事件应从 harness.getTraceSink().events() 获取。 */
    List<AgentTrace.Event> getTraceEvents() {
        return null; // used only via harness.getTraceSink()
    }
}
