package byog.Core;

import byog.Common.Direction;

/** Converts held direction keys into immediate, evenly paced grid moves. */
public final class PlayerMoveController {
    public static final long REPEAT_INTERVAL_NANOS = 60_000_000L;
    private static final Direction[] DIRECTIONS = Direction.values();

    private final boolean[] previousDown = new boolean[DIRECTIONS.length];
    private final boolean[] currentDown = new boolean[DIRECTIONS.length];
    private final long[] pressOrder = new long[DIRECTIONS.length];
    private long orderCounter;
    private long nextMoveAt;
    private Direction activeDirection;

    /**
     * Returns the next requested move, or null when the held input is not due.
     * Missed repeats are discarded so a slow frame cannot create later movement.
     */
    public Direction nextMove(boolean up, boolean down, boolean left,
                              boolean right, long nowNanos) {
        currentDown[Direction.UP.ordinal()] = up;
        currentDown[Direction.DOWN.ordinal()] = down;
        currentDown[Direction.LEFT.ordinal()] = left;
        currentDown[Direction.RIGHT.ordinal()] = right;

        for (Direction direction : DIRECTIONS) {
            int index = direction.ordinal();
            if (currentDown[index] && !previousDown[index]) {
                pressOrder[index] = ++orderCounter;
            }
        }

        Direction selected = null;
        long newestOrder = Long.MIN_VALUE;
        for (Direction direction : DIRECTIONS) {
            int index = direction.ordinal();
            if (currentDown[index] && pressOrder[index] > newestOrder) {
                selected = direction;
                newestOrder = pressOrder[index];
            }
            previousDown[index] = currentDown[index];
        }

        if (selected == null) {
            activeDirection = null;
            nextMoveAt = 0;
            return null;
        }
        if (selected != activeDirection) {
            activeDirection = selected;
            nextMoveAt = nowNanos + REPEAT_INTERVAL_NANOS;
            return selected;
        }
        if (nowNanos - nextMoveAt >= 0) {
            nextMoveAt = nowNanos + REPEAT_INTERVAL_NANOS;
            return selected;
        }
        return null;
    }

    public void reset() {
        for (int i = 0; i < previousDown.length; i++) {
            previousDown[i] = false;
            currentDown[i] = false;
            pressOrder[i] = 0;
        }
        orderCounter = 0;
        nextMoveAt = 0;
        activeDirection = null;
    }
}
