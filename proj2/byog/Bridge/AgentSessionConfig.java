package byog.Bridge;

import java.util.List;
import java.util.Objects;

/**
 * Immutable configuration snapshot for one remote-agent session.
 */
public final class AgentSessionConfig {
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 9876;
    public static final long DEFAULT_SOFT_DEADLINE_MS = 1500;
    public static final long DEFAULT_HARD_DEADLINE_MS = 10000;
    public static final long DEFAULT_CANCEL_GRACE_MS = 500;
    public static final int DEFAULT_OUTBOUND_CAPACITY = 32;
    public static final int DEFAULT_INBOUND_CAPACITY = 16;
    public static final int DEFAULT_PENDING_EVENT_CAPACITY = 16;
    public static final int DEFAULT_MAX_INBOUND_PER_POLL = 8;
    public static final int DEFAULT_MAX_FRAME_BYTES = 65536;
    public static final long DEFAULT_RECONNECT_INITIAL_MS = 250;
    public static final long DEFAULT_RECONNECT_MAX_MS = 4000;
    public static final long DEFAULT_SHUTDOWN_JOIN_MS = 1000;
    public static final long DEFAULT_HEARTBEAT_TICKS = 120;

    private final boolean enabled;
    private final String host;
    private final int port;
    private final long softDeadlineMs;
    private final long hardDeadlineMs;
    private final long cancelGraceMs;
    private final int outboundCapacity;
    private final int inboundCapacity;
    private final int pendingEventCapacity;
    private final int maxInboundPerPoll;
    private final int maxFrameBytes;
    private final long reconnectInitialMs;
    private final long reconnectMaxMs;
    private final long shutdownJoinMs;
    private final long heartbeatTicks;
    private final AgentProtocol.CapabilitiesData capabilities;

