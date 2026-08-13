package byog.AI;

import byog.Entity.EntityManager;
import byog.TileEngine.TETile;
import byog.lab5.Position;

import java.util.Objects;
import java.util.Random;

/** Java-authoritative planning inputs supplied after validation. */
public record SkillPlanningContext(
        Position actorPosition,
        int actorId,
        TETile[][] committedWorld,
        EntityManager entityManager,
        Random random) {
    public SkillPlanningContext {
        Objects.requireNonNull(actorPosition, "actorPosition");
        Objects.requireNonNull(committedWorld, "committedWorld");
        Objects.requireNonNull(entityManager, "entityManager");
        Objects.requireNonNull(random, "random");
    }
}
