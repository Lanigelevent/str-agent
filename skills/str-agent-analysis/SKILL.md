---
name: str-agent-analysis
description: 用 Str Agent 做可信数据分析：导入表格 → 生成可溯源的中文报告 → 继续追问。报告里的每个数字都能回溯到原始行。也用于口语说法：分析这份表格、生成数据报告、这份数据有什么结论
---

# str-agent-analysis — Str Agent 能力入口

本技能是 **Str Agent 插件**的能力说明：能力本体在 MCP 服务器里（`serverName = str-agent`），
本技能只负责告诉宿主「有哪些工具、怎么按顺序用」。**不要自己算数**，所有数字都来自工具输出。

## 工具清单

**数据契约（4 条）**

| 工具 | 用途 | 关键参数 |
|---|---|---|
| `stragent_import_table` | 导入 CSV/TSV 文本，返回 `datasetId` | `csv`（文本）、`name` |
| `stragent_get_column_profile` | 列画像：类型 / 缺失 / 分位 / 去重数 / 高频值 | `datasetId`、`column` |
| `stragent_get_group_aggregate` | 按维度分组聚合（sum / avg）并给占比 | `datasetId`、`column`、`valueColumn`、`mode` |
| `stragent_list_rows` | 分页读原始行（带行号，便于溯源引用） | `datasetId`、`offset`、`limit` |

**插件能力（3 条）**

| 工具 | 用途 | 关键参数 |
|---|---|---|
| `stragent_generate_report` | 生成中文分析报告（章节化，数字可溯源） | `datasetId`、`useWeb` |
| `stragent_ask_followup` | 对同一份数据继续追问（自动带上此前会话对同一份数据的产出记录，回包里 `memoryFactsUsed` 说明带了几条） | `datasetId`、`question` |
| `stragent_recall_memory` | 只读召回：这份数据在此前会话里留过什么（报告要点 / 追问问答） | `datasetId`（缺省当前数据集）、`kind`（report / followup） |

> **记忆不是数据**：`stragent_recall_memory` 与追问自动带入的记忆都是**上一轮的复述**，
> 未经本次数据复核。里面的数字一律不许直接引用；要用就在本次数据上重新算。
> 记忆按数据集内容指纹存取 —— 数据改过一行就召不回，同内容跨进程可复现。

**策展分析工具（16 条，作用于会话当前数据集）**

`get_overview`（概况）、`get_column_profile`（列画像）、`group_aggregate`（分组聚合，参数 `dimension`/`metric`/`mode`）、
`get_outliers`（离群）、`scan_anomalies`（异常扫描）、`get_attribution_chain`（量价归因）、`get_group_yoy`（分组同比）、
`get_cross_tab`（交叉表）、`get_group_trend`（分组走势）、`get_correlations`（相关）、`drill_down`（下钻）、
`get_series`（序列）、`get_extremes`（极值）、`get_quality`（质量）、`get_report_context`（摘要上下文）、`audit_report`（确定性审计）

> 这些工具**绑定会话当前数据集**（最近一次 `stragent_import_table` 导入的那份），参数用**列名**（不是下标）。
> 先导入、再查数；不传 datasetId 也能用。

**大文件工具（2 条，tier-2）**

| 工具 | 用途 | 关键参数 |
|---|---|---|
| `stragent_aggregate_csv` | 对**本地大 CSV/TSV**做分组聚合（DuckDB 直读文件，数据不进内存；百万行亚秒级） | `csvPath`、`dimension`、`metric`、`mode`、`topN` |
| `stragent_profile_csv` | 对**本地大 CSV/TSV**做列画像（缺失率 / 分位 / 去重数 / 高频值） | `csvPath`、`column`（省略则全部列） |

> 只支持 `.csv` / `.tsv`；只读不写。普通表格仍走 `stragent_import_table`（会话内存，≤1 万行）。

**溯源复核（1 条）**

| 工具 | 用途 | 关键参数 |
|---|---|---|
| `stragent_provenance` | 取回本次分析的**溯源清单**并当场重放复核：用了哪份数据（datasetHash）、调了哪些工具与参数（steps）、重放一遍结果是否一致 | `datasetId`（缺省取当前数据集） |

