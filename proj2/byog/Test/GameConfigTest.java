package byog.Test;

import byog.Bridge.AgentSessionConfig;
import byog.Common.Difficulty;
import byog.Entity.Enemy;
import byog.IO.GameConfig;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import org.junit.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Verifies startup parsing, safe fallback, and immutable Agent settings. */
public class GameConfigTest {

    @Test
    public void enemyAttackIntervalIsUnifiedAndFallbackSafe() {
        // 攻击间隔按产品要求三难度统一（enemy.attackInterval=5）；
        // 移动速率与视野由配置决定，不锁死具体值（用户可调）。
        for (Difficulty difficulty : Difficulty.values()) {
            GameConfig config = new GameConfig(difficulty);
            assertEquals(
                    "attackInterval must be unified for " + difficulty,
                    5, config.enemyAttackInterval);
            assertTrue("moveInterval must be positive for " + difficulty,
                    config.enemyMoveInterval > 0);
            assertTrue("sightRange must be positive for " + difficulty,
                    config.enemySightRange > 0);
        }
        // 缺失 key 时回退安全默认
        GameConfig missing = new GameConfig(
                Difficulty.BALANCED, new Properties());
        assertEquals(5, missing.enemyAttackInterval);
    }

    @Test
    public void missingAgentPropertiesUseSafeDefaults() {
        GameConfig config = new GameConfig(
                Difficulty.BALANCED, new Properties());
        AgentSessionConfig session = config.toAgentSessionConfig();

        assertFalse(session.isEnabled());
        assertEquals(AgentSessionConfig.DEFAULT_HOST, session.getHost());
        assertEquals(AgentSessionConfig.DEFAULT_PORT, session.getPort());
        assertEquals(AgentSessionConfig.DEFAULT_SOFT_DEADLINE_MS,
                session.getSoftDeadlineMs());
        assertEquals(AgentSessionConfig.DEFAULT_HARD_DEADLINE_MS,
                session.getHardDeadlineMs());
        assertEquals(2, config.agentActionQueueLowWater);
        assertEquals(5, config.agentActionQueueHighWater);
    }

    @Test
    public void validAgentPropertiesFlowIntoSessionAndEnemyQueue() {
        Properties values = new Properties();
        values.setProperty("agent.bridge.enabled", "true");
        values.setProperty("agent.bridge.host", "localhost");
        values.setProperty("agent.bridge.port", "12345");
        values.setProperty("agent.bridge.softDeadlineMs", "25");
        values.setProperty("agent.bridge.hardDeadlineMs", "250");
        values.setProperty("agent.bridge.cancelGraceMs", "10");
        values.setProperty("agent.bridge.outboundCapacity", "7");
        values.setProperty("agent.bridge.inboundCapacity", "6");
        values.setProperty("agent.bridge.pendingEventCapacity", "5");
        values.setProperty("agent.bridge.maxInboundPerPoll", "3");
        values.setProperty("agent.bridge.maxFrameBytes", "4096");
        values.setProperty("agent.bridge.reconnectInitialMs", "20");
        values.setProperty("agent.bridge.reconnectMaxMs", "80");
        values.setProperty("agent.bridge.shutdownJoinMs", "40");
        values.setProperty("agent.bridge.heartbeatTicks", "9");
        values.setProperty("agent.actionQueue.lowWater", "1");
        values.setProperty("agent.actionQueue.highWater", "3");

        GameConfig config = new GameConfig(Difficulty.BALANCED, values);
        AgentSessionConfig session = config.toAgentSessionConfig();
        assertTrue(session.isEnabled());
        assertEquals("localhost", session.getHost());
        assertEquals(12345, session.getPort());
        assertEquals(25, session.getSoftDeadlineMs());
        assertEquals(250, session.getHardDeadlineMs());
        assertEquals(7, session.getOutboundCapacity());
        assertEquals(3, session.getMaxInboundPerPoll());
        assertEquals(4096, session.getMaxFrameBytes());
        assertEquals(20, session.getReconnectInitialMs());
        assertEquals(80, session.getReconnectMaxMs());
        assertEquals(9, session.getHeartbeatTicks());

        List<Enemy> enemies = Enemy.spawnEnemies(
                openWorld(24, 16), "config-contract",
                new Position(12, 8), 0, 1, config);
        assertFalse(enemies.isEmpty());
        for (Enemy enemy : enemies) {
            assertEquals(1, enemy.getActionQueue().getLowWater());
            assertEquals(3, enemy.getActionQueue().getHighWater());
        }
    }

    @Test
    public void invalidValuesAndRelationsFallBackTogether() {
        Properties values = new Properties();
        values.setProperty("agent.bridge.enabled", "maybe");
        values.setProperty("agent.bridge.host", "   ");
        values.setProperty("agent.bridge.port", "70000");
        values.setProperty("agent.bridge.softDeadlineMs", "5000");
        values.setProperty("agent.bridge.hardDeadlineMs", "100");
        values.setProperty("agent.bridge.cancelGraceMs", "0");
        values.setProperty("agent.bridge.outboundCapacity", "3");
        values.setProperty("agent.bridge.maxFrameBytes", "999");
        values.setProperty("agent.bridge.reconnectInitialMs", "500");
        values.setProperty("agent.bridge.reconnectMaxMs", "100");
        values.setProperty("agent.actionQueue.lowWater", "8");
        values.setProperty("agent.actionQueue.highWater", "4");

        GameConfig config = new GameConfig(Difficulty.BALANCED, values);
        AgentSessionConfig session = config.toAgentSessionConfig();
        assertFalse(session.isEnabled());
        assertEquals(AgentSessionConfig.DEFAULT_HOST, session.getHost());
        assertEquals(AgentSessionConfig.DEFAULT_PORT, session.getPort());
        assertEquals(AgentSessionConfig.DEFAULT_SOFT_DEADLINE_MS,
                session.getSoftDeadlineMs());
        assertEquals(AgentSessionConfig.DEFAULT_HARD_DEADLINE_MS,
                session.getHardDeadlineMs());
        assertEquals(AgentSessionConfig.DEFAULT_CANCEL_GRACE_MS,
                session.getCancelGraceMs());
        assertEquals(AgentSessionConfig.DEFAULT_OUTBOUND_CAPACITY,
                session.getOutboundCapacity());
        assertEquals(AgentSessionConfig.DEFAULT_MAX_FRAME_BYTES,
                session.getMaxFrameBytes());
        assertEquals(AgentSessionConfig.DEFAULT_RECONNECT_INITIAL_MS,
                session.getReconnectInitialMs());
        assertEquals(AgentSessionConfig.DEFAULT_RECONNECT_MAX_MS,
                session.getReconnectMaxMs());
        assertEquals(2, config.agentActionQueueLowWater);
        assertEquals(5, config.agentActionQueueHighWater);
    }

    @Test
    public void parsedSnapshotDoesNotFollowLaterPropertyMutation() {
        Properties values = new Properties();
        values.setProperty("agent.bridge.port", "12000");
        GameConfig config = new GameConfig(Difficulty.BALANCED, values);
        values.setProperty("agent.bridge.port", "13000");

        assertEquals(12000, config.agentBridgePort);
        assertEquals(12000, config.toAgentSessionConfig().getPort());
    }

    private static TETile[][] openWorld(int width, int height) {
        TETile[][] world = new TETile[width][height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                world[x][y] = x == 0 || y == 0
                        || x == width - 1 || y == height - 1
                        ? Tileset.WALL : Tileset.FLOOR;
            }
        }
        return world;
    }
}
