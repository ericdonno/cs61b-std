# DungeonMind 写作素材底稿

> 文档用途：供后续 Agent 与项目作者讨论文章角度、个人能力和简历材料时使用。
>
> 它不是 README、项目介绍、宣传稿、最终博客或最终简历文案。
>
> 最近整理：2026-09-06。

## 1. 事实标记与使用边界

本文使用四种标记，后续写作不得混用：

- **[仓库事实]**：可以从代码、Git 历史、测试或 Completion 复查。
- **[用户陈述]**：来自项目作者对开发过程的回忆，目前不一定能由仓库独立证明。
- **[合理推断]**：由多份材料共同支持，但仍不是直接证据。
- **[待确认]**：公开写作前需要项目作者补充或裁决。

项目包含两套容易混淆的“阶段”概念：

1. **开发方式阶段**：作者所说的 CS61B Project 2 Phase 1、Phase 2，以及后来引入 Codex 的时期。
2. **DungeonMind 正式阶段**：2026-07-20 后建立的 Phase 0–7 工程路线。

后续文章应把前者称为“开发时代”或“协作模式”，把后者称为“DungeonMind 工程阶段”，避免让读者误以为
两个 Phase 2 是同一件事。

## 2. 已确认的开发方式变化

项目开发控制方式先后发生了三次变化：

1. 作者从课程项目出发，先以传统方式手写和理解系统。
2. Trae + DeepSeek 能继续实现明确功能，生成代码仍处于作者能够阅读和判断的范围；瓶颈出现在准备设计
   LLM AI 时，现有模型的设计能力不足以支持下一步，而不是作者失去了代码审查能力。
3. 引入 Codex 后，作者使用 Grilling skill 和 GPT-5.6-sol 进行高强度设计推演，建立 Intent、Roadmap、
   Phase Spec、Build Guide、Completion、固定场景和验证闸门。人的职责由逐项实现进一步上移到产品目标、
   系统边界、阶段裁决、验收真实性和最终体验。
4. 上述工作流使此前无法继续设计的 LLM AI 被拆成有边界、有顺序、有验收条件和测试证据的工程阶段，项目得以
   继续推进；与此同时，Codex 生成的架构和文档逐渐超过作者的阅读能力，作者在后期基本直接信任 Codex 的判断。

**[用户陈述]** 上述第二、三点是作者对协作过程的明确校正，应优先于旧文档中可能暗示“review 带宽失控”的说法。

## 3. 项目的由来

### 3.1 课程项目底盘

**[仓库事实]** 仓库来自 Berkeley CS61B Spring 2018 skeleton，保留 TileEngine、课程示例和早期课程提交历史。
项目核心原型是 BYOG：基于 seed 生成房间和走廊，提供键盘交互、存档和 tile 渲染。

**[用户陈述]** CS61B Project 2 的第一个开发阶段为纯手写。作者希望这一段在个人材料中承担两个作用：

- 证明自己在引入 Coding Agent 前已经具备亲自实现、阅读和调试传统 Java 项目的能力；
- 说明后来使用 Agent 不是用来跳过基础学习，而是建立在已有代码和理解之上。

**[待确认]** 纯手写阶段具体包含哪些类和功能，以及它与 Git commit `690dd54`（`just trae started`）之间的
准确边界。该 commit 一次加入 32 个项目文件、3398 行内容，更像一次边界快照，但 Git 本身不能说明这些内容
分别由谁编写。

### 3.2 从完成课程要求到扩展游戏

**[仓库事实]** 2026-06-21 至 2026-07-15 的提交依次覆盖输入处理、存读档、键盘游戏、敌人、碰撞与速度、
传统 AI、战斗、楼层和楼层冻结修复。关键提交包括：

| 日期 | Commit | 仓库记录的变化 |
|------|--------|----------------|
| 2026-06-21 | `9a2f923` | 重构 `playWithInputString` |
| 2026-06-22 | `4f4c206` / `6e7ec39` | 存读档与键盘游玩 |
| 2026-07-11 | `bc44226` | 加入敌人 |
| 2026-07-12 | `fa4ea57` | 碰撞与移动速度 |
| 2026-07-14 | `d617877` | 传统 AI 完成 |
| 2026-07-14 | `b297534` | 战斗与多楼层 |
| 2026-07-15 | `a97353e` | 楼层问题修复 |

