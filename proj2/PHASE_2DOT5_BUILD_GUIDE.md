# DungeonMind Phase 2.5 构建指南

> 对应规范：[PHASE_2DOT5_SPEC.md](PHASE_2DOT5_SPEC.md)  
> 状态：Approved 施工指南  
> 目标：把跨层玩家状态、苹果、命名世界存档、敌人朝向、半边视野与确定性巡视做成 Phase 3 可依赖的稳定地基。

## 先读这里

这份指南面向负责实现 Phase 2.5 的 Builder。读者应理解当前 Java Game/Enemy/Action 基础结构、JUnit 4、
序列化存档和 Phase 2 `AgentSession` 的职责；不要求预先设计 Phase 3 的模型 runtime。

开始写代码前，按顺序阅读：

1. [PROJECT_INTENT_zh-CN.md](PROJECT_INTENT_zh-CN.md) 的核心体验、记忆范围和 Agent MVP。
2. [DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md) 的文档优先级、系统不变量和 Phase 2.5 gate。
3. [AI_TICK_ARCHITECTURE.md](AI_TICK_ARCHITECTURE.md) 的生产 tick、commit barrier、Session 生命周期和换层处理。
4. [PHASE_2_COMPLETION.md](PHASE_2_COMPLETION.md) 的真实交付、命令、偏差和已知限制。
5. [health-pack-requirements.md](documents/health-pack-requirements.md) 的生成、拾取、Agent 隐藏和验收规则。
6. [PHASE_2DOT5_SPEC.md](PHASE_2DOT5_SPEC.md) 的锁定决定、接口与测试矩阵。

这份 Guide 解释施工顺序和 review checkpoint；Spec 决定产品语义与验收。发生冲突时以 Spec 为准。

### 文档分工

| 文档 | 回答的问题 |
|------|------------|
| `DEVELOPMENT_ROADMAP.md` | 为什么 Phase 3 前必须先完成这批地基能力 |
| `AI_TICK_ARCHITECTURE.md` | 当前 tick、Session、Action cadence 和 commit barrier 如何运行 |
| `health-pack-requirements.md` | 苹果与跨层 HP 的具体玩法规则 |
| `PHASE_2DOT5_SPEC.md` | 必须实现的状态、协议、失败行为和验收矩阵 |
| 本指南 | 按什么依赖顺序修改生产代码，以及每一阶段在哪里停下来验证 |
| `PHASE_2DOT5_COMPLETION.md` | 最终实际完成、命令、证据、偏差和 Phase 3 交接 |

### 当前到目标的变化

| 当前 | 目标 |
|------|------|
| 换层创建满血 Player | 创建新 Player，但从 `PlayerRunState` 恢复当前 HP |
| 单一 `save/game.ser` | 不限数量的命名世界、稳定 `worldId` 与摘要列表 |
| 敌人只有当前 HP | 正式 `maxHp`、四向 Facing 和可保存巡视状态 |
| 全向曼哈顿菱形 FOV | 默认朝向半菱形，保留 LOS 与全向 baseline |
| PATROL 每次重抽目标 | 持续目标、停留扫描和有界受阻恢复 |
| hover 信息有限 | 两行底栏按需显示 HP、Facing、苹果和实际 FOV |

### 完成后的最短可见闭环

```text
玩家创建命名世界
  → 世界首次原子保存
  → 玩家受伤并拾取苹果
  → 以剩余 HP 下楼
  → 敌人按正式朝向获得半边 FOV
  → 未见玩家时持续巡视、停留和扫描
  → 玩家进入 FOV 后 reflex 中断巡视
  → :q 覆盖当前命名世界
  → 读档恢复 HP、charge、apples、敌人 facing/patrol
  → 新 runId 拒绝旧 response，worldId 仍标识同一世界
```

如果上述闭环只能通过 GUI 手工操作、测试专用分支、旧单文件存档或完整 world 注入 Brain 才成立，
实现尚未完成。

### Phase 2.5 不做什么

- 不实现背包、装备、货币、永久成长或可成长的 Player `maxHp`。
- 不让敌人识别、拾取、守卫或争夺苹果。
- 不加入敌人完整地图意识、地图记忆、跨楼层记忆或共享上下文。
- 不实现存档版本迁移、删除、重命名、云同步或周期 autosave。
- 不提前接入真实模型、LangGraph、Tool Calling、复杂技能库或多 Agent 通信。

## 八条不可破坏的边界

### 1. `PlayerRunState` 是白名单，不是 Player 镜像

它当前只保存 `currentHp`。不要把 Player 的所有字段复制进去，也不要复用旧 Player 跨层。
当前蓄力属于 floor snapshot；动画和 hover 属于 runtime-only。

### 2. 存档替换前不能删除旧文件

