package byog.Core;

import byog.Common.Facing;
import byog.Entity.Enemy;
import byog.Entity.EntityManager;
import byog.Entity.Player;
import byog.IO.GameConfig;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

/**
 * 底部上下文条 hover 模型：把鼠标所在世界格解析为可显示文案。
 *
 * <p>只读取世界事实（Enemy hp/maxHp/Facing、苹果状态、tile description），
 * 不维护独立 UI 状态，也不重新计算 FOV。无有效目标时返回空文案。</p>
 */
public final class HoverModel {

    /** 一次 hover 解析结果：两行文本 + 可选聚焦敌人（用于高亮其 committed FOV）。 */
    public record HoverInfo(String line1, String line2, Enemy focusedEnemy) {

        public boolean isEmpty() {
            return line1 == null && line2 == null;
        }

        public static HoverInfo empty() {
            return new HoverInfo(null, null, null);
        }
    }

    private HoverModel() {
    }

    /**
     * 解析鼠标所在世界坐标的悬停信息。
     *
     * @param worldPos 世界逻辑坐标（已由 ScreenLayout 反向映射）
     */
    public static HoverInfo resolve(TETile[][] world,
                                    EntityManager entityMgr,
                                    Player player,
                                    GameConfig config,
                                    Position worldPos) {
        if (world == null || worldPos == null
                || worldPos.x < 0 || worldPos.x >= world.length
                || worldPos.y < 0 || worldPos.y >= world[0].length) {
            return HoverInfo.empty();
        }
        if (entityMgr != null) {
            var entity = entityMgr.findEntityAt(worldPos);
            if (entity instanceof Enemy enemy && enemy.isAlive()) {
                return enemyHover(enemy);
            }
        }
        TETile tile = world[worldPos.x][worldPos.y];
        if (tile == Tileset.APPLE && config != null) {
            return new HoverInfo(
                    "apple health pack",
                    "Heal +" + config.healthPackHealAmount
                            + " (not consumed at full HP)", null);
        }
        if (tile == null) {
            return HoverInfo.empty();
        }
        return new HoverInfo(tile.description(), null, null);
    }

    private static HoverInfo enemyHover(Enemy enemy) {
        int current = enemy.getHp();
        int max = enemy.getMaxHp();
        Facing facing = enemy.getFacing();
        String bar = healthBar(current, max);
        String line2 = "Facing: " + (facing == null ? "?" : facing.name());
        return new HoverInfo(
                "Enemy " + enemy.getAgentId() + " | HP: " + current + "/"
                        + max + " " + bar,
                line2, enemy);
    }

    /** 从 current/max HP 绘制文本血条（10 格）。 */
    public static String healthBar(int current, int max) {
        int width = 10;
        int filled = max <= 0 ? 0
                : (int) Math.round(current * (double) width / max);
        filled = Math.max(0, Math.min(width, filled));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            sb.append(i < filled ? '=' : '-');
        }
        return sb.append(']').toString();
    }
}
