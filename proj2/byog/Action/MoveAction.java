package byog.Action;

import byog.Common.Direction;
import byog.Common.Facing;
import byog.Entity.Enemy;
import byog.Entity.Entity;
import byog.Entity.EntityManager;
import byog.TileEngine.TETile;
import byog.lab5.Position;

import java.util.Objects;

public class MoveAction implements Action {
    private final Direction direction;
    private final EntityManager entityMgr;

    public MoveAction(Direction direction, EntityManager entityMgr) {
        this.direction = Objects.requireNonNull(direction, "direction");
        this.entityMgr = Objects.requireNonNull(entityMgr, "entityMgr");
    }

    /**
     * 执行移动动作。敌人先朝移动方向转向（即使碰撞受阻也更新朝向），
     * 再通过 EntityManager 统一检测地形和实体碰撞。
     * <p>
     * 只修改 entity.position 字段，索引更新延迟到帧末 {@link EntityManager#flushPendingChanges()}。
     * @param world 游戏世界瓦片数组
     * @param entity 执行移动的实体
     * @return SUCCESS 表示移动成功，BLOCKED 表示受阻
     */
    @Override
    public ActionResult execute(TETile[][] world, Entity entity) {
        if (entity instanceof Enemy enemy) {
            enemy.setFacing(Facing.fromDirection(direction));
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
