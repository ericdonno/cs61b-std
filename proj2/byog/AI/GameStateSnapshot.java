package byog.AI;

import byog.TileEngine.TETile;
import byog.lab5.Position;

/**
 * 游戏状态快照。某时刻游戏世界的"截屏"，供 AI 决策使用。阶段二简化版（3 字段）。
 */
public class GameStateSnapshot {

    private final TETile[][] world;
    private final Position playerPosition;
    private final Position enemyPosition;
    private final int enemyId;

    public GameStateSnapshot(TETile[][] world, Position playerPosition,
                             Position enemyPosition, int enemyId) {
        this.world = world;
        this.playerPosition = playerPosition;
        this.enemyPosition = enemyPosition;
        this.enemyId = enemyId;
    }

    public TETile[][] getWorld() {
        return world;
    }

    public Position getPlayerPosition() {
        return playerPosition;
    }

    public Position getEnemyPosition() {
        return enemyPosition;
    }

    public int getEnemyId() {
        return enemyId;
    }
}
