package byog.AI;

import byog.Action.Action;

import java.util.List;

/** Single authority seam for one externally selectable tactical skill. */
public interface TacticalSkill {
    String id();

    SkillValidation validate(
            SkillInvocation invocation, SkillValidationContext context);

    StrategicIntent toIntent(
            SkillInvocation invocation, PlanMetadata metadata);

    List<Action> planBounded(
            StrategicIntent intent, SkillPlanningContext context, int maxActions);

    boolean canResume(
            StrategicIntent intent, ReflexObservation observation);
}
