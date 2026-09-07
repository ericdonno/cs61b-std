package byog.AI;

import byog.Bridge.AgentProtocol;

import java.util.Objects;

/** Java-authoritative result of evaluating one committed plan step. */
public record StepProgress(
        AgentProtocol.OutcomeReason reasonCode,
        AgentProtocol.StepStatus stepStatus,
        AgentProtocol.PlanStatus planStatus,
        boolean localReroute) {
    public StepProgress {
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(stepStatus, "stepStatus");
        Objects.requireNonNull(planStatus, "planStatus");
    }

    public static StepProgress active(AgentProtocol.OutcomeReason reason,
                                      boolean localReroute) {
        return new StepProgress(reason, AgentProtocol.StepStatus.ACTIVE,
                AgentProtocol.PlanStatus.ACTIVE, localReroute);
    }

    public static StepProgress succeeded(AgentProtocol.OutcomeReason reason) {
        return new StepProgress(reason, AgentProtocol.StepStatus.SUCCEEDED,
                AgentProtocol.PlanStatus.ACTIVE, false);
    }

    public static StepProgress failed(AgentProtocol.OutcomeReason reason) {
        return new StepProgress(reason, AgentProtocol.StepStatus.FAILED,
                AgentProtocol.PlanStatus.REPLAN_REQUIRED, false);
    }
}
