package byog.Bridge;

import byog.Action.ActionOutcome;
import byog.Perception.HeardEvent;
import byog.Perception.ObservationEnvelope;
import byog.Perception.VisibleEntity;
import byog.Perception.VisibleTile;
import byog.lab5.Position;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns request identity, deadlines, bounded mailboxes, and terminal lifecycle
 * for one remote agent.
 */
public final class AgentSession implements AutoCloseable {
    private static final int LIFECYCLE_EVENT_CAPACITY = 256;

    public enum ConnectionState {
        DISABLED,
        CONNECTING,
        CONNECTED,
        DISCONNECTED
    }

    public enum RequestState {
        NO_REQUEST,
        AWAITING_INTENT,
        SOFT_TIMED_OUT,
        CANCEL_PENDING
    }

    public enum RequestStartResult {
        STARTED,
        COALESCED,
        NOT_CONNECTED,
        BACKPRESSURED,
        CLOSED
    }

    public enum EnqueueResult {
        ACCEPTED,
        COALESCED,
        DROPPED_LOW_PRIORITY,
        REJECTED_CRITICAL,
        CLOSED
    }

    public enum InboundEnqueueResult {
        ACCEPTED,
        DROPPED_STALE,
        DROPPED_DUPLICATE,
        PROTOCOL_FATAL,
        CLOSED
    }

    public enum SupersedeReason {
        HARD_TIMEOUT,
        PRECONDITION_INVALIDATED,
        EXPLICIT_CANCEL,
        FLOOR_EXIT,
        ENEMY_DIED
    }

    public enum LifecycleEventType {
        CONNECTION_CONNECTING,
        CONNECTION_OPENED,
        CONNECTION_LOST,
        REQUEST_STARTED,
        REQUEST_COMPLETED,
        REQUEST_REJECTED,
        OBSERVATION_COALESCED,
        AGENT_SLOW,
        CANCELLATION_STARTED,
        CANCELLATION_ACKNOWLEDGED,
        CANCELLATION_GRACE_EXPIRED,
        INBOUND_REJECTED,
        INBOUND_PROTOCOL_FATAL,
        OUTBOUND_LOW_PRIORITY_DROPPED,
        OUTBOUND_CRITICAL_REJECTED,
        TRANSPORT_CONTROL_FAILED,
        SESSION_CLOSED
    }

    /**
     * Immutable identity and validation snapshot for one request.
     */
    public static final class RequestContext {
        private final AgentProtocol.Identity identity;
        private final String decisionId;
        private final long observationSeq;
        private final long requestGeneration;
        private final long requestedAtNanos;
        private final ObservationEnvelope sourceObservation;
        private final List<AgentProtocol.WorldEventData> sentEvents;

        private RequestContext(
                AgentProtocol.Identity identity,
                String decisionId,
                long observationSeq,
                long requestGeneration,
                long requestedAtNanos,
                ObservationEnvelope sourceObservation,
                List<AgentProtocol.WorldEventData> sentEvents) {
            this.identity = copyIdentity(identity);
            this.decisionId = decisionId;
            this.observationSeq = observationSeq;
            this.requestGeneration = requestGeneration;
            this.requestedAtNanos = requestedAtNanos;
            this.sourceObservation = sourceObservation;
            this.sentEvents = List.copyOf(sentEvents);
        }

        public AgentProtocol.Identity getIdentity() {
            return copyIdentity(identity);
        }

        public String getDecisionId() {
            return decisionId;
        }

        public long getObservationSeq() {
            return observationSeq;
        }

        public long getRequestGeneration() {
            return requestGeneration;
        }

        public long getRequestedAtNanos() {
            return requestedAtNanos;
        }

        public ObservationEnvelope getSourceObservation() {
            return sourceObservation;
        }

        public List<AgentProtocol.WorldEventData> getSentEvents() {
            return sentEvents;
        }

        private RequestContext withObservation(
                ObservationEnvelope observation,
                List<AgentProtocol.WorldEventData> events) {
            return new RequestContext(
                    identity, decisionId, observation.getObservationSeq(),
                    requestGeneration, requestedAtNanos, observation, events);
        }
    }

    /**
     * Typed diagnostic event for deterministic lifecycle assertions.
     */
    public static final class LifecycleEvent {
        private final LifecycleEventType type;
        private final long logicalTick;
        private final long sessionEpoch;
        private final long requestGeneration;
        private final String decisionId;
        private final String detail;

        private LifecycleEvent(
                LifecycleEventType type,
                long logicalTick,
                long sessionEpoch,
                long requestGeneration,
                String decisionId,
                String detail) {
            this.type = type;
            this.logicalTick = logicalTick;
            this.sessionEpoch = sessionEpoch;
            this.requestGeneration = requestGeneration;
            this.decisionId = decisionId;
            this.detail = detail;
        }

        public LifecycleEventType getType() {
            return type;
        }

        public long getLogicalTick() {
            return logicalTick;
        }

        public long getSessionEpoch() {
            return sessionEpoch;
        }

        public long getRequestGeneration() {
            return requestGeneration;
        }

        public String getDecisionId() {
            return decisionId;
        }

        public String getDetail() {
            return detail;
        }
    }

    /**
     * Non-blocking mailbox endpoint owned by the transport worker.
     *
     * <p>Tests may drive this endpoint with an in-memory transport. Network
     * implementations can use the same endpoint without exposing socket
     * operations to the game thread.</p>
     */
    public final class TransportEndpoint {
        private TransportEndpoint() {
        }

        /** Marks that a connection attempt has started. */
        public void markConnecting() {
            synchronized (AgentSession.this) {
                if (!closed && config.isEnabled()
                        && connectionState != ConnectionState.CONNECTED
                        && connectionState != ConnectionState.CONNECTING) {
                    connectionState = ConnectionState.CONNECTING;
                    record(LifecycleEventType.CONNECTION_CONNECTING,
                            -1, decisionIdForTrace(),
                            "transport connecting");
                }
            }
        }

