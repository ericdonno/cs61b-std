# DungeonMind 项目进度分析

> 文档角色：面向人的全局项目史志，记录项目走到哪里、已经具备什么、证据是什么、当前还欠什么。
>
> 本次校准：2026-07-19；分支 `ai-enemis`；代码基线 `a97353e`（`floor bug fixed`）。
>
> 事实优先级：当前代码与测试证据 > [PROJECT_INTENT_zh-CN.md](../../../PROJECT_INTENT_zh-CN.md) > [DEVELOPMENT_ROADMAP.md](../../../DEVELOPMENT_ROADMAP.md) > 当前 [PHASE_0_SPEC.md](../../phases/phase-0/PHASE_0_SPEC.md)。`documents/` 中的旧 TDD、Build Guide、brainstorm 和审查文档只保留设计历史价值。

## 1. 项目现在是什么

DungeonMind 起源于 CS61B Project 2（BYOG），当前已经是一个可运行的 2D 程序生成地牢原型。它的长期目标不是单纯“给敌人接一次 LLM”，而是让 Builder 通过真实项目学习现代 Agent 工程，并最终做出更有趣、可观察、可反制的游戏敌人。

目标中的敌人是彼此独立的 Agent。每个敌人只能通过自身视觉、听觉、受伤和收到的消息理解世界；敌人之间的配合必须由呼喊、警报或其他世界内行为产生。不存在一个共享全局知识、同时控制所有敌人的“总大脑”。

当前代码还没有达到这个目标。它已经具备游戏底盘、规则 AI 和 `Brain → Planner → Action` 决策骨架，但尚未具备有限感知、独立信念、Python Agent runtime、LLM tool calling、通信或执行反馈闭环。因此最准确的定位是：

> **游戏原型与传统 AI 基线已形成；Agent 工程处于 Phase 0 的可测量性建设阶段。**

## 2. 当前文档分工

| 文档 | 作用 | 是否驱动当前开发 |
|---|---|---|
| [PROJECT_INTENT_zh-CN.md](../../../PROJECT_INTENT_zh-CN.md) | 产品意图、Agent 身份与边界、MVP 定义 | 是，最高产品基准 |
| [DEVELOPMENT_ROADMAP.md](../../../DEVELOPMENT_ROADMAP.md) | Phase 0–7 的依赖、交付物和验收门槛 | 是 |
| [PHASE_0_SPEC.md](../../phases/phase-0/PHASE_0_SPEC.md) | 当前阶段的详细实施合同 | 是 |
| [PHASE_SPEC_TEMPLATE.md](../../../PHASE_SPEC_TEMPLATE.md) | 后续阶段 Spec 模板 | 是，生成规范 |
| 本文 | 面向人的全局状态、历史与证据 | 记录，不直接授权实现 |
| [实体与性能优化记录](../architecture/entity-and-performance-optimization-notes.md) | 工程优化事项及完成状态 | 专题台账 |
| [早期项目交接笔记](../notes/project-handoff-notes.md) | Builder 的草稿本 | 非正式输入 |
| `documents/` | 旧方案、专题说明、审查和研究材料 | 仅供参考 |

## 3. 已形成的游戏能力

### 3.1 程序化地牢

- 生成 `80 × 30` 的房间与走廊地图。
- 使用房间图和最小生成树保证基本连通，并添加冗余通路。
- 世界由 seed 决定；楼层使用 `seed + "_F" + floor` 派生。
- 每层在远离玩家的房间放置楼梯，进入后生成下一层。
- 敌人基础数量随难度和楼层提高，并带有 seed 决定的随机波动。

主要实现位于 [WorldGenerator.java](../../../byog/WorldGen/WorldGenerator.java)、[RoomGraph.java](../../../byog/WorldGen/RoomGraph.java) 和 [Game.java](../../../byog/Core/Game.java)。

### 3.2 可交互游戏循环

- 菜单、新游戏、难度选择、seed 输入、游玩、暂停和保存退出状态已经接通。
- 玩家使用 WASD 移动，顶部 UI 显示 HP、难度、敌人数量、楼层和攻击蓄力。
- 支持 Easy、Balanced、Hardcore 三档配置；数值从 `config/game.properties` 读取。
- 楼层切换会重新生成地牢、玩家和敌人。
- `Game.playWithInputString(String)` 是遗留 CS61B autograder API，不属于 DungeonMind 的产品或 Agent 路线。

### 3.3 实体、碰撞与存档

