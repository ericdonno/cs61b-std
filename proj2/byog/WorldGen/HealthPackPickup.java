package byog.WorldGen;

import byog.Entity.Player;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.List;

/**
 * 苹果拾取：玩家成功移动并提交新坐标后调用。
 *
 * <p>缺血时治疗并消耗（tile 恢复为地板）；满血时不修改 HP、不消耗苹果。
 * 只接受玩家触发，敌人移动永不进入此流程。remaining 为当前楼层剩余苹果
 * 坐标列表（可为 null），拾取成功时同步移除。</p>
 */
public final class HealthPackPickup {

    private HealthPackPickup() {
    }

    /**
     * 尝试在指定位置拾取苹果。
     *
     * @return true 表示拾取并治疗成功；false 表示该格不是苹果或玩家满血
     */
    public static boolean tryPickup(TETile[][] world, Player player,
                                    Position pos, int healAmount,
                                    List<Position> remaining) {
        if (world == null || player == null || pos == null) {
            throw new IllegalArgumentException(
                    "world, player and pos must not be null");
        }
        if (!isInside(world, pos) || world[pos.x][pos.y] != Tileset.APPLE) {
            return false;
        }
        if (player.getHp() >= player.getMaxHp()) {
            return false;
        }
        player.heal(healAmount);
        world[pos.x][pos.y] = Tileset.FLOOR;
        if (remaining != null) {
            remaining.remove(pos);
        }
        return true;
    }

    private static boolean isInside(TETile[][] world, Position p) {
        return p.x >= 0 && p.x < world.length
                && p.y >= 0 && p.y < world[0].length;
    }
}
