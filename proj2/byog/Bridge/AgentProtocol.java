package byog.Bridge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 会话消息协议的纯数据容器：信封、消息 data record、身份元组、枚举。
 * 不含 socket/IO/游戏逻辑，只承载可序列化的数据结构。
 */
public final class AgentProtocol {

    /** 通用信封版本 */
    public static final String ENVELOPE_VERSION = "agent-session.v1";
    /** observation payload 版本 */
    public static final String OBSERVATION_VERSION = "private-observation.v3";
    /** intent payload 版本 */
    public static final String INTENT_VERSION = "strategic-intent.v2";
    public static final String FEEDBACK_VERSION = "action-feedback.v2";
    public static final String EVENT_VERSION = "agent-event.v1";

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

    /** Agent 会话允许的远程技能白名单 */
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

    /** WorldEvent 白名单。 */
    public enum WorldEventType {
        PLAYER_SPOTTED,
        SOUND_HEARD,
        MESSAGE_RECEIVED,
        STEP_SUCCEEDED,
        STEP_FAILED,
        PLAN_COMPLETED,
        PLAN_CANCELLED,
        REFLEX_OVERRIDE_STARTED,
        REFLEX_OVERRIDE_ENDED
    }

    public enum StepStatus {
        UNTRACKED,
        ACTIVE,
        SUCCEEDED,
        FAILED,
        PAUSED,
        CANCELLED
    }

    public enum PlanStatus {
        UNTRACKED,
        ACTIVE,
        PAUSED,
        COMPLETED,
        REPLAN_REQUIRED,
        CANCELLED
    }

    public enum OutcomeReason {
        NONE,
        ACTION_COMMITTED,
        DAMAGE_COMMITTED,
        OCCUPIED_OR_TERRAIN_BLOCKED,
        LOCAL_REROUTE,
        REPEATED_BLOCKED,
        TARGET_LOST,
        PRECONDITION_CHANGED,
        COMMITMENT_COMPLETED,
        REFLEX_OVERRIDE_STARTED,
        REFLEX_OVERRIDE_ENDED,
        LEASE_INVALIDATED,
        PLAN_COMPLETED,
        CANCELLED
    }

    /** 完整身份元组，后续 AgentSession 用这些字段校验响应是否有效 */
    public static final class Identity {
        public final String worldId;
        public final String runId;
        public final int floorId;
        public final String agentId;
        public final long sessionEpoch;
        public final long requestGeneration;

        public Identity(String worldId, String runId, int floorId,
                        String agentId, long sessionEpoch,
                        long requestGeneration) {
            this.worldId = requireNonBlank(worldId, "worldId");
            this.runId = requireNonBlank(runId, "runId");
            if (floorId < 1) {
                throw new IllegalArgumentException("floorId must be >= 1");
            }
            this.floorId = floorId;
            this.agentId = requireNonBlank(agentId, "agentId");
            if (sessionEpoch < 0 || requestGeneration < 0) {
                throw new IllegalArgumentException(
                        "session counters must be non-negative");
            }
            this.sessionEpoch = sessionEpoch;
            this.requestGeneration = requestGeneration;
        }
    }

    /** 通用信封 */
    public static final class Envelope {
        public final String schemaVersion;
        public final String messageId;
        public final long messageSeq;
        public final String worldId;
        public final String runId;
        public final int floorId;
        public final String agentId;
        public final long sessionEpoch;
        public final long logicalTick;
        public final MessageType type;
        public final MessageData data;

        public Envelope(String schemaVersion, String messageId, long messageSeq,
                        String worldId, String runId, int floorId,
                        String agentId, long sessionEpoch, long logicalTick,
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
            this.worldId = worldId;
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
            String visionMode,
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
            Objects.requireNonNull(visionMode, "visionMode");
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
            String skill,
            Map<String, Object> parameters,
            double confidence,
            int validForTicks,
            InterruptPolicyData interruptPolicy,
            PlanMetadataData planMetadata
    ) {
        public IntentData {
            Objects.requireNonNull(intentVersion, "intentVersion");
            Objects.requireNonNull(skill, "skill");
            Objects.requireNonNull(parameters, "parameters");
            if (!skill.matches("[A-Z][A-Z0-9_]{0,63}")) {
                throw new IllegalArgumentException("invalid skill id: " + skill);
            }
            parameters = immutableJsonObject(parameters);
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
            Objects.requireNonNull(interruptPolicy, "interruptPolicy");
            Objects.requireNonNull(planMetadata, "planMetadata");
        }
    }

