package byog.Test;

import byog.Bridge.AgentProtocol;
import byog.Bridge.AgentSession;
import byog.Entity.EntityManager;
import byog.Trace.AgentTrace;
import byog.lab5.Position;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Verifies deterministic, correlated evidence for the asynchronous Agent loop. */
public class AgentTraceContractTest {
    private static final String[] REMOTE_ASCII = {
        "#####################",
        "#A.......B........P>#",
        "#####################"
    };

    @Test
    public void agent_harness_trace_is_byte_identical() {
        assertEquals(runRemotePatrolTrace(), runRemotePatrolTrace());
    }

    @Test
    public void trace_correlates_observation_request_intent_action_and_feedback() {
        EncounterHarness harness = newAgentHarness();
        try {
            beginRemotePatrol(harness);
            harness.step();

            AgentProtocol.Envelope feedbackMessage = findFirstOutbound(
                    harness.drainOutbound("guard-a"),
                    AgentProtocol.MessageType.ACTION_FEEDBACK);
            assertTrue(feedbackMessage.data
                    instanceof AgentProtocol.ActionFeedbackData);

            List<AgentTrace.TraceEvent> events =
                    harness.getTraceSink().events();
            AgentTrace.TraceEvent request = findEvent(
                    events, AgentTrace.EventType.AGENT_REQUEST_SENT);
            AgentTrace.TraceEvent intent = findEvent(
                    events, AgentTrace.EventType.INTENT_ADOPTED);
            AgentTrace.TraceEvent action = findEvent(
                    events, AgentTrace.EventType.ACTION_RESULT);
            AgentTrace.TraceEvent feedback = findEvent(
                    events, AgentTrace.EventType.ACTION_FEEDBACK_ENQUEUED);

            assertEquals(request.decisionId, intent.decisionId);
            assertEquals(intent.decisionId, action.decisionId);
            assertEquals(action.decisionId, feedback.decisionId);
            assertEquals(request.observationSeq, intent.observationSeq);
            assertEquals(action.actionIndex, feedback.actionIndex);
            assertEquals(action.afterX, feedback.afterX);
            assertEquals(action.afterY, feedback.afterY);
            assertEquals(AgentProtocol.DecisionSource.REMOTE_AGENT.name(),
                    action.decisionSource);
        } finally {
            harness.close();
        }
    }

    @Test
    public void stale_response_is_traced_without_replacing_current_behavior() {
        EncounterHarness harness = newAgentHarness();
        try {
            AgentProtocol.Envelope response = beginRemotePatrol(harness);
            harness.step();
            String adoptedDecision = harness.guardA()
                    .getArbiter().getCurrentLease().getDecisionId();

            assertEquals(AgentSession.InboundEnqueueResult.DROPPED_STALE,
                    harness.injectInbound("guard-a", response));
            harness.step();

            assertEquals(adoptedDecision, harness.guardA()
                    .getArbiter().getCurrentLease().getDecisionId());
            AgentTrace.TraceEvent stale = findEvent(
                    harness.getTraceSink().events(),
                    AgentTrace.EventType.STALE_RESPONSE_DROPPED);
            assertEquals(adoptedDecision, stale.decisionId);
            assertEquals("STALE_IDENTITY", stale.validationResult);
        } finally {
            harness.close();
        }
    }

    @Test
    public void fake_clock_drives_soft_and_hard_deadline_events() {
        EncounterHarness harness = newAgentHarness();
        try {
            harness.step();
            harness.advanceClockMs(100);
            harness.step();
            assertNotNull(findEvent(
                    harness.getTraceSink().events(),
                    AgentTrace.EventType.AGENT_SLOW));

            harness.advanceClockMs(100);
            harness.step();
            assertNotNull(findEvent(
                    harness.getTraceSink().events(),
                    AgentTrace.EventType.AGENT_HARD_TIMEOUT));
            assertNotNull(findEvent(
                    harness.getTraceSink().events(),
                    AgentTrace.EventType.AGENT_CANCEL_SENT));
        } finally {
            harness.close();
        }
    }

