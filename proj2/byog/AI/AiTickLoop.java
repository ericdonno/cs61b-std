package byog.AI;

import byog.Entity.Enemy;
import byog.Entity.Entity;
import byog.Entity.EntityManager;
import byog.Entity.Player;
import byog.TileEngine.TETile;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared production coordinator for one AI tick.
 */
public final class AiTickLoop {
    private AiTickLoop() {
    }

    /**
     * 按固定顺序完成一次 AI tick，并在动作执行与反馈收集之间提交实体变更。
     */
    public static void run(AiTickContext context, TETile[][] world,
                           EntityManager entityMgr, Player player) {
        List<Enemy> enemies = snapshot(entityMgr);   // 获取敌人快照
        for (Enemy enemy : enemies) {
            enemy.pollAgentMessages(context);    // poll
        }
        for (Enemy enemy : enemies) {
            enemy.executeOneAction(context, world, entityMgr);  // execute
        }

        // commit
        entityMgr.flushPendingChanges();
        entityMgr.removeDeadEntities();

        for (Enemy enemy : enemies) {
            if (enemy.isAlive()) {
                enemy.collectAgentUpdates(context, world, entityMgr, player);
            }
        }
        for (Enemy enemy : enemies) {
            if (!enemy.isAlive()) {
                enemy.closeAgentRuntime();
            }
        }
    }

    /**
     * 创建当前实体集合中的敌人快照，避免遍历期间的实体变更影响本次调度。
     */
    public static List<Enemy> snapshot(EntityManager entityMgr) {
        List<Enemy> enemies = new ArrayList<>();
        for (Entity entity : entityMgr.getAllEntities()) {
            if (entity instanceof Enemy enemy) {
                enemies.add(enemy);
            }
        }
        return enemies;
    }
}
