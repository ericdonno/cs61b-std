# Phase 2.5 Spec：持久世界状态与可读敌人感知

## 0. 元数据

- **Phase**：2.5
- **状态**：Approved
- **创建日期**：2026-08-02
- **基线分支**：`ai-enemis`
- **基线 HEAD**：`bbd5dae99a9827a87017b751b196c42c3ece436d`
- **工作树说明**：Phase 2 实现与 Completion 尚未形成最终 commit；本 Spec 以该 HEAD 加当前未提交工作树为事实基线
- **前一阶段 Completion**：[PHASE_2_COMPLETION.md](PHASE_2_COMPLETION.md)
- **配套指南**：[PHASE_2DOT5_BUILD_GUIDE.md](PHASE_2DOT5_BUILD_GUIDE.md)
- **后续阶段**：[PHASE_3_SPEC.md](PHASE_3_SPEC.md)

本文是 Phase 3 启动前的阻塞 gate。它只批准本文列出的玩法、持久化、感知和回归工作；
不能把真实模型、LangGraph、Tool Calling 或模型 checkpoint 提前写入本阶段 Completion。

## 1. 必读输入与审计范围

### 1.1 已读取的基准文档

- [PROJECT_INTENT_zh-CN.md](PROJECT_INTENT_zh-CN.md)：玩家带着生命和资源损耗下楼；敌人记忆随楼层结束；敌人感知必须可理解、可反制。
- [DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md)：文档优先级、INV-01–INV-09、阶段 gate 和交付链。
- [AI_TICK_ARCHITECTURE.md](AI_TICK_ARCHITECTURE.md)：生产 tick、单 Action cadence、commit barrier、Session 生命周期和换层边界。
- [PHASE_2_COMPLETION.md](PHASE_2_COMPLETION.md)：Phase 2 已交付的异步桥、私有 Observation、reflex、fallback、trace 与已知限制。
- [health-pack-requirements.md](documents/health-pack-requirements.md)：跨层 HP、苹果生成、拾取、存档和 Agent 隐藏规则。
- [PHASE_3_SPEC.md](PHASE_3_SPEC.md) 与 [PHASE_3_BUILD_GUIDE.md](PHASE_3_BUILD_GUIDE.md)：需要改写的前置契约、checkpoint 身份和 intent 迁移假设。

### 1.2 已审计的当前实现

| 事实 | 当前位置 | 对本阶段的影响 |
|------|----------|----------------|
| `nextFloor()` 生成新 `Player` | `byog/Core/Game.java:655-680` | 当前 HP 和全部对象内状态被重置；必须建立显式整局状态边界 |
| 新玩家从 `GameConfig.playerHp` 初始化 | `byog/Entity/Player.java:35-44` | 不能靠复用旧 Player 隐式保留所有状态 |
| 玩家已有 HP、伤害、蓄力和表现计时 | `byog/Entity/Player.java:10-17` | 必须区分整局、当前楼层和纯表现状态 |
| 存档固定写入 `./save/game.ser` | `byog/IO/SaveLoadManager.java:20-26` | 需要改成动态命名世界目录与可枚举摘要 |
| `GameSaveData` 主要依赖开放 `extraData` | `byog/IO/GameSaveData.java:16-31` | 新存档应改为可审计的类型化快照，不继续堆字符串 key |
| 当前菜单只有 N/L/Q | `byog/Core/Game.java:189-229, 311-318` | 新建和读取都需要命名世界选择流程 |
| 玩家移动后仅检查楼梯 | `byog/Core/Game.java:508-517` | 苹果拾取应进入同一已提交移动结果路径 |
| 楼梯放置不返回坐标或房间 | `byog/Core/Game.java:636-653` | 苹果无法可靠排除整间楼梯房，必须返回类型化放置结果 |
| 敌人没有正式朝向或最大 HP | `byog/Entity/Enemy.java:37-77` | 朝向、上限和巡视状态需要成为实体事实 |
| 移动与攻击 Action 不更新朝向 | `byog/Action/MoveAction.java:23-35`、`AttackAction.java:82-105` | 成功、受阻和落空路径都要统一更新 |
| FOV 是 sightRange 内全向曼哈顿菱形，再做 Bresenham LOS | `byog/Perception/PerceptionSystem.java:80-150` | 半边视野应在原菱形上增加朝向过滤，保留遮挡算法 |
| Observation self 只有位置和当前 HP | `byog/Perception/ObservationEnvelope.java:14-45` | v2 需要加入朝向、最大 HP 和视野模式 |
| wire 使用 `phase2.session.v1` 与 `private-observation.v1` | `byog/Bridge/AgentProtocol.java:13-18` | 本阶段执行已批准的一次性领域命名硬切换 |
| Python codec 对 Observation 使用 exact fields | `agent/python/dungeonmind_agent/protocol.py:190-230` | Java、Python 和 fixture 必须原子升级，不能单边发布 |
| 本地 PATROL 每次重决策随机挑目标 | `byog/AI/RuleBasedBrain.java:70-144` | 目标、停留、扫描和受阻进度必须显式持有 |
| planner 路径失败时随机走一步 | `byog/AI/ClassicalPlanner.java:68-75` | 会产生不可解释抖动；巡视路径必须改成确定性局部恢复 |
| 已有全局敌人 FOV debug overlay | `byog/Core/Game.java:548-564` | 可扩为单敌人悬停视野和全局基线模式，不另建渲染器 |
| `TETile` 支持 16×16 图片与字符 fallback | `byog/TileEngine/TETile.java:14-18, 75-99` | 苹果可以先试 emoji 风格图片，不必把全部 tile 改成字符串 |

### 1.3 已审计的测试与入口

- Phase 2 Completion 记录：Java 全量编译通过；deterministic Suite 100 tests；SocketTransport 9 tests；真实 Python integration 7 tests；Python 23 tests。
- 现有私有感知覆盖 LOS、墙后不可见和 Observation 不可变性，但没有正式朝向、半边视野或地图边缘矩阵。
- 现有协议测试在 Java 内构造 payload；`agent/contract/fixtures/` 尚未成为共享权威 fixture 目录。
- 现有存档测试依赖默认单文件路径；本阶段测试必须改用注入的临时目录、假时钟和确定性 ID。
- 默认 deterministic gate 不得打开 GUI、真实网络、默认存档或使用 `Thread.sleep()`。

### 1.4 文档冲突与本 Spec 的裁决

1. healthpack 文档此前要求兼容旧苹果存档；Builder 已明确决定开发阶段不迁移旧存档，原需求文档现已同步。
2. Phase 3 草案此前依赖 `phase2.session.v1`、`private-observation.v1`、读档冷启动 checkpoint 和 intent v1/v2 双解码；Phase 3 Spec 与 Build Guide 现已按本阶段裁决同步。
3. Roadmap 在审计时从 Phase 2 直接进入 Phase 3；现已加入 2.5 gate 和最小交付物。
4. 源码与测试中存在开发阶段编号命名。本阶段凡触及相关类、测试、常量、日志或 schema，必须迁移为稳定领域命名。

