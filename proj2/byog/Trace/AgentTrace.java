package byog.Trace;

import byog.Action.Action;
import byog.Action.ActionOutcome;
import byog.AI.StrategicIntent;
import byog.lab5.Position;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 0 使用的最小结构化 trace（决策跟踪）协议。
 *
 * <p>这个类只负责描述和收集“敌人 AI 在一次更新中经历了什么”，不负责作出决策，
 * 也不允许改变 Brain、Planner、ActionQueue 或 Action 的执行结果。它是插在真实
 * {@link Enemy#updateAI} 调用链中的观测接缝（trace seam）。</p>
 *
 * <p>Phase 0 故意把所有类型收在一个容器类中，避免在实验基础设施尚未稳定时
 * 引入额外框架或过多文件。事件只使用场景内稳定的 actorKey 和逻辑时间；
 * JVM 自增的 Entity.id、墙钟时间、对象 hash 和自由文本日志均不能进入
 * canonical（规范化）比较结果。</p>
 */
public final class AgentTrace {

    /**
     * trace JSON 的结构版本。若将来改变字段或字段语义，应升级版本，
     * 不能让同一个版本号同时表示两种不兼容的事件协议。
     */
    public static final String PHASE0_SCHEMA_VERSION = "phase0.trace.v1";
    public static final String SCHEMA_VERSION = "phase1.trace.v1";
    public static final String AGENT_SCHEMA_VERSION = "agent.trace.v1";

    /**
     * 一次敌人决策的四个生命周期节点。
     * 顺序通常为 input -> intent -> attempted -> result；一个 intent 可能产生
     * 多次动作尝试，所以最后两类事件通过 actionOrdinal 成对关联。
     */
    public enum EventType {
        LEGACY_DECISION_INPUT,
        OBSERVATION_GENERATED,
        INTENT_SELECTED,
        ACTION_ATTEMPTED,
        ACTION_RESULT,
        AGENT_SESSION_STATE_CHANGED,
        AGENT_REQUEST_SENT,
        AGENT_SLOW,
        AGENT_HARD_TIMEOUT,
        AGENT_CANCEL_SENT,
        AGENT_CANCEL_ACKED,
        STALE_RESPONSE_DROPPED,
        INTENT_ADOPTED,
        LOCAL_BRAIN_TAKEOVER,
        REMOTE_AGENT_RESUMED,
        REFLEX_OVERRIDE_STARTED,
        REFLEX_OVERRIDE_ENDED,
        ACTION_FEEDBACK_ENQUEUED,
        OUTBOUND_MESSAGE_COALESCED,
        OUTBOUND_MESSAGE_DROPPED,
        PROTOCOL_ERROR
    }

    /**
     * trace 事件的场景级上下文。
     *
     * <p>Context 由 Harness 在每个 actor 更新前创建，给随后产生的所有事件补充
     * “哪个场景、哪个版本、哪个逻辑 tick、哪个参与者”四个关联维度。
     * 它不包含 Entity.id 和 wall-clock timestamp，因为这些值跨运行不稳定。</p>
     */
    public static final class Context {
        /** 本次事件使用的 trace schema。 */
        public final String schemaVersion;
        /** 固定场景名，例如 baseline-two-guards。 */
        public final String scenarioId;
        /** 场景定义版本；地图或参数发生语义变化时必须提升。 */
        public final int scenarioVersion;
        /** Harness 分配的逻辑时间，从 0 开始，不是现实时间。 */
        public final long logicalTick;
        /** 场景内稳定身份，例如 guard-a；不是正式可存档 agentId。 */
        public final String actorKey;

        public Context(String scenarioId, int scenarioVersion,
                       long logicalTick, String actorKey) {
            this(SCHEMA_VERSION, scenarioId, scenarioVersion, logicalTick, actorKey);
        }

        public Context(String schemaVersion, String scenarioId, int scenarioVersion,
                       long logicalTick, String actorKey) {
            this.schemaVersion = schemaVersion;
            this.scenarioId = scenarioId;
            this.scenarioVersion = scenarioVersion;
            this.logicalTick = logicalTick;
            this.actorKey = actorKey;
        }
    }

    /**
     * 不可变的 trace 事件。
     *
     * <p>字段按 canonical JSON 的输出顺序声明，方便审查协议。不同事件类型只填写
     * 与自己相关的字段，其余字段统一输出 JSON null，而不是省略。调用方不能直接
     * new Event，而必须使用下面四个 factory，以免构造出语义不完整的字段组合。</p>
     */
    public static final class TraceEvent {
        // ----- 所有事件共有的协议、场景、逻辑时间和身份 -----
        public final String schemaVersion;
        public final String scenarioId;
        public final int scenarioVersion;
        public final long logicalTick;
        /** 同 actor、同 tick 内的动作序号；只有 action 事件有值。 */
        public final Integer actionOrdinal;
        public final String actorKey;
        public final EventType eventType;

        // ----- Decision input / intent 相关字段 -----
        public final String inputKind;
        public final String goal;
        public final String strategy;
        public final Integer targetX;
        public final Integer targetY;

        // ----- Action attempt / result 相关字段 -----
        public final String actionType;
        /** 原样保存 ActionResult.name()，观测层不重新解释 DAMAGE 等语义。 */
        public final String rawActionResult;
        public final Integer beforeX;
        public final Integer beforeY;
        public final Integer afterX;
        public final Integer afterY;

        // ----- Observation 感知字段（仅 OBSERVATION_GENERATED 时有值）-----
        public final Boolean visiblePlayer;
        public final Integer visibleEntityCount;
        public final Integer fovTileCount;

        // ----- 异步 Agent 运行链路的稳定关联字段 -----
        public final String runId;
        public final Integer floorId;
        public final String agentId;
        public final Long sessionEpoch;
        public final Long observationSeq;
        public final String decisionId;
        public final Long requestGeneration;
        public final String messageType;
        public final String connectionState;
        public final String requestState;
        public final String executionState;
        public final String decisionSource;
        public final String validationResult;
        public final String overrideReason;
        public final Integer actionIndex;

        private TraceEvent(String schemaVersion, String scenarioId, int scenarioVersion,
                      long logicalTick, Integer actionOrdinal, String actorKey,
                      EventType eventType, String inputKind,
                      String goal, String strategy,
                      Integer targetX, Integer targetY,
                      String actionType, String rawActionResult,
                      Integer beforeX, Integer beforeY,
                      Integer afterX, Integer afterY,
                      Boolean visiblePlayer, Integer visibleEntityCount,
                      Integer fovTileCount) {
            this.schemaVersion = schemaVersion;
            this.scenarioId = scenarioId;
            this.scenarioVersion = scenarioVersion;
            this.logicalTick = logicalTick;
            this.actionOrdinal = actionOrdinal;
            this.actorKey = actorKey;
            this.eventType = eventType;
            this.inputKind = inputKind;
            this.goal = goal;
            this.strategy = strategy;
            this.targetX = targetX;
            this.targetY = targetY;
            this.actionType = actionType;
            this.rawActionResult = rawActionResult;
            this.beforeX = beforeX;
            this.beforeY = beforeY;
            this.afterX = afterX;
            this.afterY = afterY;
            this.visiblePlayer = visiblePlayer;
            this.visibleEntityCount = visibleEntityCount;
            this.fovTileCount = fovTileCount;
            this.runId = null;
            this.floorId = null;
            this.agentId = null;
            this.sessionEpoch = null;
            this.observationSeq = null;
            this.decisionId = null;
            this.requestGeneration = null;
            this.messageType = null;
            this.connectionState = null;
            this.requestState = null;
            this.executionState = null;
            this.decisionSource = null;
            this.validationResult = null;
            this.overrideReason = null;
            this.actionIndex = null;
        }

        private TraceEvent(AgentEventBuilder builder) {
            this.schemaVersion = AGENT_SCHEMA_VERSION;
            this.scenarioId = null;
            this.scenarioVersion = 0;
            this.logicalTick = builder.logicalTick;
            this.actionOrdinal = null;
            this.actorKey = builder.agentId;
            this.eventType = builder.eventType;
            this.inputKind = null;
            this.goal = null;
            this.strategy = null;
            this.targetX = null;
            this.targetY = null;
            this.actionType = builder.actionType;
            this.rawActionResult = builder.rawActionResult;
            this.beforeX = builder.beforeX;
            this.beforeY = builder.beforeY;
            this.afterX = builder.afterX;
            this.afterY = builder.afterY;
            this.visiblePlayer = builder.visiblePlayer;
            this.visibleEntityCount = builder.visibleEntityCount;
            this.fovTileCount = builder.fovTileCount;
            this.runId = builder.runId;
            this.floorId = builder.floorId;
            this.agentId = builder.agentId;
            this.sessionEpoch = builder.sessionEpoch;
            this.observationSeq = builder.observationSeq;
            this.decisionId = builder.decisionId;
            this.requestGeneration = builder.requestGeneration;
            this.messageType = builder.messageType;
            this.connectionState = builder.connectionState;
            this.requestState = builder.requestState;
            this.executionState = builder.executionState;
            this.decisionSource = builder.decisionSource;
            this.validationResult = builder.validationResult;
            this.overrideReason = builder.overrideReason;
            this.actionIndex = builder.actionIndex;
        }

        /**
         * 记录当前 EnemyBrain 仍然收到完整世界快照。
         * 事件名和 inputKind 特意带有 legacy，防止后续把它误称为已实现知识边界的
         * 私有 Observation。
         */
        public static TraceEvent legacyDecisionInput(Context context) {
            return new TraceEvent(context.schemaVersion, context.scenarioId,
                    context.scenarioVersion, context.logicalTick,
                    null, context.actorKey,
                    EventType.LEGACY_DECISION_INPUT,
                    "legacy-full-world-snapshot",
                    null, null, null, null,
                    null, null, null, null, null, null,
                    null, null, null);
        }

        /**
         * 记录私有感知结果。仅 Phase 1+ 的私有感知路径使用。
         */
        public static TraceEvent observationGenerated(Context context,
                boolean visiblePlayer, int visibleEntityCount, int fovTileCount) {
            return new TraceEvent(context.schemaVersion, context.scenarioId,
                    context.scenarioVersion, context.logicalTick,
                    null, context.actorKey,
                    EventType.OBSERVATION_GENERATED,
                    "private-perception-v1",
                    null, null, null, null,
                    null, null, null, null, null, null,
                    visiblePlayer, visibleEntityCount, fovTileCount);
        }

        /**
         * 记录 Brain 返回的高层意图。目标坐标允许为空，例如某些未来策略可能没有
         * 明确位置；goal 和 strategy 保留枚举名称，保证输出稳定且便于比较。
         */
        public static TraceEvent intentSelected(Context context, StrategicIntent intent) {
            Integer tx = null;
            Integer ty = null;
            Position tp = intent.getTargetPosition();
            if (tp != null) {
                tx = tp.x;
                ty = tp.y;
            }
            return new TraceEvent(context.schemaVersion, context.scenarioId,
                    context.scenarioVersion, context.logicalTick,
                    null, context.actorKey,
                    EventType.INTENT_SELECTED,
                    null,
                    intent.getGoal().name(),
                    intent.getStrategy().name(),
                    tx, ty,
                    null, null, null, null, null, null,
                    null, null, null);
        }
        /**
         * 在 action.execute 之前记录一次真实动作尝试。
         * before 坐标在执行前立即读取，用于和对应 ACTION_RESULT 的 after 坐标比较。
         */
        public static TraceEvent actionAttempted(Context context, int actionOrdinal,
                                            Action action, Position before) {
            return new TraceEvent(context.schemaVersion, context.scenarioId,
                    context.scenarioVersion, context.logicalTick,
                    actionOrdinal, context.actorKey,
                    EventType.ACTION_ATTEMPTED,
                    null, null, null, null, null,
                    action.getClass().getSimpleName(),
                    null,
                    before.x, before.y,
                    null, null,
                    null, null, null);
        }

        /**
         * 在 action.execute 返回后记录原始结果和执行后位置。
         * actionOrdinal 必须与对应 ACTION_ATTEMPTED 相同，构成生命周期关联键的一部分。
         */
        public static TraceEvent actionResult(Context context, int actionOrdinal,
                                         Action action, Action.ActionResult result,
                                         Position before, Position after) {
            return new TraceEvent(context.schemaVersion, context.scenarioId,
                    context.scenarioVersion, context.logicalTick,
                    actionOrdinal, context.actorKey,
                    EventType.ACTION_RESULT,
                    null, null, null, null, null,
                    action.getClass().getSimpleName(),
                    result.name(),
                    before.x, before.y,
                    after.x, after.y,
                    null, null, null);
        }
    }

    /**
     * 构造一条异步 Agent canonical 事件，并仅接受确定性字段。
     */
    public static AgentEventBuilder agentEvent(
            EventType eventType, String runId, int floorId,
            String agentId, long logicalTick) {
        return new AgentEventBuilder(
                eventType, runId, floorId, agentId, logicalTick);
    }

    /**
     * 为不同事件类型逐步补充关联字段，避免庞大的位置参数构造器。
     */
    public static final class AgentEventBuilder {
        private final EventType eventType;
        private final String runId;
        private final int floorId;
        private final String agentId;
        private final long logicalTick;
        private Long sessionEpoch;
        private Long observationSeq;
        private String decisionId;
        private Long requestGeneration;
        private String messageType;
        private String connectionState;
        private String requestState;
        private String executionState;
        private String decisionSource;
        private String validationResult;
        private String overrideReason;
        private Integer actionIndex;
        private String actionType;
        private String rawActionResult;
        private Integer beforeX;
        private Integer beforeY;
        private Integer afterX;
        private Integer afterY;
        private Boolean visiblePlayer;
        private Integer visibleEntityCount;
        private Integer fovTileCount;

        private AgentEventBuilder(
                EventType eventType, String runId, int floorId,
                String agentId, long logicalTick) {
            if (eventType == null) {
                throw new IllegalArgumentException("eventType must not be null");
            }
            if (runId == null || runId.trim().isEmpty()
                    || agentId == null || agentId.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "runId and agentId must not be blank");
            }
            if (floorId < 1 || logicalTick < 0) {
                throw new IllegalArgumentException(
                        "floorId must be positive and logicalTick non-negative");
            }
            this.eventType = eventType;
            this.runId = runId;
            this.floorId = floorId;
            this.agentId = agentId;
            this.logicalTick = logicalTick;
        }

        public AgentEventBuilder session(
                long epoch, long generation,
                String connection, String request) {
            sessionEpoch = epoch;
            requestGeneration = generation;
            connectionState = connection;
            requestState = request;
            return this;
        }

        public AgentEventBuilder observation(
                long sequence, boolean playerVisible,
                int entityCount, int tileCount) {
            observationSeq = sequence;
            visiblePlayer = playerVisible;
            visibleEntityCount = entityCount;
            fovTileCount = tileCount;
            return this;
        }

        public AgentEventBuilder observationSequence(long sequence) {
            observationSeq = sequence;
            return this;
        }

        public AgentEventBuilder decision(String id, String source) {
            decisionId = id;
            decisionSource = source;
            return this;
        }

        public AgentEventBuilder message(String type) {
            messageType = type;
            return this;
        }

        public AgentEventBuilder execution(String state) {
            executionState = state;
            return this;
        }

        public AgentEventBuilder validation(String result) {
            validationResult = result;
            return this;
        }

        public AgentEventBuilder override(String reason) {
            overrideReason = reason;
            return this;
        }

        public AgentEventBuilder action(
                int index, String type, String result,
                Position before, Position after) {
            actionIndex = index;
            actionType = type;
            rawActionResult = result;
            if (before != null) {
                beforeX = before.x;
                beforeY = before.y;
            }
            if (after != null) {
                afterX = after.x;
                afterY = after.y;
            }
            return this;
        }

        public AgentEventBuilder action(ActionOutcome outcome) {
            return decision(
                    outcome.getDecisionId(),
                    outcome.getDecisionSource().name())
                    .override(outcome.getOverrideReason())
                    .action(
                            outcome.getActionIndex(),
                            outcome.getActionType(),
                            outcome.getResult().name(),
                            outcome.getBeforePosition(),
                            outcome.getAfterPosition());
        }

        public TraceEvent build() {
            return new TraceEvent(this);
        }
    }

    /**
     * trace 事件消费者。Enemy 只依赖这个最小接口，不需要知道事件最终存入内存、
     * 文件还是其他系统，从而保持事件生产与存储方式分离。
     */
    public interface Sink {
        void record(TraceEvent event);
    }

    /**
     * 空 Sink。旧游戏入口使用它保持源代码兼容，事件到达后不分配存储空间。
     * NO_OP 路径理论上必须与加入 trace 前的行为完全等价。
     */
    public static final Sink NO_OP = event -> { };

    /**
     * 按到达顺序把事件保存在内存中的 Sink。
     * Phase 0 的 Harness 使用它生成 trace；List 的 append 顺序同时定义 canonical
     * sequence，因此这里不能改用无顺序集合。
     */
    public static final class InMemorySink implements Sink {
        private final List<TraceEvent> events = new ArrayList<>();

        /** 追加事件；索引即该事件序列化时的全局 sequence。 */
        @Override
        public void record(TraceEvent event) {
            events.add(event);
        }

        /**
         * 返回防御性副本，避免测试或调用方通过 clear/remove 修改 Sink 内部证据。
         * Event 本身不可变，所以不需要逐个深拷贝。
         */
        public List<TraceEvent> events() {
            return new ArrayList<>(events);
        }

        /**
         * 按固定字段顺序生成 canonical JSON。
         *
         * <p>这里手工输出而不依赖 Map 序列化，是为了锁定字段顺序、null 写法和换行。
         * sequence 由事件在 List 中的下标产生，不存入 Event 构造参数。输出不包含
         * Entity.id、时间戳、耗时、对象 hash 或日志文本等非确定字段。</p>
         */
        public String toCanonicalJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("[\n");
            for (int i = 0; i < events.size(); i++) {
                TraceEvent e = events.get(i);
                sb.append("  {");
                if (AGENT_SCHEMA_VERSION.equals(e.schemaVersion)) {
                    appendAgentEvent(sb, e, i);
                    sb.append("}");
                    if (i < events.size() - 1) {
                        sb.append(",");
                    }
                    sb.append("\n");
                    continue;
                }
                appendField(sb, "schemaVersion", e.schemaVersion, false);
                appendField(sb, "scenarioId", e.scenarioId, false);
                appendField(sb, "scenarioVersion", e.scenarioVersion, false);
                appendField(sb, "logicalTick", e.logicalTick, false);
                appendField(sb, "sequence", i, false);
                appendNullableInt(sb, "actionOrdinal", e.actionOrdinal, false);
                appendField(sb, "actorKey", e.actorKey, false);
                appendField(sb, "eventType", e.eventType.name(), false);
                appendNullableStr(sb, "inputKind", e.inputKind, false);
                appendNullableStr(sb, "goal", e.goal, false);
                appendNullableStr(sb, "strategy", e.strategy, false);
                appendNullableInt(sb, "targetX", e.targetX, false);
                appendNullableInt(sb, "targetY", e.targetY, false);
                appendNullableStr(sb, "actionType", e.actionType, false);
                appendNullableStr(sb, "rawActionResult", e.rawActionResult, false);
                appendNullableInt(sb, "beforeX", e.beforeX, false);
                appendNullableInt(sb, "beforeY", e.beforeY, false);
                appendNullableInt(sb, "afterX", e.afterX, false);
                boolean phase0Schema = PHASE0_SCHEMA_VERSION.equals(e.schemaVersion);
                appendNullableInt(sb, "afterY", e.afterY, phase0Schema);
                if (!phase0Schema) {
                    appendNullableBoolean(sb, "visiblePlayer", e.visiblePlayer, false);
                    appendNullableInt(sb, "visibleEntityCount", e.visibleEntityCount, false);
                    appendNullableInt(sb, "fovTileCount", e.fovTileCount, true);
                }
                sb.append("}");
                if (i < events.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("]");
            return sb.toString();
        }

        private void appendAgentEvent(
                StringBuilder sb, TraceEvent e, int sequence) {
            appendField(sb, "schemaVersion", e.schemaVersion, false);
            appendField(sb, "runId", e.runId, false);
            appendNullableInt(sb, "floorId", e.floorId, false);
            appendField(sb, "agentId", e.agentId, false);
            appendField(sb, "logicalTick", e.logicalTick, false);
            appendField(sb, "sequence", sequence, false);
            appendField(sb, "eventType", e.eventType.name(), false);
            appendNullableLong(sb, "sessionEpoch", e.sessionEpoch, false);
            appendNullableLong(sb, "observationSeq", e.observationSeq, false);
            appendNullableStr(sb, "decisionId", e.decisionId, false);
            appendNullableLong(
                    sb, "requestGeneration", e.requestGeneration, false);
            appendNullableStr(sb, "messageType", e.messageType, false);
            appendNullableStr(
                    sb, "connectionState", e.connectionState, false);
            appendNullableStr(sb, "requestState", e.requestState, false);
            appendNullableStr(
                    sb, "executionState", e.executionState, false);
            appendNullableStr(
                    sb, "decisionSource", e.decisionSource, false);
            appendNullableStr(
                    sb, "validationResult", e.validationResult, false);
            appendNullableStr(sb, "overrideReason", e.overrideReason, false);
            appendNullableInt(sb, "actionIndex", e.actionIndex, false);
            appendNullableStr(sb, "actionType", e.actionType, false);
            appendNullableStr(
                    sb, "rawActionResult", e.rawActionResult, false);
            appendPosition(sb, "beforePosition", e.beforeX, e.beforeY, false);
            appendPosition(sb, "afterPosition", e.afterX, e.afterY, false);
            appendNullableBoolean(
                    sb, "visiblePlayer", e.visiblePlayer, false);
            appendNullableInt(
                    sb, "visibleEntityCount", e.visibleEntityCount, false);
            appendNullableInt(sb, "fovTileCount", e.fovTileCount, true);
        }

        private void appendField(StringBuilder sb, String key, String value, boolean last) {
            sb.append("\"").append(key).append("\":\"")
                    .append(escapeJson(value)).append("\"");
            if (!last) {
                sb.append(",");
            }
        }

        private void appendField(StringBuilder sb, String key, long value, boolean last) {
            sb.append("\"").append(key).append("\":").append(value);
            if (!last) {
                sb.append(",");
            }
        }

        private void appendNullableStr(StringBuilder sb, String key, String value, boolean last) {
            sb.append("\"").append(key).append("\":");
            if (value == null) {
                sb.append("null");
            } else {
                sb.append("\"").append(escapeJson(value)).append("\"");
            }
            if (!last) {
                sb.append(",");
            }
        }

        private void appendNullableInt(StringBuilder sb, String key, Integer value, boolean last) {
            sb.append("\"").append(key).append("\":");
            if (value == null) {
                sb.append("null");
            } else {
                sb.append(value);
            }
            if (!last) {
                sb.append(",");
            }
        }

        private void appendNullableLong(
                StringBuilder sb, String key, Long value, boolean last) {
            sb.append("\"").append(key).append("\":");
            if (value == null) {
                sb.append("null");
            } else {
                sb.append(value);
            }
            if (!last) {
                sb.append(",");
            }
        }

        private void appendPosition(
                StringBuilder sb, String key,
                Integer x, Integer y, boolean last) {
            sb.append("\"").append(key).append("\":");
            if (x == null || y == null) {
                sb.append("null");
            } else {
                sb.append("{\"x\":").append(x)
                        .append(",\"y\":").append(y).append("}");
            }
            if (!last) {
                sb.append(",");
            }
        }

        private void appendNullableBoolean(StringBuilder sb, String key,
                                            Boolean value, boolean last) {
            sb.append("\"").append(key).append("\":");
            if (value == null) {
                sb.append("null");
            } else {
                sb.append(value);
            }
            if (!last) {
                sb.append(",");
            }
        }

        /**
         * 对 JSON 字符串中最常见的特殊字符做稳定转义。
         * 当前协议字段来自受控枚举和场景常量，但仍在协议边界统一转义，避免未来
         * actorKey 或 scenarioId 中出现引号、反斜杠或换行时生成非法 JSON。
         */
        private static String escapeJson(String s) {
            if (s == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"':  sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:   sb.append(c);
                }
            }
            return sb.toString();
        }
    }
}
