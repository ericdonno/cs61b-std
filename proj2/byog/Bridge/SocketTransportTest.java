package byog.Bridge;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Bounded transport tests for socket ownership, framing, reconnect, and close.
 */
public class SocketTransportTest {
    private static final String RUN_ID = "run-socket-test";
    private static final int FLOOR_ID = 1;
    private static final long WAIT_MS = 2000;

    @Test
    @SuppressWarnings("try")
    public void localhostSocketExchangesNdjsonFrames() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        CountDownLatch frameReceived = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(
                0, 1, InetAddress.getLoopbackAddress())) {
            Thread serverThread = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    accepted.setSoTimeout((int) WAIT_MS);
                    received.set(readAsciiLine(accepted.getInputStream()));
                    writeProtocolDiagnostic(accepted.getOutputStream());
                    frameReceived.countDown();
                    releaseServer.await(WAIT_MS, TimeUnit.MILLISECONDS);
                } catch (Throwable throwable) {
                    serverFailure.set(throwable);
                    frameReceived.countDown();
                }
            }, "socket-transport-test-server");
            serverThread.start();

            AgentSessionConfig config = baseConfig()
                    .host(InetAddress.getLoopbackAddress()
                            .getHostAddress())
                    .port(server.getLocalPort())
                    .build();
            AgentSession session = new AgentSession(
                    config, identity(), MonotonicClock.systemClock(),
                    deterministicIds());
            RecordingHandler handler = new RecordingHandler();
            try {
                awaitCondition(
                        () -> session.getConnectionState()
                                == AgentSession.ConnectionState.CONNECTED,
                        "localhost transport did not connect");
                assertEquals(AgentSession.EnqueueResult.ACCEPTED,
                        session.sendHeartbeat(7));
                assertTrue("server did not receive NDJSON frame",
                        frameReceived.await(WAIT_MS, TimeUnit.MILLISECONDS));
                if (serverFailure.get() != null) {
                    throw new AssertionError(
                            "fake endpoint failed", serverFailure.get());
                }
                AgentProtocolCodec.DecodeResult result =
                        AgentProtocolCodec.decodeMessage(received.get());
                assertTrue(result
                        instanceof AgentProtocolCodec.DecodeResult.Success);
                AgentProtocol.Envelope envelope =
                        ((AgentProtocolCodec.DecodeResult.Success) result)
                                .envelope();
                assertEquals(AgentProtocol.MessageType.HEARTBEAT,
                        envelope.type);
                assertEquals(7, envelope.logicalTick);
                awaitCondition(
                        () -> session.getInboundQueueSize() == 1,
                        "localhost inbound frame was not decoded");
                session.pollInbound(handler, 8);
                assertEquals(1, handler.failures.size());
                assertEquals(
                        AgentProtocolCodec.FailureReason.SCHEMA_MISMATCH,
                        handler.failures.get(0).reason());
            } finally {
                releaseServer.countDown();
                session.close();
                server.close();
                serverThread.join(WAIT_MS);
            }
        }
    }

    @Test
    public void workerThreadOwnsConnectReadAndWrite() throws Exception {
        PollingInput input = new PollingInput();
        RecordingOutput output = new RecordingOutput();
        RecordingConnection connection =
                new RecordingConnection(input, output);
        ImmediateWaiter waiter = new ImmediateWaiter();
        AgentSessionConfig config = baseConfig().build();
        SocketTransport transport = new SocketTransport(
                config, () -> connection, waiter);
        AgentSession session = newSession(config, transport);
        try {
            awaitCondition(
                    () -> session.getConnectionState()
                            == AgentSession.ConnectionState.CONNECTED,
                    "recording connection did not open");
            assertEquals(AgentSession.EnqueueResult.ACCEPTED,
                    session.sendHeartbeat(3));
            assertTrue("worker did not read",
                    input.firstRead.await(WAIT_MS, TimeUnit.MILLISECONDS));
            assertTrue("worker did not flush outbound frame",
                    output.flushed.await(WAIT_MS, TimeUnit.MILLISECONDS));

            Thread owner = connection.connectThread.get();
            assertNotNull(owner);
            assertNotEquals(Thread.currentThread(), owner);
            assertEquals(owner, input.readThread.get());
            assertEquals(owner, output.writeThread.get());
        } finally {
            session.close();
        }
        assertFalse(transport.isWorkerAlive());
    }

    @Test
    public void reconnectUsesExponentialBackoffAndNewEpoch()
            throws Exception {
        ImmediateWaiter waiter = new ImmediateWaiter();
        AttemptingFactory factory = new AttemptingFactory(2);
        AgentSessionConfig config = baseConfig()
                .reconnectInitialMs(250)
                .reconnectMaxMs(4000)
                .build();
        SocketTransport transport = new SocketTransport(
                config, factory, waiter);
        AgentSession session = newSession(config, transport);
        try {
            awaitCondition(
                    () -> session.getConnectionState()
                                == AgentSession.ConnectionState.CONNECTED
                            && factory.attempts.get() >= 3,
                    "transport did not recover after connect failures");
            assertEquals(List.of(250L, 500L),
                    waiter.snapshot());
            assertEquals(1, session.getSessionEpoch());

            transport.requestRebuild();
            awaitCondition(
                    () -> session.getSessionEpoch() == 2
                            && factory.attempts.get() >= 4,
                    "transport did not reconnect into a new epoch");
            assertEquals(List.of(250L, 500L, 250L),
                    waiter.snapshot());
        } finally {
            session.close();
        }
        assertFalse(transport.isWorkerAlive());
    }

    @Test
    public void closeUnblocksReaderAndStopsWorker() throws Exception {
        BlockingInput input = new BlockingInput();
        RecordingConnection connection =
                new RecordingConnection(input, new RecordingOutput());
        AgentSessionConfig config = baseConfig()
                .shutdownJoinMs(300)
                .build();
        SocketTransport transport = new SocketTransport(
                config, () -> connection, new ImmediateWaiter());
        AgentSession session = newSession(config, transport);

        awaitCondition(
                () -> session.getConnectionState()
                        == AgentSession.ConnectionState.CONNECTED,
                "blocking connection did not open");
        assertTrue("worker never entered blocking read",
                input.entered.await(WAIT_MS, TimeUnit.MILLISECONDS));
        long started = System.nanoTime();
        session.close();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);
        session.close();

        assertFalse(transport.isWorkerAlive());
        assertTrue("close exceeded bounded cleanup window: " + elapsedMs,
                elapsedMs < config.getShutdownJoinMs() + 200);
        assertEquals(1, connection.closeCount.get());
    }

    @Test
    public void boundedReaderStopsOnFirstExcessByte() throws Exception {
        int limit = 64;
        CountingInput input = new CountingInput(1000);
        SocketTransport.BoundedFrameReader reader =
                new SocketTransport.BoundedFrameReader(limit);

        try {
            reader.readFrame(input);
            fail("oversize frame should fail");
        } catch (SocketTransport.FrameException exception) {
            assertEquals(
                    AgentProtocolCodec.FailureReason.FRAME_TOO_LARGE,
                    exception.failure().reason());
        }
        assertEquals(limit + 1, input.readCount);
    }

    @Test
    public void boundedReaderPreservesPartialFrameAcrossTimeout()
            throws Exception {
        TimeoutOnceInput input = new TimeoutOnceInput();
        SocketTransport.BoundedFrameReader reader =
                new SocketTransport.BoundedFrameReader(64);

        try {
            reader.readFrame(input);
            fail("first read should hit the scheduled timeout");
        } catch (SocketTimeoutException expected) {
            assertEquals(2, input.readCount);
        }
        assertEquals("{}", reader.readFrame(input));
    }

    @Test
    public void boundedReaderRejectsMalformedUtf8() throws Exception {
        byte[] invalid = new byte[]{
            (byte) 0xC3, (byte) 0x28, (byte) '\n'
        };
        SocketTransport.BoundedFrameReader reader =
                new SocketTransport.BoundedFrameReader(64);

        try {
            reader.readFrame(new ByteArrayInputStream(invalid));
            fail("malformed UTF-8 should fail");
        } catch (SocketTransport.FrameException exception) {
            assertEquals(
                    AgentProtocolCodec.FailureReason.JSON_SYNTAX,
                    exception.failure().reason());
        }
    }

    @Test
    public void oversizedOutboundFrameIsRejectedBeforeWrite()
            throws Exception {
        PollingInput input = new PollingInput();
        RecordingOutput output = new RecordingOutput();
        RecordingConnection connection =
                new RecordingConnection(input, output);
        HoldingWaiter waiter = new HoldingWaiter();
        AgentSessionConfig config = baseConfig()
                .maxFrameBytes(1024)
                .build();
        SocketTransport transport = new SocketTransport(
                config, () -> connection, waiter);
        AgentSession session = newSession(config, transport);
        RecordingHandler handler = new RecordingHandler();
        try {
            awaitCondition(
                    () -> session.getConnectionState()
                            == AgentSession.ConnectionState.CONNECTED,
                    "recording connection did not open");
            AgentProtocol.WorldEventData event =
                    new AgentProtocol.WorldEventData(
                            AgentProtocol.WorldEventType
                                    .PLAN_BLOCKED.name(),
                            9, null, "x".repeat(2000));
            assertEquals(AgentSession.EnqueueResult.ACCEPTED,
                    session.sendWorldEvent(event, 9));
            assertTrue("protocol failure did not enter backoff",
                    waiter.entered.await(WAIT_MS, TimeUnit.MILLISECONDS));
            assertEquals(AgentSession.ConnectionState.DISCONNECTED,
                    session.getConnectionState());
            session.pollInbound(handler, 10);
            assertEquals(1, handler.failures.size());
            assertEquals(
                    AgentProtocolCodec.FailureReason.FRAME_TOO_LARGE,
                    handler.failures.get(0).reason());
            assertEquals(0, output.byteCount());
        } finally {
            session.close();
        }
    }

    @Test
    public void unknownMessageTypeIsReportedWithoutDisconnect()
            throws Exception {
        PollingInput input = new PollingInput();
        RecordingConnection connection =
                new RecordingConnection(input, new RecordingOutput());
        AgentSessionConfig config = baseConfig().build();
        SocketTransport transport = new SocketTransport(
                config, () -> connection, new ImmediateWaiter());
        AgentSession session = newSession(config, transport);
        RecordingHandler handler = new RecordingHandler();
        try {
            awaitCondition(
                    () -> session.getConnectionState()
                            == AgentSession.ConnectionState.CONNECTED,
                    "recording connection did not open");
            String frame = "{"
                    + "\"schemaVersion\":\""
                    + AgentProtocol.ENVELOPE_VERSION + "\","
                    + "\"messageId\":\"future-message\","
                    + "\"messageSeq\":0,"
                    + "\"runId\":\"" + RUN_ID + "\","
                    + "\"floorId\":" + FLOOR_ID + ","
                    + "\"agentId\":\"guard-a\","
                    + "\"sessionEpoch\":1,"
                    + "\"logicalTick\":4,"
                    + "\"type\":\"future_message\","
                    + "\"data\":{}}\n";
            input.offer(frame.getBytes(StandardCharsets.UTF_8));
            awaitCondition(
                    () -> hasLifecycleEvent(
                            session,
                            AgentSession.LifecycleEventType
                                    .INBOUND_REJECTED),
                    "compatible unknown message was not reported");
            session.pollInbound(handler, 4);

            assertEquals(AgentSession.ConnectionState.CONNECTED,
                    session.getConnectionState());
            assertEquals(1, handler.failures.size());
            assertEquals(
                    AgentProtocolCodec.FailureReason.UNKNOWN_MESSAGE_TYPE,
                    handler.failures.get(0).reason());
        } finally {
            session.close();
        }
    }

    private static AgentSessionConfig.Builder baseConfig() {
        return AgentSessionConfig.builder()
                .enabled(true)
                .softDeadlineMs(100)
                .hardDeadlineMs(500)
                .cancelGraceMs(100)
                .shutdownJoinMs(500);
    }

    private static AgentProtocol.Identity identity() {
        return new AgentProtocol.Identity(
                RUN_ID, FLOOR_ID, "guard-a", 0, 0);
    }

    private static IdGenerator deterministicIds() {
        return new IdGenerator.DeterministicIdGenerator(
                "socket-decision", "socket-message");
    }

    private static AgentSession newSession(
            AgentSessionConfig config, AgentTransport transport) {
        return new AgentSession(
                config, identity(), MonotonicClock.systemClock(),
                deterministicIds(), transport);
    }

    /**
     * Spins only within a strict wall-clock bound for worker-state assertions.
     */
    private static void awaitCondition(
            BooleanSupplier condition, String failureMessage)
            throws InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
        while (!condition.getAsBoolean()
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
        assertTrue(failureMessage, condition.getAsBoolean());
    }

    private static boolean hasLifecycleEvent(
            AgentSession session,
            AgentSession.LifecycleEventType expected) {
        for (AgentSession.LifecycleEvent event
                : session.getLifecycleEvents()) {
            if (event.getType() == expected) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the fake endpoint's one expected ASCII-compatible JSON line.
     */
    private static String readAsciiLine(InputStream input)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) >= 0) {
            if (next == '\n') {
                return bytes.toString(StandardCharsets.UTF_8);
            }
            bytes.write(next);
        }
        throw new IOException("connection ended before newline");
    }

    /**
     * Sends a valid inbound diagnostic while keeping the TCP connection open.
     */
    private static void writeProtocolDiagnostic(OutputStream output)
            throws IOException {
        String frame = "{"
                + "\"schemaVersion\":\""
                + AgentProtocol.ENVELOPE_VERSION + "\","
                + "\"messageId\":\"server-message\","
                + "\"messageSeq\":0,"
                + "\"runId\":\"" + RUN_ID + "\","
                + "\"floorId\":" + FLOOR_ID + ","
                + "\"agentId\":\"guard-a\","
                + "\"sessionEpoch\":1,"
                + "\"logicalTick\":8,"
                + "\"type\":\"protocol_error\","
                + "\"data\":{"
                + "\"reason\":\"fake diagnostic\","
                + "\"offendingType\":\"test\"}}\n";
        output.write(frame.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static final class RecordingHandler
            implements AgentHandler {
        private final List<AgentProtocolCodec.ProtocolFailure> failures =
                new ArrayList<>();

        @Override
        public IntentHandlingResult onIntentSubmitted(
                AgentProtocol.SubmitIntentData data,
                AgentProtocol.Envelope envelope,
                AgentSession.RequestContext requestContext) {
            return IntentHandlingResult.REJECTED;
        }

        @Override
        public void onCancelAcknowledged(
                AgentProtocol.CancelAckData data,
                AgentProtocol.Envelope envelope,
                AgentSession.RequestContext cancelledRequest) {
        }

        @Override
        public void onProtocolRejected(
                AgentProtocolCodec.ProtocolFailure failure) {
            failures.add(failure);
        }
    }

    private static final class RecordingConnection
            implements SocketTransport.TransportConnection {
        private final InputStream input;
        private final OutputStream output;
        private final AtomicReference<Thread> connectThread =
                new AtomicReference<>();
        private final AtomicInteger closeCount = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();

        private RecordingConnection(
                InputStream input, OutputStream output) {
            this.input = input;
            this.output = output;
        }

        @Override
        public void connect(
                String host, int port, int timeoutMs) throws IOException {
            connectThread.compareAndSet(null, Thread.currentThread());
        }

        @Override
        public void setReadTimeout(int timeoutMs) {
        }

        @Override
        public InputStream input() {
            return input;
        }

        @Override
        public OutputStream output() {
            return output;
        }

        @Override
        public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                closeCount.incrementAndGet();
                input.close();
                output.close();
            }
        }
    }

    private static final class PollingInput extends InputStream {
        private final ArrayDeque<Integer> bytes = new ArrayDeque<>();
        private final AtomicReference<Thread> readThread =
                new AtomicReference<>();
        private final CountDownLatch firstRead = new CountDownLatch(1);
        private boolean closed;

        @Override
        public synchronized int read() throws IOException {
            readThread.compareAndSet(null, Thread.currentThread());
            firstRead.countDown();
            if (closed) {
                throw new SocketException("fake connection closed");
            }
            if (bytes.isEmpty()) {
                try {
                    wait(5);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new SocketException("fake read interrupted");
                }
            }
            if (closed) {
                throw new SocketException("fake connection closed");
            }
            if (bytes.isEmpty()) {
                throw new SocketTimeoutException("fake read timeout");
            }
            return bytes.removeFirst();
        }

        private synchronized void offer(byte[] offeredBytes) {
            for (byte value : offeredBytes) {
                bytes.addLast(value & 0xff);
            }
            notifyAll();
        }

        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }
    }

    private static final class RecordingOutput extends OutputStream {
        private final ByteArrayOutputStream bytes =
                new ByteArrayOutputStream();
        private final AtomicReference<Thread> writeThread =
                new AtomicReference<>();
        private final CountDownLatch flushed = new CountDownLatch(1);

        @Override
        public synchronized void write(int value) {
            writeThread.compareAndSet(null, Thread.currentThread());
            bytes.write(value);
        }

        @Override
        public synchronized void write(
                byte[] values, int offset, int length) {
            writeThread.compareAndSet(null, Thread.currentThread());
            bytes.write(values, offset, length);
        }

        @Override
        public void flush() {
            flushed.countDown();
        }

        private synchronized int byteCount() {
            return bytes.size();
        }
    }

    private static final class ImmediateWaiter
            implements SocketTransport.BackoffWaiter {
        private final List<Long> delays =
                Collections.synchronizedList(new ArrayList<>());

        @Override
        public void await(long delayMs) {
            delays.add(delayMs);
        }

        @Override
        public void wake() {
        }

        private List<Long> snapshot() {
            synchronized (delays) {
                return List.copyOf(delays);
            }
        }
    }

    private static final class HoldingWaiter
            implements SocketTransport.BackoffWaiter {
        private final CountDownLatch entered = new CountDownLatch(1);
        private boolean woken;

        @Override
        public synchronized void await(long delayMs)
                throws InterruptedException {
            entered.countDown();
            while (!woken) {
                wait();
            }
        }

        @Override
        public synchronized void wake() {
            woken = true;
            notifyAll();
        }
    }

    private static final class AttemptingFactory
            implements SocketTransport.ConnectionFactory {
        private final int failuresBeforeSuccess;
        private final AtomicInteger attempts = new AtomicInteger();

        private AttemptingFactory(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public SocketTransport.TransportConnection create() {
            int attempt = attempts.incrementAndGet();
            return new SocketTransport.TransportConnection() {
                private final PollingInput input = new PollingInput();

                @Override
                public void connect(
                        String host, int port, int timeoutMs)
                        throws IOException {
                    if (attempt <= failuresBeforeSuccess) {
                        throw new IOException(
                                "scheduled connect failure " + attempt);
                    }
                }

                @Override
                public void setReadTimeout(int timeoutMs) {
                }

                @Override
                public InputStream input() {
                    return input;
                }

                @Override
                public OutputStream output() {
                    return OutputStream.nullOutputStream();
                }

                @Override
                public void close() {
                    input.close();
                }
            };
        }
    }

    private static final class BlockingInput extends InputStream {
        private final CountDownLatch entered = new CountDownLatch(1);
        private boolean closed;

        @Override
        public synchronized int read() throws IOException {
            entered.countDown();
            while (!closed) {
                try {
                    wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new SocketException("fake read interrupted");
                }
            }
            throw new SocketException("fake connection closed");
        }

        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }
    }

    private static final class CountingInput extends InputStream {
        private final int availableBytes;
        private int readCount;

        private CountingInput(int availableBytes) {
            this.availableBytes = availableBytes;
        }

        @Override
        public int read() {
            if (readCount >= availableBytes) {
                return -1;
            }
            readCount++;
            return 'a';
        }
    }

    private static final class TimeoutOnceInput extends InputStream {
        private int readCount;

        @Override
        public int read() throws IOException {
            readCount++;
            return switch (readCount) {
                case 1 -> '{';
                case 2 -> throw new SocketTimeoutException(
                        "scheduled partial-frame timeout");
                case 3 -> '}';
                case 4 -> '\n';
                default -> -1;
            };
        }
    }
}