- [EntityManager.java](../../../byog/Entity/EntityManager.java) 使用位置索引统一管理实体和碰撞查询。
- 玩家与敌人共享 `Entity` 基类、位置、存活状态和进程内自增 ID。
- AI tick 后统一重建位置索引，并使用 `frameOccupied` 避免同帧多个实体认领同一位置。
- 存档记录 seed、楼层、难度以及玩家/敌人的位置、HP、视野、攻击和移动参数。
- 读档可以恢复当前格式的实体状态并重建当前楼层。

### 3.4 战斗系统

- 玩家攻击采用蓄力机制；蓄满后按空格，对周围八格敌人造成范围伤害。
- 敌人相邻时会选择 `ATTACK`，执行有方向的单体近战攻击。
- 玩家和敌人均有基础伤害与随机浮动；难度配置影响血量、伤害、蓄力速度、敌人速度和数量。
- 已有受击显示、攻击闪光、死亡标记和主循环清理。

该系统已经让项目从“地图上有移动字符”进入可战斗原型，但平衡、死亡占位语义和跨楼层资源延续尚未通过试玩定案。

## 4. 传统 AI 基线

当前每个 `Enemy` 对象分别持有自己的 `RuleBasedBrain`、`Random`、`ActionQueue`、当前策略和 tick 计数器；代码结构上不是一个大脑控制所有敌人。

当前决策链为：

```text
Game tick
  → Enemy.updateAI
  → GameStateSnapshot
  → RuleBasedBrain.think
  → StrategicIntent
  → ClassicalPlanner.translate
  → BFSPathfinder
  → ActionQueue
  → MoveAction / AttackAction
  → EntityManager 与权威游戏状态
```

已有行为：

- 与玩家曼哈顿距离为 1：攻击。
- 玩家在 `sightRange` 内：追击。
- 玩家不在范围内：随机选择附近地板巡逻。
- 策略变化时清空旧动作队列；同策略且队列不足时补充计划。
- BFS 把高层目标转换为确定性移动序列；碰撞和伤害仍由游戏引擎裁决。

这套系统的价值是规则基线、快速反射、确定性执行层和未来 Agent 失败时的 fallback。它不是最终 Agent 成果。

## 5. Agent 能力的真实完成度

| 能力 | 当前状态 | 说明 |
|---|---|---|
| 每敌人独立运行对象 | 部分完成 | 每个敌人有独立 Brain 实例、队列和随机流，但没有正式稳定的 Agent 身份与 checkpoint |
| 高层意图与低层执行分离 | 已有骨架 | `StrategicIntent → ClassicalPlanner → Action` 已存在 |
| 私有视觉/听觉观察 | 未实现 | 当前 `GameStateSnapshot` 暴露完整 `TETile[][]` 和玩家精确位置 |
| 不完整信念与本层工作记忆 | 未实现 | 敌人只根据当前全知输入即时决策 |
| Python Agent runtime | 未实现 | 尚无 Java/Python schema、mailbox 或健康检查 |
| 真实 LLM 推理与 tool calling | 未实现 | 尚无模型调用、工具循环或结构化模型验证 |
| 异步和过期决策处理 | 未实现 | 当前所有规则决策同步执行 |
| 执行反馈与事件重规划 | 部分骨架 | 有策略切换和动作结果，但结果没有形成正式 `ActionOutcome` 返回大脑 |
| 敌人通信与小队协同 | 未实现 | 没有消息、传播距离、延迟、打断或警报机制 |
| Agent tracing 与评估 | 未实现 | 只有普通 Logger，没有 canonical trace 或 A/B 指标 |

特别需要纠正一个旧文档中的判断：当前 `GameStateSnapshot` 不是可直接序列化后交给 Agent 的正式 API。它包含敌人不该知道的全图与玩家权威位置，只能作为 legacy rule-AI 输入，后续必须由私有 `ObservationEnvelope` 取代。

## 6. Roadmap 进度

| 阶段 | 状态 | 当前结论 |
|---|---|---|
| 前置游戏原型 | 已有可运行实现 | 世界生成、交互、楼层、难度、战斗、存档和规则 AI 均已进入代码 |
| Phase 0：固定遭遇与基线 | **Spec 已完成，代码未实施** | 当前应实现 headless 固定场景、canonical trace、JUnit 契约测试和 Completion |
| Phase 1：私有感知与知识边界 | 未开始 | 依赖 Phase 0 的 runner、身份和 trace seam |
| Phase 2：确定性 Java↔Python 桥接 | 未开始 | 先用 fake runtime，不接真实模型 |
| Phase 3：单敌人 Agent Runtime | 未开始 | 接入有状态图运行时、LLM 和 tool calling |
| Phase 4：执行反馈与事件重规划 | 未开始 | 建立持续感知—行动—反馈循环 |
| Phase 5：独立多 Agent 通信 | 未开始 | 通过世界内消息形成可阻断协同 |
| Phase 6：本层记忆与 Agent MVP 验收 | 未开始 | 固定场景 A/B、指标、overlay 和试玩验收 |
| Phase 7：试玩驱动深化 | 未开始 | 战斗、潜行、声音、光照等方向由实际游玩决定 |

