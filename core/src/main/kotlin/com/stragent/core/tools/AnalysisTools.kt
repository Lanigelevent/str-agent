package com.stragent.core.tools

import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.OutlierReport
import com.stragent.core.dataset.aggregateBy
import com.stragent.core.dataset.analyzeQuality
import com.stragent.core.dataset.correlate
import com.stragent.core.dataset.detectAllOutliers
import com.stragent.core.dataset.detectOutliers
import com.stragent.core.dataset.pickDefaultColumns
import com.stragent.core.dataset.summarizeColumn
import com.stragent.core.dataset.validRowsAndDensity
import com.stragent.core.ports.AgentTool
import com.stragent.core.ports.ToolException
import com.stragent.core.report.BriefSection
import com.stragent.core.report.scanAnomalies
import com.stragent.core.report.splitBrief
import com.stragent.core.stats.CrossTabReport
import com.stragent.core.stats.DrillDown
import com.stragent.core.stats.GroupTrendReport
import com.stragent.core.stats.GroupYoYReport
import com.stragent.core.stats.ResolvedExtremes
import com.stragent.core.stats.buildAttributionChain
import com.stragent.core.stats.buildSeries
import com.stragent.core.stats.crossTabulateWithYield
import com.stragent.core.stats.drillDown
import com.stragent.core.stats.groupTrend
import com.stragent.core.stats.groupYearOverYear
import com.stragent.core.stats.pickSecondDimension
import com.stragent.core.stats.resolveExtremes
import com.stragent.core.verify.countHardIssues
import com.stragent.core.verify.verifyReport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 16 个策展分析工具（T-148 自 str-agent/BuiltInTools.kt 下移）：**纯函数适配**——
 * 列名解析 + core 统计/校验实现 → AgentTool。只依赖 core 自身（Dataset/brief/stats/verify/report），
 * 不依赖 str-agent（agent 循环）与 data（传输）。环境工具（web_search/web_fetch）属 agent
 * 循环，留在 str-agent（buildAgentTools = 本文件全集 + 环境工具）。
 *
 * 纪律与能力层一致：错误抛 ToolException（中文、面向用户/模型都能读），返回 JsonObject
 * ——「每个数字能回到原始行/出处」的口径在各自实现里保持。
 */

/** audit_report 工具：draft → verifyReport → 面向 agent 的纠错清单 */
fun auditReportTool(dataset: Dataset, brief: String): AgentTool = object : AgentTool {
    override val name = "audit_report"
    override val description =
        "报告草稿自检（写完 finish 前必调）：逐个数溯源校验。返回未溯源数字清单、算式错误与关系错误——把 high 问题修掉或删掉对应结论再 finish。"
    override val parametersSchema = schema("report" to Param("string", true, "最终报告正文"))  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）

    override fun invoke(args: JsonObject): JsonObject {
        val draft = args["report"]?.let { (it as? JsonPrimitive)?.content }
            ?: throw ToolException("report（草稿全文）不能为空")
        val verification = verifyReport(draft, brief)
        val hard = countHardIssues(verification)
        val unsourced = verification.unsourced.take(8)
        return buildJsonObject {
            put("checked", verification.checked)
            put("hardIssues", hard)
            put(
                "unsourcedNumbers",
                JsonArray(
                    unsourced.map { issue ->
                        buildJsonObject {
                            put("value", issue.value)
                            put("kind", issue.kind)
                            put("context", issue.context.take(80))
                        }
                    },
                ),
            )
            if (verification.arithmetic.isNotEmpty()) {
                put(
                    "arithmeticErrors",
                    JsonArray(
                        verification.arithmetic.take(5).map { error ->
                            buildJsonObject {
                                put("claimed", error.claimed)
                                put("expected", error.expected)
                            }
                        },
                    ),
                )
            }
            put(
                "verdict",
                if (hard == 0) "通过：所有数字可溯源。可以 finish。"
                else "未通过：有 $hard 处硬伤——修掉或删除对应结论后再 finish；无法修复的结论宁可不写。",
            )
        }
    }
}

/** JS 数字语义：JSON.stringify(550.0) 是 "550" */
private fun jsNumber(value: Double): Number =
    if (value.isFinite() && value == Math.floor(value) && kotlin.math.abs(value) < 9.007199254740992E15) {
        value.toLong()
    } else {
        value
    }

