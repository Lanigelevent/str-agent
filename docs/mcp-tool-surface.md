# MCP 工具面规范

> 快照日期：2026-11-21　|　默认形态工具总数：**44**（运行时实测；T-155/T-190 按部署形态裁剪——
> ①沙箱未开时受控计算两档 `stragent_compute` / `stragent_compute_python` 不下发；②**视觉未就绪（未配视觉 Key）时 `stragent_analyze_image` 不下发**（2026-11-13 起生效）；③**db 未授权（未设 `STRAGENT_DB_TOOLS=1` 且 prefs 未开 `tools.db.enabled=true`）时 db_* 6 条不下发**（T-190，2026-11-21 起生效）。
> 形态矩阵：默认 44 / 沙箱开启 46 / 视觉就绪 45 / 双就绪 47 / db 授权 50 / 三就绪 53（= 注册面全量）。
> 数据来源：`gradlew :host:run --args="--list-tools"`（走与 MCP 客户端相同的 `tools/list` 分发路径）
> 本文档由运行时目录生成，是工具面的**快照**；唯一事实源是代码注册表，两者不一致时以运行时为准。
>
> 安全边界：对外工具面**不含** `web_search` / `web_fetch` 等网络型工具（`AnalysisToolSession` 显式过滤）。
>
> 契约字段（T-053 / T-056）：53/53 下发 `annotations`；53/53 下发 `outputSchema`（**全部为宽松对象 schema**，
> 无严格逐字段 schema——严格 schema 会随工具结果变化而失效），`tools/call` 一律双发 `structuredContent`；
> 目录只公布 `stragent_` 规范名。16 个历史裸名的兼容别名已于 **1.1.0 退役**（旧名调用返回 -32602 并附规范名指引）。

## 总览

| 分组 | 数量 |
|------|------|
| 一、数据契约 | 4 |
| 二、报告、记忆与语义 | 9 |
| 三、统计计算与可视化 | 8 |
| 四、大文件流式处理 | 2 |
| 五、导入导出 | 6 |
| 六、数据库 | 6 |
| 七、溯源 | 1 |
| 八、会话内分析工具 | 16 |
| 九、人机交互 | 1 |
| **合计** | **53** |

## 一、数据契约（4）

### `stragent_import_table`

> 导入一段分隔文本表格（CSV/TSV，自动探测分隔符，支持引号转义）并在本机完成统计分析，返回数据集编号与列清单。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `csv` | string | ✅ | 表格文本，第一行为表头。缺省处理: 必填，无默认值。例值: "门店,销售额\n东店,1200" |
| `name` | string |  | 数据集名称。缺省处理: 未传按「未命名数据」。例值: 10月销售明细 |

### `stragent_get_column_profile`

> 返回数据集某列或全部列的统计画像。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `datasetId` | string | ✅ | 数据集编号。缺省处理: 必填，无默认值。例值: ds_1a2b3c4d5e6f |
| `column` | string |  | 要画像的列名（须与表头一致）。缺省处理: 未传时返回全部列概要。例值: 销售额 |

### `stragent_get_group_aggregate`

> 按一个文本维度列对一个数值指标列做分组聚合（求和或平均），返回各组的值、样本量与占比。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `datasetId` | string | ✅ | 数据集编号。缺省处理: 必填，无默认值。例值: ds_1a2b3c4d5e6f |
| `dimension` | string | ✅ | 分组维度列名（文本列）。缺省处理: 必填，无默认值。例值: 门店 |
| `metric` | string | ✅ | 聚合指标列名（数值列）。缺省处理: 必填，无默认值。例值: 销售额 |
| `mode` | string（sum / avg） |  | 聚合方式。缺省处理: 未传按 sum。 |
| `limit` | number |  | 返回组数上限，超出折叠为「其他 N 项」。缺省处理: 未传按 8，上限 50。例值: 10 |

### `stragent_list_rows`

> 分页返回数据集的原始数据行，用于核对统计结论或引用具体记录。
> 大文件抽样数据集额外返回 `items[].sourceRow`（源文件数据行号）与 `sampling` 块（步长 / 源文件行数 / 文件指纹）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `datasetId` | string | ✅ | 数据集编号。缺省处理: 必填，无默认值。例值: ds_1a2b3c4d5e6f |
| `offset` | number |  | 起始偏移。缺省处理: 未传按 0。例值: 50 |
| `limit` | number |  | 单页行数。缺省处理: 未传按 10，上限 50。例值: 20 |