**[用户陈述]** 这一时期属于 Trae + DeepSeek 阶段。作者仍能阅读和理解生成代码，并通过检查和修改维持
控制。到 Phase 2 和本地 AI 完成、准备设计 LLM AI 时，开发遇到的瓶颈是模型能力和系统设计能力不足，
无法可靠推进下一层架构。

**[用户陈述]** [早期 DungeonMind 构建指南](archive/designs/early-dungeonmind-build-guide.md) 曾在
Trae + DeepSeek 阶段实际指导传统 AI 的完成。作者按文档逐步实施；到“感知层”章节时，文档开始变得空洞，
代码构建缺少明确方向，游戏设计也没有形成清晰目标，项目走向混沌。作者由此意识到当时模型能力和工作流
已经到达上限，并开始寻找能力更强的模型与工作方式。

### 3.3 项目脱离课程边界

**[仓库事实]** `PROJECT_INTENT_zh-CN.md` 将项目重新定义为 DungeonMind：作者通过真实游戏系统学习现代
Agent 工程；目标不是一次 API 调用，而是可观察的感知—推理—工具—行动—反馈—通信循环。

**[仓库事实]** `5ad0555`（2026-07-20，`mind phase0, test stage`）开始引入固定遭遇、headless harness、
canonical trace 和 deterministic baseline。随后 `4c1bc98` 将 Intent、Roadmap、架构文档、阶段规范和
大量历史设计材料纳入仓库。这里可以视为课程项目向 DungeonMind 转型的可验证节点。

**[用户陈述]** 设计能力瓶颈促使作者引入 Codex；通过 Grilling skill 与 GPT-5.6-sol 进行头脑风暴后，
形成了一套专业、复杂且可持续推进的工作流。此后项目进入纯 Vibe Coding：Agent 承担大量调查、设计展开、
实现和测试，人类负责更高层的意图、取舍、验收与方向控制。

## 4. 开发过程：协作模式与工程阶段的双时间线

### 4.1 协作模式时间线

| 开发时代 | 已知工作 | 作者的职责 | 当前证据状态 |
|----------|----------|------------|--------------|
| 传统手写 | CS61B Project 2 的早期实现 | 直接设计、编码、调试并建立代码理解 | 用户已确认；具体文件边界待补 |
| Trae + DeepSeek | 完成交互底盘、敌人、传统 AI、战斗和楼层；早期 Build Guide 可以逐步指导明确功能 | 阅读生成代码、按 Guide 实施、判断实现、修正问题 | 用户已确认；Git 可证明功能演进，不能单独证明生成归属 |
| 能力瓶颈 | 本地 AI 已完成；Build Guide 到感知层后内容空洞，代码和游戏设计失去明确方向 | 识别到原模型和工作流无法继续承担开放式 Agent 架构设计 | 用户已确认；具体失败输出待补 |
| Codex 设计转折 | Grilling skill + GPT-5.6-sol；建立 Intent、Roadmap 与阶段流程 | 定义愿景、不变量和决策边界，接受或否决设计 | 用户已确认；仓库保留大量结果文档 |
| 纯 Vibe Coding | DungeonMind Phase 0–4 的文档、实现、测试和迭代 | 高层产品控制、范围裁决、证据验收、人工试玩 | 用户已确认；Completion 留有 Builder 裁决记录 |

“纯 Vibe Coding”在这里不应解释成作者不参与，而应解释成作者不再把逐行亲写代码作为主要控制手段。
其控制面转移到了：项目意图、权限边界、不可违反的不变量、阶段范围、验收条件、异常处理和体验判断。

### 4.2 DungeonMind 工程阶段

