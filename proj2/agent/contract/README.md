# 智能体通信契约

当前权威 Java 定义位于 `byog/Bridge/AgentProtocol.java` 和
`AgentProtocolCodec.java`。所有外部运行时都必须与其信封字段、载荷版本、
消息类型、数值范围、帧大小限制和严格校验行为保持一致。

这里的 contract（契约）是各进程共同遵守的通信规则；envelope（消息信封）保存
消息类型、身份和序号等通用字段；payload（载荷）是 `data` 中某类消息自己的内容；
codec（编解码器）负责在类型化对象与 JSON 字节之间转换；frame（帧）是一条以换行符
结束的完整 NDJSON 消息。“权威 Java 定义”表示出现歧义时以这些 Java 类型和校验规则为准。

## 当前锁定版本（一次性硬切换，不提供旧版本双读）

| 契约 | 版本 | 说明 |
|------|------|------|
| Envelope | `agent-session.v1` | 顶层必填 `worldId` 身份字段 |
| Observation | `private-observation.v2` | `self` 含 `maxHp`、`facing`；顶层含 `visionMode` |
| Intent | `strategic-intent.v1` | 保持 Phase 2 语义；Phase 3 开始时整体升级到 v2 |

旧版本 `phase2.session.v1`、`private-observation.v1` 与旧 trace 一律拒绝，
不存在降级解码或宽松字段路径。

## Envelope 身份字段

顶层必填字段顺序（编解码顺序固定）：`schemaVersion`、`messageId`、
`messageSeq`、`worldId`、`runId`、`floorId`、`agentId`、`sessionEpoch`、
`logicalTick`、`type`、`data`。

- `worldId`：命名世界的稳定身份，随存档保持；读档/换层不改变。
- `runId`：每次运行重建；用于拒绝旧连接与迟到响应。
- `floorId`：楼层边界；换层后新楼层身份。
- `agentId`：当前楼层内敌人的稳定身份。

## Observation（`private-observation.v2`）

- `visionMode` ∈ {`DIRECTIONAL`, `OMNIDIRECTIONAL`}。
- `self` 必填 `position`、`hp`、`maxHp`、`facing`
  （`facing` ∈ {`NORTH`, `EAST`, `SOUTH`, `WEST`}）。
- `visibleTiles[].type` 必须属于合法 tile 类型白名单；
  **`APPLE` 不是合法 tile type**——苹果对 Agent 等价于 `FLOOR`。
- 严格 exact-field：未知字段、缺失必填字段、非法枚举一律拒绝，
  不忽略、不映射默认值。

## 跨语言共享 fixtures

`fixtures/` 目录保存权威正反样例，Java 与 Python runner 测试共用：

- `valid-observation-directional.json` / `valid-observation-omnidirectional.json`：
  合法 v2 observation，必须被两端解码为相同 typed data。
- `invalid-*.json`：非法样例，必须被两端以稳定 rejection code 拒绝
  （`SCHEMA_MISMATCH`、`UNKNOWN_PAYLOAD_VERSION`、`MISSING_REQUIRED`、
  `UNKNOWN_FIELD` 等）。

Java runner：`byog.Bridge.AgentContractFixtureTest`
Python runner：`agent/python/tests/test_contract_fixtures.py`

## 兼容性变更流程

进行兼容性变更时必须：

1. 同时更新 Java 编解码器和所有语言的编解码器。
2. 在此处更新字段、方向、版本和合法/非法输入规则。
3. 同步更新跨语言共享的 fixtures 和 runner 测试。
4. 除非迁移方案得到明确批准，否则保留旧协议版本的行为。

此目录不得包含模型提示词、规划逻辑、套接字生命周期或特定语言的数据传输对象类。
