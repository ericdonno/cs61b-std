package byog.Test;

import byog.Action.Action;
import byog.Action.ActionOutcome;
import byog.Bridge.AgentHandler;
import byog.Bridge.AgentProtocol;
import byog.Bridge.AgentProtocolCodec;
import byog.Bridge.AgentSession;
import byog.Bridge.AgentSessionConfig;
import byog.Bridge.AgentTransport;
import byog.Bridge.IdGenerator;
import byog.Bridge.MonotonicClock;
import byog.Entity.Enemy;
import byog.Perception.ObservationEnvelope;
import byog.Perception.PerceptionSystem;
import byog.lab5.Position;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Deterministic contract tests for request lifecycle and mailbox backpressure.
 */
public class AgentSessionTest {
    private static final String RUN_ID = "run-session-test";
    private static final int FLOOR_ID = 1;

    @Test
    public void disabledSessionDoesNotStartTransport() {
        InMemoryTransport transport = new InMemoryTransport();
        AgentSession session = new AgentSession(
                AgentSessionConfig.builder().enabled(false).build(),
                new AgentProtocol.Identity(
                        "world-test", RUN_ID, FLOOR_ID, "guard-a", 0, 0),
                new FakeClock(),
                new IdGenerator.DeterministicIdGenerator(
                        "disabled-decision", "disabled-message"),
                transport);

        assertEquals(AgentSession.ConnectionState.DISABLED,
                session.getConnectionState());
        assertEquals(0, transport.startCount);
        session.close();
        assertEquals(1, transport.closeCount);
    }

    @Test
    public void unsentObservationIsReplacedWithoutCreatingAnotherRequest() {
        Fixture fixture = newFixture("guard-a", 4, 4);
        ObservationEnvelope first = fixture.observation(1);
        ObservationEnvelope latest = fixture.observation(2);

        assertEquals(AgentSession.RequestStartResult.STARTED,
                fixture.session.requestIntent(first, List.of(), 1));
        assertEquals(AgentSession.RequestStartResult.COALESCED,
                fixture.session.requestIntent(latest, List.of(), 2));

        assertEquals(1, fixture.session.getOutboundQueueSize());
        assertEquals(2,
                fixture.session.getCurrentRequest().getObservationSeq());
        assertEquals(2,
                fixture.session.getLatestObservation().getObservationSeq());
        AgentProtocol.Envelope envelope =
                fixture.transport.takeOneOutbound();
        AgentProtocol.ObservationData data =
                (AgentProtocol.ObservationData) envelope.data;
        assertEquals(2, data.observationSeq());
    }

    @Test
    public void transportedRequestKeepsIdentityWhileLatestSnapshotCoalesces() {
        Fixture fixture = newFixture("guard-a", 4, 4);
        fixture.session.requestIntent(fixture.observation(1), List.of(), 1);
        AgentProtocol.Envelope request =
                fixture.transport.takeOneOutbound();
        fixture.session.requestIntent(fixture.observation(2), List.of(), 2);

        assertEquals(1,
                fixture.session.getCurrentRequest().getObservationSeq());
        assertEquals(2,
                fixture.session.getLatestObservation().getObservationSeq());
        assertEquals(0, fixture.session.getOutboundQueueSize());

        fixture.transport.inject(
                fixture.transport.validIntentFor(request), 3);
        fixture.session.pollInbound(fixture.handler, 3);

        assertEquals(1, fixture.handler.intentCount);
        assertEquals(AgentSession.RequestState.AWAITING_INTENT,
                fixture.session.getRequestState());
        assertEquals(2,
                fixture.session.getCurrentRequest().getObservationSeq());
        assertEquals(1, fixture.session.getOutboundQueueSize());
    }

    @Test
    public void reconnectRequestUsesLatestCommittedObservationTick() {
        Fixture fixture = newFixture("guard-a", 4, 4);
        fixture.session.requestIntent(fixture.observation(1), List.of(), 1);
        fixture.transport.takeOneOutbound();
        fixture.transport.disconnect("test reconnect", 2);

        assertEquals(AgentSession.RequestStartResult.NOT_CONNECTED,
                fixture.session.requestIntent(
                        fixture.observation(7), List.of(), 7));
        fixture.transport.connect(-1);

        AgentProtocol.Envelope request =
                fixture.transport.takeOneOutbound();
        assertEquals(7, request.logicalTick);
        assertEquals(7,
                ((AgentProtocol.ObservationData) request.data)
                        .observedAtTurn());
    }

