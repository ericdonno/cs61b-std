package byog.Entity;

import byog.Action.Action;
import byog.Action.ActionOutcome;
import byog.Action.ActionQueue;
import byog.Action.AttackAction;
import byog.Action.MoveAction;
import byog.Action.TurnAction;
import byog.Action.WaitAction;
import byog.AI.AiTickContext;
import byog.AI.BFSPathfinder;
import byog.AI.ClassicalPlanner;
import byog.AI.DecisionValidator;
import byog.AI.EnemyBrain;
import byog.AI.GameStateSnapshot;
import byog.AI.IntentArbiter;
import byog.AI.IntentLease;
import byog.AI.PatrolController;
import byog.AI.PatrolState;
import byog.AI.ReflexController;
import byog.AI.ReflexObservation;
import byog.AI.RuleBasedBrain;
import byog.AI.StrategicIntent;
import byog.Bridge.AgentHandler;
import byog.Bridge.AgentProtocol;
import byog.Bridge.AgentProtocolCodec;
import byog.Bridge.AgentSession;
import byog.Common.Direction;
import byog.Common.Facing;
import byog.Common.VisionMode;
import byog.Helper.Logger;
import byog.Helper.MathHelper;
import byog.IO.GameConfig;
import byog.Perception.ObservationEnvelope;
import byog.Perception.PerceptionSystem;
import byog.Perception.VisibleEntity;
import byog.Trace.AgentTrace;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

public class Enemy extends Entity {
    private static final int MAX_RETRY = 4;
    private ActionQueue actionQueue;
    private EnemyBrain brain;
    private int hp;
    private int maxHp;
    private Facing facing;
    private int sightRange;
    private Random random;
    private int moveInterval;
    /** 独立攻击间隔（tick）；由 config.enemyAttackInterval 设置，默认与移动一致。 */
    private int attackInterval;
    /** 攻击冷却剩余 tick；不入存档，读档从 0 开始。 */
    private int attackCooldownTicks;
    private int tickCounter;
    private StrategicIntent.Strategy currentStrategy;
    private int attackDamage;
    private int damageVariance;
    private boolean perceptionEnabled = false;
    private String agentId;
    /** 所属命名世界的稳定身份；由 Game 在创建/读档时设置。 */
    private String worldId;
    /** 本敌人的视野模式；随世界存档保存。 */
    private VisionMode visionMode = VisionMode.DIRECTIONAL;
    private long observationSeq = 0;
    /** 最近一次私有感知计算的可见性遮罩，perceptionEnabled=false 时为 null */
    private boolean[][] cachedVisibleMask;
    /** 上一个 commit/collect 周期生成的不可变私有观察。 */
    private ObservationEnvelope latestObservation;
    /** 从 latestObservation 提取、供下一次快脑决策使用的有限切片。 */
    private ReflexObservation latestReflexObservation;
    /** execute 产生、等待 commit 后补全的动作记录。 */
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
    private final AgentHandler agentHandler;
    private AgentSession agentSession;
    private TETile[][] committedWorldForAgentValidation;
    private AiTickContext activeAgentPollContext;
    private boolean actionQueueWasLow;
    private boolean lastReflexOverrideState;
    private long lastAgentRequestTick;
    private long lastSessionLifecycleSequence;
    /** 可保存的确定性巡视状态；换层重置、读档恢复。 */
    private final PatrolState patrolState;
    private final PatrolController patrolController;
    /** 巡视目标选择的世界 seed 派生；由 Game 设置。 */
    private long patrolSeedKey;

    public Enemy(Position position, TETile tile, int hp, int sightRange,
                 int moveInterval, int attackDamage, int damageVariance,
                 Random random, String agentId) {
        this(position, tile, hp, sightRange, moveInterval,
                attackDamage, damageVariance, random, agentId,
                ActionQueue.DEFAULT_LOW_WATER,
                ActionQueue.DEFAULT_HIGH_WATER);
    }

