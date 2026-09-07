# DungeonMind 开发路线图

> 状态：当前开发路线与跨 AI 交接基准
>
> 本路线图服从 [PROJECT_INTENT_zh-CN.md](PROJECT_INTENT_zh-CN.md)。它规定阶段目标、硬约束、阶段间输入输出和验收门槛；[AI_TICK_ARCHITECTURE.md](AI_TICK_ARCHITECTURE.md) 规定 Phase 2+ 的异步 Agent runtime、模型延迟与分层执行契约；当前阶段的具体实现由对应 `PHASE_N_SPEC.md` 根据真实代码展开。[LLMBrain_Agent_Build_Guide.md](documents/archive/designs/legacy-agent-build-guide.md) 保留为历史方案，不作为当前执行顺序。

## 1. 文档体系与优先级

发生冲突时，按以下顺序处理：

1. `PROJECT_INTENT_zh-CN.md`：产品目标、Agent 身份和不可违反的玩法原则。
2. `DEVELOPMENT_ROADMAP.md`：阶段边界、依赖关系和 Agent MVP 出口。
3. Roadmap 明确引用的跨阶段架构决策文档：当前为 `AI_TICK_ARCHITECTURE.md`，约束 Phase 2+ 的通信、时效、仲裁和执行骨架。
4. `PHASE_N_SPEC.md`：当前阶段已审计、已确认的实施契约。
5. 当前代码、自动化测试与运行证据：项目实际状态；若与文档不一致，必须先记录差异并修正文档或明确迁移方案。
6. `PHASE_N_COMPLETION.md`：本阶段真正完成的内容、证据、偏差和下一阶段输入。

旧 TDD、旧 Build Guide、brainstorm 和研究文档只提供历史背景，不能覆盖以上文档。

任何 AI 在生成或修改 Phase Spec 前，必须完整阅读 Intent、Roadmap、Roadmap 指定的跨阶段架构决策、上一阶段 Completion（Phase 0 除外），并检查当前相关代码和测试。不能只根据阶段标题扩写。

## 2. 推进与交接循环

每次只推进一个阶段：

1. **Discovery**：审计代码、测试、依赖和上一阶段输出，列出事实、风险与未知项。
2. **Specification**：按 `PHASE_SPEC_TEMPLATE.md` 生成 `PHASE_N_SPEC.md`，明确接口、文件、任务顺序和测试矩阵。
3. **Decision**：只有会改变产品体验、学习目标或跨阶段契约的问题才交给 Builder 决定；普通工程细节由 Advisor 给出建议。
4. **Build**：Builder 实现核心代码；Advisor 解释技术并审查实现。可将边界明确的调研、初稿或模块交给其他 AI。
5. **Verify**：运行自动化测试、固定场景和人工试玩，保留命令与结果。
6. **Close**：生成 `PHASE_N_COMPLETION.md`，记录实际完成、未完成、设计偏差、已知问题和交给下一阶段的 artifacts。

每个阶段都必须留下可运行结果和可复查证据，不能只完成框架或文档。未通过出口条件时不得把状态写成完成。

## 3. 不可违反的系统约束

- **INV-01 独立身份**：每个敌人是独立 Agent，拥有私有状态和上下文。
- **INV-02 有限知识**：Agent 只能获得自己实际看见、听见、收到或经历的信息。
- **INV-03 世界内通信**：信息共享必须通过可被阻止的世界内行为发生，不存在自动共享意识。
- **INV-04 Java 权威**：Java 游戏引擎是世界事实、合法性和状态修改的唯一权威来源。
- **INV-05 分层控制**：LLM 负责低频高层决策；移动、碰撞、战斗、寻路和紧急反射保持确定性。
- **INV-06 严格契约**：跨进程数据和 Agent 输出必须经过 schema、权限、知识来源与游戏规则校验。
- **INV-07 异步时效**：LLM 延迟不能阻塞游戏；过期、失去关联或来自旧楼层的决策不能生效。
- **INV-08 可追踪评估**：关键感知、意图、工具、动作、结果、延迟和回退必须可关联、可重放、可评估。
- **INV-09 玩法价值**：技术是否保留由实际能力、可读性和可玩性决定，而不是技术标签。

## 4. Phase Spec 的强制输入与输出

每份 `PHASE_N_SPEC.md` 必须来自以下输入：