覆盖流程必须先完整写 sibling temp，再 replace。写入、flush、close 或 move 任一步失败时，旧文件仍应可读。
不实现备份轮转或版本迁移，但必须避免一次普通 I/O 失败直接毁档。

### 3. `worldId` 不等于 `runId`

`worldId` 随命名世界保存；`runId` 每次启动或读档重建。不能为了恢复未来 checkpoint 而复用旧 runId，
否则旧 Session 的迟到 response 可能重新获得资格。

### 4. FOV 只有一套权威计算

游戏 AI、wire Observation、trace、单敌人 hover 和全局 debug 都读取 `PerceptionSystem` 产出的 committed mask。
不要为 UI 再实现一个近似半菱形。

### 5. 巡视 Brain 不读取完整 world

目标选择、停留、扫描和局部改道只接收私有 Observation 或由它构造的有限快照。执行层仍可用 world 和
`EntityManager` 做碰撞校验，但不能把隐藏地形或玩家位置传回巡视控制器。

### 6. 转向也是正常 Action

每个 cooldown 最多执行一个 Move、Attack、Wait 或 Turn。攻击可以在同一次 Action 中转向并结算；
原地扫描不能在一个 tick 内转完四向。

### 7. 苹果对 Agent 仍是地板

苹果可以被玩家和 UI 看见，但 `VisibleTile.tileTypeOf()` 必须返回 `FLOOR`。不要添加 APPLE wire enum、
skill、event 或 prompt 提示。

### 8. Java/Python contract 必须原子升级

`agent-session.v1`、`private-observation.v2`、worldId、Facing、maxHp 和 VisionMode 要在 Java codec、
Python codec、fixtures、README 与 tests 同时落地。任何过渡期宽松解码都会掩盖半完成状态。

## 从 Phase 2 到 Phase 2.5：先理解三种状态生命周期

Phase 2.5 最容易出错的地方不是单个字段，而是把状态放进了错误的生命周期。实现前先用下表判断所有权：

| 生命周期 | 当前正式内容 | 何时重建 | 是否进入命名世界存档 |
|----------|--------------|----------|----------------------|
| 整局玩家状态 | `PlayerRunState.currentHp` | 新建命名世界 | 是，且跨楼层保留 |
| 当前楼层状态 | 玩家位置/charge、苹果、楼梯、Enemy 身体与巡视 | 进入下一层 | 是，只恢复当前楼层 |
| 运行时状态 | Session、Lease、ActionQueue、hover、FOV cache、动画 | 启动、读档或重连 | 否 |

关键结论是：读档会恢复整局与当前楼层事实，但会创建新的 `runId` 和 Session；换层只继承白名单中的
整局状态。后面的保存、感知和 Phase 3 checkpoint 都依赖这个区分。

## 实施路线

本指南的阶段编号直接对应 [PHASE_2DOT5_SPEC.md](PHASE_2DOT5_SPEC.md) 第 10 节：

| Build Guide 阶段 | Spec 实施 Step | 阶段产物 |
|------------------|----------------|----------|
| 2.5.1 | Step 2.5.1 | `PlayerRunState` 与跨层状态边界 |
| 2.5.2 | Step 2.5.2 | 可保存的苹果玩法 |
| 2.5.3 | Step 2.5.3 | 命名世界 repository 与稳定 `worldId` |
| 2.5.4 | Step 2.5.4 | Enemy `maxHp`、Facing、Turn/Wait |
| 2.5.5 | Step 2.5.5 | 半菱形 FOV 与 Observation v2 |
| 2.5.6 | Step 2.5.6 | 可保存的确定性巡视状态机 |
| 2.5.7 | Step 2.5.7 | 底部上下文 UI 与 FOV 解释 |
| 2.5.8 | Step 2.5.8 | 领域 trace、测试命名和完整 gate |
| 2.5.9 | Step 2.5.9 | Completion 与 Phase 3 开工证明 |

下图只表达阶段依赖；每个阶段的“阶段闸门”决定是否可以继续。

```text
2.5.1 玩家整局状态
  ├─> 2.5.2 苹果玩法
  └─> 2.5.3 命名世界存档

2.5.2 ────────────────> 2.5.3 命名世界存档
2.5.3 ────────────────> 2.5.4 Enemy maxHp / Facing / Action
2.5.4 ────────────────> 2.5.5 半菱形 FOV / Observation v2 / wire
2.5.5 ────────────────> 2.5.6 确定性巡视
2.5.2 + 2.5.4 + 2.5.5 ─> 2.5.7 底部 UI / debug / 图片
2.5.3 + 2.5.5 + 2.5.6 ─> 2.5.8 trace / fixtures / 全量 gate
2.5.8 + 人工证据 ───────> 2.5.9 Completion
```

不要先做 UI。没有权威状态和 committed mask 时，UI 只会制造第二套事实。

