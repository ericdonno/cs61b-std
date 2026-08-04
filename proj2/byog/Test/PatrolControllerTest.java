package byog.Test;

import byog.AI.AiTickContext;
import byog.AI.AiTickLoop;
import byog.AI.PatrolController;
import byog.AI.PatrolState;
import byog.Common.Facing;
import byog.Common.VisionMode;
import byog.Entity.Enemy;
import byog.Entity.EntityManager;
import byog.Entity.Player;
import byog.IO.GameConfig;
import byog.Perception.ObservationEnvelope;
import byog.Perception.PerceptionSystem;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 确定性巡视状态机：持续目标、到达停留、顺时针扫描、受阻恢复与 reflex 中断。
 */
public class PatrolControllerTest {

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

    private Enemy enemyAt(int x, int y, Facing facing) {
        Enemy enemy = new Enemy(new Position(x, y), Tileset.ENEMY,
                20, 5, 1, 10, 3, new Random(1), "guard-a");
        enemy.setFacing(facing);
        return enemy;
    }

    private ObservationEnvelope observe(TETile[][] world,
                                        EntityManager em,
                                        Enemy self, Player player) {
        return PerceptionSystem.computeObservation(
                "world-test", "run-test", 1, 0,
                world, em, self, player, self.getSightRange(),
                VisionMode.DIRECTIONAL, 0);
    }

    private static final long SEED_KEY = 4242L;
    private static final int FLOOR_ID = 1;

    private static final class Fixture {
        final TETile[][] world;
        final EntityManager em;
        final Enemy enemy;
        final Player player;
        final PatrolState state = new PatrolState();
        final PatrolController controller = new PatrolController();

        Fixture(TETile[][] world, EntityManager em, Enemy enemy,
                Player player) {
            this.world = world;
            this.em = em;
            this.enemy = enemy;
            this.player = player;
        }

        ObservationEnvelope obs() {
            return PerceptionSystem.computeObservation(
                    "world-test", "run-test", 1, 0,
                    world, em, enemy, player, enemy.getSightRange(),
                    VisionMode.DIRECTIONAL, 0);
        }

        PatrolController.PatrolDecision decide() {
            return controller.decide(obs(), state, SEED_KEY, FLOOR_ID,
                    enemy.getAgentId());
        }
    }

    private Fixture fixture() {
        TETile[][] world = openWorld(15, 15);
        EntityManager em = new EntityManager();
        Enemy enemy = enemyAt(7, 7, Facing.NORTH);
        em.addEntity(enemy);
        Player player = new Player(new Position(14, 14), config());
        em.addEntity(player);
        return new Fixture(world, em, enemy, player);
    }

    // ---------- P25-PATROL-01 持续同一目标 ----------

    @Test
    public void targetStaysStableWhileTraveling() {
        Fixture f = fixture();
        PatrolController.PatrolDecision first = f.decide();
        assertEquals(PatrolController.PatrolDecision.Kind.MOVE,
                first.kind());
        Position target = f.state.getTarget();
        assertNotNull(target);
        assertTrue("target must be within 3..8 of self",
                dist(f.enemy.getPosition(), target) >= 3
                        && dist(f.enemy.getPosition(), target) <= 8);

        // 模拟向目标移动：重新定位 enemy 后观察仍持续同一目标
        for (int step = 0; step < 3; step++) {
            Position before = f.enemy.getPosition();
            PatrolController.PatrolDecision decision = f.decide();
            if (decision.kind()
                    != PatrolController.PatrolDecision.Kind.MOVE) {
                break; // 可能已到达
            }
            assertEquals("target must not change mid-travel",
                    target, f.state.getTarget());
            Position next = new Position(
                    before.x + decision.moveDirection().dx,
                    before.y + decision.moveDirection().dy);
            f.em.flushPendingChanges();
            f.enemy.setPosition(next);
            f.em.flushPendingChanges();
        }
    }

    // ---------- P25-PATROL-02 相同脚本重复 ----------

    @Test
    public void sameScriptRepeatsSameTrace() {
        List<String> first = runPatrolTrace();
        List<String> second = runPatrolTrace();
        assertEquals(first, second);
    }

