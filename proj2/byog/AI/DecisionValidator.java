package byog.AI;

import byog.Bridge.AgentProtocol;
import byog.Perception.ObservationEnvelope;
import byog.Perception.VisibleEntity;
import byog.Perception.VisibleTile;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.Objects;

/**
 * 校验远程 submit_intent 是否可以进入游戏。
 *
 * <p>分两层：先检查完整身份元组，再检查 skill/参数/知识边界/可达性/语义时效。
 * 校验失败不得修改 lease、queue、cooldown 或 Entity 状态。</p>
 */
public final class DecisionValidator {

    /** 类型化校验结果，不使用自由文本作为唯一语义。 */
    public enum ValidationResult {
        ACCEPTED,
        SCHEMA_MISMATCH,
        IDENTITY_MISMATCH,
        SESSION_EPOCH_MISMATCH,
        REQUEST_GENERATION_MISMATCH,
        DECISION_ID_MISMATCH,
        OBSERVATION_SEQ_MISMATCH,
        UNKNOWN_SKILL,
        INVALID_PARAMETERS,
        TARGET_OUT_OF_BOUNDS,
        TARGET_NOT_KNOWN,
        TARGET_UNREACHABLE,
        INVALID_TTL,
        INVALID_INTERRUPT_POLICY,
        STALE_PRECONDITION
    }

    /** 校验所需的身份与请求上下文。 */
    public static final class RequestExpectation {
        public final AgentProtocol.Identity identity;
        public final String expectedDecisionId;
        public final long expectedObservationSeq;
        public final long expectedRequestGeneration;
        public final ObservationEnvelope sourceObservation;

        public RequestExpectation(AgentProtocol.Identity identity,
                                  String expectedDecisionId,
                                  long expectedObservationSeq,
                                  long expectedRequestGeneration,
                                  ObservationEnvelope sourceObservation) {
            this.identity = Objects.requireNonNull(identity, "identity");
            this.expectedDecisionId = Objects.requireNonNull(
                    expectedDecisionId, "expectedDecisionId");
            if (expectedObservationSeq < 0) {
                throw new IllegalArgumentException(
                        "expectedObservationSeq must be >= 0");
            }
            this.expectedObservationSeq = expectedObservationSeq;
            if (expectedRequestGeneration < 0) {
                throw new IllegalArgumentException(
                        "expectedRequestGeneration must be >= 0");
            }
            this.expectedRequestGeneration = expectedRequestGeneration;
            this.sourceObservation = Objects.requireNonNull(
                    sourceObservation, "sourceObservation");

            if (identity.requestGeneration != expectedRequestGeneration) {
                throw new IllegalArgumentException(
                        "identity/request generation mismatch");
            }
            if (sourceObservation.getObservationSeq() != expectedObservationSeq
                    || !identity.runId.equals(sourceObservation.getRunId())
                    || identity.floorId != sourceObservation.getFloorId()
                    || !identity.agentId.equals(sourceObservation.getAgentId())) {
                throw new IllegalArgumentException(
                        "source observation/request identity mismatch");
            }
        }
    }

