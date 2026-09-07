package byog.AI;

import byog.Action.Action;
import byog.Action.ActionOutcome;
import byog.Bridge.AgentProtocol;
import byog.Perception.ObservationEnvelope;
import byog.Perception.VisibleEntity;
import byog.Perception.VisibleTile;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Definitions for the four tactical skills supported by the Java game. */
public final class BuiltinTacticalSkills {
    private BuiltinTacticalSkills() {
    }

    public static List<TacticalSkill> definitions() {
        return List.of(
                new PatrolSkill(),
                new ChaseSkill(),
                new AttackSkill(),
                new GuardSkill());
    }

    private abstract static class TargetSkill implements TacticalSkill {
        private final String id;
        private final StrategicIntent.Goal goal;
        private final StrategicIntent.Strategy strategy;
        private final boolean targetRequired;

        TargetSkill(String id, StrategicIntent.Goal goal,
                    StrategicIntent.Strategy strategy,
                    boolean targetRequired) {
            this.id = id;
            this.goal = goal;
            this.strategy = strategy;
            this.targetRequired = targetRequired;
        }

        @Override
        public final String id() {
            return id;
        }

        @Override
        public final SkillValidation validate(
                SkillInvocation invocation, SkillValidationContext context) {
            if (!invocation.parameters().keySet().stream()
                    .allMatch("targetPosition"::equals)) {
                return rejected(DecisionValidator.ValidationResult.UNKNOWN_PARAMETER);
            }
            Object rawTarget = invocation.parameters().get("targetPosition");
            if (rawTarget == null && targetRequired) {
                return rejected(DecisionValidator.ValidationResult.MISSING_REQUIRED_PARAMETER);
            }

            Position target;
            if (rawTarget == null) {
                target = deterministicPatrolTarget(context.sourceObservation());
            } else {
                PositionParse parsed = parseTarget(rawTarget);
                if (parsed.result != DecisionValidator.ValidationResult.ACCEPTED) {
                    return rejected(parsed.result);
                }
                target = parsed.position;
            }

            DecisionValidator.ValidationResult common = validateTarget(
                    target, context, requiresVisiblePlayer());
            if (common != DecisionValidator.ValidationResult.ACCEPTED) {
                return rejected(common);
            }
            DecisionValidator.ValidationResult specific = validatePrecondition(
                    target, context.currentObservation());
            if (specific != DecisionValidator.ValidationResult.ACCEPTED) {
                return rejected(specific);
            }

            Map<String, Object> normalized = Map.of(
                    "targetPosition", positionMap(target));
            return SkillValidation.accepted(new SkillInvocation(
                    invocation.skillId(), normalized,
                    invocation.confidence(), invocation.validForTicks(),
                    invocation.interruptPolicy(), invocation.planMetadata()));
        }

        protected boolean requiresVisiblePlayer() {
            return false;
        }

        protected abstract DecisionValidator.ValidationResult validatePrecondition(
                Position target, ReflexObservation observation);

        @Override
        public final StrategicIntent toIntent(
                SkillInvocation invocation, PlanMetadata metadata) {
            Position target = parseTarget(
                    invocation.parameters().get("targetPosition")).position;
            return new StrategicIntent(
                    goal, strategy, target, invocation.confidence(), -1,
                    id, metadata);
        }

        @Override
        public final List<Action> planBounded(
                StrategicIntent intent, SkillPlanningContext context,
                int maxActions) {
            return ClassicalPlanner.translateBounded(
                    intent, context.actorPosition(), context.actorId(),
                    context.committedWorld(), context.entityManager(),
                    context.random(), maxActions);
        }

        protected static boolean playerMatches(
                Position target, ReflexObservation observation) {
            VisibleEntity player = observation.getVisiblePlayer();
            return player != null && target.equals(player.getPosition());
        }

        protected static StepProgress blockedProgress(
                SkillProgressContext context) {
            if (context.allowLocalReroute()
                    && context.localRerouteCount() == 0
                    && context.consecutiveBlocked() == 1) {
                return StepProgress.active(
                        AgentProtocol.OutcomeReason.LOCAL_REROUTE, true);
            }
            return StepProgress.failed(
                    AgentProtocol.OutcomeReason.REPEATED_BLOCKED);
        }

        protected static boolean blocked(ActionOutcome outcome) {
            return outcome.getResult() == Action.ActionResult.BLOCKED
                    || outcome.getResult() == Action.ActionResult.INTERRUPTED;
        }
    }