- 当前 Intent 与 Roadmap；
- Roadmap 明确引用且与本阶段相关的跨阶段架构决策；
- 当前 Git 分支、基线 commit、相关代码和测试；
- 上一阶段 Completion 与 artifacts；
- 对易变化依赖所查阅的当前官方文档；
- Builder 已明确确认的产品决定。

每份 Phase Spec 必须至少写明：起始事实、需求追踪、范围与非目标、锁定决定、数据流与接口、逐文件改动、实施顺序、测试矩阵、失败路径、兼容策略、Definition of Done 和下一阶段交付物。

生成者不得把猜测写成事实。无法从仓库或权威文档确认的内容必须标注为“假设”或“待决定”，并说明它会影响什么。

## 5. 开发阶段

### 阶段 0：固定遭遇与基线

**目标**：建立一个可以反复运行、比较和调试的最小遭遇场景。

**前置输入**：Intent、Roadmap、当前仓库审计；这是唯一没有上一阶段 Completion 的阶段。

**交付物**：

- 手写 ASCII 固定遭遇 `baseline-two-guards-v1`：玩家、楼梯、守卫 A、守卫 B。
- 不依赖渲染、键盘、墙钟时间、网络和默认存档的 headless `ScenarioRunner`。
- `RuleBasedBrain` 基线运行记录。
- 以逻辑 tick、场景角色和单调 sequence 为核心的初始结构化 trace seam。
- 可重复执行的 JUnit 4 场景测试与 Phase 0 测试命令。
- `PHASE_0_COMPLETION.md`，包含测试结果、已知旧行为与 Phase 1 输入。

**完成标准**：同一 JVM 连续运行相同场景和脚本得到完全相同的 canonical trace；两个守卫可分别追踪；活实体不重叠或站上非法地形；测试全程 headless；明确记录现有回归基线。

**本阶段不做**：LLM、正式私有感知、通信、异步桥接、跨语言 schema、长期记忆、玩法平衡。当前全知 `GameStateSnapshot` 只能标记为 legacy input，不能升级为正式 Observation API。

### 阶段 1：私有感知与知识边界

**前置输入**：Phase 0 的固定场景、runner、trace seam、Completion 与已知 legacy 输入边界。

**目标**：让每个敌人只获得自身合理知道的信息。

**交付物**：

- Java `PerceptionSystem`。
- 每敌人独立的 `ObservationEnvelope`。
- 基础视觉/遮挡、听觉事件、自身状态和行动反馈字段。
- 稳定、可保存的 Agent 身份，以及 `runId / floorId / agentId / observationSeq / observedAtTurn` 版本字段。

**完成标准**：墙后玩家不会出现在观察中；未被观察的玩家 HP 和位置不会泄露；不同敌人的 observation 可以不同；相关契约测试全部通过。

**本阶段不做**：自然语言场景描述、向量数据库。

### 阶段 2：确定性端到端桥接

**前置输入**：Phase 1 已验收的私有 Observation contract、身份与 no-cheat 测试。

**目标**：在接入模型前打通 Java → Python → Java → 动作结果的完整链路，并固定游戏线程不等待网络或推理的双速执行骨架。

**交付物**：

- 版本化消息信封，以及最小 `ObservationEnvelope`、`StrategicIntent`、`ActionOutcome` schema；最小 intent 至少携带决策身份、目标/技能、参数、有效期和中断策略，不把跨进程输出定义为可直接执行的世界命令。
- Python deterministic fake runtime、健康检查与硬编码 intent，用于验证协议和时序，不接入真实模型。
- 每敌人持久 `AgentSession`：独立非阻塞 IO loop、有界 inbound/outbound queue、断线重连与 `sessionEpoch`；游戏线程只入队和轮询，不直接读写 socket。
- 每敌人最多一个有效 in-flight 请求、observation/event 合并、soft/hard deadline、取消或 Session 重建规则。
- Java `ReflexController`、`IntentArbiter`、`IntentLease` 与 `RuleBasedBrain` fallback，使模型等待期间仍能继续安全旧计划、响应当前威胁或本地接管。
- 五阶段游戏循环与 world commit barrier：消费消息、校验/采纳 intent、每个 action tick 至多执行一个动作、提交实体变化、生成并异步发送反馈。
- `runId / floorId / agentId / sessionEpoch / observationSeq / decisionId / requestGeneration` 校验和迟到结果丢弃。

**完成标准**：游戏循环在 Python 变慢、停止读取或断开时仍继续推进；等待期间敌人按原 cooldown 执行安全旧计划或本地 fallback；玩家进入当前 FOV 时快脑能在下一个 action tick 响应；迟到或语义过时的 intent 不会覆盖当前有效行为；一次决策能关联到实际动作、结果和 fallback；队列饱和不会阻塞游戏线程。

