package byog.Core;

import byog.TileEngine.TETile;
import byog.lab5.Position;

public class MoveAction implements Action {
    private Direction direction;

    public MoveAction(Direction direction) {
        this.direction = direction;
    }

    /**
     * 执行移动动作。方向为 null 时原地等待。
     * 计算目标位置，检测合法性后更新实体位置。
     * @param world 游戏世界瓦片数组
     * @param entity 执行移动的实体
     * @return SUCCESS 表示移动成功，BLOCKED 表示受阻
     */
    @Override
    public ActionResult execute(TETile[][] world, Entity entity) {
        if (direction == null) {
            return ActionResult.SUCCESS;
        }

        Position currentPos = entity.getPosition();
        int newX = currentPos.x;
        int newY = currentPos.y;

        switch (direction) {
            case UP:
                newY += 1;
                break;
            case DOWN:
                newY -= 1;
                break;
            case LEFT:
                newX -= 1;
                break;
            case RIGHT:
                newX += 1;
                break;
        }

        Position newPos = new Position(newX, newY);
        if (Player.canMoveTo(newPos, world)) {
            entity.setPosition(newPos);
            return ActionResult.SUCCESS;
        }
        return ActionResult.BLOCKED;
    }
}