    /** Runtime-owned correlation metadata; it is not a multi-step executor. */
    public record PlanMetadataData(
            String planId, String stepId, int revision) {
        public PlanMetadataData {
            Objects.requireNonNull(planId, "planId");
            Objects.requireNonNull(stepId, "stepId");
            if (planId.isEmpty() || stepId.isEmpty()) {
                throw new IllegalArgumentException(
                        "planId and stepId must not be empty");
            }
            if (revision < 0) {
                throw new IllegalArgumentException("revision must be >= 0");
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
            String feedbackVersion,
            String feedbackId,
            String decisionId,
            String planId,
            String stepId,
            Integer planRevision,
            int actionIndex,
            String actionType,
            String result,
            String reasonCode,
            String stepStatus,
            String planStatus,
            PositionData beforePosition,
            PositionData afterPosition,
            int selfHp,
            DecisionSource decisionSource,
            String overrideReason
    ) implements MessageData {
        public ActionFeedbackData {
            requireNonBlank(feedbackVersion, "feedbackVersion");
            requireNonBlank(feedbackId, "feedbackId");
            requireNonBlank(decisionId, "decisionId");
            Objects.requireNonNull(actionType, "actionType");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(stepStatus, "stepStatus");
            Objects.requireNonNull(planStatus, "planStatus");
            OutcomeReason.valueOf(reasonCode);
            StepStatus.valueOf(stepStatus);
            PlanStatus.valueOf(planStatus);
            Objects.requireNonNull(beforePosition, "beforePosition");
            Objects.requireNonNull(afterPosition, "afterPosition");
            Objects.requireNonNull(decisionSource, "decisionSource");
            boolean hasPlan = planId != null || stepId != null
                    || planRevision != null;
            if (hasPlan && (planId == null || stepId == null
                    || planRevision == null || planRevision < 0)) {
                throw new IllegalArgumentException(
                        "plan metadata must be entirely present or null");
            }
            if (decisionSource == DecisionSource.REMOTE_AGENT && !hasPlan) {
                throw new IllegalArgumentException(
                        "remote feedback requires plan metadata");
            }
            if (decisionSource == DecisionSource.LOCAL_FALLBACK && hasPlan) {
                throw new IllegalArgumentException(
                        "local feedback cannot carry plan metadata");
            }
        }

        public ActionFeedbackData(
                String decisionId, int actionIndex, String actionType,
                String result, PositionData beforePosition,
                PositionData afterPosition, int selfHp,
                DecisionSource decisionSource, String overrideReason) {
            this(FEEDBACK_VERSION,
                    "feedback-" + decisionId + "-" + actionIndex,
                    decisionId,
                    decisionSource == DecisionSource.REMOTE_AGENT
                            ? decisionId + ":plan" : null,
                    decisionSource == DecisionSource.REMOTE_AGENT
                            ? "step-0" : null,
                    decisionSource == DecisionSource.REMOTE_AGENT
                            ? 0 : null,
                    actionIndex,
                    actionType, result, OutcomeReason.ACTION_COMMITTED.name(),
                    decisionSource == DecisionSource.REMOTE_AGENT
                            ? StepStatus.ACTIVE.name()
                            : StepStatus.UNTRACKED.name(),
                    decisionSource == DecisionSource.REMOTE_AGENT
                            ? PlanStatus.ACTIVE.name()
                            : PlanStatus.UNTRACKED.name(),
                    beforePosition, afterPosition, selfHp,
                    decisionSource, overrideReason);
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
            String eventVersion,
            String eventId,
            String eventType,
            long logicalTick,
            PositionData relatedPosition,
            String relatedEntityId,
            String decisionId,
            String planId,
            String stepId,
            String reasonCode
    ) implements MessageData {
        public WorldEventData {
            requireNonBlank(eventVersion, "eventVersion");
            requireNonBlank(eventId, "eventId");
            Objects.requireNonNull(eventType, "eventType");
            try {
                WorldEventType.valueOf(eventType);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "unknown world event type: " + eventType, e);
            }
            if (reasonCode != null) {
                OutcomeReason.valueOf(reasonCode);
            }
        }

        public WorldEventData(
                String eventType, long logicalTick,
                PositionData relatedPosition, String relatedEntityId) {
            this(EVENT_VERSION,
                    "event-" + logicalTick + "-" + eventType,
                    eventType, logicalTick, relatedPosition,
                    relatedEntityId, null, null, null, null);
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
    public record SelfData(
            PositionData position, int hp, int maxHp, String facing) {
        public SelfData {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(facing, "facing");
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

    private static Map<String, Object> immutableJsonObject(
            Map<String, Object> source) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "parameter key");
            copy.put(key, immutableJsonValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableJsonValue(Object value) {
        if (value == null || value instanceof String
                || value instanceof Boolean || value instanceof Integer
                || value instanceof Long || value instanceof Double) {
            if (value instanceof Double number && !Double.isFinite(number)) {
                throw new IllegalArgumentException("JSON number must be finite");
            }
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON object key must be string");
                }
                typed.put(key, immutableJsonValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(typed);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AgentProtocol::immutableJsonValue).toList();
        }
        throw new IllegalArgumentException(
                "unsupported JSON parameter type: " + value.getClass());
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
