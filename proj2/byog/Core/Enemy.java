package byog.Core;

import byog.Helper.Logger;
import byog.Helper.MathHelper;
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

    public Enemy(Position position, TETile tile, int hp, int sightRange,
                 int moveInterval, int attackDamage, int damageVariance, Random random) {
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
    }

    /**
     * 更新敌人 AI 状态。Brain 决策 → Planner 翻译 → 动作队列消费。
     * @param world 游戏世界瓦片数组
     * @param entityMgr 实体管理器，用于碰撞检测和空间索引
     * @param player 玩家，用于构建快照供 Brain 决策
     */
    public void updateAI(TETile[][] world, EntityManager entityMgr, Player player) {
        tickCounter++;
        if (tickCounter >= moveInterval) {
            tickCounter = 0;

            // 每 动作tick 评估当前局势
            GameStateSnapshot snapshot = new GameStateSnapshot(world,
                    player.getPosition(), this.getPosition(), this.getId());
            StrategicIntent intent = brain.think(snapshot);
            StrategicIntent.Strategy newStrategy = intent.getStrategy();

            // 策略切换 → 立即清空旧队列，重新规划
            if (newStrategy != currentStrategy) {
                Logger.info("Enemy#%d Strategy: %s → %s",
                        this.getId(), currentStrategy, newStrategy);
                actionQueue.clear();
                currentStrategy = newStrategy;
                List<Action> actions = ClassicalPlanner.translate(intent,
                        this.getPosition(), this.getId(), world, entityMgr, random);
                actionQueue.enqueueAll(actions);
            } else if (actionQueue.needRefill()) {
                // 同策略续补
                List<Action> actions = ClassicalPlanner.translate(intent,
                        this.getPosition(), this.getId(), world, entityMgr, random);
                actionQueue.enqueueAll(actions);
            }

            for (int i = 0; i < MAX_RETRY; i++) {
                Action action = actionQueue.poll();
                if (action == null) {
                    break;
                }
                // 行动
                if (action.execute(world, this) == Action.ActionResult.SUCCESS) {
                    break;
                }
            }
        }
    }

    public int getHp() {
        return hp;
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
                    config.enemyAttack, config.enemyDamageVariance, random);
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
