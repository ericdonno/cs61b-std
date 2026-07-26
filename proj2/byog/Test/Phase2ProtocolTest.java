package byog.Test;

import byog.Bridge.AgentProtocol;
import byog.Bridge.AgentProtocolCodec;
import byog.Bridge.AgentProtocolCodec.DecodeResult;
import byog.Bridge.AgentProtocolCodec.FailureReason;
import byog.Bridge.AgentProtocolCodec.JsonObject;
import byog.Bridge.AgentProtocolCodec.JsonString;
import byog.Bridge.AgentProtocolCodec.JsonValue;
import byog.Bridge.IdGenerator;
import byog.Perception.ObservationEnvelope;
import byog.Perception.PerceptionSystem;
import byog.Perception.VisibleTile;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Phase 2 协议层测试（P2-P01 至 P2-P06）。
 * 纯 Java、无网络、无 sleep，验证 codec 与 observation 快照的知识边界。
 */
public class Phase2ProtocolTest {

    private static final String RUN_ID = "test-run-001";
    private static final int FLOOR_ID = 1;
    private static final String AGENT_ID = "guard-a";
    private static final long SESSION_EPOCH = 9_007_199_254_740_993L;
    private static final long REQUEST_GENERATION = 1;
    private static final long LOGICAL_TICK = 42;

    // ────────── P2-P01 ──────────

