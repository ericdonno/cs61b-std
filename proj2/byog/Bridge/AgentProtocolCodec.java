package byog.Bridge;

import byog.Helper.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 两层协议 codec：
 * - 语法层：递归下降 JSON parser/writer，输出 JsonValue 树，严格校验帧大小/深度/重复 key/转义/数字。
 * - 语义层：Envelope <-> NDJSON，按消息类型做 schema 校验。
 */
public final class AgentProtocolCodec {

    public static final int DEFAULT_MAX_FRAME_BYTES = 65536;
    public static final int DEFAULT_MAX_DEPTH = 16;

    private AgentProtocolCodec() {
    }

    // ════════════════════════════════════════════════════════════════════
    //  语法层：JsonValue 层级
    // ════════════════════════════════════════════════════════════════════

    public sealed interface JsonValue
            permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBool, JsonNull {
    }

    public record JsonObject(LinkedHashMap<String, JsonValue> members) implements JsonValue {
    }

    public record JsonArray(List<JsonValue> elements) implements JsonValue {
    }

    public record JsonString(String value) implements JsonValue {
    }

    /**
     * 保存原始十进制字面量，避免 messageSeq/sessionEpoch 等 long 经 double
     * 中转后丢失精度。
     */
    public record JsonNumber(String literal, boolean isInteger) implements JsonValue {
        public JsonNumber {
            if (literal == null || literal.isEmpty()) {
                throw new IllegalArgumentException("number literal is empty");
            }
            if (!isValidLiteral(literal, isInteger)) {
                throw new IllegalArgumentException(
                        "invalid JSON number literal: " + literal);
            }
            if (!isInteger && !Double.isFinite(Double.parseDouble(literal))) {
                throw new IllegalArgumentException(
                        "non-finite number literal: " + literal);
            }
        }

        public JsonNumber(int value, boolean ignored) {
            this(Integer.toString(value), true);
        }

        public JsonNumber(long value, boolean ignored) {
            this(Long.toString(value), true);
        }

        public JsonNumber(double value, boolean isInteger) {
            this(formatDouble(value, isInteger), isInteger);
        }

        public long longValueExact() {
            if (!isInteger) {
                throw new ArithmeticException("number is not an integer: " + literal);
            }
            return Long.parseLong(literal);
        }

        public double doubleValue() {
            double value = Double.parseDouble(literal);
            if (!Double.isFinite(value)) {
                throw new ArithmeticException("number is not finite: " + literal);
            }
            return value;
        }

