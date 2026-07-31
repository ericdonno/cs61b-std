package byog.Bridge;

import byog.Helper.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns one persistent NDJSON socket and moves messages between it and an
 * {@link AgentSession.TransportEndpoint}.
 */
public final class SocketTransport implements AgentTransport {
    private static final int READ_POLL_TIMEOUT_MS = 50;
    private static final int MAX_WRITES_PER_CYCLE = 8;
    private static final AtomicLong WORKER_SEQUENCE = new AtomicLong();

    interface TransportConnection extends AutoCloseable {
        void connect(String host, int port, int timeoutMs) throws IOException;

        void setReadTimeout(int timeoutMs) throws IOException;

        InputStream input() throws IOException;

        OutputStream output() throws IOException;

        @Override
        void close() throws IOException;
    }

    @FunctionalInterface
    interface ConnectionFactory {
        TransportConnection create() throws IOException;
    }

    interface BackoffWaiter {
        void await(long delayMs) throws InterruptedException;

        void wake();
    }

    static final class FrameException extends IOException {
        private static final long serialVersionUID = 1L;
        private final transient AgentProtocolCodec.ProtocolFailure failure;

        FrameException(AgentProtocolCodec.ProtocolFailure failure) {
            super(failure.detail());
            this.failure = failure;
        }

        AgentProtocolCodec.ProtocolFailure failure() {
            return failure;
        }
    }

    /**
     * Accumulates at most one bounded byte frame across socket read timeouts.
     */
    static final class BoundedFrameReader {
        private final int maxFrameBytes;
        private final ByteArrayOutputStream frame;

        BoundedFrameReader(int maxFrameBytes) {
            if (maxFrameBytes < 1) {
                throw new IllegalArgumentException(
                        "maxFrameBytes must be positive");
            }
            this.maxFrameBytes = maxFrameBytes;
            frame = new ByteArrayOutputStream(
                    Math.min(maxFrameBytes, 4096));
        }

        /**
         * Reads through the next newline while preserving partial bytes when a
         * socket timeout interrupts the call.
         */
        String readFrame(InputStream input) throws IOException {
            Objects.requireNonNull(input, "input");
            while (true) {
                int next = input.read();
                if (next < 0) {
                    if (frame.size() == 0) {
                        throw new EOFException("remote endpoint closed");
                    }
                    throw new EOFException(
                            "remote endpoint closed inside NDJSON frame");
                }
                if (next == '\n') {
                    byte[] bytes = frame.toByteArray();
                    frame.reset();
                    return decodeUtf8(bytes);
                }
                if (frame.size() >= maxFrameBytes) {
                    throw new FrameException(
                            new AgentProtocolCodec.ProtocolFailure(
                                    AgentProtocolCodec.FailureReason
                                            .FRAME_TOO_LARGE,
                                    "frame exceeds " + maxFrameBytes
                                            + " UTF-8 bytes"));
                }
                frame.write(next);
            }
        }

