package byog.Test;

import byog.Bridge.AgentContractFixtureTest;
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
    LegacyEncounterTest.class,
    PerceptionSystemTest.class,
    PrivatePerceptionEncounterTest.class,
    AgentProtocolContractTest.class,
    AgentAiTickTest.class,
    AgentArbiterTest.class,
    AgentSessionTest.class,
    AgentTraceContractTest.class,
    GameConfigTest.class,
    PlayerRunStateTest.class,
    HealthPackTest.class,
    WorldSaveRepositoryTest.class,
    FacingActionTest.class,
    DirectionalFovTest.class,
    PatrolControllerTest.class,
    BottomBarUiTest.class,
    AgentContractFixtureTest.class
})
public final class CoreGameplayRegressionSuite {
    private static Logger.Level previousLogLevel;

    private CoreGameplayRegressionSuite() {
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
