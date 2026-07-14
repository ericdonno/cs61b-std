package byog.Core;

import byog.Helper.Logger;
import byog.Helper.MathHelper;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.Random;

/**
 * 规则 AI 大脑。纯手写规则决策，作为 LLM 不可用时的保底 AI。
 * 玩家在视野内（曼哈顿距离 ≤ sightRange）则追击，否则巡逻。
 */
public class RuleBasedBrain implements EnemyBrain {

    private int sightRange;
    private Random random;

    public RuleBasedBrain(int sightRange, Random random) {
        this.sightRange = sightRange;
        this.random = random;
    }

    /**
     * 根据游戏状态快照生成战略意图。
     * @param state 游戏状态快照
     * @return CHASE（视野内）或 PATROL（视野外）
     */
    @Override
    public StrategicIntent think(GameStateSnapshot state) {
        Position enemyPos = state.getEnemyPosition();
        Position playerPos = state.getPlayerPosition();

        int dist = MathHelper.manhattanDistance(enemyPos, playerPos);

        if (dist == 1) {
            Logger.debug("Enemy#%d RuleBasedBrain: dist=%d → ATTACK",
                    state.getEnemyId(), dist);
            return new StrategicIntent(StrategicIntent.Goal.ATTACK_PLAYER,
                    StrategicIntent.Strategy.ATTACK, playerPos);
        } else if (dist <= sightRange) {
            Logger.debug("Enemy#%d RuleBasedBrain: dist=%d <= sightRange=%d → CHASE",
                    state.getEnemyId(), dist, sightRange);
            return new StrategicIntent(StrategicIntent.Goal.CHASE,
                    StrategicIntent.Strategy.CHASE, playerPos);
        } else {
            Position patrolTarget = generateRandomPatrolPos(state.getWorld(), enemyPos);
            Logger.debug("Enemy#%d RuleBasedBrain: dist=%d > sightRange=%d → PATROL target=(%d,%d)",
                    state.getEnemyId(), dist, sightRange,
                    patrolTarget.x, patrolTarget.y);
            return new StrategicIntent(StrategicIntent.Goal.PATROL,
                    StrategicIntent.Strategy.PATROL, patrolTarget);
        }
    }

    /**
     * 在敌人周围随机生成一个可通行的巡逻目标点。
     * 曼哈顿距离在 3~8 格范围内，最多重试 20 次，失败则原地不动。
     */
    private Position generateRandomPatrolPos(TETile[][] world, Position enemyPos) {
        int w = world.length;
        int h = world[0].length;
        int minDist = 3;
        int maxDist = 8;

        for (int attempt = 0; attempt < 20; attempt++) {
            int dx = random.nextInt(maxDist * 2 + 1) - maxDist;
            int dy = random.nextInt(maxDist * 2 + 1) - maxDist;
            int nx = enemyPos.x + dx;
            int ny = enemyPos.y + dy;

            if (nx < 0 || nx >= w || ny < 0 || ny >= h) {
                continue;
            }

            int dist = Math.abs(dx) + Math.abs(dy);
            if (dist < minDist) {
                continue;
            }

            if (world[nx][ny].description().equals(Tileset.FLOOR.description())) {
                return new Position(nx, ny);
            }
        }

        return new Position(enemyPos.x, enemyPos.y);
    }
}
