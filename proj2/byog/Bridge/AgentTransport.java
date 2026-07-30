package byog.Bridge;

/**
 * Controls the non-blocking lifecycle of one agent transport.
 */
public interface AgentTransport extends AutoCloseable {

    /** Binds the session endpoint without starting blocking work. */
    void start(AgentSession.TransportEndpoint endpoint);

    /** Tears down the active connection and schedules reconnection. */
    void requestRebuild();

    /** Permanently closes the transport and wakes blocked work. */
    @Override
    void close();

    /** Returns a transport suitable for sessions without an IO worker. */
    static AgentTransport noOp() {
        return NoOpTransport.INSTANCE;
    }

    /**
     * Provides compatibility for callers that manage no physical transport.
     */
    final class NoOpTransport implements AgentTransport {
        private static final NoOpTransport INSTANCE = new NoOpTransport();

        private NoOpTransport() {
        }

        @Override
        public void start(AgentSession.TransportEndpoint endpoint) {
        }

        @Override
        public void requestRebuild() {
        }

        @Override
        public void close() {
        }
    }
}
