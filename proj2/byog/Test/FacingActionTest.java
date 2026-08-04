package byog.Test;

import byog.Action.Action;
import byog.Action.ActionOutcome;
import byog.Action.AttackAction;
import byog.Action.MoveAction;
import byog.Action.TurnAction;
import byog.Action.WaitAction;
import byog.AI.AiTickContext;
import byog.AI.AiTickLoop;
import byog.Common.Difficulty;
import byog.Common.Direction;
import byog.Common.Facing;
import byog.Entity.Enemy;
import byog.Entity.EntityManager;
import byog.Entity.Player;
import byog.IO.GameConfig;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;
import org.junit.Test;

import java.util.List;
import java.util.Properties;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 敌人最大 HP、四向朝向与原子转向动作。
 *
 * <p>覆盖 Spec 朝向验收：固定 seed 生成朝向可重复且不扰动其他随机序列；
 * 成功/受阻移动都更新朝向；命中/落空攻击都更新朝向；Turn 消耗正常
 * action opportunity、Wait 不改朝向；存档恢复 current/max HP 与 Facing。</p>
 */
public class FacingActionTest {

    private GameConfig config() {
        return new GameConfig(Difficulty.BALANCED, new Properties());
    }

    private TETile[][] openWorld(int w, int h) {
        TETile[][] world = new TETile[w][h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                world[x][y] = Tileset.FLOOR;
            }
        }
        return world;
    }

    // ---------- P25-FACING-01 固定 seed 生成朝向 ----------

    @Test
    public void spawnedFacingIsDeterministicAndIsolated() {
        TETile[][] w1 = openWorld(24, 16);
        List<Enemy> first = Enemy.spawnEnemies(w1, "seed-42",
                new Position(12, 8), 0, 1, config());
        TETile[][] w2 = openWorld(24, 16);
        List<Enemy> second = Enemy.spawnEnemies(w2, "seed-42",
                new Position(12, 8), 0, 1, config());

        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals("enemy " + i + " facing must repeat",
                    first.get(i).getFacing(), second.get(i).getFacing());
            assertEquals("enemy " + i + " position must repeat",
                    first.get(i).getPosition(), second.get(i).getPosition());
            assertEquals("maxHp must equal config hp",
                    config().enemyHp, first.get(i).getMaxHp());
        }
        // 不同楼层 id 使用不同随机流，但仍是确定性的
        TETile[][] w3 = openWorld(24, 16);
        List<Enemy> third = Enemy.spawnEnemies(w3, "seed-42",
                new Position(12, 8), 0, 2, config());
        for (int i = 0; i < first.size(); i++) {
            assertEquals("floor-2 facing must repeat across runs",
                    third.get(i).getFacing(),
                    Enemy.spawnEnemies(openWorld(24, 16), "seed-42",
                            new Position(12, 8), 0, 2, config())
                            .get(i).getFacing());
        }
    }

    // ---------- P25-FACING-02 成功/受阻移动都更新朝向 ----------

    @Test
    public void moveUpdatesFacingOnSuccessAndBlock() {
        TETile[][] world = openWorld(7, 7);
        EntityManager em = new EntityManager();
        Enemy enemy = new Enemy(new Position(3, 3), Tileset.ENEMY,
                10, 5, 5, 5, 2, new Random(1), "guard-a");
        em.addEntity(enemy);
        enemy.setFacing(Facing.NORTH);

        // 成功移动：向东
        Action.ActionResult ok = new MoveAction(Direction.RIGHT, em)
                .execute(world, enemy);
        assertEquals(Action.ActionResult.SUCCESS, ok);
        assertEquals(new Position(4, 3), enemy.getPosition());
        assertEquals(Facing.EAST, enemy.getFacing());

        // 受阻移动：向西，目标格被玩家占用
        Player blocker = new Player(new Position(3, 3), config());
        em.addEntity(blocker);
        Action.ActionResult blocked = new MoveAction(Direction.LEFT, em)
                .execute(world, enemy);
        assertEquals(Action.ActionResult.BLOCKED, blocked);
        assertEquals("blocked move must not change position",
                new Position(4, 3), enemy.getPosition());
        assertEquals("blocked move must still update facing",
                Facing.WEST, enemy.getFacing());
    }

    // ---------- P25-FACING-03 命中/落空攻击都更新朝向 ----------

    @Test
    public void attackUpdatesFacingOnHitAndMiss() {
        TETile[][] world = openWorld(7, 7);
        EntityManager em = new EntityManager();
        Enemy enemy = new Enemy(new Position(3, 3), Tileset.ENEMY,
                10, 5, 5, 10, 2, new Random(1), "guard-a");
        em.addEntity(enemy);
        Player player = new Player(new Position(4, 3), config());
        em.addEntity(player);
        enemy.setFacing(Facing.NORTH);

        // 命中：向东攻击相邻玩家
        Action.ActionResult hit = new AttackAction(em, Direction.RIGHT,
                new Random(1)).execute(world, enemy);
        assertEquals(Action.ActionResult.DAMAGE, hit);
        assertEquals(Facing.EAST, enemy.getFacing());
        assertTrue(player.getHp() < config().playerHp);

        // 落空：向西攻击无目标
        enemy.setFacing(Facing.NORTH);
        Action.ActionResult miss = new AttackAction(em, Direction.LEFT,
                new Random(2)).execute(world, enemy);
        assertEquals(Action.ActionResult.BLOCKED, miss);
        assertEquals("missed attack must still update facing",
                Facing.WEST, enemy.getFacing());
    }

    // ---------- P25-FACING-04 Turn/Wait ----------

    @Test
    public void turnChangesFacingAndWaitDoesNot() {
        TETile[][] world = openWorld(5, 5);
        EntityManager em = new EntityManager();
        Enemy enemy = new Enemy(new Position(2, 2), Tileset.ENEMY,
                10, 5, 5, 5, 2, new Random(1), "guard-a");
        em.addEntity(enemy);
        enemy.setFacing(Facing.NORTH);

        new TurnAction(Facing.SOUTH).execute(world, enemy);
        assertEquals(Facing.SOUTH, enemy.getFacing());

        Facing before = enemy.getFacing();
        new WaitAction().execute(world, enemy);
        assertEquals("Wait must not change facing", before, enemy.getFacing());
    }

    @Test
    public void turnDoesNotBypassActionQueueOrCadence() {
        TETile[][] world = openWorld(5, 5);
        EntityManager em = new EntityManager();
        Enemy enemy = new Enemy(new Position(2, 2), Tileset.ENEMY,
                10, 5, 5, 5, 2, new Random(1), "guard-a");
        em.addEntity(enemy);
        int queueBefore = enemy.getActionQueue().size();

        new TurnAction(Facing.EAST).execute(world, enemy);

        // Turn 只改朝向：不直接写 queue、不推进 tickCounter（由执行层统一管理）
        assertEquals(queueBefore, enemy.getActionQueue().size());
        assertEquals(Facing.EAST, enemy.getFacing());
    }

    @Test
    public void aiTickLoopExecutesAtMostOneActionPerCooldown() {
        TETile[][] world = openWorld(9, 9);
        // 玩家放在 enemy 视野外（距离 > sightRange）
        EntityManager em = new EntityManager();
        Enemy enemy = new Enemy(new Position(1, 1), Tileset.ENEMY,
                10, 5, 1, 5, 2, new Random(1), "guard-a");
        enemy.setPerceptionEnabled(true);
        em.addEntity(enemy);
        Player player = new Player(new Position(8, 8), config());
        em.addEntity(player);

        AiTickContext first = new AiTickContext("run-1", 1, 0);
        AiTickLoop.run(first, world, em, player);   // 冷启动：产生 committed observation
        AiTickContext second = new AiTickContext("run-1", 1, 1);
        AiTickLoop.run(second, world, em, player);  // 该 tick 至多执行一个 action

        ActionOutcome outcome = enemy.consumeLastActionOutcome();
        assertNotNull("one action must have been executed", outcome);
        assertEquals("exactly one action per cooldown",
                1, outcome.getActionIndex());
        assertNull("no second action in the same tick",
                enemy.consumeLastActionOutcome());
    }

    // ---------- 独立攻击冷却 ----------

    @Test
    public void attackCooldownDowngradesToChaseAndRecovers() {
        TETile[][] world = openWorld(9, 9);
        EntityManager em = new EntityManager();
        Enemy enemy = new Enemy(new Position(4, 4), Tileset.ENEMY,
                20, 5, 1, 10, 3, new Random(1), "guard-a");
        enemy.setPerceptionEnabled(true);
        enemy.setAttackInterval(5);
        em.addEntity(enemy);
        Player player = new Player(new Position(5, 4), config()); // 东边相邻
        em.addEntity(player);

        // 冷启动：产生 committed observation
        AiTickLoop.run(new AiTickContext("run-1", 1, 0), world, em, player);
        // 首次行动：冷却为 0 → 正常攻击
        AiTickLoop.run(new AiTickContext("run-1", 1, 1), world, em, player);
        ActionOutcome first = enemy.consumeLastActionOutcome();
        assertNotNull(first);
        assertEquals("AttackAction", first.getActionType());

        // 冷却递减（5→1）期间：仲裁到攻击都降级为朝玩家移动（相邻被挡 BLOCKED）
        for (int tick = 2; tick < 6; tick++) {
            AiTickLoop.run(new AiTickContext("run-1", 1, tick),
                    world, em, player);
            ActionOutcome outcome = enemy.consumeLastActionOutcome();
            assertNotNull("tick " + tick + " must still act", outcome);
            assertEquals("cooldown must downgrade attack to chase at tick "
                            + tick,
                    "MoveAction", outcome.getActionType());
        }
        // 冷却结束：再次正常攻击
        AiTickLoop.run(new AiTickContext("run-1", 1, 6), world, em, player);
        ActionOutcome second = enemy.consumeLastActionOutcome();
        assertNotNull(second);
        assertEquals("AttackAction", second.getActionType());
    }

    // ---------- 存档恢复 ----------

    @Test
    public void maxHpClampsCurrentHpOnRestore() {
        Enemy enemy = new Enemy(new Position(0, 0), Tileset.ENEMY,
                10, 5, 5, 5, 2, new Random(1), "guard-a");
        assertEquals(10, enemy.getMaxHp());
        enemy.setMaxHp(20);
        enemy.setHp(14);
        assertEquals(14, enemy.getHp());
        assertEquals(20, enemy.getMaxHp());
        enemy.setMaxHp(8); // 读档恢复较小上限
        assertEquals(8, enemy.getHp());
        assertEquals(8, enemy.getMaxHp());
        enemy.setHp(999);
        assertEquals(8, enemy.getHp());
    }
}