## 二、报告、记忆与语义（9）

### `stragent_generate_report`

> 基于已导入的数据集生成可信中文分析报告：章节化结构 + 每个数字可回溯到原始行。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `datasetId` | string | ✅ | stragent_import_table 返回的 datasetId |
| `useWeb` | boolean |  | 是否联网检索（默认 false；联网会增加耗时与不确定性） |
| `reportType` | string |  | 报告类型覆盖（T-105，可选）：standard/executive/bulletin/deepdive/compare/anomaly/trend/quality；缺省用偏好设置 |
| `style` | string |  | 文风覆盖（T-105，可选）：professional/concise/indepth/executive/friendly/teaching/detective/news |
| `industry` | string |  | 行业框架覆盖（T-105，可选）：47 项行业 id（general/finance/civil 等）；缺省用偏好设置 |
| `depth` | string |  | 详细程度覆盖（T-105，可选）：brief（篇幅减半）/standard（默认）/deep（1.75 倍且分章更细）；仅篇幅轴，与报告类型正交 |
| `audience` | string |  | 受众画像（T-105，可选，≤300 字）：写给谁，术语深度按此校准 |
| `requirements` | string |  | 附加输出要求（T-105，可选，≤2000 字）：总结口径等自由要求；优先级高于类型/行业/文风，但不得突破零编造与数字白名单硬约束 |
| `profile` | string |  | 预设 bundle（T-106，可选）：内联 JSON 一次给出多参（如 `{"depth":"deep","audience":"施工方项目经理"}`）；显式参数优先于 bundle 值；bundle 值同样过枚举/长度校验 |
| `language` | string |  | 报告输出语言档位（T-173，可选）：zh（缺省，中文）/ en（英文）。切换语言时写作纪律、结构规范与写作审计的词表同步切换；数据里的列名与专有名词保留原样 |

T-105 输出适配（按调用覆盖）：六个可选参数缺省全部回退用户偏好设置（PrefsStore 持久预设层），传入即按调用覆盖（无效 id 中文报错列合法值）；全部缺省时行为与历史版本逐字节一致。回执附 `outputProfile`（T-108，恒输出）：`{profile?: 使用的预设名, overrides: 实际生效的覆盖键值对}`——调用方能分辨「参数生效了」与「走了偏好缺省」。

回执附 `charts`（T-089，恒输出）：报告链建议的图表数据数组，每项 `{type, title, description?, labels, values}`（labels 与 values 对齐，type 为 bar/line/pie/scatter 等图表类型 id）——客户端可据此自行渲染；**空数组也是输出**（「报告无图表」与「回执没带图表」是两种事实）。classic 引擎恒为空数组（图表建议是 agent 循环 `suggest_chart` 的产物）；`/ui` 驾驶舱的报告视图对同一份数据离线渲染 SVG（T-086）。

回执附 `engineFallback`（T-091，恒输出）：`true` = 用户所选引擎未被注册表承接（kind 不被支持/插件已删），本次报告实际按经典流水线生成——降级事实如实到达客户端，`engineFallbackReason`（仅回落时带出）说明原因；`false` = 所选引擎正常执行或本就使用 classic。

### `stragent_ask_followup`

> 对已生成报告的数据集继续追问：回答基于同一份数据与报告垫底，口径与报告一致。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `datasetId` | string | ✅ | stragent_import_table 返回的 datasetId |
| `question` | string | ✅ | 追问内容（自然语言） |
| `audience` | string |  | 受众画像（T-106，可选，≤300 字）：写给谁；非空时注入 persona 块校准回答的术语深度与背景交代 |

回执附溯源归因（T-073）：`provenanceRef`（数组，每项 `{step, tool, matched}`——step 是 `stragent_provenance` steps 数组的 0 起下标，matched 是回答引用且在该步骤入参/结果数值面里出现过的数字）+ `provenanceNote`（归因口径与状态说明；未接线 / 无留痕 / 未命中 / 命中四种事实如实区分，命中时按 note 指引重放对应步骤复核）。

