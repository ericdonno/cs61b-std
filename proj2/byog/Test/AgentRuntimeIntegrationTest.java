package byog.Test;

import byog.Action.Action;
import byog.Action.ActionOutcome;
import byog.AI.AiTickContext;
import byog.AI.AiTickLoop;
import byog.AI.IntentLease;
import byog.Bridge.AgentProtocol;
import byog.Bridge.AgentSession;
import byog.Bridge.AgentSessionConfig;
import byog.Bridge.MonotonicClock;
import byog.Entity.Enemy;
import byog.Entity.EntityManager;
import byog.Entity.Player;
import byog.Helper.Logger;
import byog.Trace.AgentTrace;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;
import org.junit.Test;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Real-process verification for the Java game-thread bridge and Python runtime. */
public class AgentRuntimeIntegrationTest {
    private static final String RUN_ID = "runtime-integration";
    private static final int FLOOR_ID = 1;
    private static final long AWAIT_TIMEOUT_MS = 4000;
    private static Logger.Level previousLogLevel;

    @BeforeClass
    public static void silenceGameLogs() {
        previousLogLevel = Logger.getLevel();
        Logger.setLevel(Logger.Level.OFF);
    }

    @AfterClass
    public static void restoreGameLogs() {
        Logger.setLevel(previousLogLevel);
    }

    @Test
    public void javaPythonRoundTripKeepsDecisionAndFeedbackCorrelation()
            throws Exception {
        try (RuntimeProcess runtime = RuntimeProcess.start("normal", 0, 0.0);
             GameFixture fixture = new GameFixture(1)) {
            AgentSession session = fixture.attachSession(
                    fixture.enemies.get(0), runtime.port,
                    1000, 5000);
            awaitConnected(session);

            fixture.prime(0);
            await(() -> session.getInboundQueueSize() > 0,
                    "normal runtime did not return an intent");
            AgentSession.RequestContext request = session.getCurrentRequest();
            assertNotNull(request);

            fixture.tick(1);

            Enemy enemy = fixture.enemies.get(0);
            IntentLease lease = enemy.getArbiter().getCurrentLease();
            assertNotNull(lease);
            assertEquals(AgentProtocol.DecisionSource.REMOTE_AGENT,
                    lease.getDecisionSource());
            assertEquals(request.getDecisionId(), lease.getDecisionId());

            ActionOutcome outcome = enemy.getLastActionOutcome();
            assertNotNull(outcome);
            assertEquals(request.getDecisionId(), outcome.getDecisionId());
            assertEquals(AgentProtocol.DecisionSource.REMOTE_AGENT,
                    outcome.getDecisionSource());
            assertTrue(outcome.getActionIndex() >= 1);
            await(() -> runtime.containsOutput(
                            "Recorded action_feedback for agentId=guard-a"),
                    "Python runtime did not record committed feedback");
            assertTrue(hasTraceEvent(
                    fixture, AgentTrace.EventType.AGENT_REQUEST_SENT));
            assertTrue(hasTraceEvent(
                    fixture, AgentTrace.EventType.INTENT_ADOPTED));
            assertTrue(hasTraceEvent(
                    fixture,
                    AgentTrace.EventType.ACTION_FEEDBACK_ENQUEUED));
        }
    }

    @Test
    public void scriptedGraphIntentPassesJavaAuthorityAndCommitsAction()
            throws Exception {
        try (RuntimeProcess runtime = RuntimeProcess.start(
                "normal", 0, 0.0, "scripted");
             GameFixture fixture = new GameFixture(1)) {
            AgentSession session = fixture.attachSession(
                    fixture.enemies.get(0), runtime.port,
                    1000, 5000);
            awaitConnected(session);

            fixture.prime(0);
            await(() -> session.getInboundQueueSize() > 0,
                    "scripted graph returned no intent");
            fixture.tick(1);

            Enemy enemy = fixture.enemies.get(0);
            IntentLease lease = enemy.getArbiter().getCurrentLease();
            assertNotNull(lease);
            assertNotNull(lease.getIntent().getPlanMetadata());
            assertEquals("PATROL", lease.getIntent().getSkillId());
            assertEquals(AgentProtocol.DecisionSource.REMOTE_AGENT,
                    lease.getDecisionSource());
            assertNotNull(enemy.getLastActionOutcome());
            assertEquals(AgentProtocol.DecisionSource.REMOTE_AGENT,
                    enemy.getLastActionOutcome().getDecisionSource());

            AgentTrace.TraceEvent adopted = fixture.traceSink.events()
                    .stream()
                    .filter(event -> event.eventType
                            == AgentTrace.EventType.INTENT_ADOPTED)
                    .findFirst().orElseThrow();
            assertEquals("PATROL", adopted.skillId);
            assertNotNull(adopted.planId);
            assertEquals(AgentTrace.AGENT_RUNTIME_TRACE_VERSION,
                    adopted.schemaVersion);
        }
    }

