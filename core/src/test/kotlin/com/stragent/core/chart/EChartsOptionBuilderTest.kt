package com.stragent.core.chart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * EChartsOptionBuilder 单元测试（D11 v3 S1 交互式可视化）。
 *
 * 验证维度：
 *  1. 八种图表类型的 option 结构正确
 *  2. 确定性——同一 ChartSpec 产出同一 option（hash 稳定）
 *  3. 双轨兼容——SVG 与 ECharts option 同源（同一 ChartSpec）
 *  4. 数值格式化正确（整值去 .0）
 */
class EChartsOptionBuilderTest {

    private val barSpec = barChart(
        title = "销售对比",
        categories = listOf("Q1", "Q2", "Q3"),
        series = listOf(ChartSeries("2024", listOf(100.0, 200.0, 150.0))),
        xLabel = "季度",
        yLabel = "万元",
    )

    private val pieSpec = pieChart(
        title = "占比",
        categories = listOf("A", "B", "C"),
        values = listOf(30.0, 50.0, 20.0),
    )

    private val scatterSpec = scatterChart(
        title = "相关性",
        xValues = listOf(1.0, 2.0, 3.0),
        yValues = listOf(2.0, 4.0, 6.0),
        xLabel = "X",
        yLabel = "Y",
    )

    private val multiSeriesSpec = ChartSpec(
        type = ChartType.BAR,
        title = "多系列",
        categories = listOf("A", "B", "C"),
        series = listOf(
            ChartSeries("2023", listOf(10.0, 20.0, 30.0)),
            ChartSeries("2024", listOf(15.0, 25.0, 35.0)),
        ),
    )