        /** Marks a successful physical connection and opens a new epoch. */
        public void markConnected(long logicalTick) {
            synchronized (AgentSession.this) {
                openConnection(logicalTick);
            }
        }

        /** Marks transport loss and invalidates work tied to that connection. */
        public void markDisconnected(String detail, long logicalTick) {
            synchronized (AgentSession.this) {
                loseConnection(detail, logicalTick);
            }
        }

        /** Removes one outbound message without waiting. */
        public AgentProtocol.Envelope pollOutbound() {
            synchronized (AgentSession.this) {
                if (closed) {
                    return null;
                }
                return outboundQueue.pollFirst();
            }
        }

        /** Offers one already-decoded inbound message without waiting. */
        public InboundEnqueueResult offerInbound(
                AgentProtocol.Envelope envelope, long logicalTick) {
            synchronized (AgentSession.this) {
                return enqueueInbound(envelope, logicalTick);
            }
        }

        /** Reports a decoder or framing failure for game-thread delivery. */
        public void reportProtocolFailure(
                AgentProtocolCodec.ProtocolFailure failure,
                long logicalTick) {
            synchronized (AgentSession.this) {
                protocolFatal(failure, logicalTick);
            }
        }

        /** Returns whether the session has requested a physical rebuild. */
        public boolean isRebuildRequested() {
            synchronized (AgentSession.this) {
                return rebuildRequested;
            }
        }

        /** Acknowledges that the worker observed the rebuild request. */
        public void acknowledgeRebuildRequest() {
            synchronized (AgentSession.this) {
                rebuildRequested = false;
            }
        }
    }

    private record EventKey(String eventType, String relatedEntityId) {
    }

    private final AgentSessionConfig config;
    private final String runId;
    private final int floorId;
    private final String agentId;
    private final MonotonicClock clock;
    private final IdGenerator idGenerator;
    private final AgentTransport transport;
    private final ArrayDeque<AgentProtocol.Envelope> outboundQueue;
    private final ArrayDeque<AgentProtocol.Envelope> inboundQueue;
    private final LinkedHashMap<EventKey, AgentProtocol.WorldEventData>
            pendingEvents;
    private final ArrayDeque<LifecycleEvent> lifecycleEvents;
    private final TransportEndpoint transportEndpoint;

    private ConnectionState connectionState;
    private RequestState requestState;
    private long sessionEpoch;
    private long requestGeneration;
    private long nextOutboundMessageSeq;
    private long highestInboundMessageSeq;
    private RequestContext currentRequest;
    private RequestContext cancelledRequest;
    private ObservationEnvelope latestObservation;
    private boolean requestPending;
    private long cancellationStartedAtNanos;
    private boolean rebuildRequested;
    private boolean closed;
    private AgentProtocolCodec.ProtocolFailure pendingProtocolFailure;

    public AgentSession(
            AgentSessionConfig config,
            AgentProtocol.Identity identity,
            MonotonicClock clock) {
        this(config, identity, clock, new IdGenerator.UuidIdGenerator(),
                AgentTransport.noOp());
    }

    public AgentSession(
            AgentSessionConfig config,
            AgentProtocol.Identity identity,
            MonotonicClock clock,
            IdGenerator idGenerator) {
        this(config, identity, clock, idGenerator, AgentTransport.noOp());
    }

    public AgentSession(
            AgentSessionConfig config,
            AgentProtocol.Identity identity,
            MonotonicClock clock,
            IdGenerator idGenerator,
            AgentTransport transport) {
        this.config = Objects.requireNonNull(config, "config");
        Objects.requireNonNull(identity, "identity");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.transport = Objects.requireNonNull(transport, "transport");
        runId = requireNonBlank(identity.runId, "runId");
        if (identity.floorId < 1) {
            throw new IllegalArgumentException("floorId must be at least 1");
        }
        floorId = identity.floorId;
        agentId = requireNonBlank(identity.agentId, "agentId");
        if (identity.sessionEpoch < 0 || identity.requestGeneration < 0) {
            throw new IllegalArgumentException(
                    "session counters must not be negative");
        }
        sessionEpoch = identity.sessionEpoch;
        requestGeneration = identity.requestGeneration;
        outboundQueue = new ArrayDeque<>(config.getOutboundCapacity());
        inboundQueue = new ArrayDeque<>(config.getInboundCapacity());
        pendingEvents = new LinkedHashMap<>();
        lifecycleEvents = new ArrayDeque<>(LIFECYCLE_EVENT_CAPACITY);
        transportEndpoint = new TransportEndpoint();
        connectionState = config.isEnabled()
                ? ConnectionState.CONNECTING : ConnectionState.DISABLED;
        requestState = RequestState.NO_REQUEST;
        highestInboundMessageSeq = -1;
        if (config.isEnabled()) {
            transport.start(transportEndpoint);
        }
    }

    public synchronized ConnectionState getConnectionState() {
        return connectionState;
    }

    public synchronized RequestState getRequestState() {
        return requestState;
    }

    public synchronized long getSessionEpoch() {
        return sessionEpoch;
    }

