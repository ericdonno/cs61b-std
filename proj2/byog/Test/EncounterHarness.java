package byog.Test;

import byog.AI.AiTickContext;
import byog.AI.AiTickLoop;
import byog.Bridge.AgentProtocol;
import byog.Bridge.AgentSession;
import byog.Bridge.AgentSessionConfig;
import byog.Bridge.AgentTransport;
import byog.Bridge.IdGenerator;
import byog.Bridge.MonotonicClock;
import byog.Entity.EntityManager;
import byog.Entity.Enemy;
import byog.Entity.Player;
import byog.Trace.AgentTrace;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Shared deterministic two-guard fixture for legacy and private-perception
 * contracts. It is the only ASCII parser and scheduler for this scenario.
 */
public final class EncounterHarness {
    public enum Mode {
        LEGACY(
                AgentTrace.LEGACY_DECISION_TRACE_VERSION,
                "baseline-two-guards",
                false,
                false),
        PRIVATE_PERCEPTION(
                AgentTrace.PRIVATE_PERCEPTION_TRACE_VERSION,
                "baseline-two-guards-perception",
                true,
                false),
        AGENT_BRIDGE(
                AgentTrace.AGENT_RUNTIME_TRACE_VERSION,
                "agent-two-guards",
                true,
                true);

        private final String traceSchema;
        private final String scenarioId;
        private final boolean perceptionEnabled;
        private final boolean productionTickLoop;

        Mode(String traceSchema, String scenarioId,
             boolean perceptionEnabled, boolean productionTickLoop) {
            this.traceSchema = traceSchema;
            this.scenarioId = scenarioId;
            this.perceptionEnabled = perceptionEnabled;
            this.productionTickLoop = productionTickLoop;
        }
    }

    private static final int SCENARIO_VERSION = 1;
    private static final String RUN_ID = "agent-harness-run";
    private static final int FLOOR_ID = 1;
    private static final String[] BASELINE_ASCII = {
        "#################",
        "#.......#......>#",
        "#.......#...B...#",
        "#.......#####.###",
        "#...............#",
        "#..P.....A......#",
        "#...............#",
        "#################"
    };

    private final Mode mode;
    private final TETile[][] world;
    private final Player player;
    private final Enemy guardA;
    private final Enemy guardB;
    private final EntityManager entityMgr;
    private final Position stairsPos;
    private final AgentTrace.InMemorySink traceSink;
    private final FakeClock clock;
    private final AgentSession guardASession;
    private final AgentSession guardBSession;
    private long guardAInboundSequence;
    private long guardBInboundSequence;
    private long logicalTick;

    private EncounterHarness(Mode mode, TETile[][] world, Player player,
                             Enemy guardA, Enemy guardB,
                             EntityManager entityMgr, Position stairsPos) {
        this.mode = mode;
        this.world = world;
        this.player = player;
        this.guardA = guardA;
        this.guardB = guardB;
        this.entityMgr = entityMgr;
        this.stairsPos = stairsPos;
        this.traceSink = new AgentTrace.InMemorySink();
        guardA.setPerceptionEnabled(mode.perceptionEnabled);
        guardB.setPerceptionEnabled(mode.perceptionEnabled);
        if (mode.productionTickLoop) {
            clock = new FakeClock();
            guardASession = newSession(guardA, clock);
            guardBSession = newSession(guardB, clock);
            guardA.attachAgentSession(guardASession);
            guardB.attachAgentSession(guardBSession);
            guardASession.transportEndpoint().markConnected(0);
            guardBSession.transportEndpoint().markConnected(0);
        } else {
            clock = null;
            guardASession = null;
            guardBSession = null;
        }
    }

    public static EncounterHarness legacyV1() {
        return fromAscii(Mode.LEGACY, BASELINE_ASCII, 1000, 101, 202);
    }

    public static EncounterHarness privatePerceptionV1() {
        return fromAscii(
                Mode.PRIVATE_PERCEPTION, BASELINE_ASCII, 1000, 101, 202);
    }

    public static EncounterHarness agentBridgeV1() {
        return fromAscii(
                Mode.AGENT_BRIDGE, BASELINE_ASCII, 1000, 101, 202);
    }

