package byog.Test;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Phase 0 的单一 JUnit 验收入口。
 *
 * <p>Suite 把固定遭遇契约测试和修复前提后的碰撞回归测试放在同一条命令中运行，
 * 让本地开发和 CI 使用相同 gate。故意不包含 MathTest：它依赖无 seed 随机数、
 * 系统时间、控制台或 GUI，不属于 Phase 0 的确定性自动验收范围。</p>
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
    // 先验证原有活实体碰撞回归，再运行固定遭遇的 P0-T01 至 P0-T09。
    EnemyCollisionTest.class,
    Phase0EncounterTest.class
})
public class Phase0TestSuite {
}
