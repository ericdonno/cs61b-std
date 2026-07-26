# Step 2.1 实施计划：固化 observation snapshot 与协议 codec

## Summary

本 Step 做三件事：

1. **VisibleTile snapshot** — 在 PerceptionSystem 创建 observation 时固化可见 tile 的类型与可行走性，使序列化器无需读取 live world。
2. **AgentProtocol + AgentProtocolCodec** — 手写严格 JSON codec + 所有 Phase 2 消息类型的编解码，不引入第三方依赖。
3. **IdGenerator** — 确定性 test identity seam：生产用 UUID，测试用前缀递增计数器。

产出物：`Phase2ProtocolTest` 中 6 个测试用例（P2-P01 至 P2-P06）全部通过。

***

## Current State Analysis

已有事实（Phase 1 基线）：

* `ObservationEnvelope` 已不可变，持有 `visibleMask` + `walkableMask`，但**没有 tile 类型快照**。构造时接收 `TETile[][] world` 引用仅用于构建 `walkableMask`，构造完即丢弃引用。

* `PerceptionSystem.computeObservation` 是 `ObservationEnvelope` 的**唯一创建者**。

* `ObservationEnvelope` 已有 `visibleEntities`、`heardEvents`（当前始终为空列表）。

* 仓库**无 Bridge 包**、**无 JSON 库**（classpath 仅 algs4/junit/jh61b/stdlib）。

* Phase 1 测试是绿色回归基线（`Phase1TestSuite`），不能破坏。

* `Tileset` 使用单例常量（FLOOR/WALL/STAIRS/NOTHING 等），`TETile.equals` 按 `character` 比较。注意 `Tileset.FLOOR_FOV` 是运行时 FOV 渲染专用 tile，character 与 FLOOR 相同但实例不同。

Step 2.1 明确不做的事（留给 Step 2.2–2.9）：

* MonotonicClock、AgentSession、AgentSessionConfig、AgentHandler（Step 2.4）

* AiTickContext、ReflexObservation、ActionOutcome（Step 2.2）

* IntentLease、DecisionValidator、IntentArbiter（Step 2.3）

* GameConfig / properties 修改（Step 2.9）

* Game loop / Enemy 三阶段方法（Step 2.2）

***

## Proposed Changes

### 1. `byog/Perception/VisibleTile.java`（新建）

不可变可见 tile 快照。序列化时直接读它，不需要 live world。

```java
public final class VisibleTile {
    public enum TileType { FLOOR, WALL, STAIRS, NOTHING, GRASS, WATER, FLOWER,
                           LOCKED_DOOR, UNLOCKED_DOOR, SAND, MOUNTAIN, TREE, UNKNOWN }

    private final int x;
    private final int y;
    private final TileType type;
    private final boolean walkable;

    public VisibleTile(int x, int y, TileType type, boolean walkable) { ... }

    /** 从 TETile 映射到 TileType（== 比较 Tileset 单例，兜底返回 UNKNOWN） */
    public static TileType tileTypeOf(TETile tile) { ... }
    // getters: getX, getY, getType, isWalkable
}
```

映射规则：

* 按 `==` 逐一比较 Tileset 单例常量（FLOOR/WALL/STAIRS/NOTHING/GRASS/WATER/FLOWER/LOCKED\_DOOR/UNLOCKED\_DOOR/SAND/MOUNTAIN/TREE）。

* **显式添加** **`FLOOR_FOV → FLOOR`**：`Tileset.FLOOR_FOV` 与 `Tileset.FLOOR` 是不同实例，`==` 匹配不到，需要单独分支。

* 未匹配则返回 `UNKNOWN`。不依赖 `description()` 字符串解析。

* 实体 tile（ENEMY、PLAYER、PLAYER\_HIT、ATTACK\_FLASH）理论上不会出现在 world 数组中（它们由 EntityManager 管理），但以防万一也映射为 `UNKNOWN` 或 `FLOOR`。

**关键问题：实体覆盖位置上的 tile 类型**

`EntityManager` 管理实体位置，但 world 数组本身不会被实体 tile 覆盖（实体的 tile 是独立管理的）。`PerceptionSystem.computeObservation` 直接从 `TETile[][] world` 读取，读到的是地形 tile。因此不存在"站在敌人身上读到 ENEMY tile"的问题。如果实际运行中确实出现 entity tile 污染 world 数组，则在 `tileTypeOf` 中将 `ENEMY`/`PLAYER`/`PLAYER_HIT`/`ATTACK_FLASH` 映射为 `FLOOR`（因为这些实体必然站在可行走地形上）。

