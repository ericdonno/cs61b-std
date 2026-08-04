package byog.Test;

import byog.Entity.EntityManager;
import byog.Entity.Enemy;
import byog.Entity.Player;
import byog.Trace.AgentTrace;
import byog.TileEngine.TETile;
import byog.lab5.Position;

/**
 * Compatibility adapter for the historical private-perception tests.
 * New tests should use {@link EncounterHarness} directly.
 */
public final class PrivatePerceptionEncounterHarness {
    private final EncounterHarness delegate;

    private PrivatePerceptionEncounterHarness(EncounterHarness delegate) {
        this.delegate = delegate;
    }

    public static PrivatePerceptionEncounterHarness fromAscii(
            String[] ascii, int playerHp, long guardASeed, long guardBSeed) {
        return new PrivatePerceptionEncounterHarness(EncounterHarness.fromAscii(
                EncounterHarness.Mode.PRIVATE_PERCEPTION, ascii, playerHp,
                guardASeed, guardBSeed));
    }

    public static PrivatePerceptionEncounterHarness baselineTwoGuardsV1() {
        return new PrivatePerceptionEncounterHarness(
                EncounterHarness.privatePerceptionV1());
    }

    public void step() {
        delegate.step();
    }

    public void runTicks(int count) {
        delegate.runTicks(count);
    }

    public String canonicalTraceJson() {
        return delegate.canonicalTraceJson();
    }

    public String canonicalState() {
        return delegate.canonicalState();
    }

    public TETile[][] terrainCopy() {
        return delegate.terrainCopy();
    }

    public TETile[][] getWorld() {
        return delegate.getWorld();
    }

    public EntityManager getEntityMgr() {
        return delegate.getEntityMgr();
    }

    public Player player() {
        return delegate.player();
    }

    public Enemy guardA() {
        return delegate.guardA();
    }

    public Enemy guardB() {
        return delegate.guardB();
    }

    public Position stairsPosition() {
        return delegate.stairsPosition();
    }

    public long getLogicalTick() {
        return delegate.getLogicalTick();
    }

    public AgentTrace.InMemorySink getTraceSink() {
        return delegate.getTraceSink();
    }

    public int width() {
        return delegate.width();
    }

    public int height() {
        return delegate.height();
    }
}