## 2. 阶段目标与成功定义

Phase 2.5 结束后，一个命名世界能够可靠创建、保存、列出、覆盖和恢复；玩家带着当前 HP 进入下一层，
每层拥有确定性生成且可保存的苹果血包；敌人拥有可保存的最大 HP、正式朝向、半边菱形视野和可持续的
确定性巡视行为。玩家可以从敌人朝向标记、悬停信息与调试视野理解规则；Agent 仍只接收自己的私有
Observation，苹果不会泄露为战术资源。Phase 3 以新的身份、Observation、trace、fixture 和回归 gate
为唯一前置，不再同时兼容旧协议。

成功必须同时满足：

- 相同输入的跨层、苹果、朝向、视野和巡视结果可重复；
- 保存/读档恢复整局状态与当前楼层快照，但不恢复 Socket、Lease 或 ActionQueue；
- 半边视野不会从隐藏地图或背后玩家泄露信息；
- 新 UI 能解释敌人 HP、朝向和苹果效果；
- 全向基线仍可通过保存的配置模式运行和对比；
- Java/Python contract、trace 与固定场景使用同一领域版本；
- Phase 2 的非阻塞、单 Action cadence、commit barrier 和 fallback 回归不退化。

## 3. 状态所有权与生命周期

### 3.1 整局玩家状态

新增领域对象 `PlayerRunState`，Phase 2.5 只正式承载 `currentHp`。它是跨楼层和命名世界存档的
权威玩家整局状态，不持有 `Player`、world 或 UI 引用。

未来默认归入整局状态、但本阶段不实现的项目：

- `maxHp`、攻击力或其他基础属性的永久成长；
- 背包资源、货币和消耗品库存；
- 装备及其耐久或词条；
- 已学习技能、天赋、永久升级和整局任务进度。

### 3.2 当前楼层状态

以下状态会写入当前世界存档，但进入下一层时重建或清理：

- 玩家坐标和当前蓄力；
- 楼梯位置、剩余苹果位置和视野模式；
- 每个敌人的坐标、当前/最大 HP、朝向和巡视状态；
- 当前楼层敌人的身份和存活状态。

玩家当前蓄力在读档时恢复，在换层时清零。攻击闪光、受击动画计时、鼠标悬停和 debug overlay
属于纯表现状态，既不保存也不跨层。

### 3.3 运行时状态

以下状态永远不进入 Java 存档：

- `AgentSession`、Socket、worker thread 和队列；
- `IntentLease`、in-flight request、ActionQueue 和迟到 response；
- cached Observation、FOV mask、trace sink 和 runtime clock；
- Python 进程、provider 配置和 API key。

读档生成新 `runId` 和新 Session，保持迟到响应隔离。稳定 `worldId` 只用于识别同一个命名世界；
它不赋予旧 request 继续生效的资格。

## 4. 需求追踪

| Requirement | 本阶段满足方式 | 验收证据 |
|-------------|----------------|----------|
| INV-01 独立身份 | `worldId/runId/floorId/agentId` 分工明确；巡视和朝向逐敌人保存 | identity、双敌人状态隔离测试 |
| INV-02 有限知识 | 半边 FOV + LOS；巡视目标只从私有 Observation 选择 | 背后、墙角、隐藏地图 no-cheat 测试 |
| INV-03 世界内通信 | 不新增共享视野、共享巡视或广播 | 双敌人不同朝向 Observation 测试 |
| INV-04 Java 权威 | 保存、拾取、朝向、碰撞、视野和动作执行均由 Java 决定 | Action/commit/pickup 测试 |
| INV-05 分层控制 | 本地反射可中断巡视；原地转向也遵守正常 action cooldown | cadence 与 reflex interrupt 测试 |
| INV-06 严格契约 | `agent-session.v1`、`private-observation.v2`、共享 fixtures | Java/Python 正反 fixture 测试 |
| INV-07 异步时效 | 保留新 run、floor 和 generation 过期规则 | 读档/换层迟到 intent 回归 |
| INV-08 可追踪评估 | `agent-runtime.trace.v2` 记录朝向、视野模式和巡视转换 | canonical trace 测试 |
| INV-09 玩法价值 | 朝向标记、底部悬停条、单敌人 FOV 与全向 A/B | 人工试玩清单与截图 |

## 5. 范围与非目标

### 5.1 In Scope

- `PlayerRunState` 与当前 HP 跨楼层保留。
- 玩家蓄力的存档恢复与换层清零。
- healthpack 文档中的苹果生成、拾取、治疗、保存、随机流隔离和可见性隐藏。
- 不限数量的命名世界存档、摘要列表、覆盖确认、首次创建保存和 `:q` 覆盖当前世界。
- 世界名、稳定 `worldId`、保存时间、楼层、HP、难度摘要。
- `Enemy.maxHp`、四向 `Facing`、主动转向和动作朝向更新。
- 原菱形 FOV 的朝向半边过滤、全向 baseline 模式和 LOS 保留。
- 可保存、可中断、使用私有 Observation 的确定性巡视状态机。
- 底部悬停 UI、敌人朝向标记、单敌人/全局 FOV 调试和苹果图片试验。
- Observation、envelope、Python codec、fixtures、save snapshot、trace 与相关测试迁移。
- Roadmap、healthpack 和 Phase 3 两份草案同步。

### 5.2 Out of Scope

- 背包、装备、货币、永久成长或可成长 `Player.maxHp`。
- 敌人地图记忆、已探索区域、跨楼层记忆或玩家画像。
- 敌人拾取、守卫、争夺、治疗或识别苹果。
- 周期自动存档、云存档、存档删除、重命名、导入、导出或版本迁移。
- 旧 `save/game.ser`、旧 Serializable 结构或旧 wire/trace schema 的兼容读取。
- 新复杂战术 skill、真实模型、LangGraph、Tool Calling 或模型预算。
- 改造 `Game.playWithInputString(String)` 作为新运行时或存档入口。
- 将自由转向改为零成本；本阶段只保留未来易改的职责边界。

## 6. 已锁定决定

### D25-01：跨楼层状态必须显式列入 `PlayerRunState`

换层仍创建按当前配置初始化的新 `Player`，只恢复 `PlayerRunState.currentHp`。不得直接复用旧 Player，
也不得因为“以后可能要用”而让蓄力、动画或其他字段隐式跨层。

### D25-02：旧存档和旧协议不兼容

新存档不迁移 `save/game.ser`，缺少苹果、朝向或新类型字段的对象不走 fallback。加载失败保留原文件，
记录明确路径并回到世界列表；程序不自动删除。Java/Python wire 和 trace 同样采用一次性硬切换。

### D25-03：命名世界数量不设上限

新建流程为 `New → World Name → Difficulty → Seed → Generate`。世界生成成功后立即保存一次；之后
只有 `:q` 覆盖当前世界，不做周期自动存档。列表按世界名展示楼层、玩家 HP、难度和保存时间，并分页。