> 结论要交付给用户前，先用它自证：数据一致 + 重放一致 → 才是「可复核」。

**受控计算（1 条，默认关闭）**

| 工具 | 用途 | 关键参数 |
|---|---|---|
| `stragent_compute` | 对当前数据集执行**受控表达式**：算术、比较、列引用 + 聚合（sum/avg/min/max/count/median/p25/p75/distinct）。例：`avg(销售额 / 订单数)`、`median(销售额 - 成本)`、`count(销售额 > 1000)` | `code`、`datasetId`（缺省当前） |

> **默认关闭**：需宿主显式授权（`compute.sandbox.enabled=true` 或环境变量 `STRAGENT_SANDBOX=1`）。
> 不支持函数定义/循环/IO/网络。每次执行产出计算清单，**可重放复核**。

**看图（1 条）**

| 工具 | 用途 | 关键参数 |
|---|---|---|
| `stragent_analyze_image` | 看本地图片并回答问题（默认）；`mode="extract_series"` 时把**图表反解成数据序列**并给出 CSV | `imagePath`、`question`、`mode` |

> 抽取模式下拿到的 CSV 可直接 `stragent_import_table` 导入 → 继续做分组聚合等分析，
> 于是**图里的数**与**表里的数**走同一条可溯源流水线。读不出的点会留空，不会被编造。

## 标准流程（照此顺序，别跳步）

1. **导入**：`stragent_import_table(csv=<文件全文>, name=<文件名>)` → 拿到 `datasetId`
   （幂等键 = 内容 sha256 前 12 位，同内容重复导入返回同一个 id）。
2. **摸底**：`get_overview` 看行列与类型；再 `get_column_profile(datasetId, column)` 逐列确认
   「哪列是维度、哪列是数值」。
3. **出报告**：`stragent_generate_report(datasetId)` —— 报告自带行业口径、章节结构与数字溯源。
4. **补证**（可选）：报告里的关键结论要落到具体行时，用 `group_aggregate` / `drill_down` /
   `get_series` 等策展工具取原始数字。
5. **追问**：用户接着问 → `stragent_ask_followup(datasetId, question)`，答案与报告同口径。
   同一份数据在**此前会话**里聊过什么会自动带进上下文（回包 `memoryFactsUsed` = 带了几条）；
   想先把历史摊开看，用 `stragent_recall_memory(datasetId)`。

## 纪律

- **不要自己算数字**：报告与追问里的每个数都应来自上述工具的输出；需要新数字先查工具。
- **数据集是会话内存**：MCP 进程重启后 `datasetId` 失效，需要重新 import（工具错误文案会提示）。
- **记忆按内容指纹存取**：重新导入**同一份内容**（sha256 相同）就能召回此前会话的产出；改过一行数据就召不回——
  这是设计，不是 bug。召回的记忆与追问自动带入的历史**都是上一轮的复述**，未经本次数据复核，
  里面的数字不许直接引用；要用就在本次数据上重算，或写进回答时注明「来自此前会话，本次未复核」。
- **规模边界**：单文件 10,000 行以内最稳（tier-1 内存路径）；**更大的文件用 `stragent_aggregate_csv` 直接指本地 CSV 路径**（列式引擎直读，数据不进内存）。
- **联网检索默认关闭**：`useWeb` 会引入不确定性与耗时，只有用户明确要「结合最新信息」才开。
- **要进度**：在 `stragent_generate_report` 的请求里带 `_meta.progressToken`，就能收到阶段进度通知（analyzing / chapter …），不必干等。

## 失败怎么办

- 「数据集不存在」→ 先 `stragent_import_table`（或进程重启过，需重新导入）。
- 「没有配置 xxx API Key」→ 宿主侧配置模型 Key（`STRAGENT_API_KEY` 环境变量优先，其次本机密钥文件）。
- 「未知工具」→ 用 `tools/list` 看当前 server 暴露的工具（能力面随版本增补）。
