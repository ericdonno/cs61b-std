# DungeonMind 仓库执行约束

本文件适用于本目录下的 Java 游戏、外部 Agent runtime、测试和项目文档。它规定长期有效的仓库边界；
具体阶段的产品行为以已批准的 Phase Spec 为准，施工顺序以对应 Build Guide 为准，完成事实以 Completion 为准。

## 包与运行时边界

### Java 包职责

项目不再受 CS61B autograder 的 `byog.Core` 包结构约束。`byog/` 按稳定职责拆分：

```text
byog/
  Core/          游戏入口、菜单、主循环与顶层 composition
  Entity/        Player、Enemy、实体状态与位置索引
  AI/            感知后的仲裁、反射、意图、规划与 AI tick 协调
  Action/        原子 Action、ActionOutcome 与有界 ActionQueue
  WorldGen/      房间、走廊、楼层生成及生成结果
  Perception/    私有视野、Observation 与可见 tile/entity 快照
  Bridge/        Java 游戏侧协议、Session、transport 与跨进程边界
  IO/            配置、命名世界存档、类型化快照与加载结果
  Trace/         canonical gameplay trace 与关联字段
  Common/        跨模块使用的方向、难度和确定性工具
  Test/          领域命名的测试、fixture 与 headless harness
  TileEngine/    tile 定义与渲染
  Helper/        Logger 和通用数据结构/算法
  lab5/          当前共享 Position 类型及遗留课程示例；运行时只允许依赖已存在的 Position 契约
  lab6/, SaveDemo/  课程或演示代码；不得成为 DungeonMind 运行时依赖
```

包表只描述职责，不作为完整类清单。新增或移动类时按职责选择包；不要为了让文档看起来完整而在这里枚举
所有类名。

### 外部 Agent runtime

外部 Agent 按语言隔离，并共享 `agent/contract/` 中的稳定 wire contract：

```text
agent/
  contract/      跨语言协议、版本规则与共享 fixtures
  python/        Python runtime、CLI 与 Python tests
  typescript/    未来 TypeScript runtime；与 python/ 平行
```

`byog/Bridge` 只负责 Java 游戏进程一侧的协议、会话与传输。具体语言的 Agent brain 不得放入
`byog/Bridge` 或根目录 `bridge/`。Java 仍是世界状态、动作合法性、碰撞、伤害和最终提交的唯一权威。

### 已作废的历史入口

`Game.playWithInputString(String)` 是 DungeonMind 不维护的遗留 CS61B autograder API。除非用户明确要求，
不得围绕它新增 runtime、Agent、测试或 integration 能力。

## 文档与阶段契约

### 文档位置

- 仓库根目录保存 `DEVELOPMENT_ROADMAP.md`、跨阶段架构文档、当前实施阶段的 Spec/Build Guide，
  以及仍作为直接前置输入的 Completion。
- `documents/` 保存已关闭阶段的 Spec/Build Guide/Completion 和专题说明。当前阶段关闭后可以迁移文档，但必须在同一改动中更新
  所有相对链接和入口说明。
- 不得仅凭 Roadmap、文件名或测试名宣称功能已经实现；实现事实必须来自当前代码与 Completion 证据。

### 生成或更新 Phase 文档前

1. 完整阅读 `DEVELOPMENT_ROADMAP.md` 第 1 节的文档优先级与系统不变量。
2. 阅读当前阶段的前置 Completion、相关跨阶段架构和被引用的专题需求。
3. 审计当前代码、配置、测试入口、分支、HEAD 与工作树；明确区分当前事实和拟议行为。
4. 遇到冲突时显式记录并请求或采用有授权的裁决，不得静默选择方便实现的一方。

### Spec 与 Build Guide 编号映射

- Spec 第 10 节使用 `Step <阶段号>.<序号>`；Build Guide 使用完全相同的阶段编号、顺序和覆盖范围。
- 例如 Spec `Step 3.4` 对应 Build Guide `## 3.4`；Phase 2.5 使用 `Step 2.5.4` 对应
  Build Guide `## 2.5.4`。
- 一个 Spec Step 只能对应一个 Build Guide 顶级实施阶段。Guide 可以在该阶段下增加
  `3.4.1`、`3.4.2` 等子节，但不得另建没有对应 Spec Step 的平行实施阶段。
