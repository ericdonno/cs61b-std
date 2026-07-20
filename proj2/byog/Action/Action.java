package byog.Action;

import byog.Entity.Entity;
import byog.TileEngine.TETile;

public interface Action {
    enum ActionResult {
        SUCCESS, BLOCKED, INTERRUPTED, COMPLETED, DAMAGE
    }

    /**
     * 执行动作，更新实体状态。
     * @param world 游戏世界瓦片数组
     * @param entity 执行动作的实体
     * @return 动作执行结果
     */
    ActionResult execute(TETile[][] world, Entity entity);
}
