package byog.AI;

import byog.Helper.MathHelper;
import byog.Perception.ObservationEnvelope;
import byog.Perception.VisibleEntity;
import byog.lab5.Position;

import java.util.List;
import java.util.Objects;

/**
 * Minimal private-observation slice used by the deterministic fast brain.
 *
 * <p>This value object deliberately contains no {@code Player}, world, or
 * {@code EntityManager} reference.</p>
 */
public final class ReflexObservation {
    private final Position selfPosition;
    private final int selfHp;
    private final List<VisibleEntity> visibleEntities;
    private final VisibleEntity visiblePlayer;
    private final boolean playerVisible;
    private final boolean playerAdjacent;

    private ReflexObservation(Position selfPosition, int selfHp,
                              List<VisibleEntity> visibleEntities,
                              VisibleEntity visiblePlayer,
                              boolean playerAdjacent) {
        this.selfPosition = copyPosition(selfPosition);
        this.selfHp = selfHp;
        this.visibleEntities = List.copyOf(visibleEntities);
        this.visiblePlayer = visiblePlayer;
        this.playerVisible = visiblePlayer != null;
        this.playerAdjacent = playerAdjacent;
    }

    public static ReflexObservation from(ObservationEnvelope observation) {
        Objects.requireNonNull(observation, "observation");
        Position self = observation.getSelfPosition();
        VisibleEntity player = observation.getVisiblePlayer();
        boolean adjacent = player != null
                && MathHelper.manhattanDistance(self, player.getPosition()) == 1;
        return new ReflexObservation(self, observation.getSelfHp(),
                observation.getVisibleEntities(), player, adjacent);
    }

    public Position getSelfPosition() {
        return copyPosition(selfPosition);
    }

    public int getSelfHp() {
        return selfHp;
    }

    public List<VisibleEntity> getVisibleEntities() {
        return visibleEntities;
    }

    public boolean canSeePlayer() {
        return playerVisible;
    }

    public boolean isPlayerAdjacent() {
        return playerAdjacent;
    }

    public VisibleEntity getVisiblePlayer() {
        return visiblePlayer;
    }

    private static Position copyPosition(Position position) {
        return position == null ? null : new Position(position.x, position.y);
    }
}