回执附 `subQueries`（T-113，恒输出）：本次追问实际生效的查询分解子查询数组，空表 = 未启用/未分解——调用方据此分辨「检索走了分解」与「单查询直出」。分解由宿主 env `STRAGENT_TEXT_QUERY_DECOMP=1/on` 显式开启（默认关）：开启后复合问题先拆 2-4 个子查询逐路检索，与原始查询的排名 RRF 融合（跨列表互证段升头部，单查询召不回的第二跳源由此进窗）；corrective 拒答标准不因分解放松。回执 `totalTokens` 含回答与改写/分解等轻调用之和（成功路径如实入账）。

回执附 `chunkContextCount`（T-114，恒输出）：本次检索索引中获得上下文补语的块数（0 = 未启用/全失败）。补语由宿主 env `STRAGENT_TEXT_CONTEXTUAL=1/on` 显式开启（默认关）：开启后每个材料块先经轻调用生成一句「放回文档全局语境」的补语（5 块/批，内容寻址缓存后零重复调用），拼进**检索索引文本**（「段N {补语} {原文}」）——BM25 词法与嵌入语义两路同时受益（Anthropic Contextual Retrieval，行业数据单项 35-49% 失败率降幅）。**索引专用纪律**：补语绝不进回答材料与门禁 brief（生成物数字不是数据），回答引用仍以原文「段 N」为锚。

回执附检索粒度说明（T-116/T-117）：文本追问检索默认**多粒度双层**——细层（句组 top-K）+ 粗层（~4000 字聚合块，最优块整块回填）；宿主开启 `STRAGENT_TEXT_SUMMARY_TREE=1` 时粗层索引文本升级为 LLM 摘要节点（RAPTOR 第一级，索引专用、回填单位不变），开启 `STRAGENT_TEXT_CONTEXTUAL=1` 时细层索引文本带上下文补语。两者均可 env 关闭/开启，检索行为差异见 D7/D4。

### `stragent_save_profile`

> 保存命名输出预设（T-107）：bundle 校验通过后落库，此后 `stragent_generate_report` 的 `profile` 参数传名即可展开（Langfuse name+label 完整形态）。prefs 未接线时不注册。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | ✅ | 预设名（字母/数字/下划线/连字符/中文，≤64 字符） |
| `bundle` | string | ✅ | 预设 JSON：`{"reportType":..,"style":..,"industry":..,"depth":..,"audience":..,"requirements":..}`（键均可选，值过枚举/长度校验） |

### `stragent_list_profiles`

> 列出已保存的命名输出预设（T-108，只读）：返回每个预设的 name 与 bundle，可把 name 传给 `stragent_generate_report` 的 `profile` 参数。prefs 未接线时不注册。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| （无参数） |  |  | 空表恒输出 count=0 与保存指引（「没有已存预设」≠「回执没带清单」） |

回执：`count` + `profiles`（数组，每项 `{name, bundle}`）。

### `stragent_recall_memory`

> 查询这份数据在此前会话里留下的产出记录（报告要点与追问问答）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `datasetId` | string |  | 缺省用会话当前数据集（最近一次导入）；显式传则指定 |
| `kind` | string |  | 只看某类：report / followup，缺省全部 |

### `stragent_list_semantic_terms`

> 查询已定义的指标口径与业务约束（跨数据集沉淀，不随数据更换而丢失）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string |  | 按术语名过滤（精确匹配，缺省全部） |

### `stragent_list_metrics`

> 查询指标定义（内置 10 个销售/财务指标 + 用户自定义）：名称、别名、表达式、单位、维度、粒度、自检与版本。
> 传 `datasetId` 时额外标注每个定义在该数据上的解析结果（字段落到哪一列 / 为什么解析不了）。
> **定义不是数据**：要数字请用 `stragent_evaluate_metric`。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `datasetId` | string |  | 可选：按这份数据标注可解析性（缺省不标注） |
| `name` | string |  | 按指标名/别名过滤（缺省全部） |

### `stragent_define_metric`