    private AgentSessionConfig(Builder builder) {
        enabled = builder.enabled;
        host = requireNonBlank(builder.host, "host");
        port = requireRange(builder.port, 1, 65535, "port");
        softDeadlineMs = requirePositive(
                builder.softDeadlineMs, "softDeadlineMs");
        hardDeadlineMs = requirePositive(
                builder.hardDeadlineMs, "hardDeadlineMs");
        if (hardDeadlineMs <= softDeadlineMs) {
            throw new IllegalArgumentException(
                    "hardDeadlineMs must be greater than softDeadlineMs");
        }
        cancelGraceMs = requirePositive(
                builder.cancelGraceMs, "cancelGraceMs");
        outboundCapacity = requireMinimum(
                builder.outboundCapacity, 4, "outboundCapacity");
        inboundCapacity = requireMinimum(
                builder.inboundCapacity, 4, "inboundCapacity");
        pendingEventCapacity = requireMinimum(
                builder.pendingEventCapacity, 4, "pendingEventCapacity");
        maxInboundPerPoll = requireMinimum(
                builder.maxInboundPerPoll, 1, "maxInboundPerPoll");
        maxFrameBytes = requireRange(
                builder.maxFrameBytes, 1024, 1048576, "maxFrameBytes");
        reconnectInitialMs = requirePositive(
                builder.reconnectInitialMs, "reconnectInitialMs");
        reconnectMaxMs = requirePositive(
                builder.reconnectMaxMs, "reconnectMaxMs");
        if (reconnectMaxMs < reconnectInitialMs) {
            throw new IllegalArgumentException(
                    "reconnectMaxMs must be at least reconnectInitialMs");
        }
        shutdownJoinMs = requirePositive(
                builder.shutdownJoinMs, "shutdownJoinMs");
        heartbeatTicks = requirePositive(
                builder.heartbeatTicks, "heartbeatTicks");
        capabilities = new AgentProtocol.CapabilitiesData(
                builder.supportedSkills,
                requireMinimum(builder.sightRange, 0, "sightRange"),
                requireMinimum(builder.attackDamage, 0, "attackDamage"),
                requireMinimum(builder.moveInterval, 1, "moveInterval"));
        validateNanosecondConversion(softDeadlineMs, "softDeadlineMs");
        validateNanosecondConversion(hardDeadlineMs, "hardDeadlineMs");
        validateNanosecondConversion(cancelGraceMs, "cancelGraceMs");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static AgentSessionConfig defaults() {
        return builder().build();
    }

    /** Returns a builder initialized from this immutable configuration. */
    public Builder toBuilder() {
        return builder()
                .enabled(enabled)
                .host(host)
                .port(port)
                .softDeadlineMs(softDeadlineMs)
                .hardDeadlineMs(hardDeadlineMs)
                .cancelGraceMs(cancelGraceMs)
                .outboundCapacity(outboundCapacity)
                .inboundCapacity(inboundCapacity)
                .pendingEventCapacity(pendingEventCapacity)
                .maxInboundPerPoll(maxInboundPerPoll)
                .maxFrameBytes(maxFrameBytes)
                .reconnectInitialMs(reconnectInitialMs)
                .reconnectMaxMs(reconnectMaxMs)
                .shutdownJoinMs(shutdownJoinMs)
                .heartbeatTicks(heartbeatTicks)
                .capabilities(
                        capabilities.supportedSkills(),
                        capabilities.sightRange(),
                        capabilities.attackDamage(),
                        capabilities.moveInterval());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public long getSoftDeadlineMs() {
        return softDeadlineMs;
    }

    public long getHardDeadlineMs() {
        return hardDeadlineMs;
    }

    public long getCancelGraceMs() {
        return cancelGraceMs;
    }

    public int getOutboundCapacity() {
        return outboundCapacity;
    }

    public int getInboundCapacity() {
        return inboundCapacity;
    }

    public int getPendingEventCapacity() {
        return pendingEventCapacity;
    }

    public int getMaxInboundPerPoll() {
        return maxInboundPerPoll;
    }

    public int getMaxFrameBytes() {
        return maxFrameBytes;
    }

    public long getReconnectInitialMs() {
        return reconnectInitialMs;
    }

    public long getReconnectMaxMs() {
        return reconnectMaxMs;
    }

    public long getShutdownJoinMs() {
        return shutdownJoinMs;
    }

    public long getHeartbeatTicks() {
        return heartbeatTicks;
    }

    public AgentProtocol.CapabilitiesData getCapabilities() {
        return capabilities;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static int requireMinimum(int value, int minimum, String name) {
        if (value < minimum) {
            throw new IllegalArgumentException(
                    name + " must be at least " + minimum);
        }
        return value;
    }

    private static long requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int requireRange(
            int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static void validateNanosecondConversion(long value, String name) {
        try {
            Math.multiplyExact(value, 1_000_000L);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(name + " is too large", e);
        }
    }

    /**
     * Builds validated immutable configuration snapshots.
     */
    public static final class Builder {
        private boolean enabled;
        private String host = DEFAULT_HOST;
        private int port = DEFAULT_PORT;
        private long softDeadlineMs = DEFAULT_SOFT_DEADLINE_MS;
        private long hardDeadlineMs = DEFAULT_HARD_DEADLINE_MS;
        private long cancelGraceMs = DEFAULT_CANCEL_GRACE_MS;
        private int outboundCapacity = DEFAULT_OUTBOUND_CAPACITY;
        private int inboundCapacity = DEFAULT_INBOUND_CAPACITY;
        private int pendingEventCapacity = DEFAULT_PENDING_EVENT_CAPACITY;
        private int maxInboundPerPoll = DEFAULT_MAX_INBOUND_PER_POLL;
        private int maxFrameBytes = DEFAULT_MAX_FRAME_BYTES;
        private long reconnectInitialMs = DEFAULT_RECONNECT_INITIAL_MS;
        private long reconnectMaxMs = DEFAULT_RECONNECT_MAX_MS;
        private long shutdownJoinMs = DEFAULT_SHUTDOWN_JOIN_MS;
        private long heartbeatTicks = DEFAULT_HEARTBEAT_TICKS;
        private List<String> supportedSkills = List.of(
                AgentProtocol.Skill.PATROL.name(),
                AgentProtocol.Skill.CHASE.name(),
                AgentProtocol.Skill.ATTACK.name(),
                AgentProtocol.Skill.GUARD.name());
        private int sightRange = 7;
        private int attackDamage = 10;
        private int moveInterval = 5;

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder host(String value) {
            host = value;
            return this;
        }

        public Builder port(int value) {
            port = value;
            return this;
        }

        public Builder softDeadlineMs(long value) {
            softDeadlineMs = value;
            return this;
        }

        public Builder hardDeadlineMs(long value) {
            hardDeadlineMs = value;
            return this;
        }

        public Builder cancelGraceMs(long value) {
            cancelGraceMs = value;
            return this;
        }

        public Builder outboundCapacity(int value) {
            outboundCapacity = value;
            return this;
        }

        public Builder inboundCapacity(int value) {
            inboundCapacity = value;
            return this;
        }

        public Builder pendingEventCapacity(int value) {
            pendingEventCapacity = value;
            return this;
        }

        public Builder maxInboundPerPoll(int value) {
            maxInboundPerPoll = value;
            return this;
        }

        public Builder maxFrameBytes(int value) {
            maxFrameBytes = value;
            return this;
        }

        public Builder reconnectInitialMs(long value) {
            reconnectInitialMs = value;
            return this;
        }

        public Builder reconnectMaxMs(long value) {
            reconnectMaxMs = value;
            return this;
        }

        public Builder shutdownJoinMs(long value) {
            shutdownJoinMs = value;
            return this;
        }

        public Builder heartbeatTicks(long value) {
            heartbeatTicks = value;
            return this;
        }

        public Builder capabilities(
                List<String> skills, int newSightRange,
                int newAttackDamage, int newMoveInterval) {
            supportedSkills = List.copyOf(skills);
            sightRange = newSightRange;
            attackDamage = newAttackDamage;
            moveInterval = newMoveInterval;
            return this;
        }

        public AgentSessionConfig build() {
            return new AgentSessionConfig(this);
        }
    }
}
