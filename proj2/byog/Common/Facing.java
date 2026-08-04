package byog.Common;

/**
 * 敌人正式朝向：四个 cardinal 方向。与 {@link Direction} 显式转换，
 * 不直接把 Direction 暴露为 wire facing。
 */
public enum Facing {
    NORTH(0, 1),
    EAST(1, 0),
    SOUTH(0, -1),
    WEST(-1, 0);

    public final int dx;
    public final int dy;

    Facing(int dx, int dy) {
        this.dx = dx;
        this.dy = dy;
    }

    /** 从移动方向转换；非法方向返回 null。 */
    public static Facing fromDirection(Direction direction) {
        if (direction == null) {
            return null;
        }
        return switch (direction) {
            case UP -> NORTH;
            case DOWN -> SOUTH;
            case LEFT -> WEST;
            case RIGHT -> EAST;
        };
    }

    /** 顺时针转 90°。 */
    public Facing clockwise() {
        return switch (this) {
            case NORTH -> EAST;
            case EAST -> SOUTH;
            case SOUTH -> WEST;
            case WEST -> NORTH;
        };
    }
}
