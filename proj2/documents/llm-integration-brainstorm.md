# 项目接入大模型：灵感与规划

## 1. 当前游戏状态分析

### 1.1 敌人数值（Balanced 难度）

| 属性 | Player | Enemy（普通） |
|------|--------|--------------|
| HP | 100 | 20 |
| 攻击力 | 15 (+0~5) | 10 (+0~3) |
| 移动间隔 | 实时 | 5 tick |
| 视野 | 10 | 7 |
| 数量 | 1 | 3 + (floor-1) + random(0..2) |

- 玩家一刀 15~19 伤害，敌人 20 HP → **1~2 刀一个**
- 敌人一刀 10~12 伤害，玩家 100 HP → 需要打中 10 次
- 玩家有充能攻击（AoE，8 格范围），充能期间敌人只能单点

**结论：单个敌人威胁极低。玩家最优策略是绕开，绕不开就秒掉。敌人数量多是地图填充物，不是核心挑战。**

### 1.2 现有 AI 架构（设计得很好）

```
EnemyBrain 接口
  └── RuleBasedBrain（当前唯一实现）
        └── think(GameStateSnapshot) → StrategicIntent
              └── ClassicalPlanner.translate() → ActionQueue
```

- `StrategicIntent` 已经定义了丰富的策略枚举：CHASE、AMBUSH、GUARD、INTERCEPT、RETREAT...
- `RuleBasedBrain` 只用到了 CHASE / PATROL / ATTACK 三种
- 注释明确写了："作为 LLM 不可用时的保底 AI"——作者**原本就规划了 LLM 大脑**

---

## 2. 为什么接入大模型是有意义的

### 2.1 从游戏体验角度

当前游戏的核心问题是：**敌人没有个性，战斗没有决策张力。**

每一层地牢都是"绕过 5~8 个同质化敌人 → 找到楼梯"。敌人 AI 的行为模式只有"看到你 → 追 / 没看到 → 乱逛"，玩家不需要思考，只需要 WASD。

接入大模型不是为了让所有敌人都变聪明（那不可能，也无聊），而是**创造少数几个"会思考的对手"**，让玩家在遇到它们时紧张起来："这个 Boss 刚才是不是在埋伏我？"

### 2.2 从技术角度

这个项目的 AI 架构**天然适合接入 LLM**：

1. `EnemyBrain` 是接口 → 实现一个 `LLMBrain` 即可，零侵入
2. `StrategicIntent` 的 Goal/Strategy 枚举已经覆盖了高级战术词汇
3. `ClassicalPlanner` + `BFSPathfinder` 已经解决了"怎么走"的问题，LLM 只需要解决"想去哪、想干嘛"
4. `GameStateSnapshot` 就是 LLM prompt 的数据来源

### 2.3 从课程项目角度

CS61B 是数据结构和算法课。接入 LLM 的核心工作不是调 API，而是：
- 设计 LLM 能理解的**结构化 prompt**（把游戏状态编码为自然语言/JSON）
- 设计 LLM 输出的**解析器**（从自然语言/JSON 提取 StrategicIntent）
- 设计 **fallback 机制**（LLM 不可用时降级为 RuleBasedBrain）
- 设计 **性能策略**（不是每 tick 都调，只在关键时刻调）

这些都是软件工程核心能力。

---

## 3. 三个可行方向

### 方向 A：Boss 战 + LLM 战略大脑（推荐）

**核心思路**：每层地牢的最远房间（当前放楼梯的位置）放置一个 Boss。Boss 拥有：
- **高数值**：HP 100~200，攻击 20~30，2~3 种特殊技能
- **阶段机制**：血量降到 50% 进入二阶段，AI 策略切换
- **LLM 大脑**：在 Boss 生成时和阶段切换时调用 LLM，生成该阶段的策略

**LLM 调用时机**（不是每 tick，每阶段一次）：

```
Boss 生成时  → LLM 分析地图结构 + 玩家初始位置 → 决定开局策略
血量 < 50%   → LLM 分析当前局势                     → 决定二阶段策略
血量 < 20%   → LLM 分析是否应该撤退/狂暴            → 决定终局策略
```

