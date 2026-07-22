# DungeonMind_Agent_Build_Guide.md 审查报告

## 严重（影响可执行性）

### 1. 第 108 行代码块收尾损坏

```properties
agent.refresh_interval=60
``&```
```

`&` 导致 markdown 渲染异常。应为 ` ``` `。

### 2. server.py 从未接入 graph

阶段二 `server.py` 的 `/decide` 返回硬编码 JSON。阶段四实现了 `planner_node`（异步函数）。但文档从未告诉读者应该在什么时候把 `server.py` 里的硬编码逻辑替换成 `agent_graph.ainvoke(initial_state)`。

读者做完阶段四后，`server.py` 仍在返回 "CHASE"——所有 AI 行为都不会生效。这是一个从"骨架"到"真实"的关键切换点，必须显式标注。

### 3. fallback 策略名 "PATROL" 不在 System Prompt 中

第 709-710 行 planner 的异常 fallback 用 `decision.get("goal", "PATROL")`，但 System Prompt（4.1 节）列出的可选策略里没有 PATROL——CHASE、AMBUSH、FLANK、RETREAT、GUARD、INTERCEPT。LLM 收到一个不在可用列表里的策略名，行为不可预期。应改成 `"CHASE"`，和 except 块的 fallback 保持一致。

---

## 重要（影响完整性）

### 4. 阶段六只给了 `sendAsyncRequest()`，没给修改后的 `think()`

异步版本的关键变化不只是"把 `send` 换成 `sendAsync`"——`think()` 的逻辑结构也要变：需要检查是否有飞行中的请求（避免重复发送）、需要在回调中解析响应而不是同步 return。当前 6.1 节只贴了 `sendAsyncRequest` 函数体，没有展示新的 `think()` 骨架。

### 5. `player_last_move` 定义了但从未使用

AgentState 中有 `player_last_move: str`（2.2 节），协议 JSON 中也传了 `"last_move_direction": "LEFT"`（1.2 节）。但 Perception 摘要、Memory 方向统计（用坐标差值自己算）、System Prompt 中都没用到这个字段。要么删掉，要么在 Perception 或 Memory 里用起来。

---

## 轻微（影响流畅度）

### 6. 阶段二的 server.py 和 graph.py 之间缺一句说明

2.1 节展示 `server.py` 返回硬编码 JSON，2.3 节展示 `graph.py` 定义 workflow stub。但没有一句话说明"阶段二暂时不连接它们，阶段四做完 planner 后再连"——读者会困惑为什么建了两个东西却不接在一起。

### 7. 协议 JSON 字段和 AgentState 字段不完全对齐

1.2 节协议中有 `room_count`、`passable_neighbors`、`player_room_id`，但 2.2 节 AgentState 中没有对应字段。Perception 节点确实不需要它们（只用 `is_in_hallway`），但这是"Java 发了 Python 不用"的刻意设计，应加一句话说明原因——"Java 发送完整上下文，Python 各节点按需取用，AgentState 只定义会被用到的字段"。

### 8. 阶段三验证太弱

"先在 perception_node 里 `print(summary)` 看控制台输出是否合理"——这只是临时调试。真正验证应是：阶段四接上 LLM 之后，观察 reasoning 中是否引用了摘要的信息。当前措辞让读者感觉这步验证可有可无。