## 2.5.1 建立玩家整局状态边界

先确定 HP 的权威所有者。苹果治疗、换层和命名存档都会写这个状态；如果这里仍依赖复用 `Player`，后续模块都会继承错误生命周期。

### 2.5.1.1 先写状态范围测试

建立领域命名的测试，覆盖：

- 非满 HP 玩家下楼后 HP 不变；
- 换层前后 Player 对象不是同一个；
- charge 在换层后为 0；
- 当前攻击配置仍来自 `GameConfig`；
- animation/hit timer 不进入可序列化状态。

不要通过反射调用 `Game.nextFloor()`。提取一个由 Game 和测试共同使用的楼层转换 seam，例如
`FloorTransitionService` 或包可见的领域协作者；它接收旧玩家、run state 和新楼层构造依赖。

### 2.5.1.2 实现 `PlayerRunState`

核心不变量：

```text
capture: currentHp = clamp(player.hp, 0, configuredMaxHp)
restore: newPlayer.hp = clamp(currentHp, 0, configuredMaxHp)
```

伤害和治疗后要更新权威 Player；在换层/保存边界 capture 最新值。不要让 Game 每帧维护一个可能漂移的
重复 HP 字段。

### 2.5.1.3 显式处理 charge

为 Player 提供经过范围校验的 `restoreCharge(int)` 或等价入口。它只用于读档；换层创建的新 Player
自然从 0 开始。不要保存 `hitTimer`、`frameCounter` 或 `attackFrame`。

### 2.5.1 阶段闸门

- `P25-PLAYER-01–03` 通过。
- 新方法有简短职责注释，简单 getter 不需要重复注释。
- Game 仍通过 `Logger` 输出换层信息。
- 没有存档或苹果代码混入状态对象。

## 2.5.2 实现苹果生成与拾取

苹果先于命名存档实现，让阶段 2.5.3 保存的是已经稳定的楼层资源模型，而不是临时坐标或二次生成规则。

### 2.5.2.1 让楼梯放置返回事实

将 `placeStairs()` 改为返回 `StairPlacement(position, room)`。如果无法找到合法楼梯房，返回类型化失败，
不要靠再次扫描 world 猜坐标。

同时建立“坐标属于哪个 SquareRoom”的单一 helper。玩家 spawn 无法归属房间时，本层生成 0 个苹果并记录；
不能只排除玩家坐标。

### 2.5.2.2 先做图片 spike

`TETile` 已支持 16×16 filepath。先放置一个透明背景的 emoji 风格 apple PNG，分别从项目根目录和测试常用
启动目录运行最小渲染检查。

```java
new TETile('●', Color.RED, Color.BLACK,
        "apple health pack", "assets/tiles/apple.png")
```

如果图片路径或字形在目标环境不稳定，保留字符 fallback 即可；不要把 `TETile.character` 从 `char`
扩大为 `String`。

### 2.5.2.3 解析 healthpack 配置

在 `GameConfig` 构造时一次性读取：

```properties
easy.healthPack.minCount=3
easy.healthPack.maxCount=5
easy.healthPack.healAmount=25
balanced.healthPack.minCount=2
balanced.healthPack.maxCount=4
balanced.healthPack.healAmount=20
hardcore.healthPack.minCount=1
hardcore.healthPack.maxCount=3
hardcore.healthPack.healAmount=15
```

缺失/非法值使用 Spec 锁定的安全默认并通过 Logger 说明。PLAYING tick 不重新读 properties。

### 2.5.2.4 建立稳定候选与独立随机流

生成顺序为 player → stairs → enemies → apples。候选必须：

- 来自 SquareRoom 内部 floor positions；
- 不在 spawn room 或 stair room；
- 当前仍是普通 floor；
- 不被任何 entity 或已选 apple 占用。

收集后按 `x`、再按 `y` 排序。数量和位置使用只属于 healthpack 的 seed namespace；不要复用
WorldGenerator、enemy、stairs 或 combat 的 Random。测试先记录加入苹果前的地图/敌人/楼梯随机结果，再证明
加入苹果后未改变。

### 2.5.2.5 在玩家已提交移动后拾取

把拾取检查放在 Player 成功移动之后、楼梯换层之前。推荐顺序：

```text
attempt move
  → position changed?
  → apple pickup/heal
  → stairs transition
```

苹果格是 walkable；敌人进入时不触发。满血玩家进入时既不修改 HP，也不把 apple 改回 floor。

### 2.5.2 阶段闸门

- `P25-APPLE-01–08` 通过。
- 候选不足是有界遍历，不是随机 while retry。
- Observation 仍把 apple 当 floor。
- 运行日志只走 Logger。

## 2.5.3 建立不限数量的命名世界存档