    @Test
    public void softDeadlineKeepsTheActiveRequest() {
        Fixture fixture = newFixture("guard-a", 4, 4);
        fixture.session.requestIntent(fixture.observation(1), List.of(), 1);
        AgentSession.RequestContext request =
                fixture.session.getCurrentRequest();

        fixture.clock.advanceMs(99);
        fixture.session.advanceRequestLifecycle(2);
        assertEquals(AgentSession.RequestState.AWAITING_INTENT,
                fixture.session.getRequestState());

        fixture.clock.advanceMs(1);
        fixture.session.advanceRequestLifecycle(3);
        assertEquals(AgentSession.RequestState.SOFT_TIMED_OUT,
                fixture.session.getRequestState());
        assertEquals(request, fixture.session.getCurrentRequest());
        assertNull(fixture.session.getCancelledRequest());
        assertEquals(0, fixture.session.getRequestGeneration());
        assertTrue(hasEvent(fixture.session,
                AgentSession.LifecycleEventType.AGENT_SLOW));
    }

    @Test
    public void hardDeadlineInvalidatesGenerationBeforeCancellation() {
        Fixture fixture = newFixture("guard-a", 4, 4);
        fixture.session.requestIntent(fixture.observation(1), List.of(), 1);
        AgentProtocol.Envelope request =
                fixture.transport.takeOneOutbound();
        AgentSession.RequestContext original =
                fixture.session.getCurrentRequest();

        fixture.clock.advanceMs(200);
        fixture.session.advanceRequestLifecycle(2);

        assertEquals(AgentSession.RequestState.CANCEL_PENDING,
                fixture.session.getRequestState());
        assertEquals(1, fixture.session.getRequestGeneration());
        assertNull(fixture.session.getCurrentRequest());
        assertEquals(original, fixture.session.getCancelledRequest());
        AgentProtocol.Envelope cancellation =
                fixture.transport.takeOneOutbound();
        AgentProtocol.CancelRequestData cancelData =
                (AgentProtocol.CancelRequestData) cancellation.data;
        assertEquals(original.getDecisionId(), cancelData.decisionId());
        assertEquals(0, cancelData.requestGeneration());

        assertEquals(AgentSession.InboundEnqueueResult.DROPPED_STALE,
                fixture.transport.inject(
                        fixture.transport.validIntentFor(request), 3));
        fixture.session.pollInbound(fixture.handler, 3);
        assertEquals(0, fixture.handler.intentCount);
        assertEquals(AgentSession.RequestState.CANCEL_PENDING,
                fixture.session.getRequestState());
    }

    @Test
    public void matchingCancellationAcknowledgementStartsLatestRequest() {
        Fixture fixture = newFixture("guard-a", 4, 4);
        fixture.session.requestIntent(fixture.observation(1), List.of(), 1);
        fixture.transport.takeOneOutbound();
        fixture.session.supersedeCurrentRequest(
                AgentSession.SupersedeReason.PRECONDITION_INVALIDATED, 2);
        AgentSession.RequestContext cancelled =
                fixture.session.getCancelledRequest();
        fixture.session.requestIntent(fixture.observation(2), List.of(), 3);
        fixture.transport.takeOneOutbound();

        fixture.transport.inject(
                fixture.transport.cancelAckFor(cancelled), 4);
        fixture.session.pollInbound(fixture.handler, 4);

        assertEquals(1, fixture.handler.cancellationCount);
        assertEquals(AgentSession.RequestState.AWAITING_INTENT,
                fixture.session.getRequestState());
        assertEquals(2,
                fixture.session.getCurrentRequest().getObservationSeq());
        assertEquals(1,
                fixture.session.getCurrentRequest().getRequestGeneration());
        AgentProtocol.Envelope next =
                fixture.transport.takeOneOutbound();
        assertEquals(AgentProtocol.MessageType.OBSERVATION, next.type);
    }

    @Test
    public void cancellationAcknowledgementDoesNotRestartOldObservation() {
        Fixture fixture = newFixture("guard-a", 4, 4);
        fixture.session.requestIntent(fixture.observation(1), List.of(), 1);
        fixture.transport.takeOneOutbound();
        fixture.session.supersedeCurrentRequest(
                AgentSession.SupersedeReason.EXPLICIT_CANCEL, 2);
        AgentSession.RequestContext cancelled =
                fixture.session.getCancelledRequest();
        fixture.transport.takeOneOutbound();

        fixture.transport.inject(
                fixture.transport.cancelAckFor(cancelled), 3);
        fixture.session.pollInbound(fixture.handler, 3);

        assertEquals(1, fixture.handler.cancellationCount);
        assertEquals(AgentSession.RequestState.NO_REQUEST,
                fixture.session.getRequestState());
        assertNull(fixture.session.getCurrentRequest());
        assertEquals(0, fixture.session.getOutboundQueueSize());
    }

