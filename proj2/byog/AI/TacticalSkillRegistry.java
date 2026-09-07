package byog.AI;

import byog.Action.Action;
import byog.Action.ActionOutcome;
import byog.Bridge.AgentProtocol;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable lookup and dispatch table for externally selectable skills. */
public final class TacticalSkillRegistry {
    private static final TacticalSkillRegistry STANDARD =
            new TacticalSkillRegistry(BuiltinTacticalSkills.definitions());

    private final Map<String, TacticalSkill> skills;

    public TacticalSkillRegistry(Collection<? extends TacticalSkill> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        LinkedHashMap<String, TacticalSkill> copy = new LinkedHashMap<>();
        for (TacticalSkill definition : definitions) {
            Objects.requireNonNull(definition, "definition");
            String id = Objects.requireNonNull(definition.id(), "skill id");
            if (id.isEmpty()) {
                throw new IllegalArgumentException("skill id must not be empty");
            }
            if (copy.putIfAbsent(id, definition) != null) {
                throw new IllegalArgumentException("duplicate skill id: " + id);
            }
        }
        skills = Collections.unmodifiableMap(copy);
    }

    public static TacticalSkillRegistry standard() {
        return STANDARD;
    }

    public RegistryValidation validateAndTranslate(
            SkillInvocation invocation, SkillValidationContext context) {
        TacticalSkill skill = skills.get(invocation.skillId());
        if (skill == null) {
            return RegistryValidation.rejected(
                    DecisionValidator.ValidationResult.UNKNOWN_SKILL);
        }
        SkillValidation validation = skill.validate(invocation, context);
        if (!validation.accepted()) {
            return RegistryValidation.rejected(validation.result());
        }
        SkillInvocation normalized = validation.invocation();
        StrategicIntent intent = skill.toIntent(
                normalized, normalized.planMetadata());
        return new RegistryValidation(
                DecisionValidator.ValidationResult.ACCEPTED,
                intent, normalized.interruptPolicy());
    }

    public List<Action> planBounded(
            StrategicIntent intent, SkillPlanningContext context,
            int maxActions) {
        TacticalSkill skill = skills.get(intent.getSkillId());
        if (skill == null) {
            return List.of();
        }
        return skill.planBounded(intent, context, maxActions);
    }

    public boolean canResume(
            StrategicIntent intent, ReflexObservation observation) {
        TacticalSkill skill = skills.get(intent.getSkillId());
        return skill != null && skill.canResume(intent, observation);
    }

    public StepProgress evaluateProgress(
            StrategicIntent intent, SkillProgressContext context,
            ActionOutcome committedOutcome) {
        TacticalSkill skill = skills.get(intent.getSkillId());
        return skill == null
                ? StepProgress.failed(AgentProtocol.OutcomeReason.PRECONDITION_CHANGED)
                : skill.evaluateProgress(intent, context, committedOutcome);
    }

    public boolean contains(String id) {
        return skills.containsKey(id);
    }

    public record RegistryValidation(
            DecisionValidator.ValidationResult result,
            StrategicIntent intent,
            InterruptPolicy interruptPolicy) {
        public static RegistryValidation rejected(
                DecisionValidator.ValidationResult result) {
            return new RegistryValidation(result, null, null);
        }

        public boolean accepted() {
            return result == DecisionValidator.ValidationResult.ACCEPTED;
        }
    }
}
