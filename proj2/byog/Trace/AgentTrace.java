package byog.Trace;

import byog.Action.Action;
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
        ACTION_RESULT
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