- 每个 Build Guide 实施阶段必须说明：为何此时实施、生产代码入口、职责与接口、保持不变的行为、
  阶段闸门，以及详细验证所在位置。
- Build Guide 的主结构保持为：读者与目标、当前到目标、不可破坏边界、必要心智模型、实施路线、
  编号阶段与阶段闸门、文件导航、验证命令、排错入口、最终验收清单。

## 禁止将开发阶段写入代码

- 编写或修改代码时，严禁出现 `Phase`、`Step`、`阶段 X`、`步骤 X` 等开发路线图编号或同义命名。
- 该禁令覆盖文件名、包名、类名、接口名、方法名、字段名、变量名、测试名、注释、Javadoc、日志、
  异常消息、运行时字符串和配置键。
- 命名必须表达稳定的领域职责或行为，例如使用 `AiTickLoop`、`IntentArbiter`，不得使用
  `Phase2Loop`、`Step23Arbiter` 等阶段性名称。
- Phase/Step 编号只允许出现在 Roadmap、Phase Spec、Build Guide、Completion、评审记录和提交说明中。
- 当前 `byog/Test` 中仍有历史阶段编号类名。它们是待迁移技术债，不是新命名示例；任务触及这些测试时，
  应在不扩大范围的前提下迁移为领域名并同步 imports、Suite、命令和文档，不保留 alias class。
- 如果重命名会破坏尚未批准的外部兼容契约，先向用户说明并停止扩大改动。

## 控制台与日志

### Java 生产代码

- `byog/` 下的 Java 生产运行日志必须通过 `byog.Helper.Logger` 输出。
- 禁止在 Java 生产代码中直接使用 `System.out`、`System.err`、`System.out.printf`、
  `Throwable#printStackTrace` 或其他绕过 `Logger` 的诊断输出。
- 协议帧应写入明确的 transport，不得伪装成控制台日志。JUnit、编译器和命令行工具自身的输出不受此条限制。

### Python runtime

- Python runtime 不调用 Java `Logger`。stdout 只用于协议或已定义的 ready 输出，不得混入诊断文本。
- Python diagnostics 使用标准 `logging` 或明确的 stderr sink，并遵守相应 protocol/trace 的脱敏规则。
- API key、authorization、完整 raw prompt、完整 provider response 和自由 reasoning 不得写入日志或 trace。

## 代码注释

- 新增方法应在职责、关键边界或失败行为不直观时添加简短注释。
- 构造方法不强制添加注释；类名或方法名已经准确表达简单行为时，不添加重复注释。
- 注释解释为什么存在该边界或行为，不复述逐行代码，也不写开发阶段编号。

## 验证要求

按本次实际改动选择最小但充分的验证，不用无关测试制造噪声：

| 改动范围 | 最低验证要求 |
|----------|--------------|
| 仅注释 | 不执行编译或测试；检查注释没有引入错误契约或阶段编号 |
| 仅 Markdown/文档 | 不编译；检查本地链接、标题层级、代码块闭合和相关 Spec Step/Guide 阶段映射 |
| Java 生产代码或测试 | UTF-8 全量编译，并运行对应 Build Guide 阶段闸门与受影响领域测试 |
| Python runtime 或 tests | 运行对应 Python contract/unit tests；涉及进程生命周期时再运行有界 integration |
| Java/Python wire contract | Java codec、Python codec、共享 fixtures 和正反 contract tests 必须同次验证 |
| 文件移动或重命名 | 除相关测试外，检查 imports、Suite、命令、文档入口和全部相对链接 |

- 完整关闭一个实施阶段时，必须执行该 Build Guide 的阶段闸门；不能用较小的局部测试替代。
- 测试不得默认打开 GUI、访问真实付费 provider、写默认玩家存档或依赖 `Thread.sleep()` 猜测并发时序。
- 如果本轮只改文档，不因文档中列出了未来命令而执行尚未实现的编译或测试。
- Completion 必须记录实际执行的命令、测试数、结果、耗时、环境与未验证项，不能把计划命令写成完成证据。

## 生成物与敏感信息

- 不新增或提交 `.venv/`、`__pycache__/`、`out/`、`.env`、本地 checkpoint DB、runtime trace、临时存档、
  API key 或其他机器相关生成物，除非已批准 Spec 明确把某个稳定 fixture/artifact 列为交付物。
- 不删除或重写用户已有生成物来“清理工作树”，除非用户明确授权并且目标经过确认。
