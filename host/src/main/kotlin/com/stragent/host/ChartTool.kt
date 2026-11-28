package com.stragent.host

import com.stragent.core.ports.AgentTool
import com.stragent.core.chart.ChartSpec
import com.stragent.core.chart.ChartType
import com.stragent.core.chart.ChartSeries
import com.stragent.core.chart.barChart
import com.stragent.core.chart.lineChart
import com.stragent.core.chart.pieChart
import com.stragent.core.chart.scatterChart
import com.stragent.core.chart.SvgChartRenderer
import com.stragent.core.chart.EChartsOptionBuilder
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.aggregateBy
import com.stragent.data.AbilityDatasets
import com.stragent.data.SessionRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 图表渲染工具 —— D10 阶段 4 可视化落地。
 *
 * 对已导入的数据集生成 SVG 图表。支持柱状图、折线图、饼图、散点图。
 * 纯 JVM、确定性——同一数据 + 同一参数产出同一 SVG，可重放复核。
 *
 * 诚实边界：
 *  - SVG 是静态图，不交互（无 hover/zoom）
 *  - 颜色固定调色板，不支持自定义主题
 *  - 不做动画
 */
fun chartTool(
    datasets: AbilityDatasets,
    registry: SessionRegistry? = null,
): AgentTool = object : AgentTool {
    override val name = "stragent_render_chart"
    override val description =
        "对已导入的数据集生成 SVG 图表和 ECharts JSON option（双轨输出：SVG 静态图可直接嵌入 Markdown，" +
        "ECharts JSON option 供浏览器交互式渲染——悬停/缩放/动画）。支持柱状图（bar）、折线图（line）、" +
        "饼图（pie）、散点图（scatter）、热力图（heatmap）、直方图（histogram）、箱线图（boxplot）、雷达图（radar）。" +
        "纯确定性，可重放复核——同一数据+参数产出同一 SVG 和 option。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("datasetId", buildJsonObject {
                    put("type", "string")
                    put("description", "数据集编号（缺省取会话当前数据集）")
                })
                put("chartType", buildJsonObject {
                    put("type", "string")
                    put("enum", JsonArray(listOf(
                        JsonPrimitive("bar"), JsonPrimitive("line"), JsonPrimitive("pie"), JsonPrimitive("scatter"),
                        JsonPrimitive("heatmap"), JsonPrimitive("histogram"), JsonPrimitive("boxplot"), JsonPrimitive("radar")
                    )))
                    put("description", "图表类型：bar（柱状图）/ line（折线图）/ pie（饼图）/ scatter（散点图）/ heatmap（热力图）/ histogram（直方图）/ boxplot（箱线图）/ radar（雷达图），缺省 bar。SVG 静态图 + ECharts JSON option 双轨输出")
                })
                put("categoryColumn", buildJsonObject {
                    put("type", "string")
                    put("description", "分类列名（柱状图/折线图/饼图的 X 轴或分组维度；散点图忽略）")
                })
                put("valueColumn", buildJsonObject {
                    put("type", "string")
                    put("description", "数值列名（柱状图/折线图/饼图的 Y 值；散点图的 Y 轴）")
                })
                put("xColumn", buildJsonObject {
                    put("type", "string")
                    put("description", "散点图 X 轴列名（仅散点图）")
                })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("description", "聚合方式：sum 或 avg（柱状图/折线图/饼图分组时使用），缺省 sum")
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "图表标题（缺省用列名自动生成）")
                })
                put("topN", buildJsonObject {
                    put("type", "integer")
                    put("description", "柱状图/折线图/饼图显示的最大分类数（超出折叠为「其他」），缺省 10")
                })
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("valueColumn"))))
    }

    override val cacheable = false

    /** MCP 2026 outputSchema 契约：声明返回 {chartType, title, svg, echartsOption, dataPoints} */
    override val outputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("chartType", buildJsonObject { put("type", "string") })
            put("title", buildJsonObject { put("type", "string") })
            put("svg", buildJsonObject { put("type", "string"); put("description", "SVG 静态图文本，可直接嵌入 Markdown" ) })
            put("echartsOption", buildJsonObject { put("type", "object"); put("description", "ECharts JSON option，供浏览器交互式渲染" ) })
            put("dataPoints", buildJsonObject { put("type", "integer") })
        })
    }

    override fun invoke(args: JsonObject): JsonObject {
        val datasetId = resolveDatasetId(args, datasets, registry)
        val dataset = datasets.byId(datasetId)
            ?: throw IllegalArgumentException("数据集不存在：$datasetId（请先用 stragent_import_table 导入）")

        val typeStr = args.string("chartType") ?: "bar"
        val chartType = ChartType.fromId(typeStr)
            ?: throw IllegalArgumentException("不支持的图表类型「$typeStr」：请用 bar / line / pie / scatter")

        val valueColName = args.string("valueColumn")
            ?: throw IllegalArgumentException("valueColumn 不能为空")
        val categoryColName = args.string("categoryColumn")
        val xColName = args.string("xColumn")
        val mode = args.string("mode") ?: "sum"
        val titleOverride = args.string("title")
        // 声明是 integer 就必须按数字读：旧实现用 string() 读，客户端传 5 会被静默忽略
        val topN = (args.int("topN") ?: 10).coerceIn(1, 200)

        val valueIndex = findNumericColumn(dataset, valueColName)

        val spec = when (chartType) {
            ChartType.SCATTER -> {
                val xCol = xColName ?: throw IllegalArgumentException("散点图需要 xColumn 参数")
                val xIdx = findNumericColumn(dataset, xCol)
                val pairs = extractScatterPairs(dataset, xIdx, valueIndex)
                if (pairs.isEmpty()) throw IllegalArgumentException("散点图数据为空：请检查列「$xCol」和「$valueColName」是否有有效数值")
                scatterChart(
                    title = titleOverride ?: "${dataset.columns[valueIndex]} vs ${dataset.columns[xIdx]}",
                    xValues = pairs.map { it.first },
                    yValues = pairs.map { it.second },
                    xLabel = dataset.columns[xIdx],
                    yLabel = dataset.columns[valueIndex],
                )
            }
            ChartType.PIE -> {
                val catIdx = findCategoryColumn(dataset, categoryColName)
                val items = aggregateBy(dataset, catIdx, valueIndex, mode, topN)
                if (items.isEmpty()) throw IllegalArgumentException("饼图数据为空：请检查列「$dataset.columns[catIdx]」和「$valueColName」")
                pieChart(
                    title = titleOverride ?: "${dataset.columns[valueIndex]} by ${dataset.columns[catIdx]} ($mode)",
                    categories = items.map { it.label },
                    values = items.map { it.value },
                )
            }
            ChartType.BAR -> {
                val catIdx = findCategoryColumn(dataset, categoryColName)
                val items = aggregateBy(dataset, catIdx, valueIndex, mode, topN)
                if (items.isEmpty()) throw IllegalArgumentException("柱状图数据为空")
                barChart(
                    title = titleOverride ?: "${dataset.columns[valueIndex]} by ${dataset.columns[catIdx]} ($mode)",
                    categories = items.map { it.label },
                    series = listOf(ChartSeries(dataset.columns[valueIndex], items.map { it.value })),
                    xLabel = dataset.columns[catIdx],
                    yLabel = dataset.columns[valueIndex],
                )
            }
            ChartType.LINE -> {
                val catIdx = findCategoryColumn(dataset, categoryColName)
                val items = aggregateBy(dataset, catIdx, valueIndex, mode, topN)
                if (items.isEmpty()) throw IllegalArgumentException("折线图数据为空")
                lineChart(
                    title = titleOverride ?: "${dataset.columns[valueIndex]} by ${dataset.columns[catIdx]} ($mode)",
                    categories = items.map { it.label },
                    series = listOf(ChartSeries(dataset.columns[valueIndex], items.map { it.value })),
                    xLabel = dataset.columns[catIdx],
                    yLabel = dataset.columns[valueIndex],
                )
            }
            else -> {
                val catIdx = findCategoryColumn(dataset, categoryColName)
                val items = aggregateBy(dataset, catIdx, valueIndex, mode, topN)
                if (items.isEmpty()) throw IllegalArgumentException("${chartType.label}数据为空")
                ChartSpec(
                    type = chartType,
                    title = titleOverride ?: "${dataset.columns[valueIndex]} by ${dataset.columns[catIdx]} ($mode)",
                    categories = items.map { it.label },
                    series = listOf(ChartSeries(dataset.columns[valueIndex], items.map { it.value })),
                    xLabel = dataset.columns[catIdx],
                    yLabel = dataset.columns[valueIndex],
                )
            }
        }

        val svg = SvgChartRenderer.render(spec)
        val echartsOption = EChartsOptionBuilder.build(spec)
        return buildJsonObject {
            put("chartType", chartType.id)
            put("title", spec.title)
            put("svg", svg)
            put("echartsOption", echartsOption)
            put("dataPoints", when (chartType) {
                ChartType.SCATTER -> (spec.series.firstOrNull()?.values?.size ?: 0)
                else -> spec.categories.size
            })
        }
    }

    private fun findNumericColumn(dataset: Dataset, name: String): Int {
        val idx = dataset.columns.indexOfFirst { it.equals(name, ignoreCase = true) }
        if (idx < 0) {
            throw IllegalArgumentException("找不到列「$name」：数据集中的列为 " + dataset.columns.joinToString("、") + "。")
        }
        if (dataset.columnTypes[idx] != ColumnType.NUMBER) {
            val numericCols = dataset.columns.filterIndexed { i, _ -> dataset.columnTypes[i] == ColumnType.NUMBER }
            throw IllegalArgumentException("列「$name」不是数值列。数据集中数值列有：" + numericCols.joinToString("、").ifBlank { "（无）" } + "。")
        }
        return idx
    }

    private fun findCategoryColumn(dataset: Dataset, name: String?): Int {
        if (name == null) {
            // 自动选第一个文本列
            val idx = dataset.columns.indices.firstOrNull { dataset.columnTypes[it] == ColumnType.TEXT }
            if (idx == null) throw IllegalArgumentException("数据集中没有文本列，无法做分类维度。")
            return idx
        }
        val idx = dataset.columns.indexOfFirst { it.equals(name, ignoreCase = true) }
        if (idx < 0) {
            throw IllegalArgumentException("找不到列「$name」：数据集中的列为 " + dataset.columns.joinToString("、") + "。")
        }
        return idx
    }

    private fun extractScatterPairs(dataset: Dataset, xIdx: Int, yIdx: Int): List<Pair<Double, Double>> {
        val pairs = mutableListOf<Pair<Double, Double>>()
        for (row in dataset.rows) {
            val xv = com.stragent.core.csv.Csv.parseNumber(row.getOrNull(xIdx) ?: "")
            val yv = com.stragent.core.csv.Csv.parseNumber(row.getOrNull(yIdx) ?: "")
            if (xv != null && yv != null && xv.isFinite() && yv.isFinite()) {
                pairs.add(xv to yv)
            }
        }
        return pairs
    }
}