此时玩家整局状态和苹果快照已经明确，可以一次建立类型化保存边界。后续敌人字段只扩展同一个 Enemy snapshot，不再改存档架构。

### 2.5.3.1 先定义纯 DTO 与 repository port

先写 `WorldSaveSummary`、`WorldSaveEntry`、`GameSaveData` 和 `WorldSaveRepository`，再写文件 I/O。
Game 不应知道 `.ser` 路径，UI 也不应反序列化文件。

建议把新 DTO 全部放到 `byog.IO`，只使用基础类型、String、明确 DTO 和稳定集合。不要继续用
`extraData.put("someKey", value)` 承载新机制。

### 2.5.3.2 名称与 ID 分开

实现两个独立函数：

```text
normalizeForDisplay(name): trim，保留大小写和中文
comparisonKey(name): normalized name 的 locale-independent case fold
```

文件名只使用生成的 UUID-like `worldId`。覆盖同名世界时，新游戏得到新的 worldId；不要让旧 checkpoint、
trace 或坏文件与新世界身份相同。

覆盖请求携带精确 `replacesWorldId`。先按新 ID 完成一次普通原子保存，成功后再删除旧 ID 文件；绝不能先删旧档。
若删除失败，新 DTO 保留该 replacement marker，列表据此隐藏旧入口并通过 Logger 报警。后续确认旧文件已不存在后，
下一次成功保存再清空 marker。不要用显示名或保存时间猜被替换对象。

### 2.5.3.3 文件实现使用受限根目录

生产根目录是 `save/worlds`，构造器允许测试注入临时根目录。每次 I/O 前：

1. 解析为 absolute normalized root；
2. 由已校验 opaque ID 生成目标；
3. 再次确认目标 parent 等于 root；
4. 不接受调用方传入任意 filepath。

不要使用 worldName 生成路径，也不要暴露通用 `load(String filepath)`。

### 2.5.3.4 原子写入

```text
target = <root>/<worldId>.ser
temp   = <root>/<worldId>.ser.tmp-<opaque nonce>

serialize complete data to temp
close stream
move temp → target with ATOMIC_MOVE + REPLACE_EXISTING
if filesystem rejects ATOMIC_MOVE:
    move with REPLACE_EXISTING
finally:
    delete only the exact sibling temp created by this attempt
```

测试通过注入 I/O seam 或受控 filesystem failure，分别命中 serialize、close 和 replace 失败。不得在测试中
递归删除不明确的目录。

### 2.5.3.5 列表逐文件隔离

扫描扩展名正确的文件，逐个尝试读取并转换 summary。一个文件失败只生成一条 unreadable entry 或诊断记录；
不能让 `list()` 整体抛出，也不能删除文件。可读项按 comparison key、worldId 稳定排序，再分页。

保存时间来自注入的 `Clock`，只用于显示。推荐格式 `yyyy-MM-dd HH:mm`，按本机时区渲染；测试只断言
固定 epoch 对应的 formatter 输出，不把时区文本放入 gameplay trace。

### 2.5.3.6 接入菜单状态机

不要把所有输入继续塞进 MENU 分支。增加领域职责明确的 UI state，例如：

```text
WORLD_NAME_INPUT
WORLD_OVERWRITE_CONFIRM
WORLD_LOAD_SELECT
```

源码状态名不得含开发阶段编号。新游戏生成完成后立即首次保存；首次保存失败时不要显示成功或悄悄覆盖旧世界。
`:q` 只保存活动 worldId。

### 2.5.3.7 捕获与恢复世界快照

此增量先保存 player run state、floor snapshot、stairs、apples 和已有 enemy fields。`runId` 不进入存档。
后续增量在同一个 typed EnemySaveData
中增加 maxHp、Facing 和 PatrolState。

加载顺序固定为：验证 DTO → 生成 base world → 放 stairs → 恢复 apples → 恢复 entities → rebuild index →
创建新 runId/Session。任一验证失败拒绝整个加载，不能得到半恢复世界。

### 2.5.3 阶段闸门

- `P25-SAVE-01–09` 中与现有字段有关的测试通过。
- 测试没有使用默认 `save/`。
- 当前 `save/game.ser` 被忽略，不迁移、不删除。
- 保存失败不破坏已有世界。

## 2.5.4 加入最大 HP、正式朝向和原子动作

FOV、巡视和 UI 都依赖正式的敌人身体状态，因此先让 `Enemy` 拥有 `maxHp` 与 Facing，再让感知层读取它们。

### 2.5.4.1 增加 `Enemy.maxHp`

构造时验证 `maxHp > 0` 且 clamp 当前 HP。兼容构造器如果暂时保留，只能委托一个权威构造路径。
生成时 `hp = maxHp = config.enemyHp`；读档时恢复两者。

不要把 maxHp 存在 UI 或 Observation 的派生缓存里。Enemy 是 Java 世界事实的所有者。