    /**
     * 校验一条 submit_intent proposal。
     *
     * @param proposal           远程提交的意图数据
     * @param envelope           携带完整身份的信封
     * @param expectation        Java 侧期望的身份与请求字段
     * @param currentObservation 当前私有感知切片（用于语义时效检查）
     * @param committedWorld     已提交的权威世界（用于边界和可达性检查）
     * @return 校验结果，ACCEPTED 表示可以通过
     */
    public ValidationResult validate(
            AgentProtocol.SubmitIntentData proposal,
            AgentProtocol.Envelope envelope,
            RequestExpectation expectation,
            ReflexObservation currentObservation,
            TETile[][] committedWorld) {

        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(expectation, "expectation");
        Objects.requireNonNull(currentObservation, "currentObservation");
        Objects.requireNonNull(committedWorld, "committedWorld");

        // ── 第一层：身份校验 ──

        if (!AgentProtocol.ENVELOPE_VERSION.equals(envelope.schemaVersion)) {
            return ValidationResult.SCHEMA_MISMATCH;
        }
        if (envelope.type != AgentProtocol.MessageType.SUBMIT_INTENT
                || !(envelope.data instanceof AgentProtocol.SubmitIntentData)
                || !proposal.equals(envelope.data)) {
            return ValidationResult.SCHEMA_MISMATCH;
        }

        AgentProtocol.Identity expected = expectation.identity;
        if (!expected.runId.equals(envelope.runId)
                || expected.floorId != envelope.floorId
                || !expected.agentId.equals(envelope.agentId)) {
            return ValidationResult.IDENTITY_MISMATCH;
        }
        if (expected.sessionEpoch != envelope.sessionEpoch) {
            return ValidationResult.SESSION_EPOCH_MISMATCH;
        }
        if (expectation.expectedRequestGeneration
                != proposal.requestGeneration()) {
            return ValidationResult.REQUEST_GENERATION_MISMATCH;
        }
        if (!expectation.expectedDecisionId.equals(proposal.decisionId())) {
            return ValidationResult.DECISION_ID_MISMATCH;
        }
        if (expectation.expectedObservationSeq != proposal.observationSeq()) {
            return ValidationResult.OBSERVATION_SEQ_MISMATCH;
        }

        // ── 第二层：语义校验 ──

        AgentProtocol.IntentData intent = proposal.intent();
        if (!AgentProtocol.INTENT_VERSION.equals(intent.intentVersion())) {
            return ValidationResult.SCHEMA_MISMATCH;
        }

        AgentProtocol.Skill skill = intent.skill();
        if (skill == null) {
            return ValidationResult.UNKNOWN_SKILL;
        }
        if (!Double.isFinite(intent.confidence())
                || intent.confidence() < 0.0
                || intent.confidence() > 1.0) {
            return ValidationResult.INVALID_PARAMETERS;
        }
        if (intent.validForTicks() < 1 || intent.validForTicks() > 60) {
            return ValidationResult.INVALID_TTL;
        }
        AgentProtocol.InterruptPolicyData policy = intent.interruptPolicy();
        if (policy != null && !policy.respondToAdjacentThreat()) {
            return ValidationResult.INVALID_INTERRUPT_POLICY;
        }

        // targetPosition 校验（PATROL 可以为 null，其他必须有）
        AgentProtocol.PositionData target = null;
        if (intent.parameters().containsKey("targetPosition")) {
            Object value = intent.parameters().get("targetPosition");
            if (!(value instanceof AgentProtocol.PositionData position)) {
                return ValidationResult.INVALID_PARAMETERS;
            }
            target = position;
        } else if (skill != AgentProtocol.Skill.PATROL) {
            return ValidationResult.INVALID_PARAMETERS;
        }

        if (target != null) {
            // 边界检查
            if (target.x() < 0 || target.x() >= committedWorld.length
                    || target.y() < 0
                    || target.y() >= committedWorld[0].length) {
                return ValidationResult.TARGET_OUT_OF_BOUNDS;
            }

            // 知识边界：目标必须来自请求产生时的私有 observation。
            if (!isKnownTarget(
                    skill, target, expectation.sourceObservation)) {
                return ValidationResult.TARGET_NOT_KNOWN;
            }

            // Java 权威层检查当前世界中的合法性和可达性。
            TETile tile = committedWorld[target.x()][target.y()];
            if (tile == Tileset.WALL || tile == Tileset.NOTHING) {
                return ValidationResult.TARGET_UNREACHABLE;
            }
            Position self = currentObservation.getSelfPosition();
            Position goal = new Position(target.x(), target.y());
            if (!self.equals(goal)
                    && BFSPathfinder.findPath(
                    self, goal, committedWorld).isEmpty()) {
                return ValidationResult.TARGET_UNREACHABLE;
            }
        }

        // 语义时效检查
        switch (skill) {
            case CHASE:
            case ATTACK:
                // 追击/攻击意图的前提是玩家可见
                if (!currentObservation.canSeePlayer()) {
                    return ValidationResult.STALE_PRECONDITION;
                }
                // 目标仍须与当前可见玩家位置一致。
                VisibleEntity player = currentObservation.getVisiblePlayer();
                if (player != null && target != null) {
                    Position playerPos = player.getPosition();
                    if (playerPos.x != target.x() || playerPos.y != target.y()) {
                        return ValidationResult.STALE_PRECONDITION;
                    }
                }
                if (skill == AgentProtocol.Skill.ATTACK
                        && target != null
                        && manhattanDistance(
                        currentObservation.getSelfPosition(), target) != 1) {
                    return ValidationResult.STALE_PRECONDITION;
                }
                break;
            case PATROL:
                // 巡逻意图基于"无玩家可见"；若玩家现在可见，前提已失效
                if (currentObservation.canSeePlayer()) {
                    return ValidationResult.STALE_PRECONDITION;
                }
                break;
            case GUARD:
                // 守卫意图不因玩家可见/不可见而过时
                break;
            default:
                return ValidationResult.UNKNOWN_SKILL;
        }

        return ValidationResult.ACCEPTED;
    }