世界名规则：去除首尾空格后为 1–32 个 Unicode code point；允许中文和普通空格；拒绝换行和控制字符；
判重不区分大小写。输入已有名称时必须确认覆盖。存储路径使用新生成的 opaque `worldId`，不能直接拼接名称。

### D25-04：新世界和运行身份分离

- `worldId`：创建新世界时生成，覆盖同名旧世界时也必须生成新值；随存档稳定。
- `runId`：每次新游戏运行或读档生成；用于拒绝旧连接和迟到响应。
- `floorId`：楼层边界；新楼层的敌人和记忆与上一层隔离。
- `agentId`：当前楼层内敌人的稳定身份；当前楼层存档恢复。

### D25-05：苹果是可行走地图 tile

苹果遵循 healthpack 文档的数量、位置、治疗和随机隔离规则。图片优先使用本地 16×16 emoji 风格资源，
字符 fallback 为红色 `●`。`VisibleTile` 始终把苹果映射成 `FLOOR`；Agent 不知道苹果存在。

### D25-06：敌人有正式最大 HP 与四向朝向

`Enemy.maxHp` 为正且不会因受伤改变，`0 <= hp <= maxHp`。生成时配置 `enemy.hp` 同时初始化二者。
最大 HP、当前 HP 和朝向进入存档；最大 HP 也进入 Observation self，供未来 Agent 计算生命比例。

正式朝向只有 `NORTH/EAST/SOUTH/WEST`。生成时从 `seed + floor + agentId + facing namespace`
的独立确定性随机流选择；读档恢复保存值。

### D25-07：动作尝试本身可以改变朝向

- 敌人移动前先朝移动方向转向；即使碰撞导致 `BLOCKED`，朝向仍更新。
- 敌人攻击前先朝攻击方向转向；即使没有命中，朝向仍更新。
- `TurnAction` 原地改变朝向并消耗一次正常 action opportunity。
- `WaitAction` 不改变朝向。
- 攻击在同一次行动中完成转向和攻击，不额外插入转向 tick。

朝向更新后必须在 commit/collect 后生成新 Observation 和 trace，不允许 cached FOV 继续使用旧朝向。

### D25-08：Directional FOV 是原菱形的一半

先使用现有 `manhattanDistance <= sightRange` 得到菱形，再保留面朝方向的半边，最后执行现有墙壁 LOS。
穿过敌人的切分中线属于可见半边；敌人自身格始终可见。

```text
NORTH: targetY >= selfY
SOUTH: targetY <= selfY
EAST:  targetX >= selfX
WEST:  targetX <= selfX
```

`DIRECTIONAL` 是正式默认；`OMNIDIRECTIONAL` 保留原全向菱形，用于回归、固定场景 A/B 和调试。
视野模式随世界存档保存，游戏中不提供临时切换菜单。

### D25-09：巡视是可保存的确定性状态机

敌人看不到玩家且没有更高优先级 lease 时，进入本地巡视：

1. 从当前私有 Observation 的可见、可行走格中收集曼哈顿距离 3–8 的候选；实际最大值受 sightRange 限制。
2. 候选按坐标稳定排序，再用逐敌人独立随机流和 `selectionOrdinal` 选择目标。
3. 持续前往同一目标，不在每个 tick 重抽。
4. 到达后等待一个 action opportunity，再顺时针转 90° 三次；每次转向各占一次行动。
5. 扫描结束后选择新目标。
6. 任一 committed Observation 看见玩家时，现有 reflex 在下一个可行动 tick 中断巡视。

巡视只允许读取本敌人 Observation。执行层可以读取 world 检查碰撞和动作合法性，但不得用隐藏地图替 Brain
选择目标。完整地图意识留给后续独立设计。

动态实体阻挡时，第一次阻挡保留目标并使用最新可见地形/实体局部重规划；连续两次仍受阻则放弃目标并进入
扫描。不得恢复“路径失败随机走一步”的 fallback。

### D25-10：巡视进度属于当前楼层存档

至少保存目标、`TRAVELING/DWELLING/SCANNING` 状态、剩余停留/扫描次数、连续阻挡次数和
`selectionOrdinal`。读档原样恢复；新楼层重置。Random 对象内部实现状态不作为存档契约。

### D25-11：底部 UI 是按需上下文条

窗口增加两格底部区域，世界仍为 80×30，不覆盖或压缩地图。世界绘制统一上移，鼠标坐标统一反向映射。

- 悬停敌人：名称、血条、`HP current/max`、`Facing direction`，并高亮该敌人的实际 FOV。
- 悬停苹果：名称与当前难度治疗量；满血时说明不会消耗。
- 悬停楼梯/地形：tile description。
- 未悬停有效格：底部条为空。
- 敌人仍显示红色 `E`，同格边缘绘制金色朝向标记；不占用相邻 tile。
- 全局 debug flag 可同时高亮所有敌人 FOV。

### D25-12：稳定契约硬切换

- Envelope：`agent-session.v1`，新增必填 `worldId` identity 字段。
- Observation：`private-observation.v2`。
- Observation self：`position`、`hp`、`maxHp`、`facing`。
- Observation data：新增 `visionMode`；visible tiles/entities 仍只含实际 FOV。
- Trace：`legacy-decision.trace.v1`、`private-perception.trace.v1`、`agent-runtime.trace.v2`。
- Phase 2.5 仍使用现有 `strategic-intent.v1` 语义；Phase 3 开始时一次性升级所有 brain 到
  `strategic-intent.v2`，不保留 v1/v2 双解码。

所有 Java/Python exact-field codec、fixtures、README 和测试必须在同一实现增量中切换。未知版本、缺字段、
非法 facing/vision mode 或 identity 不一致都必须拒绝，且不得改变 Lease、queue、cooldown 或实体。

### D25-13：Phase 3 checkpoint 使用稳定世界身份

Phase 3 的 thread key 改为 `worldId/floorId/agentId`；`runId`、session epoch、generation 和 decision ID
仍属于运行/请求身份。同一命名世界同一楼层读档可以恢复该敌人的 checkpoint；新楼层、新世界或
checkpoint 缺失时冷启动。Python checkpoint 仍不是 Java save 的组成部分，缺失不得破坏世界加载。

## 7. 目标架构与数据流

### 7.1 新世界创建

```text
输入并校验 worldName
  → 若重名，确认覆盖
  → 生成新的 worldId
  → 选择 difficulty 与 seed
  → 生成房间/走廊
  → 创建 Player + PlayerRunState
  → 确定楼梯坐标与楼梯房
  → 生成 Enemy（maxHp + facing）
  → 生成 apples
  → 创建新 runId / Session
  → 原子写入第一次世界存档
  → PLAYING
```

覆盖确认只授权替换选中的命名世界。实际新存档成功原子替换前，不得先删除旧文件；若首次保存失败，
返回可诊断错误并保留旧存档。