    @Test
    public void scriptedFeedbackAdvancesToNextPlanStep() throws Exception {
        try (RuntimeProcess runtime = RuntimeProcess.start(
                "normal", 0, 0.0, "scripted");
             GameFixture fixture = new GameFixture(1)) {
            AgentSession session = fixture.attachSession(
                    fixture.enemies.get(0), runtime.port,
                    1000, 5000);
            awaitConnected(session);
            fixture.prime(0);
            await(() -> session.getInboundQueueSize() > 0,
                    "scripted graph returned no first step");
            fixture.tick(1);

            long tick = 1;
            ActionOutcome first = fixture.enemies.get(0).getLastActionOutcome();
            while (first != null
                    && first.getStepStatus() != AgentProtocol.StepStatus.SUCCEEDED
                    && tick < 12) {
                fixture.tick(++tick);
                first = fixture.enemies.get(0).getLastActionOutcome();
            }
            assertNotNull(first);
            assertEquals(AgentProtocol.StepStatus.SUCCEEDED,
                    first.getStepStatus());
            String planId = first.getPlanMetadata().planId();
            String firstStepId = first.getPlanMetadata().stepId();

            await(() -> session.getInboundQueueSize() > 0,
                    "scripted graph did not advance the plan");
            fixture.tick(++tick);
            IntentLease next = fixture.enemies.get(0)
                    .getArbiter().getCurrentLease();
            assertEquals("GUARD", next.getIntent().getSkillId());
            assertEquals(planId,
                    next.getIntent().getPlanMetadata().planId());
            assertNotEquals(firstStepId,
                    next.getIntent().getPlanMetadata().stepId());
        }
    }

    @Test
    public void twoEnemyConnectionsKeepIndependentIdentity() throws Exception {
        try (RuntimeProcess runtime = RuntimeProcess.start("normal", 0, 0.0);
             GameFixture fixture = new GameFixture(2)) {
            AgentSession first = fixture.attachSession(
                    fixture.enemies.get(0), runtime.port,
                    1000, 5000);
            AgentSession second = fixture.attachSession(
                    fixture.enemies.get(1), runtime.port,
                    1000, 5000);
            awaitConnected(first);
            awaitConnected(second);

            fixture.prime(0);
            await(() -> first.getInboundQueueSize() > 0,
                    "first runtime connection returned no intent");
            await(() -> second.getInboundQueueSize() > 0,
                    "second runtime connection returned no intent");
            String firstDecision = first.getCurrentRequest().getDecisionId();
            String secondDecision = second.getCurrentRequest().getDecisionId();
            assertNotEquals(firstDecision, secondDecision);

            fixture.tick(1);

            IntentLease firstLease = fixture.enemies.get(0)
                    .getArbiter().getCurrentLease();
            IntentLease secondLease = fixture.enemies.get(1)
                    .getArbiter().getCurrentLease();
            assertEquals(firstDecision, firstLease.getDecisionId());
            assertEquals(secondDecision, secondLease.getDecisionId());
            assertEquals("guard-a", first.getIdentity().agentId);
            assertEquals("guard-b", second.getIdentity().agentId);
            assertEquals(AgentSession.ConnectionState.CONNECTED,
                    first.getConnectionState());
            assertEquals(AgentSession.ConnectionState.CONNECTED,
                    second.getConnectionState());
        }
    }