    @Test
    public void rejectedIntentPreservesEventsForTheNextRequest() {
        Fixture fixture = newFixture("guard-a", 4, 4);
        AgentProtocol.WorldEventData event = worldEvent(
                AgentProtocol.WorldEventType.PLAYER_SPOTTED, 1, "player");
        fixture.session.requestIntent(
                fixture.observation(1), List.of(event), 1);
        AgentProtocol.Envelope first =
                fixture.transport.takeOneOutbound();
        fixture.handler.intentResult =
                AgentHandler.IntentHandlingResult.REJECTED;

        fixture.transport.inject(
                fixture.transport.validIntentFor(first), 2);
        fixture.session.pollInbound(fixture.handler, 2);

        assertEquals(1, fixture.session.getPendingEventCount());
        assertEquals(AgentSession.RequestState.NO_REQUEST,
                fixture.session.getRequestState());
        assertTrue(hasEvent(fixture.session,
                AgentSession.LifecycleEventType.REQUEST_REJECTED));
        assertFalse(hasEvent(fixture.session,
                AgentSession.LifecycleEventType.REQUEST_COMPLETED));

        fixture.handler.intentResult =
                AgentHandler.IntentHandlingResult.ACCEPTED;
        fixture.session.requestIntent(
                fixture.observation(2), List.of(), 3);
        AgentProtocol.Envelope second =
                fixture.transport.takeOneOutbound();
        assertEquals(1,
                fixture.session.getCurrentRequest().getSentEvents().size());
        fixture.transport.inject(
                fixture.transport.validIntentFor(second), 4);
        fixture.session.pollInbound(fixture.handler, 4);
        assertEquals(0, fixture.session.getPendingEventCount());
    }

    @Test
    public void cancellationGraceRequestsRebuildBeforeNewWork() {
        Fixture fixture = newFixture("guard-a", 4, 4);
        fixture.session.requestIntent(fixture.observation(1), List.of(), 1);
        fixture.transport.takeOneOutbound();
        fixture.clock.advanceMs(200);
        fixture.session.advanceRequestLifecycle(2);
        fixture.transport.takeOneOutbound();
        long oldEpoch = fixture.session.getSessionEpoch();

        fixture.clock.advanceMs(49);
        fixture.session.advanceRequestLifecycle(3);
        assertEquals(AgentSession.RequestState.CANCEL_PENDING,
                fixture.session.getRequestState());

        fixture.clock.advanceMs(1);
        fixture.session.advanceRequestLifecycle(4);
        assertEquals(AgentSession.ConnectionState.DISCONNECTED,
                fixture.session.getConnectionState());
        assertEquals(AgentSession.RequestState.NO_REQUEST,
                fixture.session.getRequestState());
        assertTrue(fixture.session.isRebuildRequested());
        assertEquals(1, fixture.transport.rebuildCount);
        assertFalse(fixture.transport.activeConnection);
        assertNull(fixture.session.getCurrentRequest());

        fixture.transport.connect(5);
        assertEquals(oldEpoch + 1, fixture.session.getSessionEpoch());
        assertEquals(AgentSession.RequestState.AWAITING_INTENT,
                fixture.session.getRequestState());
        assertEquals(1, fixture.session.getOutboundQueueSize());
    }

    @Test
    public void criticalMessageEvictsHeartbeatBeforeUsingFailurePath() {
        Fixture fixture = newFixture("guard-a", 4, 4);
        assertEquals(AgentSession.EnqueueResult.ACCEPTED,
                fixture.session.sendHeartbeat(1));
        for (int i = 0; i < 3; i++) {
            assertEquals(AgentSession.EnqueueResult.ACCEPTED,
                    fixture.session.sendActionFeedback(
                            fixture.outcome(i), 2 + i));
        }

        assertEquals(AgentSession.EnqueueResult.ACCEPTED,
                fixture.session.sendActionFeedback(
                        fixture.outcome(3), 5));
        assertEquals(AgentSession.ConnectionState.CONNECTED,
                fixture.session.getConnectionState());
        List<AgentProtocol.Envelope> queued =
                fixture.transport.drainOutbound();
        assertEquals(4, queued.size());
        assertFalse(queued.stream().anyMatch(envelope ->
                envelope.type == AgentProtocol.MessageType.HEARTBEAT));
    }

