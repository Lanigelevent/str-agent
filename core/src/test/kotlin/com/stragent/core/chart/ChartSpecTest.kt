package com.stragent.core.chart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ChartSpec 数据模型单元测试（D10 阶段 4 可视化落地）。
 *
 * 验证维度：
 *  1. ChartType 枚举与 fromId 解析（大小写不敏感、无效值返回 null）
 *  2. ChartSeries 数据结构
 *  3. ChartSpec 完整字段与缺省值
 *  4. 四个快捷构造器（barChart / lineChart / pieChart / scatterChart）
 */
class ChartSpecTest {

    @Test
    fun `ChartType fromId 大小写不敏感`() {
        assertEquals(ChartType.BAR, ChartType.fromId("bar"))
        assertEquals(ChartType.BAR, ChartType.fromId("BAR"))
        assertEquals(ChartType.LINE, ChartType.fromId("line"))
        assertEquals(ChartType.LINE, ChartType.fromId("LINE"))
        assertEquals(ChartType.PIE, ChartType.fromId("pie"))
        assertEquals(ChartType.SCATTER, ChartType.fromId("scatter"))
    }

    @Test
    fun `ChartType fromId 无效值返回 null`() {
        assertNull(ChartType.fromId("unknown"))
        assertNull(ChartType.fromId(""))
        assertNull(ChartType.fromId("bubble"))
    }

    @Test
    fun `ChartType 新增类型解析正确`() {
        assertEquals(ChartType.HEATMAP, ChartType.fromId("heatmap"))
        assertEquals(ChartType.HISTOGRAM, ChartType.fromId("histogram"))
        assertEquals(ChartType.BOXPLOT, ChartType.fromId("boxplot"))
        assertEquals(ChartType.RADAR, ChartType.fromId("radar"))
    }

    @Test
    fun `ChartType 新增枚举属性正确`() {
        assertEquals("heatmap", ChartType.HEATMAP.id)
        assertEquals("热力图", ChartType.HEATMAP.label)
        assertEquals("histogram", ChartType.HISTOGRAM.id)
        assertEquals("直方图", ChartType.HISTOGRAM.label)
        assertEquals("boxplot", ChartType.BOXPLOT.id)
        assertEquals("箱线图", ChartType.BOXPLOT.label)
        assertEquals("radar", ChartType.RADAR.id)
        assertEquals("雷达图", ChartType.RADAR.label)
    }

    @Test
    fun `ChartType 枚举属性正确`() {
        assertEquals("bar", ChartType.BAR.id)
        assertEquals("柱状图", ChartType.BAR.label)
        assertEquals("line", ChartType.LINE.id)
        assertEquals("折线图", ChartType.LINE.label)
        assertEquals("pie", ChartType.PIE.id)
        assertEquals("饼图", ChartType.PIE.label)
        assertEquals("scatter", ChartType.SCATTER.id)
        assertEquals("散点图", ChartType.SCATTER.label)
    }

    @Test
    fun `ChartSeries 基本构造`() {
        val s = ChartSeries("销售额", listOf(100.0, 200.0, 300.0))
        assertEquals("销售额", s.name)
        assertEquals(3, s.values.size)
        assertEquals(100.0, s.values[0])
        assertNull(s.xValues)
    }

    @Test
    fun `ChartSeries 散点图带 xValues`() {
        val s = ChartSeries("点", listOf(10.0, 20.0), listOf(1.0, 2.0))
        assertEquals(2, s.xValues!!.size)
        assertEquals(1.0, s.xValues!![0])
    }

    @Test
    fun `ChartSpec 缺省宽高正确`() {
        val spec = ChartSpec(
            type = ChartType.BAR,
            title = "测试",
            series = listOf(ChartSeries("A", listOf(1.0))),
            categories = listOf("X"),
        )
        assertEquals(600, spec.width)
        assertEquals(400, spec.height)
        assertNull(spec.xLabel)
        assertNull(spec.yLabel)
    }

    @Test
    fun `barChart 快捷构造`() {
        val spec = barChart(
            title = "销售对比",
            categories = listOf("Q1", "Q2", "Q3"),
            series = listOf(ChartSeries("2024", listOf(100.0, 200.0, 150.0))),
            xLabel = "季度",
            yLabel = "万元",
        )
        assertEquals(ChartType.BAR, spec.type)
        assertEquals("销售对比", spec.title)
        assertEquals(3, spec.categories.size)
        assertEquals(1, spec.series.size)
        assertEquals("季度", spec.xLabel)
        assertEquals("万元", spec.yLabel)
    }

    @Test
    fun `lineChart 快捷构造`() {
        val spec = lineChart(
            title = "趋势",
            categories = listOf("1月", "2月", "3月"),
            series = listOf(ChartSeries("A", listOf(1.0, 2.0, 3.0))),
        )
        assertEquals(ChartType.LINE, spec.type)
        assertEquals("趋势", spec.title)
        assertEquals(3, spec.categories.size)
        assertNull(spec.xLabel)
        assertNull(spec.yLabel)
    }

    @Test
    fun `pieChart 快捷构造`() {
        val spec = pieChart(
            title = "占比",
            categories = listOf("A", "B", "C"),
            values = listOf(30.0, 50.0, 20.0),
        )
        assertEquals(ChartType.PIE, spec.type)
        assertEquals("占比", spec.title)
        assertEquals(3, spec.categories.size)
        assertEquals(1, spec.series.size)
        assertEquals("占比", spec.series[0].name)
        assertEquals(30.0, spec.series[0].values[0])
    }

    @Test
    fun `scatterChart 快捷构造`() {
        val spec = scatterChart(
            title = "相关性",
            xValues = listOf(1.0, 2.0, 3.0),
            yValues = listOf(2.0, 4.0, 6.0),
            seriesName = "观测点",
            xLabel = "X轴",
            yLabel = "Y轴",
        )
        assertEquals(ChartType.SCATTER, spec.type)
        assertEquals("相关性", spec.title)
        assertEquals(0, spec.categories.size)
        assertEquals(1, spec.series.size)
        assertEquals("观测点", spec.series[0].name)
        assertNotNull(spec.series[0].xValues)
        assertEquals(3, spec.series[0].xValues!!.size)
        assertEquals(3, spec.series[0].values.size)
        assertEquals("X轴", spec.xLabel)
        assertEquals("Y轴", spec.yLabel)
    }

    @Test
    fun `scatterChart 缺省系列名`() {
        val spec = scatterChart(
            title = "散点",
            xValues = listOf(1.0),
            yValues = listOf(2.0),
        )
        assertEquals("数据点", spec.series[0].name)
    }
}