因此，不能把已有 `EnemyBrain` 接口或规则决策链表述为“Agent 已经完成”。它们是有价值的前置架构，但 Intent 中定义的 Agent MVP 仍需要 Phase 0–6 的证据链。

## 7. 2026-07-19 验证证据

在代码基线 `a97353e` 上执行：

```powershell
$sources = rg --files -g '*.java'
javac -cp '..\library-sp18\javalib\*' -d out $sources
java -cp 'out;..\library-sp18\javalib\*' org.junit.runner.JUnitCore byog.Core.EnemyCollisionTest
```

结果：

- 全部 Java 源码编译成功，有未检查/不安全操作警告，无编译错误。
- `EnemyCollisionTest` 运行 1 项、失败 1 项。
- 失败发生在 tick 24：`Enemy2 overlapped Player`。
- 触发过程是两个相邻敌人持续攻击，玩家死亡后 `EntityManager.canMoveTo` 允许进入死亡占用者的位置，而测试仍断言敌人永远不能与玩家坐标重叠。

这不是绿色测试基线。Phase 0 已决定先修正测试前提，使它明确验证“活实体不重叠”；尸体是否占格、玩家死亡帧如何处理以及索引覆盖语义仍应作为独立玩法/工程问题记录，不能靠改断言假装已经解决。

## 8. 当前已知问题与风险

### P0：阻碍可信 Agent 开发

1. 没有独立于 GUI、键盘、墙钟和程序地图变化的固定遭遇 runner。
2. 没有结构化 trace，无法证明每个敌人看到了什么、决定了什么、执行结果是什么。
3. 现有碰撞测试失败，尚无可信的绿色回归起点。
4. `GameStateSnapshot` 全知，违反未来 Agent 的有限知识边界。
5. 当前 `Entity.id` 是 JVM 全局静态自增值，未持久化，不能直接承担正式 `agentId` 或 golden trace 身份。

### P1：现有游戏语义和兼容性

1. 死亡实体是否继续占格尚未定义；死亡玩家与敌人移动可能在一帧内产生位置/索引歧义。
2. `ActionResult.DAMAGE` 与 `Enemy.updateAI` 只在 `SUCCESS` 时停止 retry 的语义不一致，需要在后续执行反馈设计中明确。
3. `loadGameState` 注释声称旧存档会 fallback 到 seed 重建，但 `entityStates == null` 时当前代码没有实际重建玩家和敌人。
4. 进入下一层会创建满状态的新玩家；是否保留上一层 HP/资源与 Intent 中“带着消耗后果进入下一层”不一致，需由 Builder 决定并实现。
5. `RuleBasedBrain` 用曼哈顿距离和权威玩家位置判断“看见”，不考虑墙体遮挡；在 Phase 1 前只能视为基线规则而非感知。

### 项目交接风险

父级 `.gitignore` 的 `*` 规则仍忽略根目录 Markdown。这些 Intent、Roadmap、Spec、史志和优化台账目前可能只存在于本机工作区；在跨机器或跨 clone 交接前必须调整忽略规则或显式纳入版本控制。

## 9. 当前下一步

当前唯一正式开发目标是按 [PHASE_0_SPEC.md](../../phases/phase-0/PHASE_0_SPEC.md) 实施 Phase 0：

1. 恢复可信的碰撞测试前提。
2. 为真实 `Enemy` 决策链增加结构化 trace seam。
3. 建立 `baseline-two-guards-v1` 固定 ASCII 遭遇和 headless runner。
4. 锁定 canonical trace、最终状态和 JUnit 契约测试。
5. 生成 `PHASE_0_COMPLETION.md`，记录证据、限制和 Phase 1 输入。

Phase 0 不接 LLM，但它不是推迟 Agent 技术：它建立后续私有感知、跨语言 Agent runtime、通信和 A/B 评估共同依赖的可复现实验底座。

## 10. 维护规则

本文在以下事件发生后更新：

- 一个 Phase 通过 Definition of Done；
- 游戏新增或删除影响整体体验的系统；
- 已知问题被确认、修复或重新定性；
- 项目基准文档或北极星发生变化；
- 新的测试、trace 或试玩证据推翻旧判断。

更新时必须写明日期、代码基线和验证证据；计划不能写成完成，接口存在不能写成 Agent 能力已经成立。
