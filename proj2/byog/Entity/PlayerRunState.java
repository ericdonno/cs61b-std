package byog.Entity;

import java.io.Serializable;

/**
 * 玩家整局状态：跨楼层保留并在命名世界存档中恢复的权威玩家状态。
 *
 * <p>本阶段只承载 {@code currentHp}。它不持有 {@code Player}、world 或 UI 引用，
 * 也不保存蓄力、动画计时、悬停等当前楼层或纯表现状态。未来若引入永久成长
 * （maxHp、属性、背包等），应显式加入本类而不是塞进当前楼层快照。</p>
 */
public final class PlayerRunState implements Serializable {
    private static final long serialVersionUID = 1L;

    private int currentHp;

    /** 从当前玩家抓取整局 HP，并收敛到玩家自身生命上限。 */
    public static PlayerRunState capture(Player player) {
        if (player == null) {
            throw new IllegalArgumentException("player must not be null");
        }
        PlayerRunState state = new PlayerRunState();
        state.currentHp = clamp(player.getHp(), 0, player.getMaxHp());
        return state;
    }

    /** 把整局 HP 恢复到新建玩家，并收敛到配置生命上限。 */
    public void restoreInto(Player player, int configuredMaxHp) {
        if (player == null) {
            throw new IllegalArgumentException("player must not be null");
        }
        player.setHp(clamp(currentHp, 0, configuredMaxHp));
    }

    public int getCurrentHp() {
        return currentHp;
    }

    /** 显式设置整局 HP（测试与反序列化用）；正常流程使用 capture/restore。 */
    public void setCurrentHp(int currentHp) {
        this.currentHp = currentHp;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