private fun JsonObjectBuilder.putNum(key: String, value: Double?) {
    if (value == null) put(key, JsonNull) else put(key, jsNumber(value))
}

private fun num(value: Double): JsonPrimitive = JsonPrimitive(jsNumber(value))

/** 参数规格（T-104）：description 是调用方模型的引导面，静态自扫（McpSurfaceScanTest）强制非空 */
data class Param(val type: String, val required: Boolean, val description: String)

private fun schema(vararg fields: Pair<String, Param>): JsonObject {
    val properties = fields.associate { (name, spec) ->
        name to buildJsonObject {
            put("type", spec.type)
            put("description", spec.description) // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        }
    }
    val required = fields.filter { it.second.required }.map { it.first }
    return buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties))
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
}

private fun text(v: String): JsonPrimitive = JsonPrimitive(v)

/** 列名 → 下标；解析失败给可行动的中文错误（附全部列名） */
private fun resolveColumn(dataset: Dataset, name: String?, role: String, wantType: ColumnType?): Int {
    val trimmed = name?.trim().orEmpty()
    if (trimmed.isEmpty()) throw ToolException("$role 列名不能为空（该数据集的列: ${dataset.columns.joinToString("、")}）")
    val index = dataset.columns.indexOf(trimmed)
    if (index == -1) {
        throw ToolException("列不存在: $trimmed（该数据集的列: ${dataset.columns.joinToString("、")}）")
    }
    if (wantType != null && dataset.columnTypes[index] != wantType) {
        val actual = if (dataset.columnTypes[index] == ColumnType.NUMBER) "数值列" else "文本列"
        val expected = if (wantType == ColumnType.NUMBER) "数值列" else "文本列"
        throw ToolException("列 \"$trimmed\" 被识别为$actual：$role 请选$expected")
    }
    return index
}

private val ColumnType.jsonName: String get() = if (this == ColumnType.NUMBER) "number" else "text"

/** 数据概况（agent 落地后的第一件事通常是看这个） */
fun overviewTool(dataset: Dataset): AgentTool = object : AgentTool {
    override val name = "get_overview"
    override val description = "返回数据概况：行数、全部列名与推断类型、有效行数与单元格密度。开始分析前先调一次。"
    override val parametersSchema = schema()

    override fun invoke(args: JsonObject): JsonObject {
        val (validRows, density) = validRowsAndDensity(dataset)
        return buildJsonObject {
            put("rows", dataset.rows.size)
            put("sourceRows", dataset.sourceRows)
            put("truncated", dataset.truncated)
            put("columns", dataset.columns.size)
            put("validRows", validRows)
            put("density", num(density))
            put(
                "columnList",
                JsonArray(
                    dataset.columns.mapIndexed { index, name ->
                        buildJsonObject {
                            put("name", name)
                            put("type", dataset.columnTypes[index].jsonName)
                        }
                    },
                ),
            )
        }
    }
}

fun columnProfileTool(dataset: Dataset): AgentTool = object : AgentTool {
    override val name = "get_column_profile"
    override val description =
        "单列统计画像：数值列为 {count, missing, min, max, sum, mean, median, p25, p75}（count 是实际参与统计的样本数，missing 含空值与脏值）；文本列为 {count, missing, distinct, topValues}。"
    override val parametersSchema = schema("column" to Param("string", true, "列名（表头原文）"))  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）

    override fun invoke(args: JsonObject): JsonObject {
        val name = args["column"]?.let { (it as? JsonPrimitive)?.content } ?: ""
        val index = resolveColumn(dataset, name, "画像", null)
        val stat = summarizeColumn(dataset, index)
        return buildJsonObject {
            put("name", stat.name)
            put("type", stat.type.jsonName)
            put("count", stat.count)
            put("missing", stat.missing)
            stat.min?.let { putNum("min", it) }
            stat.max?.let { putNum("max", it) }
            stat.sum?.let { putNum("sum", it) }
            stat.mean?.let { putNum("mean", it) }
            stat.median?.let { putNum("median", it) }
            stat.p25?.let { putNum("p25", it) }
            stat.p75?.let { putNum("p75", it) }
            stat.distinct?.let { put("distinct", it) }
            stat.topValues?.let { topValues ->
                put("topValues", JsonArray(topValues.take(5).map { top ->
                    buildJsonObject {
                        put("value", top.value)
                        put("count", top.count)
                    }
                }))
            }
        }
    }
}

