package byog.Test;

/**
 * Legacy-T02 的包内测试辅助类：委托到生产 parser {@link LegacyEncounterHarness#fromAscii}。
 *
 * <p>本类不再复制解析逻辑；所有 ASCII 输入都经过唯一的生产代码路径，
 * 因此非法输入测试验证的是真实 Harness parser，而不是测试自己的副本。</p>
 */
final class LegacyEncounterTestHelper {

    private LegacyEncounterTestHelper() { }

    /** 使用默认玩家 HP 构造测试场景。 */
    static LegacyEncounterHarness buildFromAscii(String[] ascii) {
        return buildFromAscii(ascii, 1000);
    }

    /** 委托到生产 parser，使用固定 guard seed。 */
    static LegacyEncounterHarness buildFromAscii(String[] ascii, int playerHp) {
        return LegacyEncounterHarness.fromAscii(ascii, playerHp, 101, 202);
    }
}
