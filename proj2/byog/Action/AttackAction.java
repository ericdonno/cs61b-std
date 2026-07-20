package byog.Action;

import byog.Common.Direction;
import static byog.Common.RandomUtils.uniform;
import byog.Entity.Entity;
import byog.Entity.EntityManager;
import byog.Entity.Enemy;
import byog.Entity.Player;
import byog.Helper.Logger;
import byog.TileEngine.TETile;
import byog.lab5.Position;

import java.util.Random;

/**
 * 攻击动作。支持两种模式：
 * <ul>
 *   <li>玩家攻击：范围攻击，对周围8格敌人造成伤害，需蓄力满</li>
 *   <li>敌人攻击：单体攻击，向指定方向的目标造成伤害</li>
 * </ul>
 */
public class AttackAction implements Action {
    private EntityManager entityMgr;
    private Direction direction;
    private Random random;

    /** 玩家攻击：无需方向，自动检测周围8格。 */
    public AttackAction(EntityManager entityMgr, Random random) {
        this.entityMgr = entityMgr;
        this.direction = null;
        this.random = random;
    }

    /** 敌人攻击：指定方向，单体攻击。 */
    public AttackAction(EntityManager entityMgr, Direction direction, Random random) {
        this.entityMgr = entityMgr;
        this.direction = direction;
        this.random = random;
    }

    @Override
    public ActionResult execute(TETile[][] world, Entity entity) {
        if (entity instanceof Player player) {
            return executePlayerAttack(player);
        } else if (entity instanceof Enemy enemy) {
            return executeEnemyAttack(enemy);
        }
        return ActionResult.BLOCKED;
    }

    /** 玩家范围攻击：检测周围8格，对每个敌人独立计算伤害。蓄力立即消耗。 */
    private ActionResult executePlayerAttack(Player player) {
        if (!player.canAttack()) {
            return ActionResult.BLOCKED;
        }

        // 立即消耗蓄力（无论是否有敌人）
        player.resetCharge();

        Position pos = player.getPosition();
        int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
        int[] dy = {-1, -1, -1, 0, 0, 1, 1, 1};

        boolean hitAny = false;
        for (int i = 0; i < 8; i++) {
            Position targetPos = new Position(pos.x + dx[i], pos.y + dy[i]);
            Entity target = entityMgr.findEntityAt(targetPos);

            if (target instanceof Enemy enemy && enemy.isAlive()) {
                int damage = calculateDamage(player);
                dealDamage(enemy, damage);
                hitAny = true;

                Logger.info("Player attacked Enemy#%d for %d damage!",
                        enemy.getId(), damage);
            }
        }

        return hitAny ? ActionResult.DAMAGE : ActionResult.SUCCESS;
    }

    /** 敌人单体攻击：向direction方向攻击，只命中相邻格的目标。 */
    private ActionResult executeEnemyAttack(Enemy enemy) {
        if (direction == null) {
            return ActionResult.BLOCKED;
        }

        Position targetPos = new Position(
                enemy.getPosition().x + direction.dx,
                enemy.getPosition().y + direction.dy
        );

        Entity target = entityMgr.findEntityAt(targetPos);
        if (target instanceof Player player && player.isAlive()) {
            int damage = calculateDamage(enemy);
            dealDamage(player, damage);

            Logger.info("Enemy#%d attacked Player for %d damage!",
                    enemy.getId(), damage);

            return ActionResult.DAMAGE;
        }

        return ActionResult.BLOCKED;
    }

    /** 根据实体自身的attackDamage和damageVariance计算随机伤害。 */
    private int calculateDamage(Entity entity) {
        int baseDamage = 5;
        int variance = 2;

        if (entity instanceof Player player) {
            baseDamage = player.getAttackDamage();
            variance = player.getDamageVariance();
        } else if (entity instanceof Enemy enemy) {
            baseDamage = enemy.getAttackDamage();
            variance = enemy.getDamageVariance();
        }

        return baseDamage + uniform(random, variance + 1);
    }

    /** 造成伤害并处理死亡。玩家额外设置hitTimer用于受击动画。 */
    private void dealDamage(Entity target, int damage) {
        if (target instanceof Player player) {
            player.setHp(Math.max(0, player.getHp() - damage));
            player.setHitTimer(8);
            if (player.getHp() <= 0) {
                player.die();
            }
        } else if (target instanceof Enemy enemy) {
            enemy.setHp(Math.max(0, enemy.getHp() - damage));
            if (enemy.getHp() <= 0) {
                enemy.die();
            }
        }
    }
}
