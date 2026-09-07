package byog.IO;

import byog.Action.ActionQueue;
import byog.Bridge.AgentSessionConfig;
import byog.Common.Difficulty;
import byog.Helper.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Properties;

/**
 * 游戏平衡配置管理。从 config/game.properties 加载，文件不存在时自动生成默认配置。
 */
public class GameConfig {
    private static final String CONFIG_PATH = "config/game.properties";

    public final Difficulty difficulty;
    public final int playerHp;
    public final int playerAttack;
    public final int playerDamageVariance;
    public final int playerMaxCharge;
    public final int playerChargeRate;
    public final int enemyHp;
    public final int enemyAttack;
    public final int enemyDamageVariance;
    public final int enemyMoveInterval;
    public final int enemyAttackInterval;
    public final int enemySightRange;
    public final int enemyBaseCount;
    public final int healthPackMinCount;
    public final int healthPackMaxCount;
    public final int healthPackHealAmount;
    public final boolean debugShowEnemyFov;
    public final boolean agentBridgeEnabled;
    public final String agentBridgeHost;
    public final int agentBridgePort;
    public final long agentBridgeSoftDeadlineMs;
    public final long agentBridgeHardDeadlineMs;
    public final long agentBridgeCancelGraceMs;
    public final int agentBridgeOutboundCapacity;
    public final int agentBridgeInboundCapacity;
    public final int agentBridgePendingEventCapacity;
    public final int agentBridgeMaxInboundPerPoll;
    public final int agentBridgeMaxFrameBytes;
    public final long agentBridgeReconnectInitialMs;
    public final long agentBridgeReconnectMaxMs;
    public final long agentBridgeShutdownJoinMs;
    public final long agentBridgeHeartbeatTicks;
    public final int agentActionQueueLowWater;
    public final int agentActionQueueHighWater;

    public GameConfig(Difficulty difficulty) {
        this(difficulty, load());
    }