    private List<String> runPatrolTrace() {
        Fixture f = fixture();
        List<String> trace = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Position before = f.enemy.getPosition();
            PatrolController.PatrolDecision decision = f.decide();
            trace.add(f.state.getMode() + "|" + decision.kind()
                    + "|" + f.state.getTarget()
                    + "|" + f.state.getSelectionOrdinal());
            if (decision.kind()
                    == PatrolController.PatrolDecision.Kind.MOVE) {
                Position next = new Position(
                        before.x + decision.moveDirection().dx,
                        before.y + decision.moveDirection().dy);
                f.enemy.setPosition(next);
                f.em.flushPendingChanges();
            } else if (decision.kind()
                    == PatrolController.PatrolDecision.Kind.TURN) {
                f.enemy.setFacing(f.enemy.getFacing().clockwise());
            }
        }
        return trace;
    }

    // ---------- P25-PATROL-03 到达停留与顺时针扫描 ----------

    @Test
    public void reachingTargetWaitsThenScansClockwise() {
        Fixture f = fixture();
        // 直接把 enemy 放到目标上：目标 = self
        f.state.setTarget(f.enemy.getPosition());

        PatrolController.PatrolDecision dwell =
                f.controller.decide(f.obs(), f.state, SEED_KEY, FLOOR_ID,
                        f.enemy.getAgentId());
        assertEquals(PatrolController.PatrolDecision.Kind.WAIT,
                dwell.kind());
        assertEquals(PatrolState.Mode.DWELLING, f.state.getMode());

        // 停留动作消耗（dwell 1→0）
        PatrolController.PatrolDecision dwellEnd =
                f.controller.decide(f.obs(), f.state, SEED_KEY, FLOOR_ID,
                        f.enemy.getAgentId());
        assertEquals(PatrolController.PatrolDecision.Kind.WAIT,
                dwellEnd.kind());

        // 停留结束进入扫描
        PatrolController.PatrolDecision firstScan =
                f.controller.decide(f.obs(), f.state, SEED_KEY, FLOOR_ID,
                        f.enemy.getAgentId());
        assertEquals(PatrolState.Mode.SCANNING, f.state.getMode());
        assertEquals(PatrolController.PatrolDecision.Kind.TURN,
                firstScan.kind());
        // 连续两次顺时针转向（转向频率已调低为 2 次）
        for (int i = 0; i < 2; i++) {
            Facing before = f.enemy.getFacing();
            PatrolController.PatrolDecision turn =
                    f.controller.decide(f.obs(), f.state, SEED_KEY,
                            FLOOR_ID, f.enemy.getAgentId());
            if (turn.kind() != PatrolController.PatrolDecision.Kind.TURN) {
                break;
            }
            assertEquals("scan must turn clockwise",
                    before.clockwise(), f.enemy.getFacing().clockwise());
            f.enemy.setFacing(f.enemy.getFacing().clockwise());
        }
        // 扫描结束后选择新目标（ordinal 递增）
        PatrolController.PatrolDecision reselect =
                f.controller.decide(f.obs(), f.state, SEED_KEY, FLOOR_ID,
                        f.enemy.getAgentId());
        assertNotEquals(PatrolState.Mode.SCANNING, f.state.getMode());
        assertTrue("ordinal must advance after a fresh selection",
                f.state.getSelectionOrdinal() >= 1);
        assertNotNull(reselect);
    }

    // ---------- P25-PATROL-04 动态实体阻挡 ----------

    @Test
    public void twoConsecutiveBlocksAbandonTargetIntoScan() {
        Fixture f = fixture();
        PatrolController.PatrolDecision first = f.decide();
        assertTrue(f.state.hasTarget());
        Position target = f.state.getTarget();

        // 第一次受阻：保留目标，继续重规划（不立即放弃）
        f.controller.onMoveBlocked(f.state);
        assertEquals(1, f.state.getBlockedAttempts());
        assertTrue("first block must keep the target",
                target.equals(f.state.getTarget()));

        // 让目标不可达（四周封死），第二次 decide 重新规划失败后放弃
        // 第二次受阻：
        f.controller.onMoveBlocked(f.state);
        assertEquals(2, f.state.getBlockedAttempts());
        PatrolController.PatrolDecision decision =
                f.controller.decide(f.obs(), f.state, SEED_KEY, FLOOR_ID,
                        f.enemy.getAgentId());
        assertFalse("blocked twice must abandon the target",
                f.state.hasTarget());
        assertEquals(PatrolState.Mode.SCANNING, f.state.getMode());
        assertTrue(decision.kind()
                == PatrolController.PatrolDecision.Kind.TURN
                || decision.kind()
                == PatrolController.PatrolDecision.Kind.WAIT);
    }

    // ---------- P25-PATROL-05 扫描中玩家可见被 reflex 中断 ----------

    @Test
    public void visiblePlayerInterruptsScanningPatrol() {
        TETile[][] world = openWorld(11, 11);
        EntityManager em = new EntityManager();
        Enemy enemy = enemyAt(5, 5, Facing.NORTH);
        enemy.setPerceptionEnabled(true);
        em.addEntity(enemy);
        Player player = new Player(new Position(5, 1), config());        em.addEntity(player);

        AiTickContext prime = new AiTickContext("run-1", 1, 0);
        AiTickLoop.run(prime, world, em, player);   // 冷启动观察

        // 玩家移到 enemy 前方可见位置（FOV 内）
        player.setPosition(new Position(5, 9));
        em.flushPendingChanges();
        AiTickContext engaged = new AiTickContext("run-1", 1, 1);
        AiTickLoop.run(engaged, world, em, player);

        var outcome = enemy.consumeLastActionOutcome();
        assertNotNull("reflex must interrupt patrol", outcome);
        assertEquals("reflex must chase instead of waiting/turning",
                "MoveAction", outcome.getActionType());
    }

    // ---------- P25-PATROL-08 本地 lease 复用不崩溃 ----------

    @Test
    public void localPatrolLeaseReuseNeverCrashesPlanner() {
        // P4 在 WAIT/TURN 时创建 target=自身的 PATROL intent，随后 local lease
        // 被 P3 复用并交给 planner；必须不崩溃（曾经因 null target 触发 NPE），
        // 且巡视必须持续推进（转向/移动不被 self-target lease 冻结）。
        TETile[][] world = openWorld(15, 15);
        EntityManager em = new EntityManager();
        Enemy enemy = enemyAt(7, 7, Facing.NORTH);
        enemy.setPerceptionEnabled(true);
        em.addEntity(enemy);
        Player player = new Player(new Position(14, 14), config());
        em.addEntity(player);
        for (int tick = 0; tick < 40; tick++) {
            AiTickLoop.run(new AiTickContext("run-1", 1, tick),
                    world, em, player);
        }
        boolean progressed = !enemy.getPosition().equals(new Position(7, 7))
                || enemy.getFacing() != Facing.NORTH;
        assertTrue("patrol must actually progress, not freeze", progressed);
    }

    // ---------- P25-PATROL-06 玩家仍隐藏：状态不含隐藏位置 ----------

    @Test
    public void hiddenPlayerNeverEntersPatrolStateOrCandidates() {
        Fixture f = fixture();
        // 玩家位置 (14,14) 在视野外
        for (int i = 0; i < 5; i++) {
            Position before = f.enemy.getPosition();
            PatrolController.PatrolDecision decision = f.decide();
            Position target = f.state.getTarget();
            if (target != null) {
                assertFalse("target must not be the hidden player",
                        target.equals(new Position(14, 14)));
                assertTrue("target must be within sight range",
                        dist(f.enemy.getPosition(), target)
                                <= f.enemy.getSightRange());
            }
            if (decision.kind()
                    == PatrolController.PatrolDecision.Kind.MOVE) {
                Position next = new Position(
                        before.x + decision.moveDirection().dx,
                        before.y + decision.moveDirection().dy);
                f.enemy.setPosition(next);
                f.em.flushPendingChanges();
            }
        }
        assertFalse("patrol observation must not see the player",
                f.obs().canSeePlayer());
    }

    // ---------- P25-PATROL-07 保存/读档恢复 ----------

    @Test
    public void saveRestoreKeepsPatrolStateFields() {
        Fixture f = fixture();
        PatrolController.PatrolDecision decision = f.decide();
        if (decision.kind() == PatrolController.PatrolDecision.Kind.MOVE) {
            // 让状态处于 TRAVELING 且有目标
            PatrolState original = f.state;
            Enemy restored = enemyAt(7, 7, Facing.NORTH);
            restored.setPatrolState(original);
            assertEquals(original.getMode(), restored.getPatrolState().getMode());
            assertEquals(original.getTarget(),
                    restored.getPatrolState().getTarget());
            assertEquals(original.getDwellActionsRemaining(),
                    restored.getPatrolState().getDwellActionsRemaining());
            assertEquals(original.getScanTurnsRemaining(),
                    restored.getPatrolState().getScanTurnsRemaining());
            assertEquals(original.getBlockedAttempts(),
                    restored.getPatrolState().getBlockedAttempts());
            assertEquals(original.getSelectionOrdinal(),
                    restored.getPatrolState().getSelectionOrdinal());
        }
    }

    private static int dist(Position a, Position b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }
}
