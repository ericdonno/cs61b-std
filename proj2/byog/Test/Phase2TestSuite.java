package byog.Test;

import byog.Helper.Logger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Single deterministic agent gate. Leaf classes appear exactly once.
 * Network/process integration tests remain separate.
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
    EnemyCollisionTest.class,
    Phase0EncounterTest.class,
    PerceptionSystemTest.class,
    Phase1EncounterTest.class,
    Phase2ProtocolTest.class,
    Phase2AiTickTest.class,
    Phase2ArbiterTest.class
})
public final class Phase2TestSuite {
    private static Logger.Level previousLogLevel;

    private Phase2TestSuite() {
    }

    @BeforeClass
    public static void silenceProductionLogs() {
        previousLogLevel = Logger.getLevel();
        Logger.setLevel(Logger.Level.OFF);
    }

    @AfterClass
    public static void restoreProductionLogs() {
        Logger.setLevel(previousLogLevel);
    }
}
