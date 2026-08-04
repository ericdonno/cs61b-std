package byog.AI;

import byog.Action.Action;
import byog.Action.AttackAction;
import byog.Action.MoveAction;
import byog.Action.WaitAction;
import byog.Common.Direction;
import byog.Entity.Entity;
import byog.Entity.EntityManager;
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
     * Production planner entry point. It returns only the prefix that
     * can fit in the cross-tick action buffer.
     *
     * <p>The adjacent ATTACK case emits the atomic attack directly. The
     * legacy planner relied on same-tick retries to skip the blocked move into
     * the player's occupied tile; the AI tick loop intentionally permits only
     * one action attempt per cooldown.</p>
     */
    public static List<Action> translateBounded(
            StrategicIntent intent, Position enemyPos, int enemyId,
            TETile[][] world, EntityManager entityMgr, Random random,
            int maxActions) {
        List<Action> actions = new ArrayList<>();
        if (maxActions <= 0) {
            return actions;
        }

        Position targetPos = intent.getTargetPosition();
        if (intent.getStrategy() == StrategicIntent.Strategy.ATTACK
                && targetPos != null
                && Math.abs(targetPos.x - enemyPos.x)
                + Math.abs(targetPos.y - enemyPos.y) == 1) {
            Direction attackDir = Direction.fromDelta(
                    targetPos.x - enemyPos.x, targetPos.y - enemyPos.y);
            actions.add(new AttackAction(entityMgr, attackDir, random));
            return actions;
        }

        List<Action> translated = translate(intent, enemyPos, enemyId,
                world, entityMgr, random);
        int end = Math.min(maxActions, translated.size());
        actions.addAll(translated.subList(0, end));
        return actions;
    }

    /**
     * 将战略意图翻译为动作序列。
     * @param intent    AI 大脑的战略意图
     * @param enemyPos  敌人当前位置
     * @param world     游戏世界瓦片数组
     * @param entityMgr 实体管理器，用于碰撞检测
     * @param random    随机数生成器，用于攻击伤害等随机结算
     * @return 待执行的动作列表；不可达时返回明确的 Wait，不做随机抖动
     */
    public static List<Action> translate(StrategicIntent intent, Position enemyPos,
                                              int enemyId, TETile[][] world,
                                              EntityManager entityMgr, Random random) {
        List<Action> actions = new ArrayList<>();
        Position targetPos = intent.getTargetPosition();
        if (targetPos == null) {
            // 意图没有明确目标（例如等待类 primitive）：明确返回等待，不崩溃。
            actions.add(new WaitAction());
            return actions;
        }

        List<Position> path = BFSPathfinder.findPath(enemyPos, targetPos, world);

        if (path.isEmpty()) {
            // 不可达：明确返回等待，禁止随机方向占位造成不可解释抖动。
            actions.add(new WaitAction());
            return actions;
        }

        // 将路径上的每步转换为 MoveAction
        Position prev = enemyPos;
        for (Position cur : path) {
            Direction d = Direction.fromDelta(cur.x - prev.x, cur.y - prev.y);
            actions.add(new MoveAction(d, entityMgr));
            prev = cur;
        }

        // 根据 Strategy 追加到达目标后的行为（原地等待，不改变朝向）
        StrategicIntent.Strategy strategy = intent.getStrategy();
        if (strategy == StrategicIntent.Strategy.AMBUSH) {
            for (int i = 0; i < 3; i++) {
                actions.add(new WaitAction());
            }
        } else if (strategy == StrategicIntent.Strategy.GUARD) {
            actions.add(new WaitAction());
        } else if (strategy == StrategicIntent.Strategy.ATTACK) {
            Direction attackDir = Direction.fromDelta(
                    targetPos.x - enemyPos.x, targetPos.y - enemyPos.y);
            if (attackDir != null) {
                actions.add(new AttackAction(entityMgr, attackDir, random));
            } else {
                actions.add(new WaitAction());
            }
        }

        Logger.debug("Enemy#%d ClassicalPlanner: pathLength=%d, strategy=%s, actions=%d",
                enemyId, path.size(), strategy, actions.size());

        return actions;
    }
}