### 2. `byog/Perception/ObservationEnvelope.java`（修改）

在现有字段基础上**新增** `List<VisibleTile> visibleTiles`。

* 构造方法新增 `List<VisibleTile> visibleTiles` 参数（放在 `heardEvents` 之后、`world` 之前）。

* 用 `Collections.unmodifiableList(new ArrayList<>(visibleTiles))` 防御性复制。

* 新增 `getVisibleTiles()` 返回不可变列表。

* **保持** `visibleMask` / `walkableMask` / `world` 参数与现有逻辑不变，保护 Phase 0/1 golden。

* `countVisibleTiles()` 仍统计 `visibleMask`，不改名（trace 字段不变）。

### 3. `byog/Perception/PerceptionSystem.java`（修改）

在 `computeObservation` 第一步 FOV 计算完成后（`visibleMask` 已就绪），**新增**遍历可见 tile 构建 `List<VisibleTile>`：

```java
List<VisibleTile> visibleTiles = new ArrayList<>();
for (int x = 0; x < world.length; x++) {
    for (int y = 0; y < world[0].length; y++) {
        if (visibleMask[x][y]) {
            TETile tile = world[x][y];
            TileType type = VisibleTile.tileTypeOf(tile);
            boolean walkable = tile != Tileset.WALL && tile != Tileset.NOTHING;
            visibleTiles.add(new VisibleTile(x, y, type, walkable));
        }
    }
}
```

将 `visibleTiles` 传入 `new ObservationEnvelope(...)`。**不改变** FOV 规则、可见实体收集、heardEvents 逻辑。

### 4. `byog/Bridge/AgentProtocol.java`（新建）

纯数据容器类，承载所有消息 DTO、身份元组与枚举。**不含** socket/IO/游戏逻辑。

