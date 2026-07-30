package byog.Entity;

import byog.Action.Action;
import byog.Action.ActionOutcome;
import byog.Action.ActionQueue;
import byog.AI.AiTickContext;
import byog.AI.BFSPathfinder;
import byog.AI.ClassicalPlanner;
import byog.AI.DecisionValidator;
import byog.AI.EnemyBrain;
import byog.AI.GameStateSnapshot;
import byog.AI.IntentArbiter;
import byog.AI.IntentLease;
import byog.AI.ReflexController;
import byog.AI.ReflexObservation;
import byog.AI.RuleBasedBrain;
import byog.AI.StrategicIntent;
import byog.Bridge.AgentProtocol;
import byog.Helper.Logger;
import byog.Helper.MathHelper;
import byog.IO.GameConfig;
import byog.Perception.ObservationEnvelope;
import byog.Perception.PerceptionSystem;
import byog.Trace.AgentTrace;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class Enemy extends Entity {
    private static final int MAX_RETRY = 4;
    private ActionQueue actionQueue;
    private EnemyBrain brain;
    private int hp;
    private int sightRange;
    private Random random;
    private int moveInterval;
    private int tickCounter;
    private StrategicIntent.Strategy currentStrategy;
    private int attackDamage;
    private int damageVariance;
    private boolean perceptionEnabled = false;
    private String agentId;
    private long observationSeq = 0;
    /** 最近一次私有感知计算的可见性遮罩，perceptionEnabled=false 时为 null */
    private boolean[][] cachedVisibleMask;
    /** 上一个 commit/collect 阶段生成的不可变私有观察。 */
    private ObservationEnvelope latestObservation;
    /** 从 latestObservation 提取、供下一阶段快脑使用的有限切片。 */
    private ReflexObservation latestReflexObservation;
    /** execute 阶段产生、等待 commit 后补全的动作记录。 */
    private PendingAction pendingAction;
    /** 最近一个已经在 commit 后完成的动作结果。 */
    private ActionOutcome lastActionOutcome;
    private String currentDecisionId;
    private String queuedDecisionId;
    private AgentProtocol.DecisionSource currentDecisionSource;
    private int actionsExecutedForDecision;
    private boolean agentRuntimeClosed;
    private final IntentArbiter arbiter;
    private final ReflexController reflexController;

    public Enemy(Position position, TETile tile, int hp, int sightRange,
                 int moveInterval, int attackDamage, int damageVariance,
                 Random random, String agentId) {
        super(position, tile);
        this.hp = hp;
        this.sightRange = sightRange;
        this.moveInterval = moveInterval;
        this.attackDamage = attackDamage;
        this.damageVariance = damageVariance;
        this.tickCounter = 0;
        this.random = random;
        this.actionQueue = new ActionQueue();
        this.brain = new RuleBasedBrain(sightRange, random);
        this.agentId = agentId;
        this.perceptionEnabled = false;
        this.observationSeq = 0;
        this.agentRuntimeClosed = false;
        this.arbiter = new IntentArbiter();
        this.reflexController = new ReflexController();
    }

    /**
     * 更新敌人 AI 状态。Brain 决策 → Planner 翻译 → 动作队列消费。
     * @param world 游戏世界瓦片数组
     * @param entityMgr 实体管理器，用于碰撞检测和空间索引
     * @param player 玩家，用于构建快照供 Brain 决策
     */
    public void updateAI(TETile[][] world, EntityManager entityMgr, Player player) {
        updateAI(world, entityMgr, player, null, AgentTrace.NO_OP);
    }

    /**
     * 带 trace 的 updateAI。traceContext 为 null 时不记录事件。
     * @param traceContext 场景上下文（可为 null）
     * @param traceSink trace 消费端
     */
    public void updateAI(TETile[][] world, EntityManager entityMgr, Player player,
                         AgentTrace.Context traceContext, AgentTrace.Sink traceSink) {
        // Legacy encounter-harness seam.
        // Production Game must not add new agent-runtime logic here.
        tickCounter++;
        // 每个动作（每隔moveInterval）评估当前局势
        if (tickCounter >= moveInterval) {
            tickCounter = 0;

            // 1. 感知层：perception -(brain)-> intent
            StrategicIntent intent;

            if (perceptionEnabled) {
                // 私有感知路径
                long seq = this.getAndIncrementObservationSeq();
                long currentTurn = traceContext != null ? traceContext.logicalTick : 0;
                String runId = traceContext != null ? traceContext.scenarioId : "unknown";
                int floorId = 1;

                ObservationEnvelope observation = PerceptionSystem.computeObservation(
                        runId, floorId, seq,
                        world, entityMgr,
                        this, player,
                        this.sightRange, currentTurn);

                if (traceContext != null) {
                    // ---- trace: 记录私有感知结果 ----
                    safeRecord(traceSink,
                            AgentTrace.TraceEvent.observationGenerated(
                                    traceContext,
                                    observation.canSeePlayer(),
                                    observation.getVisibleEntities().size(),
                                    observation.countVisibleTiles()));
                    // ---- end trace ----
                }

                intent = brain.thinkFromObservation(observation);

                if (traceContext != null) {
                    // ---- trace: 记录选中的意图 ----
                    safeRecord(traceSink,
                            AgentTrace.TraceEvent.intentSelected(traceContext, intent));
                    // ---- end trace ----
                }
            } else {
                // Legacy 路径（原有逻辑）
                GameStateSnapshot snapshot = new GameStateSnapshot(world,
                        player.getPosition(), this.getPosition(), this.getId());

                if (traceContext != null) {
                    // ---- trace: 记录 legacy 决策输入 ----
                    safeRecord(traceSink,
                            AgentTrace.TraceEvent.legacyDecisionInput(traceContext));
                    // ---- end trace ----
                }

                intent = brain.think(snapshot);

                if (traceContext != null) {
                    // ---- trace: 记录选中的意图 ----
                    safeRecord(traceSink,
                            AgentTrace.TraceEvent.intentSelected(traceContext, intent));
                    // ---- end trace ----
                }
            }

            // 2. 策略处理层  intent -(planner)-> actions
            StrategicIntent.Strategy newStrategy = intent.getStrategy();

            // 情况1：策略切换 → 立即清空旧队列，重新规划
            if (newStrategy != currentStrategy) {
                Logger.info("Enemy#%d Strategy: %s → %s",
                        this.getId(), currentStrategy, newStrategy);
                actionQueue.clear();
                currentStrategy = newStrategy;
                List<Action> actions = ClassicalPlanner.translate(intent,
                        this.getPosition(), this.getId(), world, entityMgr, random);
                actionQueue.enqueueAll(actions);
            } else if (actionQueue.needRefill()) {
                // 情况2：策略相同，续补
                List<Action> actions = ClassicalPlanner.translate(intent,
                        this.getPosition(), this.getId(), world, entityMgr, random);
                actionQueue.enqueueAll(actions);
            }

            // 3. 动作执行层
            for (int i = 0; i < MAX_RETRY; i++) {
                Action action = actionQueue.poll();   // 取出
                if (action == null) {
                    break;
                }

                Position before = this.getPosition();
                if (traceContext != null) {
                    // ---- trace: 记录动作尝试 ----
                    safeRecord(traceSink,
                            AgentTrace.TraceEvent.actionAttempted(
                                    traceContext, i, action, before));
                    // ---- end trace ----
                }

                Action.ActionResult result = action.execute(world, this);

                if (traceContext != null) {
                    // ---- trace: 记录动作结果 ----
                    safeRecord(traceSink,
                            AgentTrace.TraceEvent.actionResult(
                                    traceContext, i, action, result, before,
                                    this.getPosition()));
                    // ---- end trace ----
                }

                if (result == Action.ActionResult.SUCCESS) {
                    break;
                }
            }

            // 动作执行完成后，重新计算 FOV 以反映移动后的位置
            if (perceptionEnabled) {
                ObservationEnvelope postObs = PerceptionSystem.computeObservation(
                        "fov_update", 0, 0,
                        world, entityMgr,
                        this, player,
                        this.sightRange, 0);
                this.cachedVisibleMask = postObs.getVisibleMask();
            }
        }
    }

    /**
     * 在一次 AI tick 开始时轮询并接收 Agent 消息。
     *
     * <p>该方法必须保持非阻塞，避免单个敌人的消息处理拖慢整个游戏循环。
     * 当前尚未挂接 {@code AgentSession}，因此只保留生命周期边界并校验上下文；
     * Agent 运行时已经关闭时直接返回。</p>
     *
     * @param context 当前 AI tick 的运行身份、楼层和逻辑时钟
     * @throws IllegalArgumentException Agent 运行时尚未关闭但 {@code context} 为 null
     */
    public void pollAgentMessages(AiTickContext context) {
        if (agentRuntimeClosed) {
            return;
        }
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
    }

    /**
     * 根据上一轮已提交的私有观察进行仲裁，并在冷却到期时最多执行一个动作。
     *
     * <p>方法先推进移动冷却；冷却未到期、敌人已死亡、Agent 运行时已关闭，
     * 或尚无可用观察时不会执行动作。仲裁只读取 {@link ReflexObservation}
     * 和已保存的 {@link ObservationEnvelope}，不会直接读取实时玩家对象。</p>
     *
     * <p>仲裁优先级与 {@link IntentArbiter.Level} 一一对应：
     * {@code P1_REFLEX}（Safety Reflex）、{@code P2_REFLEX}（Visible Threat Reflex）、
     * {@code P3_LEASE}（Active Intent Lease）和
     * {@code P4_LOCAL_FALLBACK}（Local Fallback）。
     * 所有意图都必须经过 Java planner 转换为 {@link Action}。动作执行后，本方法
     * 只保存一个待提交的动作记录；提交后的坐标、生命值和反馈由
     * {@link #collectAgentUpdates(AiTickContext, TETile[][], EntityManager, Player)}
     * 在世界变更提交后补全。</p>
     *
     * @param context 当前 AI tick 的运行身份、楼层和逻辑时钟
     * @param world 当前权威世界；动作可能修改其中的 tile
     * @param entityMgr 当前实体管理器，供规划和碰撞检查使用
     * @throws IllegalArgumentException 任一参数为 null
     * @throws IllegalStateException 上一次执行产生的待提交动作尚未完成反馈收集
     */
    public void executeOneAction(AiTickContext context,
                                 TETile[][] world,
                                 EntityManager entityMgr) {

        /* 前置判断，相当于 P0 级仲裁（安全规则） */

        if (agentRuntimeClosed || !isAlive()) {
            return;
        }
        if (context == null || world == null || entityMgr == null) {
            throw new IllegalArgumentException(
                    "context, world and entityMgr must not be null");
        }
        if (pendingAction != null) {
            throw new IllegalStateException(
                    "collectAgentUpdates must complete before the next execute");
        }

        // 保持既有冷却语义：先累计 tick，再判断本轮是否允许行动。
        tickCounter++;
        if (tickCounter < moveInterval) {
            return;
        }
        tickCounter = 0;

        // 决策只能使用上一轮已提交的私有观察；冷启动时宁可跳过，也不读取实时 Player。
        if (latestObservation == null) {
            return;
        }

        // 反射是否接管
        boolean overrideWasActive = arbiter.isInReflexOverride();
        String overrideReason = null;  // 初始化为空内容

        // Decision: 仲裁结果 (Arbiter 只选择控制来源，具体意图仍由对应控制器生成)
        ReflexObservation reflex = latestReflexObservation;    // 反射（快脑观察）
        IntentArbiter.ArbiterDecision decision =             // decide出仲裁结果，返回决策等级
                arbiter.decide(reflex, context.getLogicalTick());
        AgentProtocol.DecisionSource source = AgentProtocol.DecisionSource.LOCAL_FALLBACK;  // 初始化，默认值，兜底
        String decisionId = currentDecisionId;   // 初始化为与上一次execute一样

        // 初始化action指针
        Action action = null;

        switch (decision.getLevel()) {
            case P1_REFLEX: {
                // Safety Reflex：相邻威胁触发立即攻击，并暂时覆盖当前 Intent Lease。
                StrategicIntent reflexIntent =
                        reflexController.createAdjacentAttackIntent(reflex);
                action = planSingleAction(
                        reflexIntent, world, entityMgr);
                overrideReason = arbiter.getOverrideReason();
                IntentLease lease = arbiter.getCurrentLease();
                if (lease != null
                        && lease.isValidAt(context.getLogicalTick())) {
                    source = lease.getDecisionSource();
                    decisionId = lease.getDecisionId();
                    selectDecision(decisionId, source);
                } else {
                    if (!overrideWasActive
                            || currentDecisionId == null
                            || currentDecisionSource
                            != AgentProtocol.DecisionSource.LOCAL_FALLBACK) {
                        startLocalDecision(context);
                    }
                    decisionId = currentDecisionId;
                }
                break;
            }
            case P2_REFLEX: {
                // Visible Threat Reflex：玩家可见且 Interrupt Policy 允许时主动接战。
                StrategicIntent reflexIntent =
                        reflexController.createEngageIntent(reflex);
                action = planSingleAction(
                        reflexIntent, world, entityMgr);
                overrideReason = arbiter.getOverrideReason();
                IntentLease lease = arbiter.getCurrentLease();
                if (lease != null
                        && lease.isValidAt(context.getLogicalTick())) {
                    source = lease.getDecisionSource();
                    decisionId = lease.getDecisionId();
                    selectDecision(decisionId, source);
                } else {
                    if (!overrideWasActive
                            || currentDecisionId == null
                            || currentDecisionSource
                            != AgentProtocol.DecisionSource.LOCAL_FALLBACK) {
                        startLocalDecision(context);
                    }
                    decisionId = currentDecisionId;
                }
                break;
            }
            case P3_LEASE: {
                // Active Intent Lease：继续已有计划，仅在决策变化或队列不足时重新规划。
                IntentLease lease = arbiter.getCurrentLease();
                StrategicIntent leaseIntent = lease.getIntent();

                if (!lease.getDecisionId().equals(queuedDecisionId)) {
                    currentStrategy = leaseIntent.getStrategy();
                    List<Action> actions = ClassicalPlanner.translateBounded(
                            leaseIntent, getPosition(), getId(), world,
                            entityMgr, random, actionQueue.getHighWater());
                    actionQueue.replaceWithBoundedPrefix(actions);
                    queuedDecisionId = lease.getDecisionId();
                } else if (actionQueue.needRefill()) {
                    List<Action> actions = ClassicalPlanner.translateBounded(
                            leaseIntent, getPosition(), getId(), world,
                            entityMgr, random, actionQueue.remainingCapacity());
                    actionQueue.appendBounded(actions);
                }

                action = actionQueue.poll();
                source = lease.getDecisionSource();
                decisionId = lease.getDecisionId();
                selectDecision(decisionId, source);
                break;
            }
            case P4_LOCAL_FALLBACK: {
                // Local Fallback：没有可用 Lease 时，由本地 Brain 创建并接管新计划。
                StrategicIntent intent = brain.thinkFromObservation(latestObservation);
                currentStrategy = intent.getStrategy();
                startLocalDecision(context);
                arbiter.adoptLocalFallbackLease(intent, currentDecisionId,
                        latestObservation.getObservationSeq(),
                        context.getLogicalTick(), 30);
                List<Action> actions = ClassicalPlanner.translateBounded(
                        intent, getPosition(), getId(), world,
                        entityMgr, random, actionQueue.getHighWater());
                actionQueue.replaceWithBoundedPrefix(actions);
                queuedDecisionId = currentDecisionId;

                action = actionQueue.poll();
                source = AgentProtocol.DecisionSource.LOCAL_FALLBACK;
                decisionId = currentDecisionId;
                break;
            }
            default:
                return;
        }

        if (action == null) {
            return;
        }

        // 此处只执行并暂存原始结果；提交后的最终状态由 collectAgentUpdates 补全。
        Position before = copyPosition(getPosition());
        Action.ActionResult result = action.execute(world, this);
        actionsExecutedForDecision++;
        pendingAction = new PendingAction(
                context.getRunId(), context.getFloorId(),
                context.getLogicalTick(), decisionId,
                actionsExecutedForDecision, action.getClass().getSimpleName(),
                result, before, source,
                overrideReason);
    }

    /**
     * 在世界变更提交后收集敌人的最新私有观察和动作反馈。
     *
     * <p>调用方必须先完成实体增删提交和死亡实体清理，使空间索引能够反映
     * {@link #executeOneAction(AiTickContext, TETile[][], EntityManager)} 的执行结果。
     * 本方法基于已提交世界重新计算 {@link ObservationEnvelope}，同步更新反射观察
     * 和可见性遮罩；若存在待提交动作，则生成包含最终位置、生命值、决策来源和
     * 覆盖原因的 {@link ActionOutcome}，随后清除待提交记录。</p>
     *
     * <p>敌人已死亡或 Agent 运行时已关闭时直接返回，不再生成观察或反馈。</p>
     *
     * @param context 当前 AI tick 的运行身份、楼层和逻辑时钟；必须与待提交动作一致
     * @param world 已完成本次动作提交的权威世界
     * @param entityMgr 已完成增删提交和死亡清理的实体管理器
     * @param player 当前玩家，仅用于生成受视野限制的私有观察
     * @throws IllegalArgumentException 任一参数为 null
     * @throws IllegalStateException 当前敌人不在已提交空间索引的自身位置，
     *                               或待提交动作与当前上下文不匹配
     */
    public void collectAgentUpdates(AiTickContext context,
                                    TETile[][] world,
                                    EntityManager entityMgr,
                                    Player player) {
        if (agentRuntimeClosed || !isAlive()) {
            return;
        }
        if (context == null || world == null || entityMgr == null
                || player == null) {
            throw new IllegalArgumentException(
                    "context, world, entityMgr and player must not be null");
        }

        // 空间索引必须已经提交本轮动作产生的实体变化。
        if (entityMgr.findEntityAt(getPosition()) != this) {
            throw new IllegalStateException(
                    "collectAgentUpdates must run after the world commit barrier");
        }

        // 有动作待反馈时，禁止跨 run、楼层或逻辑 tick 错配结果。
        if (pendingAction != null) {
            ensureMatchingCollectContext(context, pendingAction);
        }

        // 从已提交世界生成下一轮决策使用的私有观察及 Reflex Observation。
        ObservationEnvelope committedObservation =
                PerceptionSystem.computeObservation(
                        context.getRunId(), context.getFloorId(),
                        getAndIncrementObservationSeq(),
                        world, entityMgr, this, player, sightRange,
                        context.getLogicalTick());
        latestObservation = committedObservation;
        latestReflexObservation = ReflexObservation.from(committedObservation);
        cachedVisibleMask = committedObservation.getVisibleMask();

        if (pendingAction != null) {
            // 使用提交后的最终位置和生命值补全 Action Outcome。
            lastActionOutcome = new ActionOutcome(
                    pendingAction.runId, pendingAction.floorId, agentId,
                    pendingAction.logicalTick, pendingAction.decisionId,
                    pendingAction.actionIndex, pendingAction.actionType,
                    pendingAction.result, pendingAction.beforePosition,
                    getPosition(), hp, pendingAction.decisionSource,
                    pendingAction.overrideReason);
            pendingAction = null;
        }
    }

    /**
     * Idempotent lifecycle seam. The local runtime owns no socket or IO thread.
     */
    public void closeAgentRuntime() {
        if (agentRuntimeClosed) {
            return;
        }
        agentRuntimeClosed = true;
        actionQueue.clear();
        queuedDecisionId = null;
        pendingAction = null;
    }

    /**
     * 创建本地决策身份，并重置该决策的动作序号。
     */
    private void startLocalDecision(AiTickContext context) {
        currentDecisionId = "local-" + agentId + "-tick-"
                + context.getLogicalTick();
        currentDecisionSource = AgentProtocol.DecisionSource.LOCAL_FALLBACK;
        actionsExecutedForDecision = 0;
    }

    /**
     * 切换当前决策归属；决策或来源变化时重新计算动作序号。
     */
    private void selectDecision(
            String decisionId,
            AgentProtocol.DecisionSource source) {
        if (!decisionId.equals(currentDecisionId)
                || source != currentDecisionSource) {
            currentDecisionId = decisionId;
            currentDecisionSource = source;
            actionsExecutedForDecision = 0;
        }
    }

    /**
     * 通过权威 Java planner 将意图转换为至多一个待执行动作。
     */
    private Action planSingleAction(
            StrategicIntent intent,
            TETile[][] world,
            EntityManager entityMgr) {
        if (intent == null) {
            return null;
        }
        List<Action> actions = ClassicalPlanner.translateBounded(
                intent, getPosition(), getId(), world,
                entityMgr, random, 1);
        return actions.isEmpty() ? null : actions.get(0);
    }

    /**
     * 校验动作执行与反馈收集使用相同的运行身份、楼层和逻辑 tick。
     */
    private static void ensureMatchingCollectContext(
            AiTickContext context, PendingAction action) {
        if (!action.runId.equals(context.getRunId())
                || action.floorId != context.getFloorId()
                || action.logicalTick != context.getLogicalTick()) {
            throw new IllegalStateException(
                    "execute and collect must use the same AI tick context");
        }
    }

    private static Position copyPosition(Position position) {
        return new Position(position.x, position.y);
    }

    private static final class PendingAction {
        private final String runId;
        private final int floorId;
        private final long logicalTick;
        private final String decisionId;
        private final int actionIndex;
        private final String actionType;
        private final Action.ActionResult result;
        private final Position beforePosition;
        private final AgentProtocol.DecisionSource decisionSource;
        private final String overrideReason;

        private PendingAction(String runId, int floorId, long logicalTick,
                              String decisionId, int actionIndex,
                              String actionType, Action.ActionResult result,
                              Position beforePosition,
                              AgentProtocol.DecisionSource decisionSource,
                              String overrideReason) {
            this.runId = runId;
            this.floorId = floorId;
            this.logicalTick = logicalTick;
            this.decisionId = decisionId;
            this.actionIndex = actionIndex;
            this.actionType = actionType;
            this.result = result;
            this.beforePosition = copyPosition(beforePosition);
            this.decisionSource = decisionSource;
            this.overrideReason = overrideReason;
        }
    }

    /**
     * Testing seam: validate and adopt a remote intent without AgentSession.
     * @return ACCEPTED if the intent was adopted, otherwise the validation failure
     */
    public DecisionValidator.ValidationResult tryAdoptRemoteIntent(
            AgentProtocol.SubmitIntentData proposal,
            AgentProtocol.Envelope envelope,
            DecisionValidator.RequestExpectation expectation,
            TETile[][] committedWorld,
            long currentLogicalTick) {
        if (agentRuntimeClosed) {
            return DecisionValidator.ValidationResult.IDENTITY_MISMATCH;
        }
        return arbiter.tryAdoptRemoteIntent(
                proposal, envelope, expectation,
                latestReflexObservation, committedWorld, currentLogicalTick);
    }

    /** 获取 Arbiter（测试用）。 */
    public IntentArbiter getArbiter() {
        return arbiter;
    }

    public int getHp() {
        return hp;
    }

    /**
     * 安全记录 trace 事件。Sink 异常不得中断 AI 行为。
     * 当前 InMemorySink 不会主动抛异常，这是防御性保护。
     */
    private static void safeRecord(AgentTrace.Sink sink, AgentTrace.TraceEvent event) {
        try {
            sink.record(event);
        } catch (RuntimeException e) {
            Logger.error("Trace record failed for " + event.eventType
                    + ": " + e.getMessage());
        }
    }

    public int getSightRange() {
        return sightRange;
    }

    public int getAttackDamage() {
        return attackDamage;
    }

    public int getDamageVariance() {
        return damageVariance;
    }

    public int getMoveInterval() {
        return moveInterval;
    }

    public void setHp(int hp) {
        this.hp = hp;
    }

    public ActionQueue getActionQueue() {
        return actionQueue;
    }

    public ObservationEnvelope getLatestObservation() {
        return latestObservation;
    }

    public ReflexObservation getLatestReflexObservation() {
        return latestReflexObservation;
    }

    public ActionOutcome getLastActionOutcome() {
        return lastActionOutcome;
    }

    public ActionOutcome consumeLastActionOutcome() {
        ActionOutcome outcome = lastActionOutcome;
        lastActionOutcome = null;
        return outcome;
    }

    public boolean hasPendingActionOutcome() {
        return pendingAction != null;
    }

    public boolean isAgentRuntimeClosed() {
        return agentRuntimeClosed;
    }

    public void setPerceptionEnabled(boolean enabled) {
        this.perceptionEnabled = enabled;
    }

    public boolean isPerceptionEnabled() {
        return perceptionEnabled;
    }

    /** 返回最近一次私有感知计算的可见性遮罩。perceptionEnabled=false 时返回 null。 */
    public boolean[][] getVisibleMask() {
        return cachedVisibleMask;
    }

    public String getAgentId() {
        return agentId;
    }

    public long getAndIncrementObservationSeq() {
        return observationSeq++;
    }

    /**
     * 在世界中随机生成多个敌人。
     *
     * 生成逻辑分三步：
     * 1. 确定数量：基础数量(config) + 楼层递增(extraCount) + 随机波动(0~2)，
     *    随机波动由 seed 决定，保证同一种子每次生成的数量一致。
     * 2. 创建敌人：每个敌人有独立的 Random（seed + 编号），确保属性稳定可复现。
     *    先用 initEntity 在世界上随机放置。
     * 3. 距离检查：如果敌人离玩家太近（曼哈顿距离 < 5），换一个新位置重新放置，
     *    直到满足距离要求为止。retry 使用不同的种子后缀避免死循环落在同一位置。
     *
     * @param world     游戏世界
     * @param seed      种子，用于敌人数量和位置的可复现随机
     * @param playerPos 玩家位置，敌人不会生成在离玩家太近的地方
     * @param extraCount 额外敌人数量（随楼层递增，floorLevel - 1 传入）
     * @param config    游戏平衡配置（血量、视野、攻击等参数）
     * @return 生成的敌人列表
     */
    public static List<Enemy> spawnEnemies(TETile[][] world, String seed,
            Position playerPos, int extraCount, GameConfig config) {
        List<Enemy> enemies = new ArrayList<>();
        Random countRandom = new Random((seed + "_enemy_count").hashCode());
        int count = config.enemyBaseCount + extraCount + countRandom.nextInt(3);

        for (int i = 0; i < count; i++) {
            Random random = new Random((seed + "_enemy_" + i).hashCode());
            Enemy enemy = new Enemy(new Position(0, 0), Tileset.ENEMY,
                    config.enemyHp, config.enemySightRange, config.enemyMoveInterval,
                    config.enemyAttack, config.enemyDamageVariance, random, "enemy-" + i);
            enemy.setPerceptionEnabled(true);   // 游戏运行时，默认开启感知模式
            Entity.initEntity(enemy, world, seed + "_pos_" + i);

            int retryCount = 0;
            while (MathHelper.manhattanDistance(enemy.getPosition(), playerPos) < 5) {
                enemy.setPosition(new Position(-1, -1));  // 强制 initEntity 重新随机放置
                Entity.initEntity(enemy, world, seed + "_pos_" + i + "_retry_" + retryCount);
                retryCount++;
            }

            enemies.add(enemy);
        }
        return enemies;
    }
}