    /** P2-P01：8 种消息类型 round-trip 字段不丢失 */
    @Test
    public void protocol_round_trip() {
        IdGenerator idGen = new IdGenerator.DeterministicIdGenerator("decision", "msg");
        AgentProtocol.Identity identity = new AgentProtocol.Identity(
                RUN_ID, FLOOR_ID, AGENT_ID, SESSION_EPOCH, REQUEST_GENERATION);

        // 1. observation
        AgentProtocol.ObservationData observationData =
                buildObservationData(idGen);
        AgentProtocol.Envelope obsEnv = buildEnvelope(
                AgentProtocol.MessageType.OBSERVATION,
                observationData,
                identity, idGen, LOGICAL_TICK);
        assertRoundTrip(obsEnv);
        try {
            observationData.visibleTiles().add(
                    new AgentProtocol.VisibleTileData(0, 0, "NOTHING", false));
            fail("visibleTiles must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }

        // 2. submit_intent
        idGen = new IdGenerator.DeterministicIdGenerator("decision", "msg");
        AgentProtocol.SubmitIntentData submitIntentData =
                buildSubmitIntentData(idGen);
        AgentProtocol.Envelope intentEnv = buildEnvelope(
                AgentProtocol.MessageType.SUBMIT_INTENT,
                submitIntentData,
                identity, idGen, LOGICAL_TICK);
        assertRoundTrip(intentEnv);
        try {
            submitIntentData.intent().parameters().put(
                    "targetPosition", new AgentProtocol.PositionData(1, 1));
            fail("intent parameters must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }

        try {
            buildEnvelope(AgentProtocol.MessageType.WORLD_EVENT,
                    observationData, identity, idGen, LOGICAL_TICK);
            fail("message type/payload mismatch must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        // 3. action_feedback
        idGen = new IdGenerator.DeterministicIdGenerator("decision", "msg");
        AgentProtocol.Envelope feedbackEnv = buildEnvelope(
                AgentProtocol.MessageType.ACTION_FEEDBACK,
                buildActionFeedbackData(),
                identity, idGen, LOGICAL_TICK);
        assertRoundTrip(feedbackEnv);

        // 4. cancel_request
        idGen = new IdGenerator.DeterministicIdGenerator("decision", "msg");
        AgentProtocol.Envelope cancelReqEnv = buildEnvelope(
                AgentProtocol.MessageType.CANCEL_REQUEST,
                buildCancelRequestData(),
                identity, idGen, LOGICAL_TICK);
        assertRoundTrip(cancelReqEnv);

        // 5. cancel_ack
        idGen = new IdGenerator.DeterministicIdGenerator("decision", "msg");
        AgentProtocol.Envelope cancelAckEnv = buildEnvelope(
                AgentProtocol.MessageType.CANCEL_ACK,
                buildCancelAckData(),
                identity, idGen, LOGICAL_TICK);
        assertRoundTrip(cancelAckEnv);

        // 6. world_event
        idGen = new IdGenerator.DeterministicIdGenerator("decision", "msg");
        AgentProtocol.Envelope worldEventEnv = buildEnvelope(
                AgentProtocol.MessageType.WORLD_EVENT,
                buildWorldEventData(),
                identity, idGen, LOGICAL_TICK);
        assertRoundTrip(worldEventEnv);

        // 7. heartbeat
        idGen = new IdGenerator.DeterministicIdGenerator("decision", "msg");
        AgentProtocol.Envelope heartbeatEnv = buildEnvelope(
                AgentProtocol.MessageType.HEARTBEAT,
                new AgentProtocol.HeartbeatData(LOGICAL_TICK),
                identity, idGen, LOGICAL_TICK);
        assertRoundTrip(heartbeatEnv);

        // 8. protocol_error
        idGen = new IdGenerator.DeterministicIdGenerator("decision", "msg");
        AgentProtocol.Envelope errorEnv = buildEnvelope(
                AgentProtocol.MessageType.PROTOCOL_ERROR,
                new AgentProtocol.ProtocolErrorData("bad schema", "future_message"),
                identity, idGen, LOGICAL_TICK);
        assertRoundTrip(errorEnv);
    }

    private void assertRoundTrip(AgentProtocol.Envelope original) {
        String json = AgentProtocolCodec.encodeEnvelope(original);
        DecodeResult result = AgentProtocolCodec.decodeMessage(json);
        assertTrue("round-trip should succeed for " + original.type
                        + ": " + describe(result),
                result instanceof DecodeResult.Success);
        AgentProtocol.Envelope decoded = ((DecodeResult.Success) result).envelope();
        assertEquals("type", original.type, decoded.type);
        assertEquals("schemaVersion", original.schemaVersion, decoded.schemaVersion);
        assertEquals("messageId", original.messageId, decoded.messageId);
        assertEquals("messageSeq", original.messageSeq, decoded.messageSeq);
        assertEquals("runId", original.runId, decoded.runId);
        assertEquals("floorId", original.floorId, decoded.floorId);
        assertEquals("agentId", original.agentId, decoded.agentId);
        assertEquals("sessionEpoch", original.sessionEpoch, decoded.sessionEpoch);
        assertEquals("logicalTick", original.logicalTick, decoded.logicalTick);
        assertEquals("data", original.data, decoded.data);
    }

    // ────────── P2-P02 ──────────

    /** P2-P02：坏 JSON、重复 key、错误类型、尾随垃圾、非法转义被拒绝 */
    @Test
    public void malformed_or_duplicate_key_rejected() {
        // 缺引号
        assertFailureReason("{schemaVersion:\"x\"}",
                FailureReason.JSON_SYNTAX);

        // 重复 key
        assertFailureReason("{\"a\":1,\"a\":2}",
                FailureReason.DUPLICATE_KEY);

        // 尾随垃圾
        assertFailureReason("{\"a\":1} garbage",
                FailureReason.JSON_SYNTAX);

        // 非法转义
        assertFailureReason("{\"a\":\"\\q\"}",
                FailureReason.JSON_SYNTAX);

        // 未闭合字符串
        assertFailureReason("{\"a\":\"unterminated}",
                FailureReason.JSON_SYNTAX);

        // 非法 JSON 数字
        assertFailureReason("{\"a\":01}", FailureReason.JSON_SYNTAX);
        assertFailureReason("{\"a\":-.5}", FailureReason.JSON_SYNTAX);
        assertFailureReason("{\"a\":1.}", FailureReason.JSON_SYNTAX);

        // 字符串中的原始控制字符
        assertFailureReason("{\"a\":\"bad\tvalue\"}",
                FailureReason.JSON_SYNTAX);

        // 合法 envelope 中的字段类型错误
        JsonObject wrongTypeData = new JsonObject(new LinkedHashMap<>());
        wrongTypeData.members().put("logicalTick",
                new AgentProtocolCodec.JsonNumber(LOGICAL_TICK, true));
        IdGenerator wrongTypeIds =
                new IdGenerator.DeterministicIdGenerator("decision", "msg");
        AgentProtocol.Identity identity = new AgentProtocol.Identity(
                RUN_ID, FLOOR_ID, AGENT_ID, SESSION_EPOCH, REQUEST_GENERATION);
        String wrongType = buildEnvelopeJson(
                AgentProtocol.MessageType.HEARTBEAT,
                wrongTypeData, wrongTypeIds, identity)
                .replace("\"messageSeq\":0", "\"messageSeq\":\"zero\"");
        assertFailureReason(wrongType, FailureReason.TYPE_MISMATCH);

        // schema 未声明字段
        String unknownField = buildEnvelopeJson(
                AgentProtocol.MessageType.HEARTBEAT,
                wrongTypeData,
                new IdGenerator.DeterministicIdGenerator("decision", "msg"),
                identity).replace("\"data\":", "\"futureField\":1,\"data\":");
        assertFailureReason(unknownField, FailureReason.UNKNOWN_FIELD);
    }

    private void assertFailureReason(String json, FailureReason expected) {
        DecodeResult result = AgentProtocolCodec.decodeMessage(json);
        assertTrue("expected Failure for: " + json + " but got " + describe(result),
                result instanceof DecodeResult.Failure);
        FailureReason actual = ((DecodeResult.Failure) result).failure().reason();
        assertEquals("failure reason for: " + json, expected, actual);
    }

    // ────────── P2-P03 ──────────

    /** P2-P03：超 64KiB 或嵌套 >16 被拒绝 */
    @Test
    public void oversize_and_deep_frame_rejected() {
        // 超 64KiB
        StringBuilder huge = new StringBuilder();
        huge.append("{\"a\":\"");
        for (int i = 0; i < 70000; i++) {
            huge.append('x');
        }
        huge.append("\"}");
        DecodeResult result = AgentProtocolCodec.decodeMessage(huge.toString());
        assertTrue("oversize should fail: " + describe(result),
                result instanceof DecodeResult.Failure);
        assertEquals(FailureReason.FRAME_TOO_LARGE,
                ((DecodeResult.Failure) result).failure().reason());

        // 嵌套 >16
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            deep.append("{\"a\":");
        }
        deep.append("1");
        for (int i = 0; i < 20; i++) {
            deep.append("}");
        }
        result = AgentProtocolCodec.decodeMessage(deep.toString());
        assertTrue("deep nesting should fail: " + describe(result),
                result instanceof DecodeResult.Failure);
        assertEquals(FailureReason.DEPTH_EXCEEDED,
                ((DecodeResult.Failure) result).failure().reason());
    }

    // ────────── P2-P04 ──────────

    /** P2-P04：observation 只序列化可见 tile，墙后坐标不出现 */
    @Test
    public void observation_serializes_only_visible_tiles() {
        Phase1EncounterHarness harness = Phase1EncounterHarness.baselineTwoGuardsV1();

        // guardA 能看到玩家
        ObservationEnvelope obsA = PerceptionSystem.computeObservation(
                RUN_ID, FLOOR_ID, 0,
                harness.getWorld(), harness.getEntityMgr(),
                harness.guardA(), harness.player(),
                7, LOGICAL_TICK);

        // guardB 看不到玩家（被墙遮挡）
        ObservationEnvelope obsB = PerceptionSystem.computeObservation(
                RUN_ID, FLOOR_ID, 0,
                harness.getWorld(), harness.getEntityMgr(),
                harness.guardB(), harness.player(),
                7, LOGICAL_TICK);

        Position playerPos = harness.player().getPosition();

        // guardA 的 visibleTiles 中应包含玩家位置对应的 tile
        boolean aContainsPlayerTile = false;
        for (VisibleTile t : obsA.getVisibleTiles()) {
            if (t.getX() == playerPos.x && t.getY() == playerPos.y) {
                aContainsPlayerTile = true;
                break;
            }
        }
        assertTrue("guardA should see player's tile", aContainsPlayerTile);

        // guardB 的 visibleTiles 中不应包含玩家位置
        boolean bContainsPlayerTile = false;
        for (VisibleTile t : obsB.getVisibleTiles()) {
            if (t.getX() == playerPos.x && t.getY() == playerPos.y) {
                bContainsPlayerTile = true;
                break;
            }
        }
        assertFalse("guardB should NOT see player's tile", bContainsPlayerTile);
        try {
            obsA.getVisibleTiles().clear();
            fail("ObservationEnvelope.visibleTiles must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }

        // 验证编码后的 JSON 也不包含墙后 tile 坐标
        IdGenerator idGen = new IdGenerator.DeterministicIdGenerator("decision", "msg");
        AgentProtocol.ObservationData dataA = buildObservationDataFromEnvelope(obsA, idGen);
        AgentProtocol.Envelope envA = buildEnvelope(
                AgentProtocol.MessageType.OBSERVATION, dataA,
                new AgentProtocol.Identity(RUN_ID, FLOOR_ID, AGENT_ID, SESSION_EPOCH, REQUEST_GENERATION),
                idGen, LOGICAL_TICK);
        String jsonA = AgentProtocolCodec.encodeEnvelope(envA);

        // guardB 同理
        AgentProtocol.ObservationData dataB = buildObservationDataFromEnvelope(obsB, idGen);
        AgentProtocol.Envelope envB = buildEnvelope(
                AgentProtocol.MessageType.OBSERVATION, dataB,
                new AgentProtocol.Identity(RUN_ID, FLOOR_ID, "guard-b", SESSION_EPOCH, REQUEST_GENERATION),
                idGen, LOGICAL_TICK);
        String jsonB = AgentProtocolCodec.encodeEnvelope(envB);

        String serializedPlayer = "\"type\":\"PLAYER\",\"position\":{\"x\":"
                + playerPos.x + ",\"y\":" + playerPos.y + "}";
        String serializedPlayerTile = "\"x\":" + playerPos.x
                + ",\"y\":" + playerPos.y + ",\"type\":";
        assertTrue("guardA JSON should contain visible player",
                jsonA.contains(serializedPlayer));
        assertFalse("guardB JSON must not contain hidden player",
                jsonB.contains(serializedPlayer));
        assertFalse("guardB JSON must not expose hidden PLAYER type",
                jsonB.contains("\"type\":\"PLAYER\""));
        assertFalse("guardB JSON must not contain hidden player tile",
                jsonB.contains(serializedPlayerTile));

        // 同时验证解码后的有限知识列表
        DecodeResult decA = AgentProtocolCodec.decodeMessage(jsonA);
        assertTrue("decode A should succeed: " + describe(decA),
                decA instanceof DecodeResult.Success);
        AgentProtocol.ObservationData decodedA =
                (AgentProtocol.ObservationData) ((DecodeResult.Success) decA).envelope().data;
        boolean aHasPlayer = false;
        for (AgentProtocol.VisibleTileData t : decodedA.visibleTiles()) {
            if (t.x() == playerPos.x && t.y() == playerPos.y) {
                aHasPlayer = true;
                break;
            }
        }
        assertTrue("guardA decoded visibleTiles should contain player tile", aHasPlayer);
        assertTrue("guardA decoded visibleEntities should contain player",
                decodedA.visibleEntities().stream()
                        .anyMatch(e -> "PLAYER".equals(e.type())
                                && e.position().equals(
                                new AgentProtocol.PositionData(
                                        playerPos.x, playerPos.y))));

        DecodeResult decB = AgentProtocolCodec.decodeMessage(jsonB);
        assertTrue("decode B should succeed: " + describe(decB),
                decB instanceof DecodeResult.Success);
        AgentProtocol.ObservationData decodedB =
                (AgentProtocol.ObservationData) ((DecodeResult.Success) decB).envelope().data;
        boolean bHasPlayer = false;
        for (AgentProtocol.VisibleTileData t : decodedB.visibleTiles()) {
            if (t.x() == playerPos.x && t.y() == playerPos.y) {
                bHasPlayer = true;
                break;
            }
        }
        assertFalse("guardB decoded visibleTiles should NOT contain player tile", bHasPlayer);
        assertFalse("guardB decoded visibleEntities must not contain player",
                decodedB.visibleEntities().stream()
                        .anyMatch(e -> "PLAYER".equals(e.type())));
    }

    // ────────── P2-P05 ──────────

    /** P2-P05：未知消息类型非致命，返回 Failure 而非抛异常 */
    @Test
    public void unknown_message_type_nonfatal() {
        JsonObject obj = new JsonObject(new LinkedHashMap<>());
        obj.members().put("schemaVersion", new JsonString(AgentProtocol.ENVELOPE_VERSION));
        obj.members().put("messageId", new JsonString("msg-1"));
        obj.members().put("messageSeq", new AgentProtocolCodec.JsonNumber(0, true));
        obj.members().put("runId", new JsonString(RUN_ID));
        obj.members().put("floorId", new AgentProtocolCodec.JsonNumber(FLOOR_ID, true));
        obj.members().put("agentId", new JsonString(AGENT_ID));
        obj.members().put("sessionEpoch", new AgentProtocolCodec.JsonNumber(SESSION_EPOCH, true));
        obj.members().put("logicalTick", new AgentProtocolCodec.JsonNumber(LOGICAL_TICK, true));
        obj.members().put("type", new JsonString("future_message"));
        obj.members().put("data", new JsonObject(new LinkedHashMap<>()));

        String json = AgentProtocolCodec.writeJson(obj);
        DecodeResult result = AgentProtocolCodec.decodeMessage(json);

        assertTrue("should be Failure, not throw: " + describe(result),
                result instanceof DecodeResult.Failure);
        assertEquals(FailureReason.UNKNOWN_MESSAGE_TYPE,
                ((DecodeResult.Failure) result).failure().reason());
    }

    // ────────── P2-P06 ──────────

    /** P2-P06：未知 skill 或未知 parameter 被拒绝 */
    @Test
    public void unknown_skill_or_parameter_rejected() {
        IdGenerator idGen = new IdGenerator.DeterministicIdGenerator("decision", "msg");
        AgentProtocol.Identity identity = new AgentProtocol.Identity(
                RUN_ID, FLOOR_ID, AGENT_ID, SESSION_EPOCH, REQUEST_GENERATION);

        // 未知 skill "FLY"
        // Java enum 无法表达未知 skill，因此直接构造 wire JSON。
        String badSkillJson = buildSubmitIntentJson("FLY", "targetPosition", 3, 2,
                idGen, identity);
        DecodeResult result = AgentProtocolCodec.decodeMessage(badSkillJson);
        assertTrue("FLY should fail: " + describe(result),
                result instanceof DecodeResult.Failure);
        assertEquals(FailureReason.UNKNOWN_SKILL,
                ((DecodeResult.Failure) result).failure().reason());

        // 未知 parameter "targetRoomId"
        idGen = new IdGenerator.DeterministicIdGenerator("decision", "msg");
        String badParamJson = buildSubmitIntentJsonWithExtraParam("CHASE",
                "targetPosition", 3, 2, "targetRoomId", 5,
                idGen, identity);
        result = AgentProtocolCodec.decodeMessage(badParamJson);
        assertTrue("unknown param should fail: " + describe(result),
                result instanceof DecodeResult.Failure);
        assertEquals(FailureReason.UNKNOWN_PARAMETER,
                ((DecodeResult.Failure) result).failure().reason());
    }

    // ────────── 辅助构造方法 ──────────

    private static String describe(DecodeResult r) {
        if (r instanceof DecodeResult.Success s) {
            return "Success(type=" + s.envelope().type + ")";
        }
        if (r instanceof DecodeResult.Failure f) {
            return "Failure(" + f.failure().reason() + ": " + f.failure().detail() + ")";
        }
        return r.toString();
    }

    private static AgentProtocol.Envelope buildEnvelope(
            AgentProtocol.MessageType type,
            AgentProtocol.MessageData data,
            AgentProtocol.Identity identity,
            IdGenerator idGen,
            long logicalTick) {
        return new AgentProtocol.Envelope(
                AgentProtocol.ENVELOPE_VERSION,
                idGen.newMessageId(),
                idGen.nextMessageSeq(),
                identity.runId,
                identity.floorId,
                identity.agentId,
                identity.sessionEpoch,
                logicalTick,
                type,
                data);
    }

    private static AgentProtocol.ObservationData buildObservationData(IdGenerator idGen) {
        List<AgentProtocol.VisibleTileData> tiles = new ArrayList<>();
        tiles.add(new AgentProtocol.VisibleTileData(9, 2, "FLOOR", true));
        tiles.add(new AgentProtocol.VisibleTileData(10, 2, "WALL", false));

        List<AgentProtocol.VisibleEntityData> entities = new ArrayList<>();
        entities.add(new AgentProtocol.VisibleEntityData(
                "PLAYER", new AgentProtocol.PositionData(3, 2), 100, null));

        List<AgentProtocol.CapabilitiesData> caps = new ArrayList<>();
        AgentProtocol.CapabilitiesData cap = new AgentProtocol.CapabilitiesData(
                List.of("PATROL", "CHASE", "ATTACK", "GUARD"), 7, 10, 5);

        return new AgentProtocol.ObservationData(
                AgentProtocol.OBSERVATION_VERSION,
                idGen.newDecisionId(),
                5,
                2,
                42,
                new AgentProtocol.SelfData(
                        new AgentProtocol.PositionData(9, 2), 20),
                tiles,
                entities,
                new ArrayList<>(),
                new ArrayList<>(),
                cap);
    }

    private static AgentProtocol.ObservationData buildObservationDataFromEnvelope(
            ObservationEnvelope obs, IdGenerator idGen) {
        List<AgentProtocol.VisibleTileData> tiles = new ArrayList<>();
        for (VisibleTile t : obs.getVisibleTiles()) {
            tiles.add(new AgentProtocol.VisibleTileData(
                    t.getX(), t.getY(), t.getType().name(), t.isWalkable()));
        }

        List<AgentProtocol.VisibleEntityData> entities = new ArrayList<>();
        for (byog.Perception.VisibleEntity ve : obs.getVisibleEntities()) {
            entities.add(new AgentProtocol.VisibleEntityData(
                    ve.getType().name(),
                    new AgentProtocol.PositionData(
                            ve.getPosition().x, ve.getPosition().y),
                    ve.getVisibleHp(),
                    ve.getAgentId()));
        }

        AgentProtocol.CapabilitiesData cap = new AgentProtocol.CapabilitiesData(
                List.of("PATROL", "CHASE", "ATTACK", "GUARD"), 7, 10, 5);

        return new AgentProtocol.ObservationData(
                AgentProtocol.OBSERVATION_VERSION,
                idGen.newDecisionId(),
                obs.getObservationSeq(),
                REQUEST_GENERATION,
                obs.getObservedAtTurn(),
                new AgentProtocol.SelfData(
                        new AgentProtocol.PositionData(
                                obs.getSelfPosition().x,
                                obs.getSelfPosition().y),
                        obs.getSelfHp()),
                tiles,
                entities,
                new ArrayList<>(),
                new ArrayList<>(),
                cap);
    }

    private static AgentProtocol.SubmitIntentData buildSubmitIntentData(IdGenerator idGen) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetPosition", new AgentProtocol.PositionData(3, 2));
        AgentProtocol.IntentData intent = new AgentProtocol.IntentData(
                AgentProtocol.INTENT_VERSION,
                AgentProtocol.Skill.CHASE,
                params, 0.8, 20,
                new AgentProtocol.InterruptPolicyData(true, true, true));
        return new AgentProtocol.SubmitIntentData(
                idGen.newDecisionId(), 5, 2, intent);
    }

    private static AgentProtocol.ActionFeedbackData buildActionFeedbackData() {
        return new AgentProtocol.ActionFeedbackData(
                "decision-0", 1, "MoveAction", "SUCCESS",
                new AgentProtocol.PositionData(9, 2),
                new AgentProtocol.PositionData(10, 2),
                20, AgentProtocol.DecisionSource.REMOTE_AGENT, null);
    }

    private static AgentProtocol.CancelRequestData buildCancelRequestData() {
        return new AgentProtocol.CancelRequestData("decision-0", 2, "HARD_TIMEOUT");
    }

    private static AgentProtocol.CancelAckData buildCancelAckData() {
        return new AgentProtocol.CancelAckData("decision-0", 2);
    }

    private static AgentProtocol.WorldEventData buildWorldEventData() {
        return new AgentProtocol.WorldEventData(
                "PLAYER_SPOTTED", LOGICAL_TICK,
                new AgentProtocol.PositionData(3, 2), AGENT_ID);
    }

    /** 手动构造 submit_intent JSON，用于测试未知 skill */
    private static String buildSubmitIntentJson(
            String skill, String paramName, int px, int py,
            IdGenerator idGen, AgentProtocol.Identity identity) {
        JsonObject dataObj = new JsonObject(new LinkedHashMap<>());
        dataObj.members().put("decisionId", new JsonString(idGen.newDecisionId()));
        dataObj.members().put("observationSeq",
                new AgentProtocolCodec.JsonNumber(5, true));
        dataObj.members().put("requestGeneration",
                new AgentProtocolCodec.JsonNumber(2, true));

        JsonObject intentObj = new JsonObject(new LinkedHashMap<>());
        intentObj.members().put("intentVersion",
                new JsonString(AgentProtocol.INTENT_VERSION));
        intentObj.members().put("skill", new JsonString(skill));

        JsonObject paramsObj = new JsonObject(new LinkedHashMap<>());
        JsonObject posObj = new JsonObject(new LinkedHashMap<>());
        posObj.members().put("x", new AgentProtocolCodec.JsonNumber(px, true));
        posObj.members().put("y", new AgentProtocolCodec.JsonNumber(py, true));
        paramsObj.members().put(paramName, posObj);
        intentObj.members().put("parameters", paramsObj);
        intentObj.members().put("confidence", new AgentProtocolCodec.JsonNumber(0.8, false));
        intentObj.members().put("validForTicks", new AgentProtocolCodec.JsonNumber(20, true));
        intentObj.members().put("interruptPolicy", new AgentProtocolCodec.JsonNull());

        dataObj.members().put("intent", intentObj);

        return buildEnvelopeJson(AgentProtocol.MessageType.SUBMIT_INTENT, dataObj,
                idGen, identity);
    }

    /** 手动构造带额外参数的 submit_intent JSON */
    private static String buildSubmitIntentJsonWithExtraParam(
            String skill, String paramName, int px, int py,
            String extraParam, int extraValue,
            IdGenerator idGen, AgentProtocol.Identity identity) {
        JsonObject dataObj = new JsonObject(new LinkedHashMap<>());
        dataObj.members().put("decisionId", new JsonString(idGen.newDecisionId()));
        dataObj.members().put("observationSeq",
                new AgentProtocolCodec.JsonNumber(5, true));
        dataObj.members().put("requestGeneration",
                new AgentProtocolCodec.JsonNumber(2, true));

        JsonObject intentObj = new JsonObject(new LinkedHashMap<>());
        intentObj.members().put("intentVersion",
                new JsonString(AgentProtocol.INTENT_VERSION));
        intentObj.members().put("skill", new JsonString(skill));

        JsonObject paramsObj = new JsonObject(new LinkedHashMap<>());
        JsonObject posObj = new JsonObject(new LinkedHashMap<>());
        posObj.members().put("x", new AgentProtocolCodec.JsonNumber(px, true));
        posObj.members().put("y", new AgentProtocolCodec.JsonNumber(py, true));
        paramsObj.members().put(paramName, posObj);
        paramsObj.members().put(extraParam,
                new AgentProtocolCodec.JsonNumber(extraValue, true));
        intentObj.members().put("parameters", paramsObj);
        intentObj.members().put("confidence", new AgentProtocolCodec.JsonNumber(0.8, false));
        intentObj.members().put("validForTicks", new AgentProtocolCodec.JsonNumber(20, true));
        intentObj.members().put("interruptPolicy", new AgentProtocolCodec.JsonNull());

        dataObj.members().put("intent", intentObj);

        return buildEnvelopeJson(AgentProtocol.MessageType.SUBMIT_INTENT, dataObj,
                idGen, identity);
    }

    private static String buildEnvelopeJson(
            AgentProtocol.MessageType type, JsonObject dataObj,
            IdGenerator idGen, AgentProtocol.Identity identity) {
        JsonObject obj = new JsonObject(new LinkedHashMap<>());
        obj.members().put("schemaVersion", new JsonString(AgentProtocol.ENVELOPE_VERSION));
        obj.members().put("messageId", new JsonString(idGen.newMessageId()));
        obj.members().put("messageSeq", new AgentProtocolCodec.JsonNumber(
                idGen.nextMessageSeq(), true));
        obj.members().put("runId", new JsonString(identity.runId));
        obj.members().put("floorId", new AgentProtocolCodec.JsonNumber(identity.floorId, true));
        obj.members().put("agentId", new JsonString(identity.agentId));
        obj.members().put("sessionEpoch", new AgentProtocolCodec.JsonNumber(
                identity.sessionEpoch, true));
        obj.members().put("logicalTick", new AgentProtocolCodec.JsonNumber(LOGICAL_TICK, true));
        obj.members().put("type", new JsonString(
                type.name().toLowerCase(Locale.ROOT)));
        obj.members().put("data", dataObj);
        return AgentProtocolCodec.writeJson(obj);
    }
}