fun groupAggregateTool(dataset: Dataset): AgentTool = object : AgentTool {
    override val name = "group_aggregate"
    override val description =
        "按文本维度列对数值指标列分组聚合（sum 或 avg），返回各组的值、样本量与占比（share 分母在 denominator 写明；avg 或存在负值时 share 为 null）。超出 limit 的组折成「其他 N 项」。"
    override val parametersSchema = schema(
        "dimension" to Param("string", true, "分组维度列名"),  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）
        "metric" to Param("string", true, "指标列名（数值列）"),  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）
        "mode" to Param("string", false, "聚合或排序模式"),  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）
        "limit" to Param("number", false, "limit 参数"),  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）
    )

    override fun invoke(args: JsonObject): JsonObject {
        val str = { key: String -> args[key]?.let { (it as? JsonPrimitive)?.content } }
        val dimIndex = resolveColumn(dataset, str("dimension"), "分组维度", ColumnType.TEXT)
        val valueIndex = resolveColumn(dataset, str("metric"), "聚合指标", ColumnType.NUMBER)
        val mode = str("mode") ?: "sum"
        if (mode !in listOf("sum", "avg")) throw ToolException("mode 只支持 sum 或 avg")
        val limit = args["limit"]?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() } ?: 8.0
        val items = aggregateBy(dataset, dimIndex, valueIndex, mode, limit.toInt().coerceIn(1, 50))
        val shareSum = items.sumOf { if (it.share.isFinite()) it.share else 0.0 }
        val round2 = { value: Double -> Math.round(value * 100) / 100.0 }
        return buildJsonObject {
            put("mode", mode)
            put(
                "denominator",
                if (mode == "sum" && kotlin.math.abs(shareSum - 1.0) < 1e-9) {
                    "share 分母 = 该指标全体合计"
                } else {
                    "share 为 null：avg 模式或存在负值，占合计无意义"
                },
            )
            put(
                "items",
                JsonArray(
                    items.map { item ->
                        buildJsonObject {
                            put("label", item.label)
                            put("value", num(round2(item.value)))
                            put("count", item.count)
                            if (item.share.isFinite()) put("share", num(round2(item.share))) else put("share", JsonNull)
                        }
                    },
                ),
            )
        }
    }
}

fun outliersTool(dataset: Dataset): AgentTool = object : AgentTool {
    override val name = "get_outliers"
    override val description = "IQR 异常值检测（1.5×IQR）：指定数值列返回该列的异常行（行号+值+偏离 IQR 倍数+正常区间），不传 column 返回全部数值列的概要。"
    override val parametersSchema = schema("column" to Param("string", false, "列名（表头原文）"))  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）

    override fun invoke(args: JsonObject): JsonObject {
        val name = args["column"]?.let { (it as? JsonPrimitive)?.content }
        if (name != null) {
            val index = resolveColumn(dataset, name, "检测", ColumnType.NUMBER)
            val report = detectOutliers(dataset, index)
                ?: throw ToolException("该列有效样本不足（<4）或 IQR 为 0，无法做 IQR 检测")
            return outlierJson(report)
        }
        val all = detectAllOutliers(dataset)
        return buildJsonObject {
            put(
                "columns",
                JsonArray(all.map { report ->
                    buildJsonObject {
                        put("column", report.column)
                        put("outliers", report.outliers.size)
                        put("normalRange", "${num(report.lower)}/${num(report.upper)}")
                    }
                }),
            )
        }
    }
}

private fun outlierJson(report: OutlierReport): JsonObject = buildJsonObject {
    put("column", report.column)
    put("sampleSize", report.sampleSize)
    putNum("q1", report.q1)
    putNum("q3", report.q3)
    putNum("lower", report.lower)
    putNum("upper", report.upper)
    put(
        "outliers",
        JsonArray(
            report.outliers.map { item ->
                buildJsonObject {
                    put("row", item.rowIndex)
                    put("value", num(item.value))
                    put("deviationIQR", num(item.deviation))
                }
            },
        ),
    )
}