> 保存一条指标定义（跨数据集生效）：名称 + 表达式 + 聚合/单位/维度/粒度/自检。
> 表达式用数据列名（可缺省 agg 自动包成 `sum(...)`），也可直接写聚合表达式（如 `sum(销售额) / distinct(订单号)`）。
> 同名/别名相交但口径不同会被拒绝并说明冲突；修改已有定义请带原 id。定义保存后，报告摘要会自动引用可解析且自检通过的指标值。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | ✅ | 指标名（如「净销售额」） |
| `expression` | string | ✅ | 字段表达式或完整聚合表达式（列名需与数据一致） |
| `agg` | string |  | 裸表达式的聚合方式，缺省 `sum`（可用：sum/avg/min/max/count/median/p25/p75/distinct） |
| `unit` | string |  | 单位（元/件/% 等），缺省空 |
| `definition` | string |  | 一句话口径说明（给人看） |
| `aliases` | array |  | 别名列表（按名查找与冲突检测用） |
| `dimensions` | array |  | 允许的分组维度（规范名；空=不限制） |
| `grain` | string |  | 时间粒度：total/month/quarter/year，缺省 total |
| `tests` | array |  | 自检列表：`nonNegative` / `notZero` / `min:0` / `max:100` / `range:0:100` |
| `owner` | string |  | 负责方，缺省 user |
| `id` | string |  | 修改已有定义时传原 id；新建可省略 |
| `fields` | object |  | 规范字段名 → 别名数组（跨数据集解析列名用） |

### `stragent_evaluate_metric`

> 按指标定义对已导入的数据集求值（确定性、可重放）：返回数值、单位、实际表达式、字段映射与自检结果；
> 可按维度分组（`groupBy`）或按时间粒度（month/quarter/year）分时。
> 定义解析不到列时直接报错并说明原因，不会用相近列顶替。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `metric` | string | ✅ | 指标名或别名（内置/用户定义均可） |
| `datasetId` | string |  | 数据集编号（缺省取会话当前数据集） |
| `groupBy` | string |  | 分组维度（须在指标声明的维度内） |
| `grain` | string |  | 时间粒度：total/month/quarter/year（缺省用指标定义的粒度） |
| `topN` | integer |  | 分组返回的明细条数，缺省 8 |

## 三、统计计算与可视化（6）

### `stragent_forecast`

> 对已导入的数据集做时序预测：自动选择指数平滑、线性趋势或季节性（Holt-Winters 加法）法，取样本内 SSE 最小者，生成指定期数的预测值与 95% 置信区间。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `datasetId` | string |  | 数据集编号（缺省取会话当前数据集） |
| `valueColumn` | string |  | 要预测的数值列名（缺省取第一个数值列） |
| `dateColumn` | string |  | 日期列名（缺省自动探测；提供则按日期排序后预测） |
| `horizon` | integer |  | 预测期数（1-24，缺省 3） |
| `seasonPeriod` | integer |  | 季节周期长度（如月度=12，季度=4；缺省 12。数据不足 2 个周期时自动回落非季节性方法） |

### `stragent_whatif`

> 对已导入的数据集做场景模拟（what-if）：对指定列施加变换（乘/加/设值），重算关键统计量，输出变换前后的对比与变化幅度。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `datasetId` | string |  | 数据集编号（缺省取会话当前数据集） |
| `column` | string |  | 要变换的数值列名（缺省取第一个数值列） |
| `operation` | string |  | 变换操作：multiply（乘）/ add（加）/ set（设为），缺省 multiply |
| `factor` | number | ✅ | 变换因子：multiply=乘数（1.1=涨10%）、add=加数、set=设值 |
| `groupBy` | string |  | 分组列名（缺省=全局统计，不分组） |

### `stragent_render_chart`

> 对已导入的数据集生成 SVG 图表和 ECharts JSON option（双轨输出：SVG 静态图可直接嵌入 Markdown，ECharts JSON option 供浏览器交互式渲染——悬停/缩放/动画）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `datasetId` | string |  | 数据集编号（缺省取会话当前数据集） |
| `chartType` | string（bar / line / pie / scatter / heatmap / histogram / boxplot / radar） |  | 图表类型：bar（柱状图）/ line（折线图）/ pie（饼图）/ scatter（散点图）/ heatmap（热力图）/ histogram（直方图）/ boxplot（箱线图）/ radar（雷达图），缺省 bar。SVG 静态图 + ECharts JSON option 双轨输出 |
| `categoryColumn` | string |  | 分类列名（柱状图/折线图/饼图的 X 轴或分组维度；散点图忽略） |
| `valueColumn` | string | ✅ | 数值列名（柱状图/折线图/饼图的 Y 值；散点图的 Y 轴） |
| `xColumn` | string |  | 散点图 X 轴列名（仅散点图） |
| `mode` | string |  | 聚合方式：sum 或 avg（柱状图/折线图/饼图分组时使用），缺省 sum |
| `title` | string |  | 图表标题（缺省用列名自动生成） |
| `topN` | integer |  | 柱状图/折线图/饼图显示的最大分类数（超出折叠为「其他」），缺省 10 |

