package byog.Test;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Phase 1 的单一 JUnit 验收入口。
 *
 * <p>聚合 Phase 0 的回归测试和 Phase 1 的私有感知测试，
 * 让本地开发和 CI 使用相同 gate。</p>
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
    EnemyCollisionTest.class,
    Phase0EncounterTest.class,
    Phase1EncounterTest.class
})
public class Phase1TestSuite {
}