**LLM 的输入**（prompt 内容）：
- 地图结构摘要（房间数量、走廊布局、当前所在房间类型）
- 双方数值（HP、距离、技能冷却）
- 历史记录（玩家过去 N 步的移动模式：激进/保守/绕圈）

**LLM 的输出**：
```json
{
  "goal": "AMBUSH",
  "strategy": "AMBUSH",
  "targetRoom": 3,
  "reasoning": "玩家在过去10步中一直沿走廊推进，我提前在走廊拐角埋伏"
}
```

Boss 的特殊能力示例：
- **冲撞**：沿直线冲刺 3 格，撞墙反弹
- **召唤小兵**：在周围生成 2 个普通敌人
- **地裂**：在玩家脚下生成临时障碍
- **回复**：蓄力一回合后回复 30 HP

**优点**：
- 解决"敌人不重要"的核心问题——Boss 战天然需要玩家重视
- LLM 调用次数少（每 Boss 1~3 次），延迟可接受
- 充分利用现有架构（EnemyBrain / StrategicIntent / ClassicalPlanner）
- Boss 的能力本身就有策略深度，LLM 只需决定"什么时候用什么"

**缺点**：
- LLM 是外部依赖，断网就退化（但有 RuleBasedBrain fallback）
- 需要处理 API 调用的异步问题（不能让游戏主循环卡住等 LLM 响应）
- prompt 工程需要反复调试

---

### 方向 B：精英怪系统 + 多 AI 混合

**核心思路**：不增加 Boss，而是在每层的普通敌人中混入 2~3 个精英怪。精英怪：
- 数值翻倍（HP 50~80，攻击 20~25）
- 有自己的 `LLMBrain` 实例，但 LLM 只做"宏观策略选择"，具体执行还是 BFS 寻路
- 精英怪之间可以通过 LLM 协调（"你去左边包抄"）

**LLM 调用时机**：每 30~60 tick 重新评估一次（约每 2~4 秒）

**优点**：
- 不需要改变地图生成逻辑
- 精英怪和普通怪混在一起，增加战术思考

**缺点**：
- 多个 LLM 实例同时调用 → 延迟和成本都高
- 精英怪仍然可以被绕过（没有强制遭遇机制）
- "精英"感不强，玩家可能分不清哪个是精英

---

### 方向 C：训练有"游戏理解"的 AI（离线 RL）

**核心思路**：不接 LLM API，而是用强化学习训练一个轻量级神经网络。
- 用 headless 模式（`playWithInputString`）做批量模拟
- 奖励函数：造成伤害 + 存活时间 - 被动等待
- 训练出的模型替代 RuleBasedBrain

**优点**：
- 完全本地运行，无延迟、无 API 成本
- 可以作为独立的 CS 项目（ML + 游戏 AI）

**缺点**：
- 工作量大——需要搭建训练环境、定义状态/动作空间、调参
- 结果不确定——RL 训练可能收敛到错误的策略
- 与 CS61B 课程目标（数据结构）关系较远
- 游戏的回合制 + 离散动作空间倒是适合 RL，但需要大量工程

---

## 4. 推荐方案：方向 A（Boss + LLM 的战略大脑）

### 4.1 为什么选这个

| 维度 | Boss + LLM | 精英怪 + LLM | 离线 RL |
|------|-----------|-------------|---------|
| 解决"敌人不重要" | 直接解决（Boss 必须打） | 部分解决 | 不解决 |
| 利用现有架构 | 完全兼容 | 兼容 | 需要新架构 |
| 开发周期 | 中等 | 中等 | 长 |
| 可展示性 | 很强 | 一般 | 取决于训练结果 |
| LLM 调用频率 | 极低（每Boss 1~3次） | 中等 | 不需要 |

### 4.2 完整方案

#### 4.2.1 新增类

| 文件 | 说明 |
|------|------|
| `Boss.java` | 继承 Enemy，增加技能系统、阶段机制、专属瓦片 |
| `BossSkill.java` | 技能接口（Execute / getCooldown / isReady） |
| `SkillCharge.java` | 冲撞技能 |
| `SkillSummon.java` | 召唤小兵技能 |
| `SkillRecover.java` | 回血技能 |
| `LLMBrain.java` | 实现 EnemyBrain，调用 LLM API 生成 StrategicIntent |
| `LLMManager.java` | 管理 API key、prompt 模板、响应解析、异步调用 |
| `BossRoomGenerator.java` | 在地图最远房间放置 Boss 房间（特殊装饰） |

