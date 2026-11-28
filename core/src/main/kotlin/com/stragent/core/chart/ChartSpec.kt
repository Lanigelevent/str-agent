package com.stragent.core.chart

/**
 * 图表数据模型 —— D10 阶段 4 可视化落地。
 *
 * 纯数据结构，不含渲染逻辑。渲染由 SvgChartRenderer 完成。
 * 设计原则：与溯源清单纪律兼容——同一份数据 + 同一参数，
 * 渲染结果（SVG 文本）必然一致，可重放复核。
 *
 * 支持四种图表类型（覆盖 80% 数据分析场景）：
 *  - BAR：柱状图（分类对比）
 *  - LINE：折线图（趋势）
 *  - PIE：饼图（占比）
 *  - SCATTER：散点图（相关性）
 */

enum class ChartType(val id: String, val label: String) {
    BAR("bar", "柱状图"),
    LINE("line", "折线图"),
    PIE("pie", "饼图"),
    SCATTER("scatter", "散点图"),
    HEATMAP("heatmap", "热力图"),
    HISTOGRAM("histogram", "直方图"),
    BOXPLOT("boxplot", "箱线图"),
    RADAR("radar", "雷达图");

    companion object {
        fun fromId(id: String): ChartType? =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** 图表中的一个数据系列 */
data class ChartSeries(
    val name: String,
    val values: List<Double>,
    /** 散点图专用：X 轴值（与 values 等长；其他图表忽略此字段） */
    val xValues: List<Double>? = null,
)

/** 图表规格（完整渲染所需信息） */
data class ChartSpec(
    val type: ChartType,
    val title: String,
    val series: List<ChartSeries>,
    /** 分类标签（柱状图/折线图的 X 轴标签；饼图的扇区标签） */
    val categories: List<String>,
    val xLabel: String? = null,
    val yLabel: String? = null,
    /** 宽度（像素），缺省 600 */
    val width: Int = 600,
    /** 高度（像素），缺省 400 */
    val height: Int = 400,
)

/** 柱状图快捷构造 */
fun barChart(
    title: String,
    categories: List<String>,
    series: List<ChartSeries>,
    xLabel: String? = null,
    yLabel: String? = null,
): ChartSpec = ChartSpec(ChartType.BAR, title, series, categories, xLabel, yLabel)

/** 折线图快捷构造 */
fun lineChart(
    title: String,
    categories: List<String>,
    series: List<ChartSeries>,
    xLabel: String? = null,
    yLabel: String? = null,
): ChartSpec = ChartSpec(ChartType.LINE, title, series, categories, xLabel, yLabel)

/** 饼图快捷构造 */
fun pieChart(
    title: String,
    categories: List<String>,
    values: List<Double>,
): ChartSpec = ChartSpec(
    type = ChartType.PIE,
    title = title,
    series = listOf(ChartSeries("占比", values)),
    categories = categories,
)

/** 散点图快捷构造 */
fun scatterChart(
    title: String,
    xValues: List<Double>,
    yValues: List<Double>,
    seriesName: String = "数据点",
    xLabel: String? = null,
    yLabel: String? = null,
): ChartSpec = ChartSpec(
    type = ChartType.SCATTER,
    title = title,
    series = listOf(ChartSeries(seriesName, yValues, xValues)),
    categories = emptyList(),
    xLabel = xLabel,
    yLabel = yLabel,
)