由于覆盖会生成新 `worldId`，repository 应先把新世界完整写入 `<newWorldId>.ser`，成功后才删除精确的
`<oldWorldId>.ser`。新 DTO 在该事务完成前以可空 `replacesWorldId` 记录被替换的旧 ID；若旧文件删除失败，
新存档仍是活动世界，列表按该字段隐藏被替换项并记录警告，不得因失败回退到旧世界或显示两个同名入口。

### 7.2 换层

```text
读取旧 Player → PlayerRunState.currentHp
  → 关闭旧楼层 Session
  → floorId + 1
  → 生成新 world
  → 从 GameConfig 创建新 Player
  → 恢复 currentHp，charge = 0
  → 放楼梯、敌人、apples
  → 清理旧 floor state
  → 创建新楼层 Session / Observation
```

换层本身不自动保存；后续 `:q` 保存当前新楼层状态。

### 7.3 保存和读取

```text
SAVE
authoritative Game state
  → capture PlayerRunState + current floor snapshot
  → build GameSaveData + WorldSaveSummary
  → sibling temp file
  → flush/close
  → atomic replace（不支持时 replace fallback）

LOAD
scan save/worlds/*.ser
  → deserialize each readable summary
  → stable sort + paginate
  → selected GameSaveData validation
  → regenerate deterministic base world
  → restore player/apples/enemies/facing/patrol
  → new runId + new Session
```

列表读取单个坏文件不能使整个菜单失败。坏文件显示为不可读取条目或记录路径后跳过；不得自动删除。

### 7.4 敌人 action tick

```text
poll remote messages
  → arbiter: reflex > active lease > local fallback
  → local fallback reads latest private Observation
  → patrol state returns Move / Wait / Turn intent primitive
  → execute at most one Action for the cooldown
  → action updates facing before collision/attack result
  → commit entity changes
  → recompute directional FOV from new facing
  → trace + ActionOutcome + next Observation
```

### 7.5 苹果拾取

玩家成功移动并提交新坐标后检查底层 tile。若为 apple 且 `hp < configuredMaxHp`，按配置治疗、clamp、
更新 `PlayerRunState.currentHp` 并把 tile 恢复为 floor。满血时不修改 tile。敌人移动永远不触发该流程。

## 8. 接口与数据契约

以下代码仅锁定职责和字段语义；实现者可以在不改变契约的前提下调整构造器细节。

### 8.1 核心领域类型

```java
public final class PlayerRunState implements Serializable {
    private int currentHp;

    public static PlayerRunState capture(Player player) { ... }
    public void restoreInto(Player player, int configuredMaxHp) { ... }
}

public enum Facing {
    NORTH(0, 1), EAST(1, 0), SOUTH(0, -1), WEST(-1, 0);

    public static Facing fromDirection(Direction direction) { ... }
}

public enum VisionMode {
    DIRECTIONAL,
    OMNIDIRECTIONAL
}

public final class PatrolState implements Serializable {
    public enum Mode { TRAVELING, DWELLING, SCANNING }
    // target、remaining counts、blocked attempts、selection ordinal
}
```

源码中的类型、方法、配置和测试名不得包含开发阶段编号。

### 8.2 存档 DTO

`GameSaveData` 至少包含：

```text
worldId: non-blank opaque ID
worldName: validated display name
replacesWorldId: nullable opaque ID used only by an unfinished overwrite cleanup
seed: original world seed
floorLevel: positive integer
difficulty: known Difficulty key
savedAtEpochMillis: display metadata only
visionMode: DIRECTIONAL | OMNIDIRECTIONAL
playerRunState.currentHp
playerFloorState.position/currentCharge
stairsPosition
remainingHealthPacks[]
enemyStates[]:
  agentId, position, alive, hp, maxHp,
  facing, combat configuration, patrolState
```

旧文件已确认删除后，下一次成功保存可清空 `replacesWorldId`。`savedAtEpochMillis` 不进入 canonical gameplay 决定；测试通过注入 `Clock` 固定。坐标列表在序列化前按
`x, y` 稳定排序。加载时对必填字段、范围、重复 agentId/坐标和越界位置做完整校验；失败即拒绝整个世界。

### 8.3 世界存档服务

```java
public interface WorldSaveRepository {
    List<WorldSaveEntry> list();
    SaveResult save(GameSaveData data);
    LoadResult load(String worldId);
    boolean nameExists(String normalizedName);
}
```

生产实现只访问注入的 `save/worlds` 根目录。测试使用临时目录。所有 resolved path 必须保持在该根目录内；
opaque ID 也要校验，不能接受 `..`、绝对路径或分隔符。保存失败不得报告成功。

### 8.4 楼梯与苹果放置

```java
public record StairPlacement(Position position, SquareRoom room) { }

public final class HealthPackGenerator {
    public static List<Position> place(
            TETile[][] world,
            List<SquareRoom> rooms,
            SquareRoom spawnRoom,
            SquareRoom stairRoom,
            EntityManager entities,
            HealthPackConfig config,
            long deterministicSeed) { ... }
}
```

生成器只负责初始放置，不负责拾取、UI 或存档 I/O。候选不足时放置全部合法位置并通过 `Logger` 记录。

### 8.5 感知函数

```java
public static ObservationEnvelope computeObservation(
        String worldId,
        String runId,
        int floorId,
        long observationSeq,
        TETile[][] world,
        EntityManager entityMgr,
        Enemy self,
        Player player,
        int sightRange,
        VisionMode visionMode,
        long currentTurn) { ... }
```

计算顺序固定为：范围 → 模式/朝向半边 → LOS → visible tile/entity snapshot。苹果只在 snapshot 映射阶段
转为 floor。不得先生成全向 visible entity，再只裁 tile mask。

### 8.6 `private-observation.v2`

```json
{
  "observationVersion": "private-observation.v2",
  "decisionId": "enemy-1-decision-4",
  "observationSeq": 7,
  "requestGeneration": 2,
  "observedAtTurn": 42,
  "visionMode": "DIRECTIONAL",
  "self": {
    "position": {"x": 12, "y": 9},
    "hp": 14,
    "maxHp": 20,
    "facing": "EAST"
  },
  "visibleTiles": [],
  "visibleEntities": [],
  "heardEvents": [],
  "pendingEvents": [],
  "capabilities": {}
}
```

Envelope 顶层必填 `worldId`，并继续包含 message/run/floor/agent/session/tick 身份。Apple 不成为合法
tile type。Java/Python decoder 都 exact-field；不允许忽略 unknown field 或把非法枚举映射为默认值。

### 8.7 Trace v2

`agent-runtime.trace.v2` 的 Observation/Action/巡视相关事件至少可记录：

```text
worldId, runId, floorId, agentId, logicalTick, observationSeq,
visionMode, facing, selfHp, selfMaxHp,
visiblePlayer, visibleEntityCount, fovTileCount,
patrolMode, patrolTargetX, patrolTargetY, selectionOrdinal,
actionType, rawActionResult, beforeFacing, afterFacing,
decisionId, decisionSource, overrideReason
```

