package byog.Test;

import byog.Action.Action;
import byog.Action.ActionOutcome;
import byog.Action.ActionQueue;
import byog.AI.AiTickContext;
import byog.AI.AiTickLoop;
import byog.Bridge.AgentProtocol;
import byog.Entity.Enemy;
import byog.Entity.EntityManager;
import byog.Entity.Player;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import org.junit.Test;

import java.util.Collections;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Deterministic Step 2.2 tests for the bridge-disabled production AI loop.
 */
public class Phase2AiTickTest {

    /** ActionQueue keeps only a bounded future prefix across ticks. */
    @Test
    public void action_queue_uses_low_and_high_water_marks() {
        ActionQueue queue = new ActionQueue(2, 5);
        Action wait = (world, entity) -> Action.ActionResult.SUCCESS;

        assertEquals(5, queue.appendBounded(
                Collections.nCopies(9, wait)));
        assertEquals(5, queue.size());
        assertFalse(queue.needRefill());

        queue.poll();
        queue.poll();
        queue.poll();
        assertEquals(2, queue.size());
        assertTrue(queue.needRefill());

        assertEquals(3, queue.appendBounded(
                Collections.nCopies(9, wait)));
        assertEquals(5, queue.size());

        queue.clear();
        queue.enqueueAll(Collections.nCopies(9, wait));
        assertEquals(5, queue.size());
    }

    /**
     * P2-A09: one cooldown attempts one action. A blocked plan is retried on
     * a later logical tick, not by consuming several queued actions at once.
     */
    @Test
    public void one_action_per_cooldown() {
        Fixture fixture = fixture(1, new Position(1, 1),
                new Position(5, 1));
        Enemy blocker = enemy(new Position(2, 1), 99, "blocker");
        fixture.entityMgr.addEntity(blocker);
        fixture.prime();

        fixture.tick(0);
        ActionOutcome first = fixture.enemy.getLastActionOutcome();
        assertNotNull(first);
        assertEquals(Action.ActionResult.BLOCKED, first.getResult());
        assertEquals(new Position(1, 1), fixture.enemy.getPosition());
        assertEquals(1, first.getActionIndex());

        fixture.tick(1);
        ActionOutcome second = fixture.enemy.getLastActionOutcome();
        assertNotNull(second);
        assertEquals(Action.ActionResult.BLOCKED, second.getResult());
        assertEquals(new Position(1, 1), fixture.enemy.getPosition());
        assertEquals(2, second.getActionIndex());
        assertEquals(1L, second.getLogicalTick());
    }

    /**
     * P2-A10: outcome.afterPosition and the new observation are completed only
     * after EntityManager has committed the move.
     */
    @Test
    public void feedback_uses_committed_position() {
        Fixture fixture = fixture(1, new Position(1, 1),
                new Position(5, 1));
        fixture.prime();
        AiTickContext context = fixture.context(0);

        fixture.enemy.executeOneAction(
                context, fixture.world, fixture.entityMgr);
        assertTrue(fixture.enemy.hasPendingActionOutcome());
        assertEquals(fixture.enemy,
                fixture.entityMgr.findEntityAt(new Position(1, 1)));
        assertNull(fixture.entityMgr.findEntityAt(new Position(2, 1)));
        try {
            fixture.enemy.collectAgentUpdates(
                    context, fixture.world, fixture.entityMgr, fixture.player);
            fail("collect must reject a pre-commit moved entity");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("commit barrier"));
        }

        fixture.entityMgr.flushPendingChanges();
        fixture.entityMgr.removeDeadEntities();
        fixture.enemy.collectAgentUpdates(
                context, fixture.world, fixture.entityMgr, fixture.player);