    @Test
    public void slowRuntimeDoesNotStopLogicalTicksOrLocalPlan()
            throws Exception {
        try (RuntimeProcess runtime = RuntimeProcess.start("delay", 0, 0.5);
             GameFixture fixture = new GameFixture(1)) {
            AgentSession session = fixture.attachSession(
                    fixture.enemies.get(0), runtime.port,
                    50, 2000);
            awaitConnected(session);
            fixture.prime(0);
            await(() -> session.getCurrentRequest() != null,
                    "delayed runtime request did not start");

            Position before = copy(fixture.enemies.get(0).getPosition());
            long[] nextTick = {1};
            await(() -> {
                fixture.tick(nextTick[0]++);
                return session.getRequestState()
                        == AgentSession.RequestState.SOFT_TIMED_OUT;
            }, "delayed runtime did not cross the soft deadline");
            long started = System.nanoTime();
            for (long tick = nextTick[0]; tick < nextTick[0] + 8; tick++) {
                fixture.tick(tick);
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started);

            assertTrue("game-thread ticks waited for the delayed runtime",
                    elapsedMs < 500);
            assertNotEquals(before, fixture.enemies.get(0).getPosition());
            assertEquals(AgentSession.RequestState.SOFT_TIMED_OUT,
                    session.getRequestState());
            assertEquals(AgentProtocol.DecisionSource.LOCAL_FALLBACK,
                    fixture.enemies.get(0).getLastActionOutcome()
                            .getDecisionSource());
            assertTrue(hasTraceEvent(
                    fixture, AgentTrace.EventType.AGENT_SLOW));
        }
    }

    @Test
    public void malformedRuntimeIsRejectedWithoutRemoteSideEffects()
            throws Exception {
        try (RuntimeProcess runtime = RuntimeProcess.start(
                "malformed", 0, 0.0);
             GameFixture fixture = new GameFixture(1)) {
            Enemy enemy = fixture.enemies.get(0);
            AgentSession session = fixture.attachSession(
                    enemy, runtime.port, 1000, 5000);
            awaitConnected(session);
            Position before = copy(enemy.getPosition());
            fixture.prime(0);

            await(() -> hasLifecycleEvent(
                            session,
                            AgentSession.LifecycleEventType
                                    .INBOUND_PROTOCOL_FATAL),
                    "malformed runtime did not trigger protocol rejection");
            assertNull(enemy.getArbiter().getCurrentLease());
            assertEquals(0, enemy.getActionQueue().size());
            assertEquals(before, enemy.getPosition());

            fixture.tick(1);
            assertTrue(hasTraceEvent(
                    fixture, AgentTrace.EventType.PROTOCOL_ERROR));
            assertEquals(AgentProtocol.DecisionSource.LOCAL_FALLBACK,
                    enemy.getLastActionOutcome().getDecisionSource());
        }
    }

