package byog.AI;

import byog.lab5.Position;

import java.io.Serializable;

/**
 * 可保存的确定性巡视状态。
 *
 * <p>只承载数据，不持有 Random、Observation、world 或实体引用；
 * 由 {@code PatrolController} 读取并转换。进入下一层时重置；
 * 读档时原样恢复，目标坐标经过快照校验后才继续使用。</p>
 */
public final class PatrolState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 巡视生命周期模式。 */
    public enum Mode {
        TRAVELING,
        DWELLING,
        SCANNING
    }

    private Mode mode;
    private int targetX;
    private int targetY;
    private boolean hasTarget;
    private int dwellActionsRemaining;
    private int scanTurnsRemaining;
    private int blockedAttempts;
    private int selectionOrdinal;

    public PatrolState() {
        this.mode = Mode.TRAVELING;
        this.hasTarget = false;
        this.dwellActionsRemaining = 0;
        this.scanTurnsRemaining = 0;
        this.blockedAttempts = 0;
        this.selectionOrdinal = 0;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public boolean hasTarget() {
        return hasTarget;
    }

    public Position getTarget() {
        return hasTarget ? new Position(targetX, targetY) : null;
    }

    public void setTarget(Position target) {
        if (target == null) {
            this.hasTarget = false;
            this.targetX = 0;
            this.targetY = 0;
            return;
        }
        this.hasTarget = true;
        this.targetX = target.x;
        this.targetY = target.y;
    }

    public int getDwellActionsRemaining() {
        return dwellActionsRemaining;
    }

    public void setDwellActionsRemaining(int dwellActionsRemaining) {
        this.dwellActionsRemaining = dwellActionsRemaining;
    }

    public int getScanTurnsRemaining() {
        return scanTurnsRemaining;
    }

    public void setScanTurnsRemaining(int scanTurnsRemaining) {
        this.scanTurnsRemaining = scanTurnsRemaining;
    }

    public int getBlockedAttempts() {
        return blockedAttempts;
    }

    public void setBlockedAttempts(int blockedAttempts) {
        this.blockedAttempts = blockedAttempts;
    }

    public int getSelectionOrdinal() {
        return selectionOrdinal;
    }

    public void setSelectionOrdinal(int selectionOrdinal) {
        this.selectionOrdinal = selectionOrdinal;
    }

    /** 每次真正选择新目标后调用，ordinal 加一；失败或仅检查候选时不增加。 */
    public void advanceSelectionOrdinal() {
        this.selectionOrdinal++;
    }

    /** 换层时重置为初始空状态。 */
    public void reset() {
        this.mode = Mode.TRAVELING;
        this.hasTarget = false;
        this.dwellActionsRemaining = 0;
        this.scanTurnsRemaining = 0;
        this.blockedAttempts = 0;
        this.selectionOrdinal = 0;
    }
}