fun anomaliesTool(dataset: Dataset, preferredGroup: Int): AgentTool = object : AgentTool {
    override val name = "scan_anomalies"
    override val description =
        "六类时序/结构异常扫描（时序突变/结构突变/时间缺口/主键重复/负值/序列塌陷），返回发现清单（severity/kind/贡献者/证据行号）与覆盖情况。"
    override val parametersSchema = schema()

    override fun invoke(args: JsonObject): JsonObject {
        val scan = scanAnomalies(dataset, preferredGroup)
        return buildJsonObject {
            put(
                "coverage",
                buildJsonObject {
                    put("monthly", scan.coverage.monthly)
                    put("sparse", scan.coverage.sparse)
                    scan.coverage.dimension?.let { put("dimension", it) }
                    scan.coverage.metric?.let { put("metric", it) }
                    put("months", scan.coverage.months)
                },
            )
            put(
                "findings",
                JsonArray(
                    scan.findings.map { finding ->
                        buildJsonObject {
                            put("kind", finding.kind.name.lowercase())
                            put("severity", finding.severity.name.lowercase())
                            put("metric", finding.metric)
                            finding.period?.let { put("period", it) }
                            finding.dimension?.let { put("dimension", it) }
                            put("change", num(finding.change))
                            put("detail", finding.detail)
                            put("evidenceRows", JsonArray(finding.evidenceRows.map { JsonPrimitive(it) }))
                        }
                    },
                ),
            )
        }
    }
}

fun attributionChainTool(dataset: Dataset, preferredGroup: Int): AgentTool = object : AgentTool {
    override val name = "get_attribution_chain"
    override val description =
        "某期的时序突变归因链：哪个维度值贡献最大 → 组内哪个子维度 → 量价分解（Δ合计 = Δ笔数×笔均前 + 笔数后×Δ笔均，无残差）与结论。period 形如 2017-09。"
    override val parametersSchema = schema("period" to Param("string", true, "period 参数"))  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）

    override fun invoke(args: JsonObject): JsonObject {
        val period = args["period"]?.let { (it as? JsonPrimitive)?.content }
            ?: throw ToolException("period 不能为空（形如 2017-09）")
        val chain = buildAttributionChain(dataset, period, preferredGroup)
            ?: throw ToolException("无法构建归因链：期号不在数据里、首期无环比、或缺日期/数值列")
        return buildJsonObject {
            put("root", buildJsonObject {
                put("period", chain.rootPeriod)
                put("previous", chain.rootPrevious)
                put("metric", chain.rootMetric)
                put("before", num(chain.rootBefore))
                put("after", num(chain.rootAfter))
            })
            put(
                "links",
                JsonArray(
                    chain.links.map { link ->
                        buildJsonObject {
                            put("kind", link.kind)
                            put("label", link.label)
                            put("delta", num(link.delta))
                            put("share", num(link.share))
                            put("detail", link.detail)
                        }
                    },
                ),
            )
            put("verdict", chain.verdict)
            put("verdictText", chain.verdictText)
        }
    }
}

fun groupYoYTool(dataset: Dataset, dateIndex: Int): AgentTool = object : AgentTool {
    override val name = "get_group_yoy"
    override val description = "分组同比：整体与各组的同比并排，标记拖累组（落后整体 ≥5pp）与样本不足组（<3 行）。"
    override val parametersSchema = schema(
        "dimension" to Param("string", true, "分组维度列名"),  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）
        "metric" to Param("string", true, "指标列名（数值列）"),  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）
    )

    override fun invoke(args: JsonObject): JsonObject {
        val str = { key: String -> args[key]?.let { (it as? JsonPrimitive)?.content } }
        val report = groupYearOverYear(
            dataset,
            resolveColumn(dataset, str("dimension"), "分组维度", ColumnType.TEXT),
            resolveColumn(dataset, str("metric"), "聚合指标", ColumnType.NUMBER),
            dateIndex,
        ) ?: throw ToolException("该数据组不出同比（需要月份列且两期样本充足）")
        return groupYoYJson(report)
    }
}

