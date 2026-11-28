package com.stragent.core.report

import com.stragent.core.ports.PrefsStore

/**
 * 专业成文规范 —— report-structure.ts 移植。
 *
 * 与写作纪律的分工：writing-rules 管"读起来像人写的"（句法），
 * 本模块管"读起来像专业交付物"（形态：速览表 / 适用范围 / 待办清单 / 口径纪律）。
 */

/**
 * 允许使用章节级专业形态的报告类型。
 * 一页纸执行摘要与数据快讯不注入：它们的铁律是 300–400 字、禁小标题，
 * 塞一张速览表进去等于把体裁改掉。
 *
 * 名单本身在 [ReportTypeRegistry]（T-058）：新增类型只登记 profile，不改本函数。
 */
/** 当前报告类型是否该注入专业成文规范 */
fun needsStructureBlock(prefs: PrefsStore): Boolean = isStructuredReportType(ReportLib.getReportType(prefs).id)

/** 报告类型 id 是否属于结构化类型（速览表 / 适用范围 / 未答问题 / 依据标注的适用面） */
fun isStructuredReportType(
    typeId: String,
    registry: ReportTypeRegistry = ReportTypeRegistry.BUILT_IN,
): Boolean = registry.profileOf(typeId).structured

/**
 * 专业成文规范块。四段，按报告里的出现顺序排列（速览表 → 适用范围 →
 * 待办清单 → 口径与依据），模型按顺序写比事后补更容易写对。
 *
 * **篇幅是硬约束**：system 指令越长，模型对每一条的遵循度越低。
 */
fun professionalStructureBlock(language: ReportLanguage = ReportLanguage.ZH): String = when (language) {
    ReportLanguage.ZH -> professionalStructureBlockZh()
    ReportLanguage.EN -> professionalStructureBlockEn()
}

private fun professionalStructureBlockZh(): String = listOf(
    "",
    "<key_metrics_table>总体概览的结论段之后放一张 Markdown 速览表，列固定四列：**指标 | 全期值 | 最值定位 | 变化或占比**。",
    "· 指标一律用数据里的列名；全期值写清是合计还是均值；",
    "· 最值定位填对象与时间（\"CYSW1-2，2021-10-15\"／\"第 812 行\"）——摘要给了定位就必须填，填不出的列不要进这张表；",
    "· 变化或占比必须写算式（\"(83,829 − 18,542) ÷ 18,542 = +352.1%\"）；",
    "· 最多 8 行，按重要性排序，不要把每个数值列都列一遍；表后一句话说明这几列是怎么选出来的。</key_metrics_table>",
    "",
    "<scope_limit>总体结论之后紧跟一段「适用范围」，三段都要有：",
    "① **判定对象**：整体还是子集、覆盖多少样本（\"基于 9,929 条有销售额记录的订单\"）；",
    "② **前提条件**：口径与零点定义（未剔除极值／以首期观测值为零点／仅统计有点号的行）——摘要声明的数据结构提示必须在这里复述一遍；",
    "③ **不覆盖的部分**：哪些列或分组没进结论、为什么。",
    "没有这一段，读者会把子集结论当成全量结论。每段都要有数据依据，写不出依据的边界就不要写。</scope_limit>",
    "",
    "<open_questions>末尾必须有「## 数据未能回答的问题」，列 3–5 条。每条 = 问题 + 缺什么 + 补上什么之后这条就能答。",
    "缺的是字段、样本量还是口径（没有预警值就无法判超限、没有去年同期就不能说同比）。",
    "不要写成\"缺字段清单\"，也不要把报告里已经答过的问题列进来凑数。</open_questions>",
    "",
    "<caliber_discipline>两种以上统计口径不可混用：",
    "· **单行极值**（\"最大 36.8，某行某日\"）与**月度合计**（\"2021-09 合计 X\"）不是一回事，不能用一个印证另一个；",
    "· **分组求和**与**分组均值**分母不同，不能说\"按总量排第一所以平均也最高\"；",
    "· **末次值**与**历史最大值**是两个时点，说\"已回落 / 已收敛\"必须同时给出这两个数。",
    "关键数字后面用（依据：XX）标明来源——列全量统计／分组求和／按月汇总／相关性／IQR 检测／极值定位。",
    "读者要能顺着这句话回到数据里的那一行，做不到的结论不要写。</caliber_discipline>",
).joinToString("\n")

/**
 * 英文版专业成文规范（T-173）。
 *
 * 章节名与列名**逐字**与 [structureMarkers] 的英文标记一致——写作审计按那些标记查，
 * 两边不一致 = 审计永远报「结构块缺失」。
 */
private fun professionalStructureBlockEn(): String = listOf(
    "",
    "<key_metrics_table>Right after the overall conclusion, add one Markdown summary table with exactly four columns: " +
        "**Metric | Full-period value | Peak location | Change or share**.",
    "· Metrics use the column names from the data; the full-period value says whether it is a sum or an average;",
    "· Peak location names the object and the time (\"CYSW1-2, 2021-10-15\" / \"row 812\") — if the summary gave a location, fill it; leave out columns you cannot fill;",
    "· Change or share must show the formula (\"(83,829 − 18,542) ÷ 18,542 = +352.1%\");",
    "· At most 8 rows, ordered by importance; one sentence after the table explains how the columns were chosen.</key_metrics_table>",
    "",
    "<scope_limit>Right after the overall conclusion, add a **Scope** paragraph with three parts:",
    "① **What was judged**: the whole set or a subset, and how many samples it covers (\"based on the 9,929 orders that carry a sales value\");",
    "② **Under what conditions**: the metric definitions and the zero point (outliers not removed / first observation as zero / only rows with a value);",
    "③ **What is not covered**: which columns or groups did not make it into the conclusion, and why.",
    "Without this paragraph readers will read a subset conclusion as a whole-set conclusion. Every part must cite data.</scope_limit>",
    "",
    "<open_questions>The report must end with \"## Questions the data cannot answer\", 3–5 items. Each item = the question + what is missing + what would answer it.",
    "Say whether the gap is a field, a sample size or a definition (no threshold means no over-limit verdict; no prior period means no year-over-year claim).",
    "Do not turn it into a field checklist and do not repeat questions the report already answered.</open_questions>",
    "",
    "<caliber_discipline>Never mix two statistical calibers:",
    "· **Row-level extremes** (\"max 36.8, one row, one day\") and **monthly totals** (\"2021-09 total X\") are different things — one cannot corroborate the other;",
    "· **Sum by group** and **average by group** have different denominators — \"first by total\" does not mean \"highest on average\";",
    "· **Last value** and **historical maximum** are two points in time — \"it has fallen back\" must carry both numbers.",
    "Mark the source after every key number as (Source: XX) — full-set statistic / group sum / monthly aggregation / correlation / IQR detection / extreme-value location.",
    "A reader must be able to follow the sentence back to one row of data. Do not write conclusions that cannot be traced back.</caliber_discipline>",
).joinToString("\n")
