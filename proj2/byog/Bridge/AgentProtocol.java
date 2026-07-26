package byog.Bridge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 2 消息协议的纯数据容器：信封、消息 data record、身份元组、枚举。
 * 不含 socket/IO/游戏逻辑，只承载可序列化的数据结构。
 */
public final class AgentProtocol {

    /** 通用信封版本 */
    public static final String ENVELOPE_VERSION = "phase2.session.v1";
    /** observation payload 版本 */
    public static final String OBSERVATION_VERSION = "private-observation.v1";
    /** intent payload 版本 */
    public static final String INTENT_VERSION = "strategic-intent.v1";

    private AgentProtocol() {
    }

    /** 8 种消息类型，双向 */
    public enum MessageType {
        OBSERVATION,
        ACTION_FEEDBACK,
        WORLD_EVENT,
        HEARTBEAT,
        CANCEL_REQUEST,
        SUBMIT_INTENT,
        CANCEL_ACK,
        PROTOCOL_ERROR
    }

    /** Phase 2 允许的远程技能白名单 */
    public enum Skill {
        PATROL,
        CHASE,
        ATTACK,
        GUARD
    }

    /** 决策来源 */
    public enum DecisionSource {
        REMOTE_AGENT,
        LOCAL_FALLBACK
    }

    /** WorldEvent 白名单，只有这 5 种可以通过 eventType 校验 */
    public enum WorldEventType {
        PLAN_BLOCKED,
        PLAN_EXHAUSTED,
        PLAYER_SPOTTED,
        REFLEX_OVERRIDE_STARTED,
        REFLEX_OVERRIDE_ENDED
    }

    /** 完整身份元组，后续 AgentSession 用这些字段校验响应是否有效 */
    public static final class Identity {
        public final String runId;
        public final int floorId;
        public final String agentId;
        public final long sessionEpoch;
        public final long requestGeneration;

        public Identity(String runId, int floorId, String agentId,
                        long sessionEpoch, long requestGeneration) {
            this.runId = runId;
            this.floorId = floorId;
            this.agentId = agentId;
            this.sessionEpoch = sessionEpoch;
            this.requestGeneration = requestGeneration;
        }
    }

    /** 通用信封 */
    public static final class Envelope {
        public final String schemaVersion;
        public final String messageId;
        public final long messageSeq;
        public final String runId;
        public final int floorId;
        public final String agentId;
        public final long sessionEpoch;
        public final long logicalTick;
        public final MessageType type;
        public final MessageData data;

        public Envelope(String schemaVersion, String messageId, long messageSeq,
                        String runId, int floorId, String agentId,
                        long sessionEpoch, long logicalTick,
                        MessageType type, MessageData data) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(data, "data");
            if (!payloadMatches(type, data)) {
                throw new IllegalArgumentException(
                        "message type " + type + " does not match payload "
                                + data.getClass().getSimpleName());
            }
            this.schemaVersion = schemaVersion;
            this.messageId = messageId;
            this.messageSeq = messageSeq;
            this.runId = runId;
            this.floorId = floorId;
            this.agentId = agentId;
            this.sessionEpoch = sessionEpoch;
            this.logicalTick = logicalTick;
            this.type = type;
            this.data = data;
        }