### 2.5.4.2 增加 `Facing`

不要把现有 `Direction.UP/DOWN/LEFT/RIGHT` 直接暴露为 wire facing。新增 `Facing` 负责 cardinal domain，
并提供显式转换。生成朝向使用独立 namespace；单独运行“加入朝向前后”随机隔离测试。

### 2.5.4.3 清除 null move 等待语义

新增：

- `TurnAction(Facing)`：只改朝向；
- `WaitAction`：只返回 success；
- `MoveAction(Direction)`：direction 必填；
- `AttackAction(Direction)`：敌人模式 direction 必填。

迁移 planner 中的 `MoveAction(null)`。这能让 trace、巡视和未来 skill registry 明确区分 MOVE、TURN、WAIT。

### 2.5.4.4 更新动作朝向的时机

在碰撞或目标检测之前设置 Enemy facing：

```text
Move:   facing = from(direction) → canMove → position/result
Attack: facing = from(direction) → lookup target → damage/result
Turn:   facing = requested → SUCCESS
Wait:   no facing change → SUCCESS
```

beforeFacing/afterFacing 与 beforePosition/afterPosition 一起进入 pending action 和 committed result。失败 Action
也可能有 `beforeFacing != afterFacing`，测试不能把 BLOCKED 等价为“世界绝对无变化”。

### 2.5.4.5 保持 cadence 集中

Turn 当前消耗正常 action opportunity。把 cadence 判断留在 Enemy/AiTickLoop 的统一执行入口，不要让
TurnAction 自己操作 tickCounter。将来改变成本时只修改执行策略，不改 FOV、save 或 Action 行为。

### 2.5.4 阶段闸门

- `P25-FACING-01–04` 通过。
- save round-trip 恢复 current/max HP 和 Facing。
- Phase 2 ActionOutcome、single-action cadence 和 collision tests 仍通过。

## 2.5.5 切换半边 FOV 与跨语言 Observation v2

Facing 已经成为世界事实后，才能用一套权威 mask 同时驱动 AI、Observation、trace 和 UI。这个阶段也一次性完成双语言协议硬切。

### 2.5.5.1 先参数化现有 FOV baseline

先把当前全向行为显式写成 `VisionMode.OMNIDIRECTIONAL` 测试，再加入 directional filter。这样失败时能判断是
LOS 退化还是半边规则错误。

权威顺序：

```text
within manhattan diamond?
  → allowed by facing half?
  → hasLineOfSight?
  → visibleMask = true
```

边缘条件用 `>=`/`<=`，保留穿过 self 的切分中线。测试四向，不用旋转一个 fixture 后假设坐标转换无误。

### 2.5.5.2 Observation 先改 Java 内部对象

给 `ObservationEnvelope` 增加 immutable：

```text
worldId
selfMaxHp
selfFacing
visionMode
```

所有 Position/mask/list 继续防御性复制。ReflexObservation 只复制实际需要的字段；它不得因为加入 facing
而恢复 Player/world 引用。

### 2.5.5.3 再硬切 wire

在同一提交增量修改：

- Java `AgentProtocol` 与 codec；
- `AgentSession.toObservationData()`；
- Python constants、exact field validators 和 DTO；
- `agent/contract/README.md`；
- shared valid/invalid fixtures；
- Java/Python fixture runner tests。

Envelope 改为 `agent-session.v1` 并加入 `worldId`；Observation 改为 `private-observation.v2`。不要添加旧版本
分支。Phase 2.5 的 intent 暂时仍是 v1；只迁移 identity/observation。

### 2.5.5.4 fixture 最小集合

至少创建：

```text
valid-observation-directional.json
valid-observation-omnidirectional.json
invalid-old-envelope-version.json
invalid-old-observation-version.json
invalid-missing-world-id.json
invalid-facing.json
invalid-vision-mode.json
invalid-apple-tile-type.json
```

合法 fixture 锁 canonical JSON；非法 fixture 锁稳定 rejection code，不锁内部异常全文。

### 2.5.5 阶段闸门

- `P25-FOV-01–06`、`P25-PROTOCOL-01–03` 通过。
- 背后玩家不会先进入 visibleEntities 再被 tile mask 隐藏。
- Python fake runtime 能在新 envelope/Observation 下继续 normal/delay/fault modes。
- Java 游戏线程仍不做 Socket I/O。

## 2.5.6 实现确定性巡视状态机

巡视必须建立在 committed Observation、Turn/Wait 和正式 FOV 上，才能既持续移动又不读取隐藏地图。

### 2.5.6.1 将状态与决策分开

`PatrolState` 是可序列化数据；`PatrolController` 是纯决策服务。不要让 DTO 持有 Random、Observation、world、
EntityManager 或 ActionQueue。