### `stragent_regression`

> 对已导入的数据集做一元线性回归（最小二乘法）：y = intercept + slope * x。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `datasetId` | string |  | 数据集编号（缺省取会话当前数据集） |
| `xColumn` | string | ✅ | 自变量列名（数值列），必填 |
| `yColumn` | string | ✅ | 因变量列名（数值列），必填 |

### `stragent_hypothesis_test`

> 对已导入的数据集做假设检验。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `datasetId` | string |  | 数据集编号（缺省取会话当前数据集） |
| `testType` | string | ✅ | 检验类型：t-test（独立样本 t 检验）/ chi-square（卡方独立性检验），必填 |
| `groupColumn` | string |  | t-test 的分组列名（文本列）；chi-square 的行维度列名 |
| `valueColumn` | string |  | t-test 的数值列名（数值列）；chi-square 不需要 |
| `columnColumn` | string |  | chi-square 的列维度列名（文本列）；t-test 不需要 |
| `groupA` | string |  | t-test 比较的分组 A 值（缺省取第一个分组值） |
| `groupB` | string |  | t-test 比较的分组 B 值（缺省取第二个分组值） |

### （条件下发）`stragent_analyze_image`

> 看**本地图片**并回答问题（默认），或把图表**反解成数据序列**（mode=extract_series）。
> 下发条件：已配置视觉 Key（`isAvailable()`=有 Key 且有模型名）；Key 经 prefs 运行时配置后，下一次 tools/list 即恢复下发。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `imagePath` | string | ✅ | 本地图片路径（.png / .jpg / .jpeg / .webp / .gif / .bmp） |
| `question` | string |  | 要问的问题（describe 模式；抽取模式忽略） |
| `mode` | string |  | describe（默认，看图回答）或 extract_series（图表反解成数据） |

## 四、大文件流式处理（2）

### `stragent_aggregate_csv`

> 对**本地 CSV/TSV 文件**做分组聚合（自动切档：小文件走 tier-1 内存引擎，超过 10000 行走 tier-2 列式引擎，数据不进内存）：按文本维度列对数值指标列分组，返回各组的值、样本量与占比。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `csvPath` | string | ✅ | 本地 CSV/TSV 文件的绝对路径（只支持 .csv / .tsv） |
| `dimension` | string | ✅ | 维度列名（表头原文，如「地区」） |
| `metric` | string | ✅ | 指标列名（表头原文，如「销售额」） |
| `mode` | string |  | 聚合方式：sum 或 avg（默认 sum） |
| `topN` | number |  | 返回前 N 组，其余合并为「其他 N 项」（默认 8） |
| `engine` | string |  | 档位：auto（默认，按行数自动切）/ memory（tier-1，报告路径口径）/ columnar（tier-2，需要 DuckDB 原生库） |

### `stragent_profile_csv`

> 对**本地 CSV/TSV 文件**做列画像（按规模自动切档：小文件 tier-1 内存引擎，超过 10000 行 tier-2 列式引擎，数据不进内存）：数值列给 count/missing/min/max/sum/mean/median/p25/p75，文本列给 count/missing/distinct/topValues。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `csvPath` | string | ✅ | 本地 CSV/TSV 文件的绝对路径（只支持 .csv / .tsv） |
| `column` | string |  | 列名（表头原文）；省略则画像全部列 |
| `engine` | string |  | 档位：auto（默认，按行数自动切）/ memory（tier-1，报告路径口径）/ columnar（tier-2，需要 DuckDB 原生库） |

## 五、导入导出（6）

### `stragent_import_file`

> 导入 csv / tsv / txt / jsonl / ndjson / parquet / xlsx / ods / fods / docx 文件或 `s3://` / `gs://` / `https://` 远程对象为数据集；大 CSV/JSONL 自动做确定性系统抽样并回报文件指纹、行号映射、编码与 schema 推断理由。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `filePath` | string | ✅ | 本地文件路径或远程只读地址（.csv / .tsv / .txt / .jsonl / .ndjson / .parquet / .xlsx / .xls / .ods / .fods / .docx；远程需带可识别扩展名），必填 |
| `name` | string |  | 数据集名称（缺省为文件名） |