| 阶段 | 要解决的问题 | 已交付事实 | 验证状态 |
|------|--------------|------------|----------|
| Phase 0 | 后续 Agent 行为无法被稳定比较 | 双守卫固定场景、headless runner、canonical trace、规则基线 | 19 tests PASS |
| Phase 1 | 旧 AI 能读取完整世界，存在“作弊”输入 | 每敌人私有 Observation、LOS、稳定身份、no-cheat 基线 | 27 tests PASS（含回归） |
| Phase 1.5 | 玩家和开发者看不懂敌人视野 | FOV overlay、配置开关、渲染层叠加 | 自动验证完成；当时有人工视觉确认 |
| Phase 2 | LLM/网络延迟可能阻塞游戏，跨进程结果可能过期 | Java↔Python 严格协议、每敌人 Session、非阻塞 transport、deadline、cancel、fallback、仲裁 | Java 100 + Socket 9 + Integration 7 + Python 23，分别 PASS |
| Phase 2.5 | 接模型前缺少稳定世界状态和可读玩法 | 命名存档、world/run identity、苹果、方向/FOV、确定性巡视、连续移动 | Core regression 174、Python 26 等 PASS；新操控手感未人工复验 |
| Phase 3 | 一次性 intent 不是有状态工具 Agent | LangGraph、独立 checkpoint、tool loop、全局预算、`strategic-intent.v2`、Java skill registry | Python 34、Agent runtime 58、Integration 8、Core regression 176 等 PASS；阶段关闭时真实 provider 未验，2026-09-06 后续交互式运行已接通 |
| Phase 4 | 模型决策与实际执行结果没有持续闭环 | 有界多步骤计划、ActionOutcome、step progress、execution inbox、事件触发重规划、trace v4 | 工作树 Completion 记录 Python 39、Agent runtime 66、Integration 9、Core regression 181 等 PASS；GUI `PLAY-01` 未做 |
| Phase 5 | 独立敌人如何通过世界内行为通信 | 尚未实现；计划实现可传播、可阻断、可追溯的消息 | 未开始 |
| Phase 6 | 如何证明 Agent MVP 的玩法价值 | 计划进行 A/B、指标、overlay 和 MVP 验收 | 未开始 |

各行测试数来自对应 Completion，测试套件可能互相包含，不能相加后宣称“总测试数”。

## 5. 作者真正做过的工作

这一节记录贡献性质，不直接写成简历 bullet。

### 5.1 传统工程工作

**[用户陈述 + 仓库演进支持]**

- 在早期阶段亲自完成并理解 CS61B Project 2 的基础实现。
- 在 Trae + DeepSeek 阶段阅读生成代码、判断设计是否符合预期并修正问题；代码量仍在可理解范围。
- 推动地图、交互、存档、实体、碰撞、传统 AI、战斗和多楼层从课程骨架成为可玩的游戏底盘。

**[待确认]** 上述每项中哪些是完全手写、哪些由 Agent 初稿后人工修改、哪些主要由 Agent 完成。

### 5.2 识别并处理设计瓶颈

**[用户陈述]**

- 识别到“能实现明确需求”不等于“能设计 LLM Agent 系统”。
- 没有继续要求能力不足的模型硬生成，而是更换到 Codex/GPT-5.6-sol，并使用 Grilling skill 先压力测试
  产品目标和设计假设。
- 接受项目脱离 CS61B autograder API 和原包结构，使 DungeonMind 可以按稳定职责重新组织。

### 5.3 建立面向 Coding Agent 的治理结构

**[用户陈述 + 仓库事实]**

- 确认 Project Intent：为什么做、什么才算真实 Agent、什么技术不该只为标签加入。
- 采用 Roadmap 将 Agent MVP 拆成可验收的增量，而不是一次生成完整架构。
- 要求当前 Spec 来自真实代码审计，并由 Build Guide 映射实施顺序。
- 通过 Completion 区分“计划完成”“自动化通过”“人工试玩通过”和“真实 provider 已验证”。
- 持续做产品裁决，例如允许自动化基线通过后进入下一阶段，同时明确保留未人工复验项。
- 维护 Java 权威、私有知识、独立 Agent、异步时效和可追踪性等长期不变量。

这部分是项目最能体现高级职责的材料：作者管理的不是单次 prompt，而是 Agent 能够持续工作的上下文、权限、
质量门和交接机制。但“职责上移”不等于作者始终完整掌握 Agent 的全部产出；后期架构和文档已经看不过来，
作者基本直接信任 Codex 的判断。这是当前协作方式的事实边界，不应改写成作者逐项审阅了全部设计与证据。

### 5.4 当前不应过度归因的内容