建议事件：`FACING_CHANGED`、`PATROL_TARGET_SELECTED`、`PATROL_STATE_CHANGED`。canonical 字段不含保存时间、
鼠标位置、墙钟耗时或 Random 对象内部状态。旧 trace 名称和测试 fixture 同步迁移，不做旧 reader。

## 9. 逐文件变更计划

| 文件 | 新建/修改 | 责任 | 关键变更 | 不应包含 |
|------|-----------|------|----------|----------|
| `DEVELOPMENT_ROADMAP.md` | 修改 | 阶段 gate | 插入 Phase 2.5 与交付链 | 把实现写成已完成 |
| `health-pack-requirements.md` | 修改 | 需求裁决 | 删除旧存档兼容；锁定 maxHP/图片决定 | Agent 识别苹果 |
| `byog/Common/Facing.java` | 新建 | 正式朝向 | 四向、delta、Direction 转换 | 开发阶段编号 |
| `byog/Common/VisionMode.java` | 新建 | 视野模式 | directional/omnidirectional | GUI 状态 |
| `byog/Entity/PlayerRunState.java` | 新建 | 整局玩家状态 | currentHp capture/restore | Player/world 引用 |
| `byog/Entity/PlayerFloorState.java` | 新建 | 当前楼层玩家快照 | position/currentCharge | 永久成长 |
| `byog/Entity/Player.java` | 修改 | 玩家状态入口 | clamp HP、恢复 charge、max accessor from config boundary | 存档 I/O |
| `byog/Entity/Enemy.java` | 修改 | 敌人事实和 tick 接线 | maxHp/facing/patrol、Observation | 选择隐藏地图目标 |
| `byog/Entity/EntityState.java` | 替换或拆分 | save DTO | typed player/enemy fields | `extraData` 字符串协议 |
| `byog/AI/PatrolState.java` | 新建 | 可保存巡视状态 | mode/target/counts/ordinal | Random 实例 |
| `byog/AI/PatrolController.java` | 新建 | 本地巡视决策 | 稳定候选、停留、扫描、阻挡恢复 | 完整 world/Player |
| `byog/AI/RuleBasedBrain.java` | 修改 | fallback 仲裁 | 委托 PatrolController | 每 tick 重抽目标 |
| `byog/AI/ClassicalPlanner.java` | 修改 | Action 翻译 | 移除随机不可达 fallback；接入 Turn/Wait | 隐藏知识决策 |
| `byog/Action/TurnAction.java` | 新建 | 原地转向 | 一次 action opportunity | free scan |
| `byog/Action/WaitAction.java` | 新建 | 显式等待 | success、不改朝向 | null Direction |
| `byog/Action/MoveAction.java` | 修改 | 移动与朝向 | Enemy 先转向，拒绝 null | 随机 fallback |
| `byog/Action/AttackAction.java` | 修改 | 攻击与朝向 | Enemy 先转向再结算 | 额外攻击 tick |
| `byog/WorldGen/StairPlacement.java` | 新建 | 楼梯结果 | position/room | UI |
| `byog/WorldGen/HealthPackGenerator.java` | 新建 | 苹果初始放置 | 稳定候选、独立随机流 | 拾取/存档 |
| `byog/TileEngine/Tileset.java` | 修改 | 苹果 tile | 图片 + 红点 fallback | emoji surrogate char |
| `assets/tiles/apple.png` | 新建 | 苹果视觉 | 16×16 透明图片 | 外部绝对路径 |
| `byog/IO/GameConfig.java` | 修改 | 配置快照 | healthpack 参数、vision mode、巡视参数 | 每 tick 读文件 |
| `config/game.properties` | 修改 | 默认配置 | 三难度苹果值和 domain settings | Phase/Step key |
| `byog/IO/GameSaveData.java` | 修改 | 类型化世界快照 | world identity、summary、run/floor state | 兼容旧结构 |
| `byog/IO/WorldSaveRepository.java` | 新建 | 存档端口 | list/save/load/name lookup | GUI |
| `byog/IO/FileWorldSaveRepository.java` | 新建 | 文件实现 | root confinement、temp + replace | 自动删除坏档 |
| `byog/IO/WorldSaveEntry.java` | 新建 | 列表结果 | readable summary 或 failure path | live Game 对象 |
| `byog/IO/SaveLoadManager.java` | 删除或薄适配 | 历史入口迁移 | 委托 repository，不再默认单文件 | 新 runtime work |
| `byog/Core/Game.java` | 修改 | 流程编排 | 命名菜单、生成顺序、换层、拾取、UI offset | protocol parser |
| `byog/Perception/ObservationEnvelope.java` | 修改 | immutable v2 observation | facing/maxHp/mode/worldId | live world 泄漏 |
| `byog/Perception/PerceptionSystem.java` | 修改 | 半边 FOV | mode filter + existing LOS | 地图记忆 |
| `byog/Perception/VisibleTile.java` | 修改 | 防御映射 | APPLE → FLOOR | APPLE wire enum |
| `byog/Bridge/AgentProtocol.java` | 修改 | v2 DTO/identity | agent-session.v1、worldId、self fields | Phase 命名 |
| `byog/Bridge/AgentProtocolCodec.java` | 修改 | strict codec | v2 encode/decode | dual old reader |
| `byog/Bridge/AgentSession.java` | 修改 | observation mapping | worldId/facing/maxHp/mode | save I/O |
| `agent/python/dungeonmind_agent/protocol.py` | 修改 | Python strict codec | 新 envelope/Observation | 宽松 unknown fields |
| `agent/contract/fixtures/` | 新建 | 跨语言 fixture | valid/invalid/canonical v2 | gameplay trace golden |
| `agent/contract/README.md` | 修改 | wire 说明 | 新身份、版本和拒绝规则 | prompt |
| `byog/Trace/AgentTrace.java` | 修改/拆分 | domain trace | 新版本、朝向/巡视字段 | raw reasoning |
| `byog/Test/` 相关测试 | 修改/重命名 | regression gate | domain names、leaf-only suite | Phase 编号类名 |
| `agent/python/tests/` | 修改 | Python contract | fixtures、identity、v2 exact fields | 真实 provider |
| `PHASE_3_SPEC.md` | 修改 | 下一阶段契约 | 新前置、identity、checkpoint、intent hard cut | 重做本阶段功能 |
| `PHASE_3_BUILD_GUIDE.md` | 修改 | 下一阶段施工 | 从 Phase 2.5 artifacts 起步 | v1/v2 双迁移 |

## 10. 实施顺序

Build Guide 的“阶段 2.5.x”与本节“Step 2.5.x”严格一一对应。任何 Step 未通过自己的
deterministic checkpoint 时不得开始下一 Step；协议 Step 必须 Java/Python 同时提交。

### Step 2.5.1：建立玩家整局状态边界

