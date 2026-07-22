# AI决策流程日志输出计划

## 需求分析

**目标：** 为AI决策流程添加清晰的日志输出，帮助理解敌人行为决策过程，同时避免输出过多信息。

**约束：**
- 必须使用项目已有的 `Logger` 类进行输出
- 不修改任何业务逻辑，仅添加日志
- 输出信息要简洁、有价值，避免冗余

## 日志输出方案

### 输出节点选择

根据AI决策流程，在以下关键节点添加日志：

| 位置 | 输出内容 | 输出级别 |
|------|----------|----------|
| Enemy.updateAI() | 敌人ID、当前位置、意图决策、队列状态 | DEBUG |
| RuleBasedBrain.think() | 距离计算结果、意图选择原因 | DEBUG |
| ClassicalPlanner.translate() | 路径长度、策略类型、动作数量 | DEBUG |

### 日志格式设计

```
[DEBUG] Enemy[ID] pos=(x,y): Intent=CHASE/PATROL, QueueSize=N
[DEBUG] RuleBasedBrain: dist=X <= sightRange=Y → CHASE (or > → PATROL)
[DEBUG] ClassicalPlanner: pathLength=N, strategy=CHASE, actions=N
```

## 修改文件

1. **byog/Core/Enemy.java**
   - 在 `updateAI()` 方法中添加日志
   - 输出敌人位置、意图、队列状态

2. **byog/Core/RuleBasedBrain.java**
   - 在 `think()` 方法中添加日志
   - 输出距离计算和意图选择

3. **byog/Core/ClassicalPlanner.java**
   - 在 `translate()` 方法中添加日志
   - 输出路径长度和动作数量

## 实施步骤

1. 修改 `Enemy.java`：
   - 添加 `Logger` 导入
   - 在 `updateAI()` 中获取意图后输出日志

2. 修改 `RuleBasedBrain.java`：
   - 添加 `Logger` 导入
   - 在 `think()` 中计算距离后输出日志

3. 修改 `ClassicalPlanner.java`：
   - 添加 `Logger` 导入
   - 在 `translate()` 中规划路径后输出日志

4. 验证编译通过

## 风险评估

- **低风险**：仅添加日志，不修改业务逻辑
- **兼容性**：所有现有API保持不变
- **性能影响**：DEBUG级别日志，不影响游戏运行

## 预期效果

运行游戏时，控制台会输出类似以下的日志：

```
[DEBUG] Enemy pos=(10,15): Intent=CHASE, QueueSize=5
[DEBUG] RuleBasedBrain: dist=3 <= sightRange=5 → CHASE
[DEBUG] ClassicalPlanner: pathLength=8, strategy=CHASE, actions=8
```

这样可以清晰地看到：
- 每个敌人当前的位置和决策意图
- 距离判断结果和意图选择依据
- 规划的路径长度和生成的动作数量