- 不能仅凭 Git author 把 Codex/Trae 生成的每一行写成作者亲手编码。
- 不能把 Agent 生成的文档行数、历史课程 skeleton 或生成 fixtures 计为个人代码量。
- 可以归因给作者的是：问题选择、目标定义、约束与取舍、工具/模型切换、任务组织、验收裁决、人工体验反馈，
  以及作者确实亲自完成或审查的实现部分。

## 6. 关键问题、解决方式与结果

### 6.1 原有模型到达系统设计上限

- **问题**：[用户陈述] Trae + DeepSeek 能完成 Phase 2 和本地 AI，但准备设计 LLM AI 时无法可靠继续。
- **处理**：引入 Codex，使用 Grilling skill 与 GPT-5.6-sol 做设计推演；先建立 Intent 和 Roadmap，再进入实现。
- **关键取舍**：不把“继续生成代码”当作默认答案，先升级问题建模和工程控制方式。
- **结果**：形成 Phase 0–7 路线、文档优先级、系统不变量和逐阶段 Completion。
- **缺失证据**：需要一个具体例子说明旧模型在哪个设计问题上卡住，以及 Grilling 后结论如何变化。

### 6.2 生成式敌人容易变成全知控制器

- **问题**：旧 `GameStateSnapshot` 暴露完整世界和玩家精确位置；如果直接交给 LLM，Agent 只是更昂贵的作弊 AI。
- **处理**：引入 `PerceptionSystem` 和每敌人私有 Observation；固定双守卫场景，让一个守卫看见玩家、另一个被墙遮挡。
- **关键取舍**：允许 Agent 基于有限事实推断，但不允许读取引擎拥有的全部事实。
- **结果**：Phase 1 的 no-cheat、身份和不同 Observation 测试通过；当前协议已演进到 `private-observation.v3`。

### 6.3 LLM 的速度与游戏循环不在同一时间尺度

- **问题**：模型或网络可能在秒级响应，游戏不能停下来等待；断线、慢响应、队列饱和也不能冻结玩家。
- **处理**：每敌人 `AgentSession`、独立 IO、有限队列、soft/hard deadline、取消和重连；游戏线程只入队与轮询。
  `RuleBasedBrain` 和 `ReflexController` 在等待期间继续提供安全行为。
- **关键取舍**：LLM 只做低频高层决策，移动、碰撞、战斗和紧急反射保持确定性。
- **结果**：Phase 2 用真实 Python 子进程覆盖 delay、disconnect、no-read 等故障；Session 和 transport 测试独立运行。

### 6.4 迟到的正确答案仍然可能是错误动作

- **问题**：模型回复到达时，玩家、楼层、请求或敌人状态可能已经改变。
- **处理**：用 `worldId / runId / floorId / agentId / sessionEpoch / observationSeq / decisionId /
  requestGeneration` 建立时效和归属；失去关联的结果被拒绝。
- **关键取舍**：不尝试让旧决定“尽量生效”，宁愿明确丢弃并回退。
- **结果**：协议、Session、取消和迟到抑制形成跨 Java/Python 测试。

### 6.5 模型不能直接修改游戏世界

- **问题**：如果模型输出直接成为移动或伤害命令，schema 合法不代表知识、权限和游戏规则合法。
- **处理**：Python 只提交 `StrategicIntent` 或有界 plan；Java 的 validator、`TacticalSkillRegistry`、planner、
  `ActionQueue` 和 commit barrier 才能执行动作。
- **关键取舍**：Java 是世界事实和状态修改的唯一权威；Python 是提议者，不是第二个游戏引擎。
- **结果**：PATROL、CHASE、ATTACK、GUARD 通过统一 skill seam 校验、规划和恢复；未知或非法 skill 被拒绝。

### 6.6 一次性意图不等于 Agent 循环

- **问题**：模型返回一次目标后，系统不知道动作是否成功、为何失败，也无法在多 tick 中持续执行计划。
- **处理**：Phase 4 引入 plan/step 关联、结构化 `ActionOutcome`、Java 权威进度判断、有界 execution inbox、
  局部恢复和事件触发重规划。
- **关键取舍**：步骤成功可以不调用模型直接推进；失败或重要事件才重新推理，heartbeat 不触发模型。
- **结果**：自动化覆盖多步骤推进、blocked→reroute→replan、去重和两阶段消费。