推荐字段：

```text
mode
targetPosition
dwellActionsRemaining
scanTurnsRemaining
blockedAttempts
selectionOrdinal
```

状态转换要由单一方法完成并返回 transition reason，便于 trace 和测试。

### 2.5.6.2 稳定选目标

候选来源只能是 `ObservationEnvelope.visibleTiles`/walkable mask。过滤 self、可见实体占位和距离 3–8，
按坐标稳定排序。选择索引由以下稳定输入派生：

```text
world seed + floorId + agentId + patrol namespace + selectionOrdinal
```

每次真正选择新目标后 ordinal 加一；失败或只做 candidate inspection 不增加。

### 2.5.6.3 实现到达后的节奏

```text
TRAVELING reaches target
  → DWELLING(1)
  → WaitAction
  → SCANNING(3)
  → Turn clockwise 90°
  → Turn clockwise 90°
  → Turn clockwise 90°
  → select target
```

每个 Action commit 后重新生成 Observation。扫描中玩家变得可见时，不要让 PatrolController 自己攻击；
现有 ReflexController 在下一个 action opportunity 接管，保持分层控制。

### 2.5.6.4 受阻时局部恢复

ActionOutcome 为 BLOCKED 时通知 PatrolController。第一次受阻保留 target，用最新私有 Observation 和可见实体
占位重新构造局部路径；第二次连续受阻放弃 target 并进入扫描。任一成功移动清零 blockedAttempts。

删除 `ClassicalPlanner` 中“空路径随机方向”的行为。非巡视 skill 的不可达必须返回明确失败/Wait，不能借机
引入另一条随机抖动路径。

### 2.5.6.5 save 与 reflex 恢复

保存完整 PatrolState。读档后若字段和 target 仍通过 snapshot validation，继续；否则整个新格式存档拒绝，
不要静默重抽。Reflex override 可以暂时中断状态；override 结束时，只有目标仍在当前有限知识中合法才恢复，
否则进入 scan。

### 2.5.6 阶段闸门

- `P25-PATROL-01–07` 通过。
- 相同场景两次 canonical transition 序列相同。
- 测试明确证明 controller API 无完整 world/Player 参数。
- Phase 2 stale patrol 不覆盖 reflex engage 的回归仍通过。

## 2.5.7 加入底部上下文 UI 与可理解视野

UI 最后读取已经稳定的 Enemy、苹果和 FOV 状态。这样显示层只解释事实，不会反过来定义第二套玩法规则。

### 2.5.7.1 先统一屏幕坐标

保留：

```text
WORLD_WIDTH = 80
WORLD_HEIGHT = 30
BOTTOM_UI_HEIGHT = 2
TOP_UI_HEIGHT = 3
WINDOW_HEIGHT = 35
WORLD_Y_OFFSET = 2
```

把 world → screen 和 mouse → world 提取为互逆函数。世界 tile 统一绘制在 `y + WORLD_Y_OFFSET`；顶部 HUD
位置也随之上移。不要在每个 draw 分支手写不同 offset。

### 2.5.7.2 绘制方向标记

Enemy tile 继续是红色 `E`。在所有 tile 绘制完成后，以 cell center 为基准，在 facing 对应边缘绘制小型金色
线段、圆点或三角 notch。标记必须留在同一格，不覆盖相邻 apple、墙或实体。

方向标记只读 Enemy facing，不维护独立 UI facing state。

### 2.5.7.3 底部条只显示有效 hover

hover model 在进入 draw 前解析一次：

- Enemy：agent/display name、current/max HP、Facing；
- Apple：health pack、heal amount、满血不消耗提示；
- 其他 tile：description；
- Nothing/越界：empty。

Enemy 使用 Enemy.maxHp 画血条，不从 `GameConfig.enemyHp` 猜。无有效 hover 时底部整条保持空白。

### 2.5.7.4 FOV 显示复用 committed mask

普通模式悬停一个 Enemy 时只高亮它的 `cachedVisibleMask`；debug flag 开启时高亮全部活敌人。不要在 hover
时重新运行 PerceptionSystem，否则鼠标 UI 可能看到一个与 action tick 不同的世界瞬间。

### 2.5.7 阶段闸门

- `P25-UI-01–04` 通过。
- 顶部 HUD、世界、底部 UI 不重叠。
- 地图边缘 mouse mapping 不越界。
- 图片失败时 apple 红点仍可识别。

## 2.5.8 迁移 trace、测试命名并运行完整 gate

所有生产行为稳定后再冻结 trace 和默认 gate，避免在实现中途反复改 canonical schema 或保留阶段编号 alias。

### 2.5.8.1 先迁移 trace schema

使用稳定领域版本：

```text
legacy-decision.trace.v1
private-perception.trace.v1
agent-runtime.trace.v2
```