- **输入**：当前 `Player`、`Game.nextFloor()` 与配置生命上限。
- **改动**：新增 `PlayerRunState`，只承载 `currentHp`；修正换层 HP、读档 charge 与换层 charge 边界。
- **保持不变**：换层仍创建新 `Player`；动画、Session、queue 和 hover 不进入整局状态。
- **验证**：`P25-PLAYER-01–03`。
- **artifact**：可供苹果和命名存档复用的显式整局玩家状态。

### Step 2.5.2：实现苹果生成与拾取

- **输入**：Step 2.5.1 的 `PlayerRunState`、房间列表和楼梯放置流程。
- **改动**：返回楼梯放置事实；加入苹果 tile、配置、独立随机流、稳定候选、拾取与满血保留。
- **保持不变**：苹果不进入 `EntityManager`，不改变地图、楼梯、敌人或战斗随机序列。
- **验证**：`P25-APPLE-01–08`。
- **artifact**：可序列化的当前楼层苹果状态。

### Step 2.5.3：建立不限数量的命名世界存档

- **输入**：玩家整局状态、当前楼层苹果状态和现有保存入口。
- **改动**：建立类型化 DTO/repository、世界名与 `worldId` 分离、摘要列表、首次保存、覆盖和坏档隔离。
- **保持不变**：不迁移 `save/game.ser`，加载失败不删除文件，不做周期 autosave。
- **验证**：`P25-SAVE-01–09`。
- **artifact**：Phase 3 可依赖的稳定世界身份与当前楼层快照。

### Step 2.5.4：加入敌人最大 HP、朝向和原子转向动作

- **输入**：Step 2.5.3 的类型化 Enemy snapshot 与现有 Move/Attack cadence。
- **改动**：增加 `Enemy.maxHp`、`Facing`、`TurnAction`、`WaitAction` 和成功/失败动作朝向语义。
- **保持不变**：每个 action opportunity 最多执行一个 Action；攻击转向与结算仍在同一 Action。
- **验证**：`P25-FACING-01–04` 与存档恢复用例。
- **artifact**：可保存、可显示、可供感知读取的敌人身体状态。

### Step 2.5.5：切换半菱形 FOV 与 Observation v2

- **输入**：正式 Facing、现有曼哈顿菱形和 LOS。
- **改动**：加入 directional 半边过滤、omnidirectional baseline、`private-observation.v2`、
  `agent-session.v1`、`worldId` 与共享 fixtures。
- **保持不变**：墙壁 LOS 和 Session 时效语义不变；苹果继续映射为 `FLOOR`。
- **验证**：`P25-FOV-01–04`、`P25-PROTOCOL-01–03` 与 no-cheat 用例。
- **artifact**：Java/Python 一致的正式私有感知契约。

### Step 2.5.6：实现可保存的确定性巡视

- **输入**：Step 2.5.5 的 committed 私有 Observation 与 Turn/Wait Action。
- **改动**：加入持续目标、到达停留、顺时针扫描、两次受阻恢复、状态保存和 reflex interrupt。
- **保持不变**：巡视选择不读取完整地图；执行层只用 world 做碰撞与合法性判断。
- **验证**：`P25-PATROL-01–06`。
- **artifact**：同输入可重复、读档可恢复的当前楼层巡视状态机。

### Step 2.5.7：加入底部上下文 UI 与视野解释

- **输入**：Enemy `hp/maxHp`、Facing、苹果状态和 committed FOV mask。
- **改动**：增加两行底栏、有效 hover 信息、血条、金色方向标、单敌人/全局 FOV 和苹果图片验证。
- **保持不变**：世界逻辑尺寸仍为 80×30；无有效 hover 时底栏为空；UI 不重算 FOV。
- **验证**：`P25-UI-01–04` 与人工视觉检查。
- **artifact**：玩家可理解且不制造第二套事实的感知 UI。

### Step 2.5.8：迁移 trace、测试命名并运行完整 gate

- **输入**：Steps 2.5.1–2.5.7 的完整行为。
- **改动**：硬切三类领域 trace、更新 fixtures/no-cheat/固定场景、迁移被触及的阶段编号源码测试名，
  建立 leaf-only deterministic gate。
- **保持不变**：默认 gate 不使用 GUI、真实网络、默认存档或 `Thread.sleep()`。
- **验证**：本 Spec 11–12 节的自动化矩阵与完整回归命令。
- **artifact**：可重复、可审计的 Phase 2.5 验收基线。

### Step 2.5.9：形成 Completion 并关闭 Phase 3 门禁

- **输入**：Step 2.5.8 的通过结果与人工试玩证据。
- **改动**：创建 `PHASE_2DOT5_COMPLETION.md`，记录实际 commit/工作树、命令、测试数、trace、截图、偏差和遗留问题。
- **保持不变**：未通过项不得写成完成；本 Step 不开始真实模型、LangGraph 或 Tool Calling 实现。
- **验证**：本 Spec Definition of Done 与 Phase 3 交接清单逐项复核。
- **artifact**：Phase 3 唯一有效的开工证明。

## 11. 测试与验收矩阵

### 11.1 测试架构

| 项目 | 本阶段决定 |
|------|------------|
| 单一 deterministic 入口 | `CoreGameplayRegressionSuite`，直接列 leaf test class，每个测试只运行一次 |
| integration 入口 | 真实 Python/TCP 单独运行，复用 Phase 2 有界进程清理 |
| shared fixture | 固定地图 parser、fake clock、ID source、临时 save root、协议 fixture 各只有一个权威实现 |
| production seam | 测试调用真实生成、Observation、AiTickLoop、repository；不反射私有方法 |
| canonical artifact | 仅 wire、save summary schema 和 trace 使用；巡视只断言状态与不变量 |
| GUI | 自动测试坐标映射/hover model；字符可读性、图片和视觉层次人工验收 |

测试源码使用领域名。文档中的 Test ID 可以带 `P25-`，但类名、方法名、注释、日志和 runtime 字符串不得
出现开发阶段编号。

### 11.2 玩家、苹果与存档