```java
public final class AgentProtocol {
    // 版本常量
    public static final String ENVELOPE_VERSION = "phase2.session.v1";
    public static final String OBSERVATION_VERSION = "private-observation.v1";
    public static final String INTENT_VERSION = "strategic-intent.v1";

    // 消息方向与类型 —— 8 种消息，双向
    public enum MessageType {
        OBSERVATION,      // Java → Python：发起 intent 请求
        ACTION_FEEDBACK,  // Java → Python：动作执行结果
        WORLD_EVENT,      // Java → Python / Python → Java：最小世界事件
        HEARTBEAT,        // Java → Python：低优先级心跳，可合并/丢弃
        CANCEL_REQUEST,   // Java → Python：取消正在推理的决策
        SUBMIT_INTENT,    // Python → Java：唯一可采纳的决策消息
        CANCEL_ACK,       // Python → Java：确认取消
        PROTOCOL_ERROR    // 双向：诊断协议失败
    }

    // Phase 2 允许的远程技能白名单
    public enum Skill { PATROL, CHASE, ATTACK, GUARD }

    // 决策来源
    public enum DecisionSource { REMOTE_AGENT, LOCAL_FALLBACK }

    // WorldEvent 白名单（只有这 5 种可以通过 eventType 校验）
    public enum WorldEventType {
        PLAN_BLOCKED, PLAN_EXHAUSTED, PLAYER_SPOTTED,
        REFLEX_OVERRIDE_STARTED, REFLEX_OVERRIDE_ENDED
    }

    // 完整身份元组
    public static final class Identity {
        public final String runId;
        public final int floorId;
        public final String agentId;
        public final long sessionEpoch;
        public final long requestGeneration;
    }

    // 通用信封
    public static final class Envelope {
        public final String schemaVersion;   // 固定 = ENVELOPE_VERSION
        public final String messageId;
        public final long messageSeq;        // 每 sessionEpoch 从 0 单调递增
        public final String runId;
        public final int floorId;
        public final String agentId;
        public final long sessionEpoch;
        public final long logicalTick;
        public final MessageType type;
        public final MessageData data;
    }

    // ── tagged union：data 的具体类型由 type 决定 ──
    public sealed interface MessageData
        permits ObservationData, SubmitIntentData, ActionFeedbackData,
                CancelRequestData, CancelAckData, WorldEventData,
                HeartbeatData, ProtocolErrorData {}

    // ── 各消息 data record ──

    public record ObservationData(
        String observationVersion,
        String decisionId,
        long observationSeq,
        long requestGeneration,
        long observedAtTurn,
        SelfData self,                  // { "position":{...}, "hp":... }
        List<VisibleTileData> visibleTiles,
        List<VisibleEntityData> visibleEntities,
        List<HeardEventData> heardEvents,
        List<WorldEventData> pendingEvents,
        CapabilitiesData capabilities
    ) implements MessageData {}

    public record SubmitIntentData(
        String decisionId,
        long observationSeq,
        long requestGeneration,
        IntentData intent
    ) implements MessageData {}

    public record IntentData(
        String intentVersion,           // 固定 = INTENT_VERSION
        Skill skill,
        Map<String,Object> parameters,  // 按 skill 白名单校验 key
        double confidence,              // 0.0 ~ 1.0
        int validForTicks,              // 1 ~ 60
        InterruptPolicyData interruptPolicy  // 缺失时用 skill 默认值，由 DecisionValidator 填充
    ) {}

    public record InterruptPolicyData(
        boolean engageVisiblePlayer,
        boolean respondToAdjacentThreat,
        boolean allowLocalReroute
    ) {}

    public record ActionFeedbackData(
        String decisionId,
        int actionIndex,
        String actionType,
        String result,
        PositionData beforePosition,
        PositionData afterPosition,
        int selfHp,
        DecisionSource decisionSource,
        String overrideReason        // null 表示无覆盖
    ) implements MessageData {}

    public record CancelRequestData(
        String decisionId,
        long requestGeneration,
        String reason                // 仅诊断用
    ) implements MessageData {}

    public record CancelAckData(
        String decisionId,
        long requestGeneration
    ) implements MessageData {}

    public record WorldEventData(
        String eventType,            // 必须是 WorldEventType 白名单之一
        long logicalTick,
        PositionData relatedPosition,
        String relatedEntityId
    ) implements MessageData {}

    public record HeartbeatData(long logicalTick) implements MessageData {}

    public record ProtocolErrorData(
        String reason,
        String offendingType         // 触发错误的消息 type
    ) implements MessageData {}

    // ── 嵌套 record ──

    public record PositionData(int x, int y) {}

    public record SelfData(PositionData position, int hp) {}

    public record VisibleTileData(int x, int y, String type, boolean walkable) {}

    public record VisibleEntityData(
        String type,                 // "PLAYER" / "ENEMY" / "OTHER"
        PositionData position,
        int visibleHp,
        String agentId               // 仅 ENEMY 有值，否则 null
    ) {}

    public record HeardEventData(
        String soundType,            // "ATTACK" / "MOVE" / "DEATH" / "ALERT"
        PositionData sourcePosition,
        long turn
    ) {}

    public record CapabilitiesData(
        List<String> supportedSkills,  // 如 ["PATROL","CHASE","ATTACK","GUARD"]
        int sightRange,
        int attackDamage,
        int moveInterval
    ) {}
}
```

设计要点：

* `Identity` 持有完整身份元组（run/floor/agent/epoch/generation），后续 AgentSession 用这些字段校验响应是否有效。

* `Envelope.data` 用 sealed interface + record 表达 tagged union，按 `type` 选择解码路径，类型安全且 switch 穷尽。

* `IntentData.parameters` 用 `Map<String,Object>` 保留灵活性，schema decoder 按 skill 做 key 白名单校验。CHASE/ATTACK/GUARD 要求必填 `targetPosition`；PATROL 不要求 `targetPosition`。

* `InterruptPolicyData` 在 schema 中标记为**可选字段**。缺失时不报错，由 DecisionValidator 按 skill 类型填充默认值（codec 不做此语义判断）。

* `WorldEventData.eventType` 在 schema decoder 中校验是否在 `WorldEventType` 白名单内，不在则拒绝。

* `CapabilitiesData` 在 Step 2.1 的测试中手动构造。真实填充等到 Step 2.2 的 `Enemy.collectAgentUpdates` 阶段，由 Enemy 传入 `sightRange`/`attackDamage`/`moveInterval`。

* record 只保证字段引用不可重新赋值；所有 `List` / `Map` 字段仍必须在
  compact constructor 中做防御性复制，防止消息排队后被外部修改。

* `Envelope` 构造时必须校验 `MessageType` 与 `MessageData` 的具体类型匹配，
  禁止编码出 `"type":"world_event"` 但 data 实际是 observation 的消息。

