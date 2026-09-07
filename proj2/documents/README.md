# DungeonMind 文档导航

本目录保存已关闭阶段的交付文档，以及仍有历史或专题参考价值、但不再直接指导当前开发的资料。
这里的文件没有被删除；移动和重命名只用于明确文档职责与权威级别。

用于个人网站、博客和简历讨论的材料分为两类：

- [`PROJECT_WRITING_MATERIALS.md`](PROJECT_WRITING_MATERIALS.md)：项目事实、证据、公开边界和待确认问题；
- [`VIBE_CODING_ENGINEERING_ARTICLE_BRIEF.md`](writing/VIBE_CODING_ENGINEERING_ARTICLE_BRIEF.md)：Vibe Coding 工程化文章的读者、论点、结构和证据分配。
- [`VIBE_CODING_ENGINEERING_FIRST_DRAFT.md`](writing/VIBE_CODING_ENGINEERING_FIRST_DRAFT.md)：依据事实底稿和文章提纲形成的第一稿，尚未经过发布前事实补全与公开信息审计。

它们是写作输入，不属于项目运行时或 Phase 实施契约；叙事提纲不得反向覆盖事实底稿。

## 当前开发入口

以下文档仍位于仓库根目录，是继续开发时的优先入口：

1. [`PROJECT_INTENT_zh-CN.md`](../PROJECT_INTENT_zh-CN.md)：产品目标与玩法边界。
2. [`DEVELOPMENT_ROADMAP.md`](../DEVELOPMENT_ROADMAP.md)：阶段依赖、系统不变量与验收出口。
3. [`AI_TICK_ARCHITECTURE.md`](../AI_TICK_ARCHITECTURE.md)：游戏循环与异步 Agent 控制架构。
4. [`PHASE_4_SPEC.md`](../PHASE_4_SPEC.md)、[`PHASE_4_BUILD_GUIDE.md`](../PHASE_4_BUILD_GUIDE.md) 与
   [`PHASE_4_COMPLETION.md`](../PHASE_4_COMPLETION.md)：当前交接基线。自动化实现已完成，人工
   `PLAY-01` 尚待补验，Phase 5 尚未开工。
5. [`agentarchitecture.md`](../agentarchitecture.md)、[`session.md`](../session.md)、
   [`socket.md`](../socket.md)、[`testsystem.md`](../testsystem.md) 与
   [`llmprovider.md`](../llmprovider.md)：外部 Agent runtime 及其关键模块说明。
6. [`AGENTS.md`](../AGENTS.md)：仓库执行约束；[`PHASE_SPEC_TEMPLATE.md`](../PHASE_SPEC_TEMPLATE.md)：
   后续阶段规范模板。

英文版产品意图保留在 [`PROJECT_INTENT.md`](../PROJECT_INTENT.md)，但发生冲突时以中文产品意图和
Roadmap 中声明的优先级为准。

## 已关闭阶段

| 阶段 | Spec | Build Guide | Completion | 补充说明 |
|------|------|-------------|------------|----------|
| Phase 0 | [`PHASE_0_SPEC.md`](phases/phase-0/PHASE_0_SPEC.md) | — | [`PHASE_0_COMPLETION.md`](phases/phase-0/PHASE_0_COMPLETION.md) | 固定遭遇与确定性基线 |
| Phase 1 | [`PHASE_1_SPEC.md`](phases/phase-1/PHASE_1_SPEC.md) | [`PHASE_1_BUILD_GUIDE.md`](phases/phase-1/PHASE_1_BUILD_GUIDE.md) | [`PHASE_1_COMPLETION.md`](phases/phase-1/PHASE_1_COMPLETION.md) | 私有感知与知识边界 |
| Phase 1.5 | [`PHASE_1DOT5_SPEC.md`](phases/phase-1.5/PHASE_1DOT5_SPEC.md) | — | [`PHASE_1DOT5_COMPLETION.md`](phases/phase-1.5/PHASE_1DOT5_COMPLETION.md) | 敌人视野可视化增强 |
| Phase 2 | [`PHASE_2_SPEC.md`](phases/phase-2/PHASE_2_SPEC.md) | [`PHASE_2_BUILD_GUIDE.md`](phases/phase-2/PHASE_2_BUILD_GUIDE.md) | [`PHASE_2_COMPLETION.md`](phases/phase-2/PHASE_2_COMPLETION.md) | 事件桥接与双速执行骨架 |
| Phase 2.5 | [`PHASE_2DOT5_SPEC.md`](phases/phase-2.5/PHASE_2DOT5_SPEC.md) | [`PHASE_2DOT5_BUILD_GUIDE.md`](phases/phase-2.5/PHASE_2DOT5_BUILD_GUIDE.md) | [`PHASE_2DOT5_COMPLETION.md`](phases/phase-2.5/PHASE_2DOT5_COMPLETION.md) | 持久世界状态与可读感知 |
| Phase 3 | [`PHASE_3_SPEC.md`](phases/phase-3/PHASE_3_SPEC.md) | [`PHASE_3_BUILD_GUIDE.md`](phases/phase-3/PHASE_3_BUILD_GUIDE.md) | [`PHASE_3_COMPLETION.md`](phases/phase-3/PHASE_3_COMPLETION.md) | 另有一份较早的[实施说明](phases/phase-3/phase-3-implementation-notes.md) |