    @Test
    public void fullMailboxStillCoalescesAnUnsentObservation() {
        Fixture fixture = newFixture("guard-a", 4, 4);
        fixture.session.requestIntent(fixture.observation(1), List.of(), 1);
        for (int i = 0; i < 3; i++) {
            fixture.session.sendActionFeedback(
                    fixture.outcome(i), 2 + i);
        }
        assertEquals(4, fixture.session.getOutboundQueueSize());

        assertEquals(AgentSession.RequestStartResult.COALESCED,
                fixture.session.requestIntent(
                        fixture.observation(2), List.of(), 5));
        assertEquals(4, fixture.session.getOutboundQueueSize());
        assertEquals(2,
                fixture.session.getCurrentRequest().getObservationSeq());
        List<AgentProtocol.Envelope> queued =
                fixture.transport.drainOutbound();
        AgentProtocol.ObservationData observation = queued.stream()
                .filter(envelope ->
                        envelope.type
                                == AgentProtocol.MessageType.OBSERVATION)
                .map(envelope ->
                        (AgentProtocol.ObservationData) envelope.data)
                .findFirst()
                .orElseThrow();
        assertEquals(2, observation.observationSeq());
    }

    @Test
    public void criticalOutboundSaturationIsExplicitAndDegradesConnection() {
        Fixture fixture = newFixture("guard-a", 4, 4);
        for (int i = 0; i < 4; i++) {
            assertEquals(AgentSession.EnqueueResult.ACCEPTED,
                    fixture.session.sendActionFeedback(
                            fixture.outcome(i), i));
        }

        assertEquals(AgentSession.EnqueueResult.REJECTED_CRITICAL,
                fixture.session.sendActionFeedback(
                        fixture.outcome(4), 5));
        assertEquals(AgentSession.ConnectionState.DISCONNECTED,
                fixture.session.getConnectionState());
        assertTrue(fixture.session.isRebuildRequested());
        assertEquals(1, fixture.session.getRequestGeneration());
        assertEquals(0, fixture.session.getOutboundQueueSize());
        assertEquals(1, fixture.transport.rebuildCount);
        assertFalse(fixture.transport.activeConnection);
        assertTrue(hasEvent(fixture.session,
                AgentSession.LifecycleEventType
                        .OUTBOUND_CRITICAL_REJECTED));
    }

    @Test
    public void saturatedCancellationRequestsOneTransportRebuild() {
        Fixture fixture = newFixture("guard-a", 4, 4);
        fixture.session.requestIntent(fixture.observation(1), List.of(), 1);
        fixture.transport.takeOneOutbound();
        for (int i = 0; i < 4; i++) {
            fixture.session.sendActionFeedback(
                    fixture.outcome(i), 2 + i);
        }

        fixture.clock.advanceMs(200);
        fixture.session.advanceRequestLifecycle(6);

        assertEquals(AgentSession.ConnectionState.DISCONNECTED,
                fixture.session.getConnectionState());
        assertEquals(AgentSession.RequestState.NO_REQUEST,
                fixture.session.getRequestState());
        assertEquals(1, fixture.session.getRequestGeneration());
        assertEquals(1, fixture.transport.rebuildCount);
        assertFalse(fixture.transport.activeConnection);
        assertTrue(hasEvent(fixture.session,
                AgentSession.LifecycleEventType
                        .OUTBOUND_CRITICAL_REJECTED));
    }

    @Test
    public void inboundOverflowIsFatalBeforeAnyHandlerRuns() {
        Fixture fixture = newFixture("guard-a", 4, 4);
        fixture.session.requestIntent(fixture.observation(1), List.of(), 1);
        AgentProtocol.Envelope request =
                fixture.transport.takeOneOutbound();

        for (int i = 0; i < 4; i++) {
            assertEquals(AgentSession.InboundEnqueueResult.ACCEPTED,
                    fixture.transport.inject(
                            fixture.transport.validIntentFor(request), 2));
        }
        assertEquals(AgentSession.InboundEnqueueResult.PROTOCOL_FATAL,
                fixture.transport.inject(
                        fixture.transport.validIntentFor(request), 3));

        assertEquals(AgentSession.ConnectionState.DISCONNECTED,
                fixture.session.getConnectionState());
        assertEquals(AgentSession.RequestState.NO_REQUEST,
                fixture.session.getRequestState());
        assertEquals(1, fixture.session.getRequestGeneration());
        assertEquals(0, fixture.session.getInboundQueueSize());
        assertEquals(1, fixture.transport.rebuildCount);
        assertFalse(fixture.transport.activeConnection);
        fixture.session.pollInbound(fixture.handler, 4);
        assertEquals(0, fixture.handler.intentCount);
        assertEquals(1, fixture.handler.failureCount);
    }

