package byog.Common;

public enum Direction {
    UP(0, 1), DOWN(0, -1), LEFT(-1, 0), RIGHT(1, 0);

    public final int dx;
    public final int dy;

    Direction(int dx, int dy) {
        this.dx = dx;
        this.dy = dy;
    }

    public static Direction fromDelta(int dx, int dy) {
        for (Direction d : values()) {
            if (d.dx == dx && d.dy == dy) {
                return d;
            }
        }
        return null;
    }
}
