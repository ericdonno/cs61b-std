package byog.Test;

import byog.Entity.Enemy;
import byog.Entity.Player;
import byog.Trace.AgentTrace;
import byog.TileEngine.TETile;
import byog.lab5.Position;

/**
 * Compatibility adapter for historical Phase 0 tests.
 * New tests should use {@link EncounterHarness} directly.
 */
public final class Phase0EncounterHarness {
    private final EncounterHarness delegate;

    private Phase0EncounterHarness(EncounterHarness delegate) {
        this.delegate = delegate;
    }

    public static Phase0EncounterHarness fromAscii(
            String[] ascii, int playerHp, long guardASeed, long guardBSeed) {
        return new Phase0EncounterHarness(EncounterHarness.fromAscii(
                EncounterHarness.Mode.LEGACY, ascii, playerHp,
                guardASeed, guardBSeed));
    }

    public static Phase0EncounterHarness baselineTwoGuardsV1() {
        return new Phase0EncounterHarness(EncounterHarness.legacyV1());
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