    public synchronized long getRequestGeneration() {
        return requestGeneration;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized AgentProtocol.Identity getIdentity() {
        return currentIdentity();
    }

    public synchronized RequestContext getCurrentRequest() {
        return currentRequest;
    }

    public synchronized RequestContext getCancelledRequest() {
        return cancelledRequest;
    }

    public synchronized ObservationEnvelope getLatestObservation() {
        return latestObservation;
    }

    public synchronized int getOutboundQueueSize() {
        return outboundQueue.size();
    }

    public synchronized int getInboundQueueSize() {
        return inboundQueue.size();
    }

    public synchronized int getPendingEventCount() {
        return pendingEvents.size();
    }

    public synchronized boolean isRebuildRequested() {
        return rebuildRequested;
    }

    public synchronized List<LifecycleEvent> getLifecycleEvents() {
        return List.copyOf(lifecycleEvents);
    }

    public TransportEndpoint transportEndpoint() {
        return transportEndpoint;
    }

    /**
     * Drains a bounded number of inbound messages and invokes the handler on
     * the calling thread.
     */
    public synchronized void pollInbound(
            AgentHandler handler, long logicalTick) {
        Objects.requireNonNull(handler, "handler");
        requireLogicalTick(logicalTick);
        if (closed) {
            return;
        }
        if (pendingProtocolFailure != null) {
            AgentProtocolCodec.ProtocolFailure failure =
                    pendingProtocolFailure;
            pendingProtocolFailure = null;
            handler.onProtocolRejected(failure);
        }
        int drained = 0;
        while (!closed
                && drained < config.getMaxInboundPerPoll()
                && !inboundQueue.isEmpty()) {
            AgentProtocol.Envelope envelope = inboundQueue.removeFirst();
            handleInbound(envelope, handler, logicalTick);
            drained++;
        }
    }

    /**
     * Starts one request or coalesces a newer observation without waiting.
     */
    public synchronized RequestStartResult requestIntent(
            ObservationEnvelope observation,
            List<AgentProtocol.WorldEventData> newPendingEvents,
            long logicalTick) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(newPendingEvents, "pendingEvents");
        requireLogicalTick(logicalTick);
        if (closed) {
            return RequestStartResult.CLOSED;
        }
        validateObservationIdentity(observation);
        if (latestObservation != null
                && observation.getObservationSeq()
                < latestObservation.getObservationSeq()) {
            throw new IllegalArgumentException(
                    "observation sequence must not move backwards");
        }
        latestObservation = observation;
        mergePendingEvents(newPendingEvents, logicalTick);

        if (currentRequest != null) {
            if (replaceQueuedObservation(
                    currentRequest, observation, logicalTick)) {
                requestPending = false;
            } else if (observation.getObservationSeq()
                    > currentRequest.getObservationSeq()) {
                requestPending = true;
            }
            record(LifecycleEventType.OBSERVATION_COALESCED, logicalTick,
                    currentRequest.getDecisionId(), "request already active");
            return RequestStartResult.COALESCED;
        }
        if (requestState == RequestState.CANCEL_PENDING) {
            requestPending = true;
            record(LifecycleEventType.OBSERVATION_COALESCED, logicalTick,
                    cancelledRequest == null
                            ? null : cancelledRequest.getDecisionId(),
                    "cancellation pending");
            return RequestStartResult.COALESCED;
        }
        if (connectionState != ConnectionState.CONNECTED) {
            requestPending = true;
            return RequestStartResult.NOT_CONNECTED;
        }
        return startLatestRequest(logicalTick);
    }

    /**
     * Enqueues committed action feedback without waiting for transport.
     */
    public synchronized EnqueueResult sendActionFeedback(
            ActionOutcome outcome, long logicalTick) {
        Objects.requireNonNull(outcome, "outcome");
        requireLogicalTick(logicalTick);
        if (closed) {
            return EnqueueResult.CLOSED;
        }
        validateOutcomeIdentity(outcome);
        if (connectionState != ConnectionState.CONNECTED) {
            record(LifecycleEventType.OUTBOUND_CRITICAL_REJECTED,
                    logicalTick, outcome.getDecisionId(),
                    "feedback while disconnected");
            return EnqueueResult.REJECTED_CRITICAL;
        }
        AgentProtocol.ActionFeedbackData data =
                new AgentProtocol.ActionFeedbackData(
                        outcome.getDecisionId(),
                        outcome.getActionIndex(),
                        outcome.getActionType(),
                        outcome.getResult().name(),
                        toPositionData(outcome.getBeforePosition()),
                        toPositionData(outcome.getAfterPosition()),
                        outcome.getSelfHp(),
                        outcome.getDecisionSource(),
                        outcome.getOverrideReason());
        EnqueueResult result = enqueueOutbound(newEnvelope(
                AgentProtocol.MessageType.ACTION_FEEDBACK,
                data, logicalTick));
        if (result == EnqueueResult.REJECTED_CRITICAL) {
            outboundCriticalFailure(
                    "action feedback queue saturation", logicalTick);
        }
        return result;
    }

    /**
     * Enqueues or coalesces a world event without waiting for transport.
     */
    public synchronized EnqueueResult sendWorldEvent(
            AgentProtocol.WorldEventData event, long logicalTick) {
        Objects.requireNonNull(event, "event");
        requireLogicalTick(logicalTick);
        if (closed) {
            return EnqueueResult.CLOSED;
        }
        mergePendingEvent(event, logicalTick);
        if (connectionState != ConnectionState.CONNECTED) {
            record(LifecycleEventType.OUTBOUND_LOW_PRIORITY_DROPPED,
                    logicalTick, decisionIdForTrace(),
                    "world event retained while disconnected");
            return EnqueueResult.DROPPED_LOW_PRIORITY;
        }
        EnqueueResult result = enqueueOutbound(newEnvelope(
                AgentProtocol.MessageType.WORLD_EVENT,
                event, logicalTick));
        if (result == EnqueueResult.DROPPED_LOW_PRIORITY) {
            record(LifecycleEventType.OUTBOUND_LOW_PRIORITY_DROPPED,
                    logicalTick, decisionIdForTrace(),
                    "world event queue saturation");
        }
        return result;
    }

    /**
     * Enqueues a low-priority heartbeat without waiting for transport.
     */
    public synchronized EnqueueResult sendHeartbeat(long logicalTick) {
        requireLogicalTick(logicalTick);
        if (closed) {
            return EnqueueResult.CLOSED;
        }
        if (connectionState != ConnectionState.CONNECTED) {
            return EnqueueResult.DROPPED_LOW_PRIORITY;
        }
        EnqueueResult result = enqueueOutbound(newEnvelope(
                AgentProtocol.MessageType.HEARTBEAT,
                new AgentProtocol.HeartbeatData(logicalTick),
                logicalTick));
        if (result == EnqueueResult.DROPPED_LOW_PRIORITY) {
            record(LifecycleEventType.OUTBOUND_LOW_PRIORITY_DROPPED,
                    logicalTick, decisionIdForTrace(),
                    "heartbeat queue saturation");
        }
        return result;
    }