### 6.7 独立敌人不应共享意识

- **问题**：最简单的多敌人实现是共享一个上下文或全局黑板，但这会破坏信息玩法，也让玩家无法阻断协作。
- **处理**：每个敌人独立 Session、state 和 checkpoint；调度器只共享计算预算，不共享知识。
- **关键取舍**：Phase 5 的协作必须通过世界内消息产生，消息需要距离、延迟、失败和来源。
- **当前结果**：独立状态和隔离已实现；真正的消息 producer 与可阻断传播尚未实现。

### 6.8 Vibe Coding 产生了文档规模问题

- **问题**：不同 Agent 生成了大量给人、给 Agent 或同时给两者阅读的文档；活规范、历史方案和工作记录混在一起。
- **处理**：建立权威优先级，区分 Intent/Roadmap/当前阶段/Completion；将 Phase 0–3 和历史材料归档，增加统一导航。
- **关键取舍**：不删除历史，因为错误方案和演进过程具有写作价值；但历史文档不能覆盖当前实现。
- **结果**：2026-09-02 整理 40 份文档；正式文档范围检查 59 个 Markdown，本地断链、`file:///` 链接和
  未闭合代码块均为 0。
- **未解决问题**：[用户陈述] 文件位置和权威级别得到整理，但 Agent 生成信息的速度仍超过作者的阅读能力；
  文档治理尚未解决“人如何在有限时间内掌握真正重要内容”的问题。

### 6.9 复杂架构并不会自动变得可理解

- **背景**：[用户陈述] Codex 生成了专业且复杂的架构，作者曾使用 Mermaid 图尝试理解这些结构。
- **结果**：[用户陈述] Mermaid 对理解架构有效。作者曾花费较长时间阅读一张复杂的 AI tick 图，并最终将该图
  理解透彻。
- **局限**：[用户陈述] 单张图能够理解，不代表完整架构容易掌握；作者需要阅读许多张复杂程度相近的 Mermaid 图，
  累计阅读成本超过了可承受范围。模型知道系统由什么组成，却不知道作者不知道什么，因此难以主动选择最能填补
  作者知识缺口的视角、层级和解释顺序，也无法自然给出最短理解路径。
- **当前状态**：架构文档能够保存系统事实，但“如何把复杂架构转化为作者容易掌握的心智模型”仍未形成稳定方法。
  作者确认，面对后期超出阅读能力的材料时，实际做法是基本直接信任 Codex 的判断。目前没有确认一次能够明确
  归因于这种信任方式的具体工程事故，因此只能记录理解和掌控方式的变化，不能把潜在风险写成已发生的失败。

### 6.10 Ponytail 尚未形成项目内的结果证据

- **背景**：[用户陈述] 作者认为 Ponytail 所代表的 YAGNI、优先复用和最小充分实现值得用于约束 Vibe Coding
  中的过度设计。
- **事实边界**：[用户陈述] DungeonMind 中没有一个明确设计能够证明在使用 Ponytail 后被删除、缩小或改成
  更直接的实现，也没有同类任务开启与关闭 Ponytail 的对照记录。
- **使用限制**：后续材料可以把 Ponytail 写成作者后来形成或引入的复杂度治理思路，不能写成它已经在本项目中
  产生了可量化成效，也不能用它构造不存在的成功闭环。

## 7. 重要取舍清单

项目已经形成以下取舍：

| 取舍 | 选择 | 放弃或推迟的方案 | 原因 |
|------|------|------------------|------|
| 世界权威 | Java 唯一提交状态 | Python/LLM 直接执行动作 | 保持规则、安全和可测性 |
| 决策分层 | LLM 低频战略 + 本地快速反射 | 每一步都问模型 | 延迟、成本和玩法响应 |
| 知识边界 | 每敌人私有 Observation | 把完整世界塞进 prompt | 防止作弊并创造信息玩法 |
| 多 Agent | 独立 state/checkpoint | 共享意识或全知队长 | 通信必须可追踪、可阻断 |
| 版本演进 | 跨语言协议一次性硬切 | 长期双读旧版本 | 开发期减少语义歧义和兼容负担 |
| 模型接入 | 先 provider-neutral 自动化 | 一开始绑定付费 provider | 先验证系统结构，测试不依赖外网 |
| 记忆 | 当前楼层的有界结构化状态 | 立即引入向量数据库/RAG | 尚无证据证明检索复杂度有玩法价值 |
| 工具协议 | 内部明确接口 | 为标签而引入 MCP | Java/Python 本地边界暂不需要额外协议层 |
| 玩法方向 | 先做可玩原型再决定侧重 | 预先锁死潜行或战斗路线 | 最终应由试玩而非技术想象决定 |
| 验收 | 自动化、人工和 provider 证据分开 | 用文档状态代替实际验证 | 防止 Agent 把计划写成完成事实 |