    public static EncounterHarness fromAscii(
            Mode mode, String[] ascii, int playerHp,
            long guardASeed, long guardBSeed) {
        if (mode == null || ascii == null || ascii.length == 0
                || ascii[0] == null || ascii[0].isEmpty()) {
            throw new IllegalArgumentException("mode and non-empty ASCII are required");
        }
        int height = ascii.length;
        int width = ascii[0].length();
        TETile[][] world = new TETile[width][height];
        Position playerPos = null;
        Position guardAPos = null;
        Position guardBPos = null;
        Position stairsPos = null;

        for (int row = 0; row < height; row++) {
            if (ascii[row] == null || ascii[row].length() != width) {
                throw new IllegalArgumentException("ASCII rows must have equal width");
            }
            int y = height - 1 - row;
            for (int x = 0; x < width; x++) {
                char cell = ascii[row].charAt(x);
                switch (cell) {
                    case '#':
                        world[x][y] = Tileset.WALL;
                        break;
                    case '.':
                        world[x][y] = Tileset.FLOOR;
                        break;
                    case '>':
                        stairsPos = unique(stairsPos, x, y, "stairs");
                        world[x][y] = Tileset.STAIRS;
                        break;
                    case 'P':
                        playerPos = unique(playerPos, x, y, "player");
                        world[x][y] = Tileset.FLOOR;
                        break;
                    case 'A':
                        guardAPos = unique(guardAPos, x, y, "guard A");
                        world[x][y] = Tileset.FLOOR;
                        break;
                    case 'B':
                        guardBPos = unique(guardBPos, x, y, "guard B");
                        world[x][y] = Tileset.FLOOR;
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "Unknown ASCII cell: " + cell);
                }
            }
        }

        requirePresent(playerPos, "player");
        requirePresent(guardAPos, "guard A");
        requirePresent(guardBPos, "guard B");
        requirePresent(stairsPos, "stairs");

