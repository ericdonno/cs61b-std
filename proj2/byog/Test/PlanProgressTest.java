package byog.Test;

import byog.Action.Action;
import byog.Action.ActionOutcome;
import byog.AI.PlanMetadata;
import byog.AI.SkillProgressContext;
import byog.AI.StepProgress;
import byog.AI.StrategicIntent;
import byog.AI.TacticalSkillRegistry;
import byog.Bridge.AgentProtocol;
import byog.Perception.ObservationEnvelope;
import byog.lab5.Position;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Verifies bounded Java-authoritative progress policies. */
public class PlanProgressTest {
    @Test
    public void patrolBlockedReroutesOnceThenRequiresReplan() {
        EncounterHarness harness = EncounterHarness.agentBridgeV1();
        try {
            harness.step();
            ObservationEnvelope observation =
                    harness.guardA().getLatestObservation();
            Position target = observation.getVisibleTiles().stream()
                    .filter(tile -> tile.isWalkable()
                            && (tile.getX() != observation.getSelfPosition().x
                            || tile.getY() != observation.getSelfPosition().y))
                    .map(tile -> new Position(tile.getX(), tile.getY()))
                    .findFirst().orElse(observation.getSelfPosition());
            PlanMetadata metadata = new PlanMetadata("plan", "step", 0);
            StrategicIntent intent = new StrategicIntent(
                    StrategicIntent.Goal.PATROL,
                    StrategicIntent.Strategy.PATROL,
                    target, 0.9, -1, "PATROL", metadata);
            ActionOutcome blocked = outcome(metadata, Action.ActionResult.BLOCKED);

            StepProgress first = TacticalSkillRegistry.standard()
                    .evaluateProgress(intent, new SkillProgressContext(
                            observation, false, 1, 0, true), blocked);
            StepProgress second = TacticalSkillRegistry.standard()
                    .evaluateProgress(intent, new SkillProgressContext(
                            observation, false, 2, 1, true), blocked);

            assertTrue(first.localReroute());
            assertEquals(AgentProtocol.OutcomeReason.LOCAL_REROUTE,
                    first.reasonCode());
            assertEquals(AgentProtocol.StepStatus.FAILED,
                    second.stepStatus());
            assertEquals(AgentProtocol.PlanStatus.REPLAN_REQUIRED,
                    second.planStatus());
        } finally {
            harness.close();
        }
    }

    private static ActionOutcome outcome(
            PlanMetadata metadata, Action.ActionResult result) {
        return new ActionOutcome(
                "run", 1, "guard", 1, "decision", "feedback",
                "PATROL", metadata, 1, "MoveAction", result,
                new Position(1, 1), new Position(1, 1), 20,
                AgentProtocol.DecisionSource.REMOTE_AGENT, null,
                AgentProtocol.OutcomeReason.OCCUPIED_OR_TERRAIN_BLOCKED,
                AgentProtocol.StepStatus.ACTIVE,
                AgentProtocol.PlanStatus.ACTIVE);
    }
}