    @Test
    public void everyRequestIdentityFieldMustMatch() {
        assertRejected(IdentityMutation.SCHEMA);
        assertRejected(IdentityMutation.MESSAGE_ID);
        assertRejected(IdentityMutation.RUN);
        assertRejected(IdentityMutation.FLOOR);
        assertRejected(IdentityMutation.AGENT);
        assertRejected(IdentityMutation.EPOCH);
        assertRejected(IdentityMutation.DECISION);
        assertRejected(IdentityMutation.OBSERVATION);
        assertRejected(IdentityMutation.GENERATION);
    }

    @Test
    public void worldEventsCoalesceAndPendingStorageRemainsBounded() {
        Fixture fixture = newFixture("guard-a", 4, 4);
        AgentProtocol.WorldEventData first = worldEvent(
                AgentProtocol.WorldEventType.PLAYER_SPOTTED, 1, "player");
        AgentProtocol.WorldEventData latest = worldEvent(
                AgentProtocol.WorldEventType.PLAYER_SPOTTED, 2, "player");
        assertEquals(AgentSession.EnqueueResult.ACCEPTED,
                fixture.session.sendWorldEvent(first, 1));
        assertEquals(AgentSession.EnqueueResult.COALESCED,
                fixture.session.sendWorldEvent(latest, 2));
        assertEquals(1, fixture.session.getOutboundQueueSize());
        assertEquals(1, fixture.session.getPendingEventCount());

        AgentProtocol.WorldEventType[] types =
                AgentProtocol.WorldEventType.values();
        for (int i = 0; i < 8; i++) {
            fixture.session.sendWorldEvent(worldEvent(
                    types[i % types.length], 3 + i,
                    "entity-" + i), 3 + i);
        }
        assertEquals(4, fixture.session.getPendingEventCount());
    }

    @Test
    public void closeIsIdempotentAndTerminal() {
        Fixture fixture = newFixture("guard-a", 4, 4);
        fixture.session.requestIntent(fixture.observation(1), List.of(), 1);
        fixture.session.close();
        fixture.session.close();

        assertTrue(fixture.session.isClosed());
        assertEquals(1, fixture.transport.closeCount);
        assertFalse(fixture.transport.activeConnection);
        assertEquals(0, fixture.session.getOutboundQueueSize());
        assertEquals(AgentSession.RequestStartResult.CLOSED,
                fixture.session.requestIntent(
                        fixture.observation(2), List.of(), 2));
        assertEquals(AgentSession.EnqueueResult.CLOSED,
                fixture.session.sendActionFeedback(
                        fixture.outcome(1), 2));
        assertEquals(AgentSession.InboundEnqueueResult.CLOSED,
                fixture.transport.inject(
                        fixture.transport.unrelatedHeartbeat(), 2));
        fixture.transport.connect(3);
        assertTrue(fixture.session.isClosed());
        assertEquals(1, countEvents(fixture.session,
                AgentSession.LifecycleEventType.SESSION_CLOSED));
    }

    @Test
    public void independentSessionsNeverAcceptEachOthersResponses() {
        Fixture first = newFixture("guard-a", 4, 4);
        Fixture second = newFixture("guard-b", 4, 4);
        first.session.requestIntent(first.observation(1), List.of(), 1);
        second.session.requestIntent(second.observation(1), List.of(), 1);
        AgentProtocol.Envelope firstRequest =
                first.transport.takeOneOutbound();
        AgentProtocol.Envelope secondRequest =
                second.transport.takeOneOutbound();

        assertEquals(AgentSession.InboundEnqueueResult.DROPPED_STALE,
                second.transport.inject(
                        first.transport.validIntentFor(firstRequest), 2));
        second.session.pollInbound(second.handler, 2);
        assertEquals(0, second.handler.intentCount);

        first.transport.inject(
                first.transport.validIntentFor(firstRequest), 3);
        second.transport.inject(
                second.transport.validIntentFor(secondRequest), 3);
        first.session.pollInbound(first.handler, 3);
        second.session.pollInbound(second.handler, 3);
        assertEquals(1, first.handler.intentCount);
        assertEquals(1, second.handler.intentCount);
    }