    private static final class PatrolSkill extends TargetSkill {
        PatrolSkill() {
            super("PATROL", StrategicIntent.Goal.PATROL,
                    StrategicIntent.Strategy.PATROL, false);
        }

        @Override
        protected DecisionValidator.ValidationResult validatePrecondition(
                Position target, ReflexObservation observation) {
            return observation.canSeePlayer()
                    ? DecisionValidator.ValidationResult.SKILL_PRECONDITION_FAILED
                    : DecisionValidator.ValidationResult.ACCEPTED;
        }

        @Override
        public boolean canResume(
                StrategicIntent intent, ReflexObservation observation) {
            return !observation.canSeePlayer();
        }

        @Override
        public StepProgress evaluateProgress(
                StrategicIntent intent, SkillProgressContext context,
                ActionOutcome outcome) {
            if (context.observation().getSelfPosition()
                    .equals(intent.getTargetPosition())) {
                return StepProgress.succeeded(
                        AgentProtocol.OutcomeReason.COMMITMENT_COMPLETED);
            }
            return blocked(outcome) ? blockedProgress(context)
                    : StepProgress.active(
                    AgentProtocol.OutcomeReason.ACTION_COMMITTED, false);
        }
    }

    private static final class ChaseSkill extends TargetSkill {
        ChaseSkill() {
            super("CHASE", StrategicIntent.Goal.CHASE,
                    StrategicIntent.Strategy.CHASE, true);
        }

        @Override
        protected boolean requiresVisiblePlayer() {
            return true;
        }

        @Override
        protected DecisionValidator.ValidationResult validatePrecondition(
                Position target, ReflexObservation observation) {
            return playerMatches(target, observation)
                    ? DecisionValidator.ValidationResult.ACCEPTED
                    : DecisionValidator.ValidationResult.SKILL_PRECONDITION_FAILED;
        }

        @Override
        public boolean canResume(
                StrategicIntent intent, ReflexObservation observation) {
            return playerMatches(intent.getTargetPosition(), observation);
        }

        @Override
        public StepProgress evaluateProgress(
                StrategicIntent intent, SkillProgressContext context,
                ActionOutcome outcome) {
            ReflexObservation observation = ReflexObservation.from(
                    context.observation());
            if (!playerMatches(intent.getTargetPosition(), observation)) {
                return StepProgress.failed(
                        AgentProtocol.OutcomeReason.TARGET_LOST);
            }
            if (manhattan(observation.getSelfPosition(),
                    intent.getTargetPosition()) == 1) {
                return StepProgress.succeeded(
                        AgentProtocol.OutcomeReason.COMMITMENT_COMPLETED);
            }
            return blocked(outcome) ? blockedProgress(context)
                    : StepProgress.active(
                    AgentProtocol.OutcomeReason.ACTION_COMMITTED, false);
        }
    }

    private static final class AttackSkill extends TargetSkill {
        AttackSkill() {
            super("ATTACK", StrategicIntent.Goal.ATTACK_PLAYER,
                    StrategicIntent.Strategy.ATTACK, true);
        }

        @Override
        protected boolean requiresVisiblePlayer() {
            return true;
        }

        @Override
        protected DecisionValidator.ValidationResult validatePrecondition(
                Position target, ReflexObservation observation) {
            if (!playerMatches(target, observation)) {
                return DecisionValidator.ValidationResult.SKILL_PRECONDITION_FAILED;
            }
            return manhattan(observation.getSelfPosition(), target) == 1
                    ? DecisionValidator.ValidationResult.ACCEPTED
                    : DecisionValidator.ValidationResult.SKILL_PRECONDITION_FAILED;
        }

        @Override
        public boolean canResume(
                StrategicIntent intent, ReflexObservation observation) {
            return playerMatches(intent.getTargetPosition(), observation);
        }

        @Override
        public StepProgress evaluateProgress(
                StrategicIntent intent, SkillProgressContext context,
                ActionOutcome outcome) {
            if (outcome.getResult() == Action.ActionResult.DAMAGE) {
                return StepProgress.succeeded(
                        AgentProtocol.OutcomeReason.DAMAGE_COMMITTED);
            }
            ReflexObservation observation = ReflexObservation.from(
                    context.observation());
            return StepProgress.failed(playerMatches(
                    intent.getTargetPosition(), observation)
                    ? AgentProtocol.OutcomeReason.OCCUPIED_OR_TERRAIN_BLOCKED
                    : AgentProtocol.OutcomeReason.TARGET_LOST);
        }
    }

