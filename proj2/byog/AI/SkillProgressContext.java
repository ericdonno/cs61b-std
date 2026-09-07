package byog.AI;

import byog.Perception.ObservationEnvelope;

import java.util.Objects;

/** Private committed facts and bounded counters used to evaluate one skill. */
public record SkillProgressContext(
        ObservationEnvelope observation,
        boolean actionQueueEmpty,
        int consecutiveBlocked,
        int localRerouteCount,
        boolean allowLocalReroute) {
    public SkillProgressContext {
        Objects.requireNonNull(observation, "observation");
        if (consecutiveBlocked < 0 || localRerouteCount < 0) {
            throw new IllegalArgumentException("progress counters must be non-negative");
        }
    }
}