private fun groupYoYJson(report: GroupYoYReport): JsonObject = buildJsonObject {
    put("metric", report.metric)
    put("dimension", report.dimension)
    put("period", report.period)
    put("previousPeriod", report.previousPeriod)
    report.overall?.let { putNum("overall", it) }
    put(
        "points",
        JsonArray(
            report.points.map { point ->
                buildJsonObject {
                    put("label", point.label)
                    put("current", num(point.current))
                    put("previous", num(point.previous))
                    point.change?.let { put("change", num(it)) }
                    put("rows", point.rows)
                    if (point.label in report.laggards) put("laggard", true)
                }
            },
        ),
    )
    put("laggards", JsonArray(report.laggards.map { text(it) }))
}

fun crossTabTool(dataset: Dataset, valueIndex: Int): AgentTool = object : AgentTool {
    override val name = "get_cross_tab"
    override val description =
        "维度交叉：主维度 × 自动挑的第二维度，各格的合计/笔数/行均（行均=合计÷笔数，配产出率判定），并给出「行数最多与行均最高是否同一格」的结论。"
    override val parametersSchema = schema("dimension" to Param("string", true, "分组维度列名"))  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）

    override fun invoke(args: JsonObject): JsonObject {
        val dimension = args["dimension"]?.let { (it as? JsonPrimitive)?.content }
        val aIndex = resolveColumn(dataset, dimension, "主维度", ColumnType.TEXT)
        val bIndex = pickSecondDimension(dataset, aIndex)
        val report = crossTabulateWithYield(dataset, aIndex, bIndex, valueIndex)
            ?: throw ToolException("维度交叉需要两个可分组维度与数值列")
        return crossTabJson(report)
    }
}

private fun crossTabJson(report: CrossTabReport): JsonObject = buildJsonObject {
    put("aColumn", report.aColumn)
    put("bColumn", report.bColumn)
    put("metric", report.metric)
    put("baseline", num(report.baseline))
    report.busiest?.let { put("busiest", "${it.a} × ${it.b}") }
    report.bestYield?.let { put("bestYield", "${it.a} × ${it.b}") }
    put("rows", report.rows)
    put(
        "cells",
        JsonArray(
            report.cells.map { cell ->
                buildJsonObject {
                    put("a", cell.a)
                    put("b", cell.b)
                    put("value", num(cell.value))
                    put("count", cell.count)
                    put("yield", num(cell.yield))
                }
            },
        ),
    )
}

fun groupTrendTool(dataset: Dataset, dateIndex: Int): AgentTool = object : AgentTool {
    override val name = "get_group_trend"
    override val description = "分组走势：滚动 12 期基线，连续 ≥3 期低于基线 30% 才报「走弱」（只报低于侧，防恢复期误报）。"
    override val parametersSchema = schema(
        "dimension" to Param("string", true, "分组维度列名"),  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）
        "metric" to Param("string", true, "指标列名（数值列）"),  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）
    )

    override fun invoke(args: JsonObject): JsonObject {
        val str = { key: String -> args[key]?.let { (it as? JsonPrimitive)?.content } }
        val report = groupTrend(
            dataset,
            resolveColumn(dataset, str("dimension"), "分组维度", ColumnType.TEXT),
            resolveColumn(dataset, str("metric"), "聚合指标", ColumnType.NUMBER),
            dateIndex,
        ) ?: throw ToolException("分组走势需要月份列")
        return groupTrendJson(report)
    }
}

private fun groupTrendJson(report: GroupTrendReport): JsonObject = buildJsonObject {
    put("metric", report.metric)
    put("dimension", report.dimension)
    put("window", report.window)
    put(
        "points",
        JsonArray(
            report.points.map { point ->
                buildJsonObject {
                    put("label", point.label)
                    put("periods", point.periods)
                    point.lastMonth?.let { put("lastMonth", it) }
                    point.lastValue?.let { put("lastValue", num(it)) }
                    point.run?.let { run ->
                        put("run", buildJsonObject {
                            put("start", run.start)
                            put("end", run.end)
                            put("months", run.months)
                            put("direction", run.direction)
                            put("deviation", num(run.deviation))
                        })
                    }
                }
            },
        ),
    )
}