### `stragent_import_pdf`

> 导入 PDF 文件为数据集。文本层 PDF 直取；**扫描件在宿主开启 OCR（env `STRAGENT_OCR=1`，默认关）时逐页视觉转写兜底**（T-115：每页一次视觉调用，页数上限 `STRAGENT_OCR_MAX_PAGES` 默认 100，超出报错指引调高），回执 `ocr` 如实区分文本层与转写来源；转写文本是生成物，其数字同样过下游门禁。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `filePath` | string | ✅ | PDF 文件路径，必填 |
| `name` | string |  | 数据集名称（缺省为文件名） |

回执附 `ocr`（T-115，恒输出）：`true` = 文本层抽取失败后走了逐页视觉转写；`false` = 文本层直取。

### `stragent_import_text`

> 导入纯文本 / 混合文档为**文本型数据集**（T-096）：txt / md 直读；docx 按文档顺序全量抽取（正文段落 + 全部表格：最大表进表格部分，其余表以 Markdown 投影进文本层，不丢数据）；pdf 优先取文本层，扫描件在宿主开启 OCR（STRAGENT_OCR=1）时逐页视觉转写兜底（T-115）。回执含 kind（text/mixed/table）、段数、投影数、注入扫描与 ocr（来源如实区分）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `filePath` | string | ✅ | 文件路径（.txt / .md / .docx / .pdf；也支持 s3:// / gs:// / https://），必填 |
| `name` | string |  | 数据集名称（缺省为文件名） |

### `stragent_summarize_text`

> 总结文本/混合数据集的正文材料（T-096）。三层路由自动：stuff（≤30K 字直通）/ map-reduce（逐块要点→归并）/ 层级归并（>40 块先节后总）。quote-first：要点必带 ≤50 字原文短引用与段锚（段 N）；数字白名单只来自要点，归并不允许清单外数字；全部块失败报错不编造。混合数据集只总结正文。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `datasetId` | string |  | 显式数据集 id（缺省为会话当前数据集） |
| `focus` | string |  | 分析视角（非空时按 BM25 相关度重排要点） |
| `maxWords` | integer |  | 目标篇幅字数上限（默认 800，100–2000） |
| `maxPoints` | integer |  | 回执要点条数上限（verbosity，默认 40，长上下文模型可传 9999 取全量） |
| `audience` | string |  | 受众画像（T-105，可选，≤300 字）：写给谁；非空时注入 persona 块并参与要点重排 |
| `requirements` | string |  | 附加输出要求（T-105，可选，≤2000 字）：总结口径等自由要求；与 focus 正交（focus=看什么，本参=怎么写） |
| `depth` | string |  | 详细程度（T-106，可选）：brief（缺省 400 字）/standard（800）/deep（1500）；显式 maxWords 优先于 depth 缺省 |

### `stragent_read_segments`

> 按段号区间读取文本/混合数据集的正文原文（T-098）。两个用途：①下钻 summarize_text / generate_report 回执中的「段 N」锚点；②核对回执引用（quote）是否被原文支撑（验证循环的取证端）。段号即分页游标，返回 nextFrom 供续读；单页 ≤20 段 / 8000 字符，超出如实标注。只读。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `datasetId` | string |  | 显式数据集 id（缺省为会话当前数据集） |
| `from` | integer |  | 起始段号（1 起始，含），缺省 1 |
| `to` | integer |  | 结束段号（含），缺省 = from + 页大小 - 1 |

### `stragent_export_report`

> 将报告内容导出为可分享文件：Excel（.xlsx 多工作表）/ Word（.docx）/ PDF（.pdf）/ PowerPoint（.pptx）/ 交互式 HTML 仪表盘 / 离线落地页。Excel：首表「报告概要」，每章一张工作表；Word/PDF/PPT：标题 + 章节 + 段落 + 真 Markdown 表格（PPT 为原生表格），可渲染的图表**内嵌为图片**。HTML：单文件离线可读（无 CDN），图表内联 SVG + 内嵌交互（悬停 tooltip、点图例开合系列）；landing：报告的对外门面（hero + 分章 + 图表，离线单文件）。用途：将分析报告导出为可分享的文件。输出：excel/docx/pdf/pptx 返回文件路径，html/landing 返回完整 HTML 文本。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `format` | string（excel / docx / pdf / pptx / landing / html） |  | 导出格式：excel（真 .xlsx 多工作表）、docx（.docx，Word）、pdf（.pdf）、pptx（.pptx，PowerPoint）、landing（离线落地页）、html（交互式单文件仪表盘），缺省 html |
| `title` | string | ✅ | 报告标题 |
| `subtitle` | string |  | 副标题（仅 landing 格式用；通常是数据集名或口径说明） |
| `sections` | array |  | 章节数组，每个章节含 title（标题）、content（Markdown 内容）、chartOptions（可选，ECharts JSON option 数组；HTML 中渲染为交互式 SVG，docx/pdf/pptx 中可渲染的图表内嵌为图片，其余如实注明未嵌入） |