## 8. 当前结果与未完成边界

### 8.1 已有结果

**[仓库事实]**

- 一个可运行的程序生成 2D 地牢底盘，包含交互、敌人、战斗、多楼层、存读档、资源恢复和难度配置。
- 一套确定性规则 AI，可作为 Agent AI 的快速反射、故障回退和 A/B 基线。
- Java 与 Python 分离的异步 Agent runtime；游戏线程不直接等待模型或 socket。
- 私有感知、稳定身份、严格 schema、跨语言 fixtures、延迟结果抑制和 Java 二次校验。
- LangGraph 有状态工具循环、每敌人 checkpoint、全局模型预算、脱敏 trace 和 Java 战术技能 registry。
- 当前工作树实现了多步骤执行反馈和事件驱动重规划，Completion 记录全部自动化闸门通过。
- 2026-09-06，作者确认当前工作树已经连接真实外部 LLM provider 并完成一次交互式运行；保留的日志截图显示
  `REQUEST sent`、`REMOTE intent`、`STEP SUCCEEDED` 和 `LOCAL fallback` 同时出现。该证据说明真实 provider
  请求、远程意图采用、执行反馈和本地降级在实际运行中发生过，但不替代正式 Completion 或完整人工验收。
- 当前源码规模约为 110 个 Java 生产文件、28 个 Java 测试文件、20 个 Python runtime 文件和 8 个 Python
  测试文件；这些数字包含课程遗留模块，不能等同于作者或 Agent 的净新增贡献。

### 8.2 尚未完成或不应公开声称完成

- Phase 4 的 GUI `PLAY-01` 尚未执行。
- Phase 4 代码和 Completion 当前主要位于未提交工作树，不属于 `result` 分支 HEAD `7e0665a` 的已提交事实。
- Phase 5 的世界内通信 producer、传播距离、延迟、失败和阻断尚未实现。
- Phase 6 的 Agent MVP A/B、指标和最终验收尚未完成。
- `PROJECT_INTENT` 所描述的玩家可观察、可欺骗、可分割的协同敌人体验尚未达到最终形态。
- Phase 3/4 Completion 当时没有验证真实 provider；2026-09-06 的后续交互式运行已经补充“真实调用发生过”的
  证据，但尚未回写为正式阶段验收，也不能据此声称完整 Agent MVP 已完成。

### 8.3 可以保守使用的成果证据

- Phase 2：100 项 Java deterministic suite、9 项 socket、7 项真实 Python 进程 integration、23 项 Python
  unit tests 分别通过。
- Phase 2.5：174 项核心回归、9 项 socket、7 项 integration、26 项 Python tests 分别通过。
- Phase 3：34 项 Python、58 项 Agent runtime、8 项 integration、9 项 socket、176 项核心回归分别通过。
- Phase 4：Completion 记录 39 项 Python、66 项 Agent runtime、9 项事件式 integration、9 项 socket、181 项
  核心回归分别通过。

这些数字只能按测试入口分别陈述，不能相加成一个总数，也不能在没有重新运行的情况下写成“当前最新测试”。

## 9. 证据索引

### 9.1 产品与工程基准

- [`PROJECT_INTENT_zh-CN.md`](../PROJECT_INTENT_zh-CN.md)：项目为什么存在、Agent MVP 和玩法北极星。
- [`DEVELOPMENT_ROADMAP.md`](../DEVELOPMENT_ROADMAP.md)：Phase 0–7、系统不变量和推进循环。
- [`AI_TICK_ARCHITECTURE.md`](../AI_TICK_ARCHITECTURE.md)：双速游戏循环与控制分层。
- [`AGENTS.md`](../AGENTS.md)：长期仓库边界、文档规则和验收要求。