#### 4.2.2 修改已有类

| 文件 | 修改 |
|------|------|
| `Game.java` | `nextFloor()` 和 `generateWorld()` 增加 Boss 生成逻辑 |
| `GameStateSnapshot.java` | 扩展字段：增加 HP、已有技能列表、地图结构摘要 |
| `GameConfig.java` | 增加 Boss 数值配置 |
| `Tileset.java` | 增加 BOSS tile、Boss 技能特效 tile |
| `Enemy.java` | `spawnEnemies()` 可以标记某些敌人生成时使用 LLMBrain |

#### 4.2.3 LLM 集成架构

```
游戏主循环（每 tick）
  └── Boss.updateAI()
        └── 检查是否需要 LLM 重新决策
              ├── 是 → LLMManager.requestDecision(snapshot, callback)
              │         异步进行，不阻塞主循环
              │         callback: 拿到 StrategicIntent → 替换 brain 当前策略
              └── 否 → 继续用现有策略（RuleBasedBrain 或上一次 LLM 决策）
```

**关键设计决策**：
- LLM 调用是**异步**的——发送请求后立即返回，主循环继续
- 在等待 LLM 响应期间，Boss 使用上一次的策略或者 fallback RuleBasedBrain
- 如果 LLM 超时（如 3 秒），自动降级为 RuleBasedBrain
- LLM 只做**高层决策**（选 Goal/Strategy/目标位置），底层的路径规划仍然由 ClassicalPlanner + BFS 完成

#### 4.2.4 Boss 阶段机制

```
Phase 1 (100% ~ 50% HP)
  - 策略偏进攻：CHASE、INTERCEPT
  - 可用技能：冲撞、普攻
  - LLM 决定开局策略

Phase 2 (50% ~ 20% HP)
  - 策略混合：AMBUSH、召唤小兵
  - 可用技能：冲撞、召唤、普攻
  - LLM 根据战况重新决策

Phase 3 (20% ~ 0% HP)
  - 策略偏防守/狂暴：RETREAT + 回血、激进 ATTACK
  - 可用技能：回血、冲撞、普攻
  - LLM 最终决策（赌一把还是苟住）
```

#### 4.2.5 LLM Prompt 设计（示意）

```
你是一个地牢 Boss 的 AI 大脑。根据以下战况，选择最佳策略。

## 地图结构
- 房间数量: 12
- Boss 所在房间: 8x6 矩形
- 走廊数量: 4（通向其他房间）

## Boss 状态
- HP: 80/150 (53%)
- 位置: (45, 18)
- 可用技能: 冲撞(就绪), 召唤(冷却中 3 tick), 回血(就绪)

## 玩家状态
- HP: 67/100
- 位置: (42, 17)（距离你 4 格）
- 最近 10 步行为: 持续逼近你

请选择策略：
- CHASE: 追击玩家
- AMBUSH: 撤退到走廊拐角埋伏
- RETREAT: 撤退并使用回血技能
- ATTACK: 如果相邻则攻击

输出 JSON: {"goal": "CHASE", "targetX": 42, "targetY": 17, "skill": "SKILL_CHARGE", "reasoning": "..."}
```

### 4.3 实施步骤

1. **扩展数据层**：给 GameStateSnapshot 加 HP、技能、地图摘要字段
2. **实现 LLMManager**：封装 HTTP 调用、prompt 模板、JSON 解析、超时/fallback
3. **实现 LLMBrain**：实现 EnemyBrain，内部持有 LLMManager + RuleBasedBrain fallback
4. **实现 Boss 技能系统**：BossSkill 接口 + 3 个技能实现
5. **实现 Boss 类**：继承 Enemy，阶段管理、技能调用、LLM 大脑
6. **修改 Game 生成逻辑**：在楼梯房间放置 Boss
7. **添加视觉效果**：Boss tile、技能动画
8. **调优**：反复测试 prompt、调整 Boss 数值、确保难度曲线合理

---

## 5. 目前方案：少而精的智能敌人集群

> 这是对方向 A/B 的反思后产生的折中方案。不引入 Boss 的重度系统，也不像精英怪那样模糊——核心是 **"让每一个敌人都值得认真对待"**。

