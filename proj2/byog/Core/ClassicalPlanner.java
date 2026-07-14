package byog.Core;

import byog.Helper.Logger;
import byog.TileEngine.TETile;
import byog.lab5.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 经典规划器。将 AI 大脑的战略意图翻译为具体的移动动作序列。
 * 通过 BFS 寻路 + Direction 转换，把"想去哪"变成"一步一步怎么走"。
 */
public class ClassicalPlanner {

    /**
     * 将战略意图翻译为 MoveAction 序列。
     * @param intent    AI 大脑的战略意图
     * @param enemyPos  敌人当前位置
     * @param world     游戏世界瓦片数组
     * @param entityMgr 实体管理器，用于碰撞检测
     * @param random    随机数生成器，用于不可达时生成占位动作
     * @return 待执行的动作列表
     */
    public static List<Action> translate(StrategicIntent intent, Position enemyPos,
                                              int enemyId, TETile[][] world,
                                              EntityManager entityMgr, Random random) {
        List<Action> actions = new ArrayList<>();
        Position targetPos = intent.getTargetPosition();

        List<Position> path = BFSPathfinder.findPath(enemyPos, targetPos, world);

        if (path.isEmpty()) {
            // 不可达：插入一个随机方向占位动作，防止敌人卡死
            Direction[] directions = Direction.values();
            Direction fallback = directions[random.nextInt(directions.length)];
            actions.add(new MoveAction(fallback, entityMgr));
            return actions;
        }

        // 将路径上的每步转换为 MoveAction
        Position prev = enemyPos;
        for (Position cur : path) {
            Direction d = Direction.fromDelta(cur.x - prev.x, cur.y - prev.y);
            actions.add(new MoveAction(d, entityMgr));
            prev = cur;
        }

        // 根据 Strategy 追加到达目标后的行为
        StrategicIntent.Strategy strategy = intent.getStrategy();
        if (strategy == StrategicIntent.Strategy.AMBUSH) {
            for (int i = 0; i < 3; i++) {
                actions.add(new MoveAction(null, entityMgr));
            }
        } else if (strategy == StrategicIntent.Strategy.GUARD) {
            actions.add(new MoveAction(null, entityMgr));
        } else if (strategy == StrategicIntent.Strategy.ATTACK) {
            Direction attackDir = Direction.fromDelta(
                    targetPos.x - enemyPos.x, targetPos.y - enemyPos.y);
            if (attackDir != null) {
                actions.add(new AttackAction(entityMgr, attackDir, random));
            } else {
                actions.add(new MoveAction(null, entityMgr));
            }
        }

        Logger.debug("Enemy#%d ClassicalPlanner: pathLength=%d, strategy=%s, actions=%d",
                enemyId, path.size(), strategy, actions.size());

        return actions;
    }
}
