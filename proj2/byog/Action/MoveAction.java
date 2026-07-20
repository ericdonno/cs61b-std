package byog.Action;

import byog.Common.Direction;
import byog.Entity.Entity;
import byog.Entity.EntityManager;
import byog.TileEngine.TETile;
import byog.lab5.Position;

public class MoveAction implements Action {
    private Direction direction;
    private EntityManager entityMgr;

    public MoveAction(Direction direction, EntityManager entityMgr) {
        this.direction = direction;
        this.entityMgr = entityMgr;
    }

    /**
     * 执行移动动作。通过 EntityManager 统一检测地形和实体碰撞。
     * <p>
     * 只修改 entity.position 字段，索引更新延迟到帧末 {@link EntityManager#flushPendingChanges()}。
     * @param world 游戏世界瓦片数组
     * @param entity 执行移动的实体
     * @return SUCCESS 表示移动成功，BLOCKED 表示受阻
     */
    @Override
    public ActionResult execute(TETile[][] world, Entity entity) {
        if (direction == null) {
            return ActionResult.SUCCESS;
        }

        Position newPos = new Position(entity.getPosition().x + direction.dx, entity.getPosition().y + direction.dy);
        if (entityMgr.canMoveTo(entity, newPos, world)) {
            entity.setPosition(newPos);
            entityMgr.claimPosition(newPos);
            return ActionResult.SUCCESS;
        }
        return ActionResult.BLOCKED;
    }
}