        ActionOutcome outcome = fixture.enemy.getLastActionOutcome();
        assertNotNull(outcome);
        assertEquals(new Position(1, 1), outcome.getBeforePosition());
        assertEquals(new Position(2, 1), outcome.getAfterPosition());
        assertEquals(new Position(2, 1),
                fixture.enemy.getLatestObservation().getSelfPosition());
        assertEquals(fixture.enemy,
                fixture.entityMgr.findEntityAt(new Position(2, 1)));
        assertEquals(AgentProtocol.DecisionSource.LOCAL_FALLBACK,
                outcome.getDecisionSource());
        assertEquals("phase2-test", outcome.getRunId());
        assertEquals(3, outcome.getFloorId());
    }

    /**
     * P2-R02: the production split loop preserves the Phase 1 increment-first
     * cooldown cadence. moveInterval=3 acts on ticks 2, 5, ...
     */
    @Test
    public void phase1_action_cadence_preserved() {
        Fixture fixture = fixture(3, new Position(1, 1),
                new Position(7, 1));
        fixture.prime();

        fixture.productionTick(0);
        assertEquals(new Position(1, 1), fixture.enemy.getPosition());
        fixture.productionTick(1);
        assertEquals(new Position(1, 1), fixture.enemy.getPosition());
        fixture.productionTick(2);
        assertEquals(new Position(2, 1), fixture.enemy.getPosition());

        fixture.productionTick(3);
        assertEquals(new Position(2, 1), fixture.enemy.getPosition());
        fixture.productionTick(4);
        assertEquals(new Position(2, 1), fixture.enemy.getPosition());
        fixture.productionTick(5);
        assertEquals(new Position(3, 1), fixture.enemy.getPosition());
    }

    /** Dead enemies are removed and their runtime seam is closed in one tick. */
    @Test
    public void dead_enemy_runtime_is_closed_after_commit() {
        Fixture fixture = fixture(1, new Position(1, 1),
                new Position(5, 1));
        fixture.enemy.die();

        fixture.productionTick(0);

        assertTrue(fixture.enemy.isAgentRuntimeClosed());
        assertNull(fixture.entityMgr.findEntityAt(new Position(1, 1)));
    }

    private static Fixture fixture(int moveInterval, Position enemyPosition,
                                   Position playerPosition) {
        TETile[][] world = corridorWorld(10, 3);
        EntityManager entityMgr = new EntityManager();
        Player player = new Player(playerPosition, 100, 10);
        Enemy enemy = enemy(enemyPosition, moveInterval, "guard-a");
        entityMgr.addEntity(player);
        entityMgr.addEntity(enemy);
        return new Fixture(world, entityMgr, player, enemy);
    }

    private static Enemy enemy(Position position, int moveInterval,
                               String agentId) {
        return new Enemy(position, Tileset.ENEMY,
                20, 10, moveInterval, 1, 0,
                new Random(42), agentId);
    }

    private static TETile[][] corridorWorld(int width, int height) {
        TETile[][] world = new TETile[width][height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                world[x][y] = y == 1 ? Tileset.FLOOR : Tileset.WALL;
            }
        }
        return world;
    }

    private static final class Fixture {
        private final TETile[][] world;
        private final EntityManager entityMgr;
        private final Player player;
        private final Enemy enemy;

        private Fixture(TETile[][] world, EntityManager entityMgr,
                        Player player, Enemy enemy) {
            this.world = world;
            this.entityMgr = entityMgr;
            this.player = player;
            this.enemy = enemy;
        }

        private AiTickContext context(long tick) {
            return new AiTickContext("phase2-test", 3, tick);
        }

        private void prime() {
            entityMgr.flushPendingChanges();
            enemy.collectAgentUpdates(
                    context(0), world, entityMgr, player);
        }

        private void tick(long tick) {
            AiTickContext context = context(tick);
            enemy.pollAgentMessages(context);
            enemy.executeOneAction(context, world, entityMgr);
            entityMgr.flushPendingChanges();
            entityMgr.removeDeadEntities();
            enemy.collectAgentUpdates(context, world, entityMgr, player);
        }

        private void productionTick(long tick) {
            AiTickLoop.run(context(tick), world, entityMgr, player);
        }
    }
}