    private static final class GuardSkill extends TargetSkill {
        GuardSkill() {
            super("GUARD", StrategicIntent.Goal.GUARD,
                    StrategicIntent.Strategy.GUARD, true);
        }

        @Override
        protected DecisionValidator.ValidationResult validatePrecondition(
                Position target, ReflexObservation observation) {
            return DecisionValidator.ValidationResult.ACCEPTED;
        }

        @Override
        public boolean canResume(
                StrategicIntent intent, ReflexObservation observation) {
            return true;
        }

        @Override
        public StepProgress evaluateProgress(
                StrategicIntent intent, SkillProgressContext context,
                ActionOutcome outcome) {
            if (blocked(outcome)) {
                return blockedProgress(context);
            }
            return context.actionQueueEmpty()
                    ? StepProgress.succeeded(
                    AgentProtocol.OutcomeReason.COMMITMENT_COMPLETED)
                    : StepProgress.active(
                    AgentProtocol.OutcomeReason.ACTION_COMMITTED, false);
        }
    }

    private static DecisionValidator.ValidationResult validateTarget(
            Position target, SkillValidationContext context,
            boolean playerTarget) {
        TETile[][] world = context.committedWorld();
        if (target.x < 0 || target.x >= world.length
                || target.y < 0 || target.y >= world[0].length) {
            return DecisionValidator.ValidationResult.TARGET_OUT_OF_BOUNDS;
        }
        if (playerTarget) {
            VisibleEntity player = context.sourceObservation().getVisiblePlayer();
            if (player == null || !target.equals(player.getPosition())) {
                return DecisionValidator.ValidationResult.TARGET_NOT_KNOWN;
            }
        } else if (!context.sourceObservation().isWalkable(target.x, target.y)) {
            return DecisionValidator.ValidationResult.TARGET_NOT_KNOWN;
        }
        TETile tile = world[target.x][target.y];
        if (tile == Tileset.WALL || tile == Tileset.NOTHING) {
            return DecisionValidator.ValidationResult.TARGET_UNREACHABLE;
        }
        Position self = context.currentObservation().getSelfPosition();
        if (!self.equals(target)
                && BFSPathfinder.findPath(self, target, world).isEmpty()) {
            return DecisionValidator.ValidationResult.TARGET_UNREACHABLE;
        }
        return DecisionValidator.ValidationResult.ACCEPTED;
    }

    private static PositionParse parseTarget(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return PositionParse.rejected(
                    DecisionValidator.ValidationResult.PARAMETER_TYPE_MISMATCH);
        }
        if (!map.keySet().stream().allMatch(key -> "x".equals(key) || "y".equals(key))) {
            return PositionParse.rejected(
                    DecisionValidator.ValidationResult.UNKNOWN_PARAMETER);
        }
        if (!map.containsKey("x") || !map.containsKey("y")) {
            return PositionParse.rejected(
                    DecisionValidator.ValidationResult.MISSING_REQUIRED_PARAMETER);
        }
        Integer x = exactInt(map.get("x"));
        Integer y = exactInt(map.get("y"));
        if (x == null || y == null) {
            return PositionParse.rejected(
                    DecisionValidator.ValidationResult.PARAMETER_TYPE_MISMATCH);
        }
        return new PositionParse(
                DecisionValidator.ValidationResult.ACCEPTED,
                new Position(x, y));
    }

    private static Integer exactInt(Object value) {
        if (value instanceof Integer integer) {
            return integer;
        }
        if (value instanceof Long number
                && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
            return number.intValue();
        }
        return null;
    }

    private static Map<String, Object> positionMap(Position position) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("x", (long) position.x);
        result.put("y", (long) position.y);
        return result;
    }

    private static Position deterministicPatrolTarget(
            ObservationEnvelope observation) {
        Position self = observation.getSelfPosition();
        for (VisibleTile tile : observation.getVisibleTiles()) {
            if (tile.isWalkable()
                    && (tile.getX() != self.x || tile.getY() != self.y)) {
                return new Position(tile.getX(), tile.getY());
            }
        }
        return self;
    }

    private static int manhattan(Position first, Position second) {
        return Math.abs(first.x - second.x) + Math.abs(first.y - second.y);
    }

    private static SkillValidation rejected(
            DecisionValidator.ValidationResult result) {
        return SkillValidation.rejected(result);
    }

    private record PositionParse(
            DecisionValidator.ValidationResult result, Position position) {
        static PositionParse rejected(
                DecisionValidator.ValidationResult result) {
            return new PositionParse(result, null);
        }
    }
}