fun correlationTool(dataset: Dataset): AgentTool = object : AgentTool {
    override val name = "get_correlations"
    override val description = "数值列两两皮尔逊相关（|r| 降序）：r、样本量 n、强度分档。相关不等于因果。"
    override val parametersSchema = schema()

    override fun invoke(args: JsonObject): JsonObject = buildJsonObject {
        put(
            "items",
            JsonArray(
                correlate(dataset).map { item ->
                    buildJsonObject {
                        put("a", item.a)
                        put("b", item.b)
                        put("r", num(item.r))
                        put("strength", item.strength)
                        put("n", item.n)
                    }
                },
            ),
        )
    }
}

fun drillDownTool(dataset: Dataset): AgentTool = object : AgentTool {
    override val name = "drill_down"
    override val description =
        "分类下钻：某维度值的合计/笔数/单均（与全体单均对照）、组内第二维度分布（占比分母=该组合计）、前 20 行明细（带原始行号）。"
    override val parametersSchema = schema(
        "dimension" to Param("string", true, "分组维度列名"),  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）
        "metric" to Param("string", true, "指标列名（数值列）"),  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）
        "group" to Param("string", true, "group 参数"),  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）
        "mode" to Param("string", false, "聚合或排序模式"),  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）
    )

    override fun invoke(args: JsonObject): JsonObject {
        val str = { key: String -> args[key]?.let { (it as? JsonPrimitive)?.content } }
        val drill = drillDown(
            dataset,
            resolveColumn(dataset, str("dimension"), "分组维度", ColumnType.TEXT),
            resolveColumn(dataset, str("metric"), "聚合指标", ColumnType.NUMBER),
            str("group") ?: throw ToolException("group（要下钻的维度值）不能为空"),
            str("mode") ?: "sum",
        ) ?: throw ToolException("该组一个值都没有（组名须与数据里的值逐字一致）")
        return drillJson(drill)
    }
}

private fun drillJson(drill: DrillDown): JsonObject = buildJsonObject {
    put("group", drill.group)
    put("groupColumn", drill.groupColumn)
    put("metric", drill.metric)
    put("mode", drill.mode)
    put("total", num(drill.total))
    put("count", drill.count)
    put("mean", num(drill.mean))
    if (drill.share.isFinite()) put("share", num(drill.share)) else put("share", JsonNull)
    put("overallMean", num(drill.overallMean))
    if (drill.meanRatio.isFinite()) put("meanRatio", num(drill.meanRatio)) else put("meanRatio", JsonNull)
    put(
        "crosses",
        JsonArray(
            drill.crosses.map { cross ->
                buildJsonObject {
                    put("dimension", cross.dimension)
                    put(
                        "items",
                        JsonArray(
                            cross.items.map { item ->
                                buildJsonObject {
                                    put("label", item.label)
                                    put("value", num(item.value))
                                    put("share", num(item.share))
                                }
                            },
                        ),
                    )
                }
            },
        ),
    )
    put(
        "rows",
        JsonArray(
            drill.rows.map { row ->
                buildJsonObject {
                    put("row", row.row)
                    put("cells", JsonArray(row.cells.map { text(it) }))
                }
            },
        ),
    )
}

fun seriesTool(dataset: Dataset): AgentTool = object : AgentTool {
    override val name = "get_series"
    override val description = "两列构建序列（x 列顺序即数据行序，y 为数值列），最多 120 点——用于看趋势与峰谷。"
    override val parametersSchema = schema(
        "x" to Param("string", true, "x 参数"),  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）
        "y" to Param("string", true, "y 参数"),  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）
    )

    override fun invoke(args: JsonObject): JsonObject {
        val str = { key: String -> args[key]?.let { (it as? JsonPrimitive)?.content } }
        val points = buildSeries(
            dataset,
            resolveColumn(dataset, str("x"), "横轴", null),
            resolveColumn(dataset, str("y"), "数值列", ColumnType.NUMBER),
        ).take(120)
        return buildJsonObject {
            put(
                "points",
                JsonArray(points.map { point -> buildJsonObject { put("x", point.x); put("y", num(point.y)) } }),
            )
        }
    }
}