        private static String decodeUtf8(byte[] bytes) throws FrameException {
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException exception) {
                throw new FrameException(
                        new AgentProtocolCodec.ProtocolFailure(
                                AgentProtocolCodec.FailureReason.JSON_SYNTAX,
                                "frame is not valid UTF-8"));
            }
        }
    }

    private static final class JavaSocketConnection
            implements TransportConnection {
        private final Socket socket = new Socket();

        @Override
        public void connect(
                String host, int port, int timeoutMs) throws IOException {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setTcpNoDelay(true);
        }

        @Override
        public void setReadTimeout(int timeoutMs) throws IOException {
            socket.setSoTimeout(timeoutMs);
        }

        @Override
        public InputStream input() throws IOException {
            return socket.getInputStream();
        }

        @Override
        public OutputStream output() throws IOException {
            return socket.getOutputStream();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static final class MonitorBackoffWaiter
            implements BackoffWaiter {
        private final Object monitor = new Object();
        private boolean woken;

        @Override
        public void await(long delayMs) throws InterruptedException {
            synchronized (monitor) {
                long remainingNanos =
                        TimeUnit.MILLISECONDS.toNanos(delayMs);
                while (!woken && remainingNanos > 0) {
                    long started = System.nanoTime();
                    long waitMillis = TimeUnit.NANOSECONDS.toMillis(
                            remainingNanos);
                    int waitNanos = (int) (remainingNanos
                            - TimeUnit.MILLISECONDS.toNanos(waitMillis));
                    monitor.wait(waitMillis, waitNanos);
                    long elapsed = Math.max(
                            1, System.nanoTime() - started);
                    remainingNanos = Math.max(
                            0, remainingNanos - elapsed);
                }
                woken = false;
            }
        }

        @Override
        public void wake() {
            synchronized (monitor) {
                woken = true;
                monitor.notifyAll();
            }
        }
    }

    private final AgentSessionConfig config;
    private final ConnectionFactory connectionFactory;
    private final BackoffWaiter backoffWaiter;
    private final Object lifecycleLock = new Object();

    private volatile AgentSession.TransportEndpoint endpoint;
    private volatile boolean rebuildRequested;
    private volatile boolean closed;
    private Thread worker;
    private TransportConnection activeConnection;

    public SocketTransport(AgentSessionConfig config) {
        this(config, JavaSocketConnection::new,
                new MonitorBackoffWaiter());
    }

    SocketTransport(
            AgentSessionConfig config,
            ConnectionFactory connectionFactory,
            BackoffWaiter backoffWaiter) {
        this.config = Objects.requireNonNull(config, "config");
        this.connectionFactory = Objects.requireNonNull(
                connectionFactory, "connectionFactory");
        this.backoffWaiter = Objects.requireNonNull(
                backoffWaiter, "backoffWaiter");
    }

    /**
     * Binds the session mailbox and starts the sole socket-owning worker.
     */
    @Override
    public void start(AgentSession.TransportEndpoint newEndpoint) {
        Objects.requireNonNull(newEndpoint, "endpoint");
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            if (worker != null) {
                throw new IllegalStateException(
                        "socket transport has already started");
            }
            endpoint = newEndpoint;
            worker = new Thread(
                    this::runIoLoop,
                    "dungeonmind-agent-io-"
                            + WORKER_SEQUENCE.incrementAndGet());
            worker.setDaemon(true);
            worker.start();
        }
    }

    /**
     * Closes the current physical connection so the worker can reconnect
     * after its normal backoff.
     */
    @Override
    public void requestRebuild() {
        if (closed) {
            return;
        }
        rebuildRequested = true;
        closeActiveConnection();
    }

    /**
     * Permanently stops reconnects, unblocks socket IO, and waits only for the
     * configured shutdown bound.
     */
    @Override
    public void close() {
        Thread thread;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            rebuildRequested = false;
            thread = worker;
        }
        closeActiveConnection();
        backoffWaiter.wake();
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(config.getShutdownJoinMs());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            Logger.error(
                    "Agent IO worker did not stop within %d ms",
                    config.getShutdownJoinMs());
        }
    }

    boolean isWorkerAlive() {
        Thread currentWorker = worker;
        return currentWorker != null && currentWorker.isAlive();
    }

    private void runIoLoop() {
        long backoffMs = config.getReconnectInitialMs();
        try {
            while (!closed) {
                acknowledgeRebuildRequest();
                boolean connected = runOneConnection();
                if (closed) {
                    break;
                }
                if (connected) {
                    backoffMs = config.getReconnectInitialMs();
                }
                acknowledgeRebuildRequest();
                if (!awaitBackoff(backoffMs)) {
                    break;
                }
                backoffMs = nextBackoff(backoffMs);
            }
        } finally {
            closeActiveConnection();
        }
    }

    /**
     * Opens and services one physical connection until it fails or is rebuilt.
     */
    private boolean runOneConnection() {
        AgentSession.TransportEndpoint currentEndpoint = endpoint;
        if (currentEndpoint == null || closed) {
            return false;
        }
        currentEndpoint.markConnecting();
        TransportConnection connection = null;
        boolean opened = false;
        boolean protocolFailureReported = false;
        try {
            connection = connectionFactory.create();
            if (!activateConnection(connection)) {
                return false;
            }
            connection.connect(
                    config.getHost(), config.getPort(), connectTimeoutMs());
            connection.setReadTimeout(READ_POLL_TIMEOUT_MS);
            InputStream input = new BufferedInputStream(connection.input());
            OutputStream output =
                    new BufferedOutputStream(connection.output());
            opened = true;
            currentEndpoint.markConnected(-1);
            serviceConnection(currentEndpoint, input, output);
        } catch (FrameException exception) {
            protocolFailureReported = true;
            if (!closed) {
                currentEndpoint.reportProtocolFailure(
                        exception.failure(), -1);
            }
        } catch (IOException exception) {
            if (!closed) {
                currentEndpoint.markDisconnected(
                        describe(exception), -1);
            }
        } catch (RuntimeException exception) {
            if (!closed) {
                currentEndpoint.markDisconnected(
                        "transport runtime failure: "
                                + exception.getClass().getSimpleName(),
                        -1);
            }
        } finally {
            releaseConnection(connection);
        }
        if (opened && !closed && !protocolFailureReported
                && rebuildRequested) {
            currentEndpoint.markDisconnected(
                    "transport rebuild requested", -1);
        }
        return opened;
    }

    /**
     * Alternates bounded writes with short reads so neither direction starves.
     */
    private void serviceConnection(
            AgentSession.TransportEndpoint currentEndpoint,
            InputStream input,
            OutputStream output) throws IOException {
        BoundedFrameReader reader =
                new BoundedFrameReader(config.getMaxFrameBytes());
        while (!closed && !rebuildRequested) {
            drainOutbound(currentEndpoint, output);
            if (closed || rebuildRequested) {
                return;
            }
            try {
                String frame = reader.readFrame(input);
                acceptInboundFrame(currentEndpoint, frame);
            } catch (SocketTimeoutException timeout) {
                // A read poll timeout is the scheduling boundary for writes.
            }
        }
    }

    /**
     * Writes a bounded batch of complete UTF-8 NDJSON frames.
     */
    private void drainOutbound(
            AgentSession.TransportEndpoint currentEndpoint,
            OutputStream output) throws IOException {
        for (int sent = 0;
             sent < MAX_WRITES_PER_CYCLE
                     && !closed && !rebuildRequested;
             sent++) {
            AgentProtocol.Envelope envelope =
                    currentEndpoint.pollOutbound();
            if (envelope == null) {
                return;
            }
            byte[] bytes;
            try {
                bytes = AgentProtocolCodec.encodeEnvelope(envelope)
                        .getBytes(StandardCharsets.UTF_8);
            } catch (RuntimeException exception) {
                throw new FrameException(
                        new AgentProtocolCodec.ProtocolFailure(
                                AgentProtocolCodec.FailureReason
                                        .SCHEMA_MISMATCH,
                                "outbound envelope cannot be encoded"));
            }
            if (bytes.length > config.getMaxFrameBytes()) {
                throw new FrameException(
                        new AgentProtocolCodec.ProtocolFailure(
                                AgentProtocolCodec.FailureReason
                                        .FRAME_TOO_LARGE,
                                "outbound frame " + bytes.length + " > "
                                        + config.getMaxFrameBytes()));
            }
            output.write(bytes);
            output.write('\n');
            output.flush();
        }
    }

    /**
     * Decodes one frame and offers only complete typed envelopes to Session.
     */
    private void acceptInboundFrame(
            AgentSession.TransportEndpoint currentEndpoint,
            String frame) throws FrameException {
        AgentProtocolCodec.DecodeResult result =
                AgentProtocolCodec.decodeMessage(
                        frame,
                        config.getMaxFrameBytes(),
                        AgentProtocolCodec.DEFAULT_MAX_DEPTH);
        if (result instanceof AgentProtocolCodec.DecodeResult.Failure failure) {
            if (failure.failure().reason()
                    == AgentProtocolCodec.FailureReason
                    .UNKNOWN_MESSAGE_TYPE) {
                currentEndpoint.reportRecoverableProtocolFailure(
                        failure.failure(), -1);
                return;
            }
            throw new FrameException(failure.failure());
        }
        AgentProtocol.Envelope envelope =
                ((AgentProtocolCodec.DecodeResult.Success) result).envelope();
        if (envelope.logicalTick < 0) {
            throw new FrameException(
                    new AgentProtocolCodec.ProtocolFailure(
                            AgentProtocolCodec.FailureReason.OUT_OF_RANGE,
                            "logicalTick must not be negative"));
        }
        AgentSession.InboundEnqueueResult enqueueResult =
                currentEndpoint.offerInbound(
                        envelope, envelope.logicalTick);
        if (enqueueResult
                == AgentSession.InboundEnqueueResult.PROTOCOL_FATAL) {
            throw new FrameException(
                    new AgentProtocolCodec.ProtocolFailure(
                            AgentProtocolCodec.FailureReason.OUT_OF_RANGE,
                            "inbound queue rejected a critical message"));
        }
    }

    /**
     * Registers a candidate before connect so terminal close can interrupt it.
     */
    private boolean activateConnection(
            TransportConnection connection) {
        synchronized (lifecycleLock) {
            if (closed) {
                closeQuietly(connection);
                return false;
            }
            activeConnection = connection;
            return true;
        }
    }

    /**
     * Removes and closes a connection without disturbing a newer candidate.
     */
    private void releaseConnection(
            TransportConnection connection) {
        if (connection == null) {
            return;
        }
        synchronized (lifecycleLock) {
            if (activeConnection == connection) {
                activeConnection = null;
            }
        }
        closeQuietly(connection);
    }

    /**
     * Closes the registered socket from lifecycle control to unblock IO.
     */
    private void closeActiveConnection() {
        TransportConnection connection;
        synchronized (lifecycleLock) {
            connection = activeConnection;
        }
        closeQuietly(connection);
    }

    /**
     * Clears both transport and Session copies of a consumed rebuild request.
     */
    private void acknowledgeRebuildRequest() {
        AgentSession.TransportEndpoint currentEndpoint = endpoint;
        if (rebuildRequested
                || currentEndpoint != null
                && currentEndpoint.isRebuildRequested()) {
            rebuildRequested = false;
            if (currentEndpoint != null) {
                currentEndpoint.acknowledgeRebuildRequest();
            }
        }
    }

    /**
     * Waits outside the game thread and remains interruptible by close.
     */
    private boolean awaitBackoff(long delayMs) {
        try {
            backoffWaiter.await(delayMs);
            return !closed;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private long nextBackoff(long currentMs) {
        if (currentMs >= config.getReconnectMaxMs()) {
            return config.getReconnectMaxMs();
        }
        if (currentMs > Long.MAX_VALUE / 2) {
            return config.getReconnectMaxMs();
        }
        return Math.min(currentMs * 2, config.getReconnectMaxMs());
    }

    private int connectTimeoutMs() {
        long bounded = Math.min(
                config.getReconnectInitialMs(),
                config.getShutdownJoinMs());
        return (int) Math.max(
                1, Math.min(bounded, Integer.MAX_VALUE));
    }

    private static String describe(IOException exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return exception.getClass().getSimpleName();
        }
        return exception.getClass().getSimpleName() + ": " + message;
    }

    private static void closeQuietly(
            TransportConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (IOException ignored) {
            // Closing is best-effort; the worker will leave this connection.
        }
    }
}