* `encodeEnvelope` 必须覆盖全部 8 种 `MessageType`，包括出方向的 `PROTOCOL_ERROR`。

### 5. `byog/Bridge/AgentProtocolCodec.java`（新建）

两层架构：JSON parser/writer（语法层）+ schema encode/decode（语义层）。

#### 5.1 JSON parser（语法层）

```java
public final class AgentProtocolCodec {

    // JsonValue sealed 层级
    public sealed interface JsonValue
        permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBool, JsonNull {}

    // 解析单行 NDJSON 文本 → JsonValue 树，严格校验
    public static JsonValue parseJson(String line, int maxFrameBytes, int maxDepth);
    // 失败抛 JsonParseException（类型化原因：DUPLICATE_KEY / DEPTH_EXCEEDED /
    //   TRAILING_GARBAGE / INVALID_ESCAPE / NON_FINITE_NUMBER / OVERSIZE_FRAME /
    //   SYNTAX_ERROR）

    // JsonValue → 紧凑 NDJSON 字符串
    public static String writeJson(JsonValue value);
}
```

严格校验规则：

* 帧大小 ≤ 65,536 UTF-8 bytes（解析前先检查 `line.getBytes(UTF_8).length`）。

* 嵌套深度 ≤ 16（parser 跟踪当前深度，超过即抛 `DEPTH_EXCEEDED`）。

* 拒绝重复 key（`JsonObject` 用 `LinkedHashMap` 检测 put 返回的非 null 旧值）。

* 拒绝尾随垃圾（解析完根值后必须到达字符串末尾，否则 `TRAILING_GARBAGE`）。

* 拒绝非法转义（只允许 JSON 标准转义序列 `\" \\ \/ \b \f \n \r \t \uXXXX`）。

* 拒绝非有限数字（NaN、Infinity、-Infinity → `NON_FINITE_NUMBER`）。

* 不用 regex，不用 JavaScript engine。

#### 5.2 Schema encode/decode（语义层）

```java
// 编码：Envelope → NDJSON 字符串
public static String encodeEnvelope(AgentProtocol.Envelope envelope);

// 解码：NDJSON 字符串 → DecodeResult
public static DecodeResult decodeMessage(String line);

public sealed interface DecodeResult {
    record Success(AgentProtocol.Envelope envelope) implements DecodeResult;
    record Failure(ProtocolFailure failure) implements DecodeResult;
}

public record ProtocolFailure(FailureReason reason, String detail) {}

public enum FailureReason {
    // 语法层
    JSON_SYNTAX,
    FRAME_TOO_LARGE,
    DEPTH_EXCEEDED,
    DUPLICATE_KEY,
    // 语义层
    SCHEMA_MISMATCH,          // schemaVersion 不是 phase2.session.v1
    MISSING_REQUIRED,         // 必填字段缺失
    TYPE_MISMATCH,            // 字段类型错误（期望 string 却是 number）
    OUT_OF_RANGE,             // 数值超出有效范围（confidence、validForTicks 等）
    UNKNOWN_MESSAGE_TYPE,     // type 不在 MessageType 枚举中
    UNKNOWN_SKILL,            // skill 不在 Skill 白名单中
    UNKNOWN_PARAMETER,        // parameters 包含该 skill 不识别的 key
    UNKNOWN_FIELD,            // schema 未声明的普通字段
    UNKNOWN_EVENT_TYPE,       // eventType 不在 WorldEventType 白名单中
    UNKNOWN_PAYLOAD_VERSION   // observationVersion 或 intentVersion 不匹配
}
```

Semantic decode 规则：

* 先 `parseJson` 得到 `JsonValue`，语法失败 → `Failure(JSON_SYNTAX/FRAME_TOO_LARGE/DEPTH_EXCEEDED/DUPLICATE_KEY)`。

* 读取 envelope 顶层字段：`schemaVersion` 必须等于 `"phase2.session.v1"`，否则 `SCHEMA_MISMATCH`。

* 字段白名单检查：envelope 与各 payload 只允许 schema 声明的字段；多余字段返回
  `Failure(UNKNOWN_FIELD)`。协议扩展必须通过新的 schema/payload version 明确引入，
  不能在同一版本中静默忽略。

* `type` 未知 → `Failure(UNKNOWN_MESSAGE_TYPE)`。调用方可以记录 protocol\_error 后丢弃，但 decode 层本身不自动升级为非致命 success。

