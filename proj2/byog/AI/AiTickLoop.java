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

    public static void run(AiTickContext context, TETile[][] world,
                           EntityManager entityMgr, Player player) {
        List<Enemy> enemies = snapshot(entityMgr);
        for (Enemy enemy : enemies) {
            enemy.pollAgentMessages(context);
        }
        for (Enemy enemy : enemies) {
            enemy.executeOneAction(context, world, entityMgr);
        }

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