    private static void assertRejected(IdentityMutation mutation) {
        Fixture fixture = newFixture("guard-a", 4, 4);
        fixture.session.requestIntent(fixture.observation(1), List.of(), 1);
        AgentProtocol.Envelope request =
                fixture.transport.takeOneOutbound();
        AgentProtocol.Envelope response =
                fixture.transport.validIntentFor(request);
        AgentProtocol.Envelope mutated =
                mutation.apply(response);

        fixture.transport.inject(mutated, 2);
        fixture.session.pollInbound(fixture.handler, 2);

        assertEquals("mutation " + mutation + " reached intent handler",
                0, fixture.handler.intentCount);
        assertEquals(AgentSession.RequestState.AWAITING_INTENT,
                fixture.session.getRequestState());
    }

    private static Fixture newFixture(
            String agentId, int outboundCapacity, int inboundCapacity) {
        EncounterHarness encounter = EncounterHarness.privatePerceptionV1();
        Enemy enemy = "guard-a".equals(agentId)
                ? encounter.guardA() : encounter.guardB();
        FakeClock clock = new FakeClock();
        AgentSessionConfig config = AgentSessionConfig.builder()
                .enabled(true)
                .softDeadlineMs(100)
                .hardDeadlineMs(200)
                .cancelGraceMs(50)
                .outboundCapacity(outboundCapacity)
                .inboundCapacity(inboundCapacity)
                .pendingEventCapacity(4)
                .maxInboundPerPoll(8)
                .build();
        AgentProtocol.Identity identity = new AgentProtocol.Identity(
                "world-test", RUN_ID, FLOOR_ID, agentId, 0, 0);
        InMemoryTransport transport = new InMemoryTransport();
        AgentSession session = new AgentSession(
                config, identity, clock,
                new IdGenerator.DeterministicIdGenerator(
                        agentId + "-decision", agentId + "-message"),
                transport);
        RecordingHandler handler = new RecordingHandler();
        assertEquals(1, transport.startCount);
        transport.connect(0);
        return new Fixture(
                encounter, enemy, clock, session, transport, handler);
    }

    private static AgentProtocol.WorldEventData worldEvent(
            AgentProtocol.WorldEventType type,
            long logicalTick,
            String relatedEntity) {
        return new AgentProtocol.WorldEventData(
                type.name(), logicalTick,
                new AgentProtocol.PositionData(3, 2),
                relatedEntity);
    }

    private static boolean hasEvent(
            AgentSession session,
            AgentSession.LifecycleEventType type) {
        return countEvents(session, type) > 0;
    }

    private static int countEvents(
            AgentSession session,
            AgentSession.LifecycleEventType type) {
        int count = 0;
        for (AgentSession.LifecycleEvent event :
                session.getLifecycleEvents()) {
            if (event.getType() == type) {
                count++;
            }
        }
        return count;
    }