* 按 `type` 分支解码 `data`：

  * `observation`：`observationVersion` 必须 = `"private-observation.v1"`；必填字段缺失 → `MISSING_REQUIRED`。

  * `submit_intent`：`intentVersion` 必须 = `"strategic-intent.v1"`；`skill` 必须在白名单 → 否则 `UNKNOWN_SKILL`；`parameters` 的 key 按 skill 白名单校验 → 否则 `UNKNOWN_PARAMETER`；`confidence` ∈ \[0,1]、`validForTicks` ∈ \[1,60] → 否则 `OUT_OF_RANGE`。

  * `world_event`：`eventType` 必须在 `WorldEventType` 白名单中 → 否则 `UNKNOWN_EVENT_TYPE`。

  * `action_feedback` / `cancel_request` / `cancel_ack` / `heartbeat` / `protocol_error`：按各自字段 schema 校验。

* `interruptPolicy` 在 `submit_intent` 中标记为**可选**，缺失时不报错（留给 DecisionValidator 填充默认值）。

编码规则：

* 按固定字段顺序输出（锁定字节序列，便于 golden 比对）：schemaVersion → messageId → messageSeq → runId → floorId → agentId → sessionEpoch → logicalTick → type → data。

* 字符串统一 UTF-8 转义（风格对齐 AgentTrace 现有的 `escapeJson`）。

* 数字：整数保留原始十进制字面量或使用独立 `long` 表示，禁止先转成 `double`；
  浮点用 `Double.toString`。这样 `messageSeq/sessionEpoch` 在大于 `2^53` 时仍能无损往返。
  `requireInt` 必须拒绝小数和超出目标 Java 类型范围的值。

### 6. `byog/Bridge/IdGenerator.java`（新建）

```java
public interface IdGenerator {
    String newDecisionId();   // 例 "decision-<n>" 或 UUID
    String newMessageId();    // 例 "msg-<n>" 或 UUID
    long nextMessageSeq();    // 每 sessionEpoch 从 0 单调递增
}

// 生产实现
public final class UuidIdGenerator implements IdGenerator { ... }

// 测试实现：确定性计数器，构造时注入前缀
public final class DeterministicIdGenerator implements IdGenerator {
    public DeterministicIdGenerator(String decisionPrefix, String messagePrefix) { ... }
}
```

`DeterministicIdGenerator` 让 `Phase2ProtocolTest` 产生可重复的 ID。`nextMessageSeq()` 从 0 起单调递增，重连时的重置由 AgentSession 负责。

### 7. `byog/Test/Phase2ProtocolTest.java`（新建）

覆盖 P2-P01 至 P2-P06。使用 `DeterministicIdGenerator` 和固定 `Identity`，纯 Java、无网络、无 sleep。

| 测试     | 方法名                                         | 断言要点                                                                                                                                          |
| ------ | ------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| P2-P01 | `protocol_round_trip`                       | 对 8 种消息类型分别 `encodeEnvelope` → `decodeMessage`，断言所有字段无丢失（含嵌套的 position、visibleTiles、interruptPolicy 等）                                        |
| P2-P02 | `malformed_or_duplicate_key_rejected`       | 构造坏 JSON（缺引号、重复 key、错误类型、尾随垃圾、非法转义、非法数字、原始控制字符、未知字段），断言 `decodeMessage` 返回对应 `Failure`，不抛异常                         |
| P2-P03 | `oversize_and_deep_frame_rejected`          | 构造 >64KiB 字符串和 >16 层嵌套，断言 `FRAME_TOO_LARGE` / `DEPTH_EXCEEDED`                                                                                |
| P2-P04 | `observation_serializes_only_visible_tiles` | 用 Phase1EncounterHarness 场景生成 observation，断言墙后 tile 坐标、实体坐标和实体类型均不在原始 NDJSON；再解码并检查 `visibleTiles/visibleEntities`                      |
| P2-P05 | `unknown_message_type_nonfatal`             | 构造 `type="future_message"` 的合法 JSON，断言 `decodeMessage` 返回 `Failure(UNKNOWN_MESSAGE_TYPE)`，不抛异常                                                |
| P2-P06 | `unknown_skill_or_parameter_rejected`       | 构造 `skill="FLY"` 和 `skill="CHASE"` 带未知 parameter `targetRoomId` 的 submit\_intent，断言返回 `Failure(UNKNOWN_SKILL)` / `Failure(UNKNOWN_PARAMETER)` |

