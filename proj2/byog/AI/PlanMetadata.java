package byog.AI;

import java.util.Objects;

/** Correlation metadata owned by the external runtime. */
public record PlanMetadata(String planId, String stepId, int revision) {
    public PlanMetadata {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(stepId, "stepId");
        if (planId.isEmpty() || stepId.isEmpty()) {
            throw new IllegalArgumentException("plan metadata ids must not be empty");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
    }
}
