package byog.IO;

import byog.lab5.Position;

import java.io.Serializable;

/**
 * 当前楼层玩家快照：位置与蓄力。进入下一层时重建，读档时恢复。
 * 不含动画计时、悬停等纯表现状态。
 */
public final class PlayerFloorState implements Serializable {
    private static final long serialVersionUID = 1L;

    private int x;
    private int y;
    private int currentCharge;

    public PlayerFloorState(Position position, int currentCharge) {
        if (position == null) {
            throw new IllegalArgumentException("position must not be null");
        }
        this.x = position.x;
        this.y = position.y;
        this.currentCharge = currentCharge;
    }

    public Position getPosition() {
        return new Position(x, y);
    }

    public int getCurrentCharge() {
        return currentCharge;
    }
}
