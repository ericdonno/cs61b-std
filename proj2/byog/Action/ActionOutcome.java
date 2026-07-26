package byog.Action;

import byog.Bridge.AgentProtocol;
import byog.lab5.Position;

import java.util.Objects;

/**
 * Immutable report completed after the world commit barrier for one action.
 */
public final class ActionOutcome {
    private final String runId;
    private final int floorId;
    private final String agentId;
    private final long logicalTick;
    private final String decisionId;
    private final int actionIndex;
    private final String actionType;
    private final Action.ActionResult result;
    private final Position beforePosition;
    private final Position afterPosition;
    private final int selfHp;
    private final AgentProtocol.DecisionSource decisionSource;
    private final String overrideReason;

    public ActionOutcome(String runId, int floorId, String agentId,
                         long logicalTick, String decisionId, int actionIndex,
                         String actionType, Action.ActionResult result,
                         Position beforePosition, Position afterPosition,
                         int selfHp,
                         AgentProtocol.DecisionSource decisionSource,
                         String overrideReason) {
        this.runId = requireNonBlank(runId, "runId");
        if (floorId < 1) {
            throw new IllegalArgumentException("floorId must be >= 1");
        }
        if (logicalTick < 0) {
            throw new IllegalArgumentException("logicalTick must be >= 0");
        }
        this.floorId = floorId;
        this.agentId = requireNonBlank(agentId, "agentId");
        this.logicalTick = logicalTick;
        this.decisionId = requireNonBlank(decisionId, "decisionId");
        if (actionIndex < 0) {
            throw new IllegalArgumentException("actionIndex must be >= 0");
        }
        this.actionIndex = actionIndex;
        this.actionType = requireNonBlank(actionType, "actionType");
        this.result = Objects.requireNonNull(result, "result");
        this.beforePosition = copyRequired(beforePosition, "beforePosition");
        this.afterPosition = copyRequired(afterPosition, "afterPosition");
        this.selfHp = selfHp;
        this.decisionSource = Objects.requireNonNull(
                decisionSource, "decisionSource");
        this.overrideReason = overrideReason;
    }

    public String getRunId() {
        return runId;
    }

    public int getFloorId() {
        return floorId;
    }

    public String getAgentId() {
        return agentId;
    }

    public long getLogicalTick() {
        return logicalTick;
    }

    public String getDecisionId() {
        return decisionId;
    }

    public int getActionIndex() {
        return actionIndex;
    }

    public String getActionType() {
        return actionType;
    }

    public Action.ActionResult getResult() {
        return result;
    }

    public Position getBeforePosition() {
        return copyPosition(beforePosition);
    }

    public Position getAfterPosition() {
        return copyPosition(afterPosition);
    }

    public int getSelfHp() {
        return selfHp;
    }

    public AgentProtocol.DecisionSource getDecisionSource() {
        return decisionSource;
    }

    public String getOverrideReason() {
        return overrideReason;
    }

    private static Position copyRequired(Position value, String name) {
        Objects.requireNonNull(value, name);
        return copyPosition(value);
    }

    private static Position copyPosition(Position value) {
        return new Position(value.x, value.y);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