    /**
     * Advances request deadlines using only the injected monotonic clock.
     */
    public synchronized void advanceRequestLifecycle(long logicalTick) {
        requireLogicalTick(logicalTick);
        if (closed) {
            return;
        }
        long now = clock.nanoTime();
        if ((requestState == RequestState.AWAITING_INTENT
                || requestState == RequestState.SOFT_TIMED_OUT)
                && currentRequest != null) {
            long started = currentRequest.getRequestedAtNanos();
            if (deadlineReached(
                    now, started, config.getHardDeadlineMs())) {
                beginCancellation(
                        SupersedeReason.HARD_TIMEOUT, logicalTick, now);
            } else if (requestState == RequestState.AWAITING_INTENT
                    && deadlineReached(
                    now, started, config.getSoftDeadlineMs())) {
                requestState = RequestState.SOFT_TIMED_OUT;
                record(LifecycleEventType.AGENT_SLOW, logicalTick,
                        currentRequest.getDecisionId(),
                        "soft deadline reached");
            }
        }
        if (requestState == RequestState.CANCEL_PENDING
                && deadlineReached(
                now, cancellationStartedAtNanos,
                config.getCancelGraceMs())) {
            cancellationGraceExpired(logicalTick);
        }
    }

    /**
     * Invalidates the active request and asks the remote runtime to cancel it.
     */
    public synchronized void supersedeCurrentRequest(
            SupersedeReason reason, long logicalTick) {
        Objects.requireNonNull(reason, "reason");
        requireLogicalTick(logicalTick);
        if (closed || currentRequest == null
                || requestState == RequestState.CANCEL_PENDING) {
            return;
        }
        beginCancellation(reason, logicalTick, clock.nanoTime());
    }