| Test ID | 场景 | 断言 |
|---------|------|------|
| P25-PLAYER-01 | 非满 HP 下楼 | 新 Player HP 与下楼前一致；对象不同；charge 为 0 |
| P25-PLAYER-02 | 保存/读档当前蓄力 | charge 恢复；动画计时不恢复 |
| P25-PLAYER-03 | 未来状态边界 | save/run DTO 不含 Session、queue、hover 或 animation |
| P25-APPLE-01 | 三难度数量 | 目标位于对应闭区间，配置非法安全 fallback |
| P25-APPLE-02 | 相同输入重复生成 | 数量与位置完全一致；地图/楼梯/敌人/战斗随机不变 |
| P25-APPLE-03 | 排除房间与占位 | 玩家房、楼梯房、实体、走廊和重复位置均为零 |
| P25-APPLE-04 | 候选不足 | 生成全部合法候选，无重试死循环，记录短缺 |
| P25-APPLE-05 | 缺血拾取 | 治疗 clamp，apple → floor，run state 更新 |
| P25-APPLE-06 | 满血经过 | 不治疗、不消耗，离开后仍显示 |
| P25-APPLE-07 | 敌人经过 | apple 不消失，敌人离开后恢复显示 |
| P25-APPLE-08 | Agent tile snapshot | apple 编码为 FLOOR，wire 中不存在 APPLE |
| P25-SAVE-01 | 创建多个命名世界 | 数量不限、摘要正确、排序分页稳定 |
| P25-SAVE-02 | 重名覆盖 | 大小写无关判重；未确认不写；确认后新 worldId |
| P25-SAVE-03 | 初次保存与 `:q` | 生成后立即落盘；`:q` 只覆盖活动世界 |
| P25-SAVE-04 | 原子失败 | temp/write/replace 任一步失败不破坏旧文件、不报告成功 |
| P25-SAVE-05 | 坏档与旧档 | 单个失败不阻断列表；不迁移、不删除、不能加载 |
| P25-SAVE-06 | 状态恢复 | HP、charge、apples、stairs、enemy maxHp/facing/patrol/mode 一致 |
| P25-SAVE-07 | 路径安全 | world name/ID 不能越出注入 save root |
| P25-SAVE-08 | 保存时间 | fake clock 决定摘要；墙钟值不影响 canonical gameplay |
| P25-SAVE-09 | 新 ID 覆盖清理失败 | 新档已落盘、旧档保留但被明确 supersede；列表只显示新世界并记录警告 |

### 11.3 朝向、FOV 与 UI

| Test ID | 场景 | 断言 |
|---------|------|------|
| P25-FACING-01 | 固定 seed 生成 | 初始朝向重复一致且不扰动其他随机序列 |
| P25-FACING-02 | 成功/受阻移动 | 两者都更新为移动方向，只有成功改变坐标 |
| P25-FACING-03 | 命中/落空攻击 | 两者都更新朝向；攻击仍只占一次行动 |
| P25-FACING-04 | Turn/Wait | Turn 消耗正常 cooldown；Wait 不改朝向 |
| P25-FOV-01 | 四个朝向半菱形 | 只保留对应半边；中线和 self 可见 |
| P25-FOV-02 | 背后玩家 | 不出现在 visible entities、ReflexObservation 或 wire |
| P25-FOV-03 | 地图边缘 | 四朝向均不越界、不抛异常 |
| P25-FOV-04 | 墙与墙角 | 保留现有 LOS；墙后与绕角位置不可见 |
| P25-FOV-05 | 全向 baseline | 与旧全向菱形 mask 相同，模式随存档恢复 |
| P25-FOV-06 | 双敌人不同朝向 | 相同位置附近也可得到不同私有 Observation |
| P25-UI-01 | bottom offset | 世界尺寸不变；draw/hover 坐标互为逆映射 |
| P25-UI-02 | enemy hover model | current/max HP、血条和 Facing 来源正确 |
| P25-UI-03 | apple/terrain/no hover | 文案正确；无有效目标时底栏为空 |
| P25-UI-04 | 人工视觉 | E、金色方向标、单/全 FOV、emoji 图片/fallback 可辨识 |

### 11.4 巡视、契约与回归

| Test ID | 场景 | 断言 |
|---------|------|------|
| P25-PATROL-01 | 玩家不可见 | 目标在私有可见候选内，多个 action 持续同一目标 |
| P25-PATROL-02 | 相同脚本重复 | target/state/action trace 稳定一致 |
| P25-PATROL-03 | 到达目标 | Wait 一次，顺时针 Turn 三次，再选目标 |
| P25-PATROL-04 | 动态实体阻挡 | 首次局部重算；连续两次后扫描；无随机抖动 |
| P25-PATROL-05 | 玩家在扫描中可见 | 下一个 action tick 由 reflex 中断巡视 |
| P25-PATROL-06 | 玩家仍隐藏 | Brain、state、trace 不含隐藏位置或完整地图候选 |
| P25-PATROL-07 | 保存/读档 | 目标、mode、remaining、blocked、ordinal 原样恢复 |
| P25-PROTOCOL-01 | valid v2 fixture | Java/Python 接受并得到相同 typed data/canonical JSON |
| P25-PROTOCOL-02 | old/unknown version | 两端拒绝，世界和 Lease 无副作用 |
| P25-PROTOCOL-03 | invalid facing/mode/worldId | 两端给稳定 rejection code |
| P25-TRACE-01 | observation/action/patrol | v2 含新字段和关联 ID，两次 canonical 相同 |
| P25-TRACE-02 | domain schema migration | 输出和源码不含旧阶段编号 schema 名称 |
| P25-REGRESSION-01 | Phase 2 leaf tests 迁移后 | Session、deadline、cancel、feedback、fallback 全部通过 |
| P25-REGRESSION-02 | 真实 Python modes | normal/delay/malformed/disconnect/no-read 仍有界通过 |
| P25-REGRESSION-03 | source naming scan | 触及的源码、测试、配置无 Phase/Step 编号或同义命名 |

## 12. Observability 与运行证据

Completion 至少保存：

- 同一 seed 的苹果位置、初始朝向和巡视 target 两次运行对比；
- directional 与 omnidirectional 固定场景的 visible tile/player 对比；
- 玩家 HP 跨层、苹果拾取、命名存档摘要与读档恢复证据；
- 朝向变化与 Action result、巡视状态、reflex override 的可关联 trace；
- Java/Python 对共享 fixture 的通过数和拒绝原因；
- 底部 UI、方向标记、单敌人 FOV、全局 FOV 和苹果图片的人工截图或检查记录；
- Phase 2 完整非阻塞回归的命令、测试数和结果。

所有运行期控制台输出必须通过 `Logger`。存档时间和文件路径属于 diagnostics，不进入 deterministic trace。

## 13. 失败处理与兼容策略

| 失败 | 行为 |
|------|------|
| 世界名非法或重复未确认 | 留在命名界面，不创建 worldId 或文件 |
| 新档写入或原子 move 失败 | 不进入已保存成功状态；旧文件保持；显示/记录失败 |
| 新档成功但旧档清理失败 | 新档保持活动；保留 replacement marker、隐藏旧入口并记录警告 |
| 单个存档损坏 | 其他世界仍可列出和加载；保留失败文件与路径 |
| 旧 `game.ser` 或旧 DTO | 不迁移、不自动删除、不加载 |
| 苹果配置非法 | 使用已记录的安全默认值；不影响其他随机流 |
| 无合法苹果候选 | 生成 0 个，游戏继续 |
| 苹果图片失败 | 自动使用红色 `●` 字符 fallback |
| 无巡视候选 | 进入 Wait/Scan，不读取隐藏地图补目标 |
| 动态阻挡 | 有界局部恢复；两次失败后放弃目标 |
| Observation/wire 版本不符 | strict reject；不改变 Agent/世界状态 |
| Python runtime 不可用 | 保持本地 reflex、deterministic fallback 和正常游戏 tick |
| checkpoint 缺失 | Phase 3 冷启动 Agent；Java 世界事实照常恢复 |

