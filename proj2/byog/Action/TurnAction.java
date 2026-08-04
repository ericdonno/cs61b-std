package byog.Action;

import byog.Common.Facing;
import byog.Entity.Enemy;
import byog.Entity.Entity;
import byog.TileEngine.TETile;

import java.util.Objects;

/**
 * 原地转向动作：把敌人朝向改为指定方向，消耗一次正常 action opportunity。
 * 只对 {@link Enemy} 有意义；对其他实体返回 BLOCKED。
 */
public class TurnAction implements Action {
    private final Facing targetFacing;

    public TurnAction(Facing targetFacing) {
        this.targetFacing = Objects.requireNonNull(
                targetFacing, "targetFacing");
    }

    @Override
    public ActionResult execute(TETile[][] world, Entity entity) {
        if (entity instanceof Enemy enemy) {
            enemy.setFacing(targetFacing);
            return ActionResult.SUCCESS;
        }
        return ActionResult.BLOCKED;
    }
}