    /**
     * Permanently closes the session and discards queued runtime state.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        requestState = RequestState.NO_REQUEST;
        currentRequest = null;
        cancelledRequest = null;
        latestObservation = null;
        requestPending = false;
        rebuildRequested = false;
        pendingProtocolFailure = null;
        outboundQueue.clear();
        inboundQueue.clear();
        pendingEvents.clear();
        record(LifecycleEventType.SESSION_CLOSED, -1, null,
                "terminal close");
        try {
            transport.close();
        } catch (RuntimeException exception) {
            recordTransportControlFailure("close", exception);
        }
    }

    private RequestStartResult startLatestRequest(long logicalTick) {
        if (latestObservation == null
                || connectionState != ConnectionState.CONNECTED
                || requestState != RequestState.NO_REQUEST) {
            return RequestStartResult.NOT_CONNECTED;
        }
        String decisionId = idGenerator.newDecisionId();
        List<AgentProtocol.WorldEventData> eventSnapshot =
                List.copyOf(pendingEvents.values());
        RequestContext request = new RequestContext(
                currentIdentity(),
                decisionId,
                latestObservation.getObservationSeq(),
                requestGeneration,
                clock.nanoTime(),
                latestObservation,
                eventSnapshot);
        AgentProtocol.ObservationData data = toObservationData(
                latestObservation, request, eventSnapshot);
        AgentProtocol.Envelope envelope = newEnvelope(
                AgentProtocol.MessageType.OBSERVATION, data, logicalTick);
        EnqueueResult result = enqueueOutbound(envelope);
        if (result == EnqueueResult.REJECTED_CRITICAL) {
            outboundCriticalFailure(
                    "observation queue saturation", logicalTick);
            return RequestStartResult.BACKPRESSURED;
        }
        currentRequest = request;
        requestState = RequestState.AWAITING_INTENT;
        requestPending = false;
        record(LifecycleEventType.REQUEST_STARTED, logicalTick,
                decisionId, "observation="
                        + latestObservation.getObservationSeq());
        return RequestStartResult.STARTED;
    }

    private boolean replaceQueuedObservation(
            RequestContext request,
            ObservationEnvelope observation,
            long logicalTick) {
        List<AgentProtocol.WorldEventData> eventSnapshot =
                List.copyOf(pendingEvents.values());
        for (Iterator<AgentProtocol.Envelope> iterator =
                outboundQueue.iterator(); iterator.hasNext();) {
            AgentProtocol.Envelope queued = iterator.next();
            if (queued.type != AgentProtocol.MessageType.OBSERVATION
                    || !(queued.data
                    instanceof AgentProtocol.ObservationData data)
                    || !request.getDecisionId().equals(data.decisionId())) {
                continue;
            }
            RequestContext replacement =
                    request.withObservation(observation, eventSnapshot);
            AgentProtocol.ObservationData replacementData =
                    toObservationData(
                            observation, replacement, eventSnapshot);
            AgentProtocol.Envelope replacementEnvelope =
                    new AgentProtocol.Envelope(
                            queued.schemaVersion,
                            queued.messageId,
                            queued.messageSeq,
                            queued.runId,
                            queued.floorId,
                            queued.agentId,
                            queued.sessionEpoch,
                            logicalTick,
                            queued.type,
                            replacementData);
            iterator.remove();
            outboundQueue.addLast(replacementEnvelope);
            currentRequest = replacement;
            return true;
        }
        return false;
    }

    private void beginCancellation(
            SupersedeReason reason, long logicalTick, long now) {
        RequestContext request = currentRequest;
        if (request == null) {
            return;
        }
        currentRequest = null;
        cancelledRequest = request;
        incrementRequestGeneration();
        requestState = RequestState.CANCEL_PENDING;
        cancellationStartedAtNanos = now;
        requestPending = latestObservation != null
                && latestObservation.getObservationSeq()
                > request.getObservationSeq();
        removeQueuedObservation(request.getDecisionId());
        AgentProtocol.CancelRequestData data =
                new AgentProtocol.CancelRequestData(
                        request.getDecisionId(),
                        request.getRequestGeneration(),
                        reason.name());
        EnqueueResult result = enqueueOutbound(newEnvelope(
                AgentProtocol.MessageType.CANCEL_REQUEST,
                data, logicalTick));
        record(LifecycleEventType.CANCELLATION_STARTED, logicalTick,
                request.getDecisionId(), reason.name());
        if (result == EnqueueResult.REJECTED_CRITICAL) {
            outboundCriticalFailureWithoutGeneration(
                    "cancel request queue saturation", logicalTick);
        }
    }

    private void cancellationGraceExpired(long logicalTick) {
        String decisionId = cancelledRequest == null
                ? null : cancelledRequest.getDecisionId();
        record(LifecycleEventType.CANCELLATION_GRACE_EXPIRED,
                logicalTick, decisionId, "remote acknowledgement missing");
        cancelledRequest = null;
        requestState = RequestState.NO_REQUEST;
        requestPending = latestObservation != null;
        connectionState = ConnectionState.DISCONNECTED;
        outboundQueue.clear();
        inboundQueue.clear();
        highestInboundMessageSeq = -1;
        requestTransportRebuild();
    }

    private void openConnection(long logicalTick) {
        if (closed || !config.isEnabled()
                || connectionState == ConnectionState.CONNECTED) {
            return;
        }
        sessionEpoch = incrementCounter(sessionEpoch, "sessionEpoch");
        nextOutboundMessageSeq = 0;
        highestInboundMessageSeq = -1;
        connectionState = ConnectionState.CONNECTED;
        rebuildRequested = false;
        record(LifecycleEventType.CONNECTION_OPENED, logicalTick,
                decisionIdForTrace(), "transport connected");
        if (requestState == RequestState.NO_REQUEST
                && latestObservation != null && requestPending) {
            startLatestRequest(logicalTick);
        }
    }

    private void loseConnection(String detail, long logicalTick) {
        if (closed || !config.isEnabled()
                || connectionState == ConnectionState.DISABLED
                || connectionState == ConnectionState.DISCONNECTED) {
            return;
        }
        if (currentRequest != null || cancelledRequest != null) {
            incrementRequestGeneration();
        }
        requestPending = latestObservation != null;
        requestState = RequestState.NO_REQUEST;
        currentRequest = null;
        cancelledRequest = null;
        outboundQueue.clear();
        inboundQueue.clear();
        highestInboundMessageSeq = -1;
        connectionState = ConnectionState.DISCONNECTED;
        record(LifecycleEventType.CONNECTION_LOST, logicalTick,
                null, detail == null ? "transport disconnected" : detail);
        requestTransportRebuild();
    }

    private InboundEnqueueResult enqueueInbound(
            AgentProtocol.Envelope envelope, long logicalTick) {
        Objects.requireNonNull(envelope, "envelope");
        requireLogicalTick(logicalTick);
        if (closed) {
            return InboundEnqueueResult.CLOSED;
        }
        if (isObviouslyStale(envelope)) {
            record(LifecycleEventType.INBOUND_REJECTED, logicalTick,
                    decisionIdOf(envelope), "stale identity");
            return InboundEnqueueResult.DROPPED_STALE;
        }
        if (envelope.messageSeq <= highestInboundMessageSeq) {
            record(LifecycleEventType.INBOUND_REJECTED, logicalTick,
                    decisionIdOf(envelope), "duplicate message sequence");
            return InboundEnqueueResult.DROPPED_DUPLICATE;
        }
        removeStaleInbound();
        if (inboundQueue.size() >= config.getInboundCapacity()) {
            protocolFatal(new AgentProtocolCodec.ProtocolFailure(
                    AgentProtocolCodec.FailureReason.OUT_OF_RANGE,
                    "inbound queue capacity exceeded"), logicalTick);
            return InboundEnqueueResult.PROTOCOL_FATAL;
        }
        highestInboundMessageSeq = envelope.messageSeq;
        inboundQueue.addLast(envelope);
        return InboundEnqueueResult.ACCEPTED;
    }

    private void handleInbound(
            AgentProtocol.Envelope envelope,
            AgentHandler handler,
            long logicalTick) {
        AgentProtocolCodec.ProtocolFailure identityFailure =
                validateInboundIdentity(envelope);
        if (identityFailure != null) {
            record(LifecycleEventType.INBOUND_REJECTED, logicalTick,
                    decisionIdOf(envelope), identityFailure.detail());
            handler.onProtocolRejected(identityFailure);
            return;
        }
        switch (envelope.type) {
            case SUBMIT_INTENT ->
                    handleIntentSubmission(envelope, handler, logicalTick);
            case CANCEL_ACK ->
                    handleCancellationAcknowledgement(
                            envelope, handler, logicalTick);
            case PROTOCOL_ERROR -> {
                AgentProtocol.ProtocolErrorData data =
                        (AgentProtocol.ProtocolErrorData) envelope.data;
                handler.onProtocolRejected(
                        new AgentProtocolCodec.ProtocolFailure(
                                AgentProtocolCodec.FailureReason.SCHEMA_MISMATCH,
                                data.reason() + " [" + data.offendingType()
                                        + "]"));
            }
            default -> handler.onProtocolRejected(
                    new AgentProtocolCodec.ProtocolFailure(
                            AgentProtocolCodec.FailureReason.UNKNOWN_MESSAGE_TYPE,
                            "unexpected inbound message: "
                                    + envelope.type));
        }
    }

    private void handleIntentSubmission(
            AgentProtocol.Envelope envelope,
            AgentHandler handler,
            long logicalTick) {
        AgentProtocol.SubmitIntentData data =
                (AgentProtocol.SubmitIntentData) envelope.data;
        RequestContext request = currentRequest;
        if ((requestState != RequestState.AWAITING_INTENT
                && requestState != RequestState.SOFT_TIMED_OUT)
                || request == null
                || requestGeneration != data.requestGeneration()
                || request.getRequestGeneration()
                != data.requestGeneration()
                || !request.getDecisionId().equals(data.decisionId())
                || request.getObservationSeq() != data.observationSeq()) {
            rejectRequestIdentity(
                    handler, envelope, logicalTick,
                    "intent request identity mismatch");
            return;
        }
        AgentHandler.IntentHandlingResult handlingResult =
                Objects.requireNonNull(
                        handler.onIntentSubmitted(data, envelope, request),
                        "intent handling result");
        if (handlingResult
                == AgentHandler.IntentHandlingResult.ACCEPTED) {
            clearSentEvents(request);
        }
        removeQueuedObservation(request.getDecisionId());
        currentRequest = null;
        requestState = RequestState.NO_REQUEST;
        if (handlingResult
                == AgentHandler.IntentHandlingResult.ACCEPTED) {
            record(LifecycleEventType.REQUEST_COMPLETED,
                    logicalTick, request.getDecisionId(),
                    "intent delivered to game thread");
        } else {
            record(LifecycleEventType.REQUEST_REJECTED,
                    logicalTick, request.getDecisionId(),
                    "intent rejected by game thread");
        }
        if (requestPending && latestObservation != null
                && connectionState == ConnectionState.CONNECTED) {
            startLatestRequest(logicalTick);
        }
    }

    private void handleCancellationAcknowledgement(
            AgentProtocol.Envelope envelope,
            AgentHandler handler,
            long logicalTick) {
        AgentProtocol.CancelAckData data =
                (AgentProtocol.CancelAckData) envelope.data;
        RequestContext request = cancelledRequest;
        if (requestState != RequestState.CANCEL_PENDING
                || request == null
                || !request.getDecisionId().equals(data.decisionId())
                || request.getRequestGeneration()
                != data.requestGeneration()) {
            rejectRequestIdentity(
                    handler, envelope, logicalTick,
                    "cancel acknowledgement identity mismatch");
            return;
        }
        try {
            handler.onCancelAcknowledged(data, envelope, request);
        } finally {
            removeCancellationMessage(request.getDecisionId());
            cancelledRequest = null;
            requestState = RequestState.NO_REQUEST;
            record(LifecycleEventType.CANCELLATION_ACKNOWLEDGED,
                    logicalTick, request.getDecisionId(),
                    "remote cancellation confirmed");
            if (requestPending && latestObservation != null
                    && connectionState == ConnectionState.CONNECTED) {
                startLatestRequest(logicalTick);
            }
        }
    }

    private AgentProtocolCodec.ProtocolFailure validateInboundIdentity(
            AgentProtocol.Envelope envelope) {
        if (!AgentProtocol.ENVELOPE_VERSION.equals(envelope.schemaVersion)) {
            return identityFailure("schema version mismatch");
        }
        if (envelope.messageId == null
                || envelope.messageId.trim().isEmpty()) {
            return identityFailure("messageId is required");
        }
        if (!runId.equals(envelope.runId)
                || floorId != envelope.floorId
                || !agentId.equals(envelope.agentId)) {
            return identityFailure("run, floor, or agent identity mismatch");
        }
        if (sessionEpoch != envelope.sessionEpoch) {
            return identityFailure("session epoch mismatch");
        }
        return null;
    }

    private boolean isObviouslyStale(AgentProtocol.Envelope envelope) {
        if (!AgentProtocol.ENVELOPE_VERSION.equals(envelope.schemaVersion)
                || !runId.equals(envelope.runId)
                || floorId != envelope.floorId
                || !agentId.equals(envelope.agentId)
                || sessionEpoch != envelope.sessionEpoch) {
            return true;
        }
        if (envelope.type == AgentProtocol.MessageType.SUBMIT_INTENT) {
            AgentProtocol.SubmitIntentData data =
                    (AgentProtocol.SubmitIntentData) envelope.data;
            return currentRequest == null
                    || data.requestGeneration() != requestGeneration
                    || !data.decisionId().equals(
                    currentRequest.getDecisionId())
                    || data.observationSeq()
                    != currentRequest.getObservationSeq();
        }
        if (envelope.type == AgentProtocol.MessageType.CANCEL_ACK) {
            AgentProtocol.CancelAckData data =
                    (AgentProtocol.CancelAckData) envelope.data;
            return cancelledRequest == null
                    || !data.decisionId().equals(
                    cancelledRequest.getDecisionId())
                    || data.requestGeneration()
                    != cancelledRequest.getRequestGeneration();
        }
        return false;
    }

    private void removeStaleInbound() {
        inboundQueue.removeIf(this::isObviouslyStale);
    }

    private void rejectRequestIdentity(
            AgentHandler handler,
            AgentProtocol.Envelope envelope,
            long logicalTick,
            String detail) {
        record(LifecycleEventType.INBOUND_REJECTED, logicalTick,
                decisionIdOf(envelope), detail);
        handler.onProtocolRejected(identityFailure(detail));
    }

    private void protocolFatal(
            AgentProtocolCodec.ProtocolFailure failure,
            long logicalTick) {
        if (closed) {
            return;
        }
        pendingProtocolFailure = Objects.requireNonNull(failure, "failure");
        incrementRequestGeneration();
        requestPending = latestObservation != null;
        requestState = RequestState.NO_REQUEST;
        currentRequest = null;
        cancelledRequest = null;
        outboundQueue.clear();
        inboundQueue.clear();
        highestInboundMessageSeq = -1;
        if (config.isEnabled()) {
            connectionState = ConnectionState.DISCONNECTED;
        }
        record(LifecycleEventType.INBOUND_PROTOCOL_FATAL,
                logicalTick, null, failure.detail());
        requestTransportRebuild();
    }

    private EnqueueResult enqueueOutbound(
            AgentProtocol.Envelope envelope) {
        AgentProtocol.MessageType type = envelope.type;
        if (type == AgentProtocol.MessageType.HEARTBEAT) {
            AgentProtocol.Envelope existing =
                    removeFirstOutboundOfType(
                            AgentProtocol.MessageType.HEARTBEAT);
            if (existing != null) {
                outboundQueue.addLast(envelope);
                return EnqueueResult.COALESCED;
            }
        } else if (type == AgentProtocol.MessageType.WORLD_EVENT) {
            AgentProtocol.WorldEventData event =
                    (AgentProtocol.WorldEventData) envelope.data;
            AgentProtocol.Envelope existing =
                    removeMatchingWorldEvent(event);
            if (existing != null) {
                outboundQueue.addLast(envelope);
                return EnqueueResult.COALESCED;
            }
        } else if (type == AgentProtocol.MessageType.OBSERVATION) {
            AgentProtocol.Envelope existing =
                    removeFirstOutboundOfType(
                            AgentProtocol.MessageType.OBSERVATION);
            if (existing != null) {
                outboundQueue.addLast(envelope);
                return EnqueueResult.COALESCED;
            }
        }

        if (outboundQueue.size() < config.getOutboundCapacity()) {
            outboundQueue.addLast(envelope);
            return EnqueueResult.ACCEPTED;
        }
        AgentProtocol.Envelope heartbeat =
                removeFirstOutboundOfType(
                        AgentProtocol.MessageType.HEARTBEAT);
        if (heartbeat != null) {
            outboundQueue.addLast(envelope);
            return EnqueueResult.ACCEPTED;
        }
        if (type == AgentProtocol.MessageType.HEARTBEAT
                || type == AgentProtocol.MessageType.WORLD_EVENT) {
            return EnqueueResult.DROPPED_LOW_PRIORITY;
        }
        return EnqueueResult.REJECTED_CRITICAL;
    }

    private AgentProtocol.Envelope removeFirstOutboundOfType(
            AgentProtocol.MessageType type) {
        for (Iterator<AgentProtocol.Envelope> iterator =
                outboundQueue.iterator(); iterator.hasNext();) {
            AgentProtocol.Envelope candidate = iterator.next();
            if (candidate.type == type) {
                iterator.remove();
                return candidate;
            }
        }
        return null;
    }

    private AgentProtocol.Envelope removeMatchingWorldEvent(
            AgentProtocol.WorldEventData event) {
        EventKey expected = eventKey(event);
        for (Iterator<AgentProtocol.Envelope> iterator =
                outboundQueue.iterator(); iterator.hasNext();) {
            AgentProtocol.Envelope candidate = iterator.next();
            if (candidate.type == AgentProtocol.MessageType.WORLD_EVENT
                    && eventKey((AgentProtocol.WorldEventData)
                    candidate.data).equals(expected)) {
                iterator.remove();
                return candidate;
            }
        }
        return null;
    }

    private void removeQueuedObservation(String decisionId) {
        outboundQueue.removeIf(envelope ->
                envelope.type == AgentProtocol.MessageType.OBSERVATION
                        && envelope.data
                        instanceof AgentProtocol.ObservationData data
                        && decisionId.equals(data.decisionId()));
    }

    private void removeCancellationMessage(String decisionId) {
        outboundQueue.removeIf(envelope ->
                envelope.type == AgentProtocol.MessageType.CANCEL_REQUEST
                        && envelope.data
                        instanceof AgentProtocol.CancelRequestData data
                        && decisionId.equals(data.decisionId()));
    }

    private void outboundCriticalFailure(
            String detail, long logicalTick) {
        incrementRequestGeneration();
        outboundCriticalFailureWithoutGeneration(detail, logicalTick);
    }

    private void outboundCriticalFailureWithoutGeneration(
            String detail, long logicalTick) {
        requestPending = latestObservation != null;
        requestState = RequestState.NO_REQUEST;
        currentRequest = null;
        cancelledRequest = null;
        outboundQueue.clear();
        inboundQueue.clear();
        highestInboundMessageSeq = -1;
        if (config.isEnabled()) {
            connectionState = ConnectionState.DISCONNECTED;
        }
        record(LifecycleEventType.OUTBOUND_CRITICAL_REJECTED,
                logicalTick, null, detail);
        requestTransportRebuild();
    }

    /** Requests non-blocking teardown and reconnection from the transport. */
    private void requestTransportRebuild() {
        if (!config.isEnabled() || closed) {
            return;
        }
        rebuildRequested = true;
        try {
            transport.requestRebuild();
        } catch (RuntimeException exception) {
            recordTransportControlFailure("rebuild", exception);
        }
    }

