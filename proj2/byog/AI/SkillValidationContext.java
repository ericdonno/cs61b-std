package byog.AI;

import byog.Perception.ObservationEnvelope;
import byog.TileEngine.TETile;

import java.util.Objects;

/** Authoritative inputs available while validating a skill request. */
public record SkillValidationContext(
        ObservationEnvelope sourceObservation,
        ReflexObservation currentObservation,
        TETile[][] committedWorld) {
    public SkillValidationContext {
        Objects.requireNonNull(sourceObservation, "sourceObservation");
        Objects.requireNonNull(currentObservation, "currentObservation");
        Objects.requireNonNull(committedWorld, "committedWorld");
    }
}
