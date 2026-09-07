package byog.Test;

import byog.Action.ActionOutcome;
import byog.Bridge.AgentProtocol;
import byog.Bridge.AgentSession;
import byog.lab5.Position;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** Verifies committed feedback identity and current-step wire projection. */
public class ExecutionFeedbackContractTest {
    private static final String[] MAP = {
        "#####################",
        "#A.......B........P>#",
        "#####################"
    };

    @Test
    public void committedRemoteActionKeepsFrozenPlanIdentity() {
        EncounterHarness harness = EncounterHarness.fromAscii(
                EncounterHarness.Mode.AGENT_BRIDGE,
                MAP, 1000, 101, 202);
        try {
            harness.step();
            AgentProtocol.Envelope request = first(
                    harness.drainOutbound("guard-a"),
                    AgentProtocol.MessageType.OBSERVATION);
            AgentProtocol.Envelope response =
                    harness.patrolResponse("guard-a", request);
            assertEquals(AgentSession.InboundEnqueueResult.ACCEPTED,
                    harness.injectInbound("guard-a", response));
            harness.step();

            ActionOutcome outcome = harness.guardA().getLastActionOutcome();
            assertNotNull(outcome);
            assertNotNull(outcome.getFeedbackId());
            assertNotNull(outcome.getPlanMetadata());
            assertEquals(request.data instanceof AgentProtocol.ObservationData data
                            ? data.decisionId() + ":plan" : null,
                    outcome.getPlanMetadata().planId());

            AgentProtocol.ActionFeedbackData feedback =
                    (AgentProtocol.ActionFeedbackData) first(
                            harness.drainOutbound("guard-a"),
                            AgentProtocol.MessageType.ACTION_FEEDBACK).data;
            assertEquals(AgentProtocol.FEEDBACK_VERSION,
                    feedback.feedbackVersion());
            assertEquals(outcome.getFeedbackId(), feedback.feedbackId());
            assertEquals(outcome.getPlanMetadata().stepId(), feedback.stepId());
            assertEquals(outcome.getStepStatus().name(), feedback.stepStatus());
        } finally {
            harness.close();
        }
    }

    @Test
    public void localFeedbackIsExplicitlyUntracked() {
        ActionOutcome outcome = new ActionOutcome(
                "run", 1, "guard", 1, "local", 1,
                "WaitAction", byog.Action.Action.ActionResult.SUCCESS,
                new Position(1, 1), new Position(1, 1), 20,
                AgentProtocol.DecisionSource.LOCAL_FALLBACK, null);
        assertEquals(AgentProtocol.StepStatus.UNTRACKED,
                outcome.getStepStatus());
        assertEquals(AgentProtocol.PlanStatus.UNTRACKED,
                outcome.getPlanStatus());
    }

    private static AgentProtocol.Envelope first(
            List<AgentProtocol.Envelope> messages,
            AgentProtocol.MessageType type) {
        return messages.stream().filter(message -> message.type == type)
                .findFirst().orElseThrow();
    }
}