**本阶段不做**：真实 LLM 推理、完整多步骤条件计划、敌人间通信或复杂战术技能库。

### 阶段 2.5：持久世界状态与可读敌人感知

**前置输入**：Phase 2 已验收的异步传输、私有 Observation、分层仲裁、单 Action cadence、
commit barrier、deterministic fake runtime 与 Completion。

**目标**：在接入真实模型前补齐跨楼层资源压力、命名世界存档和可被玩家理解的敌人感知/巡视规则，
并把 Phase 3 需要的稳定世界身份、Observation 与回归基线一次性固定下来。

**交付物**：

- 显式 `PlayerRunState`：当前 HP 跨楼层保留；当前蓄力读档恢复、换层清零；未来整局成长与当前楼层状态边界形成文档契约。
- healthpack 需求中的确定性苹果生成、拾取、治疗、剩余状态保存与 Agent 隐藏规则。
- 不限数量的命名世界存档：创建、摘要列表、读取、覆盖、原子替换和坏档隔离；不迁移开发期旧存档。
- 稳定 `worldId` 与每次运行重建的 `runId`；保存时间、楼层、HP、难度等世界摘要。
- Enemy 最大 HP、四向 Facing、动作朝向语义、可保存巡视状态和原地 Turn/Wait 原子动作。
- 原全向菱形的一半作为默认 directional FOV，并保留墙壁遮挡与可保存的 omnidirectional A/B baseline。
- 只读私有 Observation 的确定性巡视：持续目标、到达停留、顺时针扫描、动态占位局部恢复和 reflex 中断。
- 底部悬停信息条、敌人方向标记、单敌人/全局 FOV 显示和苹果图片/fallback。
- 不依赖操作系统键盘重复率的玩家连续移动：按下立即响应、长按按游戏内固定节奏重复、松开不积压移动。
- `agent-session.v1`、`private-observation.v2`、`agent-runtime.trace.v2`、跨语言 fixtures、固定场景和 no-cheat 回归。

**完成标准**：玩家 HP、苹果、命名世界和当前楼层敌人状态可正确保存/恢复；相同输入产生相同苹果、
初始朝向和巡视行为；背后或墙后的玩家不进入敌人 Observation；玩家可以从朝向标记和 FOV 显示理解规则；
玩家连续移动不因系统键盘重复设置出现首步停顿、突发连走或松键后续走；
全向 baseline 与 Phase 2 的 Session、deadline、cancel、fallback、feedback 和非阻塞测试全部通过；
`PHASE_2DOT5_COMPLETION.md` 留下可复查证据。

**本阶段不做**：背包/装备/永久成长、敌人地图意识、跨楼层记忆、存档删除/重命名/版本迁移、周期自动存档、
真实模型、LangGraph、Tool Calling、复杂战术 skill 或多 Agent 通信。

### 阶段 3：单敌人 Agent Runtime

**前置输入**：Phase 2.5 已验收的命名世界状态、稳定 `worldId`、版本化私有 Observation、朝向/FOV、
确定性巡视、跨语言 fixtures、过期结果规则和 deterministic fake runtime。

**目标**：接入第一个真正有状态、能使用工具的敌人 Agent，并建立无需改动通信骨架即可扩展战术表达和确定性技能的接口。

**交付物**：

- 当前稳定版本的 Python、LangGraph/LangChain 依赖与锁文件；默认不安装具体 provider SDK。
- 按 `worldId / floorId / agentId` 隔离的 Agent state 和 checkpoint；`runId` 继续隔离当前运行的旧连接与迟到响应。
- 条件化 Tool Calling 循环，并设置最大轮数与 deadline。
- 全部 deterministic/model brain、Java/Python codec 和 fixtures 一次性硬切 `strategic-intent.v2`；不保留 v1 双读或降级输出。v2 不能冻结为仅有 `goal + target`，至少支持类型化 `skill`、受约束参数、有效期、中断策略和可扩展的计划元数据。
- Java 战术技能注册/执行 seam：把受支持的 skill 转换为确定性规划器和原子动作；未知 skill、非法参数、前提不满足和不可达目标必须显式拒绝。
- Python schema 校验和 Java 权威二次校验。
- 全局推理调度与预算：限制整个遭遇中的模型并发、排队长度和调用量；调度器只能安排调用，不能合并不同敌人的私有上下文或知识。
- `agent-model.trace.v1` 与 `agent-runtime.trace.v3`：可查看并关联模型、工具、延迟、token、校验和 fallback。
- provider-neutral `ModelAdapter`；具体 API、SDK、模型 ID 与凭据由 Builder 后续配置，届时补做真实 provider smoke。