    /** Parses one immutable configuration snapshot from supplied properties. */
    public GameConfig(Difficulty difficulty, Properties props) {
        this.difficulty = Objects.requireNonNull(difficulty, "difficulty");
        Objects.requireNonNull(props, "props");
        Properties runtimeProperties = withInteractiveAgentOverride(props);
        String prefix = difficulty.getKey() + ".";

        playerHp = getInt(props, prefix + "player.hp", 100);
        playerAttack = getInt(props, prefix + "player.attack", 15);
        playerDamageVariance = getInt(props, prefix + "player.damageVariance", 5);
        playerMaxCharge = getInt(props, prefix + "player.maxCharge", 100);
        playerChargeRate = getInt(props, prefix + "player.chargeRate", 2);
        enemyHp = getInt(props, prefix + "enemy.hp", 20);
        enemyAttack = getInt(props, prefix + "enemy.attack", 10);
        enemyDamageVariance = getInt(props, prefix + "enemy.damageVariance", 3);
        enemyMoveInterval = getInt(props, prefix + "enemy.moveInterval", 3);
        enemyAttackInterval = getIntAtLeast(
                props, prefix + "enemy.attackInterval", 5, 1);
        enemySightRange = getInt(props, prefix + "enemy.sightRange", 10);
        enemyBaseCount = getInt(props, prefix + "enemy.baseCount", 3);
        int hpMin = getIntAtLeast(
                props, prefix + "healthPack.minCount", 2, 0);
        int hpHeal = getIntAtLeast(
                props, prefix + "healthPack.healAmount", 20, 1);
        int hpMax = getIntAtLeast(
                props, prefix + "healthPack.maxCount", 4, 0);
        if (hpMax < hpMin) {
            logInvalidRelation(
                    prefix + "healthPack.minCount", hpMin,
                    prefix + "healthPack.maxCount", hpMax,
                    2, 4);
            hpMin = 2;
            hpMax = 4;
        }
        healthPackMinCount = hpMin;
        healthPackMaxCount = hpMax;
        healthPackHealAmount = hpHeal;
        debugShowEnemyFov = getBoolean(props, "debug.showEnemyFov", false);

        agentBridgeEnabled = getBoolean(
                runtimeProperties, "agent.bridge.enabled", false);
        agentBridgeHost = getNonBlank(
                runtimeProperties, "agent.bridge.host",
                AgentSessionConfig.DEFAULT_HOST);
        agentBridgePort = getIntInRange(
                runtimeProperties, "agent.bridge.port",
                AgentSessionConfig.DEFAULT_PORT, 1, 65535);

        long softDeadline = getPositiveLong(
                props, "agent.bridge.softDeadlineMs",
                AgentSessionConfig.DEFAULT_SOFT_DEADLINE_MS);
        long hardDeadline = getPositiveLong(
                props, "agent.bridge.hardDeadlineMs",
                AgentSessionConfig.DEFAULT_HARD_DEADLINE_MS);
        if (hardDeadline <= softDeadline) {
            logInvalidRelation(
                    "agent.bridge.softDeadlineMs", softDeadline,
                    "agent.bridge.hardDeadlineMs", hardDeadline,
                    AgentSessionConfig.DEFAULT_SOFT_DEADLINE_MS,
                    AgentSessionConfig.DEFAULT_HARD_DEADLINE_MS);
            softDeadline = AgentSessionConfig.DEFAULT_SOFT_DEADLINE_MS;
            hardDeadline = AgentSessionConfig.DEFAULT_HARD_DEADLINE_MS;
        }
        agentBridgeSoftDeadlineMs = softDeadline;
        agentBridgeHardDeadlineMs = hardDeadline;
        agentBridgeCancelGraceMs = getPositiveLong(
                props, "agent.bridge.cancelGraceMs",
                AgentSessionConfig.DEFAULT_CANCEL_GRACE_MS);
        agentBridgeOutboundCapacity = getIntAtLeast(
                props, "agent.bridge.outboundCapacity",
                AgentSessionConfig.DEFAULT_OUTBOUND_CAPACITY, 4);
        agentBridgeInboundCapacity = getIntAtLeast(
                props, "agent.bridge.inboundCapacity",
                AgentSessionConfig.DEFAULT_INBOUND_CAPACITY, 4);
        agentBridgePendingEventCapacity = getIntAtLeast(
                props, "agent.bridge.pendingEventCapacity",
                AgentSessionConfig.DEFAULT_PENDING_EVENT_CAPACITY, 4);
        agentBridgeMaxInboundPerPoll = getIntAtLeast(
                props, "agent.bridge.maxInboundPerPoll",
                AgentSessionConfig.DEFAULT_MAX_INBOUND_PER_POLL, 1);
        agentBridgeMaxFrameBytes = getIntInRange(
                props, "agent.bridge.maxFrameBytes",
                AgentSessionConfig.DEFAULT_MAX_FRAME_BYTES,
                1024, 1048576);

        long reconnectInitial = getPositiveLong(
                props, "agent.bridge.reconnectInitialMs",
                AgentSessionConfig.DEFAULT_RECONNECT_INITIAL_MS);
        long reconnectMax = getPositiveLong(
                props, "agent.bridge.reconnectMaxMs",
                AgentSessionConfig.DEFAULT_RECONNECT_MAX_MS);
        if (reconnectMax < reconnectInitial) {
            logInvalidRelation(
                    "agent.bridge.reconnectInitialMs", reconnectInitial,
                    "agent.bridge.reconnectMaxMs", reconnectMax,
                    AgentSessionConfig.DEFAULT_RECONNECT_INITIAL_MS,
                    AgentSessionConfig.DEFAULT_RECONNECT_MAX_MS);
            reconnectInitial = AgentSessionConfig.DEFAULT_RECONNECT_INITIAL_MS;
            reconnectMax = AgentSessionConfig.DEFAULT_RECONNECT_MAX_MS;
        }
        agentBridgeReconnectInitialMs = reconnectInitial;
        agentBridgeReconnectMaxMs = reconnectMax;
        agentBridgeShutdownJoinMs = getPositiveLong(
                props, "agent.bridge.shutdownJoinMs",
                AgentSessionConfig.DEFAULT_SHUTDOWN_JOIN_MS);
        agentBridgeHeartbeatTicks = getPositiveLong(
                props, "agent.bridge.heartbeatTicks",
                AgentSessionConfig.DEFAULT_HEARTBEAT_TICKS);

        int lowWater = getIntAtLeast(
                props, "agent.actionQueue.lowWater",
                ActionQueue.DEFAULT_LOW_WATER, 0);
        int highWater = getIntAtLeast(
                props, "agent.actionQueue.highWater",
                ActionQueue.DEFAULT_HIGH_WATER, 1);
        if (highWater <= lowWater) {
            logInvalidRelation(
                    "agent.actionQueue.lowWater", lowWater,
                    "agent.actionQueue.highWater", highWater,
                    ActionQueue.DEFAULT_LOW_WATER,
                    ActionQueue.DEFAULT_HIGH_WATER);
            lowWater = ActionQueue.DEFAULT_LOW_WATER;
            highWater = ActionQueue.DEFAULT_HIGH_WATER;
        }
        agentActionQueueLowWater = lowWater;
        agentActionQueueHighWater = highWater;
    }