    @Test
    public void noReadBackpressureDoesNotBlockGameThread() throws Exception {
        try (RuntimeProcess runtime = RuntimeProcess.start("no-read", 0, 0.0);
             GameFixture fixture = new GameFixture(1)) {
            Enemy enemy = fixture.enemies.get(0);
            AgentSession session = fixture.attachSession(
                    enemy, runtime.port, 1000, 5000);
            awaitConnected(session);
            fixture.prime(0);

            boolean rejected = false;
            long enqueueStarted = System.nanoTime();
            for (int index = 0; index < 10000 && !rejected; index++) {
                ActionOutcome outcome = syntheticOutcome(enemy, index);
                rejected = session.sendActionFeedback(outcome, 0)
                        == AgentSession.EnqueueResult.REJECTED_CRITICAL;
            }
            long enqueueElapsedMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - enqueueStarted);
            assertTrue("bounded outbound queue never reported saturation",
                    rejected);
            assertTrue("outbound saturation blocked the caller",
                    enqueueElapsedMs < 1000);

            long tickStarted = System.nanoTime();
            for (long tick = 1; tick <= 20; tick++) {
                fixture.tick(tick);
            }
            long tickElapsedMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - tickStarted);
            assertTrue("game-thread ticks blocked after saturation",
                    tickElapsedMs < 1000);
            assertFalse(enemy.isAgentRuntimeClosed());
            assertTrue(hasTraceEvent(
                    fixture,
                    AgentTrace.EventType.OUTBOUND_MESSAGE_DROPPED));
        }
    }

    @Test
    public void runtimeRestartResumesOnlyWhenPollAdoptsFreshIntent()
            throws Exception {
        GameFixture fixture = new GameFixture(1);
        RuntimeProcess disconnecting = RuntimeProcess.start(
                "disconnect", 0, 0.0);
        RuntimeProcess recovered = null;
        try {
            int port = disconnecting.port;
            Enemy enemy = fixture.enemies.get(0);
            AgentSession session = fixture.attachSession(
                    enemy, port, 1000, 5000);
            awaitConnected(session);
            fixture.prime(0);
            await(() -> hasLifecycleEvent(session,
                            AgentSession.LifecycleEventType.CONNECTION_LOST),
                    "disconnect mode did not close the Java connection");
            disconnecting.close();
            await(() -> session.getConnectionState()
                            != AgentSession.ConnectionState.CONNECTED,
                    "old runtime connection remained active after shutdown");

            for (long tick = 1; tick <= 5; tick++) {
                fixture.tick(tick);
            }
            assertEquals(AgentProtocol.DecisionSource.LOCAL_FALLBACK,
                    enemy.getLastActionOutcome().getDecisionSource());

            long disconnectedEpoch = session.getSessionEpoch();
            recovered = RuntimeProcess.start("normal", port, 0.0);
            awaitSessionEpoch(session, disconnectedEpoch);
            await(() -> session.getInboundQueueSize() > 0,
                    "restarted runtime returned no fresh intent");
            AgentSession.RequestContext request = session.getCurrentRequest();
            assertNotNull(request);
            assertEquals(AgentProtocol.DecisionSource.LOCAL_FALLBACK,
                    enemy.getArbiter().getCurrentLease().getDecisionSource());

            fixture.tick(6);
            assertEquals(AgentProtocol.DecisionSource.REMOTE_AGENT,
                    enemy.getArbiter().getCurrentLease().getDecisionSource());
            assertEquals(request.getDecisionId(),
                    enemy.getArbiter().getCurrentLease().getDecisionId());
            assertTrue(hasTraceEvent(
                    fixture, AgentTrace.EventType.REMOTE_AGENT_RESUMED));
        } finally {
            fixture.close();
            disconnecting.close();
            if (recovered != null) {
                recovered.close();
            }
        }
    }

    /** A dead enemy permanently closes its connected session and cannot reconnect. */
    @Test
    public void deadEnemyClosesConnectedSessionTerminally() throws Exception {
        try (RuntimeProcess runtime = RuntimeProcess.start("normal", 0, 0.0);
             GameFixture fixture = new GameFixture(1)) {
            Enemy enemy = fixture.enemies.get(0);
            AgentSession session = fixture.attachSession(
                    enemy, runtime.port, 1000, 5000);
            awaitConnected(session);
            fixture.prime(0);
            long connectedEpoch = session.getSessionEpoch();

            enemy.die();
            fixture.tick(1);

            assertTrue(enemy.isAgentRuntimeClosed());
            assertTrue(session.isClosed());
            long openedBeforeWait = countLifecycleEvents(
                    session, AgentSession.LifecycleEventType.CONNECTION_OPENED);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
            assertEquals(connectedEpoch, session.getSessionEpoch());
            assertEquals(openedBeforeWait, countLifecycleEvents(
                    session, AgentSession.LifecycleEventType.CONNECTION_OPENED));
        }
    }

    /** Creates a production session with short bounded integration timings. */
    private static AgentSession createSession(
            Enemy enemy, int port, long softDeadlineMs,
            long hardDeadlineMs) {
        AgentSessionConfig config = AgentSessionConfig.builder()
                .enabled(true)
                .host("127.0.0.1")
                .port(port)
                .softDeadlineMs(softDeadlineMs)
                .hardDeadlineMs(hardDeadlineMs)
                .cancelGraceMs(100)
                .reconnectInitialMs(20)
                .reconnectMaxMs(100)
                .shutdownJoinMs(500)
                .capabilities(
                        List.of("PATROL", "CHASE", "ATTACK", "GUARD"),
                        enemy.getSightRange(), enemy.getAttackDamage(),
                        enemy.getMoveInterval())
                .build();
        return new AgentSession(
                config,
                new AgentProtocol.Identity(
                        "world-test", RUN_ID, FLOOR_ID, enemy.getAgentId(), 0, 0),
                MonotonicClock.systemClock());
    }

    /** Waits for a physical transport connection without an unbounded sleep. */
    private static void awaitConnected(AgentSession session)
            throws Exception {
        await(() -> session.getConnectionState()
                        == AgentSession.ConnectionState.CONNECTED,
                "agent session did not connect");
    }

    /** Waits for a reconnect and reports its state history on failure. */
    private static void awaitSessionEpoch(
            AgentSession session, long previousEpoch) throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(AWAIT_TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            if (session.getConnectionState()
                    == AgentSession.ConnectionState.CONNECTED
                    && session.getSessionEpoch() > previousEpoch) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        StringBuilder history = new StringBuilder();
        for (AgentSession.LifecycleEvent event : session.getLifecycleEvents()) {
            history.append(event.getType()).append('@')
                    .append(event.getSessionEpoch()).append('[')
                    .append(event.getDetail()).append("]; ");
        }
        throw new AssertionError(
                "agent session did not open a new physical epoch: state="
                        + session.getConnectionState()
                        + ", epoch=" + session.getSessionEpoch()
                        + ", previous=" + previousEpoch
                        + ", history=" + history);
    }

    /** Polls one integration condition until its bounded deadline. */
    private static void await(BooleanSupplier condition, String message)
            throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(AWAIT_TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        assertTrue(message, condition.getAsBoolean());
    }

    /** Returns whether a session recorded the requested lifecycle transition. */
    private static boolean hasLifecycleEvent(
            AgentSession session, AgentSession.LifecycleEventType type) {
        for (AgentSession.LifecycleEvent event : session.getLifecycleEvents()) {
            if (event.getType() == type) {
                return true;
            }
        }
        return false;
    }

    /** Counts lifecycle transitions of one type for terminal-close assertions. */
    private static long countLifecycleEvents(
            AgentSession session, AgentSession.LifecycleEventType type) {
        long count = 0;
        for (AgentSession.LifecycleEvent event : session.getLifecycleEvents()) {
            if (event.getType() == type) {
                count++;
            }
        }
        return count;
    }

    /** Returns whether the production loop emitted one canonical event. */
    private static boolean hasTraceEvent(
            GameFixture fixture, AgentTrace.EventType type) {
        for (AgentTrace.TraceEvent event
                : fixture.traceSink.events()) {
            if (event.eventType == type) {
                return true;
            }
        }
        return false;
    }

    /** Builds committed feedback used to saturate the bounded outbound mailbox. */
    private static ActionOutcome syntheticOutcome(Enemy enemy, int index) {
        Position position = enemy.getPosition();
        return new ActionOutcome(
                RUN_ID, FLOOR_ID, enemy.getAgentId(), 0,
                "load-" + index, index, "MoveAction",
                Action.ActionResult.SUCCESS,
                position, position, enemy.getHp(),
                AgentProtocol.DecisionSource.LOCAL_FALLBACK, null);
    }

    private static Position copy(Position position) {
        return new Position(position.x, position.y);
    }

    /** Owns a small committed world driven through the production tick loop. */
    private static final class GameFixture implements AutoCloseable {
        private final TETile[][] world;
        private final EntityManager entityManager;
        private final Player player;
        private final List<Enemy> enemies;
        private final List<AgentSession> sessions = new ArrayList<>();
        private final AgentTrace.InMemorySink traceSink =
                new AgentTrace.InMemorySink();

        private GameFixture(int enemyCount) {
            world = openWorld(16, 10);
            entityManager = new EntityManager();
            player = new Player(new Position(8, 8), 100, 7);
            entityManager.addEntity(player);
            enemies = new ArrayList<>();
            for (int index = 0; index < enemyCount; index++) {
                Position position = index == 0
                        ? new Position(4, 4)
                        : new Position(12, 4);
                Enemy enemy = new Enemy(
                        position, Tileset.ENEMY,
                        20, 3, 1, 10, 0,
                        new Random(100 + index),
                        index == 0 ? "guard-a" : "guard-b");
                enemy.setPerceptionEnabled(true);
                entityManager.addEntity(enemy);
                enemies.add(enemy);
            }
            entityManager.flushPendingChanges();
        }

        /** Attaches and retains one production TCP session. */
        private AgentSession attachSession(
                Enemy enemy, int port,
                long softDeadlineMs, long hardDeadlineMs) {
            AgentSession session = createSession(
                    enemy, port, softDeadlineMs, hardDeadlineMs);
            enemy.attachAgentSession(session);
            sessions.add(session);
            return session;
        }

        /** Generates the first committed observation for every enemy. */
        private void prime(long logicalTick) {
            entityManager.flushPendingChanges();
            entityManager.removeDeadEntities();
            AiTickContext context = new AiTickContext(
                    RUN_ID, FLOOR_ID, logicalTick, null, traceSink);
            for (Enemy enemy : enemies) {
                enemy.collectAgentUpdates(
                        context, world, entityManager, player);
            }
        }

        /** Advances one production poll-execute-commit-collect cycle. */
        private void tick(long logicalTick) {
            AiTickLoop.run(
                    new AiTickContext(
                            RUN_ID, FLOOR_ID, logicalTick,
                            null, traceSink),
                    world, entityManager, player);
        }

        @Override
        public void close() {
            for (Enemy enemy : enemies) {
                enemy.closeAgentRuntime();
            }
            for (AgentSession session : sessions) {
                session.close();
            }
        }
    }

    /** Owns one test-created Python process and its bounded output collector. */
    private static final class RuntimeProcess implements AutoCloseable {
        private static final Pattern PORT_PATTERN = Pattern.compile(
                "\\\"port\\\":(\\d+)");

        private final Process process;
        private final BufferedReader output;
        private final List<String> lines = Collections.synchronizedList(
                new ArrayList<>());
        private final Thread outputReader;
        private final int port;
        private boolean closed;

        private RuntimeProcess(
                Process process, BufferedReader output,
                int port, String readyLine) {
            this.process = process;
            this.output = output;
            this.port = port;
            lines.add(readyLine);
            outputReader = new Thread(
                    this::drainOutput,
                    "agent-runtime-test-output-" + port);
            outputReader.setDaemon(true);
            outputReader.start();
        }

        /** Starts the repository Python runtime and reads its ready envelope. */
        private static RuntimeProcess start(
                String mode, int requestedPort, double delaySeconds)
                throws Exception {
            return start(mode, requestedPort, delaySeconds, "deterministic");
        }

        private static RuntimeProcess start(
                String mode, int requestedPort, double delaySeconds,
                String brain) throws Exception {
            ProcessBuilder builder = new ProcessBuilder(
                    System.getProperty("dungeonmind.python", "python"),
                    "agent/python/run.py",
                    "--host", "127.0.0.1",
                    "--port", Integer.toString(requestedPort),
                    "--mode", mode,
                    "--brain", brain,
                    "--delay-seconds", Double.toString(delaySeconds));
            builder.directory(new File(".").getCanonicalFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            BufferedReader output = new BufferedReader(
                    new InputStreamReader(
                            process.getInputStream(), StandardCharsets.UTF_8));
            String readyLine = output.readLine();
            if (readyLine == null) {
                process.destroyForcibly();
                throw new AssertionError(
                        "Python runtime exited before its ready envelope");
            }
            Matcher matcher = PORT_PATTERN.matcher(readyLine);
            if (!readyLine.contains("\"event\":\"ready\"")
                    || !matcher.find()) {
                process.destroyForcibly();
                throw new AssertionError(
                        "Invalid Python runtime ready envelope: " + readyLine);
            }
            return new RuntimeProcess(
                    process, output,
                    Integer.parseInt(matcher.group(1)), readyLine);
        }

        /** Continuously drains merged stdout/stderr so the child cannot block. */
        private void drainOutput() {
            try {
                String line;
                while ((line = output.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException ignored) {
                // Process shutdown closes the pipe and terminates this collector.
            }
        }

        /** Searches the bounded test process output collected so far. */
        private boolean containsOutput(String text) {
            synchronized (lines) {
                for (String line : lines) {
                    if (line.contains(text)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public void close() throws Exception {
            if (closed) {
                return;
            }
            closed = true;
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue("Python runtime did not terminate",
                        process.waitFor(2, TimeUnit.SECONDS));
            }
            output.close();
            outputReader.join(1000);
            assertFalse("runtime output collector did not terminate",
                    outputReader.isAlive());
        }
    }

    /** Creates a bordered floor map without hidden mutable fixtures. */
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