    private enum IdentityMutation {
        SCHEMA {
            @Override
            AgentProtocol.Envelope apply(AgentProtocol.Envelope source) {
                return copy(source, "wrong-schema", source.messageId,
                        source.runId, source.floorId, source.agentId,
                        source.sessionEpoch, source.data);
            }
        },
        MESSAGE_ID {
            @Override
            AgentProtocol.Envelope apply(AgentProtocol.Envelope source) {
                return copy(source, source.schemaVersion, null,
                        source.runId, source.floorId, source.agentId,
                        source.sessionEpoch, source.data);
            }
        },
        RUN {
            @Override
            AgentProtocol.Envelope apply(AgentProtocol.Envelope source) {
                return copy(source, source.schemaVersion, source.messageId,
                        "other-run", source.floorId, source.agentId,
                        source.sessionEpoch, source.data);
            }
        },
        FLOOR {
            @Override
            AgentProtocol.Envelope apply(AgentProtocol.Envelope source) {
                return copy(source, source.schemaVersion, source.messageId,
                        source.runId, source.floorId + 1, source.agentId,
                        source.sessionEpoch, source.data);
            }
        },
        AGENT {
            @Override
            AgentProtocol.Envelope apply(AgentProtocol.Envelope source) {
                return copy(source, source.schemaVersion, source.messageId,
                        source.runId, source.floorId, "guard-b",
                        source.sessionEpoch, source.data);
            }
        },
        EPOCH {
            @Override
            AgentProtocol.Envelope apply(AgentProtocol.Envelope source) {
                return copy(source, source.schemaVersion, source.messageId,
                        source.runId, source.floorId, source.agentId,
                        source.sessionEpoch + 1, source.data);
            }
        },
        DECISION {
            @Override
            AgentProtocol.Envelope apply(AgentProtocol.Envelope source) {
                AgentProtocol.SubmitIntentData data =
                        (AgentProtocol.SubmitIntentData) source.data;
                return copy(source, source.schemaVersion, source.messageId,
                        source.runId, source.floorId, source.agentId,
                        source.sessionEpoch,
                        new AgentProtocol.SubmitIntentData(
                                "other-decision", data.observationSeq(),
                                data.requestGeneration(), data.intent()));
            }
        },
        OBSERVATION {
            @Override
            AgentProtocol.Envelope apply(AgentProtocol.Envelope source) {
                AgentProtocol.SubmitIntentData data =
                        (AgentProtocol.SubmitIntentData) source.data;
                return copy(source, source.schemaVersion, source.messageId,
                        source.runId, source.floorId, source.agentId,
                        source.sessionEpoch,
                        new AgentProtocol.SubmitIntentData(
                                data.decisionId(), data.observationSeq() + 1,
                                data.requestGeneration(), data.intent()));
            }
        },
        GENERATION {
            @Override
            AgentProtocol.Envelope apply(AgentProtocol.Envelope source) {
                AgentProtocol.SubmitIntentData data =
                        (AgentProtocol.SubmitIntentData) source.data;
                return copy(source, source.schemaVersion, source.messageId,
                        source.runId, source.floorId, source.agentId,
                        source.sessionEpoch,
                        new AgentProtocol.SubmitIntentData(
                                data.decisionId(), data.observationSeq(),
                                data.requestGeneration() + 1, data.intent()));
            }
        };

        abstract AgentProtocol.Envelope apply(
                AgentProtocol.Envelope source);

        static AgentProtocol.Envelope copy(
                AgentProtocol.Envelope source,
                String schemaVersion,
                String messageId,
                String runId,
                int floorId,
                String agentId,
                long sessionEpoch,
                AgentProtocol.MessageData data) {
            return new AgentProtocol.Envelope(
                    schemaVersion, messageId, source.messageSeq,
                    "world-test", runId, floorId, agentId, sessionEpoch,
                    source.logicalTick, source.type, data);
        }
    }

    private static final class Fixture {
        private final EncounterHarness encounter;
        private final Enemy enemy;
        private final FakeClock clock;
        private final AgentSession session;
        private final InMemoryTransport transport;
        private final RecordingHandler handler;

        private Fixture(
                EncounterHarness encounter,
                Enemy enemy,
                FakeClock clock,
                AgentSession session,
                InMemoryTransport transport,
                RecordingHandler handler) {
            this.encounter = encounter;
            this.enemy = enemy;
            this.clock = clock;
            this.session = session;
            this.transport = transport;
            this.handler = handler;
        }

        private ObservationEnvelope observation(long sequence) {
            return PerceptionSystem.computeObservation(
                    "world-test", RUN_ID, FLOOR_ID, sequence,
                    encounter.getWorld(), encounter.getEntityMgr(),
                    enemy, encounter.player(), 7,
                    byog.Common.VisionMode.DIRECTIONAL, sequence);
        }

        private ActionOutcome outcome(int actionIndex) {
            Position position = enemy.getPosition();
            return new ActionOutcome(
                    RUN_ID, FLOOR_ID, enemy.getAgentId(),
                    actionIndex, "remote-decision", actionIndex,
                    "MoveAction", Action.ActionResult.SUCCESS,
                    position, position, enemy.getHp(),
                    AgentProtocol.DecisionSource.REMOTE_AGENT, null);
        }
    }

    private static final class FakeClock implements MonotonicClock {
        private long nowNanos;

        @Override
        public long nanoTime() {
            return nowNanos;
        }

        private void advanceMs(long milliseconds) {
            nowNanos += milliseconds * 1_000_000L;
        }
    }

