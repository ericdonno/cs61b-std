package byog.Test;

import byog.Common.Difficulty;
import byog.Core.FloorTransitionService;
import byog.Entity.Player;
import byog.Entity.PlayerRunState;
import byog.IO.GameConfig;
import byog.IO.GameSaveData;
import byog.lab5.Position;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * 整局玩家状态边界：跨层 HP 保留、charge 恢复与序列化白名单。
 *
 * <p>覆盖 Spec 的玩家状态验收：非满 HP 下楼后 HP 不变且对象不同；
 * charge 只从读档恢复并在换层清零；整局 DTO 不含 Session、queue、
 * hover 或动画计时。</p>
 */
public class PlayerRunStateTest {

    /** 空 Properties 触发全部安全默认值，避免测试读取真实配置文件。 */
    private GameConfig config() {
        return new GameConfig(Difficulty.BALANCED, new Properties());
    }

    @Test
    public void nextFloorRestoresHpOnNewPlayerObject() {
        GameConfig config = config();
        Player previous = new Player(new Position(3, 3), config);
        previous.setHp(42);

        Player next = FloorTransitionService.createForNextFloor(
                PlayerRunState.capture(previous), config, new Position(5, 5));

        assertNotSame("next floor must create a new Player", previous, next);
        assertEquals(42, next.getHp());
        assertEquals(0, next.getCharge());
        assertEquals(config.playerHp, next.getMaxHp());
        assertEquals(config.playerAttack, next.getAttackDamage());
        assertEquals(config.playerMaxCharge, next.getMaxCharge());
    }

    @Test
    public void captureClampsToPlayerMaxHp() {
        GameConfig config = config();
        Player player = new Player(new Position(0, 0), config);
        player.setHp(-5);
        assertEquals(0, PlayerRunState.capture(player).getCurrentHp());
        player.setHp(config.playerHp + 100);
        assertEquals(config.playerHp,
                PlayerRunState.capture(player).getCurrentHp());
    }

    @Test
    public void restoreClampsToConfiguredMaxHp() {
        GameConfig config = config();
        PlayerRunState state = PlayerRunState.capture(
                new Player(new Position(0, 0), config));
        state.setCurrentHp(999);
        Player restored = new Player(new Position(1, 1), config);
        state.restoreInto(restored, 80);
        assertEquals(80, restored.getHp());
    }

    @Test
    public void restoreChargeClampsAndRoundTrips() {
        GameConfig config = config();
        Player player = new Player(new Position(0, 0), config);
        player.restoreCharge(37);
        assertEquals(37, player.getCharge());
        player.restoreCharge(-3);
        assertEquals(0, player.getCharge());
        player.restoreCharge(config.playerMaxCharge + 10);
        assertEquals(config.playerMaxCharge, player.getCharge());
    }

    @Test
    public void runStateCarriesOnlyCurrentHp() {
        Set<String> fields = new HashSet<>();
        for (Field f : PlayerRunState.class.getDeclaredFields()) {
            fields.add(f.getName());
        }
        assertTrue("run state must carry currentHp", fields.contains("currentHp"));
        fields.remove("currentHp");
        fields.remove("serialVersionUID");
        assertTrue("unexpected run-state fields: " + fields, fields.isEmpty());
    }

    @Test
    public void saveDtoCarriesNoRuntimeState() {
        // 存档 DTO 只允许基础类型、String、明确 DTO 与稳定集合；
        // Session、queue、Lease、实体引用、tile 与动画计时不得进入。
        Set<String> forbidden = Set.of(
                "byog.Bridge.AgentSession",
                "byog.Action.ActionQueue",
                "byog.AI.IntentLease",
                "byog.Entity.Player",
                "byog.Entity.Enemy",
                "byog.Entity.EntityManager",
                "byog.TileEngine.TETile");
        for (Field f : GameSaveData.class.getDeclaredFields()) {
            assertFalse("runtime state leaked into save DTO: " + f.getName(),
                    forbidden.contains(f.getType().getName()));
        }
    }
}