### 5.1 核心思路

当前敌人的问题可以拆成三个独立维度来解：

| 维度 | 当前状态 | 目标 |
|------|---------|------|
| 数量 | 3 + (floor-1) + random(0..2)，每层 5~10 个 | 固定 2~3 个，每层 |
| 视野 | 7 格，但穿墙——隔着墙也能"看到"玩家 | 12~15 格，**墙挡视线**——玩家可以利用掩体 |
| 智力 | RuleBasedBrain：看到就追，没看到就逛 | LLM：AMBUSH / FLANK / INTERCEPT / RETREAT，敌人间协作 |

三个维度一起改，形成闭环：
- 数量少了 → 每个敌人必须更强才能保持挑战 → 视野和智力补上
- 视野有阻挡 → 玩家可以从敌人背后绕过去（战术深度）→ 但敌人数少+视野大，绕路本身有风险
- LLM 驱动 → 策略多样，不再"追或逛"二选一 → 敌人间交换信息实现包抄
- 更多精妙的决策层设计以及实现

### 5.2 视线阻挡算法

**为什么这个很重要**：当前 `RuleBasedBrain` 用曼哈顿距离判断"是否看到玩家"，隔着 3 层墙也算"看到"。这意味着玩家永远没法躲——你进房间的一瞬间，所有敌人全知道了。加上墙挡视线后，玩家可以利用走廊拐角、房间柱子做掩护，潜行绕过敌人成为可行选项。

**视线算法选择**：Bresenham 直线算法。从敌人位置到玩家位置画一条线，逐格检查——线上的每一格如果是墙，视线就被阻挡。

具体做法：在 `RuleBasedBrain` 中增加一个 `hasLineOfSight(world, enemyPos, playerPos)` 静态方法。Bresenham 的伪代码：

```
hasLineOfSight(world, x0, y0, x1, y1):
    dx = |x1 - x0|, dy = |y1 - y0|
    sx = x0 < x1 ? 1 : -1
    sy = y0 < y1 ? 1 : -1
    err = dx - dy

    loop:
        if world[x][y] 是墙 AND (x,y) 不等于终点:
            return false   // 视线被墙挡住
        if (x,y) == (x1,y1):
            return true    // 到达终点，视线通畅
        e2 = 2 * err
        if e2 > -dy: err -= dy; x += sx
        if e2 <  dx: err += dx; y += sy
```

把这个方法加到 `GameStateSnapshot` 的一个新字段 `boolean playerVisible` 中。Python Agent 拿到这个字段后，可以区分"我知道玩家存在但看不到"和"我看到玩家了"两种情况——前者适合 AMBUSH/INTERCEPT（预判），后者适合 CHASE/ATTACK（直接响应）。

### 5.3 敌人间信息交换

当前每个敌人独立决策，互不知晓对方的存在。信息交换让敌人能协作：

**交换什么**：每个敌人维护一个"已知信息"结构——`lastKnownPlayerPosition`（最后看到玩家的位置）和 `lastSeenTick`（什么时候看到的）。当两个敌人在一定范围（如 10 格）内时，交换彼此的 `lastKnownPlayerPosition`。

**怎么实现**：不需要复杂的通信协议。在 `Game.java` 的 AI tick 循环中，先遍历所有敌人收集它们各自的 `lastKnownPlayerPosition`，再让每个敌人决定"我有没有比同伴更新的信息"。如果有同伴的信息更新，就更新自己的追踪目标。

Java 端在构造每个敌人的 `GameStateSnapshot` 时，多传一个字段：`teammate_info`——包含"最近的同伴是否看到玩家、同伴在哪、同伴在追哪个方向"。Python Agent 在 prompt 中利用这个信息做协作决策。

**协作策略示例**：
- 敌人 A 看到玩家在房间 #5，敌人 B 在房间 #3 → B 提前去 #5 和 #3 之间的走廊堵截（INTERCEPT）
- 两个敌人都在同一房间 → 一前一后包夹，一个 CHASE 正面吸引，一个 FLANK 绕侧面
- 一个敌人 HP 低 → 发信号给同伴："你上，我撤" → RETREAT + 同伴 CHASE