    private static final class InMemoryTransport
            implements AgentTransport {
        private AgentSession.TransportEndpoint endpoint;
        private long nextInboundSequence;
        private int startCount;
        private int rebuildCount;
        private int closeCount;
        private boolean activeConnection;
        private boolean closed;

        @Override
        public void start(AgentSession.TransportEndpoint endpoint) {
            this.endpoint = endpoint;
            startCount++;
        }

        @Override
        public void requestRebuild() {
            rebuildCount++;
            activeConnection = false;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            activeConnection = false;
            closeCount++;
        }

        private void connect(long logicalTick) {
            if (closed) {
                return;
            }
            activeConnection = true;
            endpoint.markConnecting();
            endpoint.markConnected(logicalTick);
            nextInboundSequence = 0;
        }

        /** Simulates transport loss without invoking any socket behavior. */
        private void disconnect(String detail, long logicalTick) {
            activeConnection = false;
            endpoint.markDisconnected(detail, logicalTick);
        }

        private AgentSession.InboundEnqueueResult inject(
                AgentProtocol.Envelope envelope, long logicalTick) {
            return endpoint.offerInbound(envelope, logicalTick);
        }

        private AgentProtocol.Envelope takeOneOutbound() {
            AgentProtocol.Envelope envelope = endpoint.pollOutbound();
            assertNotNull(envelope);
            return envelope;
        }

        private List<AgentProtocol.Envelope> drainOutbound() {
            List<AgentProtocol.Envelope> drained = new ArrayList<>();
            AgentProtocol.Envelope envelope;
            while ((envelope = endpoint.pollOutbound()) != null) {
                drained.add(envelope);
            }
            return drained;
        }

        private AgentProtocol.Envelope validIntentFor(
                AgentProtocol.Envelope request) {
            AgentProtocol.ObservationData observation =
                    (AgentProtocol.ObservationData) request.data;
            AgentProtocol.IntentData intent =
                    new AgentProtocol.IntentData(
                            AgentProtocol.INTENT_VERSION,
                            AgentProtocol.Skill.PATROL,
                            Collections.emptyMap(),
                            0.75,
                            10,
                            new AgentProtocol.InterruptPolicyData(
                                    true, true, true));
            AgentProtocol.SubmitIntentData data =
                    new AgentProtocol.SubmitIntentData(
                            observation.decisionId(),
                            observation.observationSeq(),
                            observation.requestGeneration(),
                            intent);
            return inboundEnvelope(
                    request, AgentProtocol.MessageType.SUBMIT_INTENT, data);
        }

        private AgentProtocol.Envelope cancelAckFor(
                AgentSession.RequestContext request) {
            AgentProtocol.Identity identity = request.getIdentity();
            return new AgentProtocol.Envelope(
                    AgentProtocol.ENVELOPE_VERSION,
                    "inbound-" + nextInboundSequence,
                    nextInboundSequence++,
                    identity.worldId,
                    identity.runId,
                    identity.floorId,
                    identity.agentId,
                    identity.sessionEpoch,
                    0,
                    AgentProtocol.MessageType.CANCEL_ACK,
                    new AgentProtocol.CancelAckData(
                            request.getDecisionId(),
                            request.getRequestGeneration()));
        }

        private AgentProtocol.Envelope unrelatedHeartbeat() {
            return new AgentProtocol.Envelope(
                    AgentProtocol.ENVELOPE_VERSION,
                    "inbound-" + nextInboundSequence,
                    nextInboundSequence++,
                    "world-test", RUN_ID, FLOOR_ID, "guard-a", 1, 0,
                    AgentProtocol.MessageType.HEARTBEAT,
                    new AgentProtocol.HeartbeatData(0));
        }

        private AgentProtocol.Envelope inboundEnvelope(
                AgentProtocol.Envelope request,
                AgentProtocol.MessageType type,
                AgentProtocol.MessageData data) {
            return new AgentProtocol.Envelope(
                    AgentProtocol.ENVELOPE_VERSION,
                    "inbound-" + nextInboundSequence,
                    nextInboundSequence++,
                    request.worldId,
                    request.runId,
                    request.floorId,
                    request.agentId,
                    request.sessionEpoch,
                    request.logicalTick,
                    type,
                    data);
        }
    }

    private static final class RecordingHandler
            implements AgentHandler {
        private int intentCount;
        private int cancellationCount;
        private int failureCount;
        private IntentHandlingResult intentResult =
                IntentHandlingResult.ACCEPTED;

        @Override
        public IntentHandlingResult onIntentSubmitted(
                AgentProtocol.SubmitIntentData data,
                AgentProtocol.Envelope envelope,
                AgentSession.RequestContext requestContext) {
            intentCount++;
            return intentResult;
        }

        @Override
        public void onCancelAcknowledged(
                AgentProtocol.CancelAckData data,
                AgentProtocol.Envelope envelope,
                AgentSession.RequestContext cancelledRequest) {
            cancellationCount++;
        }

        @Override
        public void onProtocolRejected(
                AgentProtocolCodec.ProtocolFailure failure) {
            failureCount++;
        }
    }
}
