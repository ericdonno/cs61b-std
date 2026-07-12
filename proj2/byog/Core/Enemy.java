package byog.Core;

import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class Enemy extends Entity {
    private static final int MAX_RETRY = 4;
    private ActionQueue actionQueue;
    private int hp;
    private int sightRange;
    private Random random;
    private int moveInterval;
    private int tickCounter;

    public Enemy(Position position, Random random) {
        this(position, Tileset.ENEMY, 10, 7, 5, random);
    }

    public Enemy(Position position, TETile tile, int hp, int sightRange, int moveInterval, Random random) {
        super(position, tile);
        this.hp = hp;
        this.sightRange = sightRange;
        this.moveInterval = moveInterval;
        this.tickCounter = 0;
        this.random = random;
        this.actionQueue = new ActionQueue();
    }

    /**
     * 随机选择一个方向。
     * @return 随机方向
     */
    private Direction randomDirection() {
        Direction[] directions = Direction.values();
        int index = random.nextInt(directions.length);
        return directions[index];
    }

    /**
     * 更新敌人 AI 状态。从动作队列取动作执行，被阻挡时立即补充并重试。
     * @param world 游戏世界瓦片数组
     * @param entityMgr 实体管理器，用于碰撞检测和空间索引
     */
    public void updateAI(TETile[][] world, EntityManager entityMgr) {
        tickCounter++;
        if (tickCounter >= moveInterval) {
            tickCounter = 0;
            if (actionQueue.needRefill()) {
                actionQueue.enqueue(new MoveAction(randomDirection(), entityMgr));
            }
            for (int i = 0; i < MAX_RETRY; i++) {
                Action action = actionQueue.poll();
                if (action == null) {
                    break;
                }
                if (action.execute(world, this) == Action.ActionResult.SUCCESS) {
                    break;
                }
                if (actionQueue.needRefill()) {
                    actionQueue.enqueue(new MoveAction(randomDirection(), entityMgr));     // 目前的简单闲逛逻辑
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

    public ActionQueue getActionQueue() {
        return actionQueue;
    }

    /**
     * 在世界中随机生成多个敌人。
     * @param world 游戏世界
     * @param seed 种子
     * @param playerPos 玩家位置，用于距离检查
     * @return 生成的敌人列表
     */
    public static List<Enemy> spawnEnemies(TETile[][] world, String seed, Position playerPos) {
        List<Enemy> enemies = new ArrayList<>();
        Random countRandom = new Random((seed + "_enemy_count").hashCode());
        int count = 3 + countRandom.nextInt(3);

        for (int i = 0; i < count; i++) {
            Random random = new Random((seed + "_enemy_" + i).hashCode());
            Enemy enemy = new Enemy(new Position(0, 0), random);
            Entity.initEntity(enemy, world, seed + "_pos_" + i);

            while (distance(enemy.getPosition(), playerPos) < 5) {
                Entity.initEntity(enemy, world, seed + "_pos_" + i + "_retry");
            }

            enemies.add(enemy);
        }
        return enemies;
    }

    private static int distance(Position p1, Position p2) {
        return Math.abs(p1.x - p2.x) + Math.abs(p1.y - p2.y);
    }
}