### 5.4 LLM 调用策略

每层 2~3 个敌人，每个都在一定条件下调 LLM。调用频率和 Phase 1 的 `RemoteBrain` 一致：

- 定时刷新：每 60 tick
- 玩家刚进入视野（`playerVisible` 从 false 变 true）
- 敌人 HP 跌破 50% / 20% 阈值
- 收到同伴的新信息（队友刚看到玩家）

**为什么不是每 tick 调**：每 tick 调 LLM 成本爆炸且没必要。大部分时间，一个 BFS 寻路即可执行已有的策略。LLM 只在"需要重新思考"的时刻介入——这类似人类的"下意识执行 vs 有意识决策"。

### 5.5 敌人数量与数值调整

减少数量但强化个体，需要重新设计数值：

| 属性 | 当前普通敌人 | 新方案 |
|------|------------|--------|
| 每层数量 | 5~10 | 2~3 |
| HP | 20 | 40~60 |
| 攻击力 | 10 (+0~3) | 12~18 (+0~5) |
| 移动间隔 | 5 tick | 4 tick（稍快） |
| 视野 | 7（穿墙） | 12（墙挡） |
| AI | RuleBasedBrain | RemoteBrain（LLM）|

**设计意图**：数量减少到原来的 1/3~1/4，个体强度翻 2~3 倍。玩家打一个敌人需要 3~4 刀而不是 1~2 刀，与之周旋的时间足够 LLM 做出有意义的决策。同时视野扩大+有阻挡意味着玩家不能像现在这样"看都不看就冲"。

### 5.6 与之前方案的关系

| 维度 | 方向 A（Boss + LLM） | 方向 B（精英怪） | **目前方案** |
|------|---------------------|-----------------|-------------|
| 新类数量 | 多（Boss、技能系统、BossRoom） | 少 | 少（主要是 RemoteBrain + GameStateSnapshot 扩展） |
| 地图生成改动 | 大（Boss 房间） | 无 | 无 |
| LLM 调用频率 | 极低（每 Boss 1~3 次） | 中等 | 中等（同 RemoteBrain 设计） |
| 玩家战术深度 | Boss 战本身有深度 | 不够明显 | 潜行 + 掩体 + 包抄都有意义 |
| 可展示性 | Boss 战很壮观 | 一般 | "敌人有视野"玩家自己就能发现 |
| 对齐当前 Build Guide | 需要额外阶段 | 部分对齐 | **完全对齐**——就是 Phase 1~6 的自然演化 |

目前方案不是在否定方向 A/B，而是在它们的基础上**做减法**——砍掉 Boss 的重度系统、砍掉精英怪的模糊定位，只保留一个核心改动：**让每个普通敌人都有存在感**。这恰好也是 [DungeonMind_Agent_Build_Guide.md](./DungeonMind_Agent_Build_Guide.md) 的 Phase 1~6 所做的事——RemoteBrain 替代 RuleBasedBrain，GameStateSnapshot 扩展，记忆系统。只需要在 Phase 1 和 Phase 3 中额外加入视线阻挡算法即可。

---

## 6. 替代/补充思路

### 6.1 如果不接 LLM API，可以做"伪 LLM"

预置 10~20 种手写策略模板，Boss 根据战况**随机选择**一个模板（带权重）。虽然没有真正的"智能"，但策略多样性本身就能带来新鲜感。

### 6.2 如果接 LLM API，但不想做 Boss

可以让 LLM 充当"关卡导演"，在每层生成时：
- 分析玩家历史行为（激进/保守）
- 调整敌人数量、类型、位置
- 给敌人不同的初始策略（有些 AMBUSH，有些 GUARD 房间入口）

---

## 7. 总结

| 问题 | 答案 |
|------|------|
| LLM 操控所有敌人有意义吗？ | 没有。普通敌人数量多、数值弱，LLM 的智能浪费在炮灰上。 |
| Boss + LLM 有意义吗？ | 有。Boss 是"必须重视"的敌人，LLM 让它变成一个真正会思考的对手。 |
| 训练 AI 有意义吗？ | 有，但工作量大、与当前架构不搭、偏离课程目标。 |
| 最推荐的方案 | Boss + LLM 战略大脑，架构零侵入，可展示性强，开发量可控。 |