## 七、溯源（1）

### `stragent_provenance`

> 取回本次分析的**溯源清单**并当场重放复核：用了哪份数据（datasetHash）、调了哪些工具与参数（steps）、重放一遍结果是否一致（replay）。  steps 每项可带 `numTokens`（T-073 数值面：该步入参/结果里出现过的去重归一数字 token，上限 64；缺失 = T-073 之前的旧步骤）——追问回执 `provenanceRef` 的 matched 就是对这份面的对拍结果，可自行复核。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `datasetId` | string |  | 数据集编号（缺省取会话当前数据集） |

## 八、会话内分析工具（16，随当前数据集动态注册）

### `stragent_session_overview`

> 返回数据概况：行数、全部列名与推断类型、有效行数与单元格密度。

_无参数。_

### `stragent_session_column_profile`

> 单列统计画像：数值列为 {count, missing, min, max, sum, mean, median, p25, p75}（count 是实际参与统计的样本数，missing 含空值与脏值）；文本列为 {count, missing, distinct, topValues}。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `column` | string | ✅ |  |

### `stragent_session_group_aggregate`

> 按文本维度列对数值指标列分组聚合（sum 或 avg），返回各组的值、样本量与占比（share 分母在 denominator 写明；avg 或存在负值时 share 为 null）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `dimension` | string | ✅ |  |
| `metric` | string | ✅ |  |
| `mode` | string |  |  |
| `limit` | number |  |  |

### `stragent_session_outliers`

> IQR 异常值检测（1.5×IQR）：指定数值列返回该列的异常行（行号+值+偏离 IQR 倍数+正常区间），不传 column 返回全部数值列的概要。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `column` | string |  |  |

### `stragent_session_anomalies`

> 六类时序/结构异常扫描（时序突变/结构突变/时间缺口/主键重复/负值/序列塌陷），返回发现清单（severity/kind/贡献者/证据行号）与覆盖情况。

_无参数。_

### `stragent_session_attribution_chain`

> 某期的时序突变归因链：哪个维度值贡献最大 → 组内哪个子维度 → 量价分解（Δ合计 = Δ笔数×笔均前 + 笔数后×Δ笔均，无残差）与结论。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `period` | string | ✅ |  |

### `stragent_session_group_yoy`

> 分组同比：整体与各组的同比并排，标记拖累组（落后整体 ≥5pp）与样本不足组（<3 行）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `dimension` | string | ✅ |  |
| `metric` | string | ✅ |  |

### `stragent_session_cross_tab`

> 维度交叉：主维度 × 自动挑的第二维度，各格的合计/笔数/行均（行均=合计÷笔数，配产出率判定），并给出「行数最多与行均最高是否同一格」的结论。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `dimension` | string | ✅ |  |

### `stragent_session_group_trend`

> 分组走势：滚动 12 期基线，连续 ≥3 期低于基线 30% 才报「走弱」（只报低于侧，防恢复期误报）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `dimension` | string | ✅ |  |
| `metric` | string | ✅ |  |

### `stragent_session_correlations`

> 数值列两两皮尔逊相关（\|r\| 降序）：r、样本量 n、强度分档。

_无参数。_

### `stragent_session_drill_down`

> 分类下钻：某维度值的合计/笔数/单均（与全体单均对照）、组内第二维度分布（占比分母=该组合计）、前 20 行明细（带原始行号）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `dimension` | string | ✅ |  |
| `metric` | string | ✅ |  |
| `group` | string | ✅ |  |
| `mode` | string |  |  |

### `stragent_session_series`