    private static boolean isKnownTarget(
            AgentProtocol.Skill skill,
            AgentProtocol.PositionData target,
            ObservationEnvelope sourceObservation) {
        if (skill == AgentProtocol.Skill.CHASE
                || skill == AgentProtocol.Skill.ATTACK) {
            VisibleEntity sourcePlayer = sourceObservation.getVisiblePlayer();
            if (sourcePlayer == null) {
                return false;
            }
            Position knownPlayer = sourcePlayer.getPosition();
            return knownPlayer.x == target.x()
                    && knownPlayer.y == target.y();
        }
        return sourceObservation.isWalkable(target.x(), target.y());
    }

    private static int manhattanDistance(
            Position self, AgentProtocol.PositionData target) {
        return Math.abs(self.x - target.x())
                + Math.abs(self.y - target.y());
    }

    /**
     * 将远程 Skill + parameters 映射为 Java 内部 StrategicIntent。
     * PATROL 未提供 target 时，从源 observation 中确定性选择可见可行走 tile。
     */
    public static StrategicIntent toStrategicIntent(
            AgentProtocol.IntentData intent,
            ObservationEnvelope sourceObservation) {
        AgentProtocol.Skill skill = intent.skill();
        Position target = null;
        if (intent.parameters().containsKey("targetPosition")) {
            AgentProtocol.PositionData pos =
                    (AgentProtocol.PositionData) intent.parameters().get("targetPosition");
            target = new Position(pos.x(), pos.y());
        } else if (skill == AgentProtocol.Skill.PATROL) {
            target = deterministicPatrolTarget(sourceObservation);
        }

        return switch (skill) {
            case PATROL -> new StrategicIntent(
                    StrategicIntent.Goal.PATROL,
                    StrategicIntent.Strategy.PATROL, target,
                    intent.confidence(), -1);
            case CHASE -> new StrategicIntent(
                    StrategicIntent.Goal.CHASE,
                    StrategicIntent.Strategy.CHASE, target,
                    intent.confidence(), -1);
            case ATTACK -> new StrategicIntent(
                    StrategicIntent.Goal.ATTACK_PLAYER,
                    StrategicIntent.Strategy.ATTACK, target,
                    intent.confidence(), -1);
            case GUARD -> new StrategicIntent(
                    StrategicIntent.Goal.GUARD,
                    StrategicIntent.Strategy.GUARD, target,
                    intent.confidence(), -1);
        };
    }

    private static Position deterministicPatrolTarget(
            ObservationEnvelope sourceObservation) {
        Position self = sourceObservation.getSelfPosition();
        for (VisibleTile tile : sourceObservation.getVisibleTiles()) {
            if (tile.isWalkable()
                    && (tile.getX() != self.x || tile.getY() != self.y)) {
                return new Position(tile.getX(), tile.getY());
            }
        }
        return self;
    }
}