**完成标准**：Agent 只能使用 observation 中的信息；scripted model 确实发生模型—工具—模型循环；同一命名世界同一楼层读档可恢复对应敌人的有界 checkpoint，而新世界或新楼层冷启动；受支持 skill 能通过确定性执行 seam 落为动作；未知、非法或不可达意图被拒绝；多个敌人的上下文不会串线；同时活跃敌人增多时模型调用仍受全局预算约束且游戏线程不阻塞。真实 provider 兼容证据在 Builder 配置 API 后补充。

**本阶段不做**：完整复杂战术技能库、多个敌人共享上下文、自动共享黑板、跨楼层长期记忆。

**完成状态（2026-08-13）**：供应商无关实现与自动化闸门已完成，证据见
[`PHASE_3_COMPLETION.md`](documents/phases/phase-3/PHASE_3_COMPLETION.md)。真实 provider smoke 等 Builder 后续配置自己的
API/adapter 后补验。

### 阶段 4：执行反馈与事件驱动重规划

**前置输入**：Phase 3 已验收的独立 Agent state、Tool Calling loop、结构化意图与 validator。

**目标**：把单次 LLM 决策变成持续的感知—行动—反馈循环。

**交付物**：

- 结构化 `ActionOutcome`：通过 `decisionId / planId / stepId` 关联成功、受阻、目标丢失、原因和位置变化。
- 反馈写回对应 Agent state。
- 事件触发器：发现目标、听见声音、收到消息、计划失败或完成。
- 规则反射与 LLM 深度思考之间的明确职责边界。
- 多步骤计划的有界执行契约：前置条件、成功/失败/终止条件、当前 step、局部修正范围和整体重新规划触发条件。
- 计划承诺期、暂停、恢复、取消和重新规划规则；任何快脑覆盖都保留原决策关联并产生反馈。

**完成标准**：至少一个多步骤计划能够跨多个 action tick 执行，并在步骤失败或关键前提改变时产生可追踪反馈和重新规划；没有重要事件时不会固定频率滥用 LLM；等待推理时游戏和低层反射继续运行。

**实现状态（2026-08-23）**：provider-neutral 生产实现与全部自动化闸门已完成，见
[`PHASE_4_COMPLETION.md`](PHASE_4_COMPLETION.md)。GUI 人工 `PLAY-01` 与真实 provider smoke
仍待 Builder 配置/操作，因此尚不宣称完整人工验收。

### 阶段 5：独立多 Agent 通信

**前置输入**：Phase 4 已验收的事件触发、ActionOutcome 和重规划闭环。

**目标**：让敌人通过世界内行为共享信息并形成可反制的协作。

**交付物**：

- `MessageEvent`：发送者、接收者、内容、来源、时间和置信度。
- 呼喊、听见报告、警报或设施交互中的至少一种完整链路。
- 消息传播距离、延迟、失败和中断规则。
- 可选 `coordinationId`、提议/确认和角色信息；这些信息只有通过成功的世界内通信才能进入接收者状态，并具有来源与过期时间。
- 每个敌人独立更新自己的信念，并在本地形成自己的 `RoleIntent` 或计划。

**关键验收场景**：守卫 A 看见玩家并实际呼喊；只有能听见的守卫 B 才收到最后目击信息并前往守楼梯或截击。若玩家提前杀死、打断或隔离 A，B 不得凭空获得该信息。

**完成标准**：该场景可重复通过；trace 能说明每条知识和角色分配来自哪次通信；玩家至少有一种办法阻止信息传播或破坏协作。

**本阶段不做**：共享 Agent 上下文、自动同步的队伍黑板、全知 `SquadCoordinator` 或单个 LLM 直接控制多个敌人。若未来引入队长，它也必须是世界内可感知、可隔离、可打断的独立 Agent。

### 阶段 6：本层记忆、评估与 Agent MVP 验收

**前置输入**：Phase 5 已验收的独立消息传播、阻断机制和双守卫协同场景。

**目标**：完成可测量、可解释、可试玩的第一个 Agent MVP。

**交付物**：

