package byog.Test;

import byog.Entity.Enemy;
import byog.Perception.ObservationEnvelope;
import byog.Perception.PerceptionSystem;
import byog.Perception.VisibleEntity;
import byog.Trace.AgentTrace;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Phase 1 私有感知的契约测试（P1-T01 至 P1-T07）。
 *
 * <p>验证私有感知下的信息不对称效果：Guard A 能看到玩家，Guard B 被墙遮挡看不到，
 * trace 事件正确记录了 OBSERVATION_GENERATED 而非 LEGACY_DECISION_INPUT。</p>
 */
public class Phase1EncounterTest {

    // ────────── P1-T01 ──────────

    /** P1-T01：B 的 observation 中 canSeePlayer()==false，visibleEntities 不含 PLAYER */
    @Test
    public void guardB_cannot_see_player_behind_wall() {
        Phase1EncounterHarness harness = Phase1EncounterHarness.baselineTwoGuardsV1();
        // B 初始坐标 (12,5)，距离玩家 12，中间有墙遮挡

        ObservationEnvelope obs = PerceptionSystem.computeObservation(
                "p1-t01", 1, 0,
                harness.getWorld(), harness.getEntityMgr(),
                harness.guardB(), harness.player(),
                7, 0);

        assertFalse("B should not see player behind wall", obs.canSeePlayer());

        for (VisibleEntity ve : obs.getVisibleEntities()) {
            assertNotEquals("B's visible entities should not contain PLAYER",
                    VisibleEntity.EntityType.PLAYER, ve.getType());
        }
    }

    // ────────── P1-T02 ──────────

    /** P1-T02：A 的 observation 中 canSeePlayer()==true，player HP 可见 */
    @Test
    public void guardA_can_see_player_no_wall() {
        Phase1EncounterHarness harness = Phase1EncounterHarness.baselineTwoGuardsV1();

        ObservationEnvelope obs = PerceptionSystem.computeObservation(
                "p1-t02", 1, 0,
                harness.getWorld(), harness.getEntityMgr(),
                harness.guardA(), harness.player(),
                7, 0);

        assertTrue("A should see player with clear LOS", obs.canSeePlayer());

        VisibleEntity playerInfo = obs.getVisiblePlayer();
        assertNotNull("A should have player in visible entities", playerInfo);
        assertEquals("Visible player HP should match",
                harness.player().getHp(), playerInfo.getVisibleHp());
    }

    // ────────── P1-T03 ──────────

    /** P1-T03：同 tick 内 A 和 B 的 visibleEntityCount 不同 */
    @Test
    public void different_enemies_different_observations() {
        Phase1EncounterHarness harness = Phase1EncounterHarness.baselineTwoGuardsV1();

        ObservationEnvelope obsA = PerceptionSystem.computeObservation(
                "p1-t03", 1, 0,
                harness.getWorld(), harness.getEntityMgr(),
                harness.guardA(), harness.player(),
                7, 0);

        ObservationEnvelope obsB = PerceptionSystem.computeObservation(
                "p1-t03", 1, 0,
                harness.getWorld(), harness.getEntityMgr(),
                harness.guardB(), harness.player(),
                7, 0);

        int countA = obsA.getVisibleEntities().size();
        int countB = obsB.getVisibleEntities().size();
        assertNotEquals("A and B should see different visible entities",
                countA, countB);
    }

    // ────────── P1-T04 ──────────

    /** P1-T04：B 首次 intent 为 PATROL（看不见玩家），A 为 CHASE（看得见） */
    @Test
    public void brain_decision_based_on_private_obs() {
        Phase1EncounterHarness harness = Phase1EncounterHarness.baselineTwoGuardsV1();
        harness.runTicks(1);

        List<AgentTrace.TraceEvent> events = harness.getTraceSink().events();

        String guardAFirstGoal = findFirstGoal(events, "guard-a");
        String guardBFirstGoal = findFirstGoal(events, "guard-b");

        assertEquals("guard-a should CHASE in private perception", "CHASE", guardAFirstGoal);
        assertEquals("guard-b should PATROL in private perception", "PATROL", guardBFirstGoal);
    }