Completion 记录的是当时实际完成和验证过的事实；不能仅凭 Spec、Guide 或文件名判断功能已经实现。

## 历史资料

`archive/` 中的内容可能使用旧包名、旧接口、旧路径或已经被后续阶段取代的方案。阅读时应结合当前代码和
Completion，不得用它覆盖 Intent、Roadmap、当前阶段文档或当前实现。

### 早期设计

- [早期 DungeonMind 构建指南](archive/designs/early-dungeonmind-build-guide.md)
- [早期技术设计文档](archive/designs/early-technical-design.md)
- [早期 Agent runtime 架构分析](archive/designs/agent-runtime-design-analysis.md)
- [历史 Agent 构建指南](archive/designs/legacy-agent-build-guide.md)
- [GPT Agent 构想](archive/designs/gpt-agent-idea.md)
- [LLM 接入 brainstorm](archive/designs/llm-integration-brainstorm.md)

### 架构与功能说明

- [包结构重构说明](archive/architecture/package-restructure-note.md)
- [实体与性能优化记录](archive/architecture/entity-and-performance-optimization-notes.md)
- [存档与读档说明](archive/feature-notes/save-load-explanation.md)
- [战斗系统玩法说明](archive/feature-notes/combat-system-overview-zh.md)
- [战斗系统技术说明](archive/feature-notes/combat-system-technical-notes-zh.md)
- [实体移动速率与游戏循环](archive/feature-notes/entity-movement-and-game-loop.md)
- [早期项目代码参考](archive/reference/legacy-project-documentation.md)

### 历史需求与课程目标

- [血包玩法需求](archive/requirements/health-pack-requirements.md)
- [课程 Ambition Scores 说明](archive/requirements/course-ambition-scores.md)

### 评审、分析与交接

- [敌人碰撞 Bug 报告](archive/reviews/enemy-collision-bug-report.md)
- [早期项目分析](archive/reviews/legacy-project-analysis.md)
- [项目进度分析](archive/reviews/project-progress-analysis-zh.md)
- [Agent 构建指南评审](archive/reviews/agent-build-guide-review.md)
- [架构修订建议](archive/reviews/architecture-revision-suggestions.md)
- [Stage 1 评审与后续建议](archive/reviews/stage-1-review-and-next-steps.md)
- [Stage 2 评审与后续建议](archive/reviews/stage-2-review-and-next-steps.md)
- [早期项目交接笔记](archive/notes/project-handoff-notes.md)

## Agent 工具自有资料

`.trae/documents/` 保存 Trae 生成的细粒度工作计划和修复记录；`.trae/rules/` 与 `.trae/skills/` 是工具配置。
它们不属于项目正式文档入口，本轮不移动、不删除，也不按当前项目事实维护。需要追溯早期 Vibe Coding
过程时，可以把它们当作原始工作记录查看。

## 后续维护规则

- 根目录只放当前开发必须直接读取的文档。
- 阶段关闭后，将该阶段的 Spec、Build Guide 和 Completion 一起移入 `phases/phase-*`，并同步更新入口链接。
- 已被取代但仍有追溯价值的资料移入 `archive/`；不静默删除历史文档。
- 新增文档时优先更新本导航，避免重新形成依赖文件名猜用途的扁平目录。