> 两列构建序列（x 列顺序即数据行序，y 为数值列），最多 120 点——用于看趋势与峰谷。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `x` | string | ✅ |  |
| `y` | string | ✅ |  |

### `stragent_session_extremes`

> 关键数值列的最值定位：最大/最小值落在哪个组、哪一期、第几行（最多 3 列）。

_无参数。_

### `stragent_session_quality`

> 数据质量：空值率、重复行、每列缺失数、一致性形态、常数列——影响结论可信度的部分。

_无参数。_

### `stragent_session_report_context`

> 取数据摘要（brief）的指定段落：按关键词筛选（如「分组」「月度」「异常」），返回命中的段落文本——按需取上下文，不整段塞入。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `query` | string |  |  |

### `stragent_session_audit_report`

> 报告草稿自检（写完 finish 前必调）：逐个数溯源校验。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `report` | string | ✅ |  |

## 九、人机交互（1）

### `stragent_elicit_input`

> 向用户提一个结构化问题并等待回答（MCP elicitation）：适合长任务中途缺失、且只有用户才知道的信息（报告标题、指标口径、是否覆盖已有文件）。返回 `action=accept`（带 `content`）/ `decline`（用户拒绝回答）/ `cancel`（无通道或超时，带 `reason`）。**拿不到回答就按 cancel 的语义继续工作，不要反复重试**。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | string | ✅ | 给用户看的问题：一句话说清要什么、为什么需要 |
| `fields` | object |  | 请求回答的字段 Schema：字段名 → `{type: string\|number\|integer\|boolean, title?, description?, enum?}`；缺省只问一个自由文本字段 `value`。只接受平铺原始类型（嵌套对象/数组客户端无法渲染） |
| `required` | array<string> |  | 必答字段名（缺省为 `fields` 的全部） |
| `timeoutMs` | number |  | 等待上限（毫秒，缺省 60000，范围 1000..300000） |

传输前提：stdio 在 `serve()` 期间自动可用；HTTP 需要客户端先建立 `GET /mcp` 服务端消息流。
没有通道时**立刻**返回 `cancel`（`channelAvailable=false`），不阻塞、不假装成功。

---

## 契约字段（T-053 / AD-025）

支持两轮（T-077）：可选参数 `followUp`（`{message, fields?, required?}`，与第一轮同构）——第一轮 `accept` 后以**同一 pending id** 续问第二轮（先验参再提问；decline/cancel 不追问）。回执增 `rounds`（两轮时 =2）与 `firstContent`（第一轮 accept 的内容）。

### 命名：对外一律 `stragent_` 前缀

`tools/list` 只公布规范名。16 个历史裸名的兼容别名已在 **v1.1.0（2026-10-31）退役**
（T-053 承诺的「一版兼容期后删除」）：旧裸名调用返回 -32602，错误信息附对应规范名；
迁移方式 = 把调用名换成下表的规范名（映射关系不变，只是不再接受旧名）。

| 旧裸名（已退役） | 规范名（唯一可用） |
| `get_overview` | `stragent_session_overview` |
| `get_column_profile` | `stragent_session_column_profile` |
| `group_aggregate` | `stragent_session_group_aggregate` |
| `get_outliers` | `stragent_session_outliers` |
| `scan_anomalies` | `stragent_session_anomalies` |
| `get_attribution_chain` | `stragent_session_attribution_chain` |
| `get_group_yoy` | `stragent_session_group_yoy` |
| `get_cross_tab` | `stragent_session_cross_tab` |
| `get_group_trend` | `stragent_session_group_trend` |
| `get_correlations` | `stragent_session_correlations` |
| `drill_down` | `stragent_session_drill_down` |
| `get_series` | `stragent_session_series` |
| `get_extremes` | `stragent_session_extremes` |
| `get_quality` | `stragent_session_quality` |
| `get_report_context` | `stragent_session_report_context` |
| `audit_report` | `stragent_session_audit_report` |

## 维护方式

1. 工具注册表变更后，重新执行 `gradlew :host:run --args="--list-tools"` 获取最新目录；
2. `--list-tools` 与 HTTP/stdio 的 `tools/list` 共用 `McpBridge.dispatch`，不会出现两套口径；
3. 本文件不再由脚本静态枚举源码生成——历史上那份 `mcp-tools.json` 曾列出未注册的 `web_search` 与错误参数名，已删除。
