package byog.Bridge;

import byog.Bridge.AgentProtocolCodec.DecodeResult;
import byog.Bridge.AgentProtocolCodec.FailureReason;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 跨语言共享 wire fixture 的 Java 侧 runner。
 *
 * <p>合法 fixture 必须被严格解码为相同的 typed data；非法 fixture 必须给出
 * 稳定 rejection code，且不改变 Agent/世界状态（decode 本身无副作用）。</p>
 */
public class AgentContractFixtureTest {

    private static final Path FIXTURES =
            Paths.get("agent", "contract", "fixtures");

    private String readFixture(String name) throws IOException {
        return Files.readString(FIXTURES.resolve(name),
                StandardCharsets.UTF_8);
    }

    private DecodeResult decode(String name) throws IOException {
        return AgentProtocolCodec.decodeMessage(readFixture(name));
    }

    // ---------- P25-PROTOCOL-01 合法 v2 fixture ----------

    @Test
    public void validDirectionalFixtureDecodesToTypedData() throws Exception {
        DecodeResult result = decode("valid-observation-directional.json");
        assertTrue("must decode: " + result,
                result instanceof DecodeResult.Success);
        AgentProtocol.Envelope envelope =
                ((DecodeResult.Success) result).envelope();
        assertEquals("agent-session.v1", envelope.schemaVersion);
        assertEquals("world-fixture", envelope.worldId);
        assertEquals("run-fixture", envelope.runId);
        assertEquals(1, envelope.floorId);
        assertEquals("guard-a", envelope.agentId);
        AgentProtocol.ObservationData data =
                (AgentProtocol.ObservationData) envelope.data;
        assertEquals("private-observation.v2",
                data.observationVersion());
        assertEquals("DIRECTIONAL", data.visionMode());
        assertEquals(14, data.self().hp());
        assertEquals(20, data.self().maxHp());
        assertEquals("EAST", data.self().facing());
        assertEquals(3, data.visibleTiles().size());
    }

    @Test
    public void validOmnidirectionalFixtureDecodesToTypedData()
            throws Exception {
        DecodeResult result = decode(
                "valid-observation-omnidirectional.json");
        assertTrue("must decode: " + result,
                result instanceof DecodeResult.Success);
        AgentProtocol.Envelope envelope =
                ((DecodeResult.Success) result).envelope();
        AgentProtocol.ObservationData data =
                (AgentProtocol.ObservationData) envelope.data;
        assertEquals("OMNIDIRECTIONAL", data.visionMode());
        assertEquals("NORTH", data.self().facing());
    }

    @Test
    public void validIntentFixtureDecodesGenericParameters()
            throws Exception {
        DecodeResult result = decode("valid-submit-intent-v2.json");
        assertTrue("must decode: " + result,
                result instanceof DecodeResult.Success);
        AgentProtocol.SubmitIntentData data =
                (AgentProtocol.SubmitIntentData)
                        ((DecodeResult.Success) result).envelope().data;
        assertEquals("strategic-intent.v2",
                data.intent().intentVersion());
        assertEquals("CHASE", data.intent().skill());
        assertEquals("plan-fixture-1",
                data.intent().planMetadata().planId());
        assertTrue(data.intent().parameters().containsKey("futureHints"));
    }

    // ---------- P25-PROTOCOL-02/03 非法 fixture 稳定拒绝 ----------

    @Test
    public void oldEnvelopeVersionIsRejected() throws Exception {
        assertFailure("invalid-old-envelope-version.json",
                FailureReason.SCHEMA_MISMATCH);
    }

    @Test
    public void oldObservationVersionIsRejected() throws Exception {
        assertFailure("invalid-old-observation-version.json",
                FailureReason.UNKNOWN_PAYLOAD_VERSION);
    }

    @Test
    public void oldIntentVersionIsRejected() throws Exception {
        assertFailure("invalid-old-intent-version.json",
                FailureReason.UNKNOWN_PAYLOAD_VERSION);
    }

    @Test
    public void missingWorldIdIsRejected() throws Exception {
        assertFailure("invalid-missing-world-id.json",
                FailureReason.MISSING_REQUIRED);
    }

    @Test
    public void invalidFacingIsRejected() throws Exception {
        assertFailure("invalid-facing.json",
                FailureReason.UNKNOWN_FIELD);
    }

    @Test
    public void invalidVisionModeIsRejected() throws Exception {
        assertFailure("invalid-vision-mode.json",
                FailureReason.UNKNOWN_FIELD);
    }

    @Test
    public void appleTileTypeIsRejected() throws Exception {
        assertFailure("invalid-apple-tile-type.json",
                FailureReason.UNKNOWN_FIELD);
    }

    private void assertFailure(String name, FailureReason expected)
            throws IOException {
        DecodeResult result = decode(name);
        assertTrue("must be failure for " + name + ": " + result,
                result instanceof DecodeResult.Failure);
        assertEquals(expected,
                ((DecodeResult.Failure) result).failure().reason());
    }
}
