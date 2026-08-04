package byog.Core;

import byog.Entity.Player;
import byog.Entity.PlayerRunState;
import byog.IO.GameConfig;
import byog.lab5.Position;

import java.util.Objects;

/**
 * 楼层转换服务：按整局状态边界创建下一楼层的玩家。
 *
 * <p>换层仍然创建按当前配置初始化的新 {@code Player}（不直接复用旧对象），
 * 但新玩家从 {@code PlayerRunState} 恢复整局 HP。蓄力、动画计时等当前楼层或
 * 纯表现状态一律不跨层。Game 与测试共用此 seam，避免测试依赖私有方法。</p>
 */
public final class FloorTransitionService {

    private FloorTransitionService() {
    }

    /**
     * 创建下一楼层的玩家：先抓取旧玩家的整局 HP，再构造新玩家并恢复。
     *
     * @param runState 旧玩家抓取的整局状态
     * @param config   当前难度配置（决定生命上限与攻击属性）
     * @param spawn    新玩家的初始坐标；调用方负责将其定位到合法地板
     * @return 携带整局 HP 的新玩家，蓄力为 0
     */
    public static Player createForNextFloor(
            PlayerRunState runState, GameConfig config, Position spawn) {
        Objects.requireNonNull(runState, "runState");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(spawn, "spawn");
        Player next = new Player(spawn, config);
        runState.restoreInto(next, config.playerHp);
        return next;
    }
}