给 v2 增加 worldId、Facing、VisionMode、self current/max HP、patrol state/target/ordinal，以及 action
before/after facing。版本专属 serializer 必须输出各自字段集合；不要让旧版本只因为共享 TraceEvent 新字段而
无意改变结构。

### 2.5.8.2 重命名所有被触及的测试代码

示例迁移方向：

```text
Phase0EncounterHarness  → LegacyEncounterHarness
Phase0EncounterTest     → LegacyEncounterTest
Phase1EncounterHarness  → PrivatePerceptionEncounterHarness
Phase1EncounterTest     → PrivatePerceptionEncounterTest
Phase2ProtocolTest      → AgentProtocolContractTest
Phase2ArbiterTest       → AgentArbiterTest
Phase2TestSuite         → CoreGameplayRegressionSuite
```

实际重命名要同步 imports、Suite 列表、命令和文档。不要保留 alias class，因为源码中的开发阶段命名本身就是
本次要清理的内容。

### 2.5.8.3 建立 leaf-only gate

新的 `CoreGameplayRegressionSuite` 直接列 leaf classes。SocketTransport 和真实 Python integration 仍独立，
避免快速 gate 因进程/网络变慢。默认成功输出应简洁；失败消息包含 Spec Test ID 或可搜索标记。

### 2.5.8 阶段闸门

- 本 Spec 11.2–11.4 的自动化矩阵全部通过。
- 下文“验证命令”中的 Java、Python、integration 和源码命名检查全部通过。
- canonical trace 重跑一致，旧版本拒绝结果稳定。
- 只有满足这些条件后，才进入阶段 2.5.9 编写完成证据。

详细断言见 Spec 第 11–12 节；本阶段不要把每个测试实现重复写进 Guide。

## 2.5.9 形成 Completion 并关闭 Phase 3 门禁

阶段 2.5.9 对应 Spec Step 2.5.9。它不再修改玩法，而是证明阶段 2.5.1–2.5.8 已真实完成。

### 2.5.9.1 完成人工试玩

至少完成：

1. 创建两个中文/英文命名世界并检查列表摘要。
2. 受伤、满血经过 apple、缺血拾取 apple、下楼和读档。
3. 从敌人背后接近，确认未被发现；走到侧面中线和正面确认规则。
4. 悬停敌人检查底栏、血条、Facing、方向标记和单敌人 FOV。
5. 开启全局 FOV，比较 directional/omnidirectional 固定场景。
6. 观察敌人前往同一目标、等待、三次扫描、被另一个敌人阻挡和被玩家打断。
7. 从非项目根启动一次，检查 apple 图片或 fallback。

### 2.5.9.2 编写 Completion

创建 `PHASE_2DOT5_COMPLETION.md`，记录：

- 实际 commit、环境、命令、测试数和耗时；
- 所有 Spec Test ID 的状态；
- valid/invalid fixture 列表和两语言结果；
- deterministic trace/生成对比；
- UI 与 emoji/fallback 人工证据；
- 未完成项、偏差和 Phase 3 禁止假设。

未创建最终 commit 时要像 Phase 2 Completion 一样明确区分 HEAD 与工作树，不得伪造 commit 证据。

### 2.5.9 阶段闸门

- Spec Definition of Done 已逐项核对，未通过项保留未完成状态。
- `PHASE_2DOT5_COMPLETION.md` 包含自动化、人工、trace、fixture、环境和基线证据。
- Completion 明确列出 Phase 3 可以依赖和不得假设的内容。
- 只有本闸门关闭后，才开始 Phase 3 Step 3.1。

## 文件导航

按下面顺序阅读生产入口，可以用最短路径复核 Phase 2.5 的所有权边界：

| 顺序 | 入口 | 重点 |
|------|------|------|
| 1 | `byog/Core/Game.java` | 新建/读取世界、换层、tick、拾取与 UI composition |
| 2 | `byog/Entity/PlayerRunState.java`、`Player.java` | 整局 HP 与当前楼层状态边界 |
| 3 | `byog/IO/WorldSaveRepository.java`、`GameSaveData.java` | 命名世界、原子保存和类型化快照 |
| 4 | `byog/WorldGen/HealthPackGenerator.java` | 苹果候选、独立随机流和放置 |
| 5 | `byog/Entity/Enemy.java`、`byog/Action/` | `maxHp`、Facing、Move/Attack/Turn/Wait |
| 6 | `byog/Perception/PerceptionSystem.java`、`ObservationEnvelope.java` | 半菱形 mask、LOS 和 Observation v2 |
| 7 | `byog/AI/PatrolController.java` | 私有 Observation 驱动的巡视状态机 |
| 8 | `byog/Bridge/AgentProtocol.java`、`AgentSession.java` | world identity 与跨语言映射 |
| 9 | `byog/Trace/AgentTrace.java` | 领域 trace schema 与关联字段 |

