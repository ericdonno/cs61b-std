package byog.Action;

import byog.Entity.Entity;
import byog.TileEngine.TETile;

/**
 * 显式等待动作：占一次正常 action opportunity，不改变朝向或坐标。
 */
public class WaitAction implements Action {

    @Override
    public ActionResult execute(TETile[][] world, Entity entity) {
        return ActionResult.SUCCESS;
    }
}