    @Test
    fun `bar option 含 title tooltip xAxis yAxis series`() {
        val opt = EChartsOptionBuilder.build(barSpec)
        assertEquals("销售对比", opt["title"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("axis", opt["tooltip"]!!.jsonObject["trigger"]!!.jsonPrimitive.content)
        assertEquals("category", opt["xAxis"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("value", opt["yAxis"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val series = opt["series"]!!.jsonArray
        assertEquals(1, series.size)
        assertEquals("bar", series[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("2024", series[0].jsonObject["name"]!!.jsonPrimitive.content)
        val data = series[0].jsonObject["data"]!!.jsonArray
        assertEquals(3, data.size)
        assertEquals("100", data[0].jsonPrimitive.content)
    }

    @Test
    fun `line option type 为 line`() {
        val spec = lineChart("趋势", listOf("1月", "2月"), listOf(ChartSeries("A", listOf(1.0, 2.0))))
        val opt = EChartsOptionBuilder.build(spec)
        assertEquals("line", opt["series"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `pie option 含 pie 类型和扇区数据`() {
        val opt = EChartsOptionBuilder.build(pieSpec)
        val series = opt["series"]!!.jsonArray
        assertEquals(1, series.size)
        assertEquals("pie", series[0].jsonObject["type"]!!.jsonPrimitive.content)
        val data = series[0].jsonObject["data"]!!.jsonArray
        assertEquals(3, data.size)
        assertEquals("A", data[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("30", data[0].jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `scatter option 数据为二维数组`() {
        val opt = EChartsOptionBuilder.build(scatterSpec)
        val series = opt["series"]!!.jsonArray
        assertEquals("scatter", series[0].jsonObject["type"]!!.jsonPrimitive.content)
        val data = series[0].jsonObject["data"]!!.jsonArray
        assertEquals(3, data.size)
        val first = data[0].jsonArray
        assertEquals(2, first.size)
        assertEquals("1", first[0].jsonPrimitive.content)
        assertEquals("2", first[1].jsonPrimitive.content)
    }

    @Test
    fun `多系列 bar option 含 legend`() {
        val opt = EChartsOptionBuilder.build(multiSeriesSpec)
        assertNotNull(opt["legend"])
        val legendData = opt["legend"]!!.jsonObject["data"]!!.jsonArray
        assertEquals(2, legendData.size)
    }

    @Test
    fun `heatmap option 含 visualMap 和 heatmap 数据`() {
        val spec = ChartSpec(
            type = ChartType.HEATMAP,
            title = "热力图测试",
            categories = listOf("A", "B"),
            series = listOf(
                ChartSeries("X", listOf(10.0, 20.0)),
                ChartSeries("Y", listOf(30.0, 40.0)),
            ),
        )
        val opt = EChartsOptionBuilder.build(spec)
        assertNotNull(opt["visualMap"])
        val series = opt["series"]!!.jsonArray
        assertEquals("heatmap", series[0].jsonObject["type"]!!.jsonPrimitive.content)
        val data = series[0].jsonObject["data"]!!.jsonArray
        assertEquals(4, data.size)
    }

    @Test
    fun `histogram option 计算分箱`() {
        val spec = ChartSpec(
            type = ChartType.HISTOGRAM,
            title = "直方图测试",
            categories = emptyList(),
            series = listOf(ChartSeries("值", listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0))),
        )
        val opt = EChartsOptionBuilder.build(spec)
        val series = opt["series"]!!.jsonArray
        assertEquals("bar", series[0].jsonObject["type"]!!.jsonPrimitive.content)
        val data = series[0].jsonObject["data"]!!.jsonArray
        assertEquals(10, data.size)
        val total = data.map { it.jsonPrimitive.content.toInt() }.sum()
        assertEquals(10, total)
    }

    @Test
    fun `boxplot option 含五数概括`() {
        val spec = ChartSpec(
            type = ChartType.BOXPLOT,
            title = "箱线图测试",
            categories = emptyList(),
            series = listOf(ChartSeries("A", listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0))),
        )
        val opt = EChartsOptionBuilder.build(spec)
        val series = opt["series"]!!.jsonArray
        assertEquals("boxplot", series[0].jsonObject["type"]!!.jsonPrimitive.content)
        val data = series[0].jsonObject["data"]!!.jsonArray
        assertEquals(1, data.size)
        val box = data[0].jsonArray
        assertEquals(5, box.size)
        assertEquals("1", box[0].jsonPrimitive.content)
        assertEquals("9", box[4].jsonPrimitive.content)
    }

    @Test
    fun `radar option 含 indicator 和 radar 数据`() {
        val spec = ChartSpec(
            type = ChartType.RADAR,
            title = "雷达图测试",
            categories = listOf("速度", "力量", "技巧"),
            series = listOf(ChartSeries("选手A", listOf(80.0, 90.0, 70.0))),
        )
        val opt = EChartsOptionBuilder.build(spec)
        assertNotNull(opt["radar"])
        val indicator = opt["radar"]!!.jsonObject["indicator"]!!.jsonArray
        assertEquals(3, indicator.size)
        assertEquals("速度", indicator[0].jsonObject["name"]!!.jsonPrimitive.content)
        val series = opt["series"]!!.jsonArray
        assertEquals("radar", series[0].jsonObject["type"]!!.jsonPrimitive.content)
        val data = series[0].jsonObject["data"]!!.jsonArray
        assertEquals(1, data.size)
        assertEquals(3, data[0].jsonObject["value"]!!.jsonArray.size)
    }

    @Test
    fun `确定性_同一输入同一输出`() {
        val opt1 = EChartsOptionBuilder.build(barSpec)
        val opt2 = EChartsOptionBuilder.build(barSpec)
        assertEquals(opt1.toString(), opt2.toString())
    }

    @Test
    fun `optionHash 稳定`() {
        val hash1 = EChartsOptionBuilder.optionHash(barSpec)
        val hash2 = EChartsOptionBuilder.optionHash(barSpec)
        assertEquals(hash1, hash2)
        assertEquals(16, hash1.length)
    }

    @Test
    fun `不同图表类型产出不同 option`() {
        val barOpt = EChartsOptionBuilder.build(barSpec)
        val lineSpec = ChartSpec(ChartType.LINE, barSpec.title, barSpec.series, barSpec.categories, barSpec.xLabel, barSpec.yLabel)
        val lineOpt = EChartsOptionBuilder.build(lineSpec)
        val barType = barOpt["series"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content
        val lineType = lineOpt["series"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content
        assertEquals("bar", barType)
        assertEquals("line", lineType)
        assertTrue(EChartsOptionBuilder.optionHash(barSpec) != EChartsOptionBuilder.optionHash(lineSpec))
    }

    @Test
    fun `数值格式化_整值去小数点`() {
        val spec = barChart("测试", listOf("A"), listOf(ChartSeries("S", listOf(100.0, 200.5))))
        val opt = EChartsOptionBuilder.build(spec)
        val data = opt["series"]!!.jsonArray[0].jsonObject["data"]!!.jsonArray
        assertEquals("100", data[0].jsonPrimitive.content)
        assertEquals("200.50", data[1].jsonPrimitive.content)
    }
}