fun extremesTool(dataset: Dataset): AgentTool = object : AgentTool {
    override val name = "get_extremes"
    override val description = "关键数值列的最值定位：最大/最小值落在哪个组、哪一期、第几行（最多 3 列）。"
    override val parametersSchema = schema()

    override fun invoke(args: JsonObject): JsonObject {
        val defaults = pickDefaultColumns(dataset)
        val resolved = resolveExtremes(dataset, defaults.category, defaults.x)
            ?: throw ToolException("这份数据没有可定位的极值（需要分组与日期维度）")
        return extremesJson(resolved)
    }
}

private fun extremesJson(resolved: ResolvedExtremes): JsonObject = buildJsonObject {
    put("source", resolved.source)
    put(
        "pairs",
        JsonArray(
            resolved.pairs.take(3).map { pair ->
                buildJsonObject {
                    put("column", pair.column)
                    fun loc(name: String, location: com.stragent.core.stats.ExtremeLocation?) {
                        if (location == null) return
                        put(name, buildJsonObject {
                            put("value", num(location.value))
                            location.group?.let { put("group", it) }
                            location.date?.let { put("date", it) }
                            location.column?.let { put("column", it) }
                            put("row", location.row)
                        })
                    }
                    loc("max", pair.max)
                    loc("min", pair.min)
                }
            },
        ),
    )
}

fun qualityTool(dataset: Dataset): AgentTool = object : AgentTool {
    override val name = "get_quality"
    override val description = "数据质量：空值率、重复行、每列缺失数、一致性形态、常数列——影响结论可信度的部分。"
    override val parametersSchema = schema()

    override fun invoke(args: JsonObject): JsonObject {
        val report = analyzeQuality(dataset)
        return buildJsonObject {
            put("rows", report.rows)
            put("duplicateRows", report.duplicateRows)
            put("emptyRate", num(report.emptyRate))
            put("numericColumns", report.numericColumns)
            put("textColumns", report.textColumns)
            put("constantColumns", JsonArray(report.constantColumns.map { text(it) }))
            put(
                "worstColumns",
                JsonArray(report.worstColumns.map { worst -> buildJsonObject { put("column", worst.column); put("emptyRate", num(worst.emptyRate)) } }),
            )
        }
    }
}

fun reportContextTool(dataset: Dataset, brief: String): AgentTool = object : AgentTool {
    override val name = "get_report_context"
    override val description =
        "取数据摘要（brief）的指定段落：按关键词筛选（如「分组」「月度」「异常」），返回命中的段落文本——按需取上下文，不整段塞入。"
    override val parametersSchema = schema("query" to Param("string", false, "查询内容"))  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）

    override fun invoke(args: JsonObject): JsonObject {
        val query = args["query"]?.let { (it as? JsonPrimitive)?.content }
        val sections: List<BriefSection> = splitBrief(brief)
        val hits = if (query.isNullOrBlank()) {
            sections
        } else {
            sections.filter { it.text.contains(query) || (it.label ?: "").contains(query) }
        }
        val joined = hits.joinToString("\n\n") { it.text }.let { if (it.length > 8000) it.take(8000) + "…（已截断）" else it }
        return buildJsonObject {
            put("matched", hits.size)
            put("total", sections.size)
            put("text", joined)
        }
    }
}

/**
 * 分析工具全集（列名解析版）。dateIndex 用 pickDefaultColumns 预解析的横轴列。
 */
fun buildAnalysisTools(dataset: Dataset, brief: String): List<AgentTool> {
    val defaults = pickDefaultColumns(dataset)
    return listOf(
        overviewTool(dataset),
        columnProfileTool(dataset),
        groupAggregateTool(dataset),
        outliersTool(dataset),
        anomaliesTool(dataset, defaults.category),
        attributionChainTool(dataset, defaults.category),
        groupYoYTool(dataset, dateIndex = defaults.x),
        crossTabTool(dataset, valueIndex = defaults.value),
        groupTrendTool(dataset, dateIndex = defaults.x),
        correlationTool(dataset),
        drillDownTool(dataset),
        seriesTool(dataset),
        extremesTool(dataset),
        qualityTool(dataset),
        reportContextTool(dataset, brief),
        auditReportTool(dataset, brief),
    )
}