逐文件完整变更范围以 Spec 第 9 节为准；若实际实现采用不同领域类名，在 Completion 中记录偏差并同步本表。

## 验证命令

阶段 2.5.8 按以下顺序执行。Completion 记录实际命令、测试数、耗时和结果。

```powershell
# 1. Java 全量编译
$javaSources = Get-ChildItem byog -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
javac -encoding UTF-8 -cp "..\library-sp18\javalib\*" -d out $javaSources

# 2. deterministic leaf-only gate
java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.CoreGameplayRegressionSuite

# 3. SocketTransport 独立回归
java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Bridge.SocketTransportTest

# 4. 真实 Python 进程 integration
java "-Dfile.encoding=UTF-8" `
    -cp "out;..\library-sp18\javalib\*" `
    org.junit.runner.JUnitCore byog.Test.AgentRuntimeIntegrationTest

# 5. Python contracts
$env:PYTHONPATH = "agent/python"
python -m unittest discover -s agent/python/tests -v

# 6. 源码领域命名审计；文档目录单独排除
rg -n "Phase[0-9]|Step[0-9]|阶段[[:space:]]*[0-9]|步骤[[:space:]]*[0-9]" `
    byog agent/python config
```

第 6 项出现结果时逐项判断：协议文档可以写历史阶段，但源码、测试、注释、日志、异常、配置 key 和 runtime
字符串必须清零。不要通过修改搜索词隐藏结果。

## 出问题时先看这里

| 症状 | 第一检查点 | 不要先做什么 |
|------|------------|--------------|
| 下楼后满血 | `PlayerRunState.capture/restore` 与 nextFloor 顺序 | 复用旧 Player |
| apple 出现在楼梯房 | `StairPlacement.room` 和 room membership | 只排除 stairs coordinate |
| apple 改变敌人位置 | healthpack Random namespace 和候选排序 | 调整全局 Random 调用次数 |
| 满血 apple 消失 | pickup 判断发生在 tile replacement 之前吗 | 读档时重生成 apple |
| 世界列表一个坏档全失败 | repository 是否逐文件隔离 | 自动删除所有失败文件 |
| 覆盖失败后旧档丢失 | 是否先删 target、temp 是否同目录 | 增加旧格式 fallback |
| 背后玩家仍触发 reflex | visibleEntities 是否在 mask 完成前收集 | 在 ReflexController 再过滤一次 |
| UI FOV 与 AI 不同 | UI 是否重新计算而非读 cached mask | 微调 UI 近似公式 |
| 敌人每次 save/load 改目标 | PatrolState/ordinal 是否完整保存 | 保存 Java Random 对象 |
| 敌人卡住随机抖动 | planner 空路径 fallback 是否仍存在 | 增加更多 random retry |
| Python 拒绝所有 observation | envelope/Observation constants 与 exact fields | 临时忽略 unknown field |
| 读档接受旧 response | 是否复用了 runId | 删除 worldId |
| Phase 3 checkpoint 串世界 | thread key 是否遗漏 worldId 或覆盖生成未换 ID | 使用 display name 当 key |

## 最终验收清单

### 玩家与世界

- [ ] HP 跨层；charge 读档恢复、换层清零。
- [ ] 苹果生成、拾取、存档和 Agent 隐藏全部通过。
- [ ] 命名世界不限数量，首次保存、覆盖、摘要与失败隔离正确。
- [ ] 旧档不迁移、不删除、不加载。

### 敌人与感知

- [ ] Enemy maxHp、Facing 和 Action 朝向语义正确。
- [ ] Turn/Wait 明确，单 Action cadence 未退化。
- [ ] directional 半菱形、LOS、边缘、墙角和全向 baseline 通过。
- [ ] 私有 Observation 不泄漏 apple、背后玩家或隐藏地图。
- [ ] 巡视目标持续、到达扫描、两次受阻和 reflex interrupt 正确。

### 契约与 UI

- [ ] Java/Python 只接受 `agent-session.v1` 与 `private-observation.v2`。
- [ ] shared fixtures、domain trace 和新 Suite 命名完成。
- [ ] bottom hover、HP bar、Facing marker、单/全 FOV 可读。
- [ ] apple emoji 风格图片稳定，或明确使用红点 fallback。

### Gate 与交接

- [ ] Java compile、deterministic、Socket、integration、Python tests 全部通过。
- [ ] 触及源码和配置的开发阶段编号命名清零。
- [ ] Roadmap、healthpack、Phase 3 两份文档与实现一致。
- [ ] `PHASE_2DOT5_COMPLETION.md` 包含可复查证据和最终 commit 状态。
- [ ] Phase 3 尚未提前实现；只有 Phase 2.5 gate 关闭后才能开工。
