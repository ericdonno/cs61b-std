package byog.AI;

import byog.Trace.AgentTrace;

import java.util.Objects;

/**
 * Stable identity shared by every phase of one production AI tick.
 *
 * <p>The context contains no world or entity references. In particular, it
 * cannot be used to bypass the private-observation boundary.</p>
 */
public final class AiTickContext {
    private final String runId;
    private final int floorId;
    private final long logicalTick;
    private final AgentTrace.Context traceContext;
    private final AgentTrace.Sink traceSink;

    public AiTickContext(String runId, int floorId, long logicalTick) {
        this(runId, floorId, logicalTick, null, AgentTrace.NO_OP);
    }

    public AiTickContext(String runId, int floorId, long logicalTick,
                         AgentTrace.Context traceContext,
                         AgentTrace.Sink traceSink) {
        this.runId = requireNonBlank(runId, "runId");
        if (floorId < 1) {
            throw new IllegalArgumentException("floorId must be >= 1");
        }
        if (logicalTick < 0) {
            throw new IllegalArgumentException("logicalTick must be >= 0");
        }
        this.floorId = floorId;
        this.logicalTick = logicalTick;
        this.traceContext = traceContext;
        this.traceSink = traceSink == null ? AgentTrace.NO_OP : traceSink;
    }

    public String getRunId() {
        return runId;
    }

    public int getFloorId() {
        return floorId;
    }

    public long getLogicalTick() {
        return logicalTick;
    }

    public AgentTrace.Context getTraceContext() {
        return traceContext;
    }

    public AgentTrace.Sink getTraceSink() {
        return traceSink;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
