package byog.Core;

/**
 * P0-T02 的包内测试辅助类：委托到生产 parser {@link Phase0EncounterHarness#fromAscii}。
 *
 * <p>本类不再复制解析逻辑；所有 ASCII 输入都经过唯一的生产代码路径，
 * 因此非法输入测试验证的是真实 Harness parser，而不是测试自己的副本。</p>
 */
final class Phase0EncounterTestHelper {

    private Phase0EncounterTestHelper() { }

    /** 使用 Phase 0 默认玩家 HP 构造测试场景。 */
    static Phase0EncounterHarness buildFromAscii(String[] ascii) {
        return buildFromAscii(ascii, 1000);
    }

    /** 委托到生产 parser，使用固定 guard seed。 */
    static Phase0EncounterHarness buildFromAscii(String[] ascii, int playerHp) {
        return Phase0EncounterHarness.fromAscii(ascii, playerHp, 101, 202);
    }
}
