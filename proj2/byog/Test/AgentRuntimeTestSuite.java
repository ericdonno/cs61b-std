package byog.Test;

import byog.Bridge.AgentContractFixtureTest;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/** Single deterministic gate for protocol, authority and runtime state. */
@RunWith(Suite.class)
@Suite.SuiteClasses({
        AgentProtocolContractTest.class,
        AgentContractFixtureTest.class,
        TacticalSkillRegistryTest.class,
        AgentArbiterTest.class,
        AgentSessionTest.class,
        AgentTraceContractTest.class
})
public final class AgentRuntimeTestSuite {
    private AgentRuntimeTestSuite() {
    }
}