P2-P04 的具体做法：复用 `Phase1EncounterHarness.baselineTwoGuardsV1()`，对 guardA（能看见玩家）和 guardB（看不见玩家）分别调用 `PerceptionSystem.computeObservation`，得到 `ObservationEnvelope`。手动构造 `ObservationData` 填充 envelope 再编码，解码后检查 JSON 中的 `visibleTiles` 列表。guardA 的 visibleTiles 应包含玩家位置对应的 tile，guardB 的不应包含。

测试内提供 `buildEnvelope(MessageType, MessageData, Identity, IdGenerator, long logicalTick)` 辅助工厂，减少样板代码。

***

## Assumptions & Decisions

1. **JSON codec 采用中间 JsonValue 树架构**：duplicate key / depth / trailing garbage 检测在语法层统一处理，schema 层只做语义校验。不做 parser 直出 DTO，因为解析过程中需要先检测语法错误再决定语义。

2. **sealed interface + record 表达 tagged union**：JDK 19 可用，类型安全、switch 穷尽、字段不可变。

3. **VisibleTile.TileType 显式枚举**：不解析 `description()` 字符串。世界生成器若新增 tile 类型，只需扩展枚举 + `tileTypeOf` 分支。`FLOOR_FOV` 显式映射为 `FLOOR`；`ENEMY`/`PLAYER`/`PLAYER_HIT`/`ATTACK_FLASH` 理论上不出现在 world 数组中，但防御性映射为 `FLOOR`。

4. **ObservationEnvelope 向后兼容**：保留 `world` 参数与 `walkableMask` 逻辑全不变，仅新增 `visibleTiles` 参数和字段。Phase 0/1 golden 不受影响。

5. **IdGenerator 放在** **`byog.Bridge`** **包**：它是协议层身份生成器，后续 AgentSession 也是 Bridge 包的消费者，同包引用自然。

6. **DecodeResult 用 sealed interface 而非异常**：拒绝一个消息（如未知 skill、未知 type）是"正常业务结果"，不应用异常表达。

7. **P2-P05 非致命语义**：decode 层返回 `Failure(UNKNOWN_MESSAGE_TYPE)` 但**不抛异常**。由 AgentSession（Step 2.4）决定记录一条 `protocol_error` 出方向消息后丢弃，不修改 Enemy 状态。这一步只验证 decode 行为。

8. **InterruptPolicy 在 decode 中标记为可选**：缺失时不报 `MISSING_REQUIRED`，由 DecisionValidator（Step 2.3）按 skill 类型填入安全默认值。codec 不做语义默认值逻辑。

9. **CapabilitiesData 在 Step 2.1 测试中手动构造**：`ObservationData` 的 `CapabilitiesData` 字段在这里先设计好结构，但真实填充等到 Step 2.2 由 `Enemy.collectAgentUpdates` 从 Enemy 属性传入。

10. **编码输出字段顺序固定**：便于 golden 测试和协议调试。所有 `encodeEnvelope` 输出按同一顺序排列字段。

***

## Verification Steps

**编译**：

```powershell
$javaSources = Get-ChildItem byog -Recurse -Filter *.java | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -cp "..\library-sp18\javalib\*" -d out $javaSources
```

**运行 Phase2ProtocolTest**：

```powershell
java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" `
  org.junit.runner.JUnitCore byog.Test.Phase2ProtocolTest
```

预期：6 个测试全部通过。

**Phase 0/1 回归**（确保 ObservationEnvelope 改动未破坏 golden）：

```powershell
java "-Dfile.encoding=UTF-8" -cp "out;..\library-sp18\javalib\*" `
  org.junit.runner.JUnitCore byog.Test.Phase1TestSuite
```

预期：全部通过，golden 字节不变。

***

## 文件清单

| 文件                                         | 操作                               |
| ------------------------------------------ | -------------------------------- |
| `byog/Perception/VisibleTile.java`         | 新建                               |
| `byog/Perception/ObservationEnvelope.java` | 修改（新增 visibleTiles 字段与构造参数）      |
| `byog/Perception/PerceptionSystem.java`    | 修改（构建 visibleTiles 并传入 envelope） |
| `byog/Bridge/AgentProtocol.java`           | 新建                               |
| `byog/Bridge/AgentProtocolCodec.java`      | 新建                               |
| `byog/Bridge/IdGenerator.java`             | 新建                               |
| `byog/Test/Phase2ProtocolTest.java`        | 新建                               |
