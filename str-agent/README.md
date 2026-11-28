# Str Agent · 模块文档（`str-agent`）

> 面向单独针对性开发的模块说明。**只凭本文件 + 源码即可在 `str-agent` 模块内独立开发插件 / 工具 / 条线、跑测试**，无需读其它模块实现细节。
> 顶层定位与移植纪律见仓库根 `README.md`；模块划分与依赖方向见 `docs/D2-architecture.md`。

---

## 1. 定位与依赖

```
host  →  data  →  str-agent  →  core        （单向依赖，禁止反向）
```

- `str-agent` **只依赖 `core`**（`api(project(":core"))`），不依赖 `host` / `data`。
- `core` 禁止任何 Android / GUI / 打包类依赖（`core` 不知道 agent 存在）。本模块保持纯 Kotlin/JVM —— **它就是插件本体**。
- `host`（参考宿主，无头 MCP 桥进程）通过 `AgentIntegratedOrchestrator`（本模块唯一胶水类）调用本模块；`data` 提供 `LlmClient` / `SearchClient` / `PrefsStore` 等端口实现，经宿主注入。
- 本模块自身依赖：`kotlinx-serialization-json 1.7.3`、`kotlinx-coroutines-core 1.9.0`。

> **纯插件定位**：Str Agent 是**纯插件类 Agent**，本身不独立运行。它通过 `StrAgentPlugin`（见 `plugin/` 包）声明要消费的宿主缝（`inject`：model/storage/prefs/session）与贡献的能力缝（`contributes`：report/followup/review/editorial/mcp-bridge），并自描述归属 the reference harness 插件类型（`tools-capabilities` / `workflows`）。任意宿主——Kotlin 宿主 [`StrAgentHost`](#)、DeepSeek Harness（经 localhost:7787 MCP 桥）、或任意 MCP 客户端——装载即插即用。插件契约与 the reference harness 对齐细节见 `docs/D13-插件契约与the reference harness对齐.md`；dev-agent 契约对齐进行中（T-019）。

> 模块边界铁律（C‑02）：**不要在 `core` 里引用 `com.stragent.agent.*`，也不要在本模块里引用 `android.*` 或任何 GUI / 安装包相关依赖。** 任何违反都会编译失败或违反决策（AD-020）。

---

## 2. 包结构（`com.stragent.agent`）

| 文件 | 职责 |
|---|---|
| `AgentTool.kt` | `AgentTool` 接口 + `ToolRegistry`（注册 / 白名单 / 目录序列化） |
| `BuiltInTools.kt` | 17 个内置工具的工厂函数 + `buildAnalysisTools(...)` 全集 |
| `PluginManifest.kt` | 插件清单数据类（含校验与 JSON 解析） |
| `PluginRegistry.kt` | 4 个内置插件 + 用户导入插件（宿主提供的插件目录，默认 `<home>/plugins`） |
| `AgentLoop.kt` | **harness 内核**：JSON 文本协议循环、三道护栏、状态工具 |
| `HarnessContract.kt` | `AGENT_HARNESS_CONTRACT` 固定的协议说明字符串 |
| `AgentEngine.kt` | 按 `PrefsStore` 选择当前报告/追问/审查插件 |
| `AgentIntegratedOrchestrator.kt` | 经典流水线 ↔ agent 双路径编排 + 回落纪律 |
| `EditorialDept.kt` | 编辑部多 Agent（主编选题 → 记者条线 → 主笔成稿 → 核查 → 终审） |
| `AgentReportDelegate.kt` | 单 Agent 报告生成（含 `audit_report` 确定性自检） |
| `StepSearchClient.kt` | 能力层搜索客户端适配 |

测试（`src/test/kotlin/com/stragent/agent/`）：`AgentKernelTest.kt`（harness 内核、注册表、清单、工具适配）、`EditorialDeptTest.kt`（编辑部流程）。

---

## 3. 核心抽象

### 3.1 `AgentTool` —— harness 最小单元

```kotlin
interface AgentTool {
    val name: String
    val description: String
    val parametersSchema: JsonObject          // JSON Schema（properties/required）
    val cacheable: Boolean get() = true       // 同 (name, args) 重复调用直接回缓存
    fun invoke(args: JsonObject): JsonObject  // 无副作用执行体，返回 JsonObject
}
```

- 错误统一抛 `ToolException(message: String)`（中文、面向用户/模型可读）；`AgentLoop` 把 `message` 回填给模型，不会崩。
- **缓存语义**：`cacheable` 默认 `true`，生产纪律要求「同参重复查询不重复付费」。有状态工具（`submit_plan` / `record_finding`）由 `AgentLoop` 内建处理、**不进缓存**。
- `ToolRegistry.of(tools)` 构建注册表：重名直接抛 `工具重名`（开发期即可发现）。

### 3.2 `PluginManifest` —— 「万物皆插件」的单位

一段 JSON 描述一个 agent：

```json
{
  "id": "report-deepdive",
  "name": "深挖报告",
  "kind": "report",
  "systemPrompt": "……（追加在 harness 契约与偏好块之后）",
  "tools": ["get_overview", "get_column_profile"],   // 省略 = 全部开放；白名单按表过滤，错名忽略
  "model": null,          // 覆盖模型；null = 用户当前模型
  "temperature": 0.7,     // 0.0–2.0
  "maxSteps": 18          // 步数预算 1–40（防失控）
}
```

- `kind` 决定 `finish` 的输出契约（见 §5）：`report` / `followup` / `review` / `editorial`。
- `editorial` 额外需要 `writerPrompt`（主笔提示词）与 `beats`（1–6 条记者条线），每条 beat 自带 `name` / `prompt` / `tools?`。
- 不做动态代码加载 —— 插件化落在 manifest 层，安全边界清晰。`PluginManifest.parse(text)` 统一解析 + 校验（坏 `kind` / 缺 `id` / 预算越界都会被拒）。

### 3.3 `PluginRegistry` —— 4 内置 + 用户导入

- 内置 4+1 个插件（`builtIns`）：`report-deepdive`、`report-standard`、`followup`、`review`、`editorial-deepdive`。
- 用户插件：`filesDir/plugins/*.json`（由宿主传入插件目录实例化，默认 `<home>/plugins`），**内置 id 不可被覆盖**（同 id 导入直接抛异常，防篡改语义）。
- API：`list()` / `get(id)` / `import(text)` / `delete(id)` / `user()`。

### 3.4 `AgentLoop` —— harness 内核（JSON 文本协议）

不依赖任何服务商的 function‑calling。详见 §4。

### 3.5 `AgentIntegratedOrchestrator` —— 双路径编排

- 包装 `core` 的经典流水线（`InsightOrchestrator`）与本模块的 agent 引擎。
- `generateInsight(...)`：先按 `AgentEngine.selectedReportPlugin` 选插件 → 走 agent 路径（`editorial` 走 `EditorialDept`，否则走 `AgentReportDelegate`）；**失败 / 取消 / 返回 null 自动回落 classic**，用户无感。
- `askFollowup(...)`：同理，`selectedFollowupPlugin` → `askFollowupByAgent`（带查数工具的循环，finish 取 `answer`）→ 回落 `core.ai.askFollowup`。

### 3.6 `AgentEngine` —— 插件选择

- `selectedReportPlugin(prefs, registry)` / `selectedFollowupPlugin(...)` / `selectedReviewPlugin(...)`：按 `PrefsStore` 里用户当前选中的引擎 id 从注册表取插件；未选则返回 `null`（编排器回落经典路径）。

---

## 4. JSON 协议（harness）

固定契约 `AGENT_HARNESS_CONTRACT`（`HarnessContract.kt`，进 system prompt 的固定段）+ 工具目录（`catalogJson`）+ 插件提示词（`manifest.systemPrompt`）三者拼接成 system 消息。

### 4.1 每轮格式

模型每轮**只输出一个 JSON 对象**：

```json
{"thought":"一句话思考","tool":"工具名","args":{…}}      // 调用工具
{"tool":"finish","args":{…}}                            // 终结（args 形状由 kind 决定）
```

- 工具结果以 `<tool_result tool="工具名">\n{…}\n</tool_result>` 包裹回填为下一条 `user` 消息（`/chat/completions` 唯一通用通道）。
- 数字纪律：模型引用的每个数字必须来自工具结果或任务包里的数据摘要；写不出出处的数字即编造。

### 4.2 三道护栏

1. **解析失败** → 修复提示重试至多 2 轮 → 仍失败返回 `null`（调用方回落经典流水线）。
2. **步数预算 `maxSteps`** → 最后一轮强制要求 `finish` → 仍不出 → `null`。
3. **`cancelSignal`** 每步检查 → 抛 `AgentCancelled`（调用方决定如何呈现中断）。

### 4.3 状态工具（由 loop 内建，不进缓存）

| 工具 | 作用 |
|---|---|
| `submit_plan` | Plan‑and‑Execute 计划层：登记 3–8 步分析计划（UI 展示）；`outcome.plan` |
| `record_finding` | Scratchpad 发现日志：边查边记发现卡（title/detail/evidence）；`outcome.findings` |
| `audit_report` | 确定性自纠错：draft → `verifyReport` 数字溯源，未溯源数字/算式错误回填给模型修（校验器是代码，不是另一个 LLM，无 judge 偏差） |
| `suggest_chart` | 建议图表：`{type,title,data:[{label,value}]}`，上限 5 张；报告正文用 `{{chart:序号}}` 引用 |

### 4.4 `finish` 输出契约（按 `kind`）

| kind | `finish.args` 字段 | 用途 |
|---|---|---|
| `report` | `report` = 完整 Markdown | 深挖/标准报告（从 `## 总体概览` 开始） |
| `followup` | `answer` = 纯文本回答 | 两三段话 |
| `review` | `issues` = `[{kind,quote,why}]` | 四类数字错误（归属/口径/因果倒置/越界） |
| `editorial` | `report` = 主笔终稿 | 编辑部多层级 |

---

## 5. 内置工具清单（`buildAnalysisTools`）

分析工具（列名解析版，返回 JsonObject，数字按 JS 语义 `550.0→550`）：

| 工具名 | 作用 |
|---|---|
| `get_overview` | 数据概况：行列数、列名/类型、有效行数、单元格密度 |
| `get_column_profile` | 单列画像（数值：min/max/sum/mean/median/p25/p75；文本：distinct/topValues） |
| `group_aggregate` | 按文本维度对数值列 sum/avg 聚合 + 占比（超 limit 折「其他 N 项」） |
| `get_outliers` | IQR 异常值检测（1.5×IQR），返回异常行 + 正常区间 |
| `scan_anomalies` | 六类时序/结构异常（突变/缺口/主键重复/负值/塌陷…） |
| `get_attribution_chain` | 某期突变归因链（维度贡献 → 量价分解，无残差） |
| `get_group_yoy` | 分组同比，标记拖累组（落后 ≥5pp）与样本不足组 |
| `get_cross_tab` | 维度交叉 + 产出率判定 |
| `get_group_trend` | 分组走势（滚动 12 期基线，连续 ≥3 期低于 30% 才报走弱） |
| `get_correlations` | 数值列两两皮尔逊相关（\|r\| 降序） |
| `drill_down` | 分类下钻（合计/笔数/单均 + 组内分布 + 前 20 行明细带行号） |
| `get_series` | 两列构建序列（最多 120 点） |
| `get_extremes` | 关键数值列最值定位（组/期/行） |
| `get_quality` | 数据质量（空值率/重复行/常数列/最差列） |
| `get_report_context` | 取 brief 指定段落（按需取上下文，不整段塞入） |
| `audit_report` | 报告草稿确定性自纠错（见 §4.3） |
| `web_search` | 联网检索（需注入 `SearchClient`） |
| `web_fetch` | 抓网页正文（去标签，截 4000 字） |

> 调参错误给可行动中文提示（如「列不存在: xxx（该数据集的列: …）」「维度请选文本列」），不抛栈。

---

## 6. 如何加工具

1. 在 `BuiltInTools.kt` 写一个返回 `AgentTool` 的工厂函数（参考 `overviewTool`）：
   - `parametersSchema` 用私有 `schema("列名" to ("string" to true), …)` 构造；
   - 列名用 `resolveColumn(dataset, name, "角色", 期望类型?)` 解析（自动给全列名错误）；
   - 数字输出用 `num(Double)` / `putNum(...)`（保证 JS 语义）；
   - 异常抛 `ToolException("中文可行动信息")`。
2. 在 `buildAnalysisTools(...)` 的 `listOf(...)` 里追加该工厂（有状态工具才需特殊处理，普通工具直接加入即被缓存）。
3. 如该工具应出现在某插件的默认工具面，更新 `PluginRegistry.builtIns` 对应插件的 `tools` 白名单。
4. 加单测：在 `AgentKernelTest` 用 `ScriptedLlm`（按调用序弹预设回复）断言 JSON 形状 / 列名解析 / 错误处理。

## 7. 如何加插件

- **内置插件**：在 `PluginRegistry.builtIns` 加一条 `PluginManifest`（id 恒定、不可被用户覆盖）。
- **用户插件**：把一段符合 §3.2 的 JSON 存到 `filesDir/plugins/<id>.json`（`PluginRegistry.import(text)` 也会这么存）。`kind` 选 `report`/`followup`/`review`/`editorial`；`tools` 白名单只写本插件需要的工具名（错名被忽略，不崩）。
- 选哪套：用户在 `PrefsStore` 里选引擎 id，`AgentEngine` 据此从注册表取；取到即用 agent 路径，取不到回落经典。

## 8. 如何加条线（editorial beats）

编辑部 = 报社机制的多 Agent：`editorial` 插件的 `beats` 是若干记者条线。每条 beat：

```json
{ "name": "分组与结构", "prompt": "你负责分组与结构条线…用 group_aggregate / get_cross_tab / drill_down 查证", "tools": ["get_overview","group_aggregate","get_cross_tab","drill_down"] }
```

- `EditorialDept.run` 并行跑各 beat（每人一个受限 `AgentLoop`），发现进**全社共享采访日志**；主笔（`writerPrompt`）汇编成稿；确定性 `verifyReport` 核查（硬伤退稿，最多 2 轮）；主编终审（`publish`/`退稿` 一次）。
- 约束：`beats` 1–6 条；`writerPrompt` 必填；主笔/记者提示词极窄、互不越界。

---

## 9. 测试运行

```cmd
set JAVA_HOME=C:\Users/<user>\.zcode\tools\jdk17
set PATH=%JAVA_HOME%\bin;C:\Users/<user>\.zcode\tools\gradle\bin;%PATH%
cd /d <本仓库根>\str-agent
gradle :str-agent:test --no-daemon
```

- 框架：`kotlin.test`（`testImplementation(kotlin("test"))`），Gradle 运行。
- **测试数据夹具**：已 vendoring 至仓库根 `fixtures/`（由上游 `the upstream host project` 的 tests/ 复制的快照，随仓库提交）。`AgentKernelTest.dataset()` 读取 `../fixtures/sample/superstore.csv`（相对于 **`str-agent` 模块目录**，即仓库根 `fixtures/`）。独立开发 / 换机器无需隔壁仓库——夹具随仓库自带，测试开箱即跑。
- 关键测试类：
  - `AgentKernelTest`：循环（工具回填 / 解析修复 / 预算强推 finish / cancel / 未知工具）、`submit_plan`/`record_finding`/`suggest_chart` 状态工具、缓存、清单解析往返与校验、`buildAnalysisTools` 的列名解析与 JSON 形状（superstore 金标：`rows=9994`、`density=1.0`、`Region` 列 `West` 合计 `725457.94`）。
  - `EditorialDeptTest`：主编选题 → 记者并行 → 主笔成稿 → 核查退稿 的流程。
- 用 `ScriptedLlm`（按调用序弹预设 JSON 回复）做确定性断言，不真调 LLM，因此 CI / 离线可跑。

---

## 10. 已知局限与约束

- **模块边界**：本模块不得 import `android.*` 或任何 GUI / 打包类依赖，也不得反向依赖 `host`/`data`（即不得 import `com.stragent.data.*`）（C‑02）。
- **测试夹具已内置**：见 §9，`fixtures/` 随仓库提交，无外部依赖。
- **`web_search` 需注入**：`buildAnalysisTools(dataset, brief, search = null)` 时 `web_search` 不出现；由宿主注入真实 `SearchClient` 才启用。
- **`finish` 前必跑 `audit_report`**：报告类任务若草稿没过 `verifyReport`，编排层/核查层会拒绝——这是确定性纪律，不是建议。
- 能力层契约（四条，方法名 `stragent_import_table` 等，由 str agent 独立定义）在 `data/` 模块，金标对拍见 `data` 的 `AbilityGoldenTest`，不在本模块。