        private static String formatDouble(double value, boolean isInteger) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("non-finite number: " + value);
            }
            if (isInteger) {
                if (value != Math.rint(value)
                        || value < Long.MIN_VALUE || value > Long.MAX_VALUE) {
                    throw new IllegalArgumentException(
                            "not an exact long integer: " + value);
                }
                return Long.toString((long) value);
            }
            return Double.toString(value);
        }

        private static boolean isValidLiteral(
                String value, boolean expectedInteger) {
            int index = 0;
            if (value.charAt(index) == '-') {
                index++;
                if (index == value.length()) {
                    return false;
                }
            }
            if (value.charAt(index) == '0') {
                index++;
                if (index < value.length()
                        && isAsciiDigit(value.charAt(index))) {
                    return false;
                }
            } else if (value.charAt(index) >= '1'
                    && value.charAt(index) <= '9') {
                while (index < value.length()
                        && isAsciiDigit(value.charAt(index))) {
                    index++;
                }
            } else {
                return false;
            }

            boolean integer = true;
            if (index < value.length() && value.charAt(index) == '.') {
                integer = false;
                index++;
                int fractionStart = index;
                while (index < value.length()
                        && isAsciiDigit(value.charAt(index))) {
                    index++;
                }
                if (fractionStart == index) {
                    return false;
                }
            }
            if (index < value.length()
                    && (value.charAt(index) == 'e'
                    || value.charAt(index) == 'E')) {
                integer = false;
                index++;
                if (index < value.length()
                        && (value.charAt(index) == '+'
                        || value.charAt(index) == '-')) {
                    index++;
                }
                int exponentStart = index;
                while (index < value.length()
                        && isAsciiDigit(value.charAt(index))) {
                    index++;
                }
                if (exponentStart == index) {
                    return false;
                }
            }
            return index == value.length() && integer == expectedInteger;
        }

        private static boolean isAsciiDigit(char value) {
            return value >= '0' && value <= '9';
        }
    }

    public record JsonBool(boolean value) implements JsonValue {
    }

    public record JsonNull() implements JsonValue {
    }

    /** JSON 解析失败原因 */
    public enum JsonError {
        SYNTAX_ERROR,
        DUPLICATE_KEY,
        DEPTH_EXCEEDED,
        TRAILING_GARBAGE,
        INVALID_ESCAPE,
        NON_FINITE_NUMBER,
        OVERSIZE_FRAME
    }

    /** JSON 解析异常，携带类型化原因 */
    public static final class JsonParseException extends RuntimeException {
        public final JsonError error;

        public JsonParseException(JsonError error, String message) {
            super(error + ": " + message);
            this.error = error;
        }
    }

    // ── JSON parser ──

    /**
     * 解析单行 NDJSON 文本为 JsonValue 树，严格校验帧大小、深度、重复 key、转义、数字。
     * 失败抛出 JsonParseException，携带类型化原因。
     */
    public static JsonValue parseJson(String line, int maxFrameBytes, int maxDepth) {
        if (line == null) {
            throw new JsonParseException(JsonError.SYNTAX_ERROR, "null input");
        }
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxFrameBytes) {
            throw new JsonParseException(JsonError.OVERSIZE_FRAME,
                    "frame " + bytes.length + " > " + maxFrameBytes);
        }
        Parser p = new Parser(line, maxDepth);
        p.skipWhitespace();
        JsonValue v = p.parseValue(0);
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new JsonParseException(JsonError.TRAILING_GARBAGE,
                    "unexpected trailing content at index " + p.pos);
        }
        return v;
    }

    /** 使用默认帧大小和深度限制解析 */
    public static JsonValue parseJson(String line) {
        return parseJson(line, DEFAULT_MAX_FRAME_BYTES, DEFAULT_MAX_DEPTH);
    }

    private static final class Parser {
        private final String s;
        private final int maxDepth;
        private int pos;

        Parser(String s, int maxDepth) {
            this.s = s;
            this.maxDepth = maxDepth;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        JsonValue parseValue(int depth) {
            if (depth > maxDepth) {
                throw new JsonParseException(JsonError.DEPTH_EXCEEDED,
                        "nesting depth > " + maxDepth);
            }
            skipWhitespace();
            if (atEnd()) {
                throw new JsonParseException(JsonError.SYNTAX_ERROR, "unexpected end of input");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject(depth);
                case '[' -> parseArray(depth);
                case '"' -> parseString();
                case 't', 'f' -> parseBool();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        JsonObject parseObject(int depth) {
            pos++; // skip '{'
            LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
            skipWhitespace();
            if (!atEnd() && s.charAt(pos) == '}') {
                pos++;
                return new JsonObject(members);
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || s.charAt(pos) != '"') {
                    throw new JsonParseException(JsonError.SYNTAX_ERROR,
                            "expected string key at index " + pos);
                }
                JsonString key = parseString();
                skipWhitespace();
                if (atEnd() || s.charAt(pos) != ':') {
                    throw new JsonParseException(JsonError.SYNTAX_ERROR,
                            "expected ':' at index " + pos);
                }
                pos++; // skip ':'
                JsonValue value = parseValue(depth + 1);
                if (members.put(key.value(), value) != null) {
                    throw new JsonParseException(JsonError.DUPLICATE_KEY,
                            "duplicate key: " + key.value());
                }
                skipWhitespace();
                if (atEnd()) {
                    throw new JsonParseException(JsonError.SYNTAX_ERROR,
                            "unexpected end of object");
                }
                char c = s.charAt(pos);
                if (c == '}') {
                    pos++;
                    break;
                }
                if (c != ',') {
                    throw new JsonParseException(JsonError.SYNTAX_ERROR,
                            "expected ',' or '}' at index " + pos);
                }
                pos++; // skip ','
            }
            return new JsonObject(members);
        }

        JsonArray parseArray(int depth) {
            pos++; // skip '['
            List<JsonValue> elements = new ArrayList<>();
            skipWhitespace();
            if (!atEnd() && s.charAt(pos) == ']') {
                pos++;
                return new JsonArray(elements);
            }
            while (true) {
                JsonValue value = parseValue(depth + 1);
                elements.add(value);
                skipWhitespace();
                if (atEnd()) {
                    throw new JsonParseException(JsonError.SYNTAX_ERROR,
                            "unexpected end of array");
                }
                char c = s.charAt(pos);
                if (c == ']') {
                    pos++;
                    break;
                }
                if (c != ',') {
                    throw new JsonParseException(JsonError.SYNTAX_ERROR,
                            "expected ',' or ']' at index " + pos);
                }
                pos++; // skip ','
            }
            return new JsonArray(elements);
        }

        JsonString parseString() {
            pos++; // skip opening '"'
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '"') {
                    pos++;
                    return new JsonString(sb.toString());
                }
                if (c == '\\') {
                    pos++;
                    if (pos >= s.length()) {
                        throw new JsonParseException(JsonError.INVALID_ESCAPE,
                                "trailing backslash");
                    }
                    char esc = s.charAt(pos);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 >= s.length()) {
                                throw new JsonParseException(JsonError.INVALID_ESCAPE,
                                        "incomplete \\u escape");
                            }
                            String hex = s.substring(pos + 1, pos + 5);
                            try {
                                int code = Integer.parseInt(hex, 16);
                                char decoded = (char) code;
                                if (Character.isHighSurrogate(decoded)) {
                                    if (pos + 10 >= s.length()
                                            || s.charAt(pos + 5) != '\\'
                                            || s.charAt(pos + 6) != 'u') {
                                        throw new JsonParseException(
                                                JsonError.INVALID_ESCAPE,
                                                "high surrogate without low surrogate");
                                    }
                                    String lowHex = s.substring(pos + 7, pos + 11);
                                    char low;
                                    try {
                                        low = (char) Integer.parseInt(lowHex, 16);
                                    } catch (NumberFormatException e) {
                                        throw new JsonParseException(
                                                JsonError.INVALID_ESCAPE,
                                                "invalid low surrogate: " + lowHex);
                                    }
                                    if (!Character.isLowSurrogate(low)) {
                                        throw new JsonParseException(
                                                JsonError.INVALID_ESCAPE,
                                                "invalid low surrogate: " + lowHex);
                                    }
                                    sb.append(decoded).append(low);
                                    pos += 10;
                                } else if (Character.isLowSurrogate(decoded)) {
                                    throw new JsonParseException(
                                            JsonError.INVALID_ESCAPE,
                                            "low surrogate without high surrogate");
                                } else {
                                    sb.append(decoded);
                                    pos += 4;
                                }
                            } catch (NumberFormatException e) {
                                throw new JsonParseException(JsonError.INVALID_ESCAPE,
                                        "invalid \\u escape: " + hex);
                            }
                        }
                        default -> throw new JsonParseException(JsonError.INVALID_ESCAPE,
                                "unknown escape: \\" + esc);
                    }
                    pos++;
                } else {
                    if (c < 0x20) {
                        throw new JsonParseException(JsonError.SYNTAX_ERROR,
                                "unescaped control character at index " + pos);
                    }
                    if (Character.isHighSurrogate(c)) {
                        if (pos + 1 >= s.length()
                                || !Character.isLowSurrogate(s.charAt(pos + 1))) {
                            throw new JsonParseException(JsonError.SYNTAX_ERROR,
                                    "unpaired high surrogate at index " + pos);
                        }
                        sb.append(c).append(s.charAt(pos + 1));
                        pos += 2;
                        continue;
                    }
                    if (Character.isLowSurrogate(c)) {
                        throw new JsonParseException(JsonError.SYNTAX_ERROR,
                                "unpaired low surrogate at index " + pos);
                    }
                    sb.append(c);
                    pos++;
                }
            }
            throw new JsonParseException(JsonError.SYNTAX_ERROR, "unterminated string");
        }

        JsonValue parseBool() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return new JsonBool(true);
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return new JsonBool(false);
            }
            throw new JsonParseException(JsonError.SYNTAX_ERROR,
                    "invalid literal at index " + pos);
        }

        JsonValue parseNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return new JsonNull();
            }
            throw new JsonParseException(JsonError.SYNTAX_ERROR,
                    "invalid literal at index " + pos);
        }

        JsonValue parseNumber() {
            int start = pos;
            if (s.charAt(pos) == '-') {
                pos++;
            }
            if (pos >= s.length()) {
                throw invalidNumber(start);
            }
            if (s.charAt(pos) == '0') {
                pos++;
                if (pos < s.length() && isDigitChar(s.charAt(pos))) {
                    throw invalidNumber(start);
                }
            } else if (s.charAt(pos) >= '1' && s.charAt(pos) <= '9') {
                while (pos < s.length() && isDigitChar(s.charAt(pos))) {
                    pos++;
                }
            } else {
                throw invalidNumber(start);
            }
            boolean isInteger = true;
            if (pos < s.length() && s.charAt(pos) == '.') {
                isInteger = false;
                pos++;
                int fractionStart = pos;
                while (pos < s.length() && isDigitChar(s.charAt(pos))) {
                    pos++;
                }
                if (fractionStart == pos) {
                    throw invalidNumber(start);
                }
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isInteger = false;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                int exponentStart = pos;
                while (pos < s.length() && isDigitChar(s.charAt(pos))) {
                    pos++;
                }
                if (exponentStart == pos) {
                    throw invalidNumber(start);
                }
            }
            String numStr = s.substring(start, pos);
            if (!isInteger) {
                double value;
                try {
                    value = Double.parseDouble(numStr);
                } catch (NumberFormatException e) {
                    throw invalidNumber(start);
                }
                if (!Double.isFinite(value)) {
                    throw new JsonParseException(JsonError.NON_FINITE_NUMBER,
                            "non-finite number: " + numStr);
                }
            }
            return new JsonNumber(numStr, isInteger);
        }

        private JsonParseException invalidNumber(int start) {
            return new JsonParseException(JsonError.SYNTAX_ERROR,
                    "invalid number at index " + start);
        }

        private static boolean isDigitChar(char c) {
            return c >= '0' && c <= '9';
        }
    }

    // ── JSON writer ──

    /** 将 JsonValue 序列化为紧凑 NDJSON 字符串 */
    public static String writeJson(JsonValue value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, JsonValue v) {
        if (v instanceof JsonObject obj) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonValue> e : obj.members().entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof JsonArray arr) {
            sb.append('[');
            boolean first = true;
            for (JsonValue el : arr.elements()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, el);
            }
            sb.append(']');
        } else if (v instanceof JsonString str) {
            writeString(sb, str.value());
        } else if (v instanceof JsonNumber num) {
            sb.append(num.literal());
        } else if (v instanceof JsonBool b) {
            sb.append(b.value() ? "true" : "false");
        } else if (v instanceof JsonNull) {
            sb.append("null");
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 >= s.length()
                                || !Character.isLowSurrogate(s.charAt(i + 1))) {
                            throw new IllegalArgumentException(
                                    "unpaired high surrogate at index " + i);
                        }
                        sb.append(c).append(s.charAt(++i));
                    } else if (Character.isLowSurrogate(c)) {
                        throw new IllegalArgumentException(
                                "unpaired low surrogate at index " + i);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ════════════════════════════════════════════════════════════════════
    //  语义层：Envelope <-> NDJSON
    // ════════════════════════════════════════════════════════════════════

    /** 解码失败原因 */
    public enum FailureReason {
        // 语法层
        JSON_SYNTAX,
        FRAME_TOO_LARGE,
        DEPTH_EXCEEDED,
        DUPLICATE_KEY,
        // 语义层
        SCHEMA_MISMATCH,
        MISSING_REQUIRED,
        TYPE_MISMATCH,
        OUT_OF_RANGE,
        UNKNOWN_MESSAGE_TYPE,
        UNKNOWN_SKILL,
        UNKNOWN_PARAMETER,
        UNKNOWN_FIELD,
        UNKNOWN_EVENT_TYPE,
        UNKNOWN_PAYLOAD_VERSION
    }

    public record ProtocolFailure(FailureReason reason, String detail) {
    }

    /** schema 层内部使用；decodeMessage 会将它转换为类型化 Failure。 */
    private static final class SchemaException extends RuntimeException {
        private final FailureReason reason;

        SchemaException(FailureReason reason, String detail) {
            super(detail);
            this.reason = reason;
        }
    }

    public sealed interface DecodeResult
            permits DecodeResult.Success, DecodeResult.Failure {
        record Success(AgentProtocol.Envelope envelope) implements DecodeResult {
        }

        record Failure(ProtocolFailure failure) implements DecodeResult {
        }
    }

    // ── 编码：Envelope -> NDJSON ──

    /**
     * 将 Envelope 编码为 NDJSON 字符串，按固定字段顺序输出。
     */
    public static String encodeEnvelope(AgentProtocol.Envelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("envelope is null");
        }
        if (!AgentProtocol.ENVELOPE_VERSION.equals(envelope.schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported schemaVersion: " + envelope.schemaVersion);
        }
        if (envelope.messageId == null || envelope.worldId == null
                || envelope.runId == null || envelope.agentId == null) {
            throw new IllegalArgumentException(
                    "messageId, worldId, runId and agentId are required");
        }
        JsonObject obj = new JsonObject(new LinkedHashMap<>());
        obj.members().put("schemaVersion", new JsonString(envelope.schemaVersion));
        obj.members().put("messageId", new JsonString(envelope.messageId));
        obj.members().put("messageSeq", new JsonNumber(envelope.messageSeq, true));
        obj.members().put("worldId", new JsonString(envelope.worldId));
        obj.members().put("runId", new JsonString(envelope.runId));
        obj.members().put("floorId", new JsonNumber(envelope.floorId, true));
        obj.members().put("agentId", new JsonString(envelope.agentId));
        obj.members().put("sessionEpoch", new JsonNumber(envelope.sessionEpoch, true));
        obj.members().put("logicalTick", new JsonNumber(envelope.logicalTick, true));
        obj.members().put("type", new JsonString(
                envelope.type.name().toLowerCase(Locale.ROOT)));
        obj.members().put("data", encodeData(envelope.data, envelope.type));
        return writeJson(obj);
    }

    private static JsonValue encodeData(AgentProtocol.MessageData data,
                                        AgentProtocol.MessageType type) {
        return switch (type) {
            case OBSERVATION ->
                    encodeObservationData((AgentProtocol.ObservationData) data);
            case ACTION_FEEDBACK ->
                    encodeActionFeedbackData((AgentProtocol.ActionFeedbackData) data);
            case WORLD_EVENT ->
                    encodeWorldEventData((AgentProtocol.WorldEventData) data);
            case HEARTBEAT ->
                    encodeHeartbeatData((AgentProtocol.HeartbeatData) data);
            case CANCEL_REQUEST ->
                    encodeCancelRequestData((AgentProtocol.CancelRequestData) data);
            case SUBMIT_INTENT ->
                    encodeSubmitIntentData((AgentProtocol.SubmitIntentData) data);
            case CANCEL_ACK ->
                    encodeCancelAckData((AgentProtocol.CancelAckData) data);
            case PROTOCOL_ERROR ->
                    encodeProtocolErrorData((AgentProtocol.ProtocolErrorData) data);
        };
    }

    private static JsonValue encodeObservationData(AgentProtocol.ObservationData d) {
        JsonObject obj = new JsonObject(new LinkedHashMap<>());
        obj.members().put("observationVersion", new JsonString(d.observationVersion()));
        obj.members().put("decisionId", new JsonString(d.decisionId()));
        obj.members().put("observationSeq", new JsonNumber(d.observationSeq(), true));
        obj.members().put("requestGeneration", new JsonNumber(d.requestGeneration(), true));
        obj.members().put("observedAtTurn", new JsonNumber(d.observedAtTurn(), true));
        obj.members().put("visionMode", new JsonString(d.visionMode()));
        obj.members().put("self", encodeSelf(d.self()));
        obj.members().put("visibleTiles", encodeVisibleTiles(d.visibleTiles()));
        obj.members().put("visibleEntities", encodeVisibleEntities(d.visibleEntities()));
        obj.members().put("heardEvents", encodeHeardEvents(d.heardEvents()));
        obj.members().put("pendingEvents", encodePendingEvents(d.pendingEvents()));
        obj.members().put("capabilities", encodeCapabilities(d.capabilities()));
        return obj;
    }

    private static JsonValue encodeSelf(AgentProtocol.SelfData self) {
        JsonObject obj = new JsonObject(new LinkedHashMap<>());
        obj.members().put("position", encodePosition(self.position()));
        obj.members().put("hp", new JsonNumber(self.hp(), true));
        obj.members().put("maxHp", new JsonNumber(self.maxHp(), true));
        obj.members().put("facing", new JsonString(self.facing()));
        return obj;
    }

    private static JsonValue encodeVisibleTiles(List<AgentProtocol.VisibleTileData> tiles) {
        List<JsonValue> arr = new ArrayList<>();
        for (AgentProtocol.VisibleTileData t : tiles) {
            JsonObject obj = new JsonObject(new LinkedHashMap<>());
            obj.members().put("x", new JsonNumber(t.x(), true));
            obj.members().put("y", new JsonNumber(t.y(), true));
            obj.members().put("type", new JsonString(t.type()));
            obj.members().put("walkable", new JsonBool(t.walkable()));
            arr.add(obj);
        }
        return new JsonArray(arr);
    }

    private static JsonValue encodeVisibleEntities(List<AgentProtocol.VisibleEntityData> entities) {
        List<JsonValue> arr = new ArrayList<>();
        for (AgentProtocol.VisibleEntityData e : entities) {
            JsonObject obj = new JsonObject(new LinkedHashMap<>());
            obj.members().put("type", new JsonString(e.type()));
            obj.members().put("position", encodePosition(e.position()));
            obj.members().put("visibleHp", new JsonNumber(e.visibleHp(), true));
            obj.members().put("agentId", e.agentId() == null
                    ? new JsonNull() : new JsonString(e.agentId()));
            arr.add(obj);
        }
        return new JsonArray(arr);
    }

    private static JsonValue encodeHeardEvents(List<AgentProtocol.HeardEventData> events) {
        List<JsonValue> arr = new ArrayList<>();
        for (AgentProtocol.HeardEventData e : events) {
            JsonObject obj = new JsonObject(new LinkedHashMap<>());
            obj.members().put("soundType", new JsonString(e.soundType()));
            obj.members().put("sourcePosition", encodePosition(e.sourcePosition()));
            obj.members().put("turn", new JsonNumber(e.turn(), true));
            arr.add(obj);
        }
        return new JsonArray(arr);
    }

    private static JsonValue encodePendingEvents(List<AgentProtocol.WorldEventData> events) {
        List<JsonValue> arr = new ArrayList<>();
        for (AgentProtocol.WorldEventData e : events) {
            arr.add(encodeWorldEventData(e));
        }
        return new JsonArray(arr);
    }

    private static JsonValue encodeCapabilities(AgentProtocol.CapabilitiesData cap) {
        JsonObject obj = new JsonObject(new LinkedHashMap<>());
        List<JsonValue> skills = new ArrayList<>();
        for (String s : cap.supportedSkills()) {
            skills.add(new JsonString(s));
        }
        obj.members().put("supportedSkills", new JsonArray(skills));
        obj.members().put("sightRange", new JsonNumber(cap.sightRange(), true));
        obj.members().put("attackDamage", new JsonNumber(cap.attackDamage(), true));
        obj.members().put("moveInterval", new JsonNumber(cap.moveInterval(), true));
        return obj;
    }

    private static JsonValue encodeSubmitIntentData(AgentProtocol.SubmitIntentData d) {
        JsonObject obj = new JsonObject(new LinkedHashMap<>());
        obj.members().put("decisionId", new JsonString(d.decisionId()));
        obj.members().put("observationSeq", new JsonNumber(d.observationSeq(), true));
        obj.members().put("requestGeneration", new JsonNumber(d.requestGeneration(), true));
        obj.members().put("intent", encodeIntent(d.intent()));
        return obj;
    }

    private static JsonValue encodeIntent(AgentProtocol.IntentData intent) {
        JsonObject obj = new JsonObject(new LinkedHashMap<>());
        obj.members().put("intentVersion", new JsonString(intent.intentVersion()));
        obj.members().put("skill", new JsonString(intent.skill().name()));
        obj.members().put("parameters", encodeParameters(intent.parameters()));
        obj.members().put("confidence", new JsonNumber(intent.confidence(), false));
        obj.members().put("validForTicks", new JsonNumber(intent.validForTicks(), true));
        obj.members().put("interruptPolicy", encodeInterruptPolicy(intent.interruptPolicy()));
        return obj;
    }

    private static JsonValue encodeParameters(Map<String, Object> params) {
        JsonObject obj = new JsonObject(new LinkedHashMap<>());
        for (Map.Entry<String, Object> e : params.entrySet()) {
            obj.members().put(e.getKey(), encodeObject(e.getValue()));
        }
        return obj;
    }

    private static JsonValue encodeObject(Object o) {
        if (o == null) {
            return new JsonNull();
        }
        if (o instanceof Boolean b) {
            return new JsonBool(b);
        }
        if (o instanceof Integer i) {
            return new JsonNumber(i, true);
        }
        if (o instanceof Long l) {
            return new JsonNumber(l, true);
        }
        if (o instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("non-finite number: " + d);
            }
            return new JsonNumber(d, n instanceof Integer || n instanceof Long);
        }
        if (o instanceof String s) {
            return new JsonString(s);
        }
        if (o instanceof AgentProtocol.PositionData p) {
            return encodePosition(p);
        }
        throw new IllegalArgumentException("unsupported parameter type: " + o.getClass());
    }

    private static JsonValue encodeInterruptPolicy(AgentProtocol.InterruptPolicyData p) {
        if (p == null) {
            return new JsonNull();
        }
        JsonObject obj = new JsonObject(new LinkedHashMap<>());
        obj.members().put("engageVisiblePlayer", new JsonBool(p.engageVisiblePlayer()));
        obj.members().put("respondToAdjacentThreat", new JsonBool(p.respondToAdjacentThreat()));
        obj.members().put("allowLocalReroute", new JsonBool(p.allowLocalReroute()));
        return obj;
    }

    private static JsonValue encodePosition(AgentProtocol.PositionData p) {
        if (p == null) {
            return new JsonNull();
        }
        JsonObject obj = new JsonObject(new LinkedHashMap<>());
        obj.members().put("x", new JsonNumber(p.x(), true));
        obj.members().put("y", new JsonNumber(p.y(), true));
        return obj;
    }

    private static JsonValue encodeActionFeedbackData(AgentProtocol.ActionFeedbackData d) {
        JsonObject obj = new JsonObject(new LinkedHashMap<>());
        obj.members().put("decisionId", new JsonString(d.decisionId()));
        obj.members().put("actionIndex", new JsonNumber(d.actionIndex(), true));
        obj.members().put("actionType", new JsonString(d.actionType()));
        obj.members().put("result", new JsonString(d.result()));
        obj.members().put("beforePosition", encodePosition(d.beforePosition()));
        obj.members().put("afterPosition", encodePosition(d.afterPosition()));
        obj.members().put("selfHp", new JsonNumber(d.selfHp(), true));
        obj.members().put("decisionSource", new JsonString(d.decisionSource().name()));
        obj.members().put("overrideReason", d.overrideReason() == null
                ? new JsonNull() : new JsonString(d.overrideReason()));
        return obj;
    }

    private static JsonValue encodeCancelRequestData(AgentProtocol.CancelRequestData d) {
        JsonObject obj = new JsonObject(new LinkedHashMap<>());
        obj.members().put("decisionId", new JsonString(d.decisionId()));
        obj.members().put("requestGeneration", new JsonNumber(d.requestGeneration(), true));
        obj.members().put("reason", new JsonString(d.reason()));
        return obj;
    }

    private static JsonValue encodeCancelAckData(AgentProtocol.CancelAckData d) {
        JsonObject obj = new JsonObject(new LinkedHashMap<>());
        obj.members().put("decisionId", new JsonString(d.decisionId()));
        obj.members().put("requestGeneration", new JsonNumber(d.requestGeneration(), true));
        return obj;
    }

    private static JsonValue encodeWorldEventData(AgentProtocol.WorldEventData d) {
        JsonObject obj = new JsonObject(new LinkedHashMap<>());
        obj.members().put("eventType", new JsonString(d.eventType()));
        obj.members().put("logicalTick", new JsonNumber(d.logicalTick(), true));
        obj.members().put("relatedPosition", encodePosition(d.relatedPosition()));
        obj.members().put("relatedEntityId", d.relatedEntityId() == null
                ? new JsonNull() : new JsonString(d.relatedEntityId()));
        return obj;
    }

    private static JsonValue encodeHeartbeatData(AgentProtocol.HeartbeatData d) {
        JsonObject obj = new JsonObject(new LinkedHashMap<>());
        obj.members().put("logicalTick", new JsonNumber(d.logicalTick(), true));
        return obj;
    }

    private static JsonValue encodeProtocolErrorData(AgentProtocol.ProtocolErrorData d) {
        JsonObject obj = new JsonObject(new LinkedHashMap<>());
        obj.members().put("reason", new JsonString(d.reason()));
        obj.members().put("offendingType", new JsonString(d.offendingType()));
        return obj;
    }

    // ── 解码：NDJSON -> DecodeResult ──

    /**
     * 解码单行 NDJSON 为 Envelope，先做语法解析再做 schema 校验。
     * 失败返回 Failure，不抛异常。
     */
    public static DecodeResult decodeMessage(String line) {
        return decodeMessage(line, DEFAULT_MAX_FRAME_BYTES, DEFAULT_MAX_DEPTH);
    }

    /**
     * 解码单行 NDJSON 为 Envelope，可指定帧大小和深度限制。
     */
    public static DecodeResult decodeMessage(String line,
                                              int maxFrameBytes, int maxDepth) {
        JsonValue root;
        try {
            root = parseJson(line, maxFrameBytes, maxDepth);
        } catch (JsonParseException e) {
            FailureReason reason = switch (e.error) {
                case OVERSIZE_FRAME -> FailureReason.FRAME_TOO_LARGE;
                case DEPTH_EXCEEDED -> FailureReason.DEPTH_EXCEEDED;
                case DUPLICATE_KEY -> FailureReason.DUPLICATE_KEY;
                default -> FailureReason.JSON_SYNTAX;
            };
            return new DecodeResult.Failure(new ProtocolFailure(reason, e.getMessage()));
        }
        if (!(root instanceof JsonObject obj)) {
            return new DecodeResult.Failure(new ProtocolFailure(
                    FailureReason.JSON_SYNTAX, "root is not object"));
        }
        try {
            return decodeEnvelope(obj);
        } catch (SchemaException e) {
            return fail(e.reason, e.getMessage());
        }
    }

    private static DecodeResult decodeEnvelope(JsonObject obj) {
        ensureOnlyFields(obj, "envelope",
                "schemaVersion", "messageId", "messageSeq", "worldId", "runId",
                "floorId", "agentId", "sessionEpoch", "logicalTick", "type",
                "data");
        String schemaVersion = requireString(obj, "schemaVersion");
        if (schemaVersion == null) {
            return fail(FailureReason.MISSING_REQUIRED, "schemaVersion");
        }
        if (!AgentProtocol.ENVELOPE_VERSION.equals(schemaVersion)) {
            return fail(FailureReason.SCHEMA_MISMATCH,
                    "schemaVersion: " + schemaVersion);
        }
        String messageId = requireString(obj, "messageId");
        if (messageId == null) {
            return fail(FailureReason.MISSING_REQUIRED, "messageId");
        }
        Long messageSeq = requireInt(obj, "messageSeq");
        if (messageSeq == null) {
            return fail(FailureReason.MISSING_REQUIRED, "messageSeq");
        }
        String worldId = requireString(obj, "worldId");
        if (worldId == null) {
            return fail(FailureReason.MISSING_REQUIRED, "worldId");
        }
        String runId = requireString(obj, "runId");
        if (runId == null) {
            return fail(FailureReason.MISSING_REQUIRED, "runId");
        }
        Long floorId = requireInt(obj, "floorId");
        if (floorId == null) {
            return fail(FailureReason.MISSING_REQUIRED, "floorId");
        }
        String agentId = requireString(obj, "agentId");
        if (agentId == null) {
            return fail(FailureReason.MISSING_REQUIRED, "agentId");
        }
        Long sessionEpoch = requireInt(obj, "sessionEpoch");
        if (sessionEpoch == null) {
            return fail(FailureReason.MISSING_REQUIRED, "sessionEpoch");
        }
        Long logicalTick = requireInt(obj, "logicalTick");
        if (logicalTick == null) {
            return fail(FailureReason.MISSING_REQUIRED, "logicalTick");
        }
        String typeStr = requireString(obj, "type");
        if (typeStr == null) {
            return fail(FailureReason.MISSING_REQUIRED, "type");
        }
        AgentProtocol.MessageType type;
        try {
            type = AgentProtocol.MessageType.valueOf(
                    typeStr.toUpperCase(Locale.ROOT));
            if (!type.name().toLowerCase(Locale.ROOT).equals(typeStr)) {
                throw new IllegalArgumentException("message type case mismatch");
            }
        } catch (IllegalArgumentException e) {
            return fail(FailureReason.UNKNOWN_MESSAGE_TYPE, "type: " + typeStr);
        }
        JsonValue dataVal = obj.members().get("data");
        if (dataVal == null) {
            return fail(FailureReason.MISSING_REQUIRED, "data");
        }
        if (!(dataVal instanceof JsonObject dataObj)) {
            return fail(FailureReason.TYPE_MISMATCH, "data is not object");
        }
        DecodeResult dataResult = decodeData(dataObj, type);
        if (dataResult instanceof DecodeResult.Failure f) {
            return f;
        }
        AgentProtocol.MessageData data =
                ((DecodeResult.Success) dataResult).envelope().data;
        AgentProtocol.Envelope envelope = new AgentProtocol.Envelope(
                schemaVersion, messageId, messageSeq,
                worldId, runId, toIntExact(floorId, "floorId"), agentId,
                sessionEpoch, logicalTick,
                type, data);
        return new DecodeResult.Success(envelope);
    }

    private static DecodeResult decodeData(JsonObject obj,
                                            AgentProtocol.MessageType type) {
        return switch (type) {
            case OBSERVATION -> decodeObservationData(obj);
            case SUBMIT_INTENT -> decodeSubmitIntentData(obj);
            case ACTION_FEEDBACK -> decodeActionFeedbackData(obj);
            case CANCEL_REQUEST -> decodeCancelRequestData(obj);
            case CANCEL_ACK -> decodeCancelAckData(obj);
            case WORLD_EVENT -> decodeWorldEventData(obj);
            case HEARTBEAT -> decodeHeartbeatData(obj);
            case PROTOCOL_ERROR -> decodeProtocolErrorData(obj);
        };
    }

    private static DecodeResult decodeObservationData(JsonObject obj) {
        ensureOnlyFields(obj, "observation",
                "observationVersion", "decisionId", "observationSeq",
                "requestGeneration", "observedAtTurn", "visionMode", "self",
                "visibleTiles", "visibleEntities", "heardEvents",
                "pendingEvents", "capabilities");
        String observationVersion = requireString(obj, "observationVersion");
        if (observationVersion == null) {
            return fail(FailureReason.MISSING_REQUIRED, "observationVersion");
        }
        if (!AgentProtocol.OBSERVATION_VERSION.equals(observationVersion)) {
            return fail(FailureReason.UNKNOWN_PAYLOAD_VERSION,
                    "observationVersion: " + observationVersion);
        }
        String decisionId = requireString(obj, "decisionId");
        if (decisionId == null) {
            return fail(FailureReason.MISSING_REQUIRED, "decisionId");
        }
        Long observationSeq = requireInt(obj, "observationSeq");
        if (observationSeq == null) {
            return fail(FailureReason.MISSING_REQUIRED, "observationSeq");
        }
        Long requestGeneration = requireInt(obj, "requestGeneration");
        if (requestGeneration == null) {
            return fail(FailureReason.MISSING_REQUIRED, "requestGeneration");
        }
        Long observedAtTurn = requireInt(obj, "observedAtTurn");
        if (observedAtTurn == null) {
            return fail(FailureReason.MISSING_REQUIRED, "observedAtTurn");
        }
        String visionMode = requireString(obj, "visionMode");
        if (visionMode == null) {
            return fail(FailureReason.MISSING_REQUIRED, "visionMode");
        }
        if (!"DIRECTIONAL".equals(visionMode)
                && !"OMNIDIRECTIONAL".equals(visionMode)) {
            return fail(FailureReason.UNKNOWN_FIELD,
                    "visionMode: " + visionMode);
        }
        JsonObject selfObj = requireObject(obj, "self");
        ensureOnlyFields(selfObj, "observation.self",
                "position", "hp", "maxHp", "facing");
        AgentProtocol.PositionData selfPosition =
                decodeRequiredPositionFromField(selfObj, "position");
        long selfHp = requireInt(selfObj, "hp");
        long selfMaxHp = requireInt(selfObj, "maxHp");
        String selfFacing = requireString(selfObj, "facing");
        if (selfFacing == null) {
            return fail(FailureReason.MISSING_REQUIRED, "self.facing");
        }
        if (!isValidFacing(selfFacing)) {
            return fail(FailureReason.UNKNOWN_FIELD,
                    "self.facing: " + selfFacing);
        }
        List<AgentProtocol.VisibleTileData> visibleTiles = decodeVisibleTiles(obj.members().get("visibleTiles"));
        List<AgentProtocol.VisibleEntityData> visibleEntities = decodeVisibleEntities(obj.members().get("visibleEntities"));
        List<AgentProtocol.HeardEventData> heardEvents = decodeHeardEvents(obj.members().get("heardEvents"));
        List<AgentProtocol.WorldEventData> pendingEvents = decodeWorldEventList(obj.members().get("pendingEvents"));
        AgentProtocol.CapabilitiesData capabilities = decodeCapabilities(obj.members().get("capabilities"));
        return new DecodeResult.Success(new AgentProtocol.Envelope(
                null, null, 0, null, null, 0, null, 0, 0,
                AgentProtocol.MessageType.OBSERVATION,
                new AgentProtocol.ObservationData(
                        observationVersion, decisionId, observationSeq,
                        requestGeneration, observedAtTurn, visionMode,
                        new AgentProtocol.SelfData(
                                selfPosition, toIntExact(selfHp, "self.hp"),
                                toIntExact(selfMaxHp, "self.maxHp"),
                                selfFacing),
                        visibleTiles, visibleEntities,
                        heardEvents, pendingEvents, capabilities)));
    }

    /** 合法 facing 白名单：NORTH/EAST/SOUTH/WEST。 */
    private static boolean isValidFacing(String facing) {
        return "NORTH".equals(facing) || "EAST".equals(facing)
                || "SOUTH".equals(facing) || "WEST".equals(facing);
    }

    private static DecodeResult decodeSubmitIntentData(JsonObject obj) {
        ensureOnlyFields(obj, "submit_intent",
                "decisionId", "observationSeq", "requestGeneration", "intent");
        String decisionId = requireString(obj, "decisionId");
        if (decisionId == null) {
            return fail(FailureReason.MISSING_REQUIRED, "decisionId");
        }
        Long observationSeq = requireInt(obj, "observationSeq");
        if (observationSeq == null) {
            return fail(FailureReason.MISSING_REQUIRED, "observationSeq");
        }
        Long requestGeneration = requireInt(obj, "requestGeneration");
        if (requestGeneration == null) {
            return fail(FailureReason.MISSING_REQUIRED, "requestGeneration");
        }
        JsonObject intentObj = requireObject(obj, "intent");
        IntentDecodeResult intentResult = decodeIntent(intentObj);
        if (intentResult.failure() != null) {
            return new DecodeResult.Failure(intentResult.failure());
        }
        AgentProtocol.IntentData intent = intentResult.intent();
        return new DecodeResult.Success(new AgentProtocol.Envelope(
                null, null, 0, null, null, 0, null, 0, 0,
                AgentProtocol.MessageType.SUBMIT_INTENT,
                new AgentProtocol.SubmitIntentData(
                        decisionId, observationSeq, requestGeneration, intent)));
    }

    private record IntentDecodeResult(
            AgentProtocol.IntentData intent, ProtocolFailure failure) {
    }

    private static IntentDecodeResult decodeIntent(JsonObject obj) {
        ensureOnlyFields(obj, "intent",
                "intentVersion", "skill", "parameters", "confidence",
                "validForTicks", "interruptPolicy");
        String intentVersion = requireString(obj, "intentVersion");
        if (intentVersion == null) {
            return intentFailure(FailureReason.MISSING_REQUIRED, "intentVersion");
        }
        if (!AgentProtocol.INTENT_VERSION.equals(intentVersion)) {
            return intentFailure(FailureReason.UNKNOWN_PAYLOAD_VERSION,
                    "intentVersion: " + intentVersion);
        }
        String skillStr = requireString(obj, "skill");
        if (skillStr == null) {
            return intentFailure(FailureReason.MISSING_REQUIRED, "skill");
        }
        AgentProtocol.Skill skill;
        try {
            skill = AgentProtocol.Skill.valueOf(skillStr);
        } catch (IllegalArgumentException e) {
            return intentFailure(FailureReason.UNKNOWN_SKILL, "skill: " + skillStr);
        }
        JsonObject paramsObj = requireObject(obj, "parameters");
        Map<String, Object> params = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> e : paramsObj.members().entrySet()) {
            if (!isAllowedParameter(skill, e.getKey())) {
                return intentFailure(FailureReason.UNKNOWN_PARAMETER,
                        "skill=" + skill + " param=" + e.getKey());
            }
            if (!(e.getValue() instanceof JsonObject positionObj)) {
                throw new SchemaException(FailureReason.TYPE_MISMATCH,
                        "parameters." + e.getKey() + " must be object");
            }
            params.put(e.getKey(), decodePosition(
                    positionObj, "parameters." + e.getKey()));
        }
        // CHASE/ATTACK/GUARD 必填 targetPosition
        if (skill != AgentProtocol.Skill.PATROL) {
            if (!params.containsKey("targetPosition")) {
                return intentFailure(FailureReason.MISSING_REQUIRED,
                        "targetPosition for skill=" + skill);
            }
        }
        Double confidence = requireDouble(obj, "confidence");
        if (confidence == null) {
            return intentFailure(FailureReason.MISSING_REQUIRED, "confidence");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            return intentFailure(FailureReason.OUT_OF_RANGE, "confidence: " + confidence);
        }
        Long validForTicks = requireInt(obj, "validForTicks");
        if (validForTicks == null) {
            return intentFailure(FailureReason.MISSING_REQUIRED, "validForTicks");
        }
        if (validForTicks < 1 || validForTicks > 60) {
            return intentFailure(FailureReason.OUT_OF_RANGE, "validForTicks: " + validForTicks);
        }
        // interruptPolicy 可选
        AgentProtocol.InterruptPolicyData policy = null;
        JsonValue policyVal = obj.members().get("interruptPolicy");
        if (policyVal instanceof JsonObject policyObj) {
            policy = decodeInterruptPolicy(policyObj);
        } else if (policyVal != null && !(policyVal instanceof JsonNull)) {
            return intentFailure(FailureReason.TYPE_MISMATCH, "interruptPolicy");
        }
        return new IntentDecodeResult(new AgentProtocol.IntentData(
                intentVersion, skill, params, confidence,
                validForTicks.intValue(), policy), null);
    }

    private static IntentDecodeResult intentFailure(
            FailureReason reason, String detail) {
        Logger.debug("Protocol decode failure: %s - %s", reason, detail);
        return new IntentDecodeResult(null, new ProtocolFailure(reason, detail));
    }

    /** 检查参数 key 是否属于该 skill 允许的集合 */
    private static boolean isAllowedParameter(AgentProtocol.Skill skill, String key) {
        return switch (key) {
            case "targetPosition" -> true; // PATROL 可选，其余 skill 必填
            default -> false;
        };
    }

    private static AgentProtocol.InterruptPolicyData decodeInterruptPolicy(JsonObject obj) {
        ensureOnlyFields(obj, "interruptPolicy",
                "engageVisiblePlayer", "respondToAdjacentThreat",
                "allowLocalReroute");
        Boolean engage = requireBool(obj, "engageVisiblePlayer");
        Boolean respond = requireBool(obj, "respondToAdjacentThreat");
        Boolean allow = requireBool(obj, "allowLocalReroute");
        return new AgentProtocol.InterruptPolicyData(
                engage, respond, allow);
    }

    private static DecodeResult decodeActionFeedbackData(JsonObject obj) {
        ensureOnlyFields(obj, "action_feedback",
                "decisionId", "actionIndex", "actionType", "result",
                "beforePosition", "afterPosition", "selfHp",
                "decisionSource", "overrideReason");
        String decisionId = requireString(obj, "decisionId");
        if (decisionId == null) {
            return fail(FailureReason.MISSING_REQUIRED, "decisionId");
        }
        Long actionIndex = requireInt(obj, "actionIndex");
        if (actionIndex == null) {
            return fail(FailureReason.MISSING_REQUIRED, "actionIndex");
        }
        String actionType = requireString(obj, "actionType");
        if (actionType == null) {
            return fail(FailureReason.MISSING_REQUIRED, "actionType");
        }
        String result = requireString(obj, "result");
        if (result == null) {
            return fail(FailureReason.MISSING_REQUIRED, "result");
        }
        AgentProtocol.PositionData before =
                decodeRequiredPositionFromField(obj, "beforePosition");
        AgentProtocol.PositionData after =
                decodeRequiredPositionFromField(obj, "afterPosition");
        Long selfHp = requireInt(obj, "selfHp");
        if (selfHp == null) {
            return fail(FailureReason.MISSING_REQUIRED, "selfHp");
        }
        String sourceStr = requireString(obj, "decisionSource");
        if (sourceStr == null) {
            return fail(FailureReason.MISSING_REQUIRED, "decisionSource");
        }
        AgentProtocol.DecisionSource source;
        try {
            source = AgentProtocol.DecisionSource.valueOf(sourceStr);
        } catch (IllegalArgumentException e) {
            return fail(FailureReason.SCHEMA_MISMATCH,
                    "decisionSource: " + sourceStr);
        }
        String overrideReason = requireNullableString(obj, "overrideReason");
        return new DecodeResult.Success(new AgentProtocol.Envelope(
                null, null, 0, null, null, 0, null, 0, 0,
                AgentProtocol.MessageType.ACTION_FEEDBACK,
                new AgentProtocol.ActionFeedbackData(
                        decisionId, toIntExact(actionIndex, "actionIndex"),
                        actionType, result, before, after,
                        toIntExact(selfHp, "selfHp"), source, overrideReason)));
    }

    private static DecodeResult decodeCancelRequestData(JsonObject obj) {
        ensureOnlyFields(obj, "cancel_request",
                "decisionId", "requestGeneration", "reason");
        String decisionId = requireString(obj, "decisionId");
        if (decisionId == null) {
            return fail(FailureReason.MISSING_REQUIRED, "decisionId");
        }
        Long requestGeneration = requireInt(obj, "requestGeneration");
        if (requestGeneration == null) {
            return fail(FailureReason.MISSING_REQUIRED, "requestGeneration");
        }
        String reason = requireString(obj, "reason");
        return new DecodeResult.Success(new AgentProtocol.Envelope(
                null, null, 0, null, null, 0, null, 0, 0,
                AgentProtocol.MessageType.CANCEL_REQUEST,
                new AgentProtocol.CancelRequestData(
                        decisionId, requestGeneration, reason)));
    }

    private static DecodeResult decodeCancelAckData(JsonObject obj) {
        ensureOnlyFields(obj, "cancel_ack",
                "decisionId", "requestGeneration");
        String decisionId = requireString(obj, "decisionId");
        if (decisionId == null) {
            return fail(FailureReason.MISSING_REQUIRED, "decisionId");
        }
        Long requestGeneration = requireInt(obj, "requestGeneration");
        if (requestGeneration == null) {
            return fail(FailureReason.MISSING_REQUIRED, "requestGeneration");
        }
        return new DecodeResult.Success(new AgentProtocol.Envelope(
                null, null, 0, null, null, 0, null, 0, 0,
                AgentProtocol.MessageType.CANCEL_ACK,
                new AgentProtocol.CancelAckData(decisionId, requestGeneration)));
    }

    private static DecodeResult decodeWorldEventData(JsonObject obj) {
        ensureOnlyFields(obj, "world_event",
                "eventType", "logicalTick", "relatedPosition",
                "relatedEntityId");
        String eventType = requireString(obj, "eventType");
        if (eventType == null) {
            return fail(FailureReason.MISSING_REQUIRED, "eventType");
        }
        try {
            AgentProtocol.WorldEventType.valueOf(eventType);
        } catch (IllegalArgumentException e) {
            return fail(FailureReason.UNKNOWN_EVENT_TYPE, "eventType: " + eventType);
        }
        Long logicalTick = requireInt(obj, "logicalTick");
        if (logicalTick == null) {
            return fail(FailureReason.MISSING_REQUIRED, "logicalTick");
        }
        AgentProtocol.PositionData pos =
                decodeNullablePositionFromField(obj, "relatedPosition");
        String relatedEntityId =
                requireNullableString(obj, "relatedEntityId");
        return new DecodeResult.Success(new AgentProtocol.Envelope(
                null, null, 0, null, null, 0, null, 0, 0,
                AgentProtocol.MessageType.WORLD_EVENT,
                new AgentProtocol.WorldEventData(
                        eventType, logicalTick, pos, relatedEntityId)));
    }

    private static DecodeResult decodeHeartbeatData(JsonObject obj) {
        ensureOnlyFields(obj, "heartbeat", "logicalTick");
        Long logicalTick = requireInt(obj, "logicalTick");
        if (logicalTick == null) {
            return fail(FailureReason.MISSING_REQUIRED, "logicalTick");
        }
        return new DecodeResult.Success(new AgentProtocol.Envelope(
                null, null, 0, null, null, 0, null, 0, 0,
                AgentProtocol.MessageType.HEARTBEAT,
                new AgentProtocol.HeartbeatData(logicalTick)));
    }

    private static DecodeResult decodeProtocolErrorData(JsonObject obj) {
        ensureOnlyFields(obj, "protocol_error", "reason", "offendingType");
        String reason = requireString(obj, "reason");
        if (reason == null) {
            return fail(FailureReason.MISSING_REQUIRED, "reason");
        }
        String offendingType = requireString(obj, "offendingType");
        return new DecodeResult.Success(new AgentProtocol.Envelope(
                null, null, 0, null, null, 0, null, 0, 0,
                AgentProtocol.MessageType.PROTOCOL_ERROR,
                new AgentProtocol.ProtocolErrorData(reason, offendingType)));
    }

    // ── 辅助解码方法 ──

    private static List<AgentProtocol.VisibleTileData> decodeVisibleTiles(JsonValue val) {
        List<AgentProtocol.VisibleTileData> result = new ArrayList<>();
        JsonArray arr = requireArrayValue(val, "visibleTiles");
        int index = 0;
        for (JsonValue el : arr.elements()) {
            if (!(el instanceof JsonObject obj)) {
                throw new SchemaException(FailureReason.TYPE_MISMATCH,
                        "visibleTiles[" + index + "] must be object");
            }
            ensureOnlyFields(obj, "visibleTiles[" + index + "]",
                    "x", "y", "type", "walkable");
            Long x = requireInt(obj, "x");
            Long y = requireInt(obj, "y");
            String type = requireString(obj, "type");
            Boolean walkable = requireBool(obj, "walkable");
            if (!isValidTileType(type)) {
                throw new SchemaException(FailureReason.UNKNOWN_FIELD,
                        "visibleTiles[" + index + "].type: " + type);
            }
            result.add(new AgentProtocol.VisibleTileData(
                    toIntExact(x, "visibleTiles[" + index + "].x"),
                    toIntExact(y, "visibleTiles[" + index + "].y"),
                    type, walkable));
            index++;
        }
        return result;
    }

    /** 合法可见 tile 类型白名单：与 {@code VisibleTile.TileType} 一致，不含 APPLE。 */
    private static boolean isValidTileType(String type) {
        return switch (type) {
            case "FLOOR", "WALL", "STAIRS", "NOTHING", "GRASS", "WATER",
                    "FLOWER", "LOCKED_DOOR", "UNLOCKED_DOOR", "SAND",
                    "MOUNTAIN", "TREE", "UNKNOWN" -> true;
            default -> false;
        };
    }

    private static List<AgentProtocol.VisibleEntityData> decodeVisibleEntities(JsonValue val) {
        List<AgentProtocol.VisibleEntityData> result = new ArrayList<>();
        JsonArray arr = requireArrayValue(val, "visibleEntities");
        int index = 0;
        for (JsonValue el : arr.elements()) {
            if (!(el instanceof JsonObject obj)) {
                throw new SchemaException(FailureReason.TYPE_MISMATCH,
                        "visibleEntities[" + index + "] must be object");
            }
            ensureOnlyFields(obj, "visibleEntities[" + index + "]",
                    "type", "position", "visibleHp", "agentId");
            String type = requireString(obj, "type");
            AgentProtocol.PositionData pos =
                    decodeRequiredPositionFromField(obj, "position");
            Long hp = requireInt(obj, "visibleHp");
            String agentId = requireNullableString(obj, "agentId");
            result.add(new AgentProtocol.VisibleEntityData(
                    type, pos,
                    toIntExact(hp, "visibleEntities[" + index + "].visibleHp"),
                    agentId));
            index++;
        }
        return result;
    }

    private static List<AgentProtocol.HeardEventData> decodeHeardEvents(JsonValue val) {
        List<AgentProtocol.HeardEventData> result = new ArrayList<>();
        JsonArray arr = requireArrayValue(val, "heardEvents");
        int index = 0;
        for (JsonValue el : arr.elements()) {
            if (!(el instanceof JsonObject obj)) {
                throw new SchemaException(FailureReason.TYPE_MISMATCH,
                        "heardEvents[" + index + "] must be object");
            }
            ensureOnlyFields(obj, "heardEvents[" + index + "]",
                    "soundType", "sourcePosition", "turn");
            String soundType = requireString(obj, "soundType");
            AgentProtocol.PositionData pos =
                    decodeRequiredPositionFromField(obj, "sourcePosition");
            Long turn = requireInt(obj, "turn");
            result.add(new AgentProtocol.HeardEventData(soundType, pos, turn));
            index++;
        }
        return result;
    }

    private static List<AgentProtocol.WorldEventData> decodeWorldEventList(JsonValue val) {
        List<AgentProtocol.WorldEventData> result = new ArrayList<>();
        JsonArray arr = requireArrayValue(val, "pendingEvents");
        int index = 0;
        for (JsonValue el : arr.elements()) {
            if (!(el instanceof JsonObject obj)) {
                throw new SchemaException(FailureReason.TYPE_MISMATCH,
                        "pendingEvents[" + index + "] must be object");
            }
            ensureOnlyFields(obj, "pendingEvents[" + index + "]",
                    "eventType", "logicalTick", "relatedPosition",
                    "relatedEntityId");
            String eventType = requireString(obj, "eventType");
            try {
                AgentProtocol.WorldEventType.valueOf(eventType);
            } catch (IllegalArgumentException e) {
                throw new SchemaException(FailureReason.UNKNOWN_EVENT_TYPE,
                        "eventType: " + eventType);
            }
            Long logicalTick = requireInt(obj, "logicalTick");
            AgentProtocol.PositionData pos =
                    decodeNullablePositionFromField(obj, "relatedPosition");
            String relatedEntityId =
                    requireNullableString(obj, "relatedEntityId");
            result.add(new AgentProtocol.WorldEventData(
                    eventType, logicalTick, pos, relatedEntityId));
            index++;
        }
        return result;
    }

    private static AgentProtocol.CapabilitiesData decodeCapabilities(JsonValue val) {
        if (val == null || val instanceof JsonNull) {
            throw new SchemaException(FailureReason.MISSING_REQUIRED,
                    "capabilities");
        }
        if (!(val instanceof JsonObject obj)) {
            throw new SchemaException(FailureReason.TYPE_MISMATCH,
                    "capabilities must be object");
        }
        ensureOnlyFields(obj, "capabilities",
                "supportedSkills", "sightRange", "attackDamage", "moveInterval");
        List<String> skills = new ArrayList<>();
        JsonValue skillsVal = obj.members().get("supportedSkills");
        JsonArray skillsArray =
                requireArrayValue(skillsVal, "capabilities.supportedSkills");
        int index = 0;
        for (JsonValue el : skillsArray.elements()) {
            if (!(el instanceof JsonString skill)) {
                throw new SchemaException(FailureReason.TYPE_MISMATCH,
                        "capabilities.supportedSkills[" + index
                                + "] must be string");
            }
            try {
                AgentProtocol.Skill.valueOf(skill.value());
            } catch (IllegalArgumentException e) {
                throw new SchemaException(FailureReason.UNKNOWN_SKILL,
                        "capabilities.supportedSkills[" + index
                                + "]: " + skill.value());
            }
            skills.add(skill.value());
            index++;
        }
        Long sightRange = requireInt(obj, "sightRange");
        Long attackDamage = requireInt(obj, "attackDamage");
        Long moveInterval = requireInt(obj, "moveInterval");
        return new AgentProtocol.CapabilitiesData(
                skills,
                toIntExact(sightRange, "capabilities.sightRange"),
                toIntExact(attackDamage, "capabilities.attackDamage"),
                toIntExact(moveInterval, "capabilities.moveInterval"));
    }

    private static AgentProtocol.PositionData decodePosition(
            JsonObject obj, String context) {
        ensureOnlyFields(obj, context, "x", "y");
        Long x = requireInt(obj, "x");
        Long y = requireInt(obj, "y");
        return new AgentProtocol.PositionData(
                toIntExact(x, context + ".x"),
                toIntExact(y, context + ".y"));
    }

    private static AgentProtocol.PositionData decodeRequiredPositionFromField(
            JsonObject obj, String field) {
        JsonValue val = obj.members().get(field);
        if (val == null || val instanceof JsonNull) {
            throw new SchemaException(FailureReason.MISSING_REQUIRED, field);
        }
        if (!(val instanceof JsonObject posObj)) {
            throw new SchemaException(FailureReason.TYPE_MISMATCH,
                    field + " must be object");
        }
        return decodePosition(posObj, field);
    }

    private static AgentProtocol.PositionData decodeNullablePositionFromField(
            JsonObject obj, String field) {
        if (!obj.members().containsKey(field)) {
            throw new SchemaException(FailureReason.MISSING_REQUIRED, field);
        }
        JsonValue val = obj.members().get(field);
        if (val instanceof JsonNull) {
            return null;
        }
        if (!(val instanceof JsonObject posObj)) {
            throw new SchemaException(FailureReason.TYPE_MISMATCH,
                    field + " must be object or null");
        }
        return decodePosition(posObj, field);
    }

    // ── 字段提取工具 ──

    private static String requireString(JsonObject obj, String key) {
        if (!obj.members().containsKey(key)) {
            throw new SchemaException(FailureReason.MISSING_REQUIRED, key);
        }
        JsonValue v = obj.members().get(key);
        if (v instanceof JsonString s) {
            return s.value();
        }
        throw new SchemaException(FailureReason.TYPE_MISMATCH,
                key + " must be string");
    }

    private static String requireNullableString(JsonObject obj, String key) {
        if (!obj.members().containsKey(key)) {
            throw new SchemaException(FailureReason.MISSING_REQUIRED, key);
        }
        JsonValue v = obj.members().get(key);
        if (v instanceof JsonNull) {
            return null;
        }
        if (v instanceof JsonString s) {
            return s.value();
        }
        throw new SchemaException(FailureReason.TYPE_MISMATCH,
                key + " must be string or null");
    }

    private static Long requireInt(JsonObject obj, String key) {
        if (!obj.members().containsKey(key)) {
            throw new SchemaException(FailureReason.MISSING_REQUIRED, key);
        }
        JsonValue v = obj.members().get(key);
        if (!(v instanceof JsonNumber n) || !n.isInteger()) {
            throw new SchemaException(FailureReason.TYPE_MISMATCH,
                    key + " must be integer");
        }
        try {
            return n.longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new SchemaException(FailureReason.OUT_OF_RANGE,
                    key + " is outside long range");
        }
    }

    private static Double requireDouble(JsonObject obj, String key) {
        if (!obj.members().containsKey(key)) {
            throw new SchemaException(FailureReason.MISSING_REQUIRED, key);
        }
        JsonValue v = obj.members().get(key);
        if (!(v instanceof JsonNumber n)) {
            throw new SchemaException(FailureReason.TYPE_MISMATCH,
                    key + " must be number");
        }
        try {
            return n.doubleValue();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new SchemaException(FailureReason.OUT_OF_RANGE,
                    key + " is not a finite double");
        }
    }

    private static Boolean requireBool(JsonObject obj, String key) {
        if (!obj.members().containsKey(key)) {
            throw new SchemaException(FailureReason.MISSING_REQUIRED, key);
        }
        JsonValue v = obj.members().get(key);
        if (v instanceof JsonBool b) {
            return b.value();
        }
        throw new SchemaException(FailureReason.TYPE_MISMATCH,
                key + " must be boolean");
    }

    private static JsonObject requireObject(JsonObject obj, String key) {
        if (!obj.members().containsKey(key)) {
            throw new SchemaException(FailureReason.MISSING_REQUIRED, key);
        }
        JsonValue value = obj.members().get(key);
        if (value instanceof JsonObject child) {
            return child;
        }
        throw new SchemaException(FailureReason.TYPE_MISMATCH,
                key + " must be object");
    }

    private static JsonArray requireArrayValue(JsonValue value, String field) {
        if (value == null || value instanceof JsonNull) {
            throw new SchemaException(FailureReason.MISSING_REQUIRED, field);
        }
        if (value instanceof JsonArray array) {
            return array;
        }
        throw new SchemaException(FailureReason.TYPE_MISMATCH,
                field + " must be array");
    }

    private static int toIntExact(long value, String field) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new SchemaException(FailureReason.OUT_OF_RANGE,
                    field + " is outside int range");
        }
        return (int) value;
    }

    private static void ensureOnlyFields(
            JsonObject obj, String context, String... allowedFields) {
        for (String actual : obj.members().keySet()) {
            boolean allowed = false;
            for (String candidate : allowedFields) {
                if (candidate.equals(actual)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw new SchemaException(FailureReason.UNKNOWN_FIELD,
                        context + "." + actual);
            }
        }
    }

    private static DecodeResult.Failure fail(FailureReason reason, String detail) {
        Logger.debug("Protocol decode failure: %s - %s", reason, detail);
        return new DecodeResult.Failure(new ProtocolFailure(reason, detail));
    }
}