    @Test
    public void trace_schemas_remain_isolated_from_agent_fields() {
        EncounterHarness legacy = EncounterHarness.legacyV1();
        EncounterHarness perception = EncounterHarness.privatePerceptionV1();
        EncounterHarness agent = newAgentHarness();
        try {
            legacy.step();
            perception.step();
            agent.step();

            assertFalse(legacy.canonicalTrace().contains("\"runId\""));
            assertFalse(perception.canonicalTrace().contains("\"runId\""));
            assertFalse(perception.canonicalTrace()
                    .contains("\"sessionEpoch\""));

            String agentTrace = agent.canonicalTrace();
            assertTrue(agentTrace.contains(
                    "\"schemaVersion\":\""
                            + AgentTrace.AGENT_RUNTIME_TRACE_VERSION + "\""));
            assertTrue(agentTrace.contains("\"runId\""));
            assertFalse(agentTrace.contains("wallClockTimestamp"));
            assertFalse(agentTrace.contains("threadName"));
            assertFalse(agentTrace.contains("socketAddress"));
        } finally {
            agent.close();
        }
    }

    @Test
    public void reflex_override_start_and_end_are_explicit() {
        EncounterHarness harness = newAgentHarness();
        try {
            beginRemotePatrol(harness);
            harness.step();

            movePlayer(harness, new Position(
                    harness.guardA().getPosition().x + 1, 1));
            harness.step();
            harness.step();
            assertNotNull(findEvent(
                    harness.getTraceSink().events(),
                    AgentTrace.EventType.REFLEX_OVERRIDE_STARTED));

            movePlayer(harness, new Position(18, 1));
            harness.step();
            harness.step();
            assertNotNull(findEvent(
                    harness.getTraceSink().events(),
                    AgentTrace.EventType.REFLEX_OVERRIDE_ENDED));
        } finally {
            harness.close();
        }
    }

    private static String runRemotePatrolTrace() {
        EncounterHarness harness = newAgentHarness();
        try {
            beginRemotePatrol(harness);
            harness.step();
            return harness.canonicalTrace();
        } finally {
            harness.close();
        }
    }

    private static EncounterHarness newAgentHarness() {
        return EncounterHarness.fromAscii(
                EncounterHarness.Mode.AGENT_BRIDGE,
                REMOTE_ASCII, 1000, 101, 202);
    }

    private static AgentProtocol.Envelope beginRemotePatrol(
            EncounterHarness harness) {
        harness.step();
        AgentProtocol.Envelope request = findFirstOutbound(
                harness.drainOutbound("guard-a"),
                AgentProtocol.MessageType.OBSERVATION);
        AgentProtocol.Envelope response =
                harness.patrolResponse("guard-a", request);
        assertEquals(AgentSession.InboundEnqueueResult.ACCEPTED,
                harness.injectInbound("guard-a", response));
        return response;
    }

    private static AgentProtocol.Envelope findFirstOutbound(
            List<AgentProtocol.Envelope> messages,
            AgentProtocol.MessageType type) {
        for (AgentProtocol.Envelope message : messages) {
            if (message.type == type) {
                return message;
            }
        }
        throw new AssertionError("missing outbound " + type);
    }

    private static AgentTrace.TraceEvent findEvent(
            List<AgentTrace.TraceEvent> events,
            AgentTrace.EventType eventType) {
        for (AgentTrace.TraceEvent event : events) {
            if (event.eventType == eventType
                    && "guard-a".equals(event.agentId)) {
                return event;
            }
        }
        throw new AssertionError("missing trace event " + eventType);
    }

    private static void movePlayer(
            EncounterHarness harness, Position destination) {
        EntityManager entities = harness.getEntityMgr();
        entities.removeEntity(harness.player());
        harness.player().setPosition(destination);
        entities.addEntity(harness.player());
    }
}
