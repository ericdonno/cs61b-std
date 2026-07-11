package byog.Core;

import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.Random;

public class Enemy extends Entity {
    private ActionQueue actionQueue;
    private int hp;
    private int sightRange;
    private Random random;

    public Enemy(Position position, Random random) {
        this(position, Tileset.ENEMY, 10, 7, random);
    }

    public Enemy(Position position, TETile tile, int hp, int sightRange, Random random) {
        super(position, tile);
        this.hp = hp;
        this.sightRange = sightRange;
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
     * 更新敌人 AI 状态。队列为空时生成随机移动动作，然后执行队首动作。
     * @param world 游戏世界瓦片数组
     */
    public void updateAI(TETile[][] world) {
        if (actionQueue.needRefill()) {  // 鲁棒作用
            actionQueue.enqueue(new MoveAction(randomDirection()));
        }
        Action action = actionQueue.poll();
        if (action != null) {
            action.execute(world, this);
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
}
