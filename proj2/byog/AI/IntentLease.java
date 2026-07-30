package byog.AI;

import byog.Bridge.AgentProtocol;

import java.util.Objects;

/**
 * 已通过校验的战略意图的执行许可。持有 TTL、来源、决策身份和租约状态。
 *
 * <p>LeaseState 转换：ACTIVE -> SUSPENDED（反射覆盖开始）-> ACTIVE（恢复）
 * 或 ACTIVE/SUSPENDED -> STALE/EXPIRED（不可恢复）。</p>
 */
public final class IntentLease {

    public enum LeaseState {
        ACTIVE,
        SUSPENDED,
        STALE,
        EXPIRED
    }

    private final StrategicIntent intent;
    private final String decisionId;
    private final long basedOnObservationSeq;
    private final long adoptedAtTick;
    private final long validUntilTick;
    private final InterruptPolicy interruptPolicy;
    private final AgentProtocol.DecisionSource decisionSource;
    private LeaseState state;

    public IntentLease(StrategicIntent intent, String decisionId,
                       long basedOnObservationSeq, long adoptedAtTick,
                       long validUntilTick, InterruptPolicy interruptPolicy,
                       AgentProtocol.DecisionSource decisionSource) {
        this.intent = copyIntent(Objects.requireNonNull(intent, "intent"));
        this.decisionId = Objects.requireNonNull(decisionId, "decisionId");
        if (basedOnObservationSeq < 0) {
            throw new IllegalArgumentException("basedOnObservationSeq must be >= 0");
        }
        this.basedOnObservationSeq = basedOnObservationSeq;
        if (adoptedAtTick < 0) {
            throw new IllegalArgumentException("adoptedAtTick must be >= 0");
        }
        this.adoptedAtTick = adoptedAtTick;
        if (validUntilTick <= adoptedAtTick) {
            throw new IllegalArgumentException(
                    "validUntilTick must be > adoptedAtTick");
        }
        this.validUntilTick = validUntilTick;
        this.interruptPolicy = Objects.requireNonNull(interruptPolicy, "interruptPolicy");
        this.decisionSource = Objects.requireNonNull(decisionSource, "decisionSource");
        this.state = LeaseState.ACTIVE;
    }

    public StrategicIntent getIntent() {
        return copyIntent(intent);
    }

    public String getDecisionId() {
        return decisionId;
    }

    public long getBasedOnObservationSeq() {
        return basedOnObservationSeq;
    }

    public long getAdoptedAtTick() {
        return adoptedAtTick;
    }

    public long getValidUntilTick() {
        return validUntilTick;
    }

    public InterruptPolicy getInterruptPolicy() {
        return interruptPolicy;
    }

    public AgentProtocol.DecisionSource getDecisionSource() {
        return decisionSource;
    }

    public LeaseState getState() {
        return state;
    }

    /** 检查 lease 是否在指定 tick 仍可执行（未过期、未失效）。 */
    public boolean isUsableAt(long tick) {
        return state == LeaseState.ACTIVE && tick < validUntilTick;
    }

    /** ACTIVE/SUSPENDED 且 TTL 未到，可用于反射期间的归属和恢复判断。 */
    public boolean isValidAt(long tick) {
        return (state == LeaseState.ACTIVE
                || state == LeaseState.SUSPENDED)
                && tick < validUntilTick;
    }

    /** 检查 TTL 是否已到期。 */
    public boolean isExpiredAt(long tick) {
        return tick >= validUntilTick;
    }

    /** 暂挂 lease（反射覆盖开始时调用）。 */
    public void suspend() {
        if (state == LeaseState.ACTIVE) {
            state = LeaseState.SUSPENDED;
        }
    }

    /** 恢复 lease（反射覆盖结束时调用）；过期 lease 不可恢复。 */
    public boolean resumeAt(long currentTick) {
        refreshExpiry(currentTick);
        if (state == LeaseState.SUSPENDED) {
            state = LeaseState.ACTIVE;
            return true;
        }
        return false;
    }

    /** 标记 lease 前提已过时，不可恢复。 */
    public void markStale() {
        if (state == LeaseState.ACTIVE
                || state == LeaseState.SUSPENDED) {
            state = LeaseState.STALE;
        }
    }

    /** 标记 lease 已过期。 */
    public void markExpired() {
        if (state == LeaseState.ACTIVE
                || state == LeaseState.SUSPENDED) {
            state = LeaseState.EXPIRED;
        }
    }

    /** 如果 TTL 已到则自动标记过期。 */
    public void refreshExpiry(long currentTick) {
        if (isExpiredAt(currentTick)) {
            markExpired();
        }
    }

    /**
     * 防御性复制意图及其目标坐标，避免租约状态被外部对象修改。
     */
    private static StrategicIntent copyIntent(StrategicIntent source) {
        byog.lab5.Position target = source.getTargetPosition();
        byog.lab5.Position targetCopy = target == null
                ? null : new byog.lab5.Position(target.x, target.y);
        return new StrategicIntent(
                source.getGoal(), source.getStrategy(), targetCopy,
                source.getConfidence(), source.getTargetRoom());
    }
}