        private static boolean payloadMatches(MessageType type, MessageData data) {
            return switch (type) {
                case OBSERVATION -> data instanceof ObservationData;
                case ACTION_FEEDBACK -> data instanceof ActionFeedbackData;
                case WORLD_EVENT -> data instanceof WorldEventData;
                case HEARTBEAT -> data instanceof HeartbeatData;
                case CANCEL_REQUEST -> data instanceof CancelRequestData;
                case SUBMIT_INTENT -> data instanceof SubmitIntentData;
                case CANCEL_ACK -> data instanceof CancelAckData;
                case PROTOCOL_ERROR -> data instanceof ProtocolErrorData;
            };
        }
    }

    /** tagged union：data 的具体类型由 type 决定 */
    public sealed interface MessageData
            permits ObservationData, SubmitIntentData, ActionFeedbackData,
                    CancelRequestData, CancelAckData, WorldEventData,
                    HeartbeatData, ProtocolErrorData {
    }

    /** observation 消息 data */
    public record ObservationData(
            String observationVersion,
            String decisionId,
            long observationSeq,
            long requestGeneration,
            long observedAtTurn,
            SelfData self,
            List<VisibleTileData> visibleTiles,
            List<VisibleEntityData> visibleEntities,
            List<HeardEventData> heardEvents,
            List<WorldEventData> pendingEvents,
            CapabilitiesData capabilities
    ) implements MessageData {
        public ObservationData {
            Objects.requireNonNull(observationVersion, "observationVersion");
            Objects.requireNonNull(decisionId, "decisionId");
            Objects.requireNonNull(self, "self");
            visibleTiles = List.copyOf(visibleTiles);
            visibleEntities = List.copyOf(visibleEntities);
            heardEvents = List.copyOf(heardEvents);
            pendingEvents = List.copyOf(pendingEvents);
            Objects.requireNonNull(capabilities, "capabilities");
        }
    }

    /** submit_intent 消息 data */
    public record SubmitIntentData(
            String decisionId,
            long observationSeq,
            long requestGeneration,
            IntentData intent
    ) implements MessageData {
        public SubmitIntentData {
            Objects.requireNonNull(decisionId, "decisionId");
            Objects.requireNonNull(intent, "intent");
        }
    }

    /** 意图数据 */
    public record IntentData(
            String intentVersion,
            Skill skill,
            Map<String, Object> parameters,
            double confidence,
            int validForTicks,
            InterruptPolicyData interruptPolicy
    ) {
        public IntentData {
            Objects.requireNonNull(intentVersion, "intentVersion");
            Objects.requireNonNull(skill, "skill");
            Objects.requireNonNull(parameters, "parameters");
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                Objects.requireNonNull(entry.getKey(), "parameter key");
                Objects.requireNonNull(
                        entry.getValue(), "parameter value for " + entry.getKey());
                if (!"targetPosition".equals(entry.getKey())) {
                    throw new IllegalArgumentException(
                            "unknown parameter for " + skill + ": "
                                    + entry.getKey());
                }
                if (!(entry.getValue() instanceof PositionData)) {
                    throw new IllegalArgumentException(
                            "targetPosition must be PositionData");
                }
            }
            if (skill != Skill.PATROL
                    && !parameters.containsKey("targetPosition")) {
                throw new IllegalArgumentException(
                        "targetPosition is required for " + skill);
            }
            parameters = Collections.unmodifiableMap(
                    new LinkedHashMap<>(parameters));
            if (!Double.isFinite(confidence)) {
                throw new IllegalArgumentException("confidence must be finite");
            }
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException(
                        "confidence must be between 0.0 and 1.0");
            }
            if (validForTicks < 1 || validForTicks > 60) {
                throw new IllegalArgumentException(
                        "validForTicks must be between 1 and 60");
            }
        }
    }

    /** 中断策略，缺失时由 DecisionValidator 按 skill 填充安全默认值 */
    public record InterruptPolicyData(
            boolean engageVisiblePlayer,
            boolean respondToAdjacentThreat,
            boolean allowLocalReroute
    ) {
    }

    /** action_feedback 消息 data */
    public record ActionFeedbackData(
            String decisionId,
            int actionIndex,
            String actionType,
            String result,
            PositionData beforePosition,
            PositionData afterPosition,
            int selfHp,
            DecisionSource decisionSource,
            String overrideReason
    ) implements MessageData {
        public ActionFeedbackData {
            Objects.requireNonNull(decisionId, "decisionId");
            Objects.requireNonNull(actionType, "actionType");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(beforePosition, "beforePosition");
            Objects.requireNonNull(afterPosition, "afterPosition");
            Objects.requireNonNull(decisionSource, "decisionSource");
        }
    }

    /** cancel_request 消息 data */
    public record CancelRequestData(
            String decisionId,
            long requestGeneration,
            String reason
    ) implements MessageData {
        public CancelRequestData {
            Objects.requireNonNull(decisionId, "decisionId");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** cancel_ack 消息 data */
    public record CancelAckData(
            String decisionId,
            long requestGeneration
    ) implements MessageData {
        public CancelAckData {
            Objects.requireNonNull(decisionId, "decisionId");
        }
    }

    /** world_event 消息 data */
    public record WorldEventData(
            String eventType,
            long logicalTick,
            PositionData relatedPosition,
            String relatedEntityId
    ) implements MessageData {
        public WorldEventData {
            Objects.requireNonNull(eventType, "eventType");
            try {
                WorldEventType.valueOf(eventType);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "unknown world event type: " + eventType, e);
            }
        }
    }

    /** heartbeat 消息 data */
    public record HeartbeatData(long logicalTick) implements MessageData {
    }

    /** protocol_error 消息 data */
    public record ProtocolErrorData(
            String reason,
            String offendingType
    ) implements MessageData {
        public ProtocolErrorData {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(offendingType, "offendingType");
        }
    }

    // ── 嵌套 record ──

    /** 位置坐标 */
    public record PositionData(int x, int y) {
    }

    /** observation 中的自身状态 */
    public record SelfData(PositionData position, int hp) {
        public SelfData {
            Objects.requireNonNull(position, "position");
        }
    }

    /** 可见 tile 数据 */
    public record VisibleTileData(int x, int y, String type, boolean walkable) {
        public VisibleTileData {
            Objects.requireNonNull(type, "type");
        }
    }

    /** 可见实体数据 */
    public record VisibleEntityData(
            String type,
            PositionData position,
            int visibleHp,
            String agentId
    ) {
        public VisibleEntityData {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(position, "position");
        }
    }

    /** 听觉事件数据 */
    public record HeardEventData(
            String soundType,
            PositionData sourcePosition,
            long turn
    ) {
        public HeardEventData {
            Objects.requireNonNull(soundType, "soundType");
            Objects.requireNonNull(sourcePosition, "sourcePosition");
        }
    }

    /** 能力描述数据 */
    public record CapabilitiesData(
            List<String> supportedSkills,
            int sightRange,
            int attackDamage,
            int moveInterval
    ) {
        public CapabilitiesData {
            supportedSkills = List.copyOf(supportedSkills);
            for (String supportedSkill : supportedSkills) {
                try {
                    Skill.valueOf(supportedSkill);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "unsupported capability skill: " + supportedSkill, e);
                }
            }
        }
    }
}
