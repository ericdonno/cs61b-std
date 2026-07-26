package byog.Entity;

import byog.Action.Action;
import byog.Action.ActionOutcome;
import byog.Action.ActionQueue;
import byog.AI.AiTickContext;
import byog.AI.BFSPathfinder;
import byog.AI.ClassicalPlanner;
import byog.AI.EnemyBrain;
import byog.AI.GameStateSnapshot;
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
    private int actionsExecutedForDecision;
    private boolean agentRuntimeClosed;

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
        // Legacy Phase 0/1 harness seam.
        // Production Game must not add new Phase 2 logic here.
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
     * Phase 2 production stage 1. Step 2.2 has no AgentSession yet, so this is
     * deliberately a non-blocking no-op.
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
     * Phase 2 production stage 2. At most one action is attempted whenever the
     * original cooldown becomes due.
     */
    public void executeOneAction(AiTickContext context,
                                 TETile[][] world,
                                 EntityManager entityMgr) {
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

        // Preserve the Phase 1 cooldown order: increment first, then compare.
        tickCounter++;
        if (tickCounter < moveInterval) {
            return;
        }
        tickCounter = 0;

        // Game primes observations after spawn/load. A missing observation is a
        // cold-start boundary, never permission to read the live Player object.
        if (latestObservation == null) {
            return;
        }

        StrategicIntent intent = brain.thinkFromObservation(latestObservation);
        StrategicIntent.Strategy newStrategy = intent.getStrategy();
        boolean strategyChanged = newStrategy != currentStrategy;

        if (strategyChanged) {
            Logger.info("Enemy#%d Strategy: %s → %s",
                    this.getId(), currentStrategy, newStrategy);
            currentStrategy = newStrategy;
            startLocalDecision(context);
            List<Action> actions = ClassicalPlanner.translateBounded(
                    intent, getPosition(), getId(), world, entityMgr, random,
                    actionQueue.getHighWater());
            actionQueue.replaceWithBoundedPrefix(actions);
        } else if (actionQueue.needRefill()) {
            if (currentDecisionId == null) {
                startLocalDecision(context);
            }
            List<Action> actions = ClassicalPlanner.translateBounded(
                    intent, getPosition(), getId(), world, entityMgr, random,
                    actionQueue.remainingCapacity());
            actionQueue.appendBounded(actions);
        }

        Action action = actionQueue.poll();
        if (action == null) {
            return;
        }

        Position before = copyPosition(getPosition());
        Action.ActionResult result = action.execute(world, this);
        actionsExecutedForDecision++;
        pendingAction = new PendingAction(
                context.getRunId(), context.getFloorId(),
                context.getLogicalTick(), currentDecisionId,
                actionsExecutedForDecision, action.getClass().getSimpleName(),
                result, before, AgentProtocol.DecisionSource.LOCAL_FALLBACK,
                null);
    }

    /**
     * Phase 2 production stage 4. The entity index must already reflect the
     * action executed in stage 2.
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
        if (entityMgr.findEntityAt(getPosition()) != this) {
            throw new IllegalStateException(
                    "collectAgentUpdates must run after the world commit barrier");
        }
        if (pendingAction != null) {
            ensureMatchingCollectContext(context, pendingAction);
        }

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
     * Idempotent lifecycle seam. Step 2.2 owns no socket or IO thread.
     */
    public void closeAgentRuntime() {
        if (agentRuntimeClosed) {
            return;
        }
        agentRuntimeClosed = true;
        actionQueue.clear();
        pendingAction = null;
    }

    private void startLocalDecision(AiTickContext context) {
        currentDecisionId = "local-" + agentId + "-tick-"
                + context.getLogicalTick();
        actionsExecutedForDecision = 0;
    }

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