    /** Lets the single-click launcher inject its temporary local runtime. */
    private static Properties withInteractiveAgentOverride(Properties props) {
        String runtimePort = System.getProperty("dungeonmind.agent.port");
        if (runtimePort == null || runtimePort.trim().isEmpty()) {
            return props;
        }
        Properties merged = new Properties();
        merged.putAll(props);
        merged.setProperty("agent.bridge.enabled", "true");
        merged.setProperty("agent.bridge.port", runtimePort);
        String runtimeHost = System.getProperty(
                "dungeonmind.agent.host", AgentSessionConfig.DEFAULT_HOST);
        merged.setProperty("agent.bridge.host", runtimeHost);
        return merged;
    }

    /** Builds the validated immutable subset consumed by each Agent Session. */
    public AgentSessionConfig toAgentSessionConfig() {
        return AgentSessionConfig.builder()
                .enabled(agentBridgeEnabled)
                .host(agentBridgeHost)
                .port(agentBridgePort)
                .softDeadlineMs(agentBridgeSoftDeadlineMs)
                .hardDeadlineMs(agentBridgeHardDeadlineMs)
                .cancelGraceMs(agentBridgeCancelGraceMs)
                .outboundCapacity(agentBridgeOutboundCapacity)
                .inboundCapacity(agentBridgeInboundCapacity)
                .pendingEventCapacity(agentBridgePendingEventCapacity)
                .maxInboundPerPoll(agentBridgeMaxInboundPerPoll)
                .maxFrameBytes(agentBridgeMaxFrameBytes)
                .reconnectInitialMs(agentBridgeReconnectInitialMs)
                .reconnectMaxMs(agentBridgeReconnectMaxMs)
                .shutdownJoinMs(agentBridgeShutdownJoinMs)
                .heartbeatTicks(agentBridgeHeartbeatTicks)
                .build();
    }

