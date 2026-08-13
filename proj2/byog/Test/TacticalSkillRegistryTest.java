package byog.Test;

import byog.Action.Action;
import byog.AI.BuiltinTacticalSkills;
import byog.AI.DecisionValidator;
import byog.AI.InterruptPolicy;
import byog.AI.PlanMetadata;
import byog.AI.ReflexObservation;
import byog.AI.SkillInvocation;
import byog.AI.SkillPlanningContext;
import byog.AI.SkillValidation;
import byog.AI.SkillValidationContext;
import byog.AI.StrategicIntent;
import byog.AI.TacticalSkill;
import byog.AI.TacticalSkillRegistry;
import byog.Perception.ObservationEnvelope;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Authority and extension tests for the immutable tactical skill seam. */
public class TacticalSkillRegistryTest {

    @Test
    public void standardRegistryContainsExactlyCurrentCapabilities() {
        TacticalSkillRegistry registry = TacticalSkillRegistry.standard();
        assertTrue(registry.contains("PATROL"));
        assertTrue(registry.contains("CHASE"));
        assertTrue(registry.contains("ATTACK"));
        assertTrue(registry.contains("GUARD"));
        assertFalse(registry.contains("FLY"));
    }

    @Test
    public void duplicateDefinitionIsRejectedAtConstruction() {
        TacticalSkill definition = BuiltinTacticalSkills.definitions().get(0);
        try {
            new TacticalSkillRegistry(List.of(definition, definition));
            fail("duplicate ids must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("duplicate"));
        }
    }

    @Test
    public void chaseValidationAndPlanningShareOneDefinition() {
        String[] map = {
            "#######",
            "#.....#",
            "#.AP..#",
            "#....B#",
            "#....>#",
            "#######"
        };
        EncounterHarness harness = EncounterHarness.fromAscii(
                EncounterHarness.Mode.AGENT_BRIDGE,
                map, 100, 1, 2);
        harness.step();
        ObservationEnvelope source = harness.guardA().getLatestObservation();
        ReflexObservation current = harness.guardA()
                .getLatestReflexObservation();
        SkillInvocation invocation = invocation(
                "CHASE", Map.of("targetPosition",
                        Map.of("x", (long) harness.player().getPosition().x,
                                "y", (long) harness.player().getPosition().y)));

        TacticalSkillRegistry.RegistryValidation validation =
                TacticalSkillRegistry.standard().validateAndTranslate(
                        invocation,
                        new SkillValidationContext(
                                source, current, harness.getWorld()));

        assertTrue(validation.accepted());
        List<Action> actions = TacticalSkillRegistry.standard().planBounded(
                validation.intent(),
                new SkillPlanningContext(
                        harness.guardA().getPosition(),
                        harness.guardA().getId(), harness.getWorld(),
                        harness.getEntityMgr(), new java.util.Random(7)),
                1);
        assertEquals(1, actions.size());
    }

    @Test
    public void unknownAndWrongParametersGetPreciseResults() {
        String[] map = {
            "#######",
            "#.....#",
            "#.A.P.#",
            "#....B#",
            "#....>#",
            "#######"
        };
        EncounterHarness harness = EncounterHarness.fromAscii(
                EncounterHarness.Mode.AGENT_BRIDGE,
                map, 100, 3, 4);
        harness.step();
        SkillValidationContext context = new SkillValidationContext(
                harness.guardA().getLatestObservation(),
                harness.guardA().getLatestReflexObservation(),
                harness.getWorld());

        assertEquals(DecisionValidator.ValidationResult.UNKNOWN_PARAMETER,
                TacticalSkillRegistry.standard().validateAndTranslate(
                        invocation("PATROL", Map.of("targetRoom", 1L)),
                        context).result());
        assertEquals(
                DecisionValidator.ValidationResult.PARAMETER_TYPE_MISMATCH,
                TacticalSkillRegistry.standard().validateAndTranslate(
                        invocation("GUARD",
                                Map.of("targetPosition", "not-a-position")),
                        context).result());
        assertEquals(DecisionValidator.ValidationResult.UNKNOWN_SKILL,
                TacticalSkillRegistry.standard().validateAndTranslate(
                        invocation("FLY", Map.of()), context).result());
    }

    private static SkillInvocation invocation(
            String id, Map<String, Object> parameters) {
        return new SkillInvocation(
                id, parameters, 0.8, 10,
                InterruptPolicy.safeDefault(),
                new PlanMetadata("test-plan", "intent-0", 0));
    }
}