    public Enemy(Position position, TETile tile, int hp, int sightRange,
                 int moveInterval, int attackDamage, int damageVariance,
                 Random random, String agentId,
                 int actionQueueLowWater, int actionQueueHighWater) {
        super(position, tile);
        if (hp < 0) {
            throw new IllegalArgumentException("hp must be >= 0");
        }
        this.hp = hp;
        this.maxHp = Math.max(hp, 1);
        this.facing = Facing.NORTH;
        this.sightRange = sightRange;
        this.moveInterval = moveInterval;
        this.attackInterval = moveInterval;
        this.attackCooldownTicks = 0;
        this.attackDamage = attackDamage;
        this.damageVariance = damageVariance;
        this.tickCounter = 0;
        this.random = random;
        this.actionQueue = new ActionQueue(
                actionQueueLowWater, actionQueueHighWater);
        this.brain = new RuleBasedBrain(sightRange, random);
        this.agentId = agentId;
        this.perceptionEnabled = false;
        this.observationSeq = 0;
        this.agentRuntimeClosed = false;
        this.arbiter = new IntentArbiter();
        this.reflexController = new ReflexController();
        this.agentHandler = new EnemyAgentHandler();
        this.actionQueueWasLow = false;
        this.lastReflexOverrideState = false;
        this.lastAgentRequestTick = Long.MIN_VALUE;
        this.lastSessionLifecycleSequence = -1;
        this.patrolState = new PatrolState();
        this.patrolController = new PatrolController();
        this.patrolSeedKey = 0L;
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
                        worldId, runId, floorId, seq,
                        world, entityMgr,
                        this, player,
                        this.sightRange, visionMode, currentTurn);

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
                        worldId, "fov_update", 0, 0,
                        world, entityMgr,
                        this, player,
                        this.sightRange, visionMode, 0);
                this.cachedVisibleMask = postObs.getVisibleMask();
            }
        }
    }

    /**
     * 在一次 AI tick 开始时轮询并接收 Agent 消息。
     *
     * <p>该方法必须保持非阻塞，避免单个敌人的消息处理拖慢整个游戏循环。
     * Session 只在这里把已解析的入站消息交给 Validator 和 Arbiter；Socket IO
     * 始终由 transport worker 独立完成。Agent 运行时已经关闭时直接返回。</p>
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
        AgentSession session = agentSession;
        if (session == null || session.isClosed()) {
            return;
        }
        if (!sessionMatchesContext(session, context)) {
            disableMismatchedSession(session, context);
            return;
        }

        activeAgentPollContext = context;
        try {
            session.pollInbound(agentHandler, context.getLogicalTick());
            session.advanceRequestLifecycle(context.getLogicalTick());
            recordSessionLifecycle(context, session);
        } finally {
            activeAgentPollContext = null;
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
        if (attackCooldownTicks > 0) {
            attackCooldownTicks--;
        }
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
        String previousOverrideReason = arbiter.getOverrideReason();
        String overrideReason = null;  // 初始化为空内容

        // Decision: 仲裁结果 (Arbiter 只选择控制来源，具体意图仍由对应控制器生成)
        ReflexObservation reflex = latestReflexObservation;    // 反射（快脑观察）
        IntentArbiter.ArbiterDecision decision =             // decide出仲裁结果，返回决策等级
                arbiter.decide(reflex, context.getLogicalTick());
        recordReflexTransition(
                context, overrideWasActive, previousOverrideReason,
                decision.getLevel().name());
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

                if (leaseIntent.getStrategy()
                        == StrategicIntent.Strategy.PATROL
                        && lease.getDecisionSource()
                        == AgentProtocol.DecisionSource.LOCAL_FALLBACK) {
                    // 本地巡视 lease：由确定性巡视状态机持续驱动（移动/等待/转向），
                    // 避免 self-target 冻结导致扫描被抑制。远程 PATROL lease 仍走
                    // 原 planner 逻辑，使用 validator 补全的远程目标。
                    PatrolController.PatrolDecision patrol =
                            patrolController.decide(
                                    latestObservation, patrolState,
                                    patrolSeedKey, context.getFloorId(),
                                    agentId);
                    actionQueue.replaceWithBoundedPrefix(
                            actionsForPatrol(patrol, world, entityMgr));
                    queuedDecisionId = lease.getDecisionId();
                    recordPatrolTrace(context, patrol);
                    action = actionQueue.poll();
                    source = lease.getDecisionSource();
                    decisionId = lease.getDecisionId();
                    selectDecision(decisionId, source);
                    break;
                }

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
                // Local Fallback：没有可用 Lease 时，由确定性巡视状态机接管。
                // 玩家不可见（P1/P2 已处理可见情况），因此本地决策只做巡视。
                PatrolController.PatrolDecision patrol =
                        patrolController.decide(
                                latestObservation, patrolState,
                                patrolSeedKey, context.getFloorId(),
                                agentId);
                StrategicIntent intent = new StrategicIntent(
                        StrategicIntent.Goal.PATROL,
                        StrategicIntent.Strategy.PATROL,
                        patrolTarget(patrol, getPosition()));
                currentStrategy = intent.getStrategy();
                startLocalDecision(context);
                arbiter.adoptLocalFallbackLease(intent, currentDecisionId,
                        latestObservation.getObservationSeq(),
                        context.getLogicalTick(), 30);
                recordAgentEvent(context,
                        AgentTrace.agentEvent(
                                AgentTrace.EventType.LOCAL_BRAIN_TAKEOVER,
                                context.getRunId(), context.getFloorId(),
                                agentId, context.getLogicalTick())
                                .observationSequence(
                                        latestObservation.getObservationSeq())
                                .decision(
                                        currentDecisionId,
                                        AgentProtocol.DecisionSource
                                                .LOCAL_FALLBACK.name())
                                .execution(decision.getLevel().name()));
                actionQueue.replaceWithBoundedPrefix(
                        actionsForPatrol(patrol, world, entityMgr));
                queuedDecisionId = currentDecisionId;

                recordPatrolTrace(context, patrol);

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

        // 独立攻击冷却：仲裁为攻击但冷却未到 → 降级为朝可见玩家移动（追击），
        // 不原地等待。没有可见玩家时放弃本次行动。
        if (action instanceof AttackAction && attackCooldownTicks > 0) {
            action = chaseActionTowardsVisiblePlayer(entityMgr);
            if (action == null) {
                return;
            }
        }

        // 此处只执行并暂存原始结果；提交后的最终状态由 collectAgentUpdates 补全。
        Position before = copyPosition(getPosition());
        String beforeFacing = facing == null ? null : facing.name();
        int actionIndex = actionsExecutedForDecision + 1;
        recordAgentEvent(context,
                withSession(
                        AgentTrace.agentEvent(
                                AgentTrace.EventType.ACTION_ATTEMPTED,
                                context.getRunId(), context.getFloorId(),
                                agentId, context.getLogicalTick())
                                .observationSequence(
                                        latestObservation.getObservationSeq())
                                .decision(decisionId, source.name())
                                .override(overrideReason)
                                .execution(decision.getLevel().name())
                                .facingChange(beforeFacing,
                                        facing == null ? null : facing.name())
                                .action(actionIndex,
                                        action.getClass().getSimpleName(),
                                        null, before, null),
                        agentSession));
        Action.ActionResult result = action.execute(world, this);
        if (action instanceof AttackAction) {
            // 攻击成功后进入冷却；未命中（BLOCKED）也计入节奏。
            attackCooldownTicks = attackInterval;
        }
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
                        worldId, context.getRunId(), context.getFloorId(),
                        getAndIncrementObservationSeq(),
                        world, entityMgr, this, player, sightRange,
                        visionMode, context.getLogicalTick());
        latestObservation = committedObservation;
        latestReflexObservation = ReflexObservation.from(committedObservation);
        cachedVisibleMask = committedObservation.getVisibleMask();
        committedWorldForAgentValidation = world;
        recordAgentEvent(context,
                withSession(
                        AgentTrace.agentEvent(
                                AgentTrace.EventType.OBSERVATION_GENERATED,
                                context.getRunId(), context.getFloorId(),
                                agentId, context.getLogicalTick())
                                .observation(
                                        committedObservation.getObservationSeq(),
                                        committedObservation.getVisiblePlayer()
                                                != null,
                                        committedObservation
                                                .getVisibleEntities().size(),
                                        committedObservation
                                                .getVisibleTiles().size())
                                .world(worldId)
                                .perception(visionMode.name(),
                                        facing == null ? null : facing.name(),
                                        hp, maxHp),
                        agentSession));

        ActionOutcome completedOutcome = null;
        if (pendingAction != null) {
            // 使用提交后的最终位置和生命值补全 Action Outcome。
            completedOutcome = new ActionOutcome(
                    pendingAction.runId, pendingAction.floorId, agentId,
                    pendingAction.logicalTick, pendingAction.decisionId,
                    pendingAction.actionIndex, pendingAction.actionType,
                    pendingAction.result, pendingAction.beforePosition,
                    getPosition(), hp, pendingAction.decisionSource,
                    pendingAction.overrideReason);
            lastActionOutcome = completedOutcome;
            pendingAction = null;
            recordPatrolOutcome(completedOutcome);
            recordAgentEvent(context,
                    withSession(
                            AgentTrace.agentEvent(
                                    AgentTrace.EventType.ACTION_RESULT,
                                    context.getRunId(), context.getFloorId(),
                                    agentId, context.getLogicalTick())
                                    .observationSequence(
                                            committedObservation
                                                    .getObservationSeq())
                                    .execution("COMMITTED")
                                    .action(completedOutcome),
                            agentSession));
        }

        publishAgentUpdates(
                context, committedObservation, completedOutcome);
    }

    /**
     * Attaches one optional remote session whose stable identity belongs to this enemy.
     */
    public void attachAgentSession(AgentSession session) {
        Objects.requireNonNull(session, "session");
        if (agentRuntimeClosed) {
            throw new IllegalStateException("agent runtime is already closed");
        }
        if (session.isClosed()) {
            throw new IllegalArgumentException("session must be open");
        }
        if (!agentId.equals(session.getIdentity().agentId)) {
            throw new IllegalArgumentException(
                    "session agentId does not belong to this enemy");
        }
        if (agentSession == session) {
            return;
        }
        if (agentSession != null && !agentSession.isClosed()) {
            throw new IllegalStateException(
                    "close or detach the existing session before replacement");
        }
        agentSession = session;
        actionQueueWasLow = false;
        lastAgentRequestTick = Long.MIN_VALUE;
        lastSessionLifecycleSequence = -1;
    }

    /**
     * Detaches the optional remote session without changing local fallback behavior.
     */
    public void detachAgentSession() {
        agentSession = null;
        activeAgentPollContext = null;
        lastSessionLifecycleSequence = -1;
    }

    /**
     * Idempotently closes the optional session and makes this enemy runtime terminal.
     */
    public void closeAgentRuntime() {
        if (agentRuntimeClosed) {
            return;
        }
        agentRuntimeClosed = true;
        AgentSession session = agentSession;
        agentSession = null;
        activeAgentPollContext = null;
        committedWorldForAgentValidation = null;
        actionQueue.clear();
        queuedDecisionId = null;
        pendingAction = null;
        if (session != null) {
            session.close();
        }
    }

    /**
     * Publishes committed feedback and starts or refreshes bounded strategic requests.
     */
    private void publishAgentUpdates(
            AiTickContext context,
            ObservationEnvelope observation,
            ActionOutcome completedOutcome) {
        AgentSession session = agentSession;
        if (session == null || session.isClosed()
                || !sessionMatchesContext(session, context)) {
            return;
        }

        if (completedOutcome != null) {
            AgentSession.EnqueueResult feedbackResult =
                    session.sendActionFeedback(
                    completedOutcome, context.getLogicalTick());
            if (feedbackResult == AgentSession.EnqueueResult.ACCEPTED
                    || feedbackResult == AgentSession.EnqueueResult.COALESCED) {
                recordAgentEvent(context,
                        withSession(
                                AgentTrace.agentEvent(
                                        AgentTrace.EventType
                                                .ACTION_FEEDBACK_ENQUEUED,
                                        context.getRunId(),
                                        context.getFloorId(), agentId,
                                        context.getLogicalTick())
                                        .observationSequence(
                                                observation.getObservationSeq())
                                        .message(AgentProtocol.MessageType
                                                .ACTION_FEEDBACK.name())
                                        .validation(feedbackResult.name())
                                        .action(completedOutcome),
                                session));
            }
        }

        List<AgentProtocol.WorldEventData> events =
                collectRequestEvents(context, completedOutcome);
        boolean lowNow = actionQueue.needRefill();
        boolean crossedLowWater = lowNow && !actionQueueWasLow;
        boolean firstObservation = session.getLatestObservation() == null;
        boolean blocked = completedOutcome != null
                && (completedOutcome.getResult() == Action.ActionResult.BLOCKED
                || completedOutcome.getResult()
                == Action.ActionResult.INTERRUPTED);
        boolean reflexChanged = lastReflexOverrideState
                != arbiter.isInReflexOverride();
        boolean heartbeatDue = isHeartbeatDue(
                session, context.getLogicalTick());

        if (firstObservation || crossedLowWater || blocked
                || reflexChanged || heartbeatDue) {
            AgentSession.RequestStartResult result = session.requestIntent(
                    observation, events, context.getLogicalTick());
            if (result != AgentSession.RequestStartResult.CLOSED) {
                lastAgentRequestTick = context.getLogicalTick();
            }
        } else {
            for (AgentProtocol.WorldEventData event : events) {
                session.sendWorldEvent(event, context.getLogicalTick());
            }
        }

        actionQueueWasLow = lowNow;
        lastReflexOverrideState = arbiter.isInReflexOverride();
        recordSessionLifecycle(context, session);
    }

    /**
     * Builds the minimal committed events needed to explain replanning triggers.
     */
    private List<AgentProtocol.WorldEventData> collectRequestEvents(
            AiTickContext context, ActionOutcome completedOutcome) {
        List<AgentProtocol.WorldEventData> events = new ArrayList<>();
        AgentProtocol.PositionData position = new AgentProtocol.PositionData(
                getPosition().x, getPosition().y);
        if (completedOutcome != null
                && (completedOutcome.getResult() == Action.ActionResult.BLOCKED
                || completedOutcome.getResult()
                == Action.ActionResult.INTERRUPTED)) {
            events.add(new AgentProtocol.WorldEventData(
                    AgentProtocol.WorldEventType.PLAN_BLOCKED.name(),
                    context.getLogicalTick(), position, agentId));
        }
        if (lastReflexOverrideState != arbiter.isInReflexOverride()) {
            AgentProtocol.WorldEventType type = arbiter.isInReflexOverride()
                    ? AgentProtocol.WorldEventType.REFLEX_OVERRIDE_STARTED
                    : AgentProtocol.WorldEventType.REFLEX_OVERRIDE_ENDED;
            events.add(new AgentProtocol.WorldEventData(
                    type.name(), context.getLogicalTick(), position, agentId));
        }
        return events;
    }

    /**
     * Uses a conservative periodic observation refresh when no other trigger fires.
     */
    private boolean isHeartbeatDue(
            AgentSession session, long logicalTick) {
        if (lastAgentRequestTick == Long.MIN_VALUE) {
            return false;
        }
        return logicalTick - lastAgentRequestTick
                >= session.getHeartbeatTicks();
    }

    /**
     * Rejects a session whose fixed run or floor identity cannot serve this tick.
     */
    private boolean sessionMatchesContext(
            AgentSession session, AiTickContext context) {
        AgentProtocol.Identity identity = session.getIdentity();
        return agentId.equals(identity.agentId)
                && context.getRunId().equals(identity.runId)
                && context.getFloorId() == identity.floorId;
    }

    /**
     * Closes an incorrectly routed remote session while preserving local fallback.
     */
    private void disableMismatchedSession(
            AgentSession session, AiTickContext context) {
        Logger.error(
                "Closing mismatched agent session for %s at run=%s floor=%d",
                agentId, context.getRunId(), context.getFloorId());
        if (agentSession == session) {
            agentSession = null;
        }
        session.close();
    }

    /**
     * Adapts session callbacks to validation and lease adoption on the game thread.
     */
    private final class EnemyAgentHandler implements AgentHandler {
        @Override
        public IntentHandlingResult onIntentSubmitted(
                AgentProtocol.SubmitIntentData data,
                AgentProtocol.Envelope envelope,
                AgentSession.RequestContext requestContext) {
            AiTickContext context = activeAgentPollContext;
            if (context == null || latestReflexObservation == null
                    || committedWorldForAgentValidation == null) {
                Logger.info(
                        "Rejected agent intent for %s without committed validation context",
                        agentId);
                return IntentHandlingResult.REJECTED;
            }

            DecisionValidator.RequestExpectation expectation =
                    new DecisionValidator.RequestExpectation(
                            requestContext.getIdentity(),
                            requestContext.getDecisionId(),
                            requestContext.getObservationSeq(),
                            requestContext.getRequestGeneration(),
                            requestContext.getSourceObservation());
            DecisionValidator.ValidationResult result =
                    arbiter.tryAdoptRemoteIntent(
                            data, envelope, expectation,
                            latestReflexObservation,
                            committedWorldForAgentValidation,
                            context.getLogicalTick());
            if (result == DecisionValidator.ValidationResult.ACCEPTED) {
                AgentTrace.AgentEventBuilder adopted =
                        AgentTrace.agentEvent(
                                AgentTrace.EventType.INTENT_ADOPTED,
                                context.getRunId(), context.getFloorId(),
                                agentId, context.getLogicalTick())
                                .observationSequence(data.observationSeq())
                                .decision(
                                        data.decisionId(),
                                        AgentProtocol.DecisionSource
                                                .REMOTE_AGENT.name())
                                .message(AgentProtocol.MessageType
                                        .SUBMIT_INTENT.name())
                                .validation(result.name());
                recordAgentEvent(
                        context, withSession(adopted, agentSession));
                if (currentDecisionSource
                        == AgentProtocol.DecisionSource.LOCAL_FALLBACK) {
                    recordAgentEvent(context,
                            withSession(
                                    AgentTrace.agentEvent(
                                            AgentTrace.EventType
                                                    .REMOTE_AGENT_RESUMED,
                                            context.getRunId(),
                                            context.getFloorId(), agentId,
                                            context.getLogicalTick())
                                            .observationSequence(
                                                    data.observationSeq())
                                            .decision(
                                                    data.decisionId(),
                                                    AgentProtocol.DecisionSource
                                                            .REMOTE_AGENT
                                                            .name()),
                                    agentSession));
                }
            }
            return result == DecisionValidator.ValidationResult.ACCEPTED
                    ? IntentHandlingResult.ACCEPTED
                    : IntentHandlingResult.REJECTED;
        }

        @Override
        public void onCancelAcknowledged(
                AgentProtocol.CancelAckData data,
                AgentProtocol.Envelope envelope,
                AgentSession.RequestContext cancelledRequest) {
            Logger.debug(
                    "Agent cancellation acknowledged for %s decision=%s",
                    agentId, data.decisionId());
        }

        @Override
        public void onProtocolRejected(
                AgentProtocolCodec.ProtocolFailure failure) {
            Logger.info(
                    "Agent protocol rejected for %s: %s - %s",
                    agentId, failure.reason(), failure.detail());
            AiTickContext context = activeAgentPollContext;
            if (context != null) {
                recordAgentEvent(context,
                        withSession(
                                AgentTrace.agentEvent(
                                        AgentTrace.EventType.PROTOCOL_ERROR,
                                        context.getRunId(),
                                        context.getFloorId(), agentId,
                                        context.getLogicalTick())
                                        .validation(failure.reason().name()),
                                agentSession));
            }
        }
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
     * 把巡视 MOVE primitive 转为 planner 目标；WAIT/TURN 使用自身位置，
     * 保证本地 lease 被后续 P3 复用并交给 planner 时不会因 null target 崩溃。
     */
    private static Position patrolTarget(
            PatrolController.PatrolDecision patrol, Position self) {
        if (patrol.kind()
                == PatrolController.PatrolDecision.Kind.MOVE
                && patrol.moveDirection() != null) {
            return new Position(
                    self.x + patrol.moveDirection().dx,
                    self.y + patrol.moveDirection().dy);
        }
        return self;
    }

    /**
     * 把巡视 primitive 转换为具体动作：MOVE 经 planner 寻路，
     * TURN 顺时针转 90°，WAIT 原地等待。P3/P4 共用，保证巡视状态机
     * 在任何控制源下持续推进（转向不会被 self-target lease 冻结）。
     */
    private List<Action> actionsForPatrol(
            PatrolController.PatrolDecision patrol,
            TETile[][] world, EntityManager entityMgr) {
        if (patrol.kind()
                == PatrolController.PatrolDecision.Kind.MOVE) {
            StrategicIntent intent = new StrategicIntent(
                    StrategicIntent.Goal.PATROL,
                    StrategicIntent.Strategy.PATROL,
                    patrolTarget(patrol, getPosition()));
            return ClassicalPlanner.translateBounded(
                    intent, getPosition(), getId(), world,
                    entityMgr, random, actionQueue.getHighWater());
        }
        if (patrol.kind()
                == PatrolController.PatrolDecision.Kind.TURN) {
            return List.of(new TurnAction(getFacing().clockwise()));
        }
        return List.of(new WaitAction());
    }

    /**
     * 用已提交的移动结果推进巡视状态：成功移动清零受阻计数，
     * 受阻移动增加计数（连续两次后控制器放弃目标）。
     */
    private void recordPatrolOutcome(ActionOutcome outcome) {
        if (outcome == null) {
            return;
        }
        boolean isMove = "MoveAction".equals(outcome.getActionType());
        if (!isMove) {
            return;
        }
        if (outcome.getResult() == Action.ActionResult.BLOCKED) {
            patrolController.onMoveBlocked(patrolState);
        } else if (outcome.getResult() == Action.ActionResult.SUCCESS) {
            patrolController.onMoveSucceeded(patrolState);
        }
    }

    /** 记录巡视状态机转换与目标选择的 v2 trace 事件。 */
    private void recordPatrolTrace(
            AiTickContext context,
            PatrolController.PatrolDecision patrol) {
        recordAgentEvent(context,
                withSession(
                        AgentTrace.agentEvent(
                                AgentTrace.EventType.PATROL_STATE_CHANGED,
                                context.getRunId(), context.getFloorId(),
                                agentId, context.getLogicalTick())
                                .observationSequence(
                                        latestObservation.getObservationSeq())
                                .patrol(patrolState.getMode().name(),
                                        patrolState.getTarget(),
                                        patrolState.getSelectionOrdinal()),
                        agentSession));
        if (patrol.transitionReason() != null
                && patrol.transitionReason().startsWith("TARGET_SELECTED")) {
            recordAgentEvent(context,
                    withSession(
                            AgentTrace.agentEvent(
                                    AgentTrace.EventType
                                            .PATROL_TARGET_SELECTED,
                                    context.getRunId(),
                                    context.getFloorId(), agentId,
                                    context.getLogicalTick())
                                    .observationSequence(
                                            latestObservation
                                                    .getObservationSeq())
                                    .patrol(patrolState.getMode().name(),
                                            patrolState.getTarget(),
                                            patrolState
                                                    .getSelectionOrdinal()),
                            agentSession));
        }
    }

    /**
     * 攻击冷却期间的降级动作：朝最新可见玩家方向移动一步（追击）。
     * 玩家不可见时返回 null。
     */
    private Action chaseActionTowardsVisiblePlayer(
            EntityManager entityMgr) {
        if (latestObservation == null) {
            return null;
        }
        VisibleEntity player = latestObservation.getVisiblePlayer();
        if (player == null) {
            return null;
        }
        Position self = getPosition();
        Position target = player.getPosition();
        Direction direction = null;
        if (target.x > self.x) {
            direction = Direction.RIGHT;
        } else if (target.x < self.x) {
            direction = Direction.LEFT;
        } else if (target.y > self.y) {
            direction = Direction.UP;
        } else if (target.y < self.y) {
            direction = Direction.DOWN;
        }
        if (direction == null) {
            return null; // 玩家与自身同格（不会发生）
        }
        return new MoveAction(direction, entityMgr);
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

    /**
     * Emits an explicit begin/end event whenever reflex control changes.
     */
    private void recordReflexTransition(
            AiTickContext context, boolean wasActive,
            String previousReason, String executionState) {
        boolean active = arbiter.isInReflexOverride();
        if (active == wasActive) {
            return;
        }
        AgentTrace.EventType eventType = active
                ? AgentTrace.EventType.REFLEX_OVERRIDE_STARTED
                : AgentTrace.EventType.REFLEX_OVERRIDE_ENDED;
        String reason = active
                ? arbiter.getOverrideReason() : previousReason;
        IntentLease lease = arbiter.getCurrentLease();
        String decisionId = lease == null
                ? currentDecisionId : lease.getDecisionId();
        AgentProtocol.DecisionSource source = lease == null
                ? currentDecisionSource : lease.getDecisionSource();
        AgentTrace.AgentEventBuilder event = AgentTrace.agentEvent(
                eventType, context.getRunId(), context.getFloorId(),
                agentId, context.getLogicalTick())
                .override(reason)
                .execution(executionState);
        if (latestObservation != null) {
            event.observationSequence(
                    latestObservation.getObservationSeq());
        }
        if (decisionId != null) {
            event.decision(decisionId,
                    source == null ? null : source.name());
        }
        recordAgentEvent(context, withSession(event, agentSession));
    }

    /**
     * Projects retained Session lifecycle events into the shared canonical sink.
     */
    private void recordSessionLifecycle(
            AiTickContext context, AgentSession session) {
        for (AgentSession.LifecycleEvent lifecycle
                : session.getLifecycleEventsAfter(
                lastSessionLifecycleSequence)) {
            lastSessionLifecycleSequence = lifecycle.getSequence();
            if (lifecycle.getType()
                    == AgentSession.LifecycleEventType.CANCELLATION_STARTED
                    && AgentSession.SupersedeReason.HARD_TIMEOUT.name()
                    .equals(lifecycle.getDetail())) {
                recordLifecycleEvent(
                        context, lifecycle,
                        AgentTrace.EventType.AGENT_HARD_TIMEOUT);
            }
            AgentTrace.EventType eventType =
                    canonicalType(lifecycle);
            if (eventType != null) {
                recordLifecycleEvent(context, lifecycle, eventType);
            }
        }
    }

    /** Maps an internal Session transition to its public trace semantic. */
    private static AgentTrace.EventType canonicalType(
            AgentSession.LifecycleEvent lifecycle) {
        return switch (lifecycle.getType()) {
            case CONNECTION_CONNECTING, CONNECTION_OPENED,
                    CONNECTION_LOST, CANCELLATION_GRACE_EXPIRED,
                    SESSION_CLOSED ->
                    AgentTrace.EventType.AGENT_SESSION_STATE_CHANGED;
            case REQUEST_STARTED -> AgentTrace.EventType.AGENT_REQUEST_SENT;
            case OBSERVATION_COALESCED ->
                    AgentTrace.EventType.OUTBOUND_MESSAGE_COALESCED;
            case AGENT_SLOW -> AgentTrace.EventType.AGENT_SLOW;
            case CANCELLATION_STARTED ->
                    AgentTrace.EventType.AGENT_CANCEL_SENT;
            case CANCELLATION_ACKNOWLEDGED ->
                    AgentTrace.EventType.AGENT_CANCEL_ACKED;
            case INBOUND_REJECTED -> isStaleLifecycle(lifecycle)
                    ? AgentTrace.EventType.STALE_RESPONSE_DROPPED
                    : AgentTrace.EventType.PROTOCOL_ERROR;
            case INBOUND_PROTOCOL_FATAL, TRANSPORT_CONTROL_FAILED ->
                    AgentTrace.EventType.PROTOCOL_ERROR;
            case OUTBOUND_LOW_PRIORITY_DROPPED,
                    OUTBOUND_CRITICAL_REJECTED ->
                    AgentTrace.EventType.OUTBOUND_MESSAGE_DROPPED;
            case REQUEST_COMPLETED, REQUEST_REJECTED -> null;
        };
    }

    /** Records one mapped lifecycle event with its transition-time state. */
    private void recordLifecycleEvent(
            AiTickContext context,
            AgentSession.LifecycleEvent lifecycle,
            AgentTrace.EventType eventType) {
        long eventTick = lifecycle.getLogicalTick() < 0
                ? context.getLogicalTick() : lifecycle.getLogicalTick();
        AgentTrace.AgentEventBuilder event = AgentTrace.agentEvent(
                eventType, context.getRunId(), context.getFloorId(),
                agentId, eventTick)
                .session(
                        lifecycle.getSessionEpoch(),
                        lifecycle.getRequestGeneration(),
                        lifecycle.getConnectionState().name(),
                        lifecycle.getRequestState().name())
                .validation(lifecycleValidation(lifecycle));
        if (lifecycle.getObservationSeq() != null) {
            event.observationSequence(lifecycle.getObservationSeq());
        }
        if (lifecycle.getDecisionId() != null) {
            event.decision(lifecycle.getDecisionId(), null);
        }
        String messageType = lifecycleMessageType(lifecycle);
        if (messageType != null) {
            event.message(messageType);
        }
        recordAgentEvent(context, event);
    }

    /** Returns a stable typed reason without copying diagnostic prose. */
    private static String lifecycleValidation(
            AgentSession.LifecycleEvent lifecycle) {
        if (isStaleLifecycle(lifecycle)) {
            return lifecycle.getDetail() != null
                    && lifecycle.getDetail().contains("duplicate")
                    ? "DUPLICATE_MESSAGE" : "STALE_IDENTITY";
        }
        return lifecycle.getType().name();
    }

    /** Identifies stale or duplicate inbound responses. */
    private static boolean isStaleLifecycle(
            AgentSession.LifecycleEvent lifecycle) {
        String detail = lifecycle.getDetail();
        return detail != null
                && (detail.contains("stale")
                || detail.contains("identity mismatch")
                || detail.contains("duplicate"));
    }

    /** Infers the stable wire message involved in a Session transition. */
    private static String lifecycleMessageType(
            AgentSession.LifecycleEvent lifecycle) {
        return switch (lifecycle.getType()) {
            case REQUEST_STARTED, OBSERVATION_COALESCED ->
                    AgentProtocol.MessageType.OBSERVATION.name();
            case CANCELLATION_STARTED ->
                    AgentProtocol.MessageType.CANCEL_REQUEST.name();
            case CANCELLATION_ACKNOWLEDGED ->
                    AgentProtocol.MessageType.CANCEL_ACK.name();
            case INBOUND_REJECTED ->
                    AgentProtocol.MessageType.SUBMIT_INTENT.name();
            case OUTBOUND_LOW_PRIORITY_DROPPED -> {
                String detail = lifecycle.getDetail();
                if (detail != null && detail.contains("heartbeat")) {
                    yield AgentProtocol.MessageType.HEARTBEAT.name();
                }
                yield AgentProtocol.MessageType.WORLD_EVENT.name();
            }
            case OUTBOUND_CRITICAL_REJECTED -> {
                String detail = lifecycle.getDetail();
                if (detail != null && detail.contains("feedback")) {
                    yield AgentProtocol.MessageType.ACTION_FEEDBACK.name();
                }
                if (detail != null && detail.contains("cancel")) {
                    yield AgentProtocol.MessageType.CANCEL_REQUEST.name();
                }
                yield AgentProtocol.MessageType.OBSERVATION.name();
            }
            default -> null;
        };
    }

    /** Adds the current Session tuple to an Enemy-owned event. */
    private static AgentTrace.AgentEventBuilder withSession(
            AgentTrace.AgentEventBuilder event, AgentSession session) {
        if (session == null) {
            return event;
        }
        return event.session(
                session.getSessionEpoch(),
                session.getRequestGeneration(),
                session.getConnectionState().name(),
                session.getRequestState().name());
    }

    /** Builds and records one canonical event without affecting gameplay. */
    private static void recordAgentEvent(
            AiTickContext context,
            AgentTrace.AgentEventBuilder event) {
        try {
            context.getTraceSink().record(event.build());
        } catch (RuntimeException exception) {
            Logger.error(
                    "Agent trace record failed: %s",
                    exception.getMessage());
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

    /** 最大生命值；正数且不会因受伤改变。 */
    public int getMaxHp() {
        return maxHp;
    }

    /** 读档恢复最大生命值；要求为正，并把当前 HP 收敛到新上限。 */
    public void setMaxHp(int maxHp) {
        if (maxHp <= 0) {
            throw new IllegalArgumentException("maxHp must be > 0");
        }
        this.maxHp = maxHp;
        this.hp = Math.min(hp, maxHp);
    }

    /** 设置当前生命值并收敛到 [0, maxHp]。 */
    public void setHp(int hp) {
        this.hp = Math.max(0, Math.min(maxHp, hp));
    }

    public Facing getFacing() {
        return facing;
    }

    public void setFacing(Facing facing) {
        this.facing = Objects.requireNonNull(facing, "facing");
    }

    /**
     * 用独立确定性随机流初始化生成朝向，不扰动其他随机序列。
     * 读档时通过 {@link #setFacing(Facing)} 恢复保存值。
     */
    public void initializeFacing(String seed, int floorId) {
        Random r = new Random((seed + "_F" + floorId
                + "_facing_" + agentId).hashCode());
        Facing[] values = Facing.values();
        this.facing = values[r.nextInt(values.length)];
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

    /** 独立攻击间隔（tick）；必须为正。 */
    public int getAttackInterval() {
        return attackInterval;
    }

    public void setAttackInterval(int attackInterval) {
        if (attackInterval <= 0) {
            throw new IllegalArgumentException(
                    "attackInterval must be > 0");
        }
        this.attackInterval = attackInterval;
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

    public String getWorldId() {
        return worldId;
    }

    public void setWorldId(String worldId) {
        this.worldId = worldId;
    }

    public VisionMode getVisionMode() {
        return visionMode;
    }

    public void setVisionMode(VisionMode visionMode) {
        this.visionMode = Objects.requireNonNull(visionMode, "visionMode");
    }

    /** 当前巡视状态（可保存；读档原样恢复）。 */
    public PatrolState getPatrolState() {
        return patrolState;
    }

    /** 读档恢复巡视状态；null 时保留初始空状态。 */
    public void setPatrolState(PatrolState patrolState) {
        if (patrolState == null) {
            return;
        }
        this.patrolState.setMode(patrolState.getMode());
        this.patrolState.setTarget(patrolState.getTarget());
        this.patrolState.setDwellActionsRemaining(
                patrolState.getDwellActionsRemaining());
        this.patrolState.setScanTurnsRemaining(
                patrolState.getScanTurnsRemaining());
        this.patrolState.setBlockedAttempts(
                patrolState.getBlockedAttempts());
        this.patrolState.setSelectionOrdinal(
                patrolState.getSelectionOrdinal());
    }

    /** 设置巡视选择使用的世界 seed 派生；由 Game 在创建/读档时提供。 */
    public void setPatrolSeedKey(long patrolSeedKey) {
        this.patrolSeedKey = patrolSeedKey;
    }

    /** 换层时重置巡视状态。 */
    public void resetPatrolState() {
        this.patrolState.reset();
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
            Position playerPos, int extraCount, int floorId,
            GameConfig config) {
        List<Enemy> enemies = new ArrayList<>();
        Random countRandom = new Random((seed + "_enemy_count").hashCode());
        int count = config.enemyBaseCount + extraCount + countRandom.nextInt(3);

        for (int i = 0; i < count; i++) {
            Random random = new Random((seed + "_enemy_" + i).hashCode());
            Enemy enemy = new Enemy(new Position(0, 0), Tileset.ENEMY,
                    config.enemyHp, config.enemySightRange, config.enemyMoveInterval,
                    config.enemyAttack, config.enemyDamageVariance,
                    random, "enemy-" + i,
                    config.agentActionQueueLowWater,
                    config.agentActionQueueHighWater);
            enemy.setPerceptionEnabled(true);   // 游戏运行时，默认开启感知模式
            enemy.initializeFacing(seed, floorId);
            enemy.setAttackInterval(config.enemyAttackInterval);
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