    /** 加载配置文件，不存在则自动生成 */
    private static Properties load() {
        File file = new File(CONFIG_PATH);
        if (!file.exists()) {
            generateDefaultConfigFile();
        }
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
        } catch (IOException e) {
            Logger.error("Failed to load config file: %s, using defaults.", e.getMessage());
        }
        return props;
    }

    /** 从 Properties 读取 int，失败则返回默认值 */
    private static int getInt(Properties props, String key, int defaultValue) {
        String value = props.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            Logger.error("Invalid config value for '%s': %s, using default %d",
                    key, value, defaultValue);
            return defaultValue;
        }
    }

    /** Reads an integer constrained to an inclusive range. */
    private static int getIntInRange(
            Properties props, String key, int defaultValue,
            int minimum, int maximum) {
        String value = props.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed >= minimum && parsed <= maximum) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // The shared fallback below records the bad key and raw value.
        }
        logInvalidValue(key, value, defaultValue);
        return defaultValue;
    }

    /** Reads an integer with a required minimum. */
    private static int getIntAtLeast(
            Properties props, String key, int defaultValue, int minimum) {
        return getIntInRange(
                props, key, defaultValue, minimum, Integer.MAX_VALUE);
    }

    /** Reads a positive long that can be safely converted to nanoseconds. */
    private static long getPositiveLong(
            Properties props, String key, long defaultValue) {
        String value = props.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed > 0 && parsed <= Long.MAX_VALUE / 1_000_000L) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // The shared fallback below records the bad key and raw value.
        }
        logInvalidValue(key, value, defaultValue);
        return defaultValue;
    }

    /** Reads a trimmed non-blank string. */
    private static String getNonBlank(
            Properties props, String key, String defaultValue) {
        String value = props.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        String trimmed = value.trim();
        if (!trimmed.isEmpty()) {
            return trimmed;
        }
        logInvalidValue(key, value, defaultValue);
        return defaultValue;
    }

    /** 从 Properties 读取 boolean，失败则返回默认值 */
    private static boolean getBoolean(Properties props, String key, boolean defaultValue) {
        String value = props.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        String trimmed = value.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return false;
        }
        logInvalidValue(key, value, defaultValue);
        return defaultValue;
    }

    private static void logInvalidValue(
            String key, Object value, Object defaultValue) {
        Logger.error(
                "Invalid config value for '%s': %s, using default %s",
                key, value, defaultValue);
    }

    private static void logInvalidRelation(
            String firstKey, long firstValue,
            String secondKey, long secondValue,
            long firstDefault, long secondDefault) {
        Logger.error(
                "Invalid config relation '%s'=%d, '%s'=%d, using defaults %d/%d",
                firstKey, firstValue, secondKey, secondValue,
                firstDefault, secondDefault);
    }

    /** 生成包含三难度默认配置的 properties 文件 */
    public static void generateDefaultConfigFile() {
        File file = new File(CONFIG_PATH);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            writer.write("# Generated by GameConfig - edit to customize difficulty balance\n");
            writer.write("\n");
            writer.write("# ========== Easy ==========\n");
            writer.write("easy.player.hp=150\n");
            writer.write("easy.player.attack=12\n");
            writer.write("easy.player.damageVariance=3\n");
            writer.write("easy.player.maxCharge=80\n");
            writer.write("easy.player.chargeRate=3\n");
            writer.write("easy.enemy.hp=15\n");
            writer.write("easy.enemy.attack=5\n");
            writer.write("easy.enemy.damageVariance=2\n");
            writer.write("easy.enemy.moveInterval=3\n");
            writer.write("easy.enemy.attackInterval=5\n");
            writer.write("easy.enemy.sightRange=10\n");
            writer.write("easy.enemy.baseCount=2\n");
            writer.write("easy.healthPack.minCount=3\n");
            writer.write("easy.healthPack.maxCount=5\n");
            writer.write("easy.healthPack.healAmount=25\n");
            writer.write("\n");
            writer.write("# ========== Balanced ==========\n");
            writer.write("balanced.player.hp=100\n");
            writer.write("balanced.player.attack=15\n");
            writer.write("balanced.player.damageVariance=5\n");
            writer.write("balanced.player.maxCharge=100\n");
            writer.write("balanced.player.chargeRate=2\n");
            writer.write("balanced.enemy.hp=20\n");
            writer.write("balanced.enemy.attack=10\n");
            writer.write("balanced.enemy.damageVariance=3\n");
            writer.write("balanced.enemy.moveInterval=3\n");
            writer.write("balanced.enemy.attackInterval=5\n");
            writer.write("balanced.enemy.sightRange=10\n");
            writer.write("balanced.enemy.baseCount=3\n");
            writer.write("balanced.healthPack.minCount=2\n");
            writer.write("balanced.healthPack.maxCount=4\n");
            writer.write("balanced.healthPack.healAmount=20\n");
            writer.write("\n");
            writer.write("# ========== Hardcore ==========\n");
            writer.write("hardcore.player.hp=50\n");
            writer.write("hardcore.player.attack=10\n");
            writer.write("hardcore.player.damageVariance=8\n");
            writer.write("hardcore.player.maxCharge=150\n");
            writer.write("hardcore.player.chargeRate=1\n");
            writer.write("hardcore.enemy.hp=30\n");
            writer.write("hardcore.enemy.attack=15\n");
            writer.write("hardcore.enemy.damageVariance=5\n");
            writer.write("hardcore.enemy.moveInterval=3\n");
            writer.write("hardcore.enemy.attackInterval=5\n");
            writer.write("hardcore.enemy.sightRange=10\n");
            writer.write("hardcore.enemy.baseCount=4\n");
            writer.write("hardcore.healthPack.minCount=1\n");
            writer.write("hardcore.healthPack.maxCount=3\n");
            writer.write("hardcore.healthPack.healAmount=15\n");
            writer.write("\n");
            writer.write("# ========== Debug ==========\n");
            writer.write("debug.showEnemyFov=false\n");
            writer.write("\n");
            writer.write("# ========== Agent Bridge ==========\n");
            writer.write("agent.bridge.enabled=false\n");
            writer.write("agent.bridge.host=127.0.0.1\n");
            writer.write("agent.bridge.port=9876\n");
            writer.write("agent.bridge.softDeadlineMs=1500\n");
            writer.write("agent.bridge.hardDeadlineMs=10000\n");
            writer.write("agent.bridge.cancelGraceMs=500\n");
            writer.write("agent.bridge.outboundCapacity=32\n");
            writer.write("agent.bridge.inboundCapacity=16\n");
            writer.write("agent.bridge.pendingEventCapacity=16\n");
            writer.write("agent.bridge.maxInboundPerPoll=8\n");
            writer.write("agent.bridge.maxFrameBytes=65536\n");
            writer.write("agent.bridge.reconnectInitialMs=250\n");
            writer.write("agent.bridge.reconnectMaxMs=4000\n");
            writer.write("agent.bridge.shutdownJoinMs=1000\n");
            writer.write("agent.bridge.heartbeatTicks=120\n");
            writer.write("agent.actionQueue.lowWater=2\n");
            writer.write("agent.actionQueue.highWater=5\n");
            writer.flush();
            Logger.info("Default config file generated: %s", CONFIG_PATH);
        } catch (IOException e) {
            Logger.error("Failed to generate config file: %s", e.getMessage());
        }
    }
}
