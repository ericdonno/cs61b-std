package byog.AI;

import byog.Bridge.AgentProtocol;
import byog.TileEngine.TETile;
import byog.Helper.Logger;

import java.util.Objects;

/**
 * P0-P4 仲裁者。按固定优先级决定谁控制当前 action tick。
 *
 * <p>优先级：P0(Java 安全) > P1(相邻攻击) > P2(可见接战) > P3(远程 lease) > P4(本地 fallback)。
 * 管理反射覆盖生命周期（暂挂/恢复 lease）和远程 lease 的安全采纳。</p>
 */
public final class IntentArbiter {

    /** 仲裁决策级别 */
    public enum Level {
        P1_REFLEX,
        P2_REFLEX,
        P3_LEASE,
        P4_LOCAL_FALLBACK
    }

    /** 一次 action tick 的仲裁结果 */
    public static final class ArbiterDecision {
        private final Level level;

        private ArbiterDecision(Level level) {
            this.level = level;
        }

        public Level getLevel() {
            return level;
        }
    }

    private static final ArbiterDecision P1 = new ArbiterDecision(Level.P1_REFLEX);
    private static final ArbiterDecision P2 = new ArbiterDecision(Level.P2_REFLEX);
    private static final ArbiterDecision P3 = new ArbiterDecision(Level.P3_LEASE);
    private static final ArbiterDecision P4 = new ArbiterDecision(Level.P4_LOCAL_FALLBACK);

    private final ReflexController reflexController;
    private final DecisionValidator validator;
    private IntentLease currentLease;
    private boolean overrideActive;
    private String overrideReason;

    public IntentArbiter() {
        this(new ReflexController(), new DecisionValidator());
    }