不提供存档或 wire feature flag。唯一兼容基线是 `VisionMode.OMNIDIRECTIONAL`，它比较玩法但不恢复旧 schema。

## 14. 风险与停止条件

### 14.1 主要风险

| 风险 | 后果 | 缓解 |
|------|------|------|
| `Player` 与 `PlayerRunState` 双写漂移 | 保存或换层 HP 错误 | 只在伤害/治疗/换层/存档边界同步，并以 invariant tests 覆盖 |
| save list 直接反序列化任意路径 | 路径穿越或菜单整体失败 | root confinement、扩展名/ID 校验、逐文件错误隔离 |
| temp 覆盖顺序错误 | 覆盖失败后丢档 | 先完整写 sibling temp，再 replace；绝不先删目标 |
| 图片路径依赖 cwd | 苹果显示失败 | 早期 spike + fallback + 启动目录测试 |
| FOV 先收实体后裁 mask | 背后玩家泄漏 | mask 先完成，再收 visible tile/entity |
| 巡视读取 world 选目标 | 提前获得地图意识 | PatrolController 类型上只接 Observation/limited snapshot |
| 保存 `Random` 实现对象 | Java 变化导致序列不稳 | 保存 ordinal，以 seed/identity/ordinal 派生选择 |
| Turn/Wait 引入多 Action 同 tick | 破坏 Phase 2 cadence | 生产 AiTickLoop 仍每 cooldown 最多执行一个 Action |
| hover FOV 与真实 FOV 两套算法 | 玩家 UI 撒谎 | UI 只读取 Enemy cached committed mask |
| 协议单边升级 | runtime 全部断开 | Java/Python/fixture 同一增量、同一 gate |
| checkpoint 使用 runId | 读档无法续接同楼记忆 | Phase 3 改用 world/floor/agent，runId 继续做请求安全 |

### 14.2 停止条件

出现以下任一情况，停止实现并回到 Spec/Decision：

- 需要复用旧 Player 才能保留 HP，或需要把所有 Player 字段都放入整局状态。
- 需要让 Agent 看见 apple、完整地图或背后玩家才能完成巡视。
- 需要在一个 action tick 内免费执行多个 Turn 才能完成扫描。
- 需要读取或迁移旧存档、旧 Observation、旧 envelope 或旧 trace 才能通过 gate。
- 需要改变游戏线程非阻塞、Session identity 或 commit barrier 才能加入新行为。
- emoji 图片要求迫使整个 `TETile.character` 公共契约改为字符串；此时使用已批准 fallback。
- 命名世界要求扩展为删除、重命名、云同步或周期 autosave。
- 发现 Phase 3 必须实现地图记忆或跨楼层画像才能使用本阶段输出。

## 15. Definition of Done

- [ ] `PlayerRunState.currentHp` 跨层保留，charge 按确认边界保存/清零。
- [ ] healthpack 文档全部非兼容验收条件通过，且 apple 不进入 Agent knowledge。
- [ ] 不限数量的命名世界可创建、列出、加载和安全覆盖，摘要字段正确。
- [ ] 保存使用受限路径和 temp-replace；坏档与旧档不破坏其他世界。
- [ ] Enemy maxHp、Facing、Turn/Wait 和成功/失败动作朝向规则实现并保存。
- [ ] directional 半菱形、LOS、地图边缘、墙角和全向 baseline 测试通过。
- [ ] 可保存巡视状态机、两次受阻恢复和 reflex interrupt 测试通过。
- [ ] 底部悬停条、朝向标记、FOV 显示和苹果图片/fallback 完成人工验收。
- [ ] `agent-session.v1`、`private-observation.v2` 和 `agent-runtime.trace.v2` 双语言 gate 通过。
- [ ] 触及的源码、测试、配置和 runtime 字符串使用领域命名，不含开发阶段编号。
- [ ] deterministic Suite 只列 leaf tests，无嵌套或重复执行。
- [ ] 默认 deterministic gate 无 GUI、真实网络、默认存档和 sleep。
- [ ] Phase 2 Session、deadline、cancel、fallback、feedback 和真实进程故障回归通过。
- [ ] Roadmap、healthpack、Phase 3 Spec 与 Build Guide 已同步。
- [ ] 验证命令、测试数、人工证据、偏差和最终 commit 写入 `PHASE_2DOT5_COMPLETION.md`。
- [ ] Phase 3 只在以上条件全部满足后开始。

## 16. Phase 3 交接

### 16.1 Phase 3 可以依赖

- 稳定 `worldId`、新 `runId` 语义与 named world save。
- `agent-session.v1` 和 `private-observation.v2` 的 Java/Python strict codec 与共享 fixtures。
- Observation self 的 current/max HP、Facing 和 VisionMode。
- directional/omnidirectional 可比较基线与不泄漏背后玩家的固定场景。
- 可保存的 Enemy facing、maxHp 和 deterministic patrol state。
- `TurnAction`、`WaitAction`、移动/攻击朝向与单 Action cadence。
- `agent-runtime.trace.v2` 的身份、感知、朝向、巡视和 Action 关联字段。
- `PlayerRunState`、healthpack 与 named saves 已完成，但 apple 对 Agent 仍等价于 floor。

### 16.2 Phase 3 不得假设

- 敌人已有完整地图意识、地图记忆、跨楼层记忆或共享上下文。
- checkpoint 是 Java save 的一部分，或缺失 checkpoint 会阻止读档。
- 当前本地巡视状态机等于未来模型的完整战术计划系统。
- apple 是 Agent 工具、skill 参数或可感知目标。
- 旧 envelope、Observation、intent 或 trace 仍被接受。
- 存档版本迁移、删除、重命名或 autosave 已实现。

### 16.3 Phase 3 首要入口

以 `PHASE_2DOT5_COMPLETION.md` 为唯一前置证据：将 deterministic runtime 和 Java skill seam 一次性升级到
`strategic-intent.v2`，建立按 `worldId/floorId/agentId` 隔离的有状态 graph/checkpoint，并继续复用
Java 的私有 Observation、reflex、巡视 primitive、validator、单 Action cadence 和非阻塞 Session。

## 附录 A：生成者自检

- [x] 已先读取 Roadmap §1、Intent、架构、Phase 2 Completion、healthpack、当前代码和测试。
- [x] 已区分当前事实、Builder 决定和拟议实现。
- [x] 已给出数据所有权、生命周期、接口、文件计划、顺序和失败路径。
- [x] 未把真实模型、Tool Calling、地图记忆或多 Agent 通信提前纳入。
- [x] 只为 wire/save summary/trace 使用 canonical fixture，不用完整行为 golden 锁死巡视。
- [x] 已定义 leaf-only deterministic gate、独立 integration 和人工 UI 验收。
- [x] 已明确旧存档、旧协议和旧 trace 不兼容。
- [x] 已明确 Phase 3 可以依赖与不得假设的 artifacts。