    private static String findFirstGoal(List<AgentTrace.TraceEvent> events, String actorKey) {
        for (AgentTrace.TraceEvent e : events) {
            if (e.actorKey.equals(actorKey)
                    && e.eventType == AgentTrace.EventType.INTENT_SELECTED) {
                return e.goal;
            }
        }
        return null;
    }

    // ────────── P1-T05 ──────────

    /** P1-T05：agentId 跨实例稳定，与 JVM 自增 id 无关 */
    @Test
    public void agentId_stable_across_instances() {
        Enemy e1 = new Enemy(new Position(0, 0), Tileset.ENEMY,
                20, 7, 1, 1, 0,
                new Random(42), "guard-a");
        Enemy e2 = new Enemy(new Position(5, 5), Tileset.ENEMY,
                20, 7, 1, 1, 0,
                new Random(99), "guard-a");

        assertEquals("agentId should be identical", e1.getAgentId(), e2.getAgentId());
        assertNotEquals("JVM id should differ across instances", e1.getId(), e2.getId());
    }

    // ────────── P1-T06 ──────────

    /** P1-T06：ObservationEnvelope 包含完整的身份与版本字段 */
    @Test
    public void observation_has_identity_fields() {
        Phase1EncounterHarness harness = Phase1EncounterHarness.baselineTwoGuardsV1();

        ObservationEnvelope obs = PerceptionSystem.computeObservation(
                "test-run", 3, 7,
                harness.getWorld(), harness.getEntityMgr(),
                harness.guardA(), harness.player(),
                7, 42);

        assertEquals("runId should match", "test-run", obs.getRunId());
        assertEquals("floorId should match", 3, obs.getFloorId());
        assertNotNull("agentId should not be null", obs.getAgentId());
        assertTrue("observationSeq should be >= 0", obs.getObservationSeq() >= 0);
        assertEquals("observedAtTurn should match", 42, obs.getObservedAtTurn());
    }

    // ────────── P1-T07 ──────────

    /** P1-T07：canonical trace 每 tick 包含 OBSERVATION_GENERATED 事件，A/B 的 visiblePlayer 不同 */
    @Test
    public void trace_contains_perception_events() {
        Phase1EncounterHarness harness = Phase1EncounterHarness.baselineTwoGuardsV1();
        harness.runTicks(2);

        List<AgentTrace.TraceEvent> events = harness.getTraceSink().events();

        boolean aHasObs = false;
        boolean bHasObs = false;
        Boolean aVisiblePlayer = null;
        Boolean bVisiblePlayer = null;

        for (AgentTrace.TraceEvent e : events) {
            if (e.eventType != AgentTrace.EventType.OBSERVATION_GENERATED) {
                continue;
            }
            if ("guard-a".equals(e.actorKey)) {
                aHasObs = true;
                aVisiblePlayer = e.visiblePlayer;
            } else if ("guard-b".equals(e.actorKey)) {
                bHasObs = true;
                bVisiblePlayer = e.visiblePlayer;
            }
        }

        assertTrue("guard-a should have OBSERVATION_GENERATED events", aHasObs);
        assertTrue("guard-b should have OBSERVATION_GENERATED events", bHasObs);
        assertNotNull("guard-a should have visiblePlayer", aVisiblePlayer);
        assertNotNull("guard-b should have visiblePlayer", bVisiblePlayer);
        assertNotEquals("A and B should see different visiblePlayer values",
                aVisiblePlayer, bVisiblePlayer);
    }

    // ────────── P1-T08 ──────────

    /** P1-T08：Phase 1 私有感知的 golden baseline 字节级匹配 */
    @Test
    public void phase1RuleBaselineMatchesGolden() throws IOException {
        Path goldenPath = Paths.get("documents", "baselines",
                "phase1_rule_baseline_v1.json");

        assertTrue("baseline file must exist at " + goldenPath.toAbsolutePath(),
                Files.exists(goldenPath));

        Phase1EncounterHarness harness = Phase1EncounterHarness.baselineTwoGuardsV1();
        harness.runTicks(12);

        String golden = Files.readString(goldenPath, StandardCharsets.UTF_8);
        String expected = harness.buildBaselineJson();

        assertEquals("canonical baseline must match golden", golden, expected);
    }
}
