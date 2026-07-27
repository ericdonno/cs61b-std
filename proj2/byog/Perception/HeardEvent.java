package byog.Perception;

import byog.lab5.Position;

/**
 * 敌人听到的声音事件；当前仅定义数据结构。
 */
public final class HeardEvent {
    public enum SoundType { ATTACK, MOVE, DEATH, ALERT }

    private final SoundType type;
    private final Position sourcePosition;
    private final long turn;

    public HeardEvent(SoundType type, Position sourcePosition, long turn) {
        this.type = type;
        this.sourcePosition = copyPosition(sourcePosition);
        this.turn = turn;
    }

    public SoundType getType() {
        return type;
    }

    /** 声源位置（可能不精确） */
    public Position getSourcePosition() {
        return copyPosition(sourcePosition);
    }

    /** 产生声音的 turn */
    public long getTurn() {
        return turn;
    }

    private static Position copyPosition(Position position) {
        return position == null ? null : new Position(position.x, position.y);
    }
}