    public IntentArbiter(ReflexController reflexController,
                         DecisionValidator validator) {
        this.reflexController = Objects.requireNonNull(reflexController, "reflexController");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    /**
     * 评估 P0-P4 并返回当前 action tick 的控制级别。
     * 同时管理反射覆盖的 begin/end 和 lease 状态转换。
     *
     * @param reflex      当前私有感知切片
     * @param currentTick 当前逻辑 tick
     * @return 仲裁决策
     */
    public ArbiterDecision decide(ReflexObservation reflex, long currentTick) {
        // 刷新 lease TTL
        if (currentLease != null) {
            currentLease.refreshExpiry(currentTick);
        }

        // P1：玩家相邻 -> 立即攻击
        if (reflexController.shouldTriggerP1(reflex)) {
            beginOrUpdateReflexOverride(
                    ReflexController.P1_ADJACENT_THREAT);
            return P1;
        }

        // P2：玩家可见且 policy 允许 -> 短期接战
        InterruptPolicy policy = currentLease != null
                && currentLease.isValidAt(currentTick)
                ? currentLease.getInterruptPolicy()
                : InterruptPolicy.safeDefault();
        if (reflexController.shouldTriggerP2(reflex, policy)) {
            beginOrUpdateReflexOverride(
                    ReflexController.P2_VISIBLE_PLAYER);
            return P2;
        }

        // 反射覆盖结束：尝试恢复 lease
        if (overrideActive) {
            endReflexOverride(reflex, currentTick);
        }

        // P3：有效 lease + queue
        if (currentLease != null && currentLease.isUsableAt(currentTick)) {
            return P3;
        }

        // P4：本地 fallback
        return P4;
    }

    /**
     * 校验并采纳远程 intent。校验通过时替换当前 lease，失败时无副作用。
     *
     * @return ACCEPTED 或具体的失败原因
     */
    public DecisionValidator.ValidationResult tryAdoptRemoteIntent(
            AgentProtocol.SubmitIntentData proposal,
            AgentProtocol.Envelope envelope,
            DecisionValidator.RequestExpectation expectation,
            ReflexObservation currentObservation,
            TETile[][] committedWorld,
            long currentLogicalTick) {

        DecisionValidator.ValidationResult result = validator.validate(
                proposal, envelope, expectation,
                currentObservation, committedWorld);

        if (result != DecisionValidator.ValidationResult.ACCEPTED) {
            Logger.info("IntentArbiter: rejected remote intent: %s", result);
            return result;
        }

        // 映射为 Java 内部 StrategicIntent
        StrategicIntent intent = DecisionValidator.toStrategicIntent(
                proposal.intent(), expectation.sourceObservation);

        // 构造 InterruptPolicy
        InterruptPolicy policy = InterruptPolicy.fromData(
                proposal.intent().interruptPolicy(),
                proposal.intent().skill());

        // 计算 TTL
        int validForTicks = proposal.intent().validForTicks();
        long validUntilTick = addTicks(
                currentLogicalTick, validForTicks);

        // 创建并采纳 lease
        IntentLease lease = new IntentLease(
                intent, proposal.decisionId(),
                proposal.observationSeq(),
                currentLogicalTick, validUntilTick,
                policy, AgentProtocol.DecisionSource.REMOTE_AGENT);

        // 反射覆盖期间不替换当前执行中的 lease，
        // 但旧 lease 被新远程 lease 安全接管时清除覆盖
        if (overrideActive) {
            overrideActive = false;
            overrideReason = null;
            Logger.debug("IntentArbiter: override cleared by remote lease adoption");
        }
        if (currentLease != null) {
            currentLease.markStale();
        }
        currentLease = lease;
        Logger.info("IntentArbiter: adopted remote lease decisionId=%s skill=%s validUntil=%d",
                lease.getDecisionId(), proposal.intent().skill(), validUntilTick);
        return result;
    }

    /**
     * 创建并采纳本地 fallback lease。
     *
     * @param intent         RuleBasedBrain 生成的战略意图
     * @param decisionId     本地决策 ID
     * @param observationSeq 当前 observation 序号
     * @param currentTick    当前逻辑 tick
     * @param validForTicks  有效 tick 数
     * @return 创建的 lease
     */
    public IntentLease adoptLocalFallbackLease(StrategicIntent intent,
                                                String decisionId,
                                                long observationSeq,
                                                long currentTick,
                                                int validForTicks) {
        IntentLease lease = new IntentLease(
                intent, decisionId, observationSeq,
                currentTick, addTicks(currentTick, validForTicks),
                InterruptPolicy.safeDefault(),
                AgentProtocol.DecisionSource.LOCAL_FALLBACK);
        if (currentLease != null) {
            currentLease.markStale();
        }
        currentLease = lease;
        return lease;
    }

    /** 获取当前 lease（可能为 null）。 */
    public IntentLease getCurrentLease() {
        return currentLease;
    }

    /** 是否处于反射覆盖中。 */
    public boolean isInReflexOverride() {
        return overrideActive;
    }

    /** 获取覆盖原因（非覆盖时为 null）。 */
    public String getOverrideReason() {
        return overrideReason;
    }

    /** 开始或升级反射覆盖；P2 转 P1 时同步更新原因。 */
    private void beginOrUpdateReflexOverride(String reason) {
        if (!overrideActive) {
            overrideActive = true;
            if (currentLease != null) {
                currentLease.suspend();
            }
            Logger.debug(
                    "IntentArbiter: reflex override started: %s",
                    reason);
        }
        overrideReason = reason;
    }

    /**
     * 结束反射覆盖：尝试恢复 lease。
     * 只恢复仍有效且前提仍成立的 lease。
     */
    private void endReflexOverride(
            ReflexObservation reflex, long currentTick) {
        overrideActive = false;
        String endedReason = overrideReason;
        overrideReason = null;

        if (currentLease != null) {
            if (canResumeLease(reflex, currentTick)
                    && currentLease.resumeAt(currentTick)) {
                Logger.debug("IntentArbiter: reflex override ended: %s, lease resumed",
                        endedReason);
            } else {
                currentLease.markStale();
                Logger.info("IntentArbiter: reflex override ended: %s, lease marked STALE",
                        endedReason);
            }
        }
    }

    /**
     * 检查当前 lease 是否可以恢复。
     * CHASE/ATTACK: 玩家必须仍可见
     * PATROL: 玩家必须不可见（否则巡逻前提已失效）
     * GUARD: 总是可恢复
     */
    private boolean canResumeLease(
            ReflexObservation reflex, long currentTick) {
        if (currentLease == null) {
            return false;
        }
        if (!currentLease.isValidAt(currentTick)) {
            return false;
        }

        StrategicIntent intent = currentLease.getIntent();
        StrategicIntent.Strategy strategy = intent.getStrategy();
        switch (strategy) {
            case CHASE:
            case ATTACK:
                if (!reflex.canSeePlayer()) {
                    return false;
                }
                byog.lab5.Position expected = intent.getTargetPosition();
                byog.lab5.Position visible =
                        reflex.getVisiblePlayer().getPosition();
                return expected != null
                        && expected.equals(visible);
            case PATROL:
                return !reflex.canSeePlayer();
            case GUARD:
                return true;
            default:
                return true;
        }
    }

    private static long addTicks(long currentTick, int validForTicks) {
        if (currentTick < 0) {
            throw new IllegalArgumentException(
                    "currentTick must be >= 0");
        }
        try {
            return Math.addExact(currentTick, validForTicks);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "lease TTL overflows logical tick", e);
        }
    }
}