### 9.2 阶段完成证据

- [Phase 0 Completion](phases/phase-0/PHASE_0_COMPLETION.md)
- [Phase 1 Completion](phases/phase-1/PHASE_1_COMPLETION.md)
- [Phase 1.5 Completion](phases/phase-1.5/PHASE_1DOT5_COMPLETION.md)
- [Phase 2 Completion](phases/phase-2/PHASE_2_COMPLETION.md)
- [Phase 2.5 Completion](phases/phase-2.5/PHASE_2DOT5_COMPLETION.md)
- [Phase 3 Completion](phases/phase-3/PHASE_3_COMPLETION.md)
- [Phase 4 Completion](../PHASE_4_COMPLETION.md)

### 9.3 当前实现入口

- [`byog/Core/Game.java`](../byog/Core/Game.java)：顶层游戏循环与 composition。
- [`byog/AI/AiTickLoop.java`](../byog/AI/AiTickLoop.java)：AI tick 分层协调。
- [`byog/Perception/PerceptionSystem.java`](../byog/Perception/PerceptionSystem.java)：私有感知。
- [`byog/Bridge/AgentSession.java`](../byog/Bridge/AgentSession.java)：每敌人会话和请求生命周期。
- [`byog/Bridge/SocketTransport.java`](../byog/Bridge/SocketTransport.java)：TCP/NDJSON transport。
- [`byog/AI/TacticalSkillRegistry.java`](../byog/AI/TacticalSkillRegistry.java)：Java 权威技能注册与执行 seam。
- [`agent/python/dungeonmind_agent/brain/graph_agent.py`](../agent/python/dungeonmind_agent/brain/graph_agent.py)：Python graph brain。
- [`agent/python/dungeonmind_agent/graph/workflow.py`](../agent/python/dungeonmind_agent/graph/workflow.py)：工具与计划工作流。
- [`agent/contract/README.md`](../agent/contract/README.md)：跨语言 wire contract。
- [`dungeonmind-real-provider-runtime.png`](writing/assets/dungeonmind-real-provider-runtime.png)：2026-09-06 真实外部
  provider 交互式运行的脱敏日志截图；证据范围不超过单次实际运行。

### 9.4 Git 转折节点

- `690dd54`：仓库消息为 `just trae started`；需要作者确认它代表开始使用 Trae，还是提交此前手写成果的快照。
- `d617877`：传统 AI 完成。
- `a97353e`：战斗、多楼层和冻结问题修复后的基线。
- `5ad0555`：固定场景、trace 和 DungeonMind Phase 0。
- `4c1bc98`：Intent、Roadmap、Phase 0/1/1.5 和核心 AI tick 文档集中形成。
- `ce321d7`：正式 DungeonMind Phase 2 协议和本地 AI loop。
- `142452b` / `e6befe6`：Phase 2.5 与后续输入/巡视修正。
- `7e0665a`：已提交的 provider-neutral 有状态 Agent runtime（Phase 3）。
- Phase 4：当前主要由 `7e0665a` 之后的工作树和 `PHASE_4_COMPLETION.md` 记录，尚未形成新的 HEAD commit。

## 10. 公开与隐私风险

### 10.1 明确需要处理

- Git 历史暴露一个 `2674…@qq.com` 个人邮箱，并同时使用 `ericdonno`、`huapple` 两个 author 名称。
- Git remote 包含 GitHub 用户名 `ericdonno`；公开文章是否直接关联该账号需要作者决定。
- `config/provider.local` 当前存在，虽然使用 Windows DPAPI 加密且被 Git 忽略，仍不得复制、截图或提交。
- 不得公开 API key、Authorization header、完整 raw prompt、完整 provider response 或自由 reasoning trace。
- `save/game.ser` 是本地玩家存档；除非确认内容无个人命名信息，否则不要作为下载 artifact。
- 本地路径、Windows 用户名和截图中的 IDE/终端信息应在发布前检查。

### 10.2 可能影响专业呈现

