# 架构回顾与后续优化建议

> 上次审查日期：已对照当前代码库（`byog.Core`）逐条核验。

---

# 1. LLM 输出 goal 而非 action 序列 ✅ 已解决

**原问题**：担心 LLM 直接输出 MoveAction 序列，与 BFS 寻路冲突。

**当前状态**：架构已经就是建议的形态。

```
EnemyBrain.think(GameStateSnapshot) → StrategicIntent   ← LLM 只输出 goal + strategy + target
        ↓
ClassicalPlanner.translate()        → List<Action>      ← BFS 寻路翻译为 MoveAction
        ↓
ActionQueue                                              ← 逐 tick 消费动作
```

- [EnemyBrain.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/EnemyBrain.java)：接口返回 `StrategicIntent`，不是 Action 列表
- [ClassicalPlanner.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/ClassicalPlanner.java)：负责 BFS 寻路 + 生成 MoveAction
- [StrategicIntent.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/StrategicIntent.java)：已有 goal、strategy、targetPosition、confidence、targetRoom 字段

**结论**：这一层的理论闭环已经完成。实现 `LLMBrain` 时只需让 LLM 输出 JSON 映射到 `StrategicIntent` 各字段即可。

---

# 2. Confidence 作为启发式参数 📝 待实现 LLMBrain 时注意

`StrategicIntent` 已经有 `confidence` 字段（double，默认 0.5）。当前 `RuleBasedBrain` 不使用它。

实现 `LLMBrain` 时的使用建议：
- 让 LLM 输出 confidence，但**不把它当作概率**，而是作为**启发式控制参数**
- 例如：confidence 高 → LLM 决策缓存更久；confidence 低 → 更早触发 refresh 或 fallback
- 不要声称"模型确定性"，避免 reviewer 追问"你怎么验证这个 confidence"

---

# 3. GameStateSnapshot 扩展 📝 待实现

当前 [GameStateSnapshot.java](file:///d:/Courses/cs61b/cs61b-std/proj2/byog/Core/GameStateSnapshot.java) 只有4个字段：

```java
TETile[][] world;
Position playerPosition;
Position enemyPosition;
int enemyId;
```

LLM 要做出好决策，需要更多上下文。建议实现时扩展：

```java
// 战力
int enemyHp, enemyMaxHp;
int playerHp;
int distanceToPlayer;

// 环境
boolean isInHallway;       // 敌人在走廊还是房间
int passableNeighbors;     // 周围可通行格数

// 行为历史
Direction lastPlayerMoveDirection;
boolean playerApproaching; // 玩家最近是否在逼近
```

注意：`GameStateSnapshot` 是 **AI thread 和 Game thread 的边界对象**，保持其不可变性很重要——构造后不修改，避免并发问题。

---

# 4. PromptBuilder 独立成类 📝 待实现

实现 `LLMBrain` 时，建议把 prompt 构建逻辑抽到独立类：

```
byog/Core/
  PromptBuilder.java   -- 将 GameStateSnapshot 转为 LLM prompt 字符串
```

分层结构：

```
PromptBuilder.build(snapshot)
  ├── System:  "You are a dungeon guardian. Choose the best tactical strategy."
  ├── World:   地图结构摘要（房间数、走廊、当前位置类型）
  ├── Combat:  HP对比、距离、攻击力
  ├── Memory:  玩家行为模式（逼近/绕圈/逃跑）
  └── Output:  JSON schema 约束
```

原因：prompt 是 LLM 效果的核心变量，独立成类方便反复测试和调优。

---

# 5. Evaluation 指标 🔮 锦上添花

如果写报告或做 demo，可以加入以下指标：

| 指标 | 含义 |
|------|------|
| Planning Success Rate | LLM 决策被成功执行的比例（不被 fallback 覆盖） |
| API Latency (p50/p95) | LLM 响应延迟分布 |
| Fallback Rate | 降级到 RuleBasedBrain 的比例 |
| Strategy Diversity | 使用了多少种不同策略（不只是 CHASE/PATROL） |

不是必须的代码改动，但对展示 AI Agent 的"智能"很有说服力。

---

# 总结

| 建议 | 状态 |
|------|------|
| 1. LLM 输出 goal 而非 action | **已实现** — EnemyBrain → StrategicIntent → ClassicalPlanner 链路完整 |
| 2. Confidence 作为启发式参数 | 待实现 LLMBrain 时注意措辞 |
| 3. 扩展 GameStateSnapshot | 待实现，LLM 决策质量的关键输入 |
| 4. PromptBuilder 独立类 | 待实现，决定 LLM 效果的核心模块 |
| 5. Evaluation 指标 | 可选，适合报告/demo |

你当前的架构已经是一个完整的层级式 AI Agent 骨架：

```
LLM (战略层) / RuleBasedBrain (保底层)
        ↓
   StrategicIntent
        ↓
ClassicalPlanner + BFS (规划层)
        ↓
   ActionQueue (执行层)
        ↓
 MoveAction / AttackAction (原语层)
```

剩下的就是把 `LLMBrain`、`PromptBuilder`、`GameStateSnapshot` 扩展这三块填进去。
