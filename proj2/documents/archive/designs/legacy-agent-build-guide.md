# LLMBrain Agent 构建指南

> 本文档是 LLMBrain 的分阶段实施手册。和原 [DungeonMind_Build_Guide.md](early-dungeonmind-build-guide.md) 不同，**这里不会用纯 Java 实现 LLM 大脑**。我们将把 AI 层独立成一个 Python Agent Runtime，Java 只负责游戏模拟。读完本文后，你将建成一个：**Java 游戏引擎 + Python LangGraph Agent** 的混合架构，敌人通过 Tool Calling 与游戏世界交互。
>
> **阅读建议**：先读"原构建指南对照"了解哪些旧东西继续用、哪些废弃，再读"架构概览"建立全局认知，然后按阶段实施。

---

## 目录

- [原构建指南对照](#原构建指南对照)
- [架构概览](#架构概览)
- [前置准备](#前置准备)
- [阶段一：Game State API（Java 暴露游戏状态）](#阶段一game-state-api)
- [阶段二：Python Agent Runtime 骨架](#阶段二python-agent-runtime-骨架)
- [阶段三：Perception Agent（感知节点）](#阶段三perception-agent)
- [阶段四：Planning Agent + Tool Calling](#阶段四planning-agent--tool-calling)
- [阶段五：Memory System（向量记忆）](#阶段五memory-system)
- [阶段六：LLMBrain 桥接（Java ↔ Python 通信）](#阶段六llmbrain-桥接)
- [附录 A：常见陷阱](#附录-a常见陷阱)
- [附录 B：验收清单](#附录-b验收清单)

---

## 原构建指南对照

原 `DungeonMind_Build_Guide.md` 分为五个阶段。以下是各阶段的命运：

| 原阶段 | 状态 | 说明 |
|--------|------|------|
| 阶段一：基础敌人 | **已完工，继续用** | Enemy.java、ActionQueue、Game.java 集成全部完成且超出了原设计（多了 AttackAction、EntityManager、Difficulty 等） |
| 阶段二：传统 AI | **已完工，继续用** | RuleBasedBrain、BFSPathfinder、ClassicalPlanner、StrategicIntent 全部就位。**RuleBasedBrain 将作为 Python Agent 不可用时的降级兜底** |
| 阶段三：感知层 | **废弃，重做** | 原计划的 LocalMapRenderer + WorldDescription（Java 中渲染文本地图给 LLM）废弃。感知逻辑全部移到 Python Agent 的 Perception Node |
| 阶段四：LLM 集成 | **废弃，重做** | 原计划的 LLMClient + LLMBrain + PromptBuilder（在 Java 中调 OpenAI API）全部废弃。LLM 调用移到 Python Agent Runtime，用 LangGraph 编排 |
| 阶段五：高级特性 | **废弃，重做** | 原计划的 EnemyMemory（环形缓冲 + 方向统计）过于简单。替换为 Python 侧的向量数据库记忆系统（Chroma） |

**你需要保留的 Java 代码**：Enemy.java、ActionQueue、RuleBasedBrain、BFSPathfinder、ClassicalPlanner、StrategicIntent、GameStateSnapshot（需升级）。其他全部不动。

**一句话总结**：Java 侧已经做好了"世界模拟器"（阶段一、二），现在要把"智能体大脑"从 Java 中抽出来，用 Python Agent 技术重新实现。

---

## 架构概览

在动手写代码之前，先理解"为什么是新架构"以及"新架构长什么样"。

### 旧架构的问题

旧架构的本质是"Java 游戏里嵌一个 HTTP 调用"：

```
Java Game
    |
Enemy.updateAI()
    |
LLMBrain.think()  ← 在 Java 里调 OpenAI API，等 1~5 秒
    |
解析 JSON → StrategicIntent
    |
ClassicalPlanner (BFS)
```

这有两个深层问题：
1. **Java 生态不适合做 Agent**。没有 workflow 编排框架、没有向量数据库、没有 tool calling 的成熟库。你想加记忆系统？自己写。你想加多步推理？自己写状态机。
2. **工业界不这么做**。真正的游戏 AI 系统（如 AI Dungeon、斯坦福的 Generative Agents）都是 Python Agent + 游戏引擎分离。学到的技能可迁移。

### 新架构

```
┌─── Java Game Engine（已有）────────────────────────┐
│                                                    │
│  Game.java (60 FPS 主循环)                          │
│       │                                            │
│       v                                            │
│  Enemy.updateAI()                                  │
│       │                                            │
│       ├── RuleBasedBrain (降级兜底, 已有)            │
│       │                                            │
│       └── LLMBrain (新建, 薄层包装)                  │
│               │                                    │
│               │  HTTP POST /api/decide             │
│               │  Body: {game_state JSON}           │
│               │  ← {action_plan JSON}              │
│               │                                    │
│       v                                            │
│  ClassicalPlanner + ActionQueue → 逐帧执行           │
│                                                    │
└────────────────────┬───────────────────────────────┘
                     │ REST (JSON over HTTP)
                     ▼
┌─── Python Agent Runtime（新建）─────────────────────┐
│                                                    │
│  FastAPI Server (localhost:8000)                    │
│       │                                            │
│       v                                            │
│  LangGraph Workflow                                │
│       │                                            │
│       ├── Perception Node                          │
│       │   "我看到什么？"                             │
│       │   输入: game_state JSON                     │
│       │   输出: 结构化场景理解                        │
│       │                                            │
│       ├── Reasoning Node                           │
│       │   "这意味着什么？"                           │
│       │   查询记忆 → 分析玩家意图                     │
│       │                                            │
│       ├── Planning Node                            │
│       │   "我要做什么？"                             │
│       │   LLM 决策 + Tool Calling                   │
│       │                                            │
│       └── Action Node                              │
│           "翻译成动作序列"                           │
│           输出: List[Action] JSON                   │
│                                                    │
│  Memory System (Chroma)                            │
│  ┌─────────────────────────┐                       │
│  │ GameEvent → Embedding →  │                       │
│  │ Vector DB → Retrieval    │                       │
│  └─────────────────────────┘                       │
│                                                    │
└────────────────────────────────────────────────────┘
```

### 数据流向（一句话）

Java 每 N 帧打包 GameState JSON → POST 到 Python Agent → LangGraph 流水线（感知 → 推理 → 规划 → 动作生成）→ 返回 Action Plan JSON → Java 入队逐帧执行。

### 为什么各层放在各自语言

| 功能 | 放在哪 | 原因 |
|------|--------|------|
| 世界模拟（渲染、碰撞、移动） | Java | 已实现，性能关键，不需要改 |
| BFS 寻路 | Java | 算法简单，和地图数据紧耦合，不需要 LLM |
| 地图渲染为文本 | Java | 需要访问 TETile[][]，Java 侧做更方便 |
| 战略决策（"拦截"还是"追击"） | Python | 需要 LLM 推理，Python 生态完善 |
| 玩家行为记忆 | Python | 向量数据库（Chroma）在 Python 生态 |
| Tool Calling 编排 | Python | LangGraph 提供了成熟的 tool calling 框架 |
| 动作执行 | Java | ActionQueue 已有，直接复用 |

### 核心设计理念

**1. "思考频率 ≠ 渲染帧率"**

Java 游戏以 60 FPS 渲染画面，但 Python Agent 只需要每 0.5~1 秒调用一次。ActionQueue（已有）存储 5~8 个预生成动作，吸收这个时间差。Agent 在后台思考时画面不卡。这是你在阶段二已经打下的基础，现在继续用它。

**2. "Agent 不直接操作世界，通过 Tool Calling 表达意图"**

Python Agent **不能**直接修改 Java 游戏状态。它通过调用工具函数（如 `look_map()` 读取视野、`find_path()` 获取路径）来理解世界，然后产出一个 StrategicIntent JSON。Java 侧 ClassicalPlanner 负责把意图翻译成具体动作并执行。这是现代 Agent 的标准范式——Agent 有"手脚"（tools），但"手脚"的执行权在环境（Java）手中。

---

## 前置准备

### 你需要新建/修改的文件

| 阶段 | 新建 | 修改 |
|------|------|------|
| 一 | — | `GameStateSnapshot.java`（增加 JSON 序列化、新增字段）、`Game.java`（增加 `/api/decide` 的 server 或改用 JSON 导出） |
| 二 | `agent/server.py`、`agent/graph.py`、`agent/state.py`、`agent/requirements.txt` | — |
| 三 | `agent/perception.py` | — |
| 四 | `agent/planner.py`、`agent/tools.py` | — |
| 五 | `agent/memory.py` | — |
| 六 | `byog/Core/LLMBrain.java`、`byog/Core/AgentClient.java` | `Game.java`（Agent Server 生命周期）、`Enemy.java`（支持 LLMBrain） |

### 三个需要特别注意的坑

1. **JSON 序列化中的循环引用**。`TETile[][]` 不能直接序列化为 JSON——它包含 `TETile` 对象，内部有 `Color` 等复杂字段。正确做法：在 `GameStateSnapshot` 中新增 `toJson()` 方法，手动把 `TETile[][]` 压缩为二维字符串数组（`'#'`=墙，`'.'`=地面，`' '`=空地），然后再序列化。Python 端解析回来即可。

2. **Java 和 Python 进程的生命周期管理**。Python Agent Server 是一个独立进程，运行在 `localhost:8000`。你需要保证：Java 启动时自动拉起 Python 进程，Java 退出时自动关闭它。如果手动管理，容易端口占用或僵尸进程。阶段六会详细讲怎么做。

3. **LLM API 调用的延迟不可控**。OpenAI API 响应时间在 1~30 秒。Java 侧对 Python Agent 的 HTTP 调用 **绝对不能阻塞游戏主循环**。用 `CompletableFuture` 异步调用，超时 10 秒后自动 fallback 到 RuleBasedBrain。

---

## 阶段一：Game State API

> **一句话目标**：`GameStateSnapshot` 能输出一份结构化的 JSON，包含地图、玩家位置、敌人状态等所有 Python Agent 需要的信息。

**解释**：Java 和 Python 之间需要一种"通用语言"来交换信息。JSON 是最简单的选择——Java 手动拼接，Python 用 `json.loads()` 解析。这一阶段不需要引入任何 Python 代码，只改造 Java 侧的数据出口。

### 1.1 升级 GameStateSnapshot

**做什么**：在现有 `GameStateSnapshot`（4 字段）基础上增加更多字段，并添加 `toJson()` 方法。

**解释**：Python Agent 做决策需要比 RuleBasedBrain 更多的上下文——敌人血量、玩家血量、当前回合数等。这些信息现在加入快照，一步到位。

**新增字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `enemyHp` | int | 敌人当前血量（决定是否撤退） |
| `playerHp` | int | 玩家当前血量（决定是否追击残血） |
| `enemySightRange` | int | 敌人视野（决定感知半径） |
| `enemyAttackDamage` | int | 敌人攻击力（已有的 GameConfig 字段） |
| `turnNumber` | int | 当前回合数（让 LLM 感知时间流逝） |
| `timestamp` | long | 快照创建时间戳 |

保留现有的 4 参数简化构造函数给 RuleBasedBrain 使用。新增完整构造函数给 Python Agent 使用。

**验证**：编译通过，新字段的 getter 方法返回正确值。

### 1.2 添加 toJson() 方法

**做什么**：在 `GameStateSnapshot` 中添加 `public String toJson()`，返回格式化的 JSON 字符串。

**解释**：这是 Java 和 Python 之间的"数据契约"。一旦定义好 JSON 格式，两边的开发可以独立进行——Java 侧只需要产出符合格式的 JSON，Python 侧只需要解析它。任何一方的改动只要不破坏格式，另一方都无感。

**JSON 格式设计**（这是整个项目的接口契约，需仔细设计）：

```json
{
  "map": [
    "##################################################",
    "#....#.....#......................................#",
    "##################################################"
  ],
  "map_width": 80,
  "map_height": 30,
  "coordinate_origin": "bottom-left",
  "player": {
    "x": 15, "y": 10,
    "hp": 100, "max_hp": 100
  },
  "enemy": {
    "id": 0,
    "x": 25, "y": 12,
    "hp": 50, "max_hp": 50,
    "sight_range": 7,
    "attack_damage": 8
  },
  "turn_number": 247,
  "timestamp": 1700000000000
}
```

**关键设计决策**：

- **map 是字符串数组，不是二维数组**。`String[]` 比 `char[][]` 更利于 JSON 序列化，且 Python 侧处理字符串比处理嵌套数组更方便。每行就是地图的一行，索引 `map[y][x]` 直接取字符。
- **`coordinate_origin: "bottom-left"` 明确标注坐标系原点**。Java 游戏坐标原点在左下角（y 向上增长）。Python Agent 收到后，可以根据这个标注正确理解"上/下/左/右"——即便未来换了渲染方式也不会出错。
- **player 和 enemy 分开为独立对象**，而不是把信息混在 map 里。分离后 Python Agent 可以根据需要选择读取"完整地图"还是"直接取坐标"。
- **瓦片编码**：`'#'` = 墙体，`'.'` = 可通行地面，`' '` = 地图外空白。

**实现细节**：

- 构建 map 数组时，y 从 `map_height-1` 到 `0` 遍历（因为 `TETile[x][y]` 的 y=0 是底行，但文本数组的索引 0 是顶行，需要翻转）。
- 判断瓦片类型用 `tile.description().equals(Tileset.WALL.description())` 而非 `==` 比较（因为 `TETile` 可能是不同引用）。
- 手动拼 JSON，不要引入 Gson/Jackson——保持 CS61B 项目纯净性。

**验证**：在 `Game.java` 中临时添加 `System.out.println(snapshot.toJson())`，启动游戏后检查控制台输出的 JSON。复制粘贴到 [JSONLint](https://jsonlint.com) 验证格式正确。检查 map 方向是否和屏幕一致。

---

## 阶段二：Python Agent Runtime 骨架

> **一句话目标**：`python agent/server.py` 启动后，`curl -X POST localhost:8000/api/decide -d @sample_state.json` 能返回一个硬编码的 Action Plan JSON。

**解释**：这一阶段搭建 Python Agent 的"骨架"——FastAPI 服务器 + LangGraph 空工作流。就像阶段一我们用"随机移动"打通了整个集成链路，这里我们用"硬编码回复"先把 Java ↔ Python 的通信链路跑通。后续阶段再逐个填充 Perception、Planning 等节点的真实逻辑。

### 2.1 创建 Python 项目结构

**做什么**：在项目根目录下创建 `agent/` 目录和 3 个文件。

**文件结构**：

```
proj2/
├── agent/
│   ├── __init__.py          # 空文件，标识 Python package
│   ├── requirements.txt     # 依赖清单
│   ├── server.py            # FastAPI 入口
│   ├── state.py             # Agent 状态定义
│   └── graph.py             # LangGraph 工作流定义
```

**`requirements.txt` 内容**：

```
fastapi==0.115.0
uvicorn[standard]==0.30.0
langgraph==0.2.0
langchain==0.3.0
langchain-openai==0.2.0
chromadb==0.5.0
pydantic==2.9.0
```

**为什么用这些版本号**：锁定主版本号避免上游 breaking change。更精确的补丁版本不需要锁——`pip install` 会自动选最新兼容版。

**验证**：`pip install -r agent/requirements.txt` 全部安装成功，无报错。

### 2.2 定义 Agent State

**做什么**：`agent/state.py`，定义 LangGraph 工作流中流转的状态对象。

**解释**：LangGraph 的核心概念是 **State Graph**——一个状态对象沿着节点流动，每个节点读取状态、修改状态、传给下一个节点。这就像流水线上的产品经过每个工位被逐步加工。状态对象的定义决定了"工作流中各节点之间传递什么信息"。

**状态字段设计**：

```python
# state.py 伪代码结构
class AgentState:
    # === 从 Java 接收的原始数据 ===
    raw_game_state: dict       # Java POST 过来的完整 JSON
    map_grid: list[str]        # 地图字符串数组
    player: dict               # {x, y, hp, max_hp}
    enemy: dict                # {id, x, y, hp, max_hp, sight_range, ...}
    turn_number: int

    # === 各节点产出的中间结果 ===
    perception_result: str     # Perception 节点产出的场景描述文本
    relevant_memories: list    # Memory 检索到的相关历史事件
    reasoning_output: str      # Reasoning 节点的分析输出

    # === 最终输出的决策 ===
    goal: str                  # "CHASE" / "PATROL" / "AMBUSH" / "RETREAT"
    strategy: str              # "CHASE" / "INTERCEPT" / "PATROL" / "AMBUSH"
    target_x: int              # 目标位置 x
    target_y: int              # 目标位置 y
    confidence: float          # 0.0~1.0
    reasoning_summary: str     # 决策理由（用于调试和日志）
    tool_calls_log: list       # tool calling 的调用记录（调试用）
```

**为什么 State 用 dict 而不是严格的 TypedDict**：初期开发阶段字段会频繁增减，dict 更灵活。等接口稳定后可以重构为严格类型。

**验证**：`python -c "from agent.state import AgentState; s = AgentState(); print(type(s.raw_game_state))"` 无报错。

### 2.3 搭建 LangGraph 工作流

**做什么**：`agent/graph.py`，定义 LangGraph 工作流——哪些节点、按什么顺序执行、在什么条件下跳转。

**解释**：LangGraph 把 AI 的思考过程建模为有向图。每个节点是"一个思考步骤"（函数），边表示"从这一步到下一步"。这比写一个巨大的 `if-elif-else` 函数更清晰、可测试、可复用。

**阶段二的空工作流**（先跑通骨架，后续阶段填充真实逻辑）：

```
START
  |
  v
parse_input (解析 JSON → 填充 State)
  |
  v
perception (阶段三实现，现在直接 pass)
  |
  v
reasoning (阶段四+五实现，现在直接 pass)
  |
  v
planning  (阶段四实现，现在用硬编码)
  |
  v
format_output (组装 Action Plan JSON)
  |
  v
END
```

**`graph.py` 伪代码结构**：

```python
# 每个节点是一个函数，输入 state，返回修改后的 state
def parse_input_node(state: AgentState) -> AgentState:
    """从 raw_game_state 中提取结构化字段"""
    # 解析 map_grid、player、enemy 等
    return state

def perception_node(state: AgentState) -> AgentState:
    """阶段三实现"""
    return state

def reasoning_node(state: AgentState) -> AgentState:
    """阶段四+五实现"""
    return state

def planning_node(state: AgentState) -> AgentState:
    """阶段二：硬编码返回 PATROL"""
    state["goal"] = "PATROL"
    state["strategy"] = "PATROL"
    state["target_x"] = state["enemy"]["x"]
    state["target_y"] = state["enemy"]["y"]
    state["confidence"] = 0.5
    return state

def format_output_node(state: AgentState) -> AgentState:
    """组装最终输出的 JSON"""
    return state

# 构建图
def build_graph():
    graph = StateGraph(AgentState)
    graph.add_node("parse_input", parse_input_node)
    graph.add_node("perception", perception_node)
    graph.add_node("reasoning", reasoning_node)
    graph.add_node("planning", planning_node)
    graph.add_node("format_output", format_output_node)

    # 边：定义执行顺序
    graph.add_edge(START, "parse_input")
    graph.add_edge("parse_input", "perception")
    graph.add_edge("perception", "reasoning")
    graph.add_edge("reasoning", "planning")
    graph.add_edge("planning", "format_output")
    graph.add_edge("format_output", END)

    return graph.compile()
```

**为什么用 LangGraph 而不是自己写 if-else**：
1. LangGraph 提供可视化调试（打印每个节点的输入/输出）
2. 条件分支（如"LLM 不可用时跳过 planning 直接用规则"）用 `add_conditional_edges` 比手写 if-else 更清晰
3. 可扩展——后续可以加"反思节点"（输出质量检查，不合格重来）而不改已有节点

**验证**：`python -c "from agent.graph import build_graph; g = build_graph(); print('Graph built OK')"` 无报错。

### 2.4 搭建 FastAPI 服务器

**做什么**：`agent/server.py`，用 FastAPI 暴露 `POST /api/decide` 接口。

**解释**：FastAPI 是 Python 最流行的 HTTP API 框架——自动生成 OpenAPI 文档、内置请求验证、性能高。这里你只需要一个端点：接收 GameState JSON，运行 LangGraph 工作流，返回 Action Plan JSON。

**接口规范**：

- **请求**：`POST /api/decide`，Content-Type: `application/json`，Body 为阶段一的 GameState JSON
- **响应**：`200 OK`，Content-Type: `application/json`，Body 为：

```json
{
  "goal": "CHASE",
  "strategy": "CHASE",
  "target": {"x": 15, "y": 10},
  "confidence": 0.85,
  "reasoning": "玩家在视野内，血量更低，追击",
  "tool_calls": ["look_map", "check_distance"]
}
```

**`server.py` 伪代码结构**：

```python
from fastapi import FastAPI
from agent.graph import build_graph
from agent.state import AgentState

app = FastAPI(title="LLMBrain Agent Runtime", version="0.1.0")
agent_graph = build_graph()

@app.post("/api/decide")
async def decide(game_state: dict):
    # 1. 创建初始状态
    state = AgentState(raw_game_state=game_state)

    # 2. 运行 LangGraph 工作流
    result = await agent_graph.ainvoke(state)

    # 3. 提取输出并返回
    return {
        "goal": result["goal"],
        "strategy": result["strategy"],
        "target": {"x": result["target_x"], "y": result["target_y"]},
        "confidence": result["confidence"],
        "reasoning": result.get("reasoning_summary", ""),
        "tool_calls": result.get("tool_calls_log", [])
    }

@app.get("/health")
async def health():
    return {"status": "ok"}
```

**`/health` 端点的作用**：Java 侧启动 Python Server 后，需要确认 Server 已就绪才能开始发送请求。`GET /health` 返回 `200` 表示就绪。这解决了"Java 启动 Python 后立即发请求但 Server 还没准备好"的竞态问题。

**验证**：`uvicorn agent.server:app --reload` 启动后，新终端执行：

```bash
curl -X POST http://localhost:8000/api/decide \
  -H "Content-Type: application/json" \
  -d '{"map":["###","#.#","###"],"player":{"x":1,"y":1,"hp":100},"enemy":{"id":0,"x":2,"y":1,"hp":50},"turn_number":1}'
```

应返回 `{"goal":"PATROL","strategy":"PATROL","target":{"x":2,"y":1},"confidence":0.5,...}`。

---

## 阶段三：Perception Agent

> **一句话目标**：Python Agent 能把一段地图 JSON 转换成 LLM 能读懂的、结构化的场景描述文本。

**解释**：LLM 看不懂 `[["#","#","#"],["#",".","#"],["#","#","#"]]` 这种二维数组。它需要人类语言描述："你站在一个 3x3 房间的中央，北侧和南侧都有出口"。Perception Agent 的任务就是把原始数据翻译成自然语言。这相当于原构建指南阶段三的功能，但放在了 Python 侧。

### 3.1 实现地图文本化

**做什么**：`agent/perception.py`，实现 `render_local_map()` 函数。

**解释**：和原构建指南的 LocalMapRenderer 功能一样——以敌人为中心，截取 15×15 的矩形窗口，转成 ASCII 文本。区别是：这里用 Python 写，输入是 JSON 的 `map` 字符串数组而非 Java 的 `TETile[][]`。

**核心逻辑**：

```python
# perception.py 伪代码
def render_local_map(map_grid, enemy_x, enemy_y, player_x, player_y, radius=7):
    """
    以敌人为中心，截取 (2*radius+1) × (2*radius+1) 窗口。

    关键：map_grid[0] 是顶行（y=max），map_grid[-1] 是底行（y=0）。
    但游戏坐标系 y 从下到上增长，所以 map_grid[row] 对应游戏坐标 y = map_height - 1 - row。
    """
    size = 2 * radius + 1  # 15
    lines = []
    for dy in range(radius, -radius-1, -1):  # 从上到下遍历
        line = ""
        for dx in range(-radius, radius+1):   # 从左到右遍历
            world_x = enemy_x + dx
            world_y = enemy_y + dy
            # 检查是否在地图外
            if world_x < 0 or world_x >= len(map_grid[0]) or world_y < 0 or world_y >= len(map_grid):
                line += " "   # 地图外用空格
                continue
            # 翻转 y：game y → array row
            row = len(map_grid) - 1 - world_y
            if world_x == player_x and world_y == player_y:
                line += "P"
            elif world_x == enemy_x and world_y == enemy_y:
                line += "E"
            else:
                line += map_grid[row][world_x]  # '#' / '.' / ' '
        lines.append(line)
    return "\n".join(lines)
```

**为什么以敌人为中心、半径 7 格（15×15 窗口）**：和原设计一致——这是信息量和 token 消耗的平衡。15×15 = 225 字符，约 60 tokens。窗口太大 LLM 费用高，窗口太小没有足够信息做决策。

**验证**：用硬编码的 20×10 小地图调用 `render_local_map()`，打印输出，人工检查：敌人 'E' 在中心、玩家 'P' 位置正确、边界外用空格、地图方向不颠倒。

### 3.2 实现场景描述生成

**做什么**：在 `perception.py` 中添加 `generate_scene_description()` 函数，把局部地图变成自然语言描述。

**解释**：光有文本地图不够——LLM 还需要结构化的摘要信息（距离、方向、威胁）。这个函数从地图中提取关键信息，和文本地图一起组成场景描述。

**输出的场景描述结构**：

```
【局部地图（15×15, 以你为中心）】
...文本地图...

【关键信息】
- 玩家位置：(15, 10)，在你的东南方向，曼哈顿距离 5 格
- 玩家血量：100/100（满血）
- 你的血量：50/50（满血）
- 当前回合：247
- 可见敌人数量：0
```

**方向计算逻辑**：

```python
def describe_direction(from_x, from_y, to_x, to_y):
    """根据坐标差返回中文方向描述"""
    dx = to_x - from_x
    dy = to_y - from_y
    parts = []
    if dy > 0: parts.append("北")
    elif dy < 0: parts.append("南")
    if dx > 0: parts.append("东")
    elif dx < 0: parts.append("西")
    return "".join(parts) if parts else "同一位置"
```

**验证**：用阶段二的硬编码小地图调用 `generate_scene_description()`，打印输出，人工审查：方向正确、距离公式（`|dx|+|dy|`）计算正确、HP 状态合理。

### 3.3 接入 LangGraph 工作流

**做什么**：替换 `graph.py` 中 `perception_node` 的空实现，调用 `perception.py` 的函数。

**改动**：在 `perception_node` 中调用 `render_local_map()` + `generate_scene_description()`，结果存入 `state["perception_result"]`。

**验证**：启动 Server，用阶段一的 GameState JSON 发 POST 请求，在日志中打印 `perception_result`。检查场景描述是否准确。

---

## 阶段四：Planning Agent + Tool Calling

> **一句话目标**：Python Agent 调用 LLM，LLM 通过 Tool Calling 获取所需信息，最终产出一个比 RuleBasedBrain 更聪明的 StrategicIntent。

**解释**：这是整个项目的核心。到此为止，你的 Agent 不再只响应硬编码，而是真正用 LLM 推理。但 LLM 不直接输出动作——它通过 "Tool Calling"（工具调用）来获取信息、制定计划。

### 4.1 理解 Tool Calling 范式

**什么是 Tool Calling**：你给 LLM 一组"工具"（函数签名），LLM 在推理时决定"我需要调用哪个工具来获取更多信息"，然后你执行工具并把结果返回给 LLM，LLM 基于结果继续推理。这比给 LLM 一大段文本让它一次输出答案要精确得多。

**比喻**：就像你给助理一个任务："帮我安排去北京的行程"。助理（LLM）不会凭空瞎猜，而是先问你"查一下高铁时刻表"（tool call），你给他时刻表后，他再给出具体方案。

**你的 Enemy Agent 拥有这些工具**：

| 工具名 | 作用 | 输入 | 输出 |
|--------|------|------|------|
| `look_map` | 查看以敌人为中心的地图 | 无 | 局部地图文本 |
| `check_distance` | 计算到玩家的曼哈顿距离 | 玩家坐标 | 距离数值 |
| `check_player_hp` | 获取玩家血量 | 无 | HP 值 |
| `check_self_hp` | 获取自己血量 | 无 | HP 值 |
| `find_path_to` | 查询到目标点是否有路径 | 目标坐标 | 路径存在? + 第一个方向 |
| `recall_memories` | 查询对玩家行为的记忆 | 查询文本 | 相关历史事件 |

### 4.2 实现工具函数

**做什么**：`agent/tools.py`，定义 6 个工具函数及其 LangChain Tool 包装。

**解释**：每个工具函数做一件事（单一职责），从 State 中读取数据，返回结果。LangChain 的 `@tool` 装饰器会自动生成函数描述（给 LLM 看）和参数 schema。

**`tools.py` 伪代码结构**：

```python
from langchain.tools import tool

# 工具函数的签名和 docstring 会被自动发送给 LLM
# LLM 会基于这些描述决定调用哪个工具

@tool
def look_map(state: dict) -> str:
    """查看以你（敌人）为中心的局部地图（15x15 格）。
    使用这个工具来了解周围环境和玩家的相对位置。"""
    # 内部调用 perception.py 的 render_local_map()
    from agent.perception import render_local_map
    return render_local_map(
        state["map_grid"],
        state["enemy"]["x"], state["enemy"]["y"],
        state["player"]["x"], state["player"]["y"]
    )

@tool
def check_distance_to_player(state: dict) -> str:
    """计算你到玩家的曼哈顿距离（沿网格走的最短步数）。"""
    dx = abs(state["enemy"]["x"] - state["player"]["x"])
    dy = abs(state["enemy"]["y"] - state["player"]["y"])
    return f"曼哈顿距离：{dx + dy} 格（水平 {dx}，垂直 {dy}）"

@tool
def check_player_hp(state: dict) -> str:
    """获取玩家当前血量。如果玩家血量低，追击更安全。"""
    return f"玩家血量：{state['player']['hp']}/{state['player']['max_hp']}"

@tool
def check_self_hp(state: dict) -> str:
    """获取你自己的血量。血量低于 30% 时应考虑撤退。"""
    return f"你的血量：{state['enemy']['hp']}/{state['enemy']['max_hp']}"

@tool
def find_path_to(state: dict, target_x: int, target_y: int) -> str:
    """检查从你当前位置到目标点(x,y)是否存在通路。
    返回路径是否存在，以及推荐的第一步方向。
    参数：target_x 目标x坐标，target_y 目标y坐标"""
    # 用 BFS 判断可达性——直接复用 Java 的 BFS 逻辑思想
    # 在 Python 侧实现一个轻量 BFS，或把这个信息预计算好放在 state 里
    return "可以实现，但 Python 侧不真正寻路。此工具只返回可达性信息。"

@tool
def recall_memories(state: dict, query: str) -> str:
    """查询你对当前玩家的历史记忆。比如查询'该玩家以前如何逃跑'。
    参数：query 自然语言查询"""
    # 阶段五实现
    return "记忆系统暂未启用。"
```

**为什么 `find_path_to` 不实际寻路而只是可达性检查**：BFS 寻路的最终实现在 Java（`BFSPathfinder.java`），因为它需要访问完整的 `TETile[][]` 地图。Python 侧只需要知道"是否可达 + 大致方向"来辅助 LLM 决策。真正的路径规划由 Java 侧 `ClassicalPlanner` 完成后半段。

### 4.3 实现 Planning Node

**做什么**：`agent/planner.py`，实现 `planning_node`——这是整个 Agent 的大脑。

**解释**：Planning Node 调用 LLM（带 Tool Calling），让 LLM 推理出战略意图。LLM 在推理过程中可以调用工具来获取信息（map、距离、HP），最终输出一个结构化的决策。

**LLM 调用的 System Prompt 设计**：

```
你是地牢守护者 AI，控制一个敌人在地宫中追捕玩家。
你的目标：拦截或消灭玩家。

你可以使用以下策略：
- CHASE：追击玩家（当你在玩家附近且血量充足时）
- AMBUSH：埋伏在玩家前进路线上（当你预测到玩家移动模式时）
- PATROL：巡逻（当你看不到玩家时）
- RETREAT：撤退（当你的血量低于 30% 时）

每次决策你必须输出：
- goal：你的目标（CHASE/AMBUSH/PATROL/RETREAT）
- strategy：你的策略
- target_x, target_y：目标位置
- confidence：你对决策的信心（0.0~1.0）
- reasoning：1~2 句话解释为什么这样决策

重要规则：
1. 先使用 look_map 工具了解环境
2. 如果玩家很近且自己血量低，优先 RETREAT
3. 如果玩家在视野外，使用 PATROL 搜索
```

**`planner.py` 核心流程**：

```python
def planning_node(state: AgentState) -> AgentState:
    # 1. 准备工具列表
    tools = [look_map, check_distance_to_player, check_player_hp,
             check_self_hp, find_path_to, recall_memories]

    # 2. 调用 LLM，让 LLM 决定调用哪些工具
    # LLM 推理过程（Tool Calling）：
    #   第1轮：LLM 调用 look_map → 返回地图文本
    #   第2轮：LLM 调用 check_distance_to_player → 返回距离
    #   第3轮：LLM 综合信息，输出最终决策 JSON

    # 3. 解析 LLM 最终输出
    #   提取 goal, strategy, target_x, target_y, confidence, reasoning

    # 4. 写入 state
    state["goal"] = parsed["goal"]
    state["strategy"] = parsed["strategy"]
    state["target_x"] = parsed["target_x"]
    state["target_y"] = parsed["target_y"]
    state["confidence"] = parsed["confidence"]
    state["reasoning_summary"] = parsed["reasoning"]
    state["tool_calls_log"] = [...记录调用了哪些工具...]

    return state
```

**为什么用 Tool Calling 而不是把所有信息塞进一个 Prompt**：
1. **Token 节省**：15×15 地图文本约 60 tokens，每次都发给 LLM 意味着每轮决策都花这 60 tokens。而用 Tool Calling，LLM 只在需要时才 `look_map`，不需要时不消耗。
2. **推理质量更高**：LLM 在主动调用工具时的推理比被动接收一堆信息更精确——就像你问一个人"帮我查地图"比直接扔一张地图到他脸上更有效。
3. **可追踪**：Tool Calling 日志让你看到"LLM 是先查 map 还是先查距离"，方便调试。

**验证**：启动 Server → 发 POST 请求 → 检查响应中 reasoning 字段是否有实际内容（而不是硬编码）。观察 tool_calls 日志，看 LLM 调用了哪些工具、顺序如何。

---

## 阶段五：Memory System

> **一句话目标**：Agent 能记住玩家过往行为，并在决策时检索相关记忆作为参考。

**解释**：阶段四的 Agent 只基于"当前这一帧"做决策。如果玩家养成了一个习惯（比如每次都从东侧逃跑），Agent 在第 10 次遇到时仍然不知道，必须每次重新推理。Memory System 让 Agent "记住"历史，做出预测性决策。

### 5.1 理解向量记忆

**传统记忆（原构建指南的做法）**：用一个环形缓冲记录最近 20 个玩家位置，统计每个方向走了多少次。问题是：模式很浅层——"玩家 70% 向东"能告诉 LLM"拦截东侧"，但不能告诉 LLM"玩家上次遇到 Boss 时是先假装向东然后绕路返回"。

**向量记忆的做法**：把每个"游戏事件"转成 embedding（高维向量），存入向量数据库。需要回忆时，用当前局面做语义检索，找到"历史上类似局面下玩家做了什么"。

**比喻**：传统记忆就像你记"朋友喜欢吃什么菜"（统计偏好）。向量记忆就像你回忆"上次和这个朋友在类似餐厅时，他点什么菜"（情景记忆检索）。

**你的场景**：玩家连续遇到 Boss 20 次。第 21 次 Agent 自动检索"当玩家距离 3 格、在东侧出口附近时，历史中玩家怎么做的？"→ 返回"玩家 80% 的情况下向东逃跑，10% 向南，且有两次是先向东再折返"→ LLM 基于此做决策。

### 5.2 实现 Memory System

**做什么**：`agent/memory.py`，实现事件记录、embedding、存储和检索。

**解释**：用 Chroma（轻量级本地向量数据库）存储。每次游戏发生重要事件（玩家逃跑、玩家攻击、血量低于阈值等），记录为一条记忆并存入 Chroma。决策时检索最相似的 3 条历史记忆。

**记录的事件类型**：

| 事件 | 触发条件 | 记录内容 |
|------|----------|----------|
| `player_escaped` | 敌人在视野内 → 玩家跑出视野 | 方向、距离、当时血量 |
| `player_attacked` | 玩家攻击敌人 | 伤害值、玩家血量 |
| `enemy_low_hp` | 敌人血量 < 30% | 当前血量、玩家位置 |
| `player_pattern` | 每隔 10 回合 | 最近 10 回合玩家移动方向序列 |

**`memory.py` 伪代码结构**：

```python
import chromadb
from chromadb.utils import embedding_functions

class EnemyMemory:
    def __init__(self, enemy_id: int):
        self.client = chromadb.PersistentClient(path=f"./memory_db/enemy_{enemy_id}")
        self.collection = self.client.get_or_create_collection(
            name="game_events",
            embedding_function=embedding_functions.DefaultEmbeddingFunction()
        )

    def record(self, event_type: str, description: str, turn: int):
        """记录一条游戏事件。description 是人类可读的事件描述，
        Chroma 会自动将其转为 embedding 向量。"""
        event_id = f"turn_{turn}_{event_type}"
        self.collection.add(
            documents=[description],
            metadatas=[{"event_type": event_type, "turn": turn}],
            ids=[event_id]
        )
        # 只保留最近 200 条记忆，避免数据库无限增长
        # count = self.collection.count()
        # if count > 200: 删除最旧的 (count - 200) 条

    def recall(self, query: str, n_results: int = 3) -> list[str]:
        """检索与 query 最相关的 n 条记忆。
        query 是自然语言描述，如 '玩家在视野东侧，距离3格'。"""
        results = self.collection.query(
            query_texts=[query],
            n_results=n_results
        )
        return results["documents"][0] if results["documents"] else []

    def generate_summary(self, context: str) -> str:
        """基于当前 context 检索相关记忆，生成摘要文本供 LLM 使用。"""
        memories = self.recall(context, n_results=3)
        if not memories:
            return "无相关历史记录。"
        return "【相关历史记忆】\n" + "\n".join(f"- {m}" for m in memories)
```

**为什么用 Chroma 而不是 FAISS**：Chroma 是开箱即用的本地向量数据库——不需要额外安装依赖，数据持久化到本地文件，API 简单。FAISS 是更底层的向量索引库，需要自己管理持久化。对于项目规模，Chroma 完全够用。

**embedding 模型的选择**：Chroma 默认使用 `all-MiniLM-L6-v2`（Sentence Transformers 模型），本地运行，不调 API。这避免了额外的 API 费用和网络依赖。embedding 质量对于游戏事件检索完全足够。

**验证**：写一个测试脚本：创建 EnemyMemory → record 3 条事件 → recall("玩家在逃跑") → 打印结果，确认检索到的事件确实和"逃跑"相关。

### 5.3 接入工作流

**做什么**：
1. 在 Reasoning Node 中调用 `memory.generate_summary()`，把记忆摘要注入 LLM 上下文
2. 在 `recall_memories` 工具函数中接入真实的记忆检索
3. 在 `format_output_node` 中添加事件记录逻辑——把本次决策的结果作为一条事件存入 memory

**为什么在 Reasoning 层检索记忆而不是在 Planning 层**：记忆检索本身不需要 LLM——它是向量相似度计算。在 Reasoning 层做检索，然后把结果作为上下文传给 Planning 层的 LLM，更符合关注点分离原则。

**验证**：连续跑 10 轮决策 → 检查 memory 数据库中是否有 10 条记录 → 第 11 轮决策时，LLM 的 reasoning 中是否引用了历史记忆。

---

## 阶段六：LLMBrain 桥接

> **一句话目标**：Java 游戏和 Python Agent 通过 HTTP 通信，敌人在游戏中表现出 LLM 驱动的智能行为。

**解释**：这一阶段把之前所有的积木拼在一起——Java 侧通过 HTTP 客户端向 Python Agent 发送 GameState JSON，接收 Action Plan JSON，翻译成 StrategicIntent，交给已有的 ClassicalPlanner + ActionQueue 执行。

### 6.1 实现 AgentClient（Java HTTP 客户端）

**做什么**：`byog/Core/AgentClient.java`，封装对 Python Agent Server 的 HTTP 调用。

**解释**：这是 Java 侧与 Python Agent 通信的唯一入口。它负责：序列化请求、发送 HTTP POST、解析响应、处理超时和异常。

**核心设计**：

```java
// AgentClient.java 伪代码
public class AgentClient {
    private static final String AGENT_URL = "http://localhost:8000/api/decide";
    private static final int TIMEOUT_MS = 10000;  // 10 秒超时
    private static final int RETRY_COUNT = 2;

    /**
     * 向 Python Agent 发送游戏状态，获取 ActionPlan。
     * 此方法可能耗时 1~10 秒（取决于 LLM API 响应时间）。
     * 因此调用方必须异步调用。
     */
    public static ActionPlan decide(GameStateSnapshot snapshot) throws IOException {
        String json = snapshot.toJson();
        // 发送 HTTP POST
        // 解析响应 JSON → ActionPlan 对象
        return parseResponse(responseJson);
    }
}
```

**ActionPlan 数据类**：

```java
// 内嵌在 AgentClient.java 中
public static class ActionPlan {
    public final String goal;        // "CHASE" / "PATROL" / ...
    public final String strategy;    // "CHASE" / "INTERCEPT" / ...
    public final int targetX;
    public final int targetY;
    public final double confidence;
    public final String reasoning;
}
```

**为什么不引入第三方 HTTP 库**：Java 11+ 内置的 `java.net.http.HttpClient` 足够好——支持异步请求、超时控制、JSON 处理。不额外引入库保持项目纯净。

**超时和重试策略**：10 秒超时覆盖大多数 LLM API 正常响应时间。重试 2 次处理偶发网络波动。超时或重试耗尽后抛出 `IOException`，上层（LLMBrain）负责降级。

**验证**：写一个 hardcoded 的 `main` 方法，构造假 `GameStateSnapshot` → 调用 `AgentClient.decide()` → 打印返回的 ActionPlan。

### 6.2 实现 LLMBrain（薄层包装）

**做什么**：`byog/Core/LLMBrain.java implements EnemyBrain`。这是 Java 侧最薄的一层——它不包含任何 AI 逻辑，只负责"发请求给 Python Agent + 把返回的 JSON 转成 StrategicIntent + 失败时降级到 RuleBasedBrain"。

**解释**：LLMBrain 不是 AI 大脑，而是"AI 大脑的 Java 侧代理"。真正的推理在 Python 侧。这保持了 `EnemyBrain` 接口的统一——`Enemy.updateAI()` 不关心 brain 的背后是 Java 规则引擎还是远程 Python Agent。

**核心逻辑**：

```java
public class LLMBrain implements EnemyBrain {
    private final RuleBasedBrain fallback = new RuleBasedBrain();
    private int consecutiveFailures = 0;
    private boolean degraded = false;
    private long degradedUntil = 0;

    @Override
    public StrategicIntent think(GameStateSnapshot state) {
        // 1. 如果在降级模式 → 检查冷却时间
        if (degraded) {
            if (System.currentTimeMillis() < degradedUntil) {
                return fallback.think(state);  // 还在冷却，继续用规则
            }
            degraded = false;  // 冷却结束，尝试恢复
        }

        // 2. 调用 Python Agent（异步，但这里简化用同步）
        try {
            AgentClient.ActionPlan plan = AgentClient.decide(state);
            consecutiveFailures = 0;  // 成功，重置计数
            return toStrategicIntent(plan);
        } catch (Exception e) {
            consecutiveFailures++;
            if (consecutiveFailures >= 3) {
                degraded = true;
                degradedUntil = System.currentTimeMillis() + 30_000;  // 降级 30 秒
            }
            return fallback.think(state);  // 降级到规则 AI
        }
    }

    /** 将 Python Agent 返回的 ActionPlan 转为 Java 的 StrategicIntent */
    private StrategicIntent toStrategicIntent(AgentClient.ActionPlan plan) {
        StrategicIntent.Goal goal = parseGoal(plan.goal);
        StrategicIntent.Strategy strategy = parseStrategy(plan.strategy);
        Position target = new Position(plan.targetX, plan.targetY);
        return new StrategicIntent(goal, strategy, target, plan.confidence, -1);
    }
}
```

**为什么降级机制仍然必不可少**：Python Agent 依赖 LLM API（OpenAI），它的可用性不可控。降级确保——哪怕整个 Python 进程挂了，敌人仍然用 RuleBasedBrain 正常运作，游戏不会崩。

**线程安全考虑**：`AgentClient.decide()` 可能耗时 10+ 秒。必须从 `Enemy.updateAI()` 的主线程中以异步方式调用（`CompletableFuture.supplyAsync(...)`），否则游戏画面会卡住。

**验证**：启动 Python Server → 用 LLMBrain 替代 RuleBasedBrain 注入 Enemy → 运行游戏 → 观察控制台日志确认 HTTP 请求成功 → 敌人行为不同于规则 AI。

### 6.3 管理 Python Agent 生命周期

**做什么**：`Game.java` 中添加 Python Server 的启动和关闭逻辑。

**解释**：手动启动两个进程（先 `uvicorn`，再 Java）太麻烦。让 Java 在启动游戏时自动拉起 Python 进程，退出时自动关闭。

**启动**：在 `Game` 构造函数或 `playWithKeyboard()` 开始处：

```java
// 伪代码
ProcessBuilder pb = new ProcessBuilder("python", "-m", "uvicorn",
    "agent.server:app", "--host", "127.0.0.1", "--port", "8000");
pb.directory(new File("."));  // 项目根目录
Process agentProcess = pb.start();

// 等待 Server 就绪：轮询 GET /health 直到返回 200
waitForServerReady("http://localhost:8000/health", 30);  // 最多等 30 秒
```

**关闭**：在 `Game` 退出时（或 JVM shutdown hook）：

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    if (agentProcess != null && agentProcess.isAlive()) {
        agentProcess.destroy();
    }
}));
```

**Shutdown Hook 的作用**：即使游戏异常退出（抛异常、Ctrl+C），也能确保 Python 进程被关闭，避免端口占用。

**端口冲突处理**：如果 8000 端口被占用（上次没正常关闭），随机选一个备用端口（8001、8002...），并把端口号传给 `AgentClient`。

**验证**：在 Game.java 中启动游戏 → 检查任务管理器中是否有 Python 进程 → 关闭游戏 → 确认 Python 进程也消失。

### 6.4 端到端集成

**做什么**：最后一公里——把 LLMBrain 接入 Enemy，确保整个流程跑通。

**敌人口入**：在 `Game.java` 创建敌人时，判断是否有 `OPENAI_API_KEY` 环境变量 → 有则用 `LLMBrain`，无则用 `RuleBasedBrain`。

**流程总结**（一帧内发生的事）：

```
Game.java 主循环
  ↓ tickCounter >= moveInterval?
Enemy.updateAI()
  ↓ 异步调用
LLMBrain.think(snapshot)
  ↓ HTTP POST (CompletableFuture, 不阻塞主线程)
Python Agent Server
  ↓ LangGraph Workflow
  Parsing → Perception → Reasoning → Planning → Formatting
  ↓
LLM API (Tool Calling)
  ↓
ActionPlan JSON ← HTTP Response
  ↓ 回到 Java
LLMBrain.toStrategicIntent(plan)
  ↓
ClassicalPlanner.translate(intent) → BFS 寻路 → List<Action>
  ↓
ActionQueue.enqueueAll(actions)
  ↓ 逐帧
Action.execute(world, this) → 敌人移动/攻击
```

**验证**：启动游戏（确保 Python Server 已运行），玩家靠近敌人 → 观察敌人是否会表现出不同于规则 AI 的行为（提前拦截、绕路埋伏等）。

---

## 附录 A：常见陷阱

### A.1 JSON 中地图方向颠倒

**现象**：Python Agent 输出的地图上下颠倒（墙在上方但地图显示在下方）。

**原因**：`TETile[x][y]` 中 y=0 是底行，但 JSON 数组 `map[0]` 通常被当作顶行。

**正确做法**：在 `GameStateSnapshot.toJson()` 中构建 map 数组时，从 `y = mapHeight - 1` 到 `0` 遍历，确保 JSON 数组的第 0 行对应游戏坐标的最高行。

### A.2 LLM 调用阻塞游戏主循环

**现象**：游戏画面每隔几秒卡顿一下。

**原因**：`LLMBrain.think()` 中的 HTTP 调用在主游戏线程执行，等待 LLM 响应时阻塞了渲染循环。

**正确做法**：用 `CompletableFuture.supplyAsync()` 异步调用 AgentClient。同时确保 ActionQueue 有足够的缓冲动作（至少 5 个），在等待 LLM 响应期间有动作可执行。

### A.3 Python Server 没有正确关闭

**现象**：第二次启动游戏时报 `Address already in use: 8000`。

**原因**：上次 Java 进程被强制结束（Ctrl+C 或 IDE Stop），但 Python Server 子进程没有被终止。

**正确做法**：注册 `Runtime.getRuntime().addShutdownHook()` 确保 JVM 退出时关闭子进程。另外在启动时检查端口可用性，自动换端口。

### A.4 LLM 返回的 JSON 格式不固定

**现象**：有时 AgentClient 解析响应失败，抛出异常。

**原因**：LLM 不是确定性系统——同样的 Prompt 可能返回格式略有不同的 JSON（比如多了个逗号、少了引号、多了额外解释文字）。

**正确做法**：在 Python 侧（`format_output_node`）用 Pydantic 严格校验输出格式后再返回给 Java，不要把 LLM 的原始输出直接返回。Java 侧拿到的一定是格式正确的 JSON。

---

## 附录 B：验收清单

### 阶段一

- [ ] `GameStateSnapshot.toJson()` 输出合法的 JSON
- [ ] JSON 中地图行数与 `map_height` 一致，列数与 `map_width` 一致
- [ ] 地图方向正确：JSON 的 `map[0]` 对应屏幕顶行
- [ ] player 和 enemy 的 x/y 坐标与实际画面一致

### 阶段二

- [ ] `uvicorn agent.server:app` 启动成功，`/health` 返回 `{"status":"ok"}`
- [ ] `POST /api/decide` 返回合法的 ActionPlan JSON
- [ ] `python -c "from agent.graph import build_graph; build_graph()"` 无报错
- [ ] 阶段二的硬编码 `planning_node` 返回 `goal=PATROL`

### 阶段三

- [ ] `render_local_map()` 输出 15 行文本（7 格半径的窗口）
- [ ] 局部地图以敌人 'E' 为中心
- [ ] 玩家在视野内显示为 'P'，视野外不显示
- [ ] 地图边界外统一显示空格
- [ ] `generate_scene_description()` 输出的方向、距离、HP 信息正确

### 阶段四

- [ ] Tool Calling 日志显示 LLM 至少调用了 1 个工具
- [ ] LLM 输出的 goal 不是硬编码，而是根据当前局面变化（不同地图状态给出不同 goal）
- [ ] tool_calls 日志可追踪（知道 LLM 调了什么工具、什么顺序）
- [ ] 不同局面下给出合理的 reasoning（如低血量时推荐 RETREAT，近玩家时推荐 CHASE）

### 阶段五

- [ ] 连续决策 10 轮后，memory 数据库中有 10 条记录
- [ ] `recall_memories("玩家在逃跑")` 返回的事件确实与"逃跑"语义相关
- [ ] 第 11 轮决策时，LLM 的 reasoning 中引用了历史记忆
- [ ] 重启 Python Server 后，memory 数据不丢失（持久化到本地文件）

### 阶段六

- [ ] Java 启动时自动拉起 Python Server，退出时自动关闭
- [ ] `AgentClient.decide()` 成功获取 ActionPlan
- [ ] LLMBrain 在 Agent 可用时返回 LLM 决策，不可用时降级为 RuleBasedBrain
- [ ] 连续 3 次失败后进入 degraded 模式，30 秒后自动重试
- [ ] 游戏运行中画面不卡顿（ActionQueue 有足够的缓冲）
- [ ] 敌人行为与纯 RuleBasedBrain 有明显差异（更"聪明"——预测性拦截、利用地形、根据血量决策等）

---

> **完工**。以上六个阶段完成后，你拥有的是一个：Java 游戏引擎（世界模拟 + 规则兜底 + 动作执行）+ Python Agent Runtime（感知 + LLM 推理 + 工具调用 + 向量记忆）的混合智能系统。这和工业界 AI 游戏 Agent 的架构思路一致，也是你最初想做这个项目的初衷。