- Git 历史包含不适合公开展示的 commit message，例如 `fuck everything`；公开提交历史前应决定是否保留原历史、
  重新整理作品展示分支，或只在文章中链接干净的项目快照。
- 仓库包含 Berkeley CS61B skeleton 和课程材料。公开源代码或把整个仓库作为个人原创项目前，需要检查课程政策、
  原始代码许可和署名要求。
- 文档及启动脚本出现小米 MiMo endpoint 和模型名。是否公开供应商、调用成本和兼容性结论需要作者确认。
- `reports/` 下另有多智能体协作研究文档；它们是否属于本项目叙事、是否包含外部资料引用或私人讨论，尚未审计。

### 10.3 事实表述风险

- 不要把“使用 GPT-5.6-sol”写成模型能力的客观排名；只能作为作者实际使用的工具和转折背景。
- 不要把“专业且复杂”作为无证据形容词；应展示具体机制，例如不变量、阶段门、跨语言 contract 和失败演练。
- 不要声称“完整多 Agent 游戏已经完成”；当前完成到单敌人有状态 runtime 与执行反馈，通信和最终 MVP 尚未完成。
- 不要声称所有代码均为作者亲手编写，也不要反过来说作者只提供了想法。应准确描述不同阶段的控制方式。

## 11. 需要作者补充的问题

### A. 必须先确认的历史边界

1. 你所说的“CS61B Project 2 Phase 1 纯手写”具体完成了哪些功能？世界生成、房间/走廊、输入、渲染、
   存档分别属于哪个阶段？
2. `690dd54`（`just trae started`）是 Trae 开始前的手写成果快照，还是已经包含 Trae 生成内容？
3. 你所说的“Phase 2”是课程项目自己的第二阶段，还是你当时对扩展开发的称呼？它结束时最准确的功能边界是什么？
4. Trae + DeepSeek 阶段使用的具体 DeepSeek 模型是什么？Trae 主要负责计划、编码、修复，还是三者都有？

### B. 最关键的转折故事

5. 除了早期 Build Guide 的感知层变得空洞外，旧模型还具体卡在哪些问题上？是否保留了失败设计或对话？
6. 第一次使用 Grilling skill 时，你带进去的原始想法是什么？它连续追问或推翻了哪些假设？
7. GPT-5.6-sol 最终帮助锁定的三个最重要决定是什么？例如独立 Agent、Java 权威、异步双速、阶段路线中，
   哪些是那轮讨论真正产生的？
8. 你为什么愿意让项目脱离 CS61B，而不是另起一个新仓库？这是为了保留真实遗留系统、学习连续演进，
   还是出于别的原因？

### C. 人类职责与实际工作方式

9. 哪些决定必须由你本人拍板？请给出至少两个你否决、修改或延后 Agent 建议的案例。
10. 你是否仍会直接修改代码？如果会，通常是哪类修改：关键算法、最后一公里修复、配置、测试还是 UI 手感？
11. 在基本信任 Codex 判断的情况下，你如何决定什么时候仍需亲自下潜检查代码、trace、运行画面或 Completion？

### D. 结果、演示和下一步

12. 2026-09-06 的真实 provider 运行使用了哪个可公开模型，大致成本是多少？除现有日志截图外，是否还有可公开、
    已脱敏的完整运行结果？
13. 当前游戏有没有可以公开的录屏或 trace？哪一个场景最能让普通读者看懂 Agent 行为？
14. 你希望先把 Phase 4 提交并补完人工试玩，还是继续做到 Phase 5/6 后再写第一篇正式文章？
15. 这个项目最终要服务哪类个人定位：后端/平台工程、Agent 工程、游戏 AI、全栈，还是“AI 时代的工程治理”？

### E. 公开边界

16. `ericdonno`、`huapple`、QQ 邮箱和 GitHub 仓库是否可以在个人网站公开关联？
17. 是否允许公开提及 Trae、DeepSeek、Codex、Grilling skill、GPT-5.6-sol 和小米 MiMo？
18. 是否希望公开“纯 Vibe Coding”这个说法，还是使用更中性的“Agent 主导实现、人类负责高层控制”？
19. `reports/` 中的多智能体协作研究报告是否要纳入本项目材料？若要，需另行审计来源、引用和隐私。