- 每敌人、每楼层的结构化工作记忆：最后目击、声音、报告、已搜区域、当前计划和执行结果。
- 信息时效、来源、置信度和楼层结束清理。
- 开发者 Agent overlay 或等价 trace viewer。
- Rule AI 与 Agent AI 的固定场景 A/B。
- 延迟、全局并发、调用量、成本、schema 合法率、fallback、过期结果和信息泄露指标。

**Agent MVP 通过条件**：

- 隐藏信息泄露为零。
- 独立 Agent 状态和通信传播测试通过。
- Tool Calling、checkpoint、执行反馈和重新规划均可在 trace 中证明。
- LLM 失败不会破坏游戏。
- 完整遭遇中的模型并发和调用量始终受预算约束，且不会通过调度器泄露敌人之间的私有状态。
- 玩家能观察到协作，也能通过阻断通信、绕行或战斗进行反制。

**本阶段不做**：Chroma、跨楼层玩家画像、强化学习或模型微调。

### 阶段 7：试玩驱动的玩法深化

**前置输入**：Agent MVP Completion、A/B 数据和实际试玩记录。

**目标**：根据真实游玩决定下一步，而不是预先假设游戏应该偏向哪种玩法。

候选方向包括地形伏击、搜索、声音欺骗、光照、设施、敌人类型，以及 `AMBUSH / FLANK / GUARD_EXIT / SUPPRESS` 等参数化战术技能和更丰富的去中心化小队行为。新增战术应复用 Phase 3–4 的 skill/plan seam，不得绕过私有知识、Java validator、IntentArbiter 或世界内通信。每项扩展都需要回答：玩家能否看懂、能否反制、是否比规则基线更有趣。

只有试玩证据表明结构化本层记忆不足时，才评估向量检索或更长期记忆。

## 6. 阶段间最小交付链

| 阶段 | 必须交给下一阶段的最小 artifacts |
|------|----------------------------------|
| 0 | 固定场景、headless runner、canonical trace、测试入口、Completion |
| 1 | Observation schema、稳定身份、no-cheat 测试、感知样例、Completion |
| 2 | 双向消息 schema、fake runtime、AgentSession、有界队列、双速执行骨架、时效/过期/非阻塞证据、Completion |
| 2.5 | PlayerRunState、苹果、命名世界存档、worldId、Facing/FOV、确定性巡视、Observation/trace/fixtures、Completion |
| 3 | Agent graph、checkpoint key、tool/intent/skill schema、确定性执行 seam、validator、全局推理预算、trace、Completion |
| 4 | 带 plan/step 关联的 ActionOutcome、多步骤执行契约、事件触发与 replan 规则、延迟/回退证据、Completion |
| 5 | MessageEvent、传播与阻断规则、RoleIntent/协作来源、双守卫场景证据、Completion |
| 6 | Agent MVP 验收报告、A/B 指标、试玩记录、已知限制 |

## 7. 其他 AI 的参与规则

- 其他 AI 可以负责调研、独立审查、Phase Spec 初稿或边界明确的模块。
- 任何跨阶段接口、系统约束变化和 Roadmap 状态变化，必须由持续 Advisor 审查。
- 其他 AI 的回答不是完成证据；只有仓库中的代码、测试结果、trace 和 Builder 确认能关闭阶段。
- 接手 AI 必须在输出开头列出其读取的基准文件、commit 和相关代码，避免使用过期上下文。

## 8. 当前里程碑

阶段 0–6 合起来构成第一个 Agent MVP；中间阶段只是可验证的工程增量，不代表项目将 Agent 技术推迟到以后。

Phase 0、Phase 1 和 Phase 2 已留下 Completion 与对应 artifacts；Phase 1.5 是可视化增强，不是 Agent 主线 gate。
Phase 2 已有 Completion；Phase 2.5 的最终实现基线为 `e6befe6`，Phase 3 从该提交创建 `result` 分支。

Phase 2.5 自动验收已完成：`PHASE_2DOT5_COMPLETION.md` 记录了 173 个确定性测试、
真实进程集成、共享 fixtures、领域命名迁移与连续移动证据。原 UI 视觉已由用户确认；新操控手感仍未人工复验。
Builder 于 2026-08-13 明确授权先提交该自动化基线并进入 Phase 3；该裁决不等于伪造人工复验结果。
Phase 3 的具体 provider/API 由 Builder 后续配置，本轮先完成 LangGraph、Tool Calling、skill registry、
checkpoint、调度与 scripted integration。