        Player player = new Player(playerPos, playerHp, 10);
        Enemy guardA = new Enemy(guardAPos, Tileset.ENEMY,
                20, 7, 1, 1, 0, new Random(guardASeed), "guard-a");
        Enemy guardB = new Enemy(guardBPos, Tileset.ENEMY,
                20, 7, 1, 1, 0, new Random(guardBSeed), "guard-b");
        EntityManager entities = new EntityManager();
        entities.addEntity(player);
        entities.addEntity(guardA);
        entities.addEntity(guardB);
        return new EncounterHarness(
                mode, world, player, guardA, guardB, entities, stairsPos);
    }

    private static Position unique(
            Position current, int x, int y, String name) {
        if (current != null) {
            throw new IllegalArgumentException("Duplicate " + name);
        }
        return new Position(x, y);
    }

    private static void requirePresent(Position position, String name) {
        if (position == null) {
            throw new IllegalArgumentException("Missing " + name);
        }
    }

    public void step() {
        if (mode.productionTickLoop) {
            AiTickContext tickContext = new AiTickContext(
                    RUN_ID, FLOOR_ID, logicalTick, null, traceSink);
            AiTickLoop.run(tickContext, world, entityMgr, player);
            logicalTick++;
            return;
        }
        AgentTrace.Context guardAContext = context("guard-a");
        AgentTrace.Context guardBContext = context("guard-b");
        guardA.updateAI(world, entityMgr, player, guardAContext, traceSink);
        guardB.updateAI(world, entityMgr, player, guardBContext, traceSink);
        entityMgr.flushPendingChanges();
        entityMgr.removeDeadEntities();
        logicalTick++;
    }

    private AgentTrace.Context context(String actorKey) {
        return new AgentTrace.Context(
                mode.traceSchema, mode.scenarioId, SCENARIO_VERSION,
                logicalTick, actorKey);
    }

    public void runTicks(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0");
        }
        for (int i = 0; i < count; i++) {
            step();
        }
    }

    public String canonicalTraceJson() {
        return traceSink.toCanonicalJson();
    }

    public String canonicalTrace() {
        return canonicalTraceJson();
    }

    /** Advances only the injected monotonic clock. */
    public void advanceClockMs(long milliseconds) {
        requireAgentMode();
        clock.advanceMilliseconds(milliseconds);
    }

    /** Delivers one already-decoded inbound message to the named Agent. */
    public AgentSession.InboundEnqueueResult injectInbound(
            String stableAgentId, AgentProtocol.Envelope envelope) {
        AgentSession session = session(stableAgentId);
        return session.transportEndpoint().offerInbound(
                envelope, logicalTick);
    }

    /** Drains messages that the named Session has queued for its transport. */
    public List<AgentProtocol.Envelope> drainOutbound(
            String stableAgentId) {
        AgentSession session = session(stableAgentId);
        List<AgentProtocol.Envelope> messages = new ArrayList<>();
        AgentProtocol.Envelope envelope;
        while ((envelope = session.transportEndpoint().pollOutbound())
                != null) {
            messages.add(envelope);
        }
        return messages;
    }

    /** Builds a valid deterministic PATROL response for an observation request. */
    public AgentProtocol.Envelope patrolResponse(
            String stableAgentId, AgentProtocol.Envelope request) {
        requireAgentMode();
        if (request.type != AgentProtocol.MessageType.OBSERVATION
                || !(request.data
                instanceof AgentProtocol.ObservationData observation)) {
            throw new IllegalArgumentException(
                    "request must contain observation data");
        }
        AgentProtocol.IntentData intent = new AgentProtocol.IntentData(
                AgentProtocol.INTENT_VERSION,
                AgentProtocol.Skill.PATROL,
                java.util.Collections.emptyMap(),
                0.75,
                10,
                new AgentProtocol.InterruptPolicyData(true, true, true));
        AgentProtocol.SubmitIntentData response =
                new AgentProtocol.SubmitIntentData(
                        observation.decisionId(),
                        observation.observationSeq(),
                        observation.requestGeneration(), intent);
        long sequence = nextInboundSequence(stableAgentId);
        return new AgentProtocol.Envelope(
                AgentProtocol.ENVELOPE_VERSION,
                stableAgentId + "-inbound-" + sequence,
                sequence,
                request.worldId,
                request.runId,
                request.floorId,
                request.agentId,
                request.sessionEpoch,
                logicalTick,
                AgentProtocol.MessageType.SUBMIT_INTENT,
                response);
    }

    /** Returns the deterministic Session for one scenario role. */
    public AgentSession session(String stableAgentId) {
        requireAgentMode();
        if ("guard-a".equals(stableAgentId)) {
            return guardASession;
        }
        if ("guard-b".equals(stableAgentId)) {
            return guardBSession;
        }
        throw new IllegalArgumentException(
                "unknown scenario agent: " + stableAgentId);
    }

    /** Closes only the Sessions owned by this harness. */
    public void close() {
        if (guardASession != null) {
            guardASession.close();
        }
        if (guardBSession != null) {
            guardBSession.close();
        }
    }

    public String canonicalState() {
        StringBuilder result = new StringBuilder();
        result.append("{\"logicalTick\":").append(logicalTick);
        appendActor(result, "player", player.getPosition(), player.isAlive());
        appendActor(result, "guard-a", guardA.getPosition(), guardA.isAlive());
        appendActor(result, "guard-b", guardB.getPosition(), guardB.isAlive());
        result.append(",\"stairs\":{\"x\":").append(stairsPos.x)
                .append(",\"y\":").append(stairsPos.y).append("}}");
        return result.toString();
    }

    private static void appendActor(
            StringBuilder result, String key, Position position, boolean alive) {
        result.append(",\"").append(key).append("\":{\"x\":")
                .append(position.x).append(",\"y\":").append(position.y)
                .append(",\"alive\":").append(alive).append("}");
    }

    public TETile[][] terrainCopy() {
        TETile[][] copy = new TETile[width()][height()];
        for (int x = 0; x < width(); x++) {
            System.arraycopy(world[x], 0, copy[x], 0, height());
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
        return new Position(stairsPos.x, stairsPos.y);
    }

    public long getLogicalTick() {
        return logicalTick;
    }

    public AgentTrace.InMemorySink getTraceSink() {
        return traceSink;
    }

    public int width() {
        return world.length;
    }

    public int height() {
        return world[0].length;
    }

    private static AgentSession newSession(
            Enemy enemy, MonotonicClock clock) {
        AgentSessionConfig config = AgentSessionConfig.builder()
                .enabled(true)
                .softDeadlineMs(100)
                .hardDeadlineMs(200)
                .cancelGraceMs(50)
                .outboundCapacity(8)
                .inboundCapacity(8)
                .pendingEventCapacity(8)
                .maxInboundPerPoll(8)
                .heartbeatTicks(20)
                .build();
        AgentProtocol.Identity identity = new AgentProtocol.Identity(
                "world-test", RUN_ID, FLOOR_ID, enemy.getAgentId(), 0, 0);
        return new AgentSession(
                config, identity, clock,
                new IdGenerator.DeterministicIdGenerator(
                        enemy.getAgentId() + "-decision",
                        enemy.getAgentId() + "-message"),
                AgentTransport.noOp());
    }

    private long nextInboundSequence(String stableAgentId) {
        if ("guard-a".equals(stableAgentId)) {
            return guardAInboundSequence++;
        }
        if ("guard-b".equals(stableAgentId)) {
            return guardBInboundSequence++;
        }
        throw new IllegalArgumentException(
                "unknown scenario agent: " + stableAgentId);
    }

    private void requireAgentMode() {
        if (!mode.productionTickLoop) {
            throw new IllegalStateException(
                    "operation requires an Agent bridge harness");
        }
    }

    private static final class FakeClock implements MonotonicClock {
        private long nowNanos;

        @Override
        public long nanoTime() {
            return nowNanos;
        }

        private void advanceMilliseconds(long milliseconds) {
            if (milliseconds < 0) {
                throw new IllegalArgumentException(
                        "milliseconds must be non-negative");
            }
            nowNanos = Math.addExact(
                    nowNanos, Math.multiplyExact(milliseconds, 1_000_000L));
        }
    }
}