    private void recordTransportControlFailure(
            String operation, RuntimeException exception) {
        record(LifecycleEventType.TRANSPORT_CONTROL_FAILED,
                -1, decisionIdForTrace(),
                operation + ": "
                        + exception.getClass().getSimpleName());
    }

    private void mergePendingEvents(
            List<AgentProtocol.WorldEventData> events,
            long logicalTick) {
        for (AgentProtocol.WorldEventData event : events) {
            mergePendingEvent(Objects.requireNonNull(event, "pending event"),
                    logicalTick);
        }
    }

    private void mergePendingEvent(
            AgentProtocol.WorldEventData event, long logicalTick) {
        EventKey key = eventKey(event);
        if (pendingEvents.containsKey(key)) {
            pendingEvents.put(key, event);
            return;
        }
        if (pendingEvents.size() >= config.getPendingEventCapacity()) {
            Iterator<Map.Entry<EventKey, AgentProtocol.WorldEventData>>
                    iterator = pendingEvents.entrySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
                record(LifecycleEventType.OUTBOUND_LOW_PRIORITY_DROPPED,
                        logicalTick, decisionIdForTrace(),
                        "oldest pending event evicted");
            }
        }
        pendingEvents.put(key, event);
    }

    private void clearSentEvents(RequestContext request) {
        for (AgentProtocol.WorldEventData event : request.getSentEvents()) {
            EventKey key = eventKey(event);
            if (event.equals(pendingEvents.get(key))) {
                pendingEvents.remove(key);
            }
        }
    }

    private AgentProtocol.Envelope newEnvelope(
            AgentProtocol.MessageType type,
            AgentProtocol.MessageData data,
            long logicalTick) {
        return new AgentProtocol.Envelope(
                AgentProtocol.ENVELOPE_VERSION,
                idGenerator.newMessageId(),
                nextOutboundMessageSeq++,
                runId,
                floorId,
                agentId,
                sessionEpoch,
                logicalTick,
                type,
                data);
    }

    private AgentProtocol.ObservationData toObservationData(
            ObservationEnvelope observation,
            RequestContext request,
            List<AgentProtocol.WorldEventData> eventSnapshot) {
        List<AgentProtocol.VisibleTileData> tiles = new ArrayList<>();
        for (VisibleTile tile : observation.getVisibleTiles()) {
            tiles.add(new AgentProtocol.VisibleTileData(
                    tile.getX(), tile.getY(),
                    tile.getType().name(), tile.isWalkable()));
        }
        List<AgentProtocol.VisibleEntityData> entities = new ArrayList<>();
        for (VisibleEntity entity : observation.getVisibleEntities()) {
            entities.add(new AgentProtocol.VisibleEntityData(
                    entity.getType().name(),
                    toPositionData(entity.getPosition()),
                    entity.getVisibleHp(),
                    entity.getAgentId()));
        }
        List<AgentProtocol.HeardEventData> heard = new ArrayList<>();
        for (HeardEvent event : observation.getHeardEvents()) {
            heard.add(new AgentProtocol.HeardEventData(
                    event.getType().name(),
                    toPositionData(event.getSourcePosition()),
                    event.getTurn()));
        }
        return new AgentProtocol.ObservationData(
                AgentProtocol.OBSERVATION_VERSION,
                request.getDecisionId(),
                observation.getObservationSeq(),
                request.getRequestGeneration(),
                observation.getObservedAtTurn(),
                new AgentProtocol.SelfData(
                        toPositionData(observation.getSelfPosition()),
                        observation.getSelfHp()),
                tiles,
                entities,
                heard,
                eventSnapshot,
                config.getCapabilities());
    }

    private void validateObservationIdentity(
            ObservationEnvelope observation) {
        if (!runId.equals(observation.getRunId())
                || floorId != observation.getFloorId()
                || !agentId.equals(observation.getAgentId())) {
            throw new IllegalArgumentException(
                    "observation/session identity mismatch");
        }
        if (observation.getObservationSeq() < 0) {
            throw new IllegalArgumentException(
                    "observation sequence must not be negative");
        }
    }

    private void validateOutcomeIdentity(ActionOutcome outcome) {
        if (!runId.equals(outcome.getRunId())
                || floorId != outcome.getFloorId()
                || !agentId.equals(outcome.getAgentId())) {
            throw new IllegalArgumentException(
                    "feedback/session identity mismatch");
        }
    }

    private void record(
            LifecycleEventType type,
            long logicalTick,
            String decisionId,
            String detail) {
        if (lifecycleEvents.size() >= LIFECYCLE_EVENT_CAPACITY) {
            lifecycleEvents.removeFirst();
        }
        lifecycleEvents.addLast(new LifecycleEvent(
                type, logicalTick, sessionEpoch, requestGeneration,
                decisionId, detail));
    }

    private AgentProtocol.Identity currentIdentity() {
        return new AgentProtocol.Identity(
                runId, floorId, agentId,
                sessionEpoch, requestGeneration);
    }

    private String decisionIdForTrace() {
        if (currentRequest != null) {
            return currentRequest.getDecisionId();
        }
        return cancelledRequest == null
                ? null : cancelledRequest.getDecisionId();
    }

    private void incrementRequestGeneration() {
        requestGeneration =
                incrementCounter(requestGeneration, "requestGeneration");
    }

    private static long incrementCounter(long value, String name) {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException(name + " exhausted");
        }
        return value + 1;
    }

    private static AgentProtocol.Identity copyIdentity(
            AgentProtocol.Identity identity) {
        return new AgentProtocol.Identity(
                identity.runId, identity.floorId, identity.agentId,
                identity.sessionEpoch, identity.requestGeneration);
    }

    private static EventKey eventKey(
            AgentProtocol.WorldEventData event) {
        return new EventKey(event.eventType(), event.relatedEntityId());
    }

    private static AgentProtocol.PositionData toPositionData(
            Position position) {
        Objects.requireNonNull(position, "position");
        return new AgentProtocol.PositionData(position.x, position.y);
    }

    private static String decisionIdOf(
            AgentProtocol.Envelope envelope) {
        if (envelope.data
                instanceof AgentProtocol.SubmitIntentData data) {
            return data.decisionId();
        }
        if (envelope.data instanceof AgentProtocol.CancelAckData data) {
            return data.decisionId();
        }
        return null;
    }

    private static AgentProtocolCodec.ProtocolFailure identityFailure(
            String detail) {
        return new AgentProtocolCodec.ProtocolFailure(
                AgentProtocolCodec.FailureReason.SCHEMA_MISMATCH,
                detail);
    }

    private static boolean deadlineReached(
            long nowNanos, long startedNanos, long deadlineMs) {
        if (nowNanos < startedNanos) {
            return false;
        }
        long deadlineNanos = Math.multiplyExact(deadlineMs, 1_000_000L);
        return nowNanos - startedNanos >= deadlineNanos;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireLogicalTick(long logicalTick) {
        if (logicalTick < 0) {
            throw new IllegalArgumentException(
                    "logicalTick must not be negative");
        }
    }
}
